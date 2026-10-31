package com.pixelflow.server.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.ServerApplication;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class RedisRateLimitIT {
    final String prefix="pf_test:"+UUID.randomUUID();
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newHttpClient();
    final List<ServletWebServerApplicationContext> applications=new ArrayList<>();
    StringRedisTemplate redis;

    ServletWebServerApplicationContext start(String namespace,String... extra) {
        List<String> args=new ArrayList<>(List.of("--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:"+prefix.replace(':','_')+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.username=sa","--spring.datasource.password=","--pixelflow.seed=false","--pixelflow.admin-password=",
                "--pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long","--pixelflow.rate-limit.enabled=true",
                "--pixelflow.rate-limit.namespace="+namespace,"--pixelflow.rate-limit.trusted-proxies=127.0.0.1",
                "--spring.data.redis.host="+System.getenv().getOrDefault("REDIS_TEST_HOST","127.0.0.1"),
                "--spring.data.redis.port="+System.getenv().getOrDefault("REDIS_TEST_PORT","6379"),
                "--spring.data.redis.password="+System.getenv().getOrDefault("REDIS_TEST_PASSWORD",""),
                "--spring.data.redis.username="+System.getenv().getOrDefault("REDIS_TEST_USERNAME",""),
                "--spring.data.redis.database="+System.getenv().getOrDefault("REDIS_TEST_DATABASE","0"),
                "--pixelflow.rate-limit.login-ip.limit=5","--pixelflow.rate-limit.login-ip.window=30s",
                "--pixelflow.rate-limit.login-account.limit=5","--pixelflow.rate-limit.login-account.window=30s",
                "--pixelflow.rate-limit.register-ip.limit=5","--pixelflow.rate-limit.register-ip.window=30s",
                "--pixelflow.rate-limit.upload-user.limit=5","--pixelflow.rate-limit.upload-user.window=30s"));
        for (String override:extra) {
            String name=override.substring(0,override.indexOf('='));
            args.removeIf(argument -> argument.startsWith(name+"="));
            args.add(override);
        }
        var app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class).run(args.toArray(String[]::new));
        applications.add(app); return app;
    }

    @BeforeEach void requireRealRedis() {
        var app=start(prefix);
        redis=app.getBean(StringRedisTemplate.class);
        try (var connection=Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
            assertEquals("PONG",connection.ping(),"Real Redis is mandatory; missing Redis must fail");
        }
    }

    @AfterEach void cleanup() {
        try {
            if (redis!=null) {
                try (var cursor=redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(prefix+"*").count(100).build())) {
                    cursor.forEachRemaining(key -> redis.delete(key));
                }
            }
        } finally {
            Collections.reverse(applications); applications.forEach(ServletWebServerApplicationContext::close);
        }
    }

    HttpResponse<String> post(ServletWebServerApplicationContext app,String path,String body,String ip) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").header("X-Real-IP",ip)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

    void limited(HttpResponse<String> response) throws Exception {
        assertEquals(429,response.statusCode());
        var body=json.readTree(response.body());
        assertEquals("RATE_LIMITED",body.get("code").asText()); assertEquals(429,body.get("status_code").asInt());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(),body.get("requestId").asText());
        assertNotEquals("unknown",body.get("requestId").asText());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())>=1);
    }

    @Test void invalidLoginBodiesCountAndInstancesShareAtomicQuota() throws Exception {
        var first=applications.get(0); var second=start(prefix);
        var executor=Executors.newFixedThreadPool(12); var gate=new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> results=new ArrayList<>();
            for (int i=0;i<12;i++) {
                var app=i%2==0 ? first : second;
                results.add(executor.submit(()->{gate.await(); return post(app,"/api/auth/login","{","198.51.100.1");}));
            }
            gate.countDown(); int entered=0,rejected=0;
            for (var future:results) {
                var response=future.get(15,TimeUnit.SECONDS);
                if (response.statusCode()==429) { limited(response); rejected++; }
                else { assertEquals(400,response.statusCode()); entered++; }
            }
            assertEquals(5,entered); assertEquals(7,rejected);
            assertEquals(400,post(first,"/api/auth/login","{","198.51.100.2").statusCode());
        } finally { executor.shutdownNow(); }
    }

    @Test void registrationRejectionDoesNotWriteUsers() throws Exception {
        var app=applications.get(0); var db=app.getBean(JdbcTemplate.class);
        int before=db.queryForObject("SELECT COUNT(*) FROM users",Integer.class);
        for (int i=0;i<6;i++) {
            String body=json.writeValueAsString(Map.of("username","u"+UUID.randomUUID().toString().replace("-","").substring(0,20),
                    "password","test-password-2026","nickname","测试"));
            var response=post(app,"/api/auth/register",body,"198.51.100.3");
            if (i<5) assertEquals(201,response.statusCode()); else limited(response);
        }
        assertEquals(before+5,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
    }

    @Test void successfulLoginDoesNotResetAndAccountBucketsRemainIndependent() throws Exception {
        var app=start(prefix+":accounts","--pixelflow.rate-limit.login-ip.limit=12");
        String name="alice"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        assertEquals(201,post(app,"/api/auth/register",json.writeValueAsString(Map.of("username",name,"password","test-password-2026","nickname","甲")),"198.51.100.4").statusCode());
        String valid=json.writeValueAsString(Map.of("username",name.toUpperCase(Locale.ROOT),"password","test-password-2026"));
        assertEquals(200,post(app,"/api/auth/login",valid,"198.51.100.4").statusCode());
        String wrong=json.writeValueAsString(Map.of("username",name,"password","wrong-password"));
        for (int i=0;i<4;i++) assertEquals(401,post(app,"/api/auth/login",wrong,"198.51.100.4").statusCode());
        limited(post(app,"/api/auth/login",valid,"198.51.100.4"));
        String other="{\"username\":\"missing_account\",\"password\":\"wrong-password\"}";
        for (int i=0;i<5;i++) assertEquals(401,post(app,"/api/auth/login",other,"198.51.100.4").statusCode());
        limited(post(app,"/api/auth/login",other,"198.51.100.4"));
        limited(post(app,"/api/auth/login","{\"username\":\"another_missing\",\"password\":\"wrong\"}","198.51.100.4"));
        assertEquals(401,post(app,"/api/auth/login",other,"198.51.100.5").statusCode());
    }

    @Test void authenticatedUploadQuotaHasNoRejectedWriteAndIsPerUser() throws Exception {
        var app=applications.get(0);
        List<String> tokens=new ArrayList<>();
        for (int i=0;i<2;i++) {
            String name="upload"+i+UUID.randomUUID().toString().replace("-","").substring(0,10);
            assertEquals(201,post(app,"/api/auth/register",json.writeValueAsString(Map.of("username",name,"password","test-password-2026","nickname","图片")),"198.51.100.6").statusCode());
            var login=post(app,"/api/auth/login",json.writeValueAsString(Map.of("username",name,"password","test-password-2026")),"198.51.100.6");
            assertEquals(200,login.statusCode()); tokens.add(json.readTree(login.body()).get("accessToken").asText());
        }
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();
        bytes.write("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.imageio.ImageIO.write(image,"png",bytes); image.flush();
        bytes.write("\r\n--boundary--\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var db=app.getBean(JdbcTemplate.class); int before=db.queryForObject("SELECT COUNT(*) FROM media",Integer.class);
        for (int i=0;i<8;i++) {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+"/api/media"))
                    .header("Content-Type","multipart/form-data; boundary=boundary").POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()));
            if (i!=0) builder.header("Authorization","Bearer "+tokens.get(i==7 ? 1 : 0));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if (i==0) assertEquals(401,response.statusCode());
            else if (i==6) limited(response); else assertEquals(201,response.statusCode());
        }
        assertEquals(before+6,db.queryForObject("SELECT COUNT(*) FROM media",Integer.class));
    }

    @Test void httpWindowRecoversAndUnrelatedEndpointsRemainAvailable() throws Exception {
        var app=start(prefix+":http-window","--pixelflow.rate-limit.login-ip.limit=1",
                "--pixelflow.rate-limit.login-ip.window=2s","--pixelflow.rate-limit.login-account.limit=1",
                "--pixelflow.rate-limit.login-account.window=2s");
        String name="window"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        String credentials=json.writeValueAsString(Map.of("username",name,"password","test-password-2026"));
        assertEquals(201,post(app,"/api/auth/register",json.writeValueAsString(Map.of("username",name,"password","test-password-2026","nickname","窗口")),"198.51.100.30").statusCode());
        var login=post(app,"/api/auth/login",credentials,"198.51.100.30"); assertEquals(200,login.statusCode());
        var tokens=json.readTree(login.body());
        var rejected=post(app,"/api/auth/login",credentials,"198.51.100.30"); limited(rejected);
        String base="http://127.0.0.1:"+app.getWebServer().getPort();
        for (String path:List.of("/health","/feed/?count=1")) assertEquals(200,http.send(
                HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        String access=tokens.get("accessToken").asText();
        var profile=http.send(HttpRequest.newBuilder(URI.create(base+"/api/users/me/profile"))
                .header("Authorization","Bearer "+access).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(503,profile.statusCode());
        assertEquals("ANALYTICS_UNAVAILABLE",json.readTree(profile.body()).get("code").asText());
        var refreshed=post(app,"/api/auth/refresh",json.writeValueAsString(Map.of("refreshToken",tokens.get("refreshToken").asText())),"198.51.100.30");
        assertEquals(200,refreshed.statusCode());
        var logout=http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/logout"))
                .header("Authorization","Bearer "+json.readTree(refreshed.body()).get("accessToken").asText())
                .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,logout.statusCode());
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        HttpResponse<String> recovered;
        do {
            recovered=post(app,"/api/auth/login",credentials,"198.51.100.30");
            if (recovered.statusCode()!=429) break;
            limited(recovered); Thread.sleep(50);
        } while (System.nanoTime()<deadline);
        assertEquals(200,recovered.statusCode());
    }

    @Test void untrustedForwardedHeadersCannotChangeBuckets() throws Exception {
        var app=start(prefix+":direct","--pixelflow.rate-limit.trusted-proxies=");
        for (int i=0;i<6;i++) {
            var response=post(app,"/api/auth/login","{","198.51.100."+(10+i));
            if (i<5) assertEquals(400,response.statusCode()); else limited(response);
        }
    }

    @Test void limiterResumesAfterConnectionFailure() throws Exception {
        try (var proxy=new RedisProxy(System.getenv().getOrDefault("REDIS_TEST_HOST","127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT","6379")))) {
            var app=start(prefix+":recovery","--spring.data.redis.host=127.0.0.1","--spring.data.redis.port="+proxy.port(),
                    "--spring.data.redis.connect-timeout=150ms","--spring.data.redis.timeout=150ms");
            var limiter=app.getBean(RedisRateLimiter.class);
            var attempt=new RedisRateLimiter.Attempt("recovery",new RateLimitProperties.Rule(1,Duration.ofSeconds(30)),List.of("test"));
            proxy.available=false;
            assertEquals(-1,limiter.retryAfter(List.of(attempt))); assertTrue(limiter.failureCount()>0);
            proxy.available=true;
            long deadline=System.nanoTime()+Duration.ofSeconds(6).toNanos();
            boolean recovered=false;
            while (System.nanoTime()<deadline) {
                if (limiter.retryAfter(List.of(attempt))>0) { recovered=true; break; }
                Thread.sleep(100);
            }
            assertTrue(recovered,"Redis protection must resume after recovery");
        }
    }

    private static final class RedisProxy implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService workers=Executors.newCachedThreadPool();
        final List<Socket> sockets=new CopyOnWriteArrayList<>();
        volatile boolean available=true;
        RedisProxy(String host,int port) throws Exception {
            server=new ServerSocket(0,20,InetAddress.getLoopbackAddress());
            workers.submit(()->{
                while (!server.isClosed()) {
                    try {
                        Socket client=server.accept(); sockets.add(client);
                        if (!available) { client.close(); continue; }
                        Socket upstream=new Socket(host,port); sockets.add(upstream);
                        workers.submit(()->copy(client,upstream)); workers.submit(()->copy(upstream,client));
                    } catch (java.io.IOException error) { if (server.isClosed()) break; }
                }
            });
        }
        int port() { return server.getLocalPort(); }
        void copy(Socket source,Socket target) {
            try { source.getInputStream().transferTo(target.getOutputStream()); }
            catch (java.io.IOException ignored) { }
            finally { try { source.close(); target.close(); } catch (java.io.IOException ignored) { } }
        }
        @Override public void close() throws Exception {
            server.close(); for (Socket socket:sockets) socket.close(); workers.shutdownNow();
        }
    }

    @Test void luaWindowDoesNotSlideAndRepairsPersistentCounters() throws Exception {
        var limiter=applications.get(0).getBean(RedisRateLimiter.class);
        var attempt=new RedisRateLimiter.Attempt("window",new RateLimitProperties.Rule(5,Duration.ofMillis(700)),List.of("identity"));
        for (int i=0;i<5;i++) assertEquals(0,limiter.retryAfter(List.of(attempt)));
        String key=limiter.key(attempt);
        long original=redis.getExpire(key,TimeUnit.MILLISECONDS);
        assertTrue(original>0); assertEquals(1,limiter.retryAfter(List.of(attempt)));
        assertTrue(redis.getExpire(key,TimeUnit.MILLISECONDS)<=original);
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime()<deadline) Thread.sleep(20);
        assertEquals(0,limiter.retryAfter(List.of(attempt)));
        redis.opsForValue().set(key,"7");
        assertEquals(1,limiter.retryAfter(List.of(attempt))); assertTrue(redis.getExpire(key,TimeUnit.MILLISECONDS)>0);
        var longer=new RedisRateLimiter.Attempt("longer",new RateLimitProperties.Rule(1,Duration.ofSeconds(4)),List.of("identity"));
        limiter.retryAfter(List.of(longer)); assertEquals(4,limiter.retryAfter(List.of(attempt,longer)));
    }
}
