package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.comment.CommentReplyAdvisor;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:auto-reply-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long",
        "pixelflow.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class AutoReplyApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    /** Agent 判定用桩替换：测试不依赖模型，只验证编排与权限。 */
    @MockBean CommentReplyAdvisor advisor;
    private static final String PASSWORD="auto-reply-password";

    @BeforeEach void clean() {
        for (String table:List.of("notifications","comment_auto_replies","comments","moderation_records","post_media","media",
                "behavior_events","interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
        reset(advisor);
        when(advisor.decide(anyLong(),any(),any(),any(),anyList())).thenReturn(Optional.empty());
    }

    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private String encode(Object v) throws Exception { return json.writeValueAsString(v); }
    private String token(String name) throws Exception {
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(encode(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }
    private String account(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(encode(Map.of("username",name,"password",PASSWORD,"nickname",name)))).andExpect(status().isCreated());
        return token(name);
    }
    private String admin(String name) throws Exception {
        String t=account(name); db.update("UPDATE users SET role='ADMIN' WHERE username='"+name+"'"); return t;
    }
    private String published(String owner,String adm) throws Exception {
        String payload="{\"title\":\"auto reply post\",\"content\":\"body\",\"clips\":[{\"type\":0,\"width\":600,\"height\":800,\"url\":\"https://example.com/a.png\"}]}";
        String id=body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(payload)).andExpect(status().isCreated()).andReturn()).get("post_id").asText();
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",adm).contentType("application/json")
                .content(encode(Map.of("status","PUBLISHED","reason","ok")))).andExpect(status().isOk());
        return id;
    }
    private MvcResult rule(String token,String postId,String question,String answer) throws Exception {
        return mvc.perform(post("/api/posts/"+postId+"/auto-replies").header("Authorization",token).contentType("application/json")
                .content(encode(Map.of("question",question,"answer",answer)))).andReturn();
    }
    private JsonNode comment(String token,String postId,String content,String parent) throws Exception {
        var request=post("/api/posts/"+postId+"/comments").header("Authorization",token).contentType("application/json")
                .content(encode(parent==null ? Map.of("content",content) : Map.of("content",content,"parentCommentId",parent)));
        return body(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }
    private int count(String postId) {
        return db.queryForObject("SELECT comment_count FROM posts WHERE id=?",Integer.class,Long.parseLong(postId));
    }
    private void matchedWith(String answer) {
        when(advisor.decide(anyLong(),any(),any(),any(),anyList()))
                .thenReturn(Optional.of(new CommentReplyAdvisor.Match(0,answer,"评论在问价格","trace-1")));
    }

    @Test void matchedScenarioGetsSingleAutoReply() throws Exception {
        String owner=account("ar_author"); String adm=admin("ar_admin"); String post=published(owner,adm);
        assertEquals(200,rule(owner,post,"有人问价格","99 元，链接在主页").getResponse().getStatus());
        matchedWith("99 元，链接在主页");
        String visitor=account("ar_visitor");
        JsonNode result=comment(visitor,post,"这个要多少钱呀",null);
        assertTrue(result.has("auto_reply"),"命中场景时应返回自动回复");
        JsonNode auto=result.get("auto_reply");
        assertEquals("99 元，链接在主页",auto.get("content").asText(),"回答必须逐字取作者配置");
        assertTrue(auto.get("auto").asBoolean(),"自动回复必须带 auto 标记，不能冒充真人");
        assertEquals(result.get("comment_id").asText(),auto.get("parent_comment_id").asText());
        assertEquals(2,count(post));
        // The root comment notifies the author; the auto reply notifies the visitor.
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REPLY'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notifications n JOIN users u ON u.id=n.recipient_id WHERE n.type='REPLY' AND u.username='ar_author'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notifications n JOIN users u ON u.id=n.recipient_id WHERE n.type='REPLY' AND u.username='ar_visitor'",Integer.class));
        assertEquals(db.queryForObject("SELECT author_id FROM posts WHERE id=?",String.class,Long.parseLong(post)),
                db.queryForObject("SELECT author_id FROM comments WHERE auto_reply=TRUE",String.class),
                "自动回复必须以作者身份发出");
    }

    @Test void noMatchOrAdvisorFailureLeavesCommentOnly() throws Exception {
        String owner=account("ar_author2"); String adm=admin("ar_admin2"); String post=published(owner,adm);
        rule(owner,post,"有人问价格","99 元");
        String visitor=account("ar_visitor2");
        JsonNode plain=comment(visitor,post,"拍得真好看",null);
        assertFalse(plain.has("auto_reply"));
        assertEquals(1,count(post));

        when(advisor.decide(anyLong(),any(),any(),any(),anyList()))
                .thenThrow(new IllegalStateException("python down"));
        JsonNode afterFailure=comment(visitor,post,"这个多少钱",null);
        assertFalse(afterFailure.has("auto_reply"),"Agent 异常时不能乱回，只保留用户评论");
        assertEquals(2,count(post));
    }

    @Test void replyAndAuthorSelfCommentNeverCallAgent() throws Exception {
        String owner=account("ar_author3"); String adm=admin("ar_admin3"); String post=published(owner,adm);
        rule(owner,post,"有人问价格","99 元");
        matchedWith("99 元");
        String visitor=account("ar_visitor3");
        JsonNode root=comment(visitor,post,"价格多少",null);
        assertTrue(root.has("auto_reply"));
        clearInvocations(advisor);
        comment(owner,post,"回复一条评论",root.get("comment_id").asText());
        comment(owner,post,"我自己发的一级评论",null);
        verify(advisor,never()).decide(anyLong(),any(),any(),any(),anyList());
    }

    @Test void disabledOrMissingRulesSkipAgent() throws Exception {
        String owner=account("ar_author4"); String adm=admin("ar_admin4"); String post=published(owner,adm);
        String visitor=account("ar_visitor4");
        JsonNode noRules=comment(visitor,post,"多少钱",null);
        assertFalse(noRules.has("auto_reply"));
        verify(advisor,never()).decide(anyLong(),any(),any(),any(),anyList());

        String ruleId=body(rule(owner,post,"有人问价格","99 元")).get("id").asText();
        mvc.perform(put("/api/posts/"+post+"/auto-replies/"+ruleId).header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("question","有人问价格","answer","99 元","enabled",false)))).andExpect(status().isOk());
        JsonNode disabled=comment(visitor,post,"多少钱",null);
        assertFalse(disabled.has("auto_reply"));
        verify(advisor,never()).decide(anyLong(),any(),any(),any(),anyList());
    }

    @Test void onlyOwnerCanManageRules() throws Exception {
        String owner=account("ar_author5"); String adm=admin("ar_admin5"); String post=published(owner,adm);
        String other=account("ar_other5");
        assertEquals(403,rule(other,post,"有人问价格","99").getResponse().getStatus());
        mvc.perform(get("/api/posts/"+post+"/auto-replies").header("Authorization",other)).andExpect(status().isForbidden());
        mvc.perform(get("/api/posts/"+post+"/auto-replies")).andExpect(status().isUnauthorized());
        assertEquals(200,rule(owner,post,"有人问价格","99").getResponse().getStatus());
    }

    @Test void ruleLimitAndValidation() throws Exception {
        String owner=account("ar_author6"); String adm=admin("ar_admin6"); String post=published(owner,adm);
        for (int i=0;i<10;i++) assertEquals(200,rule(owner,post,"场景"+i,"答案"+i).getResponse().getStatus());
        assertEquals(400,rule(owner,post,"超限","回复").getResponse().getStatus());
        mvc.perform(post("/api/posts/"+post+"/auto-replies").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("question","   ","answer","x")))).andExpect(status().isBadRequest());
    }

    @Test void updateAndDeleteRule() throws Exception {
        String owner=account("ar_author7"); String adm=admin("ar_admin7"); String post=published(owner,adm);
        String ruleId=body(rule(owner,post,"有人问价格","旧回答")).get("id").asText();
        mvc.perform(put("/api/posts/"+post+"/auto-replies/"+ruleId).header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("question","有人问价格或费用","answer","新回答","enabled",true)))).andExpect(status().isOk());
        matchedWith("新回答");
        String visitor=account("ar_visitor7");
        assertEquals("新回答",comment(visitor,post,"费用怎么算",null).get("auto_reply").get("content").asText());
        mvc.perform(delete("/api/posts/"+post+"/auto-replies/"+ruleId).header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(delete("/api/posts/"+post+"/auto-replies/"+ruleId).header("Authorization",owner)).andExpect(status().isNotFound());
    }

    @Test void batchCreateAddsMultipleRulesAndRespectsLimit() throws Exception {
        String owner=account("ar_author9"); String adm=admin("ar_admin9"); String post=published(owner,adm);
        String payload="{\"rules\":[{\"question\":\"有人问价格\",\"answer\":\"99 元\"},"
                + "{\"question\":\"有人问发货\",\"answer\":\"48 小时\"}]}";
        var created=body(mvc.perform(post("/api/posts/"+post+"/auto-replies/batch")
                .header("Authorization",owner).contentType("application/json").content(payload))
                .andExpect(status().isOk()).andReturn());
        assertEquals(2,created.get("created").size());
        assertEquals("有人问发货",created.get("created").get(1).get("question").asText());

        StringBuilder tooMany=new StringBuilder("{\"rules\":[");
        for (int i=0;i<9;i++) tooMany.append(i==0?"":",").append("{\"question\":\"场景").append(i)
                .append("\",\"answer\":\"答案").append(i).append("\"}");
        tooMany.append("]}");
        mvc.perform(post("/api/posts/"+post+"/auto-replies/batch").header("Authorization",owner)
                .contentType("application/json").content(tooMany.toString())).andExpect(status().isBadRequest());
    }

    @Test void batchCreateRequiresOwnerAndRules() throws Exception {
        String owner=account("ar_author10"); String adm=admin("ar_admin10"); String post=published(owner,adm);
        String other=account("ar_other10");
        String payload="{\"rules\":[{\"question\":\"有人问价格\",\"answer\":\"99 元\"}]}";
        mvc.perform(post("/api/posts/"+post+"/auto-replies/batch").header("Authorization",other)
                .contentType("application/json").content(payload)).andExpect(status().isForbidden());
        mvc.perform(post("/api/posts/"+post+"/auto-replies/batch").header("Authorization",owner)
                .contentType("application/json").content("{\"rules\":[]}")).andExpect(status().isBadRequest());
    }
    @Test void listReturnsConfiguredScenarios() throws Exception {
        String owner=account("ar_author8"); String adm=admin("ar_admin8"); String post=published(owner,adm);
        rule(owner,post,"有人问价格","99 元");
        rule(owner,post,"有人问发货时间","48 小时内");
        var list=body(mvc.perform(get("/api/posts/"+post+"/auto-replies").header("Authorization",owner)).andReturn());
        assertEquals(2,list.get("rules").size());
        assertEquals("有人问价格",list.get("rules").get(0).get("question").asText());
        assertEquals("48 小时内",list.get("rules").get(1).get("answer").asText());
        assertTrue(list.get("rules").get(0).get("enabled").asBoolean());
    }
}
