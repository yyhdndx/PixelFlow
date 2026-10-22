package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
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
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String alice,bob,aliceId,bobId;
    static final String PATH="/api/users/me/favorites";

    @BeforeEach void setup() throws Exception {
        for (String table:List.of("recommendation_suppressed_topics","recommendation_blocked_authors","recommendation_preferences",
                "event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events",
                "interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
        alice=account("alice"); bob=account("bob");
        aliceId=db.queryForObject("SELECT id FROM users WHERE username='alice'",String.class);
        bobId=db.queryForObject("SELECT id FROM users WHERE username='bob'",String.class);
    }

    String account(String username) throws Exception {
        var credentials=Map.of("username",username,"password","favorites-test-password");
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",username,"password",credentials.get("password"),"nickname",username))))
                .andExpect(status().isCreated());
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(credentials))).andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    JsonNode page(String token,String count,String cursor) throws Exception {
        var request=get(PATH).header("Authorization",token);
        if (count!=null) request.param("count",count);
        if (cursor!=null) request.param("cursor",cursor);
        return body(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn());
    }
    void insertPost(long id,String author,String state) {
        db.update("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,'[]',FALSE,?,?)",
                id,author,"中文作品","正文\n旅行",state,1790000000L);
    }
    void relation(String user,String kind,String target) {
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,?,?)",user,kind,target);
    }
    void favorite(long id) { relation(aliceId,"favorites",Long.toString(id)); }
    void setFavorite(String token,String target,boolean active) throws Exception {
        var response=body(mvc.perform(put("/api/interactions/favorites").header("Authorization",token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("targetId",target,"active",active)))).andExpect(status().isOk()).andReturn());
        assertEquals(2,response.size()); assertEquals(target,response.get("targetId").asText());
        assertEquals(active,response.get("active").asBoolean());
    }
    List<String> ids(JsonNode page) {
        List<String> ids=new ArrayList<>();
        page.get("post_list").forEach(card -> { assertTrue(card.get("post_id").isTextual()); ids.add(card.get("post_id").asText()); });
        return ids;
    }

    @Test void emptyPageHasFourFieldsAndRequiresLiveAuthentication() throws Exception {
        var result=page(alice,null,null);
        assertEquals(Set.of("status_code","post_list","has_more","next_cursor"),json.convertValue(result,Map.class).keySet());
        assertTrue(result.get("status_code").isIntegralNumber()); assertEquals(0,result.get("status_code").asInt());
        assertTrue(result.get("post_list").isArray()); assertTrue(ids(result).isEmpty());
        assertTrue(result.get("has_more").isIntegralNumber()); assertEquals(0,result.get("has_more").asInt());
        assertTrue(result.get("next_cursor").isNull());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        mvc.perform(get(PATH).header("Authorization",alice)).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId);
        db.update("UPDATE sessions SET expires_at=0 WHERE user_id=?",aliceId);
        mvc.perform(get(PATH).header("Authorization",alice)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization",bob)).andExpect(status().isOk());
        mvc.perform(get(PATH).header("Authorization",bob)).andExpect(status().isUnauthorized());
    }

    @Test void identityAndLikesAreScopedToTheActorAndCardsPreserveFeedFields() throws Exception {
        insertPost(9,bobId,"PUBLISHED"); insertPost(10,bobId,"PUBLISHED"); favorite(9); favorite(10);
        relation(bobId,"favorites","9"); relation(aliceId,"likes","10"); relation(bobId,"likes","9");
        db.update("UPDATE users SET nickname=?,avatar=? WHERE id=?","中文作者","https://example.com/avatar.png",bobId);
        var clips=List.of(Map.of("type",0,"width",600,"height",800,"url","https://example.com/中文.png"),
                Map.of("type",1,"width",1920,"height",1080,"url","https://example.com/movie.mp4"));
        db.update("UPDATE posts SET clips_json=?,has_video=TRUE WHERE id=10",json.writeValueAsString(clips));
        for (int i=0;i<2;i++) {
            db.update("INSERT INTO media(id,owner_id,width,height,content_bytes,created_at) VALUES(?,?,600,800,?,0)","media-"+i,bobId,new byte[]{1});
            db.update("INSERT INTO post_media(post_id,media_id,position) VALUES(10,?,?)","media-"+i,1-i);
        }
        var result=page(alice,null,null);
        assertEquals(List.of("10","9"),ids(result)); assertEquals(List.of("9"),ids(page(bob,null,null)));
        var spoofed=body(mvc.perform(get(PATH).header("Authorization",alice).param("userId",bobId).param("user_id",bobId))
                .andExpect(status().isOk()).andReturn());
        assertEquals(result,spoofed);
        var card=result.get("post_list").get(0);
        assertEquals(Set.of("post_id","title","content","create_time","status","media_ids","author","clips","hashtag","music",
                "like_count","is_liked","is_favorited"),json.convertValue(card,Map.class).keySet());
        assertEquals("中文作品",card.get("title").asText()); assertEquals("正文\n旅行",card.get("content").asText());
        assertTrue(card.get("create_time").isIntegralNumber()); assertEquals(1790000000L,card.get("create_time").asLong());
        assertEquals("PUBLISHED",card.get("status").asText());
        assertEquals(json.valueToTree(Map.of("user_id",bobId,"nickname","中文作者","avatar","https://example.com/avatar.png")),card.get("author"));
        assertEquals(json.valueToTree(clips),card.get("clips")); assertEquals(json.valueToTree(List.of("media-1","media-0")),card.get("media_ids"));
        assertTrue(card.get("hashtag").isArray()); assertEquals(0,card.get("hashtag").size()); assertTrue(card.get("music").isNull());
        assertTrue(card.get("like_count").isIntegralNumber()); assertEquals(1,card.get("like_count").asInt());
        assertTrue(card.get("is_liked").isBoolean()); assertTrue(card.get("is_liked").asBoolean());
        assertFalse(result.get("post_list").get(1).get("is_liked").asBoolean());
        assertTrue(page(bob,null,null).get("post_list").get(0).get("is_liked").asBoolean());
        for (var entry:result.get("post_list")) { assertTrue(entry.get("is_favorited").isBoolean()); assertTrue(entry.get("is_favorited").asBoolean()); }
        mvc.perform(get("/feed/").header("Authorization",alice)).andExpect(status().isOk()).andExpect(jsonPath("post_list[0].post_id").value("9"));
        var detail=body(mvc.perform(get("/api/posts/10").header("Authorization",alice)).andExpect(status().isOk()).andReturn());
        var expected=card.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)expected).remove("is_favorited");
        assertEquals(expected,detail);
    }

    @Test void numericOrderAndExclusiveCursorIncludeLongBoundaries() throws Exception {
        for (long id:List.of(9L,10L,100L,Long.MAX_VALUE-1,Long.MAX_VALUE)) { insertPost(id,bobId,"PUBLISHED"); favorite(id); }
        assertEquals(List.of(Long.toString(Long.MAX_VALUE),Long.toString(Long.MAX_VALUE-1),"100","10","9"),ids(page(alice,null,null)));
        var first=page(alice,"1",null); assertEquals(Long.toString(Long.MAX_VALUE),first.get("next_cursor").asText());
        assertEquals(List.of(Long.toString(Long.MAX_VALUE-1)),ids(page(alice,"1",Long.toString(Long.MAX_VALUE))));
        assertEquals(List.of("10","9"),ids(page(alice,"20","100")));
        assertTrue(ids(page(alice,"1","1")).isEmpty());
    }

    @Test void defaultMaximumAndAllPagesHaveNoDuplicatesOrOmissions() throws Exception {
        List<String> expected=new ArrayList<>();
        for (long id=54;id>0;id--) { insertPost(id,bobId,"PUBLISHED"); favorite(id); expected.add(Long.toString(id)); }
        assertEquals(expected.subList(0,20),ids(page(alice,null,null)));
        var first=page(alice,"50",null); assertEquals(expected.subList(0,50),ids(first));
        assertEquals(1,first.get("has_more").asInt()); assertEquals("5",first.get("next_cursor").asText());
        var last=page(alice,"50","5"); assertEquals(expected.subList(50,54),ids(last));
        assertEquals(0,last.get("has_more").asInt()); assertTrue(last.get("next_cursor").isNull());
        var exact=page(alice,"4","5"); assertEquals(4,ids(exact).size());
        assertEquals(0,exact.get("has_more").asInt()); assertTrue(exact.get("next_cursor").isNull());
        List<String> visited=new ArrayList<>(); String cursor=null;
        for (int i=0;i<10;i++) {
            var result=page(alice,"7",cursor); visited.addAll(ids(result));
            if (result.get("has_more").asInt()==0) { assertTrue(result.get("next_cursor").isNull()); break; }
            assertEquals(ids(result).get(6),result.get("next_cursor").asText()); cursor=result.get("next_cursor").asText();
        }
        assertEquals(expected,visited);
    }

    @Test void visibilityFiltersBeforeLimitWithoutOwnerOrAdminExemptionsAndRestoresRelations() throws Exception {
        db.update("UPDATE users SET role='ADMIN' WHERE id=?",bobId);
        insertPost(9,aliceId,"PUBLISHED"); insertPost(10,aliceId,"PUBLISHED"); favorite(9); favorite(10);
        long id=100;
        for (String state:List.of("DRAFT","PENDING","REJECTED","WITHDRAWN")) {
            insertPost(id,aliceId,state); favorite(id); relation(bobId,"favorites",Long.toString(id)); id++;
        }
        db.update("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES('disabled','disabled','unused','停用作者','CREATOR',FALSE)");
        insertPost(200,"disabled","PUBLISHED"); favorite(200); relation(bobId,"favorites","200");
        insertPost(201,bobId,"PUBLISHED"); favorite(201);
        db.execute((ConnectionCallback<Void>) connection -> {
            boolean mysql=connection.getMetaData().getDatabaseProductName().equals("MySQL");
            try (var statement=connection.createStatement()) {
                statement.execute(mysql ? "SET FOREIGN_KEY_CHECKS=0" : "SET REFERENTIAL_INTEGRITY FALSE");
                try { statement.executeUpdate("UPDATE posts SET author_id='missing-author' WHERE id=201"); }
                finally { statement.execute(mysql ? "SET FOREIGN_KEY_CHECKS=1" : "SET REFERENTIAL_INTEGRITY TRUE"); }
            }
            return null;
        });
        var first=page(alice,"1",null); assertEquals(List.of("10"),ids(first)); assertEquals(1,first.get("has_more").asInt());
        assertEquals("10",first.get("next_cursor").asText());
        var last=page(alice,"1","10"); assertEquals(List.of("9"),ids(last)); assertEquals(0,last.get("has_more").asInt());
        assertEquals(List.of("10","9"),ids(page(alice,"2",null))); assertTrue(ids(page(bob,null,null)).isEmpty());
        assertEquals(0,page(alice,"2",null).get("has_more").asInt());
        db.update("UPDATE posts SET status='PUBLISHED' WHERE id BETWEEN 100 AND 103");
        db.update("UPDATE users SET enabled=TRUE WHERE id='disabled'");
        assertEquals(List.of("200","103","102","101","100","10","9"),ids(page(alice,null,null)));
        assertEquals(List.of("200","103","102","101","100"),ids(page(bob,null,null)));
        assertEquals(13,db.queryForObject("SELECT COUNT(*) FROM interactions WHERE kind='favorites'",Integer.class));
    }

    @Test void disablingAnAuthorHidesAlreadyReadFavoritesAndRestoringPreservesRelations() throws Exception {
        insertPost(9,aliceId,"PUBLISHED"); insertPost(10,bobId,"PUBLISHED"); favorite(9); favorite(10);
        var first=page(alice,"1",null); assertEquals(List.of("10"),ids(first));
        assertEquals(1,first.get("has_more").asInt()); assertEquals("10",first.get("next_cursor").asText());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",bobId);
        var hidden=page(alice,"1",null); assertEquals(List.of("9"),ids(hidden));
        assertEquals(0,hidden.get("has_more").asInt()); assertTrue(hidden.get("next_cursor").isNull());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind='favorites'",Integer.class,aliceId));
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",bobId);
        assertEquals(first,page(alice,"1",null));
        var last=page(alice,"1","10"); assertEquals(List.of("9"),ids(last));
        assertEquals(0,last.get("has_more").asInt()); assertTrue(last.get("next_cursor").isNull());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind='favorites'",Integer.class,aliceId));
    }

    @Test void malformedTargetsMissingPostsAndOtherKindsNeverMatch() throws Exception {
        insertPost(9,bobId,"PUBLISHED"); insertPost(10,bobId,"PUBLISHED"); favorite(10);
        for (String target:List.of("9junk","09","9.0","9e0","+9"," 9","9 ","0","-9","999999","9223372036854775808")) relation(aliceId,"favorites",target);
        for (String kind:List.of("likes","follows","not-interested")) relation(aliceId,kind,"9");
        assertEquals(List.of("10"),ids(page(alice,"1",null))); assertEquals(0,page(alice,"1",null).get("has_more").asInt());
        assertTrue(ids(page(bob,null,null)).isEmpty());
    }

    @Test void originalFavoriteWritesAndIdListStayCompatibleAndRefavoriteKeepsIdOrder() throws Exception {
        insertPost(9,bobId,"PUBLISHED"); insertPost(10,bobId,"PUBLISHED");
        setFavorite(alice,"9",true); setFavorite(alice,"10",true); setFavorite(alice,"9",true);
        assertEquals(List.of("10","9"),ids(page(alice,null,null)));
        mvc.perform(get("/api/interactions/favorites").header("Authorization",alice)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].target_id").value("10")).andExpect(jsonPath("$[1].target_id").value("9"));
        setFavorite(alice,"9",false); setFavorite(alice,"9",false); assertEquals(List.of("10"),ids(page(alice,null,null)));
        setFavorite(alice,"9",true); assertEquals(List.of("10","9"),ids(page(alice,null,null)));
        db.update("UPDATE posts SET status='WITHDRAWN' WHERE id=10");
        setFavorite(alice,"10",false); assertEquals(List.of("9"),ids(page(alice,null,null)));
        db.update("UPDATE posts SET status='PUBLISHED' WHERE id=10"); setFavorite(alice,"10",true);
        assertEquals(List.of("10","9"),ids(page(alice,null,null)));
    }

    @Test void invalidParametersUseExistingInvalidArgumentFormat() throws Exception {
        for (String count:List.of("","0","51","-1","2147483648","1.5","abc"," 1","1 ","+1","01","1e1"))
            mvc.perform(get(PATH).header("Authorization",alice).param("count",count)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        for (String cursor:List.of(""," ","0","-1","+1","01","1.0","1e1","abc","1 ","9223372036854775808","9".repeat(100),"' OR 1=1 --"))
            mvc.perform(get(PATH).header("Authorization",alice).param("cursor",cursor)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
    }
}
