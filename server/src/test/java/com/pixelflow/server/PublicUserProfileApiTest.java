package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:public-profile-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class PublicUserProfileApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String alice,bob,aliceId,bobId;
    @BeforeEach void setup() throws Exception {
        for (String table:List.of("recommendation_suppressed_topics","recommendation_blocked_authors","recommendation_preferences",
                "event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events",
                "interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
        alice=account("alice"); bob=account("bob");
        aliceId=db.queryForObject("SELECT id FROM users WHERE username='alice'",String.class);
        bobId=db.queryForObject("SELECT id FROM users WHERE username='bob'",String.class);
    }
    String account(String name) throws Exception {
        var credentials=Map.of("username",name,"password","profile-test-password");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(
                Map.of("username",name,"password",credentials.get("password"),"nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(credentials))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("accessToken").asText();
    }
    String path(String id) { return "/api/users/"+id+"/profile"; }
    JsonNode profile(String token,String id) throws Exception {
        return json.readTree(mvc.perform(get(path(id)).header("Authorization",token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
    }
    void follow(String from,String to) {
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'follows',?)",from,to);
    }
    @Test void publicFieldsOnlyAndOwnProfileIsReadable() throws Exception {
        var empty=profile(alice,bobId);
        assertEquals("",empty.get("bio").asText()); assertEquals("",empty.get("avatar").asText());
        for (String key:List.of("post_count","follower_count","following_count")) assertEquals(0,empty.get(key).asLong());
        Set<String> keys=new HashSet<>(); empty.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("user_id","nickname","bio","avatar","post_count","follower_count","following_count","is_following"),keys);
        assertFalse(empty.get("is_following").asBoolean());
        db.update("UPDATE users SET nickname=?,bio=?,avatar=? WHERE id=?","中文昵称","摄影\n旅行","https://example.com/a.jpg",bobId);
        var result=profile(alice,bobId);
        assertEquals("中文昵称",result.get("nickname").asText()); assertEquals("摄影\n旅行",result.get("bio").asText());
        assertEquals("https://example.com/a.jpg",result.get("avatar").asText());
        assertEquals(bobId,profile(bob,bobId).get("user_id").asText());
    }
    @Test void countsFilterStatusesMissingAndDisabledAccounts() throws Exception {
        for (String state:List.of("DRAFT","PENDING","PUBLISHED","REJECTED","WITHDRAWN"))
            db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,'title','','[]',FALSE,?,1)",bobId,state);
        db.update("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES('disabled','disabled','hash','hidden','CREATOR',FALSE)");
        follow(aliceId,bobId); follow(bobId,aliceId); follow("disabled",bobId); follow(bobId,"disabled"); follow(bobId,"missing");
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'likes',?)",aliceId,bobId);
        var result=profile(alice,bobId);
        for (String key:List.of("post_count","follower_count","following_count")) assertEquals(1,result.get(key).asLong());
        db.update("UPDATE users SET enabled=TRUE WHERE id='disabled'");
        result=profile(alice,bobId);
        assertEquals(2,result.get("follower_count").asLong()); assertEquals(2,result.get("following_count").asLong());
        var ownStats=json.readTree(mvc.perform(get("/api/users/me/stats").header("Authorization",bob))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (String key:List.of("post_count","follower_count","following_count"))
            assertEquals(ownStats.get(key),result.get(key),"Public and own profile counts must agree: "+key);
    }
    @Test void followStateUsesViewerAndLegacyWritesRemainEffective() throws Exception {
        follow(bobId,aliceId);
        assertFalse(profile(alice,bobId).get("is_following").asBoolean());
        assertTrue(profile(bob,aliceId).get("is_following").asBoolean());
        for (boolean active:List.of(true,false)) {
            mvc.perform(put("/api/interactions/follows").header("Authorization",alice).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("targetId",bobId,"active",active)))).andExpect(status().isOk());
            assertEquals(active,profile(alice,bobId).get("is_following").asBoolean());
        }
        mvc.perform(get(path(bobId)).header("Authorization",alice).param("userId",bobId))
                .andExpect(status().isOk()).andExpect(jsonPath("is_following").value(false));
        db.update("INSERT INTO recommendation_blocked_authors(user_id,author_id) VALUES(?,?)",aliceId,bobId);
        assertEquals(bobId,profile(alice,bobId).get("user_id").asText());
    }
    @Test void hiddenAndMissingUsersHaveSame404AndInvalidIdsAreRejected() throws Exception {
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",bobId);
        for (String id:List.of(bobId,"missing"))
            mvc.perform(get(path(id)).header("Authorization",alice)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("code").value("USER_NOT_FOUND"));
        for (String id:List.of("a".repeat(37),"bad.id"))
            mvc.perform(get(path(id)).header("Authorization",alice)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",bobId);
        assertEquals(bobId,profile(alice,bobId).get("user_id").asText());
    }
    @Test void authenticationAndEnabledViewerAreRequired() throws Exception {
        mvc.perform(get(path(bobId))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(bobId)).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        mvc.perform(get(path(bobId)).header("Authorization",alice)).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId);
        db.update("UPDATE sessions SET expires_at=0 WHERE user_id=?",aliceId);
        mvc.perform(get(path(bobId)).header("Authorization",alice)).andExpect(status().isUnauthorized());
    }
}
