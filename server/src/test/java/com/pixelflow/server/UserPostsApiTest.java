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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:user-posts-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class UserPostsApiTest {
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
        var credentials=Map.of("username",name,"password","user-posts-test-password");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(
                Map.of("username",name,"password",credentials.get("password"),"nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(credentials))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("accessToken").asText();
    }

    String path(String id) { return "/api/users/"+id+"/posts"; }
    void fixture(long id,String author,String state) {
        db.update("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,'[]',FALSE,?,1)",
                id,author,"作品"+id,"中文正文",state);
    }
    JsonNode page(String token,String author,String count,String cursor) throws Exception {
        var request=get(path(author)).header("Authorization",token);
        if (count!=null) request.param("count",count);
        if (cursor!=null) request.param("cursor",cursor);
        return json.readTree(mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
    }
    List<String> ids(JsonNode page) {
        var result=new ArrayList<String>();
        page.get("post_list").forEach(card -> result.add(card.get("post_id").asText()));
        return result;
    }
    Set<String> keys(JsonNode node) {
        Set<String> result=new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result;
    }

    @Test void emptyExactAndExtraRowsHaveCorrectEnvelope() throws Exception {
        var empty=page(alice,bobId,null,null);
        assertEquals(Set.of("status_code","post_list","has_more","next_cursor"),keys(empty));
        assertTrue(empty.get("status_code").isIntegralNumber()); assertEquals(0,empty.get("status_code").asInt());
        assertEquals(List.of(),ids(empty)); assertEquals(0,empty.get("has_more").asInt()); assertTrue(empty.get("next_cursor").isNull());
        fixture(9,bobId,"PUBLISHED"); fixture(10,bobId,"PUBLISHED");
        var exact=page(alice,bobId,"2",null);
        assertEquals(List.of("10","9"),ids(exact)); assertEquals(0,exact.get("has_more").asInt()); assertTrue(exact.get("next_cursor").isNull());
        fixture(100,bobId,"PUBLISHED");
        var extra=page(alice,bobId,"2",null);
        assertEquals(List.of("100","10"),ids(extra)); assertEquals(1,extra.get("has_more").asInt());
        assertTrue(extra.get("next_cursor").isTextual()); assertEquals("10",extra.get("next_cursor").asText());
        assertEquals(List.of("9"),ids(page(alice,bobId,"2","10")));
        assertEquals(List.of(),ids(page(alice,bobId,"2","1")));
    }

    @Test void numericOrderMaximumIdAndTraversalHaveNoDuplicatesOrOmissions() throws Exception {
        for (long id:List.of(9L,10L,100L,101L,102L,Long.MAX_VALUE)) fixture(id,bobId,"PUBLISHED");
        var seen=new ArrayList<String>(); String cursor=null;
        do {
            var result=page(alice,bobId,"2",cursor); seen.addAll(ids(result));
            cursor=result.get("next_cursor").isNull() ? null : result.get("next_cursor").asText();
        } while (cursor!=null);
        assertEquals(List.of(Long.toString(Long.MAX_VALUE),"102","101","100","10","9"),seen);
        assertEquals(List.of("102","101","100","10","9"),ids(page(alice,bobId,"50",Long.toString(Long.MAX_VALUE))));
        assertEquals(List.of("9"),ids(page(alice,bobId,"01","00010")));
    }

    @Test void defaultsAndCountLimitsAreApplied() throws Exception {
        for (long id=1;id<=51;id++) fixture(id,bobId,"PUBLISHED");
        assertEquals(20,ids(page(alice,bobId,null,null)).size());
        assertEquals(1,ids(page(alice,bobId,"1",null)).size());
        assertEquals(50,ids(page(alice,bobId,"50",null)).size());
        for (String value:List.of("","0","51","-1","1.5","no","999999999999999999999","+1"," 1"))
            mvc.perform(get(path(bobId)).header("Authorization",alice).param("count",value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        for (String value:List.of("","0","-1","1.5","no","9223372036854775808","+1"," 1"))
            mvc.perform(get(path(bobId)).header("Authorization",alice).param("cursor",value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
    }

    @Test void authorScopeStatusesAndViewerIdentityAreIndependentOfQueryParameters() throws Exception {
        fixture(9,bobId,"PUBLISHED"); fixture(10,aliceId,"PUBLISHED");
        long id=20;
        for (String state:List.of("DRAFT","PENDING","REJECTED","WITHDRAWN")) fixture(id++,bobId,state);
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'likes','9')",aliceId);
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'likes','10')",bobId);
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'not-interested','9')",aliceId);
        db.update("INSERT INTO recommendation_blocked_authors(user_id,author_id) VALUES(?,?)",aliceId,bobId);
        var result=page(alice,bobId,null,null);
        assertEquals(List.of("9"),ids(result)); assertTrue(result.get("post_list").get(0).get("is_liked").asBoolean());
        assertFalse(page(bob,bobId,null,null).get("post_list").get(0).get("is_liked").asBoolean());
        assertTrue(page(bob,aliceId,null,null).get("post_list").get(0).get("is_liked").asBoolean());
        mvc.perform(get(path(bobId)).header("Authorization",alice).param("userId",bobId).param("actorId",bobId))
                .andExpect(status().isOk()).andExpect(jsonPath("post_list[0].is_liked").value(true));
        assertEquals(List.of("9"),ids(page(bob,bobId,null,null)));
        db.update("UPDATE users SET role='ADMIN' WHERE id=?",aliceId);
        assertEquals(List.of("9"),ids(page(alice,bobId,null,null)));
    }

    @Test void filteringPrecedesLimitAndWithdrawalIsImmediatelyVisible() throws Exception {
        for (long id=1;id<=8;id++) fixture(id,bobId,id%2==0 ? "PUBLISHED" : "DRAFT");
        var first=page(alice,bobId,"2",null); assertEquals(List.of("8","6"),ids(first));
        assertEquals(List.of("4","2"),ids(page(alice,bobId,"2","6")));
        mvc.perform(post("/api/posts/8/withdraw").header("Authorization",bob)).andExpect(status().isOk());
        assertEquals(List.of("6","4"),ids(page(alice,bobId,"2",null)));
    }

    @Test void eachPageChecksTargetEnabledAndMeIsAnOrdinaryId() throws Exception {
        fixture(9,bobId,"PUBLISHED"); fixture(10,bobId,"PUBLISHED");
        assertEquals("10",page(alice,bobId,"1",null).get("next_cursor").asText());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",bobId);
        for (String id:List.of(bobId,"missing","me"))
            mvc.perform(get(path(id)).header("Authorization",alice).param("cursor","10"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("USER_NOT_FOUND"));
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",bobId);
        assertEquals(List.of("9"),ids(page(alice,bobId,"1","10")));
        db.update("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES('me','literal_me','hash','literal','CREATOR',TRUE)");
        assertEquals(List.of(),ids(page(alice,"me",null,null)));
        for (String id:List.of("a".repeat(37),"bad.id"))
            mvc.perform(get(path(id)).header("Authorization",alice)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
    }

    @Test void authenticationSessionAndEnabledViewerAreRequired() throws Exception {
        mvc.perform(get(path(bobId))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(bobId)).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        mvc.perform(get(path(bobId)).header("Authorization",alice)).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId);
        db.update("UPDATE sessions SET expires_at=0 WHERE user_id=?",aliceId);
        mvc.perform(get(path(bobId)).header("Authorization",alice)).andExpect(status().isUnauthorized());
    }

    @Test void cardsPreserveFieldsMediaOrderAndDetailFeedShape() throws Exception {
        fixture(9,bobId,"PUBLISHED"); fixture(10,bobId,"PUBLISHED");
        db.update("UPDATE users SET nickname='中文作者',bio='',avatar='https://example.com/avatar.png' WHERE id=?",bobId);
        String clips="[{\"type\":0,\"width\":600,\"height\":800,\"url\":\"https://example.com/first.png\"},"
                +"{\"type\":1,\"width\":1280,\"height\":720,\"url\":\"https://example.com/second.mp4\"}]";
        db.update("UPDATE posts SET clips_json=?,has_video=TRUE WHERE id=10",clips);
        for (String media:List.of("first","second"))
            db.update("INSERT INTO media(id,owner_id,width,height,content_bytes,created_at) VALUES(?,?,1,1,?,1)",media,bobId,new byte[]{1});
        db.update("INSERT INTO post_media(post_id,media_id,position) VALUES(10,'second',0),(10,'first',1)");
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'likes','10')",aliceId);
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'likes','010')",bobId);
        var card=page(alice,bobId,null,null).get("post_list").get(0);
        assertEquals(Set.of("post_id","title","content","create_time","status","media_ids","author","clips","hashtag","music","like_count","is_liked"),keys(card));
        assertTrue(card.get("post_id").isTextual()); assertTrue(card.get("create_time").isIntegralNumber());
        assertTrue(card.get("like_count").isIntegralNumber()); assertTrue(card.get("is_liked").isBoolean());
        assertEquals(1,card.get("like_count").asInt()); assertTrue(card.get("is_liked").asBoolean());
        assertFalse(page(bob,bobId,null,null).get("post_list").get(0).get("is_liked").asBoolean());
        assertEquals("作品10",card.get("title").asText()); assertEquals("中文正文",card.get("content").asText());
        assertEquals(Set.of("user_id","nickname","avatar"),keys(card.get("author")));
        assertEquals(bobId,card.get("author").get("user_id").asText());
        assertEquals("中文作者",card.get("author").get("nickname").asText());
        assertEquals("https://example.com/avatar.png",card.get("author").get("avatar").asText());
        assertEquals(json.readTree(clips),card.get("clips"));
        assertEquals(json.readTree("[\"second\",\"first\"]"),card.get("media_ids"));
        assertTrue(card.get("music").isNull()); assertTrue(card.get("hashtag").isArray());
        var detail=json.readTree(mvc.perform(get("/api/posts/10").header("Authorization",alice))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(detail,card);
        var feed=json.readTree(mvc.perform(get("/feed/").param("accept_video_clip","true").header("Authorization",alice))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(feed.get("post_list").get(0),card);
    }
}
