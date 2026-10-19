package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:stats-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class UserStatsApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private static final String PASSWORD="test-password-2026";
    @BeforeEach void clean() {
        for (String t:List.of("recommendation_suppressed_topics","recommendation_blocked_authors","recommendation_preferences",
                "event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events",
                "interactions","posts","sessions","users")) db.update("DELETE FROM "+t);
    }
    private String encode(Object v) throws Exception { return json.writeValueAsString(v); }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private String token(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(encode(Map.of("username",name,"password",PASSWORD,"nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(encode(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk()).andReturn())
                .get("accessToken").asText();
    }
    private String userId(String name) { return db.queryForObject("SELECT id FROM users WHERE username=?",String.class,name); }
    private void insertUser(String id,String username,boolean enabled) {
        db.update("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES(?,?,?,?,?,?)",
                id,username,"x",username,"CREATOR",enabled);
    }
    private void insertPost(String authorId,String status) {
        db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,FALSE,?,0)",
                authorId,"t","c","[]",status);
    }
    private void follow(String followerId,String targetId) {
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'follows',?)",followerId,targetId);
    }

    @Test void newAccountHasZeroCounts() throws Exception {
        String alice=token("alice");
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("user_id").value(userId("alice")))
                .andExpect(jsonPath("post_count").value(0))
                .andExpect(jsonPath("follower_count").value(0))
                .andExpect(jsonPath("following_count").value(0));
    }

    @Test void anonymousAndInvalidTokenGet401() throws Exception {
        mvc.perform(get("/api/users/me/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/stats").header("Authorization","Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test void onlyPublishedPostsAreCounted() throws Exception {
        String alice=token("alice"), id=userId("alice");
        for (String status:List.of("DRAFT","PENDING","PUBLISHED","REJECTED","WITHDRAWN","PUBLISHED"))
            insertPost(id,status);
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("post_count").value(2))
                .andExpect(jsonPath("follower_count").value(0))
                .andExpect(jsonPath("following_count").value(0));
    }

    @Test void followAndUnfollowUpdateBothSides() throws Exception {
        String alice=token("alice"), bob=token("bob");
        String aliceId=userId("alice"), bobId=userId("bob");
        follow(aliceId,bobId);
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(jsonPath("following_count").value(1))
                .andExpect(jsonPath("follower_count").value(0));
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob))
                .andExpect(jsonPath("follower_count").value(1))
                .andExpect(jsonPath("following_count").value(0));
        mvc.perform(put("/api/interactions/follows").header("Authorization",alice).contentType("application/json")
                .content(encode(Map.of("targetId",bobId,"active",false)))).andExpect(status().isOk());
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice)).andExpect(jsonPath("following_count").value(0));
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob)).andExpect(jsonPath("follower_count").value(0));
    }

    @Test void countsAreNotTruncatedBeyondPageSize() throws Exception {
        String alice=token("alice"), bob=token("bob");
        String bobId=userId("bob");
        for (int i=0;i<60;i++) { String fid="follower-"+i; insertUser(fid,"fan"+i,true); follow(fid,bobId); }
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob))
                .andExpect(jsonPath("follower_count").value(60));
        String aliceId=userId("alice");
        for (int i=0;i<55;i++) { String tid="star-"+i; insertUser(tid,"star"+i,true); follow(aliceId,tid); }
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(jsonPath("following_count").value(55));
    }

    @Test void disabledAccountsAreNotCounted() throws Exception {
        String alice=token("alice"), bob=token("bob");
        String aliceId=userId("alice"), bobId=userId("bob");
        insertUser("ghost-f","ghostf",false);
        insertUser("ghost-t","ghostt",false);
        follow("ghost-f",bobId);
        follow(aliceId,"ghost-t");
        follow(aliceId,bobId);
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob))
                .andExpect(jsonPath("follower_count").value(1));
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(jsonPath("following_count").value(1));
    }
}
