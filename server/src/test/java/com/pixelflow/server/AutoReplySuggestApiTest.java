package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.comment.AutoReplySuggester;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:auto-suggest-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long",
        "pixelflow.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class AutoReplySuggestApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockBean AutoReplySuggester suggester;
    private static final String PASSWORD="auto-suggest-password";

    @BeforeEach void clean() {
        for (String table:List.of("notifications","comment_auto_replies","comments","moderation_records","post_media","media",
                "behavior_events","interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
        reset(suggester);
    }

    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private String token(String name) throws Exception {
        String payload=json.writeValueAsString(Map.of("username",name,"password",PASSWORD));
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(payload)).andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }
    private String account(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password",PASSWORD,"nickname",name))))
                .andExpect(status().isCreated());
        return token(name);
    }
    private String draft(String owner) throws Exception {
        String payload="{\"title\":\"手冲咖啡豆\",\"content\":\"中度烘焙\",\"clips\":[{\"type\":0,\"width\":600,\"height\":800,\"url\":\"https://example.com/a.png\"}]}";
        return body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(payload)).andExpect(status().isCreated()).andReturn()).get("post_id").asText();
    }
    private MvcResult suggest(String token,String postId,String prompt) throws Exception {
        return mvc.perform(post("/api/posts/"+postId+"/auto-replies/suggest").header("Authorization",token)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("prompt",prompt)))).andReturn();
    }

    @Test void authorGetsSuggestedRules() throws Exception {
        String owner=account("sg_author"); String post=draft(owner);
        when(suggester.suggest(anyLong(),any(),any(),eq("价格 99 元，48 小时发货")))
                .thenReturn(Optional.of(new AutoReplySuggester.Result(List.of(
                        new AutoReplySuggester.Rule("有人问价格","99 元"),
                        new AutoReplySuggester.Rule("有人问发货时间","48 小时内发出")),false,"","trace-9")));
        var result=body(suggest(owner,post,"价格 99 元，48 小时发货"));
        assertEquals(2,result.get("suggestions").size());
        assertEquals("有人问价格",result.get("suggestions").get(0).get("question").asText());
        assertEquals("48 小时内发出",result.get("suggestions").get(1).get("answer").asText());
        assertFalse(result.get("degraded").asBoolean());
        verify(suggester).suggest(eq(Long.parseLong(post)),eq("手冲咖啡豆"),eq("中度烘焙"),eq("价格 99 元，48 小时发货"));
    }

    @Test void nonAuthorForbiddenAndGuestUnauthorized() throws Exception {
        String owner=account("sg_author2"); String other=account("sg_other2"); String post=draft(owner);
        assertEquals(403,suggest(other,post,"随便写写").getResponse().getStatus());
        mvc.perform(post("/api/posts/"+post+"/auto-replies/suggest").contentType("application/json")
                .content(json.writeValueAsString(Map.of("prompt","x")))).andExpect(status().isUnauthorized());
        verifyNoInteractions(suggester);
    }

    @Test void agentUnavailableGives503() throws Exception {
        String owner=account("sg_author3"); String post=draft(owner);
        when(suggester.suggest(anyLong(),any(),any(),any())).thenReturn(Optional.empty());
        assertEquals(503,suggest(owner,post,"价格 99 元").getResponse().getStatus());
    }

    @Test void blankPromptRejected() throws Exception {
        String owner=account("sg_author4"); String post=draft(owner);
        assertEquals(400,suggest(owner,post,"   ").getResponse().getStatus());
        verifyNoInteractions(suggester);
    }
}
