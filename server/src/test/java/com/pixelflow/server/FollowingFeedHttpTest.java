package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FollowingFeedHttpTest {
    final ObjectMapper json=new ObjectMapper();
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    int port;

    @Test void followsPaginationAndUnfollowOverHttp() throws Exception {
        try (var app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class).run(
                "--server.address=127.0.0.1","--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:following_http_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.username=sa","--spring.datasource.password=",
                "--pixelflow.seed=false","--pixelflow.admin-password=","--pixelflow.rate-limit.enabled=false",
                "--pixelflow.jwt-secret="+UUID.randomUUID())) {
            port=app.getWebServer().getPort();
            var db=app.getBean(JdbcTemplate.class);
            String author=account("a"),viewer=account("b");
            String authorId=send("GET","/api/users/me",author,null,200).get("id").asText();
            try {
                send("GET","/api/feed/following",null,null,401);
                assertTrue(send("GET","/api/feed/following",viewer,null,200).get("post_list").isEmpty());
                for (long id:List.of(9L,10L,100L)) db.update(
                        "INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,'中文作品','正文','[]',FALSE,'PUBLISHED',1)",id,authorId);
                String follow=json.writeValueAsString(Map.of("targetId",authorId,"active",true));
                send("PUT","/api/interactions/follows",viewer,follow,200);
                var first=send("GET","/api/feed/following?count=2",viewer,null,200);
                assertEquals(List.of("100","10"),ids(first)); assertEquals(1,first.get("has_more").asInt());
                var last=send("GET","/api/feed/following?count=2&cursor="+first.get("next_cursor").asText(),viewer,null,200);
                assertEquals(List.of("9"),ids(last)); assertTrue(last.get("next_cursor").isNull());
                send("PUT","/api/interactions/follows",viewer,json.writeValueAsString(Map.of("targetId",authorId,"active",false)),200);
                assertTrue(send("GET","/api/feed/following",viewer,null,200).get("post_list").isEmpty());
            } finally {
                db.update("DELETE FROM interactions"); db.update("DELETE FROM posts");
                db.update("DELETE FROM sessions"); db.update("DELETE FROM users");
            }
        }
    }

    String account(String suffix) throws Exception {
        String name="ff_http_"+UUID.randomUUID().toString().replace("-","").substring(0,12)+suffix;
        send("POST","/api/auth/register",null,json.writeValueAsString(Map.of("username",name,"password","following-http-password","nickname","中文作者")),201);
        return "Bearer "+send("POST","/api/auth/login",null,json.writeValueAsString(Map.of("username",name,"password","following-http-password")),200).get("accessToken").asText();
    }
    List<String> ids(JsonNode page) {
        var result=new ArrayList<String>(); page.get("post_list").forEach(card -> result.add(card.get("post_id").asText())); return result;
    }
    JsonNode send(String method,String path,String token,String body,int status) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if (token!=null) request.header("Authorization",token);
        if (body!=null) request.header("Content-Type","application/json");
        var response=client.send(request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(status,response.statusCode(),response.body());
        if (status==200 && path.startsWith("/api/feed/following")) assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        return json.readTree(response.body());
    }
}
