package com.pixelflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:profile-gateway;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password="})
@AutoConfigureMockMvc
class ProfileGatewayTest {
    private static volatile String response="{}", receivedToken;
    private static volatile int status=200;
    private static final HttpServer UPSTREAM=createServer();
    private static HttpServer createServer() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/profiles/me",exchange -> {
                receivedToken=exchange.getRequestHeaders().getFirst("Authorization");
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length);
                try(var output=exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start(); return server;
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("pixelflow.intelligence-base-url",() -> "http://127.0.0.1:"+UPSTREAM.getAddress().getPort());
    }
    @AfterAll static void stop() { UPSTREAM.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;

    @Test void profileMergesSuppressedTopicsAndFiltersThem() throws Exception {
        status=200;
        String creds=json.writeValueAsString(Map.of("username","suppress_user","password","test-password-2026","nickname","S"));
        mvc.perform(post("/api/auth/register").contentType("application/json").content(creds)).andExpect(status().isCreated());
        var login=mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username","suppress_user","password","test-password-2026"))))
                .andExpect(status().isOk()).andReturn();
        String token="Bearer "+json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        String id=json.readTree(mvc.perform(get("/api/users/me").header("Authorization",token)).andReturn()
                .getResponse().getContentAsString()).get("id").asText();
        response="{\"user_id\":\""+id+"\",\"version\":1,\"topics\":[{\"name\":\"美食\",\"score\":3.0,\"share\":1.0},{\"name\":\"宠物\",\"score\":1.0,\"share\":0.5}]}";
        db.update("INSERT INTO recommendation_suppressed_topics(user_id,topic) VALUES(?,?)", id, "美食");
        mvc.perform(get("/api/users/me/profile").header("Authorization",token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("suppressed_topics[0]").value("美食"))
                .andExpect(jsonPath("topics.length()").value(1))
                .andExpect(jsonPath("topics[0].name").value("宠物"));
    }

    @Test void gatewayChecksIdentityAndHandlesUnavailableAnalytics() throws Exception {
        mvc.perform(get("/api/users/me/profile")).andExpect(status().isUnauthorized());
        String credentials=json.writeValueAsString(Map.of("username","gateway_user","password","test-password-2026","nickname","Gateway"));
        mvc.perform(post("/api/auth/register").contentType("application/json").content(credentials)).andExpect(status().isCreated());
        var login=mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username","gateway_user","password","test-password-2026"))))
                .andExpect(status().isOk()).andReturn();
        String token="Bearer "+json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        var me=mvc.perform(get("/api/users/me").header("Authorization",token)).andReturn();
        String id=json.readTree(me.getResponse().getContentAsString()).get("id").asText();
        response=json.writeValueAsString(Map.of("user_id",id,"version",1,"topics",java.util.List.of()));
        mvc.perform(get("/api/users/me/profile").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("user_id").value(id))
                .andExpect(header().string("Cache-Control","no-store"));
        assertEquals(token,receivedToken);
        response="{\"user_id\":\"someone-else\",\"version\":1,\"topics\":[]}";
        mvc.perform(get("/api/users/me/profile").header("Authorization",token)).andExpect(status().isBadGateway());
        status=503;
        mvc.perform(get("/api/users/me/profile").header("Authorization",token)).andExpect(status().isServiceUnavailable());
        status=401;
        mvc.perform(get("/api/users/me/profile").header("Authorization",token)).andExpect(status().isUnauthorized());
    }
}
