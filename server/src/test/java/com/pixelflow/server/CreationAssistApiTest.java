package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:creation-assist;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class CreationAssistApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    String alice, bob;

    @BeforeEach void setup() throws Exception {
        for (String table : List.of("recommendation_suppressed_topics","recommendation_blocked_authors","recommendation_preferences",
                "event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events",
                "interactions","post_creation_idempotency","posts","sessions","users"))
            db.update("DELETE FROM " + table);
        alice = account("assist_alice");
        bob = account("assist_bob");
    }

    String account(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content(
                json.writeValueAsString(Map.of("username", name, "password", "assist-password", "nickname", name))))
                .andExpect(status().isCreated());
        return "Bearer " + json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json").content(
                json.writeValueAsString(Map.of("username", name, "password", "assist-password"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }

    String createDraft(String token) throws Exception {
        var result = mvc.perform(post("/api/posts").header("Authorization", token).contentType("application/json")
                .content("{\"title\":\"草稿\",\"content\":\"正文\"}")).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("post_id").asText();
    }

    @Test void applyAssistUpdatesContentAndBumpsVersion() throws Exception {
        String id = createDraft(alice);
        mvc.perform(post("/api/posts/" + id + "/assist-application").header("Authorization", alice)
                .contentType("application/json").content("{\"title\":\"新标题\",\"content\":\"新正文\",\"expected_version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.content").value("新正文")).andExpect(jsonPath("$.content_version").value(1));
    }

    @Test void applyAssistStaleVersionConflicts() throws Exception {
        String id = createDraft(alice);
        String body = "{\"title\":\"新标题\",\"content\":\"新正文\",\"expected_version\":0}";
        mvc.perform(post("/api/posts/" + id + "/assist-application").header("Authorization", alice)
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/posts/" + id + "/assist-application").header("Authorization", alice)
                .contentType("application/json").content("{\"title\":\"再次\",\"content\":\"再次\",\"expected_version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test void applyAssistRejectsNonOwner() throws Exception {
        String id = createDraft(alice);
        mvc.perform(post("/api/posts/" + id + "/assist-application").header("Authorization", bob)
                .contentType("application/json").content("{\"title\":\"新标题\",\"content\":\"新正文\",\"expected_version\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test void createAssistRequiresAuth() throws Exception {
        mvc.perform(post("/api/agent/create-assist").contentType("application/json")
                .content("{\"draft\":{\"title\":\"\",\"content\":\"\",\"tags\":[]},\"goal\":\"all\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void createAssistUnavailableWhenIntelligenceNotConfigured() throws Exception {
        mvc.perform(post("/api/agent/create-assist").header("Authorization", alice).contentType("application/json")
                .content("{\"draft\":{\"title\":\"\",\"content\":\"\",\"tags\":[]},\"goal\":\"all\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ASSIST_UNAVAILABLE"));
    }

    @Test void createAssistRejectsUnknownGoal() throws Exception {
        mvc.perform(post("/api/agent/create-assist").header("Authorization", alice).contentType("application/json")
                .content("{\"draft\":{\"title\":\"\",\"content\":\"\",\"tags\":[]},\"goal\":\"hack\"}"))
                .andExpect(status().isBadRequest());
    }
}
