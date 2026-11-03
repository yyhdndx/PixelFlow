package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:comment-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class CommentApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private static final String PASSWORD="test-password-2026";
    @BeforeEach void clean() {
        for (String table:List.of("notifications","comments","moderation_records","post_media","media","behavior_events","interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
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
    private String create(String token) throws Exception {
        return body(mvc.perform(post("/api/posts").header("Authorization",token).contentType("application/json")
                .content(encode(Map.of("title","作品","content","正文","clips",List.of(Map.of("type",0,"width",600,"height",800,"url","https://example.com/a.png"))))))
                .andExpect(status().isCreated()).andReturn()).get("post_id").asText();
    }
    private String published(String owner,String admin) throws Exception {
        String id=create(owner);
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("status","PUBLISHED","reason","通过")))).andExpect(status().isOk());
        return id;
    }
    private String comment(String token,String postId,String content) throws Exception {
        return body(mvc.perform(post("/api/posts/"+postId+"/comments").header("Authorization",token).contentType("application/json")
                .content(encode(Map.of("content",content)))).andExpect(status().isOk()).andReturn()).get("comment_id").asText();
    }
    private int count(String postId) {
        return db.queryForObject("SELECT comment_count FROM posts WHERE id=?",Integer.class,Long.parseLong(postId));
    }

    @Test void postListDeleteFullChain() throws Exception {
        String owner=account("author"); String adm=admin("admin_c"); String id=published(owner,adm);
        assertEquals(0,count(id));
        String cid=comment(owner,id,"第一条评论");
        assertEquals(1,count(id));
        var list=body(mvc.perform(get("/api/posts/"+id+"/comments").header("Authorization",owner))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1,list.get("comment_list").size());
        assertEquals(cid,list.get("comment_list").get(0).get("comment_id").asText());
        mvc.perform(delete("/api/comments/"+cid).header("Authorization",owner)).andExpect(status().isOk());
        assertEquals(0,count(id));
        var after=body(mvc.perform(get("/api/posts/"+id+"/comments").header("Authorization",owner)).andReturn());
        assertEquals(0,after.get("comment_list").size());
    }

    @Test void countSyncAcrossHideAndRestore() throws Exception {
        String owner=account("author2"); String adm=admin("admin_h"); String id=published(owner,adm);
        String cid=comment(owner,id,"待隐藏的评论");
        assertEquals(1,count(id));
        mvc.perform(put("/api/admin/comments/"+cid+"/status").header("Authorization",adm).contentType("application/json")
                .content(encode(Map.of("status","HIDDEN")))).andExpect(status().isOk());
        assertEquals(0,count(id));
        mvc.perform(put("/api/admin/comments/"+cid+"/status").header("Authorization",adm).contentType("application/json")
                .content(encode(Map.of("status","VISIBLE")))).andExpect(status().isOk());
        assertEquals(1,count(id));
    }

    @Test void onlyPublishedPostCanBeCommented() throws Exception {
        String owner=account("author3"); String id=create(owner);
        mvc.perform(post("/api/posts/"+id+"/comments").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("content","评论")))).andExpect(status().isNotFound());
    }

    @Test void nonAuthorNonAdminDeleteForbidden() throws Exception {
        String owner=account("author4"); String other=account("other4"); String adm=admin("admin_d"); String id=published(owner,adm);
        String cid=comment(owner,id,"别人的评论");
        mvc.perform(delete("/api/comments/"+cid).header("Authorization",other)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/comments/"+cid).header("Authorization",adm)).andExpect(status().isOk());
    }

    @Test void guestGets401() throws Exception {
        String owner=account("author5"); String adm=admin("admin_g"); String id=published(owner,adm);
        mvc.perform(post("/api/posts/"+id+"/comments").contentType("application/json")
                .content(encode(Map.of("content","游客评论")))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/posts/"+id+"/comments")).andExpect(status().isUnauthorized());
    }

    @Test void cursorPaginationBoundary() throws Exception {
        String owner=account("author6"); String adm=admin("admin_p"); String id=published(owner,adm);
        for (int i=1;i<=25;i++) comment(owner,id,"评论 "+i);
        assertEquals(25,count(id));
        var first=body(mvc.perform(get("/api/posts/"+id+"/comments").header("Authorization",owner).param("count","20")).andReturn());
        assertEquals(20,first.get("comment_list").size());
        assertEquals(1,first.get("has_more").asInt());
        String cursor=first.get("next_cursor").asText();
        var second=body(mvc.perform(get("/api/posts/"+id+"/comments").header("Authorization",owner)
                .param("count","20").param("cursor",cursor)).andReturn());
        assertEquals(5,second.get("comment_list").size());
        assertEquals(0,second.get("has_more").asInt());
    }

    @Test void replyCarriesParentNicknameInCreateResponseAndList() throws Exception {
        String owner=account("author8"); String other=account("other8"); String adm=admin("admin_r");
        String id=published(owner,adm);
        String parentId=comment(other,id,"原评论");
        var reply=body(mvc.perform(post("/api/posts/"+id+"/comments").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("content","回复内容","parentCommentId",parentId)))).andExpect(status().isOk()).andReturn());
        assertEquals(parentId,reply.get("parent_comment_id").asText());
        assertEquals("other8",reply.get("parent_nickname").asText(),
                "创建回复的响应必须带 parent_nickname，客户端乐观插入才不会丢掉“回复 @xx”前缀");
        var list=body(mvc.perform(get("/api/posts/"+id+"/comments").header("Authorization",owner)).andReturn());
        assertEquals(2,list.get("comment_list").size());
        assertEquals("other8",list.get("comment_list").get(0).get("parent_nickname").asText());
        assertEquals(2,count(id));
    }

    @Test void blankAndOversizedContentRejected() throws Exception {
        String owner=account("author7"); String adm=admin("admin_v"); String id=published(owner,adm);
        mvc.perform(post("/api/posts/"+id+"/comments").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("content","   ")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts/"+id+"/comments").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("content","x".repeat(501))))).andExpect(status().isBadRequest());
    }
}
