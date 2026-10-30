package com.pixelflow.server.ratelimit;

import com.pixelflow.server.ServerApplication;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitFailureTest {
    @Test void disabledLimiterDoesNotConnectEvenWhenEndpointAcceptsConnections() throws Exception {
        try (var probe=new ServerSocket(0,10,InetAddress.getLoopbackAddress())) {
            probe.setSoTimeout(300);
            try (var app=start(false,probe.getLocalPort())) {
                assertEquals(400,login(app).statusCode());
                assertThrows(SocketTimeoutException.class,probe::accept);
                assertEquals(0,app.getBean(RedisRateLimiter.class).failureCount());
            }
        }
    }

    @Test void unavailableRedisDoesNotBlockStartupOrOriginalBusiness() throws Exception {
        int port;
        try (var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        try (var app=start(true,port)) {
            long begin=System.nanoTime();
            for (int i=0;i<2;i++) assertEquals(400,login(app).statusCode());
            assertTrue(Duration.ofNanos(System.nanoTime()-begin).compareTo(Duration.ofSeconds(5))<0);
            assertTrue(app.getBean(RedisRateLimiter.class).failureCount()>=2);
            var factory=app.getBean(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class);
            assertEquals(Duration.ofMillis(150),factory.getClientConfiguration().getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout());
            assertEquals(Duration.ofMillis(150),factory.getClientConfiguration().getCommandTimeout());
            normalBusiness(app);
        }
    }

    @Test void commandTimeoutFailsOpenWithinBoundedTime() throws Exception {
        var executor=Executors.newCachedThreadPool();
        List<Socket> connections=new CopyOnWriteArrayList<>(); var accepted=new AtomicInteger();
        try (var blackhole=new ServerSocket(0,10,InetAddress.getLoopbackAddress())) {
            executor.submit(()->{
                while (!blackhole.isClosed()) {
                    try { connections.add(blackhole.accept()); accepted.incrementAndGet(); }
                    catch (java.io.IOException error) { break; }
                }
            });
            try (var app=start(true,blackhole.getLocalPort())) {
                long begin=System.nanoTime();
                assertEquals(400,login(app).statusCode());
                assertTrue(Duration.ofNanos(System.nanoTime()-begin).compareTo(Duration.ofSeconds(5))<0);
                assertTrue(accepted.get()>0); assertTrue(app.getBean(RedisRateLimiter.class).failureCount()>0);
                normalBusiness(app);
            }
        } finally {
            for (var socket:connections) socket.close(); executor.shutdownNow();
        }
    }

    @Test void partialFailuresDoNotReportRecoveryOrSpamWarnings() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RedisRateLimiter.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        try (var app=start(false,1)) {
            appender.start(); logger.addAppender(appender);
            var limiter=app.getBean(RedisRateLimiter.class);
            limiter.recordOutcome(true); limiter.recordOutcome(true);
            limiter.recordOutcome(false); limiter.recordOutcome(true); limiter.recordOutcome(true);
            assertEquals(1,appender.list.stream().filter(event -> event.getFormattedMessage().startsWith("Rate limiting degraded")).count());
            assertEquals(1,appender.list.stream().filter(event -> event.getFormattedMessage().startsWith("Rate limiting recovered")).count());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    private void normalBusiness(ServletWebServerApplicationContext app) throws Exception {
        String name="failure"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String base="http://127.0.0.1:"+app.getWebServer().getPort();
        var client=HttpClient.newHttpClient();
        String registration="{\"username\":\""+name+"\",\"password\":\"test-password-2026\",\"nickname\":\"测试\"}";
        long begin=System.nanoTime();
        var registered=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/register"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(registration)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(201,registered.statusCode());
        var loggedIn=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/login"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\""+name+"\",\"password\":\"test-password-2026\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,loggedIn.statusCode());
        for (String path:List.of("/health","/feed/?count=1")) assertEquals(200,client.send(
                HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime()-begin).compareTo(Duration.ofSeconds(5))<0);
    }

    private ServletWebServerApplicationContext start(boolean enabled,int redisPort) {
        return (ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class).run(
                "--server.address=127.0.0.1","--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:failure_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.username=sa","--spring.datasource.password=","--pixelflow.seed=false","--pixelflow.admin-password=",
                "--pixelflow.rate-limit.enabled="+enabled,"--spring.data.redis.host=127.0.0.1","--spring.data.redis.port="+redisPort,
                "--spring.data.redis.connect-timeout=150ms","--spring.data.redis.timeout=150ms");
    }

    private HttpResponse<String> login(ServletWebServerApplicationContext app) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+"/api/auth/login"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{")).build(),HttpResponse.BodyHandlers.ofString());
    }
}
