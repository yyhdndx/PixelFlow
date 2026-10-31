package com.pixelflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PostIdempotencyRestartTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient client=HttpClient.newHttpClient();
    @Test void fileDatabaseUpgradeAndActualHttpServerRestart() throws Exception {
        exercise("jdbc:h2:file:"+directory.resolve("posts").toAbsolutePath().toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE","sa","","h2");
    }
    void exercise(String url,String user,String password,String vendor) throws Exception {
        var flyway=Flyway.configure().dataSource(url,user,password).locations("classpath:db/"+vendor).target("7").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        try (var connection=DriverManager.getConnection(url,user,password); var sql=connection.createStatement()) {
            if (vendor.equals("mysql")) {
                try (var version=sql.executeQuery("SELECT VERSION()")) { assertTrue(version.next()); assertTrue(version.getString(1).startsWith("8.4.")); }
            }
            sql.executeUpdate("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES('legacy','legacy','hash','legacy','CREATOR',TRUE)");
            sql.executeUpdate("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(70,'legacy','旧作品','正文','[]',FALSE,'DRAFT',1)");
            sql.executeUpdate("INSERT INTO media(id,owner_id,width,height,content_bytes,created_at) VALUES('legacy-media','legacy',1,1,X'01',1)");
            sql.executeUpdate("INSERT INTO post_media(post_id,media_id,position) VALUES(70,'legacy-media',0)");
        }
        String key=UUID.randomUUID().toString(),auth,id;
        String body="{\"title\":\"重启作品\",\"content\":\"正文\"}";
        try (var app=start(url,user,password)) {
            int port=app.getWebServer().getPort(); var db=app.getBean(JdbcTemplate.class);
            assertEquals("旧作品",db.queryForObject("SELECT title FROM posts WHERE id=70",String.class));
            assertEquals("legacy-media",db.queryForObject("SELECT media_id FROM post_media WHERE post_id=70",String.class));
            assertEquals("10",db.queryForObject("SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank DESC LIMIT 1",String.class));
            assertEquals(201,send(port,"/api/auth/register",null,null,"{\"username\":\"restart\",\"password\":\"restart-password\",\"nickname\":\"重启\"}").statusCode());
            var login=send(port,"/api/auth/login",null,null,"{\"username\":\"restart\",\"password\":\"restart-password\"}");
            assertEquals(200,login.statusCode()); auth="Bearer "+json.readTree(login.body()).get("accessToken").asText();
            var first=send(port,"/api/posts",auth,key,body); assertEquals(201,first.statusCode());
            id=json.readTree(first.body()).get("post_id").asText();
            // The caller discards the first response and retries the original logical request.
            var retry=send(port,"/api/posts",auth,key,body); assertEquals(200,retry.statusCode());
            assertEquals(id,json.readTree(retry.body()).get("post_id").asText());
            assertEquals(400,send(port,"/api/posts",auth,"",body).statusCode());
            var duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/posts"))
                    .header("Authorization",auth).header("Content-Type","application/json").header("Idempotency-Key",key).header("Idempotency-Key",key)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(400,client.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        try (var app=start(url,user,password)) {
            var replay=send(app.getWebServer().getPort(),"/api/posts",auth,key,body);
            assertEquals(200,replay.statusCode(),replay.body()); assertEquals(id,json.readTree(replay.body()).get("post_id").asText());
            var db=app.getBean(JdbcTemplate.class);
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM posts",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post_creation_idempotency",Integer.class));
            System.out.println("POST_IDEMPOTENCY_RESTART vendor="+vendor+" first=201 lost-response-retry=200 restarted-replay=200 same-id=true legacy-post-and-media-preserved=true");
        }
    }
    ServletWebServerApplicationContext start(String url,String user,String password) {
        return (ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class).run(
                "--server.port=0","--spring.datasource.url="+url,"--spring.datasource.username="+user,"--spring.datasource.password="+password,
                "--pixelflow.seed=false","--pixelflow.admin-password=","--pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long");
    }
    HttpResponse<String> send(int port,String path,String auth,String key,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if (auth!=null) request.header("Authorization",auth);
        if (key!=null) request.header("Idempotency-Key",key);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
