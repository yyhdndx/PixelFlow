package com.pixelflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class UserProfileHttpTest {
    @TempDir Path directory;
    private final ObjectMapper json=new ObjectMapper();

    @Test void liveHttpWriteSurvivesApplicationRestart() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("profile-http").toString().replace('\\','/')
                +";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        var client=HttpClient.newHttpClient();
        String login="{\"username\":\"profile_http\",\"password\":\"test-password-2026\"}";
        try (var application=start(url)) {
            int port=application.getWebServer().getPort();
            var register=request(port,"POST","/api/auth/register",null,
                    "{\"username\":\"profile_http\",\"password\":\"test-password-2026\",\"nickname\":\"initial\"}");
            assertEquals(201,client.send(register,HttpResponse.BodyHandlers.ofString()).statusCode());
            String auth="Bearer "+token(client,port,login);
            var initial=client.send(request(port,"GET","/api/users/me",auth,null),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,initial.statusCode()); assertEquals("no-store",initial.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("",json.readTree(initial.body()).get("bio").asText());
            var updated=client.send(request(port,"PUT","/api/users/me",auth,"{\"nickname\":\" New \" ,\"bio\":\"中文\\n😀\"}"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200,updated.statusCode()); assertEquals("no-store",updated.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("New",json.readTree(updated.body()).get("nickname").asText());
            var invalid=client.send(request(port,"PUT","/api/users/me",auth,"{\"nickname\":42,\"bio\":\"\"}"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400,invalid.statusCode()); assertEquals("INVALID_ARGUMENT",json.readTree(invalid.body()).get("code").asText());
        }
        try (var application=start(url)) {
            int port=application.getWebServer().getPort();
            String auth="Bearer "+token(client,port,login);
            var restored=client.send(request(port,"GET","/api/users/me",auth,null),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,restored.statusCode());
            assertEquals("no-store",restored.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("中文\n😀",json.readTree(restored.body()).get("bio").asText());
            assertEquals("New",json.readTree(restored.body()).get("nickname").asText());
        }
    }

    private ServletWebServerApplicationContext start(String url) {
        return (ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class)
                .run("--server.port=0", "--spring.datasource.url="+url,"--spring.datasource.username=sa",
                        "--spring.datasource.password=", "--pixelflow.seed=false", "--pixelflow.admin-password=",
                        "--pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long");
    }

    private String token(HttpClient client,int port,String login) throws Exception {
        var response=client.send(request(port,"POST","/api/auth/login",null,login),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        return json.readTree(response.body()).get("accessToken").asText();
    }

    private HttpRequest request(int port,String method,String path,String token,String body) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if (token!=null) builder.header("Authorization",token);
        if (body!=null) builder.header("Content-Type","application/json; charset=utf-8");
        return builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
    }
}
