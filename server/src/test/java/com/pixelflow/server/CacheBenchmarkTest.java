package com.pixelflow.server;

import com.pixelflow.server.content.PostContentCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CACHE_BENCHMARK",matches="true")
class CacheBenchmarkTest {
    @Test void compareSameDatasetOverHttp() throws Exception {
        String url=MySqlBusinessApiTest.testUrl("MYSQL_TEST_CACHE_URL");
        var helper=new PostLifecycleHttpTest();
        var ids=new ArrayList<String>();
        var baseline=new HashMap<String,String>();
        try (var connection=java.sql.DriverManager.getConnection(url,
                System.getenv().getOrDefault("MYSQL_TEST_USER","root"),System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""))) {
            try (var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"users",new String[]{"TABLE"})) {
                if (tables.next()) try (var statement=connection.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM users")) {
                    assertTrue(result.next()); assertEquals(0,result.getInt(1),"benchmark requires an empty dedicated test database");
                }
            }
        }
        for (boolean enabled:List.of(false,true)) {
            try (var app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class).run(
                    "--server.address=127.0.0.1","--server.port=0","--spring.datasource.url="+url,
                    "--spring.datasource.username="+System.getenv().getOrDefault("MYSQL_TEST_USER","root"),
                    "--spring.datasource.password="+System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""),
                    "--pixelflow.seed=false","--pixelflow.admin-password="+helper.password,
                    "--pixelflow.jwt-secret="+helper.password,"--pixelflow.rate-limit.enabled=false",
                    "--pixelflow.local-cache.enabled="+enabled)) {
                helper.port=app.getWebServer().getPort(); helper.db=app.getBean(JdbcTemplate.class);
                try {
                    if (!enabled) {
                        assertEquals(0,helper.db.queryForObject("SELECT COUNT(*) FROM posts",Integer.class),"benchmark requires an empty dedicated test database");
                        helper.usernames.add("admin"); helper.author=helper.register("a","压测作者"); helper.admin=helper.login("admin");
                        for (int i=0;i<100;i++) {
                            String input=helper.json.writeValueAsString(Map.of("title","压测作品"+i,"content","正文".repeat(1000),
                                    "clips",List.of(Map.of("type",0,"width",100,"height",100,"url","https://example.invalid/image.png"))));
                            String id=helper.body(helper.send("POST","/api/posts",helper.author,null,input),201).get("post_id").asText();
                            helper.body(helper.send("POST","/api/posts/"+id+"/publish",helper.author,null,null),200);
                            helper.body(helper.send("POST","/api/admin/posts/"+id+"/moderation",helper.admin,null,
                                    "{\"status\":\"PUBLISHED\",\"reason\":\"本地压测\"}"),200);
                            ids.add(id);
                        }
                    }
                    var requests=new ArrayList<HttpRequest>();
                    for (String id:ids) {
                        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+helper.port+"/api/posts/"+id)).GET().build();
                        requests.add(request);
                        String body=helper.client.send(request,HttpResponse.BodyHandlers.ofString()).body();
                        if (!enabled) baseline.put(id,body); else assertEquals(baseline.get(id),body);
                    }
                    for (int i=0;i<300;i++) assertEquals(200,helper.client.send(requests.get(i%10),HttpResponse.BodyHandlers.discarding()).statusCode());
                    var cache=app.getBean(PostContentCache.class);
                    var before=cache.stats();
                    var executor=Executors.newFixedThreadPool(8);
                    List<Long> times=new ArrayList<>();
                    try {
                        List<Callable<Long>> calls=new ArrayList<>();
                        for (int i=0;i<3000;i++) {
                            int index=i%10==0 ? i%100 : i%10;
                            calls.add(() -> {
                                long start=System.nanoTime();
                                var response=helper.client.send(requests.get(index),HttpResponse.BodyHandlers.discarding());
                                long elapsed=System.nanoTime()-start;
                                assertEquals(200,response.statusCode()); return elapsed;
                            });
                        }
                        for (var future:executor.invokeAll(calls)) times.add(future.get());
                    } finally { executor.shutdownNow(); }
                    Collections.sort(times);
                    var delta=cache.stats().minus(before);
                    System.out.printf(Locale.ROOT,
                            "CACHE_BENCHMARK enabled=%s posts=100 requests=3000 concurrency=8 p50_ms=%.3f p95_ms=%.3f hits=%d misses=%d hit_rate=%.6f average_load_ns=%.0f%n",
                            enabled,times.get(1499)/1e6,times.get(2849)/1e6,delta.hitCount(),delta.missCount(),
                            delta.requestCount()==0 ? 0 : delta.hitRate(),delta.averageLoadPenalty());
                } catch (Throwable failure) {
                    if (!enabled) {
                        try { helper.cleanup(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                    }
                    throw failure;
                } finally { if (enabled) helper.cleanup(); }
            }
        }
    }
}
