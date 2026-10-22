package com.pixelflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UserPostsHttpTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newHttpClient();

    @Test void realHttpBindingPaginationAndWithdrawal() throws Exception {
        try (var application=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class)
                .run("--server.port=0","--spring.datasource.url=jdbc:h2:mem:user-posts-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                        "--spring.datasource.username=sa","--spring.datasource.password=","--pixelflow.seed=false",
                        "--pixelflow.admin-password=","--pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long")) {
            int port=application.getWebServer().getPort();
            String credentials="{\"username\":\"posts_http\",\"password\":\"test-password-2026\"}";
            assertEquals(201,send(port,"POST","/api/auth/register",null,
                    "{\"username\":\"posts_http\",\"password\":\"test-password-2026\",\"nickname\":\"本地作者\"}").statusCode());
            var login=send(port,"POST","/api/auth/login",null,credentials);
            assertEquals(200,login.statusCode());
            String auth="Bearer "+json.readTree(login.body()).get("accessToken").asText();
            var db=application.getBean(JdbcTemplate.class);
            String id=db.queryForObject("SELECT id FROM users WHERE username='posts_http'",String.class);
            String path="/api/users/"+id+"/posts";
            for (String parameter:List.of("count=","count","cursor=","cursor")) {
                var response=send(port,"GET",path+"?"+parameter,auth,null);
                assertEquals(400,response.statusCode(),parameter);
                assertEquals("INVALID_ARGUMENT",json.readTree(response.body()).get("code").asText());
            }
            for (long postId:List.of(9L,10L,100L))
                db.update("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,'中文正文','[]',FALSE,'PUBLISHED',1)",postId,id,"作品"+postId);
            var first=send(port,"GET",path+"?count=2",auth,null);
            assertEquals(200,first.statusCode()); assertEquals("no-store",first.headers().firstValue("Cache-Control").orElseThrow());
            var firstPage=json.readTree(first.body());
            assertEquals("100",firstPage.get("post_list").get(0).get("post_id").asText());
            assertEquals("10",firstPage.get("next_cursor").asText()); assertEquals(1,firstPage.get("has_more").asInt());
            var last=send(port,"GET",path+"?count=2&cursor=10",auth,null);
            assertEquals(200,last.statusCode());
            var lastPage=json.readTree(last.body());
            assertEquals(1,lastPage.get("post_list").size()); assertEquals("9",lastPage.get("post_list").get(0).get("post_id").asText());
            assertEquals(0,lastPage.get("has_more").asInt()); assertTrue(lastPage.get("next_cursor").isNull());
            var withdraw=send(port,"POST","/api/posts/100/withdraw",auth,null);
            assertEquals(200,withdraw.statusCode());
            var after=send(port,"GET",path+"?count=2",auth,null);
            assertEquals(200,after.statusCode());
            var afterPage=json.readTree(after.body());
            assertEquals("10",afterPage.get("post_list").get(0).get("post_id").asText());
            assertEquals("9",afterPage.get("post_list").get(1).get("post_id").asText());
            assertEquals(0,afterPage.get("has_more").asInt()); assertTrue(afterPage.get("next_cursor").isNull());
            System.out.println("USER_POSTS_HTTP_FIRST="+first.body().replace(id,"http-author"));
            System.out.println("USER_POSTS_HTTP_LAST="+last.body().replace(id,"http-author"));
            System.out.println("USER_POSTS_HTTP_WITHDRAW="+withdraw.body());
            System.out.println("USER_POSTS_HTTP_AFTER="+after.body().replace(id,"http-author"));
        }
    }

    private HttpResponse<String> send(int port,String method,String path,String token,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if (token!=null) builder.header("Authorization",token);
        if (body!=null) builder.header("Content-Type","application/json; charset=utf-8");
        return client.send(builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
