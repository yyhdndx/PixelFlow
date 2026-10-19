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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:connections-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class UserConnectionsApiTest {
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

    String account(String username) throws Exception {
        Map<String,String> credentials=Map.of("username",username,"password","connections-test-password");
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",username,"password",credentials.get("password"),"nickname",username))))
                .andExpect(status().isCreated());
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(credentials))).andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }

    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    String path(String kind) { return "/api/users/me/"+kind; }
    JsonNode page(String token,String kind,String count,String cursor) throws Exception {
        var request=get(path(kind)).header("Authorization",token);
        if (count!=null) request.param("count",count);
        if (cursor!=null) request.param("cursor",cursor);
        return body(mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn());
    }
    void user(String id,boolean enabled) {
        db.update("INSERT INTO users(id,username,password_hash,nickname,bio,avatar,role,enabled) VALUES(?,?,?,?,?,?,'CREATOR',?)",
                id,id,"not-a-real-password-hash","中文昵称","摄影\n旅行","https://example.com/avatar.jpg",enabled);
    }
    void follow(String from,String to) {
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'follows',?)",from,to);
    }
    void setFollow(String token,String target,boolean active) throws Exception {
        mvc.perform(put("/api/interactions/follows").header("Authorization",token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("targetId",target,"active",active)))).andExpect(status().isOk());
    }
    List<String> ids(JsonNode page) {
        List<String> ids=new ArrayList<>();
        page.get("user_list").forEach(card -> ids.add(card.get("user_id").asText()));
        return ids;
    }

    @Test void emptyListsHaveStableShapeAndAuthenticationIsRequired() throws Exception {
        for (String kind:List.of("followers","following")) {
            var result=page(alice,kind,null,null);
            assertEquals(4,result.size()); assertEquals(0,result.get("status_code").asInt());
            assertTrue(result.get("user_list").isArray()); assertTrue(ids(result).isEmpty());
            assertEquals(0,result.get("has_more").asInt()); assertTrue(result.get("next_cursor").isNull());
            mvc.perform(get(path(kind))).andExpect(status().isUnauthorized());
            mvc.perform(get(path(kind)).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        }
    }

    @Test void accountIsolationAndFollowBackStateAreCorrect() throws Exception {
        user("user-001",true); user("user-002",true);
        follow(aliceId,"user-001"); follow("user-001",aliceId); follow("user-002",aliceId);
        follow(bobId,"user-002");
        var followers=page(alice,"followers",null,null).get("user_list");
        assertEquals(2,followers.size());
        assertTrue(followers.get(0).get("is_following").asBoolean());
        assertFalse(followers.get(1).get("is_following").asBoolean());
        assertEquals(List.of("user-001"),ids(page(alice,"following",null,null)));
        assertEquals(List.of("user-002"),ids(page(bob,"following",null,null)));
        assertTrue(ids(page(bob,"followers",null,null)).isEmpty());
        for (String kind:List.of("followers","following")) {
            var spoofed=body(mvc.perform(get(path(kind)).header("Authorization",alice)
                    .param("userId",bobId).param("user_id",bobId)).andExpect(status().isOk()).andReturn());
            assertEquals(page(alice,kind,null,null),spoofed);
        }
        JsonNode card=followers.get(0);
        assertEquals(Set.of("user_id","nickname","bio","avatar","is_following"),json.convertValue(card,Map.class).keySet());
        assertEquals("中文昵称",card.get("nickname").asText());
        assertEquals("摄影\n旅行",card.get("bio").asText());
        assertEquals("https://example.com/avatar.jpg",card.get("avatar").asText());
        assertTrue(card.get("is_following").isBoolean());
        assertTrue(page(alice,"following",null,null).get("user_list").get(0).get("is_following").asBoolean());
    }

    @Test void allPagesUseStableIdOrderAndDefaultAndMaximumCounts() throws Exception {
        List<String> expected=new ArrayList<>();
        for (int i=1;i<=54;i++) {
            String id=String.format(Locale.ROOT,"user-%03d",i);
            user(id,true); follow(aliceId,id); follow(id,aliceId); expected.add(id);
        }
        for (String kind:List.of("followers","following")) {
            assertEquals(20,ids(page(alice,kind,null,null)).size());
            var first=page(alice,kind,"50",null);
            assertEquals(expected.subList(0,50),ids(first));
            assertEquals(1,first.get("has_more").asInt());
            assertEquals("user-050",first.get("next_cursor").asText());
            var last=page(alice,kind,"50",first.get("next_cursor").asText());
            assertEquals(expected.subList(50,54),ids(last));
            assertEquals(0,last.get("has_more").asInt()); assertTrue(last.get("next_cursor").isNull());
            List<String> visited=new ArrayList<>();
            String cursor=null;
            for (int pages=0;pages<10;pages++) {
                var result=page(alice,kind,"7",cursor); visited.addAll(ids(result));
                if (result.get("has_more").asInt()==0) break;
                cursor=result.get("next_cursor").asText();
            }
            assertEquals(expected,visited);
            assertTrue(ids(page(alice,kind,"1","zzzz")).isEmpty());
        }
    }

    @Test void hiddenAccountsAreFilteredBeforePaginationAndCanReturnAfterReenable() throws Exception {
        for (String id:List.of("user-001","user-002","user-003","user-004")) {
            user(id,id.compareTo("user-002")>0); follow(aliceId,id); follow(id,aliceId);
        }
        follow(aliceId,"000-missing");
        for (String kind:List.of("followers","following")) {
            var first=page(alice,kind,"1",null);
            assertEquals(List.of("user-003"),ids(first)); assertEquals(1,first.get("has_more").asInt());
            var last=page(alice,kind,"1",first.get("next_cursor").asText());
            assertEquals(List.of("user-004"),ids(last)); assertEquals(0,last.get("has_more").asInt());
            var exact=page(alice,kind,"2",null);
            assertEquals(2,ids(exact).size()); assertEquals(0,exact.get("has_more").asInt());
            assertTrue(exact.get("next_cursor").isNull());
        }
        db.update("UPDATE users SET enabled=TRUE WHERE id='user-001'");
        assertEquals(List.of("user-001","user-003","user-004"),ids(page(alice,"following",null,null)));
        assertEquals(9,db.queryForObject("SELECT COUNT(*) FROM interactions",Integer.class));
    }

    @Test void originalFollowWritesAndIdListRemainCompatible() throws Exception {
        setFollow(alice,bobId,true); setFollow(alice,bobId,true); setFollow(bob,aliceId,true);
        assertEquals(List.of(bobId),ids(page(alice,"following",null,null)));
        mvc.perform(get("/api/interactions/follows").header("Authorization",alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].target_id").value(bobId));
        setFollow(alice,bobId,false); setFollow(alice,bobId,false);
        assertTrue(ids(page(alice,"following",null,null)).isEmpty());
        assertTrue(ids(page(bob,"followers",null,null)).isEmpty());
        assertFalse(page(alice,"followers",null,null).get("user_list").get(0).get("is_following").asBoolean());
        setFollow(alice,bobId,true);
        assertTrue(page(alice,"followers",null,null).get("user_list").get(0).get("is_following").asBoolean());
    }

    @Test void otherInteractionKindsAndPreferencesDoNotAffectRelationships() throws Exception {
        for (String kind:List.of("likes","favorites","not-interested")) {
            db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,?,?)",aliceId,kind,bobId);
            db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,?,?)",bobId,kind,aliceId);
        }
        assertTrue(ids(page(alice,"followers",null,null)).isEmpty());
        assertTrue(ids(page(alice,"following",null,null)).isEmpty());
        follow(aliceId,bobId);
        db.update("INSERT INTO recommendation_blocked_authors(user_id,author_id) VALUES(?,?)",aliceId,bobId);
        db.update("INSERT INTO recommendation_preferences(user_id,personalized) VALUES(?,FALSE)",aliceId);
        assertEquals(List.of(bobId),ids(page(alice,"following",null,null)));
    }

    @Test void disabledAccountsAndExpiredSessionsCannotReadEitherList() throws Exception {
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        for (String kind:List.of("followers","following"))
            mvc.perform(get(path(kind)).header("Authorization",alice)).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId);
        db.update("UPDATE sessions SET expires_at=0 WHERE user_id=?",aliceId);
        for (String kind:List.of("followers","following"))
            mvc.perform(get(path(kind)).header("Authorization",alice)).andExpect(status().isUnauthorized());
    }

    @Test void invalidPaginationUsesExisting400ErrorFormat() throws Exception {
        for (String kind:List.of("followers","following")) {
            for (String count:List.of("","0","51","-1","2147483648","1.5","abc"," 1","+1"))
                mvc.perform(get(path(kind)).header("Authorization",alice).param("count",count))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
            for (String cursor:List.of(""," ","a".repeat(37),"a/b","' OR 1=1 --","a\nb"))
                mvc.perform(get(path(kind)).header("Authorization",alice).param("cursor",cursor))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        }
    }
}
