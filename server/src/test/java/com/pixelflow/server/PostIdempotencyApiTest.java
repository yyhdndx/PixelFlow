package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import com.pixelflow.server.content.PostCreationIdempotencyStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:post-idempotency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class PostIdempotencyApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoSpyBean PostCreationIdempotencyStore store;
    String alice,bob,aliceId,bobId;
    final String draft="{\"title\":\"作品\",\"content\":\"正文\"}";
    @BeforeEach void setup() throws Exception {
        reset(store);
        for (String table:List.of("post_creation_idempotency","recommendation_suppressed_topics","recommendation_blocked_authors","recommendation_preferences",
                "event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events","interactions","posts","sessions","users"))
            db.update("DELETE FROM "+table);
        alice=account("idem_alice"); bob=account("idem_bob");
        aliceId=db.queryForObject("SELECT id FROM users WHERE username='idem_alice'",String.class);
        bobId=db.queryForObject("SELECT id FROM users WHERE username='idem_bob'",String.class);
    }
    String account(String name) throws Exception {
        String credentials=json.writeValueAsString(Map.of("username",name,"password","idempotency-password"));
        mvc.perform(post("/api/auth/register").contentType("application/json").content(
                json.writeValueAsString(Map.of("username",name,"password","idempotency-password","nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json").content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }
    MvcResult create(String token,String key,String body,int expected) throws Exception {
        var request=post("/api/posts").contentType("application/json").content(body);
        if (token!=null) request.header("Authorization",token);
        if (key!=null) request.header("Idempotency-Key",key);
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }
    String id(MvcResult response) throws Exception { return json.readTree(response.getResponse().getContentAsString()).get("post_id").asText(); }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    String media(String owner) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO media(id,owner_id,width,height,content_bytes,created_at) VALUES(?,?,2,3,?,1)",id,owner,new byte[]{1});
        return id;
    }
    String withMedia(String... ids) throws Exception { return json.writeValueAsString(Map.of("title","作品","content","正文","mediaIds",ids)); }
    @Test void legacyAndNormalizedReplay() throws Exception {
        assertNotEquals(id(create(alice,null,draft,201)),id(create(alice,null,draft,201)));
        String key=UUID.randomUUID().toString(); String first=id(create(alice,key.toUpperCase(Locale.ROOT),draft,201));
        for (String body:List.of("{\"content\":\"正文\",\"title\":\"作品\",\"clips\":null,\"mediaIds\":null}",
                "{\"title\":\"作品\",\"content\":\"正文\",\"clips\":[],\"mediaIds\":[]}")) assertEquals(first,id(create(alice,key,body,200)));
        assertEquals(3,count("posts")); assertEquals(1,count("post_creation_idempotency"));
        assertEquals(key,db.queryForObject("SELECT request_key FROM post_creation_idempotency",String.class));
    }
    @Test void unpairedSurrogatesConflictAndValidEmojiReplay() throws Exception {
        for (String field:List.of("title","content")) {
            String key=UUID.randomUUID().toString();
            String original=json.writeValueAsString(Map.of("title","?","content","?"));
            String first=id(create(alice,key,original,201));
            String changed=field.equals("title")
                    ? "{\"title\":\"\\uD800\",\"content\":\"?\"}"
                    : "{\"title\":\"?\",\"content\":\"\\uD800\"}";
            var conflict=create(alice,key,changed,409);
            assertEquals("IDEMPOTENCY_CONFLICT",json.readTree(conflict.getResponse().getContentAsString()).get("code").asText());
            assertEquals(first,id(create(alice,key,original,200)));
        }
        String key=UUID.randomUUID().toString();
        String emoji="\uD83D\uDE00";
        String body=json.writeValueAsString(Map.of("title",emoji,"content",emoji));
        String first=id(create(alice,key,body,201));
        var replay=create(alice,key,body,200);
        assertEquals(first,id(replay));
        var post=json.readTree(replay.getResponse().getContentAsString());
        assertEquals(emoji,post.get("title").asText()); assertEquals(emoji,post.get("content").asText());
        assertEquals(3,count("posts")); assertEquals(3,count("post_creation_idempotency"));
    }
    @Test void strictHeadersAndAuthenticationDoNotClaimKey() throws Exception {
        String key=UUID.randomUUID().toString();
        for (String bad:List.of(""," ","1-1-1-1-1","{"+key+"}",key+","+key,key+" ")) create(alice,bad,draft,400);
        mvc.perform(post("/api/posts").header("Authorization",alice).header("Idempotency-Key",key,key)
                .contentType("application/json").content(draft)).andExpect(status().isBadRequest());
        create(null,key,draft,401); create("Bearer invalid",key,draft,401);
        db.update("UPDATE sessions SET expires_at=0 WHERE user_id=?",bobId); create(bob,key,draft,401);
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId); create(alice,key,draft,401);
        assertEquals(0,count("post_creation_idempotency"));
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId); create(alice,key,draft,201);
    }
    @Test void usersHaveIndependentKeysAndPrivateDrafts() throws Exception {
        String key=UUID.randomUUID().toString(); String a=id(create(alice,key,draft,201)); String b=id(create(bob,key,draft,201));
        assertNotEquals(a,b);
        mvc.perform(get("/api/posts/"+a).header("Authorization",bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/posts/"+b).header("Authorization",alice)).andExpect(status().isNotFound());
        assertEquals(a,id(create(alice,key,draft,200))); assertEquals(b,id(create(bob,key,draft,200)));
    }
    @Test void everyLogicalFieldAndOrderParticipatesInFingerprint() throws Exception {
        String key=UUID.randomUUID().toString(); create(alice,key,draft,201);
        for (String changed:List.of("{\"title\":\"作品 \",\"content\":\"正文\"}","{\"title\":\"作品\",\"content\":\"正文 \"}")) {
            var result=create(alice,key,changed,409);
            assertEquals("IDEMPOTENCY_CONFLICT",json.readTree(result.getResponse().getContentAsString()).get("code").asText());
        }
        String a=media(aliceId),b=media(aliceId),c=media(aliceId),mk=UUID.randomUUID().toString();
        String first=id(create(alice,mk,withMedia(a,b),201));
        create(alice,mk,withMedia(b,a),409); create(alice,mk,withMedia(a,c),409);
        assertEquals(first,id(create(alice,mk,withMedia(a,b),200))); assertEquals(2,count("post_media"));
        String ck=UUID.randomUUID().toString();
        var clip=new LinkedHashMap<String,Object>(Map.of("type",0,"width",2,"height",3,"url","https://example.com/a.png"));
        String body=json.writeValueAsString(Map.of("title","作品","content","正文","clips",List.of(clip)));
        create(alice,ck,body,201);
        for (String field:List.of("type","width","height","url")) {
            var changed=new LinkedHashMap<>(clip); changed.put(field,field.equals("url") ? "https://example.com/b.png" : field.equals("type") ? 1 : 4);
            create(alice,ck,json.writeValueAsString(Map.of("title","作品","content","正文","clips",List.of(changed))),409);
        }
        create(alice,ck,body,200);
        var second=new LinkedHashMap<>(clip); second.put("url","https://example.com/second.png");
        String orderKey=UUID.randomUUID().toString();
        create(alice,orderKey,json.writeValueAsString(Map.of("title","作品","content","正文","clips",List.of(clip,second))),201);
        create(alice,orderKey,json.writeValueAsString(Map.of("title","作品","content","正文","clips",List.of(second,clip))),409);
    }
    @Test void replayReturnsCurrentStateWithoutResolvingOriginalMedia() throws Exception {
        String media=media(aliceId),key=UUID.randomUUID().toString(),body=withMedia(media);
        String id=id(create(alice,key,body,201));
        mvc.perform(put("/api/posts/"+id).header("Authorization",alice).contentType("application/json").content(draft.replace("作品","已编辑"))).andExpect(status().isOk());
        db.update("DELETE FROM media WHERE id=?",media);
        var replay=create(alice,key,body,200);
        assertEquals("已编辑",json.readTree(replay.getResponse().getContentAsString()).get("title").asText());
        db.update("UPDATE posts SET clips_json='[{\"type\":0,\"width\":1,\"height\":1,\"url\":\"https://example.com/a.png\"}]' WHERE id=?",Long.parseLong(id));
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",alice)).andExpect(status().isOk());
        assertEquals("PENDING",json.readTree(create(alice,key,body,200).getResponse().getContentAsString()).get("status").asText());
        db.update("DELETE FROM posts WHERE id=?",Long.parseLong(id));
        var missing=create(alice,key,body,404);
        assertEquals("POST_NOT_FOUND",json.readTree(missing.getResponse().getContentAsString()).get("code").asText());
        assertEquals(0,count("posts")); assertEquals(1,count("post_creation_idempotency"));
    }
    @Test void invalidMediaAndLateWriteFailureRollBackEverything() throws Exception {
        String key=UUID.randomUUID().toString();
        create(alice,key,withMedia("missing"),404); create(alice,key,withMedia(media(bobId)),404);
        assertEquals(0,count("post_creation_idempotency")); assertEquals(0,count("posts"));
        String body=withMedia(media(aliceId));
        doAnswer(invocation -> {
            assertEquals(1,count("posts")); assertEquals(1,count("post_media"));
            invocation.callRealMethod();
            throw new org.springframework.dao.DataIntegrityViolationException("test-only failure after successful record insert");
        }).when(store).save(anyString(),anyString(),anyString(),anyLong());
        create(alice,key,body,500);
        assertEquals(0,count("posts")); assertEquals(0,count("post_media")); assertEquals(0,count("post_creation_idempotency"));
        reset(store); create(alice,key,body,201); create(alice,key,body,200);
        assertEquals(1,count("posts")); assertEquals(1,count("post_media"));
    }
    @Test void sixConcurrentIdenticalRequestsCreateExactlyOnce() throws Exception { concurrent(false); }
    @Test void sixConcurrentConflictingRequestsHaveOneWinner() throws Exception { concurrent(true); }
    void concurrent(boolean conflict) throws Exception {
        String key=UUID.randomUUID().toString(); String body=withMedia(media(aliceId));
        var barrier=new CyclicBarrier(6); var executor=Executors.newFixedThreadPool(6);
        try {
            var futures=new ArrayList<Future<MvcResult>>();
            for (int i=0;i<6;i++) { final int n=i; futures.add(executor.submit(() -> {
                barrier.await(10,TimeUnit.SECONDS);
                return mvc.perform(post("/api/posts").header("Authorization",alice).header("Idempotency-Key",key)
                        .contentType("application/json").content(conflict && n%2==1 ? body.replace("作品","另一作品") : body)).andReturn();
            })); }
            int created=0,replayed=0,conflicted=0; Set<String> ids=new HashSet<>();
            for (var future:futures) {
                var response=future.get(30,TimeUnit.SECONDS); int status=response.getResponse().getStatus();
                if (status==201) created++; else if (status==200) replayed++; else { assertEquals(409,status,response.getResponse().getContentAsString()); conflicted++; }
                if (status!=409) ids.add(id(response));
                else assertEquals("IDEMPOTENCY_CONFLICT",json.readTree(response.getResponse().getContentAsString()).get("code").asText());
            }
            assertEquals(1,created); assertEquals(conflict ? 2 : 5,replayed); assertEquals(conflict ? 3 : 0,conflicted);
            assertEquals(1,ids.size()); assertEquals(1,count("posts")); assertEquals(1,count("post_media")); assertEquals(1,count("post_creation_idempotency"));
            String winner=db.queryForObject("SELECT title FROM posts",String.class);
            create(alice,key,winner.equals("作品") ? body : body.replace("作品","另一作品"),200);
        } finally { executor.shutdownNow(); }
    }
    @Test void databaseRejectsDuplicateUserKey() throws Exception {
        String key=UUID.randomUUID().toString(); create(alice,key,draft,201);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,() -> db.update(
                "INSERT INTO post_creation_idempotency SELECT user_id,request_key,request_fingerprint,post_id FROM post_creation_idempotency"));
        assertEquals(1,count("post_creation_idempotency"));
    }
}
