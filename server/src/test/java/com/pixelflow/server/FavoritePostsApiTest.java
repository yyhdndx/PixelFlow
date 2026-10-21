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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:favorites-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class FavoritePostsApiTest {
    static final String PATH="/api/users/me/favorites";
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
        var credentials=Map.of("username",name,"password","favorites-test-password","nickname","中文作者");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(credentials)))
                .andExpect(status().isCreated());
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password",credentials.get("password")))))
                .andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    JsonNode page(String token,String count,String cursor) throws Exception {
        var request=get(PATH).header("Authorization",token);
        if (count!=null) request.param("count",count);
        if (cursor!=null) request.param("cursor",cursor);
        return body(mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn());
    }
    List<String> ids(JsonNode page) {
        List<String> result=new ArrayList<>(); page.get("post_list").forEach(p -> result.add(p.get("post_id").asText()));
        return result;
    }
    void postRow(long id,String author,String state,boolean video) {
        db.update("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,?,?,?,?)",
                id,author,"中文作品","图文正文","[{\"type\":"+(video ? 1 : 0)+",\"width\":600,\"height\":800,\"url\":\"https://example.com/media\"}]",
                video,state,1791000000L);
    }
    void relation(String user,String kind,String target) {
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,?,?)",user,kind,target);
    }
    void favorite(String user,long id) { relation(user,"favorites",Long.toString(id)); }
    void setFavorite(long id,boolean active) throws Exception {
        mvc.perform(put("/api/interactions/favorites").header("Authorization",alice).contentType("application/json")
                .content(json.writeValueAsString(Map.of("targetId",Long.toString(id),"active",active)))).andExpect(status().isOk());
    }

    @Test void emptyPageAndAuthentication() throws Exception {
        var result=page(alice,null,null);
        assertEquals(4,result.size()); assertEquals(0,result.get("status_code").asInt());
        assertTrue(ids(result).isEmpty()); assertEquals(0,result.get("has_more").asInt()); assertTrue(result.get("next_cursor").isNull());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        mvc.perform(get(PATH).header("Authorization",alice)).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId);
        db.update("UPDATE sessions SET expires_at=0 WHERE user_id=?",aliceId);
        mvc.perform(get(PATH).header("Authorization",alice)).andExpect(status().isUnauthorized());
    }

    @Test void cardsMatchDetailsAndLikeStateBelongsToViewer() throws Exception {
        postRow(9,bobId,"PUBLISHED",false); postRow(10,bobId,"PUBLISHED",true);
        favorite(aliceId,9); favorite(aliceId,10); favorite(bobId,9); relation(aliceId,"likes","9"); relation(bobId,"likes","10");
        var first=page(alice,null,null);
        assertEquals(List.of("10","9"),ids(first)); assertEquals(List.of("9"),ids(page(bob,null,null)));
        for (JsonNode card:first.get("post_list")) {
            var detail=body(mvc.perform(get("/api/posts/"+card.get("post_id").asText()).header("Authorization",alice))
                    .andExpect(status().isOk()).andReturn());
            var expected=detail.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)expected).put("is_favorited",true);
            assertEquals(expected,card);
            assertEquals("中文作品",card.get("title").asText());
            assertEquals("中文作者",card.get("author").get("nickname").asText());
            assertTrue(card.get("is_favorited").asBoolean());
        }
        assertFalse(first.get("post_list").get(0).get("is_liked").asBoolean());
        assertTrue(first.get("post_list").get(1).get("is_liked").asBoolean());
        assertFalse(page(bob,null,null).get("post_list").get(0).get("is_liked").asBoolean());
        var spoofed=body(mvc.perform(get(PATH).header("Authorization",alice).param("user_id",bobId).param("userId",bobId))
                .andExpect(status().isOk()).andReturn());
        assertEquals(first,spoofed);
    }

    @Test void numericOrderingIncludesMaximumLongOnFirstPage() throws Exception {
        for (long id:new long[]{9,10,100,Long.MAX_VALUE}) { postRow(id,bobId,"PUBLISHED",false); favorite(aliceId,id); }
        List<String> visited=new ArrayList<>(); String cursor=null;
        for (int i=0;i<4;i++) {
            var result=page(alice,"1",cursor); visited.addAll(ids(result));
            assertEquals(i<3 ? 1 : 0,result.get("has_more").asInt());
            cursor=result.get("next_cursor").isNull() ? null : result.get("next_cursor").asText();
        }
        assertEquals(List.of(Long.toString(Long.MAX_VALUE),"100","10","9"),visited);
        assertEquals(List.of("100","10","9"),ids(page(alice,"50",Long.toString(Long.MAX_VALUE))));
        assertTrue(ids(page(alice,"1","1")).isEmpty());
    }

    @Test void defaultsMaximumCountAndExactLastPage() throws Exception {
        for (int id=1;id<=51;id++) { postRow(id,bobId,"PUBLISHED",id%2==0); favorite(aliceId,id); }
        assertEquals(20,ids(page(alice,null,null)).size());
        var first=page(alice,"50",null); assertEquals(50,ids(first).size());
        assertEquals(1,first.get("has_more").asInt()); assertEquals("2",first.get("next_cursor").asText());
        var last=page(alice,"1","2"); assertEquals(List.of("1"),ids(last));
        assertEquals(0,last.get("has_more").asInt()); assertTrue(last.get("next_cursor").isNull());
    }

    @Test void visibilityIsFilteredBeforeLimitEvenForOwnerOrAdmin() throws Exception {
        long id=200;
        for (String state:List.of("DRAFT","PENDING","REJECTED","WITHDRAWN")) {
            postRow(id,aliceId,state,false); favorite(aliceId,id--);
        }
        postRow(100,bobId,"PUBLISHED",false); favorite(aliceId,100);
        postRow(10,aliceId,"PUBLISHED",false); favorite(aliceId,10);
        postRow(9,aliceId,"PUBLISHED",false); favorite(aliceId,9);
        favorite(aliceId,999); db.update("UPDATE users SET enabled=FALSE WHERE id=?",bobId);
        var first=page(alice,"1",null); assertEquals(List.of("10"),ids(first)); assertEquals(1,first.get("has_more").asInt());
        assertEquals(List.of("9"),ids(page(alice,"1","10")));
        db.update("UPDATE users SET role='ADMIN' WHERE id=?",aliceId);
        assertEquals(List.of("10","9"),ids(page(alice,null,null)));
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",bobId);
        assertEquals(List.of("100","10","9"),ids(page(alice,null,null)));
        db.update("UPDATE posts SET status='WITHDRAWN' WHERE id=100");
        assertEquals(List.of("10","9"),ids(page(alice,null,null)));
        db.update("UPDATE posts SET status='PUBLISHED' WHERE id=100");
        assertEquals(List.of("100","10","9"),ids(page(alice,null,null)));
        assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM interactions WHERE kind='favorites'",Integer.class));
    }

    @Test void malformedTargetsAndOtherKindsCannotMatchNumericPosts() throws Exception {
        postRow(9,bobId,"PUBLISHED",false);
        for (String target:List.of("09","9x","9.0","9e0","9 "," 9","+9","bob","9223372036854775808")) relation(aliceId,"favorites",target);
        for (String kind:List.of("likes","follows","not-interested")) relation(aliceId,kind,"9");
        assertTrue(ids(page(alice,null,null)).isEmpty());
        favorite(aliceId,9);
        assertEquals(List.of("9"),ids(page(alice,null,null)));
    }

    @Test void existingWritesIdListAndPreferencesRemainCompatible() throws Exception {
        postRow(10,bobId,"PUBLISHED",false); postRow(9,bobId,"PUBLISHED",false);
        setFavorite(9,true); setFavorite(10,true); setFavorite(9,true);
        assertEquals(List.of("10","9"),ids(page(alice,null,null)));
        assertEquals(2,body(mvc.perform(get("/api/interactions/favorites").header("Authorization",alice))
                .andExpect(status().isOk()).andReturn()).size());
        db.update("INSERT INTO recommendation_blocked_authors(user_id,author_id) VALUES(?,?)",aliceId,bobId);
        db.update("INSERT INTO recommendation_preferences(user_id,personalized) VALUES(?,FALSE)",aliceId);
        assertEquals(List.of("10","9"),ids(page(alice,null,null)));
        setFavorite(10,false); setFavorite(10,false); assertEquals(List.of("9"),ids(page(alice,null,null)));
        setFavorite(10,true); assertEquals(List.of("10","9"),ids(page(alice,null,null)));
    }

    @Test void paginationRejectsInvalidAndRepeatedParameters() throws Exception {
        for (String count:List.of("","0","51","-1","1.5","abc"," 1","+1","１","9".repeat(200)))
            mvc.perform(get(PATH).header("Authorization",alice).param("count",count)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_ARGUMENT")).andExpect(header().string("Cache-Control","no-store"));
        for (String cursor:List.of("","0","-1","1.5","abc"," 1","+1","9223372036854775808","9".repeat(200)))
            mvc.perform(get(PATH).header("Authorization",alice).param("cursor",cursor)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        for (String name:List.of("count","cursor"))
            mvc.perform(get(PATH).header("Authorization",alice).param(name,"1","2")).andExpect(status().isBadRequest());
        assertTrue(ids(page(alice,"0001","0".repeat(200)+"1")).isEmpty());
    }
}
