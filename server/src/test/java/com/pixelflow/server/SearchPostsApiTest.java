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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:search-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long",
        "pixelflow.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class SearchPostsApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String alice,bob,aliceId,bobId;

    @BeforeEach void setup() throws Exception {
        db.update("DELETE FROM interactions WHERE user_id IN (SELECT id FROM users WHERE username IN ('search_alice','search_bob'))");
        db.update("DELETE FROM posts WHERE author_id IN (SELECT id FROM users WHERE username IN ('search_alice','search_bob'))");
        db.update("DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE username IN ('search_alice','search_bob'))");
        db.update("DELETE FROM users WHERE username IN ('search_alice','search_bob')");
        alice=account("search_alice"); bob=account("search_bob");
        aliceId=db.queryForObject("SELECT id FROM users WHERE username='search_alice'",String.class);
        bobId=db.queryForObject("SELECT id FROM users WHERE username='search_bob'",String.class);
    }

    String account(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(
                Map.of("username",name,"password","search-test-password","nickname","中文作者")))).andExpect(status().isCreated());
        return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password","search-test-password"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }

    void fixture(long id,String title,String content,String state) {
        db.update("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,'[]',FALSE,?,1)",
                id,aliceId,title,content,state);
    }
    JsonNode page(String q,String count,String cursor,String token) throws Exception {
        var request=get("/api/search/posts").param("q",q);
        if (count!=null) request.param("count",count);
        if (cursor!=null) request.param("cursor",cursor);
        if (token!=null) request.header("Authorization",token);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    List<String> ids(JsonNode page) {
        var result=new ArrayList<String>(); page.get("post_list").forEach(card -> result.add(card.get("post_id").asText())); return result;
    }
    void invalid(String name,String value) throws Exception {
        var request=get("/api/search/posts");
        if (!name.equals("q")) request.param("q","摄影");
        mvc.perform(request.param(name,value)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test void matchesTitleOrAnchoredTopicCaseInsensitivelyWithoutSplitting() throws Exception {
        fixture(9,"Travel摄影","普通正文","PUBLISHED");
        fixture(10,"无关标题","正文 #tRaVeL摄影指南","PUBLISHED");
        fixture(100,"非前缀Travel","普通正文 travel","PUBLISHED");
        assertEquals(List.of("10","9"),ids(page("  TRAVEL  ",null,null,null)));
        assertEquals(List.of("10","9"),ids(page("#travel",null,null,null)));
        fixture(101,"城市 光影记录","","PUBLISHED");
        assertEquals(List.of("101"),ids(page("城市 光影",null,null,null)));
        assertEquals(List.of(),ids(page("城市 记录",null,null,null)));
        assertEquals(List.of(),ids(page("摄影",null,null,null)));
    }

    @Test void treatsWildcardAndEscapeCharactersLiterally() throws Exception {
        fixture(9,"100%_!\\记录","","PUBLISHED"); fixture(10,"100xxx记录","","PUBLISHED");
        assertEquals(List.of("9"),ids(page("100%_!\\",null,null,null)));
        fixture(100,"无关","正文 #50%_!\\话题","PUBLISHED");
        assertEquals(List.of("100"),ids(page("50%_!\\",null,null,null)));
        assertEquals(List.of(),ids(page("%",null,null,null)));
    }

    @Test void validatesQueryUtf16Boundaries() throws Exception {
        mvc.perform(get("/api/search/posts")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        for (String q:List.of("","   ","\n\t","#","#   ","中".repeat(61),"😀".repeat(30)+"a")) invalid("q",q);
        assertTrue(page("中".repeat(60),null,null,null).get("post_list").isEmpty());
        assertTrue(page("😀".repeat(30),null,null,null).get("post_list").isEmpty());
    }

    @Test void validatesCountAndCursor() throws Exception {
        for (String count:List.of("0","51","","-1","1.0","abc","9223372036854775808")) invalid("count",count);
        for (String cursor:List.of("0","-1","","1.5","abc","9223372036854775808")) invalid("cursor",cursor);
        assertTrue(page("摄影","1",Long.toString(Long.MAX_VALUE),null).get("post_list").isEmpty());
        assertTrue(page("摄影","50",null,null).get("post_list").isEmpty());
    }

    @Test void paginatesVisibleNumericIdsWithoutGapsOrDuplicates() throws Exception {
        var empty=page("摄影",null,null,null);
        assertEquals(Set.of("status_code","post_list","has_more","next_cursor"),keys(empty));
        assertEquals(0,empty.get("status_code").asInt()); assertEquals(0,empty.get("has_more").asInt()); assertTrue(empty.get("next_cursor").isNull());
        for (long id:List.of(9L,10L,100L)) fixture(id,"摄影"+id,"","PUBLISHED");
        for (long id:List.of(11L,99L,101L)) fixture(id,"摄影隐藏","","PENDING");
        var exact=page("摄影","3",null,null); assertEquals(List.of("100","10","9"),ids(exact));
        assertEquals(0,exact.get("has_more").asInt()); assertTrue(exact.get("next_cursor").isNull());
        List<String> all=new ArrayList<>(); String cursor=null;
        do {
            var result=page("摄影","1",cursor,null); all.addAll(ids(result));
            assertTrue(result.get("has_more").isInt());
            cursor=result.get("next_cursor").isNull() ? null : result.get("next_cursor").asText();
            if (cursor!=null) assertTrue(result.get("next_cursor").isTextual());
        } while (cursor!=null);
        assertEquals(List.of("100","10","9"),all);
        assertTrue(page("摄影","1","9",null).get("next_cursor").isNull());
    }

    @Test void firstPageIncludesMaximumLongId() throws Exception {
        fixture(Long.MAX_VALUE,"摄影最大值","","PUBLISHED");
        try {
            assertEquals(List.of(Long.toString(Long.MAX_VALUE)),ids(page("摄影",null,null,null)));
            assertTrue(ids(page("摄影",null,Long.toString(Long.MAX_VALUE),null)).isEmpty());
        } finally {
            db.update("DELETE FROM posts WHERE id=?",Long.MAX_VALUE);
            try (var connection=Objects.requireNonNull(db.getDataSource()).getConnection()) {
                if (connection.getMetaData().getDatabaseProductName().equals("MySQL"))
                    db.execute("ALTER TABLE posts AUTO_INCREMENT=1");
            }
        }
    }

    @Test void visibilityMatchesFeedAndRejectsInvalidOrDisabledTokens() throws Exception {
        long id=1;
        for (String state:List.of("DRAFT","PENDING","REJECTED","WITHDRAWN","PUBLISHED")) fixture(id++,"摄影","",state);
        assertEquals(List.of("5"),ids(page("摄影",null,null,null)));
        assertEquals(List.of("5"),ids(page("摄影",null,null,alice)));
        var feed=json.readTree(mvc.perform(get("/feed/").param("accept_video_clip","true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(ids(feed),ids(page("摄影",null,null,bob)));
        mvc.perform(get("/api/search/posts").param("q","摄影").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        assertTrue(ids(page("摄影",null,null,bob)).isEmpty());
        mvc.perform(get("/api/search/posts").param("q","摄影").header("Authorization",alice)).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId);
        assertEquals(List.of("5"),ids(page("摄影",null,null,null)));
        db.update("UPDATE posts SET status='WITHDRAWN' WHERE id=5");
        assertTrue(ids(page("摄影",null,null,null)).isEmpty());
    }

    @Test void reusesFullFeedCardAndCurrentViewerStateIncludingVideo() throws Exception {
        fixture(9,"摄影中文","正文 #摄影","PUBLISHED");
        db.update("UPDATE posts SET clips_json=?,has_video=TRUE WHERE id=9", "[{\"type\":1,\"width\":120,\"height\":80,\"url\":\"https://example.invalid/video.mp4\"},{\"type\":0,\"width\":40,\"height\":60,\"url\":\"https://example.invalid/image.png\"}]");
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'likes','9')",bobId);
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'favorites','9')",bobId);
        var guest=page("摄影",null,null,null).get("post_list").get(0);
        assertFalse(guest.get("is_liked").asBoolean()); assertFalse(guest.get("is_favorited").asBoolean());
        var card=page("摄影",null,null,bob).get("post_list").get(0);
        assertTrue(card.get("is_liked").asBoolean()); assertTrue(card.get("is_favorited").asBoolean());
        assertFalse(page("摄影",null,null,alice).get("post_list").get(0).get("is_liked").asBoolean());
        assertEquals(1,card.get("like_count").asInt()); assertEquals(aliceId,card.get("author").get("user_id").asText());
        assertEquals("中文作者",card.get("author").get("nickname").asText());
        assertEquals(1,card.get("clips").get(0).get("type").asInt()); assertEquals(0,card.get("clips").get(1).get("type").asInt());
        var feed=json.readTree(mvc.perform(get("/feed/").param("accept_video_clip","true").header("Authorization",bob))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(feed.get("post_list").get(0),card);
        assertEquals(Set.of("post_id","title","content","create_time","submit_time","review_time","publish_time","status","author","clips","media_ids","hashtag","music","like_count","is_liked","is_favorited"),keys(card));
    }

    Set<String> keys(JsonNode value) { var keys=new HashSet<String>(); value.fieldNames().forEachRemaining(keys::add); return keys; }
}
