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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:notification-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class NotificationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private static final String PASSWORD="test-password-2026";
    @BeforeEach void clean() {
        for (String table:List.of("notifications","comment_auto_replies","comments","moderation_records","post_media","media","behavior_events","interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
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
    private String admin(String name) throws Exception {
        String t=account(name); db.update("UPDATE users SET role='ADMIN' WHERE username='"+name+"'"); return t;
    }
    private String create(String token) throws Exception {
        return body(mvc.perform(post("/api/posts").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("title","作品","content","正文","clips",List.of(Map.of("type",0,"width",600,"height",800,"url","https://example.com/a.png"))))))
                .andExpect(status().isCreated()).andReturn()).get("post_id").asText();
    }
    private String published(String owner,String admin) throws Exception {
        String id=create(owner);
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("status","PUBLISHED","reason","通过")))).andExpect(status().isOk());
        return id;
    }
    private String comment(String token,String postId,String content,String parentId) throws Exception {
        Map<String,Object> payload=new java.util.HashMap<>(Map.of("content",content));
        if (parentId!=null) payload.put("parentCommentId",parentId);
        return body(mvc.perform(post("/api/posts/"+postId+"/comments").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(encode(payload))).andExpect(status().isOk()).andReturn()).get("comment_id").asText();
    }
    private void like(String token,String postId,boolean active) throws Exception {
        mvc.perform(put("/api/interactions/likes").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("targetId",postId,"active",active)))).andExpect(status().isOk());
    }
    private JsonNode list(String token,String type) throws Exception {
        return body(mvc.perform(get("/api/users/me/notifications?type="+type).header("Authorization",token))
                .andExpect(status().isOk()).andReturn());
    }
    private long unread(String token) throws Exception {
        return body(mvc.perform(get("/api/users/me/notifications/unread-count").header("Authorization",token))
                .andExpect(status().isOk()).andReturn()).get("count").asLong();
    }
    private void mute(String token,List<String> types) throws Exception {
        mvc.perform(put("/api/users/me/notification-preferences").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("mutedTypes",types)))).andExpect(status().isOk());
    }

    @Test void replyAndMentionNotifications() throws Exception {
        String alice=account("alice"); String bob=account("bob"); String carol=account("carol"); String adm=admin("admin_n");
        String post=published(alice,adm);
        String aliceComment=comment(alice,post,"一楼",null);
        comment(bob,post,"回复一楼",aliceComment);
        comment(carol,post,"@alice @bob 来看看这个",null);
        var aliceReply=list(alice,"REPLY").get("notification_list");
        assertEquals(2,aliceReply.size());
        assertEquals("carol",aliceReply.get(0).get("actor").get("nickname").asText());
        assertEquals("bob",aliceReply.get(1).get("actor").get("nickname").asText());
        assertEquals(post,aliceReply.get(0).get("post_id").asText());
        assertEquals("作品",aliceReply.get(0).get("post_title").asText());
        var aliceMention=list(alice,"MENTION").get("notification_list");
        assertEquals(0,aliceMention.size()); // Root-comment REPLY suppresses duplicate mention.
        var bobMention=list(bob,"MENTION").get("notification_list");
        assertEquals(1,bobMention.size());
        // 自己 @ 自己不产生通知；被回复者收到 REPLY 后不再收 MENTION。
        var carolMention=list(carol,"MENTION").get("notification_list");
        assertEquals(0,carolMention.size());
        assertEquals(0,list(alice,"LIKE").get("notification_list").size());
    }

    @Test void invalidParentRejected() throws Exception {
        String alice=account("alice2"); String bob=account("bob2"); String adm=admin("admin_p");
        String post=published(alice,adm);
        mvc.perform(post("/api/posts/"+post+"/comments").header("Authorization",bob).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("content","回复","parentCommentId","999999")))).andExpect(status().isNotFound());
    }

    @Test void likeNotifiesOncePerLikeEvent() throws Exception {
        String alice=account("alice3"); String bob=account("bob3"); String adm=admin("admin_l");
        String post=published(alice,adm);
        like(alice,post,true);                                   // 自赞无通知
        assertEquals(0,list(alice,"LIKE").get("notification_list").size());
        like(bob,post,true);
        like(bob,post,false);                                    // 取消赞不产生通知
        like(bob,post,true);                                     // 再次点赞视为新的点赞事件，再通知一次
        var likes=list(alice,"LIKE").get("notification_list");
        assertEquals(2,likes.size());
        assertEquals("bob3",likes.get(0).get("actor").get("nickname").asText());
    }

    @Test void muteSuppressesNotifications() throws Exception {
        String alice=account("alice4"); String bob=account("bob4"); String adm=admin("admin_m");
        String post=published(alice,adm);
        mute(alice,List.of("LIKE"));
        like(bob,post,true);
        assertEquals(0,list(alice,"LIKE").get("notification_list").size());
        assertEquals(0,unread(alice));
        // 解除屏蔽后再次点赞生效（取消+重点赞产生一次新通知）
        mute(alice,List.of());
        like(bob,post,false); like(bob,post,true);
        assertEquals(1,list(alice,"LIKE").get("notification_list").size());
    }

    @Test void markReadAndUnreadCount() throws Exception {
        String alice=account("alice5"); String bob=account("bob5"); String adm=admin("admin_r");
        String post=published(alice,adm);
        comment(bob,post,"@alice5",null);
        assertEquals(1,unread(alice));
        mvc.perform(post("/api/users/me/notifications/read").header("Authorization",alice).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of()))).andExpect(status().isOk());
        assertEquals(0,unread(alice));
        assertEquals(0,list(alice,"MENTION").get("notification_list").size());
        assertEquals(1,list(alice,"REPLY").get("notification_list").size());
    }

    @Test void prefsRoundtripAndValidation() throws Exception {
        String alice=account("alice6");
        var prefs=body(mvc.perform(get("/api/users/me/notification-preferences").header("Authorization",alice))
                .andExpect(status().isOk()).andReturn());
        assertEquals(0,prefs.get("muted_types").size());
        mute(alice,List.of("MENTION","LIKE"));
        var saved=body(mvc.perform(get("/api/users/me/notification-preferences").header("Authorization",alice))
                .andExpect(status().isOk()).andReturn());
        assertEquals(2,saved.get("muted_types").size());
        mvc.perform(put("/api/users/me/notification-preferences").header("Authorization",alice).contentType(MediaType.APPLICATION_JSON)
                .content(encode(Map.of("mutedTypes",List.of("BOGUS"))))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/me/notifications?type=BOGUS").header("Authorization",alice)).andExpect(status().isBadRequest());
    }
}
