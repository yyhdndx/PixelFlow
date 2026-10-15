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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:pref-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class PreferenceApiTest {
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

    @Test void defaultsAndPersonalizedToggleAreIdempotent() throws Exception {
        String alice=token("alice");
        mvc.perform(get("/api/recommendations/me/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recommendations/me/preferences").header("Authorization",alice))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("personalized").value(true))
                .andExpect(jsonPath("version").value(0))
                .andExpect(jsonPath("excluded_post_ids.length()").value(0))
                .andExpect(jsonPath("blocked_author_ids.length()").value(0))
                .andExpect(jsonPath("suppressed_topics.length()").value(0));
        mvc.perform(put("/api/recommendations/me/preferences").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("personalized",false))))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("version").value(1)).andExpect(jsonPath("personalized").value(false));
        mvc.perform(put("/api/recommendations/me/preferences").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("personalized",false))))
                .andExpect(jsonPath("version").value(1));
        mvc.perform(put("/api/recommendations/me/preferences").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("personalized",true))))
                .andExpect(jsonPath("version").value(2));
    }

    @Test void authorBlocksBumpVersionAndAreIsolatedPerUser() throws Exception {
        String alice=token("alice"), bob=token("bob");
        mvc.perform(put("/api/recommendations/me/blocked-authors/a-1").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("blocked",true))))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("version").value(1)).andExpect(jsonPath("blocked_author_ids[0]").value("a-1"));
        mvc.perform(put("/api/recommendations/me/blocked-authors/a-1").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("blocked",true))))
                .andExpect(jsonPath("version").value(1));
        mvc.perform(get("/api/recommendations/me/preferences").header("Authorization",bob))
                .andExpect(jsonPath("blocked_author_ids.length()").value(0)).andExpect(jsonPath("version").value(0));
        mvc.perform(put("/api/recommendations/me/blocked-authors/a-1").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("blocked",false))))
                .andExpect(jsonPath("version").value(2)).andExpect(jsonPath("blocked_author_ids.length()").value(0));
    }

    @Test void authorBlockLimitReturns409AndIsNotTruncated() throws Exception {
        String alice=token("alice");
        for (int i=0;i<500;i++)
            mvc.perform(put("/api/recommendations/me/blocked-authors/author-"+i).header("Authorization",alice)
                    .contentType("application/json").content(encode(Map.of("blocked",true)))).andExpect(status().isOk());
        mvc.perform(put("/api/recommendations/me/blocked-authors/overflow").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("blocked",true))))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("BLOCK_LIMIT"));
        assertEquals(500,db.queryForObject("SELECT COUNT(*) FROM recommendation_blocked_authors",Integer.class));
    }

    @Test void topicSuppressionIsValidatedAndReflectedInProfile() throws Exception {
        String alice=token("alice");
        mvc.perform(put("/api/users/me/profile/topics/未知").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("suppressed",true))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("UNSUPPORTED_TOPIC"));
        mvc.perform(put("/api/users/me/profile/topics/美食").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("suppressed",true))))
                .andExpect(status().isOk()).andExpect(jsonPath("version").value(1))
                .andExpect(jsonPath("suppressed_topics[0]").value("美食"));
        mvc.perform(put("/api/users/me/profile/topics/美食").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("suppressed",true))))
                .andExpect(jsonPath("version").value(1));
        mvc.perform(put("/api/users/me/profile/topics/美食").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("suppressed",false))))
                .andExpect(jsonPath("version").value(2));
    }
}
