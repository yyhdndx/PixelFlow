package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:following-feed-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long",
        "pixelflow.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class FollowingFeedApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String alice,bob,carol,aliceId,bobId,carolId;
    final List<String> owned=new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        alice=account("a"); bob=account("b"); carol=account("c");
        aliceId=owned.get(0); bobId=owned.get(1); carolId=owned.get(2);
    }

    @AfterEach void cleanup() {
        for (String id:owned) {
            db.update("DELETE FROM moderation_records WHERE post_id IN (SELECT id FROM posts WHERE author_id=?)",id);
            db.update("DELETE FROM post_media WHERE post_id IN (SELECT id FROM posts WHERE author_id=?)",id);
            db.update("DELETE FROM behavior_events WHERE user_id=?",id);
            db.update("DELETE FROM interactions WHERE user_id=?",id);
            db.update("DELETE FROM recommendation_blocked_authors WHERE user_id=?",id);
            db.update("DELETE FROM sessions WHERE user_id=?",id);
        }
        for (String id:owned) db.update("DELETE FROM posts WHERE author_id=?",id);
        for (String id:owned) db.update("DELETE FROM users WHERE id=?",id);
        owned.clear();
    }

    String account(String suffix) throws Exception {
        String name="ff_"+UUID.randomUUID().toString().replace("-","").substring(0,16)+suffix;
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(
                Map.of("username",name,"password","following-test-password","nickname","中文作者"))))
                .andExpect(status().isCreated());
        owned.add(db.queryForObject("SELECT id FROM users WHERE username=?",String.class,name));
        return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password","following-test-password"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }

    void fixture(long id,String author,String state) {
        db.update("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,'[]',FALSE,?,1)",
                id,author,"中文作品"+id,"正文 #摄影",state);
    }
    void follow(String token,String author,boolean active) throws Exception {
        mvc.perform(put("/api/interactions/follows").header("Authorization",token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("targetId",author,"active",active)))).andExpect(status().isOk());
    }
    JsonNode page(String token,String count,String cursor) throws Exception {
        var request=get("/api/feed/following").header("Authorization",token);
        if (count!=null) request.param("count",count);
        if (cursor!=null) request.param("cursor",cursor);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andReturn().getResponse().getContentAsString());
    }
    List<String> ids(JsonNode page) {
        var ids=new ArrayList<String>(); page.get("post_list").forEach(card -> ids.add(card.get("post_id").asText())); return ids;
    }

    @Test void emptyFollowUnfollowAndAccountIsolation() throws Exception {
        fixture(9,aliceId,"PUBLISHED"); fixture(10,carolId,"PUBLISHED");
        assertEquals(json.readTree("{\"status_code\":0,\"post_list\":[],\"has_more\":0,\"next_cursor\":null}"),page(bob,null,null));
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'favorites',?)",bobId,carolId);
        follow(bob,aliceId,true); follow(alice,carolId,true);
        assertEquals(List.of("9"),ids(page(bob,null,null)));
        assertEquals(List.of("10"),ids(page(alice,null,null)));
        follow(bob,aliceId,false);
        assertTrue(ids(page(bob,null,null)).isEmpty());
        follow(bob,carolId,true); db.update("DELETE FROM posts WHERE id=10");
        assertTrue(ids(page(bob,null,null)).isEmpty());
    }

    @Test void filtersStatesBeforePaginationAndRechecksAuthors() throws Exception {
        follow(bob,aliceId,true);
        for (long id:List.of(9L,10L,100L)) fixture(id,aliceId,"PUBLISHED");
        int hidden=101;
        for (String state:List.of("DRAFT","PENDING","REJECTED","WITHDRAWN")) fixture(hidden++,aliceId,state);
        fixture(200,carolId,"PUBLISHED");
        assertEquals(List.of("100","10"),ids(page(bob,"2",null)));
        assertEquals(List.of("9"),ids(page(bob,"2","10")));
        db.update("UPDATE users SET role='ADMIN' WHERE id=?",bobId);
        assertEquals(List.of("100","10","9"),ids(page(bob,null,null)));
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        assertTrue(ids(page(bob,"2","100")).isEmpty());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId);
        assertEquals(List.of("10","9"),ids(page(bob,"2","100")));
        db.update("UPDATE posts SET status='WITHDRAWN' WHERE id=10");
        assertEquals(List.of("100","9"),ids(page(bob,null,null)));
    }

    @Test void numericPaginationAndRepublishingDoNotChangeOrder() throws Exception {
        follow(bob,aliceId,true);
        for (long id:List.of(9L,10L,100L)) fixture(id,aliceId,"PUBLISHED");
        db.update("UPDATE posts SET publish_time=1000-id WHERE author_id=?",aliceId);
        var exact=page(bob,"3",null); assertEquals(List.of("100","10","9"),ids(exact));
        assertEquals(0,exact.get("has_more").asInt()); assertTrue(exact.get("next_cursor").isNull());
        List<String> all=new ArrayList<>(); String cursor=null;
        do {
            var result=page(bob,"1",cursor); all.addAll(ids(result));
            assertTrue(result.get("has_more").isInt());
            cursor=result.get("next_cursor").isNull() ? null : result.get("next_cursor").asText();
            if (cursor!=null) assertTrue(result.get("next_cursor").isTextual());
        } while (cursor!=null);
        assertEquals(List.of("100","10","9"),all);
        db.update("UPDATE posts SET publish_time=9999 WHERE id=9");
        assertEquals(all,ids(page(bob,null,null)));
        assertTrue(ids(page(bob,"1","9")).isEmpty());
    }

    @Test void validatesCountCursorAndMaximumId() throws Exception {
        for (String name:List.of("count","cursor")) {
            var values=new ArrayList<>(List.of("","0","-1","1.5","abc","9223372036854775808"));
            if (name.equals("count")) values.add("51");
            for (String value:values) mvc.perform(get("/api/feed/following").header("Authorization",bob).param(name,value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        follow(bob,aliceId,true);
        fixture(Long.MAX_VALUE,aliceId,"PUBLISHED");
        try {
            assertEquals(List.of(Long.toString(Long.MAX_VALUE)),ids(page(bob,"50",null)));
            assertTrue(ids(page(bob,"1",Long.toString(Long.MAX_VALUE))).isEmpty());
        } finally {
            db.update("DELETE FROM posts WHERE id=?",Long.MAX_VALUE);
            try (var connection=Objects.requireNonNull(db.getDataSource()).getConnection()) {
                if (connection.getMetaData().getDatabaseProductName().equals("MySQL")) db.execute("ALTER TABLE posts AUTO_INCREMENT=1");
            }
        }
    }

    @Test void authenticationAndClientIdentityCannotBeBypassed() throws Exception {
        mvc.perform(get("/api/feed/following")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/feed/following").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        fixture(9,aliceId,"PUBLISHED"); follow(carol,aliceId,true);
        mvc.perform(get("/api/feed/following").header("Authorization",bob).param("userId",carolId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.post_list").isEmpty());
        db.update("DELETE FROM sessions WHERE user_id=?",bobId);
        mvc.perform(get("/api/feed/following").header("Authorization",bob)).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",carolId);
        mvc.perform(get("/api/feed/following").header("Authorization",carol)).andExpect(status().isUnauthorized());
    }

    @Test void completeCardsMatchFeedIncludingVideoTimesAndViewerState() throws Exception {
        follow(bob,aliceId,true); follow(carol,aliceId,true); fixture(9,aliceId,"PUBLISHED");
        db.update("UPDATE posts SET clips_json=?,has_video=TRUE,submit_time=2,review_time=3,publish_time=3 WHERE id=9",
                "[{\"type\":1,\"width\":120,\"height\":80,\"url\":\"https://example.invalid/video.mp4\"},{\"type\":0,\"width\":40,\"height\":60,\"url\":\"https://example.invalid/image.png\"}]");
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'likes','9')",bobId);
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'favorites','9')",bobId);
        db.update("INSERT INTO recommendation_blocked_authors(user_id,author_id) VALUES(?,?)",bobId,aliceId);
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'not-interested','9')",bobId);
        var feed=json.readTree(mvc.perform(get("/feed/").param("accept_video_clip","true").header("Authorization",bob))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var card=page(bob,null,null).get("post_list").get(0);
        assertEquals(feed.get("post_list").get(0),card);
        for (String field:List.of("create_time","submit_time","review_time","publish_time","author","clips","media_ids","comment_count")) assertTrue(card.has(field));
        assertEquals(2,card.get("clips").size()); assertEquals(3,card.get("publish_time").asInt());
        assertTrue(card.get("is_liked").asBoolean()); assertTrue(card.get("is_favorited").asBoolean());
        var other=page(carol,null,null).get("post_list").get(0);
        assertFalse(other.get("is_liked").asBoolean()); assertFalse(other.get("is_favorited").asBoolean());
        db.update("UPDATE posts SET submit_time=NULL,review_time=NULL,publish_time=NULL WHERE id=9");
        var legacy=page(bob,null,null).get("post_list").get(0);
        for (String field:List.of("submit_time","review_time","publish_time")) assertTrue(legacy.get(field).isNull());
    }
}
