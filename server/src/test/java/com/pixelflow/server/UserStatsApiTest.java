package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:stats-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class UserStatsApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private static final String PASSWORD="test-password-2026";
    @BeforeEach void clean() {
        for (String t:List.of("recommendation_suppressed_topics","recommendation_blocked_authors","recommendation_preferences",
                "event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events",
                "interactions","posts","sessions","users")) db.update("DELETE FROM "+t);
    }
    private String encode(Object v) throws Exception { return json.writeValueAsString(v); }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private String token(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(encode(Map.of("username",name,"password",PASSWORD,"nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(encode(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk()).andReturn())
                .get("accessToken").asText();
    }
    private String userId(String name) { return db.queryForObject("SELECT id FROM users WHERE username=?",String.class,name); }
    private void insertUser(String id,String username,boolean enabled) {
        db.update("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES(?,?,?,?,?,?)",
                id,username,"x",username,"CREATOR",enabled);
    }
    private void insertPost(String authorId,String status) {
        db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,FALSE,?,0)",
                authorId,"t","c","[]",status);
    }
    private void follow(String followerId,String targetId) {
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'follows',?)",followerId,targetId);
    }

    private void assertCounts(String token,String id,long posts,long followers,long following) throws Exception {
        JsonNode response=body(mvc.perform(get("/api/users/me/stats").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn());
        assertEquals(Set.of("user_id","post_count","follower_count","following_count"),
                json.convertValue(response,Map.class).keySet());
        assertEquals(id,response.get("user_id").asText());
        for (String field:List.of("post_count","follower_count","following_count")) {
            assertTrue(response.get(field).isIntegralNumber());
            assertTrue(response.get(field).asLong()>=0);
        }
        assertEquals(posts,response.get("post_count").asLong());
        assertEquals(followers,response.get("follower_count").asLong());
        assertEquals(following,response.get("following_count").asLong());
    }

    private void setFollow(String token,String target,boolean active) throws Exception {
        mvc.perform(put("/api/interactions/follows").header("Authorization",token).contentType("application/json")
                .content(encode(Map.of("targetId",target,"active",active)))).andExpect(status().isOk());
    }

    @Test void accountsAreIsolatedAndClientCannotChooseTheStatsOwner() throws Exception {
        String alice=token("alice"), bob=token("bob"), a=userId("alice"), b=userId("bob");
        insertPost(a,"PUBLISHED");
        insertPost(b,"PUBLISHED"); insertPost(b,"PUBLISHED");
        setFollow(alice,b,true);
        assertCounts(alice,a,1,0,1);
        assertCounts(bob,b,2,1,0);
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice)
                        .param("userId",b).param("user_id",b))
                .andExpect(status().isOk()).andExpect(jsonPath("user_id").value(a))
                .andExpect(jsonPath("post_count").value(1));
        // Repeated reads must not mutate counts.
        assertCounts(alice,a,1,0,1);
        assertCounts(bob,b,2,1,0);
    }

    @Test void publicationAndWithdrawalChangeCountsThroughRealEndpoints() throws Exception {
        String alice=token("alice"), admin=token("reviewer"), a=userId("alice");
        db.update("UPDATE users SET role='ADMIN' WHERE username='reviewer'");
        String postId=body(mvc.perform(post("/api/posts").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("title","统计测试","content","正文",
                        "clips",List.of(Map.of("type",0,"width",600,"height",800,"url","https://example.com/a.png"))))))
                .andExpect(status().isCreated()).andReturn()).get("post_id").asText();
        assertCounts(alice,a,0,0,0);
        mvc.perform(post("/api/posts/"+postId+"/publish").header("Authorization",alice)).andExpect(status().isOk());
        assertCounts(alice,a,0,0,0);
        mvc.perform(post("/api/admin/posts/"+postId+"/moderation").header("Authorization",admin)
                .contentType("application/json").content(encode(Map.of("status","PUBLISHED","reason","通过"))))
                .andExpect(status().isOk());
        assertCounts(alice,a,1,0,0);
        mvc.perform(post("/api/posts/"+postId+"/withdraw").header("Authorization",alice)).andExpect(status().isOk());
        assertCounts(alice,a,0,0,0);
    }

    @Test void repeatedFollowAndUnfollowAreIdempotent() throws Exception {
        String alice=token("alice"), bob=token("bob"), a=userId("alice"), b=userId("bob");
        for (int i=0;i<2;i++) {
            setFollow(alice,b,true);
            assertCounts(alice,a,0,0,1); assertCounts(bob,b,0,1,0);
        }
        for (int i=0;i<2;i++) {
            setFollow(alice,b,false);
            assertCounts(alice,a,0,0,0); assertCounts(bob,b,0,0,0);
        }
    }

    @Test void disablingAndReenablingAccountsChangesBothCountsWithoutDeletingRelations() throws Exception {
        String alice=token("alice"), bob=token("bob"), a=userId("alice"), b=userId("bob");
        setFollow(alice,b,true); setFollow(bob,a,true);
        assertCounts(alice,a,0,1,1);
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",b);
        assertCounts(alice,a,0,0,0);
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob)).andExpect(status().isUnauthorized());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM interactions WHERE kind='follows'",Integer.class));
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",b);
        assertCounts(alice,a,0,1,1); assertCounts(bob,b,0,1,1);
    }

    @Test void missingTargetsAndOtherInteractionKindsDoNotInflateCounts() throws Exception {
        String alice=token("alice"), bob=token("bob"), a=userId("alice"), b=userId("bob");
        follow(a,"missing-user"); // target_id is polymorphic and has no user FK.
        for (String kind:List.of("likes","favorites","not-interested")) {
            db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,?,?)",a,kind,b);
            db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,?,?)",b,kind,a);
        }
        setFollow(alice,b,true);
        db.update("INSERT INTO recommendation_preferences(user_id,personalized) VALUES(?,FALSE)",a);
        db.update("INSERT INTO recommendation_blocked_authors(user_id,author_id) VALUES(?,?)",a,b);
        assertCounts(alice,a,0,0,1); assertCounts(bob,b,0,1,0);
    }

    @Test void expiredSignedTokenAndExpiredSessionCannotReadStats() throws Exception {
        String alice=token("alice"), a=userId("alice");
        String session=db.queryForObject("SELECT id FROM sessions WHERE user_id=?",String.class,a);
        Instant now=Instant.now();
        var encoder=new NimbusJwtEncoder(new ImmutableSecret<>(
                "test-secret-at-least-thirty-two-bytes-long".getBytes(StandardCharsets.UTF_8)));
        var claims=JwtClaimsSet.builder().issuer("pixelflow")
                .subject(a).id(session).issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600)).build();
        var header=JwsHeader.with(MacAlgorithm.HS256).build();
        String expired=encoder.encode(JwtEncoderParameters.from(header,claims)).getTokenValue();
        mvc.perform(get("/api/users/me/stats").header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized());
        assertCounts(alice,a,0,0,0);
        db.update("UPDATE sessions SET expires_at=? WHERE id=?",now.minusSeconds(3600).getEpochSecond(),session);
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice)).andExpect(status().isUnauthorized());
    }

    @Test void newAccountHasZeroCounts() throws Exception {
        String alice=token("alice");
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("user_id").value(userId("alice")))
                .andExpect(jsonPath("post_count").value(0))
                .andExpect(jsonPath("follower_count").value(0))
                .andExpect(jsonPath("following_count").value(0));
    }

    @Test void anonymousAndInvalidTokenGet401() throws Exception {
        mvc.perform(get("/api/users/me/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/stats").header("Authorization","Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test void onlyPublishedPostsAreCounted() throws Exception {
        String alice=token("alice"), id=userId("alice");
        for (String status:List.of("DRAFT","PENDING","PUBLISHED","REJECTED","WITHDRAWN","PUBLISHED"))
            insertPost(id,status);
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("post_count").value(2))
                .andExpect(jsonPath("follower_count").value(0))
                .andExpect(jsonPath("following_count").value(0));
    }

    @Test void followAndUnfollowUpdateBothSides() throws Exception {
        String alice=token("alice"), bob=token("bob");
        String aliceId=userId("alice"), bobId=userId("bob");
        follow(aliceId,bobId);
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(jsonPath("following_count").value(1))
                .andExpect(jsonPath("follower_count").value(0));
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob))
                .andExpect(jsonPath("follower_count").value(1))
                .andExpect(jsonPath("following_count").value(0));
        mvc.perform(put("/api/interactions/follows").header("Authorization",alice).contentType("application/json")
                .content(encode(Map.of("targetId",bobId,"active",false)))).andExpect(status().isOk());
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice)).andExpect(jsonPath("following_count").value(0));
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob)).andExpect(jsonPath("follower_count").value(0));
    }

    @Test void countsAreNotTruncatedBeyondPageSize() throws Exception {
        String alice=token("alice"), bob=token("bob");
        String bobId=userId("bob");
        for (int i=0;i<60;i++) { String fid="follower-"+i; insertUser(fid,"fan"+i,true); follow(fid,bobId); }
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob))
                .andExpect(jsonPath("follower_count").value(60));
        String aliceId=userId("alice");
        for (int i=0;i<55;i++) { String tid="star-"+i; insertUser(tid,"star"+i,true); follow(aliceId,tid); }
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(jsonPath("following_count").value(55));
    }

    @Test void disabledAccountsAreNotCounted() throws Exception {
        String alice=token("alice"), bob=token("bob");
        String aliceId=userId("alice"), bobId=userId("bob");
        insertUser("ghost-f","ghostf",false);
        insertUser("ghost-t","ghostt",false);
        follow("ghost-f",bobId);
        follow(aliceId,"ghost-t");
        follow(aliceId,bobId);
        mvc.perform(get("/api/users/me/stats").header("Authorization",bob))
                .andExpect(jsonPath("follower_count").value(1));
        mvc.perform(get("/api/users/me/stats").header("Authorization",alice))
                .andExpect(jsonPath("following_count").value(1));
    }
}
