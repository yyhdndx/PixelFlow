package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import com.pixelflow.server.auth.AuthService;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.MediaService;
import com.pixelflow.server.users.UserAvatarController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:avatar-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class UserAvatarApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired MediaService media;
    @Autowired UserAvatarController avatars;
    String alice,bob,aliceId,bobId;
    @BeforeEach void setup() {
        db.update("UPDATE users SET avatar_media_id=NULL");
        for (String table:List.of("recommendation_suppressed_topics","recommendation_blocked_authors","recommendation_preferences",
                "event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events",
                "interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
        aliceId=auth.register("alice","avatar-test-password","Alice").get("id");
        bobId=auth.register("bob","avatar-test-password","Bob").get("id");
        alice="Bearer "+auth.login("alice","avatar-test-password").accessToken();
        bob="Bearer "+auth.login("bob","avatar-test-password").accessToken();
    }
    String upload(String token) throws Exception {
        var image=new java.awt.image.BufferedImage(2,3,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image,"png",bytes); image.flush();
        var result=mvc.perform(multipart("/api/media").file(new MockMultipartFile("file","avatar.png","image/png",bytes.toByteArray()))
                .header("Authorization",token)).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("mediaId").asText();
    }
    String url(String id) { return "/api/media/"+id+"/content"; }
    String set(String token,String id) throws Exception {
        var result=mvc.perform(put("/api/users/me/avatar").header("Authorization",token)
                .contentType("application/json").content(json.writeValueAsString(Map.of("mediaId",id))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("avatar").asText();
    }
    void clear() throws Exception {
        mvc.perform(delete("/api/users/me/avatar").header("Authorization",alice)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("user_id").value(aliceId)).andExpect(jsonPath("avatar").value(""));
    }
    @Test void uploadedAvatarIsPublicAndFlowsThroughExistingCards() throws Exception {
        String id=upload(alice);
        mvc.perform(get(url(id))).andExpect(status().isNotFound());
        String avatar=set(alice,id);
        assertEquals(media.url(id),avatar); assertEquals(avatar,set(alice,id));
        mvc.perform(get(url(id))).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/users/me").header("Authorization",alice)).andExpect(jsonPath("avatar").value(avatar));
        mvc.perform(get("/api/users/"+aliceId+"/profile").header("Authorization",bob))
                .andExpect(status().isOk()).andExpect(jsonPath("avatar").value(avatar));
        db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,'follows',?)",bobId,aliceId);
        mvc.perform(get("/api/users/me/following").header("Authorization",bob)).andExpect(jsonPath("user_list[0].avatar").value(avatar));
        db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,'title','','[]',FALSE,'PUBLISHED',1)",aliceId);
        mvc.perform(get("/feed/")).andExpect(jsonPath("post_list[0].author.avatar").value(avatar));
    }
    @Test void replacementClearAndDisabledOwnerRevokeOnlyAvatarPublicAccess() throws Exception {
        String old=upload(alice), next=upload(alice);
        set(alice,old); set(alice,next);
        mvc.perform(get(url(old))).andExpect(status().isNotFound());
        mvc.perform(get(url(old)).header("Authorization",alice)).andExpect(status().isOk());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        mvc.perform(get(url(next))).andExpect(status().isNotFound());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",aliceId);
        mvc.perform(get(url(next))).andExpect(status().isOk());
        clear(); clear();
        mvc.perform(get(url(next))).andExpect(status().isNotFound());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM media",Integer.class));
        mvc.perform(delete("/api/media/"+next).header("Authorization",alice)).andExpect(status().isNoContent());
    }
    @Test void referencesProtectDeletionAndUnusedListAndIncludePosts() throws Exception {
        String id=upload(alice); set(alice,id);
        mvc.perform(get("/api/media").header("Authorization",alice)).andExpect(jsonPath("items[0].referenceCount").value(1));
        mvc.perform(get("/api/media?unusedOnly=true").header("Authorization",alice)).andExpect(jsonPath("items").isEmpty());
        mvc.perform(delete("/api/media/"+id).header("Authorization",alice)).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("MEDIA_IN_USE"));
        db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,'title','','[]',FALSE,'PUBLISHED',1)",aliceId);
        Long postId=db.queryForObject("SELECT MAX(id) FROM posts",Long.class);
        db.update("INSERT INTO post_media(post_id,media_id,position) VALUES(?,?,0)",postId,id);
        mvc.perform(get("/api/media").header("Authorization",alice)).andExpect(jsonPath("items[0].referenceCount").value(2));
        clear();
        mvc.perform(get(url(id))).andExpect(status().isOk());
        mvc.perform(delete("/api/media/"+id).header("Authorization",alice)).andExpect(status().isConflict());
        db.update("UPDATE posts SET status='WITHDRAWN' WHERE id=?",postId);
        mvc.perform(get(url(id))).andExpect(status().isNotFound());
        mvc.perform(get("/api/media").header("Authorization",alice)).andExpect(jsonPath("items[0].referenceCount").value(1));
        db.update("DELETE FROM post_media WHERE post_id=?",postId);
        mvc.perform(get("/api/media?unusedOnly=true").header("Authorization",alice)).andExpect(jsonPath("items[0].mediaId").value(id));
    }
    @Test void ownershipAuthenticationAndInputAreEnforcedWithoutChangingAvatar() throws Exception {
        String id=upload(alice); String before=set(alice,id);
        String foreign=upload(bob);
        for (String target:List.of(foreign,UUID.randomUUID().toString())) {
            mvc.perform(put("/api/users/me/avatar").header("Authorization",alice).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("mediaId",target)))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("code").value("MEDIA_NOT_FOUND"));
        }
        for (String body:List.of("{}","{\"mediaId\":null}","{\"mediaId\":1}","{\"mediaId\":true}",
                "{\"mediaId\":\"\"}","{\"mediaId\":\"https://example.com/a.png\"}",
                "{\"mediaId\":\""+id+"\",\"userId\":\""+bobId+"\"}")) {
            mvc.perform(put("/api/users/me/avatar").header("Authorization",alice).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        }
        mvc.perform(put("/api/users/me/avatar").contentType("application/json").content("{\"mediaId\":\""+id+"\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/me/avatar")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/me/avatar").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        assertEquals(before,db.queryForObject("SELECT avatar FROM users WHERE id=?",String.class,aliceId));
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",aliceId);
        mvc.perform(delete("/api/users/me/avatar").header("Authorization",alice)).andExpect(status().isUnauthorized());
    }
    @Test void legacyAvatarAndProfileEditsRemainCompatible() throws Exception {
        db.update("UPDATE users SET avatar='https://example.com/legacy.png' WHERE id=?",aliceId);
        clear();
        String id=upload(alice), avatar=set(alice,id);
        mvc.perform(put("/api/users/me").header("Authorization",alice).contentType("application/json")
                .content("{\"nickname\":\"新名字\",\"bio\":\"简介\"}")).andExpect(status().isOk()).andExpect(jsonPath("avatar").value(avatar));
        assertEquals(id,db.queryForObject("SELECT avatar_media_id FROM users WHERE id=?",String.class,aliceId));
    }
    @Test void concurrentSetAndDeleteNeverLeaveDanglingAvatar() throws Exception {
        var actor=auth.authenticate(alice.substring(7));
        for (int iteration=0;iteration<5;iteration++) {
            clear(); String id=upload(alice);
            var barrier=new CyclicBarrier(2);
            var pool=Executors.newFixedThreadPool(2);
            try {
                var setting=pool.submit(() -> { barrier.await(10,TimeUnit.SECONDS);
                    try { avatars.set(actor,new UserAvatarController.Input(id)); return "set"; }
                    catch (ApiException error) { return "rejected"; } });
                var deleting=pool.submit(() -> { barrier.await(10,TimeUnit.SECONDS);
                    try { media.delete(actor,id); return "deleted"; }
                    catch (ApiException error) { return "protected"; } });
                String outcome=setting.get(20,TimeUnit.SECONDS)+"/"+deleting.get(20,TimeUnit.SECONDS);
                assertTrue(Set.of("set/protected","rejected/deleted").contains(outcome),outcome);
                String reference=db.queryForObject("SELECT avatar_media_id FROM users WHERE id=?",String.class,aliceId);
                int exists=db.queryForObject("SELECT COUNT(*) FROM media WHERE id=?",Integer.class,id);
                if (exists==1) assertEquals(id,reference); else assertNull(reference);
            } finally { pool.shutdownNow(); }
        }
    }
}
