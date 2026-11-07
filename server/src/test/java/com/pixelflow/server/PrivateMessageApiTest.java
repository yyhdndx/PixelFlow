package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:private-message-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class PrivateMessageApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private static final String PASSWORD="test-password-2026";

    @BeforeEach void clean() {
        for (String table:List.of("conversation_reads","messages","conversations","notifications","comments","moderation_records","post_media","media","behavior_events","interactions","posts","sessions","users"))
            db.update("DELETE FROM "+table);
    }

    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private String encode(Object v) throws Exception { return json.writeValueAsString(v); }
    private String token(String name) throws Exception {
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }
    private String account(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("username",name,"password",PASSWORD,"nickname",name)))).andExpect(status().isCreated());
        return token(name);
    }
    private String userId(String token) throws Exception {
        return body(mvc.perform(get("/api/users/me").header("Authorization",token)).andExpect(status().isOk()).andReturn()).get("id").asText();
    }
    private String createConversation(String token, String targetUserId) throws Exception {
        return body(mvc.perform(post("/api/conversations").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("targetUserId",targetUserId)))).andExpect(status().isOk()).andReturn()).get("conversation_id").asText();
    }
    private String send(String token, String conversationId, String content) throws Exception {
        return body(mvc.perform(post("/api/conversations/"+conversationId+"/messages").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("content",content)))).andExpect(status().isOk()).andReturn()).get("message_id").asText();
    }
    private JsonNode messages(String token, String conversationId, String query) throws Exception {
        return body(mvc.perform(get("/api/conversations/"+conversationId+"/messages"+(query==null?"":query)).header("Authorization",token))
                .andExpect(status().isOk()).andReturn());
    }
    private int unread(String token) throws Exception {
        return body(mvc.perform(get("/api/users/me/messages/unread-count").header("Authorization",token))
                .andExpect(status().isOk()).andReturn()).get("count").asInt();
    }

    @Test void dedupAndPermissions() throws Exception {
        String a=account("pm_a"), b=account("pm_b"), c=account("pm_c");
        String aId=userId(a), bId=userId(b);
        // 自己和自己 -> 400
        mvc.perform(post("/api/conversations").header("Authorization",a).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("targetUserId",aId)))).andExpect(status().isBadRequest());
        // A→B 创建
        String conv=createConversation(a,bId);
        // B→A 得到同一个会话（去重）
        assertEquals(conv,createConversation(b,aId));
        // 非参与方 C 读消息 -> 403
        mvc.perform(get("/api/conversations/"+conv+"/messages").header("Authorization",c)).andExpect(status().isForbidden());
        // 非参与方 C 发消息 -> 403
        mvc.perform(post("/api/conversations/"+conv+"/messages").header("Authorization",c).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("content","hi")))).andExpect(status().isForbidden());
        // 非参与方 C 标记已读 -> 403
        mvc.perform(put("/api/conversations/"+conv+"/read").header("Authorization",c).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
    }

    @Test void messageValidationAndPagination() throws Exception {
        String a=account("pm_d"), b=account("pm_e");
        String conv=createConversation(a,userId(b));
        // 空内容 / 纯空白 / 超长拒绝
        mvc.perform(post("/api/conversations/"+conv+"/messages").header("Authorization",a).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("content","")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations/"+conv+"/messages").header("Authorization",a).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("content","   ")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations/"+conv+"/messages").header("Authorization",a).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("content","x".repeat(1001))))).andExpect(status().isBadRequest());
        // 发 3 条
        send(a,conv,"第一条"); send(a,conv,"第二条"); send(a,conv,"第三条");
        // 倒序分页：count=2 -> 2 条 + has_more + next_cursor
        JsonNode page1=messages(b,conv,"?count=2");
        assertEquals(0,page1.get("status_code").asInt());
        assertEquals(2,page1.get("message_list").size());
        assertEquals(1,page1.get("has_more").asInt());
        String lastContent=page1.get("message_list").get(0).get("content").asText();
        assertEquals("第三条",lastContent); // 倒序，最新在前
        String cursor=page1.get("next_cursor").asText();
        // 第二页 -> 剩 1 条，无更多
        JsonNode page2=messages(b,conv,"?count=2&cursor="+cursor);
        assertEquals(1,page2.get("message_list").size());
        assertEquals(0,page2.get("has_more").asInt());
        // after 增量：id>0 -> 3 条正序
        JsonNode incr=messages(b,conv,"?after=0");
        assertEquals(3,incr.get("message_list").size());
        assertEquals("第一条",incr.get("message_list").get(0).get("content").asText());
    }

    @Test void unreadCountAndMarkRead() throws Exception {
        String a=account("pm_f"), b=account("pm_g");
        String conv=createConversation(a,userId(b));
        // 初始双方未读都是 0
        assertEquals(0,unread(a)); assertEquals(0,unread(b));
        // A 发 2 条
        send(a,conv,"你好"); send(a,conv,"在吗");
        // B 未读 = 2，A 未读 = 0（自己发的不算）
        assertEquals(0,unread(a)); assertEquals(2,unread(b));
        // B 标记已读（空 body 读到最新）
        mvc.perform(put("/api/conversations/"+conv+"/read").header("Authorization",b).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());
        assertEquals(0,unread(b));
        // B 回 1 条 -> A 未读 = 1
        send(b,conv,"回复"); assertEquals(1,unread(a));
        // 会话列表带未读数
        JsonNode list=body(mvc.perform(get("/api/conversations").header("Authorization",a)).andExpect(status().isOk()).andReturn());
        assertEquals(1,list.get("conversation_list").size());
        assertEquals(1,list.get("conversation_list").get(0).get("unread_count").asInt());
        assertEquals("pm_g",list.get("conversation_list").get(0).get("partner").get("nickname").asText());
    }

    @Test void uniqueConstraintBackstop() {
        // 直接落库两条同对用户的会话，唯一约束应拒绝
        db.update("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES('u1','pm_h','x','h','CREATOR',TRUE)");
        db.update("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES('u2','pm_i','x','i','CREATOR',TRUE)");
        db.update("INSERT INTO conversations(user_low,user_high,create_time) VALUES('u1','u2',1)");
        assertThrows(Exception.class, () -> db.update("INSERT INTO conversations(user_low,user_high,create_time) VALUES('u1','u2',2)"));
    }
}
