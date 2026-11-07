package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.copilot.CopilotAdvisor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:copilot-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long",
        "pixelflow.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class CopilotApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockBean CopilotAdvisor advisor;

    private static final String PASSWORD="copilot-password";

    @BeforeEach void clean() {
        for (String table:List.of("conversation_reads","messages","conversations","notifications","comment_auto_replies",
                "comments","moderation_records","post_media","media","behavior_events","interactions","posts","sessions","users"))
            db.update("DELETE FROM "+table);
        reset(advisor);
    }

    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private String encode(Object v) throws Exception { return json.writeValueAsString(v); }

    private String token(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("username",name,"password",PASSWORD,"nickname",name))))
                .andExpect(status().isCreated());
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk())
                .andReturn()).get("accessToken").asText();
    }

    private String userId(String token) throws Exception {
        return body(mvc.perform(get("/api/users/me").header("Authorization",token)).andExpect(status().isOk())
                .andReturn()).get("id").asText();
    }

    private String draftPayload(String content) throws Exception {
        return "{\"title\":\"草稿\",\"content\":" + json.writeValueAsString(content)
                + ",\"clips\":[{\"type\":0,\"width\":600,\"height\":800,\"url\":\"https://example.com/a.png\"}]}";
    }

    private String createPost(String owner) throws Exception {
        return body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
                .content(draftPayload("宿舍做饭长文。" .repeat(20)))).andExpect(status().isCreated())
                .andReturn()).get("post_id").asText();
    }

    private String publishedPost(String owner,String admin) throws Exception {
        String id=createPost(owner);
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("status","PUBLISHED","reason","ok")))).andExpect(status().isOk());
        return id;
    }

    private String admin(String name) throws Exception {
        String token=token(name);
        db.update("UPDATE users SET role='ADMIN' WHERE username='"+name+"'");
        return token;
    }

    private String conversation(String owner,String targetId) throws Exception {
        return body(mvc.perform(post("/api/conversations").header("Authorization",owner)
                .contentType(MediaType.APPLICATION_JSON).content(encode(Map.of("targetUserId",targetId))))
                .andExpect(status().isOk()).andReturn()).get("conversation_id").asText();
    }

    private MvcResult copilot(String token,String payload) throws Exception {
        return mvc.perform(post("/api/agent/copilot").header("Authorization",token)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
    }

    @Test void detailScreenUsesServerContextAndFiltersActions() throws Exception {
        String owner=token("cp_author"), adm=admin("cp_admin"), reader=token("cp_reader");
        String postId=publishedPost(owner,adm);
        when(advisor.chat(eq("detail"),anyMap(),eq("帮我写条评论"),anyList())).thenReturn(Optional.of(
                new CopilotAdvisor.Result("给你写了一条。", List.of(
                        Map.of("type","POST_COMMENT","text","300W 够用吗？","rationale","提问"),
                        Map.of("type","POST_COMMENT","text","长".repeat(501)),
                        Map.of("type","UPDATE_DRAFT","content","越界动作")), "trace-1")));

        MvcResult result=copilot(reader,"{\"screen\":\"detail\",\"postId\":\""+postId+"\",\"message\":\"帮我写条评论\"}");
        assertEquals(200,result.getResponse().getStatus());
        JsonNode body=body(result);
        assertEquals("给你写了一条。",body.get("reply").asText());
        assertEquals(1,body.get("actions").size(),"超出长度或其他页面的动作必须被丢弃");
        assertEquals("300W 够用吗？",body.get("actions").get(0).get("text").asText());
        // 服务端上下文里带的是权威正文，不是客户端传来的内容
        verify(advisor).chat(eq("detail"),argThat(context -> String.valueOf(context).contains("宿舍做饭长文")),
                eq("帮我写条评论"), anyList());
    }

    @Test void detailGuestRejectedAndDraftOfOthersForbidden() throws Exception {
        String owner=token("cp_author2"), other=token("cp_other2");
        String draft=createPost(owner);
        mvc.perform(post("/api/agent/copilot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screen\":\"detail\",\"postId\":\""+draft+"\",\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        MvcResult result=copilot(other,"{\"screen\":\"create\",\"draftId\":\""+draft+"\",\"message\":\"润色\"}");
        assertEquals(403,result.getResponse().getStatus());
        verifyNoInteractions(advisor);
    }

    @Test void createScreenReturnsDraftEditWithinLimits() throws Exception {
        String owner=token("cp_author3");
        String draft=createPost(owner);
        when(advisor.chat(eq("create"),anyMap(),eq("润色一下"),anyList())).thenReturn(Optional.of(
                new CopilotAdvisor.Result("改好了", List.of(
                        Map.of("type","UPDATE_DRAFT","title","宿舍做饭","content","改写后的正文"),
                        Map.of("type","UPDATE_DRAFT","content","长".repeat(5001))), "trace-2")));
        MvcResult result=copilot(owner,"{\"screen\":\"create\",\"draftId\":\""+draft+"\",\"message\":\"润色一下\"}");
        assertEquals(200,result.getResponse().getStatus());
        JsonNode body=body(result);
        assertEquals(1,body.get("actions").size());
        assertEquals("改写后的正文",body.get("actions").get(0).get("content").asText());
    }

    @Test void messagesScreenOnlyAcceptsParticipantConversations() throws Exception {
        String a=token("cp_a"), b=token("cp_b"), c=token("cp_c");
        String conv=conversation(a,userId(b));
        String foreign=conversation(b,userId(c));
        when(advisor.chat(eq("messages"),anyMap(),eq("帮我回一下"),anyList())).thenReturn(Optional.of(
                new CopilotAdvisor.Result("写好了", List.of(Map.of("type","SEND_MESSAGES","items",List.of(
                        Map.of("conversation_id",conv,"text","周日可以"),
                        Map.of("conversation_id",foreign,"text","越权会话"),
                        Map.of("conversation_id",conv,"text","重复会话")))), "trace-3")));

        MvcResult result=copilot(a,"{\"screen\":\"messages\",\"conversationIds\":[\""+conv+"\"],\"message\":\"帮我回一下\"}");
        assertEquals(200,result.getResponse().getStatus());
        JsonNode items=body(result).get("actions").get(0).get("items");
        assertEquals(1,items.size());
        assertEquals(conv,items.get(0).get("conversation_id").asText());
    }

    @Test void messagesScreenRejectsForeignConversationInRequest() throws Exception {
        String a=token("cp_a2"), b=token("cp_b2"), c=token("cp_c2");
        String foreign=conversation(b,userId(c));
        MvcResult result=copilot(a,"{\"screen\":\"messages\",\"conversationIds\":[\""+foreign+"\"],\"message\":\"hi\"}");
        assertEquals(403,result.getResponse().getStatus());
        verifyNoInteractions(advisor);
    }

    @Test void advisorFailureReturns503() throws Exception {
        String owner=token("cp_author4"), adm=admin("cp_admin4");
        String postId=publishedPost(owner,adm);
        when(advisor.chat(any(),anyMap(),any(),anyList())).thenReturn(Optional.empty());
        MvcResult result=copilot(owner,"{\"screen\":\"detail\",\"postId\":\""+postId+"\",\"message\":\"hi\"}");
        assertEquals(503,result.getResponse().getStatus());
    }

    @Test void batchSendWritesAllOrNothing() throws Exception {
        String a=token("cp_batch_a"), b=token("cp_batch_b"), c=token("cp_batch_c");
        String conv1=conversation(a,userId(b)), conv2=conversation(a,userId(c));
        String payload="{\"items\":[{\"conversation_id\":\""+conv1+"\",\"content\":\"周日可以\"},"
                + "{\"conversation_id\":\""+conv2+"\",\"content\":\"价格我再想想\"}]}";
        MvcResult result=mvc.perform(post("/api/conversations/messages/batch").header("Authorization",a)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andReturn();
        assertEquals(2,body(result).get("sent").size());
        assertEquals(1,body(mvc.perform(get("/api/users/me/messages/unread-count").header("Authorization",b))
                .andExpect(status().isOk()).andReturn()).get("count").asInt());
    }

    @Test void batchSendRejectsTooManyDuplicateAndForeign() throws Exception {
        String a=token("cp_batch_d"), b=token("cp_batch_e"), c=token("cp_batch_f");
        String conv1=conversation(a,userId(b)), conv2=conversation(a,userId(c));
        StringBuilder many=new StringBuilder("{\"items\":[");
        for (int i=0;i<11;i++) many.append(i==0?"":",").append("{\"conversation_id\":\"").append(conv1)
                .append("\",\"content\":\"消息").append(i).append("\"}");
        many.append("]}");
        mvc.perform(post("/api/conversations/messages/batch").header("Authorization",a)
                .contentType(MediaType.APPLICATION_JSON).content(many.toString())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations/messages/batch").header("Authorization",a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"conversation_id\":\""+conv1+"\",\"content\":\"a\"},{\"conversation_id\":\""
                        +conv1+"\",\"content\":\"b\"}]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations/messages/batch").header("Authorization",b)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"conversation_id\":\""+conv2+"\",\"content\":\"x\"}]}"))
                .andExpect(status().isForbidden());
    }
}
