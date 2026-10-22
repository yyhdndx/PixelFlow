package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:favorites-http-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FavoritePostsHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    final HttpClient client=HttpClient.newHttpClient();

    JsonNode request(String method,String path,String token,Object input,int expected) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(10));
        if (token!=null) builder.header("Authorization",token);
        if (input!=null) builder.header("Content-Type","application/json");
        builder.method(method,input==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),response.body());
        if (path.startsWith("/api/users/me/favorites")) {
            String cacheControl=response.headers().firstValue("Cache-Control").orElseThrow();
            if (expected==200) assertEquals("no-store",cacheControl);
            else assertTrue(Arrays.asList(cacheControl.split(",\\s*")).contains("no-store"));
        }
        return json.readTree(response.body());
    }

    @Test void registerLoginFavoritePaginateCancelAndReadOverHttp() throws Exception {
        var credentials=Map.of("username","http_favorites","password","favorites-http-password");
        request("POST","/api/auth/register",null,Map.of("username",credentials.get("username"),"password",credentials.get("password"),"nickname","中文收藏"),201);
        String token="Bearer "+request("POST","/api/auth/login",null,credentials,200).get("accessToken").asText();
        String owner=db.queryForObject("SELECT id FROM users WHERE username='http_favorites'",String.class);
        for (long id:List.of(9L,10L,100L)) {
            db.update("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,'HTTP作品','','[]',FALSE,'PUBLISHED',0)",id,owner);
            request("PUT","/api/interactions/favorites",token,Map.of("targetId",Long.toString(id),"active",true),200);
        }
        var first=request("GET","/api/users/me/favorites?count=2",token,null,200);
        assertEquals("100",first.get("post_list").get(0).get("post_id").asText());
        assertEquals("10",first.get("post_list").get(1).get("post_id").asText());
        assertEquals(1,first.get("has_more").asInt()); assertEquals("10",first.get("next_cursor").asText());
        var padded=request("GET","/api/users/me/favorites?count=0001&cursor=00010",token,null,200);
        assertEquals(1,padded.get("post_list").size());
        assertEquals("9",padded.get("post_list").get(0).get("post_id").asText());
        assertEquals(0,padded.get("has_more").asInt()); assertTrue(padded.get("next_cursor").isNull());
        var last=request("GET","/api/users/me/favorites?count=2&cursor="+first.get("next_cursor").asText(),token,null,200);
        assertEquals(1,last.get("post_list").size()); assertEquals("9",last.get("post_list").get(0).get("post_id").asText());
        assertEquals(0,last.get("has_more").asInt()); assertTrue(last.get("next_cursor").isNull());
        request("PUT","/api/interactions/favorites",token,Map.of("targetId","100","active",false),200);
        var after=request("GET","/api/users/me/favorites",token,null,200);
        assertEquals(2,after.get("post_list").size()); assertEquals("10",after.get("post_list").get(0).get("post_id").asText());
        assertEquals(0,after.get("has_more").asInt()); assertTrue(after.get("next_cursor").isNull());
        for (String query:List.of("?cursor=","?cursor","?count=","?count","?count=1&count=2","?cursor=9&cursor=10"))
            assertEquals("INVALID_ARGUMENT",request("GET","/api/users/me/favorites"+query,token,null,400).get("code").asText());
    }
}
