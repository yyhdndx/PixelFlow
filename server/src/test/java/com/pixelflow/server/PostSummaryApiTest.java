package com.pixelflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.content.PostSummaryEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:post-summary-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long",
        "pixelflow.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class PostSummaryApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockBean PostSummaryEngine engine;
    private static final String PASSWORD="post-summary-password";
    private static final String LONG_TEXT="宿舍低预算做饭经验。".repeat(30);

    @BeforeEach void clean() {
        for (String table:List.of("notifications","comment_auto_replies","comments","moderation_records","post_media","media",
                "behavior_events","interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
        reset(engine);
    }

    private String token(String name) throws Exception {
        String payload=json.writeValueAsString(Map.of("username",name,"password",PASSWORD));
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password",PASSWORD,"nickname",name))))
                .andExpect(status().isCreated());
        return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(payload)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String published(String owner,String adm,String content) throws Exception {
        String payload=draftPayload(content);

        String id=json.readTree(mvc.perform(post("/api/posts").header("Authorization",owner)
                .contentType("application/json").content(payload))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("post_id").asText();
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",adm)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("status","PUBLISHED","reason","ok"))))
                .andExpect(status().isOk());
        return id;
    }


    /** 用字面量拼请求体，避免 Map.of 嵌套 clips 时括号写错。 */
    private String draftPayload(String content) throws Exception {
        return "{\"title\":\"post\",\"content\":" + json.writeValueAsString(content)
                + ",\"clips\":[{\"type\":0,\"width\":600,\"height\":800,\"url\":\"https://example.com/a.png\"}]}";
    }
    private String admin(String name) throws Exception {
        String token=token(name);
        db.update("UPDATE users SET role='ADMIN' WHERE username='"+name+"'");
        return token;
    }

    @Test void longPostReturnsSummaryAndPoints() throws Exception {
        String owner=token("ps_author"); String adm=admin("ps_admin");
        String post=published(owner,adm,LONG_TEXT);
        when(engine.summarize(anyLong(),any(),any())).thenReturn(Optional.of(
                new PostSummaryEngine.Result("作者分享宿舍低成本做饭的经验。",
                        List.of("用电煮锅","买菜看折扣","食材分装冷冻"),false,"","trace-1")));
        var result=json.readTree(mvc.perform(get("/api/posts/"+post+"/summary").header("Authorization",owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("作者分享宿舍低成本做饭的经验。",result.get("summary").asText());
        assertEquals(3,result.get("points").size());
        assertEquals("用电煮锅",result.get("points").get(0).asText());
    }

    @Test void shortPostRejectedWithoutCallingEngine() throws Exception {
        String owner=token("ps_author2"); String adm=admin("ps_admin2");
        String post=published(owner,adm,"太短了");
        mvc.perform(get("/api/posts/"+post+"/summary").header("Authorization",owner))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(engine);
    }

    @Test void unpublishedPostNotFound() throws Exception {
        String owner=token("ps_author3");
        String payload=draftPayload(LONG_TEXT);

        String post=json.readTree(mvc.perform(post("/api/posts").header("Authorization",owner)
                .contentType("application/json").content(payload)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("post_id").asText();
        mvc.perform(get("/api/posts/"+post+"/summary").header("Authorization",owner))
                .andExpect(status().isNotFound());
    }

    @Test void guestUnauthorizedAndEngineFailureGives503() throws Exception {
        String owner=token("ps_author4"); String adm=admin("ps_admin4");
        String post=published(owner,adm,LONG_TEXT);
        mvc.perform(get("/api/posts/"+post+"/summary")).andExpect(status().isUnauthorized());
        when(engine.summarize(anyLong(),any(),any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/posts/"+post+"/summary").header("Authorization",owner))
                .andExpect(status().isServiceUnavailable());
    }
}
