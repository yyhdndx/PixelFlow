package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:api-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long",
        "pixelflow.analytics-export-token=test-analytics-read-token-at-least-32-chars"})
@AutoConfigureMockMvc
class BusinessApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private static final String PASSWORD="test-password-2026";
    @BeforeEach void clean() {
        for (String table:List.of("event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events","interactions","posts","sessions","users")) db.update("DELETE FROM "+table);
    }
    private JsonNode body(MvcResult response) throws Exception { return json.readTree(response.getResponse().getContentAsString()); }
    private String encode(Object value) throws Exception { return json.writeValueAsString(value); }
    private JsonNode account(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content(encode(Map.of("username",name,"password",PASSWORD,"nickname",name))))
                .andExpect(status().isCreated());
        return login(name);
    }
    private JsonNode login(String name) throws Exception {
        return body(mvc.perform(post("/api/auth/login").contentType("application/json").content(encode(Map.of("username",name,"password",PASSWORD))))
                .andExpect(status().isOk()).andReturn());
    }
    private String token(JsonNode tokens) { return "Bearer "+tokens.get("accessToken").asText(); }
    private Map<String,Object> draft() {
        return Map.of("title","测试作品","content","正文","clips",List.of(Map.of("type",0,"width",600,"height",800,"url","https://example.com/a.png")));
    }
    private String create(String token) throws Exception {
        return body(mvc.perform(post("/api/posts").header("Authorization",token).contentType("application/json").content(encode(draft())))
                .andExpect(status().isCreated()).andReturn()).get("post_id").asText();
    }
    private String admin() throws Exception {
        var tokens=account("admin_test");
        db.update("UPDATE users SET role='ADMIN' WHERE username='admin_test'");
        return token(tokens);
    }
    private String published(String owner,String admin) throws Exception {
        String id=create(owner);
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("status","PUBLISHED","reason","人工确认")))).andExpect(status().isOk());
        return id;
    }
    @Test void registrationHashesPasswordAndRejectsRoleEscalation() throws Exception {
        account("alice");
        assertTrue(db.queryForObject("SELECT password_hash FROM users WHERE username='alice'",String.class).startsWith("$2a$"));
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(encode(Map.of("username","evil","password",PASSWORD,"nickname","evil","role","ADMIN"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(encode(Map.of("username","Alice","password",PASSWORD,"nickname","same"))))
                .andExpect(status().isConflict());
    }
    @Test void authenticationAndAdminBoundary() throws Exception {
        String user=token(account("alice"));
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized()).andExpect(jsonPath("requestId").isNotEmpty());
        mvc.perform(get("/api/admin/users").header("Authorization",user)).andExpect(status().isForbidden());
        mvc.perform(get("/feed/").header("Authorization","Bearer forged")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(encode(Map.of("username","alice","password","wrong"))))
                .andExpect(status().isUnauthorized());
    }
    @Test void feedAndDetailLikeStateAreScopedToTheAuthenticatedUser() throws Exception {
        String alice=token(account("alice")), bob=token(account("bob"));
        String id=published(alice,admin());
        mvc.perform(put("/api/interactions/likes").header("Authorization",alice).contentType("application/json")
                .content(encode(Map.of("targetId",id,"active",true)))).andExpect(status().isOk());
        mvc.perform(get("/feed/").header("Authorization",alice))
                .andExpect(jsonPath("post_list[0].is_liked").value(true))
                .andExpect(jsonPath("post_list[0].like_count").value(1));
        mvc.perform(get("/feed/").header("Authorization",bob)).andExpect(jsonPath("post_list[0].is_liked").value(false));
        mvc.perform(get("/feed/")).andExpect(jsonPath("post_list[0].is_liked").value(false));
        mvc.perform(get("/api/posts/"+id).header("Authorization",alice)).andExpect(jsonPath("is_liked").value(true));
        mvc.perform(get("/api/posts/"+id).header("Authorization",bob)).andExpect(jsonPath("is_liked").value(false));
        mvc.perform(get("/api/posts/"+id)).andExpect(jsonPath("is_liked").value(false));
    }
    @Test void refreshRotatesAndLogoutRevokes() throws Exception {
        var old=account("alice");
        String request=encode(Map.of("refreshToken",old.get("refreshToken").asText()));
        var rotated=body(mvc.perform(post("/api/auth/refresh").contentType("application/json").content(request)).andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/posts").header("Authorization",token(old))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization",token(rotated))).andExpect(status().isOk());
        mvc.perform(get("/api/posts").header("Authorization",token(rotated))).andExpect(status().isUnauthorized());
    }
    @Test void disabledAccountInvalidatesExistingToken() throws Exception {
        String user=token(account("alice"));
        String admin=admin();
        String id=db.queryForObject("SELECT id FROM users WHERE username='alice'",String.class);
        mvc.perform(put("/api/admin/users/"+id+"/status").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("enabled",false,"reason","测试停用")))).andExpect(status().isOk());
        mvc.perform(get("/api/posts").header("Authorization",user)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/users/"+id+"/status").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("enabled",true,"reason","测试启用")))).andExpect(status().isOk());
        mvc.perform(get("/api/posts").header("Authorization",user)).andExpect(status().isUnauthorized());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_status_audit",Integer.class));
    }
    @Test void draftOwnershipAndModerationVisibility() throws Exception {
        String owner=token(account("alice")), other=token(account("bob")), admin=admin();
        String id=create(owner);
        mvc.perform(get("/api/posts/"+id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/posts/"+id).header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(put("/api/posts/"+id).header("Authorization",other).contentType("application/json").content(encode(draft())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(jsonPath("status").value("PENDING"));
        mvc.perform(get("/feed/")).andExpect(jsonPath("post_list.length()").value(0));
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("status","PUBLISHED","reason","通过")))).andExpect(status().isOk());
        mvc.perform(get("/feed/")).andExpect(jsonPath("post_list[0].post_id").value(id));
        mvc.perform(post("/api/posts/"+id+"/withdraw").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(get("/feed/")).andExpect(jsonPath("post_list.length()").value(0));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM moderation_records",Integer.class));
    }
    @Test void cursorPaginationAndInvalidParameters() throws Exception {
        String owner=token(account("alice")), admin=admin();
        String first=published(owner,admin), second=published(owner,admin);
        var page=body(mvc.perform(get("/feed/?count=1")).andExpect(status().isOk()).andExpect(jsonPath("has_more").value(1)).andReturn());
        assertEquals(second,page.get("post_list").get(0).get("post_id").asText());
        mvc.perform(get("/feed/").param("count","1").param("cursor",page.get("next_cursor").asText()))
                .andExpect(jsonPath("post_list[0].post_id").value(first)).andExpect(jsonPath("has_more").value(0));
        for (String query:List.of("count=0","count=51","cursor=-1","cursor=oops","accept_video_clip=oops"))
            mvc.perform(get("/feed/?"+query)).andExpect(status().isBadRequest());
    }
    @Test void interactionSetsAreIdempotentAndRemovableAfterWithdrawal() throws Exception {
        String owner=token(account("alice")), admin=admin(), id=published(owner,admin);
        String input=encode(Map.of("targetId",id,"active",true));
        for (int i=0;i<2;i++) mvc.perform(put("/api/interactions/likes").header("Authorization",owner).contentType("application/json").content(input)).andExpect(status().isOk());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM interactions",Integer.class));
        mvc.perform(get("/api/posts/"+id)).andExpect(jsonPath("like_count").value(1));
        mvc.perform(post("/api/posts/"+id+"/withdraw").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(put("/api/interactions/likes").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("targetId",id,"active",false)))).andExpect(status().isOk());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM interactions",Integer.class));
    }
    @Test void eventDedupConflictAndIdentityInjection() throws Exception {
        String owner=token(account("alice")), admin=admin(), id=published(owner,admin);
        long now=Instant.now().getEpochSecond();
        String input=encode(Map.of("eventId","evt-1","postId",id,"type","CLICK","occurredAt",now));
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json").content(input)).andExpect(jsonPath("duplicate").value(false));
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json").content(input)).andExpect(jsonPath("duplicate").value(true));
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json").content(input.replace("CLICK","LIKE"))).andExpect(status().isConflict());
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("eventId","evt-2","postId",id,"type","CLICK","occurredAt",now,"userId","someone-else"))))
                .andExpect(status().isBadRequest());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM behavior_events",Integer.class));
        mvc.perform(get("/api/admin/events").header("Authorization",owner)).andExpect(status().isForbidden());
    }
    @Test void dwellDurationIsValidatedAndIncludedInDeduplication() throws Exception {
        String owner=token(account("alice")), id=published(owner,admin());
        var input=new java.util.HashMap<String,Object>(Map.of("eventId","dwell-1","postId",id,"type","DWELL",
                "occurredAt",Instant.now().getEpochSecond(),"scene","DETAIL"));
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json").content(encode(input)))
                .andExpect(status().isBadRequest());
        input.put("durationMs",3000);
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json").content(encode(input)))
                .andExpect(jsonPath("duplicate").value(false));
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json").content(encode(input)))
                .andExpect(jsonPath("duplicate").value(true));
        input.put("durationMs",4000);
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json").content(encode(input)))
                .andExpect(status().isConflict());
        input.put("durationMs",1800001);
        mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json").content(encode(input)))
                .andExpect(status().isBadRequest());
    }
    @Test void likeTransitionsProduceExactlyOneServerEventPerChange() throws Exception {
        String owner=token(account("alice")), id=published(owner,admin());
        for (boolean active:List.of(true,true,false,false)) {
            mvc.perform(put("/api/interactions/likes").header("Authorization",owner).contentType("application/json")
                    .content(encode(Map.of("targetId",id,"active",active)))).andExpect(status().isOk());
        }
        assertEquals(List.of("LIKE","UNLIKE"),db.queryForList("SELECT event_type FROM behavior_events ORDER BY seq",String.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM behavior_events WHERE source='SERVER'",Integer.class));
    }
    @Test void analyticsIdentityIsReadOnlyAndSeparateFromUserTokens() throws Exception {
        String owner=token(account("alice")), administrator=admin();
        String service="Bearer test-analytics-read-token-at-least-32-chars";
        mvc.perform(get("/api/internal/analytics/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/analytics/events").header("Authorization",owner)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/analytics/events").header("Authorization",administrator)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/analytics/events").header("Authorization",service)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization",service)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/analytics/events").header("Authorization",service))
                .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(0));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM event_export_audit",Integer.class));
    }
    @Test void analyticsPagesHaveStableWatermarksAndHideWithdrawnTitles() throws Exception {
        String owner=token(account("alice")), id=published(owner,admin());
        for (int i=0;i<2;i++) mvc.perform(post("/api/events").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("eventId","click-"+i,"postId",id,"type","CLICK","occurredAt",Instant.now().getEpochSecond()))))
                .andExpect(status().isOk());
        String service="Bearer test-analytics-read-token-at-least-32-chars";
        var first=body(mvc.perform(get("/api/internal/analytics/events?count=1").header("Authorization",service))
                .andExpect(jsonPath("has_more").value(true)).andReturn());
        mvc.perform(post("/api/posts/"+id+"/withdraw").header("Authorization",owner)).andExpect(status().isOk());
        var second=body(mvc.perform(get("/api/internal/analytics/events?count=1&after="+first.get("next_cursor").asLong())
                .header("Authorization",service)).andExpect(jsonPath("has_more").value(false))
                .andExpect(jsonPath("items[0].eligible").value(false))
                .andExpect(jsonPath("items[0].title").value("")).andReturn());
        assertEquals(first.get("source_id"),second.get("source_id"));
        assertTrue(second.get("next_cursor").asLong()>first.get("next_cursor").asLong());
        mvc.perform(get("/api/internal/analytics/events?after="+second.get("next_cursor").asLong()).header("Authorization",service))
                .andExpect(jsonPath("items.length()").value(0))
                .andExpect(jsonPath("next_cursor").value(second.get("next_cursor").asLong()));
    }
    @Test void invalidMediaAndBlankModerationReasonAreRejected() throws Exception {
        String owner=token(account("alice"));
        mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","test","content","","clips",List.of(Map.of("type",2,"width",0,"height",20,"url","file:///secret"))))))
                .andExpect(status().isBadRequest());
        String admin=admin(), id=create(owner);
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("status","PUBLISHED","reason"," ")))).andExpect(status().isBadRequest());
    }

    @Test void simultaneousRefreshConsumesTokenOnce() throws Exception {
        var tokens=account("alice");
        String request=encode(Map.of("refreshToken",tokens.get("refreshToken").asText()));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> action=() -> {
                assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
                return mvc.perform(post("/api/auth/refresh").contentType("application/json").content(request)).andReturn().getResponse().getStatus();
            };
            var first=executor.submit(action); var second=executor.submit(action); start.countDown();
            assertEquals(java.util.Set.of(200,401),java.util.Set.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)));
        } finally { executor.shutdownNow(); }
    }

    @Test void simultaneousLikesProduceOneRecord() throws Exception {
        String owner=token(account("alice")), admin=admin(), id=published(owner,admin);
        String request=encode(Map.of("targetId",id,"active",true));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Integer>>();
            for (int i=0;i<8;i++) tasks.add(() -> mvc.perform(put("/api/interactions/likes").header("Authorization",owner)
                    .contentType("application/json").content(request)).andReturn().getResponse().getStatus());
            for (var result:executor.invokeAll(tasks,10,java.util.concurrent.TimeUnit.SECONDS)) assertEquals(200,result.get());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM interactions",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM behavior_events WHERE source='SERVER' AND event_type='LIKE'",Integer.class));
        } finally { executor.shutdownNow(); }
    }

    @Test void imageOnlyFeedExcludesVideoAndInvalidTokenDoesNotBecomeAnonymous() throws Exception {
        String owner=token(account("alice")), admin=admin();
        String id=body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","视频","content","","clips",List.of(Map.of("type",1,"width",600,"height",800,"url","https://example.com/a.mp4"))))))
                .andExpect(status().isCreated()).andReturn()).get("post_id").asText();
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("status","PUBLISHED","reason","通过")))).andExpect(status().isOk());
        mvc.perform(get("/feed/")).andExpect(jsonPath("post_list.length()").value(0));
        mvc.perform(get("/feed/?accept_video_clip=true")).andExpect(jsonPath("post_list[0].clips[0].type").value(1));
        mvc.perform(get("/feed/").header("Authorization","invalid")).andExpect(status().isUnauthorized());
    }

    private byte[] picture(String format,int width,int height) throws Exception {
        var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image,format,bytes); image.flush(); return bytes.toByteArray();
    }
    private String upload(String owner) throws Exception {
        var file=new org.springframework.mock.web.MockMultipartFile("file","../../ignored.jpg","image/jpeg",picture("jpeg",20,30));
        return body(mvc.perform(multipart("/api/media").file(file).header("Authorization",owner))
                .andExpect(status().isCreated()).andExpect(jsonPath("contentType").value("image/png"))
                .andExpect(jsonPath("width").value(20)).andReturn()).get("mediaId").asText();
    }

    @Test void uploadedImagesFollowPublicationVisibility() throws Exception {
        String owner=token(account("alice")), other=token(account("bob")), admin=admin();
        String media=upload(owner), path="/api/media/"+media+"/content";
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization",owner)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get(path).header("Authorization",admin)).andExpect(status().isOk());
        String id=body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","上传作品","content","","mediaIds",List.of(media)))))
                .andExpect(status().isCreated()).andExpect(jsonPath("media_ids[0]").value(media)).andReturn()).get("post_id").asText();
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("status","PUBLISHED","reason","通过")))).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/posts/"+id+"/withdraw").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test void mediaCannotBeAttachedByAnotherUserOrByForgedUrl() throws Exception {
        String owner=token(account("alice")), other=token(account("bob")), media=upload(owner);
        mvc.perform(post("/api/posts").header("Authorization",other).contentType("application/json")
                .content(encode(Map.of("title","盗用","content","","mediaIds",List.of(media)))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","重复","content","","mediaIds",List.of(media,media)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts").header("Authorization",other).contentType("application/json")
                .content(encode(Map.of("title","伪造链接","content","","clips",List.of(Map.of("type",0,"width",20,"height",30,
                        "url","http://127.0.0.1:8080/api/media/"+media+"/content"))))))
                .andExpect(status().isBadRequest());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM posts",Integer.class));
    }

    @Test void emptyDraftCanBeRestoredButCannotBePublishedUntilComplete() throws Exception {
        String owner=token(account("alice"));
        String id=body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","","content","还没写完")))).andExpect(status().isCreated()).andReturn()).get("post_id").asText();
        mvc.perform(get("/api/posts/"+id).header("Authorization",owner)).andExpect(jsonPath("content").value("还没写完"));
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isConflict());
        String media=upload(owner);
        mvc.perform(put("/api/posts/"+id).header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","写完了","content","正文","mediaIds",List.of(media)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
    }

    @Test void uploadRejectsSpoofedUnsupportedAndOversizedFiles() throws Exception {
        String owner=token(account("alice"));
        var valid=new org.springframework.mock.web.MockMultipartFile("file","a.png","image/png",picture("png",10,10));
        mvc.perform(multipart("/api/media").file(valid)).andExpect(status().isUnauthorized());
        for (byte[] invalid:List.of(new byte[0],"<script>alert(1)</script>".getBytes(),picture("gif",10,10),picture("png",2001,2000))) {
            mvc.perform(multipart("/api/media").file(new org.springframework.mock.web.MockMultipartFile("file","a.png","image/png",invalid))
                    .header("Authorization",owner)).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/media").file(new org.springframework.mock.web.MockMultipartFile("file","large.png","image/png",new byte[8*1024*1024+1]))
                .header("Authorization",owner)).andExpect(status().isPayloadTooLarge());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media",Integer.class));
    }

    @Test void replacingDraftMediaDetachesOldReferences() throws Exception {
        String owner=token(account("alice")), media=upload(owner);
        String id=body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","草稿","content","","mediaIds",List.of(media)))))
                .andExpect(status().isCreated()).andReturn()).get("post_id").asText();
        mvc.perform(put("/api/posts/"+id).header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","","content","只留文字")))).andExpect(status().isOk());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM post_media",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM media",Integer.class));
    }

    @Test void mediaListIsPrivateAndPagedWithoutImageBytes() throws Exception {
        String owner=token(account("alice")), other=token(account("bob"));
        upload(owner); upload(owner); upload(other);
        mvc.perform(get("/api/media")).andExpect(status().isUnauthorized());
        var first=body(mvc.perform(get("/api/media?count=1").header("Authorization",owner))
                .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(1))
                .andExpect(jsonPath("usedCount").value(2)).andExpect(jsonPath("limit").value(100))
                .andExpect(jsonPath("items[0].content_bytes").doesNotExist()).andReturn());
        var second=body(mvc.perform(get("/api/media").param("count","1").param("after",first.get("nextCursor").asText())
                .header("Authorization",owner)).andExpect(status().isOk()).andReturn());
        assertNotEquals(first.get("items").get(0).get("mediaId"),second.get("items").get(0).get("mediaId"));
        assertTrue(second.get("nextCursor").isNull());
        for (String query:List.of("count=0","count=51","after=invalid"))
            mvc.perform(get("/api/media?"+query).header("Authorization",owner)).andExpect(status().isBadRequest());
    }

    @Test void referencedMediaMustBeDetachedBeforeOwnerCanDeleteIt() throws Exception {
        String owner=token(account("alice")), other=token(account("bob")), admin=admin(), media=upload(owner);
        String id=body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","草稿","content","","mediaIds",List.of(media)))))
                .andExpect(status().isCreated()).andReturn()).get("post_id").asText();
        mvc.perform(get("/api/media?unusedOnly=true").header("Authorization",owner)).andExpect(jsonPath("items.length()").value(0));
        mvc.perform(get("/api/media").header("Authorization",owner)).andExpect(jsonPath("items[0].referenceCount").value(1));
        mvc.perform(delete("/api/media/"+media)).andExpect(status().isUnauthorized());
        for (String token:List.of(other,admin)) mvc.perform(delete("/api/media/"+media).header("Authorization",token)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/media/"+media).header("Authorization",owner)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("MEDIA_IN_USE"));
        mvc.perform(put("/api/posts/"+id).header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","草稿","content","已移除图片")))).andExpect(status().isOk());
        mvc.perform(get("/api/media?unusedOnly=true").header("Authorization",owner)).andExpect(jsonPath("items.length()").value(1));
        mvc.perform(delete("/api/media/"+media).header("Authorization",owner)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/media/"+media).header("Authorization",owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/media/"+media+"/content").header("Authorization",owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/media").header("Authorization",owner)).andExpect(jsonPath("usedCount").value(0));
    }

    @Test void deletingUnusedImageReleasesUploadQuota() throws Exception {
        String owner=token(account("alice"));
        String ownerId=db.queryForObject("SELECT id FROM users WHERE username='alice'",String.class);
        String removable=null;
        byte[] image=picture("png",1,1);
        for (int i=0;i<100;i++) {
            removable=java.util.UUID.randomUUID().toString();
            db.update("INSERT INTO media(id,owner_id,width,height,content_bytes,created_at) VALUES(?,?,1,1,?,0)",removable,ownerId,image);
        }
        var file=new org.springframework.mock.web.MockMultipartFile("file","a.png","image/png",image);
        mvc.perform(multipart("/api/media").file(file).header("Authorization",owner)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("MEDIA_QUOTA"));
        mvc.perform(delete("/api/media/"+removable).header("Authorization",owner)).andExpect(status().isNoContent());
        mvc.perform(multipart("/api/media").file(file).header("Authorization",owner)).andExpect(status().isCreated());
        assertEquals(100,db.queryForObject("SELECT COUNT(*) FROM media",Integer.class));
    }

    @Test void concurrentDeleteAndAttachmentNeverLeaveDanglingMedia() throws Exception {
        String owner=token(account("alice")), media=upload(owner);
        String request=encode(Map.of("title","并发草稿","content","","mediaIds",List.of(media)));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var creation=executor.submit(() -> {
                assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
                return mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json").content(request)).andReturn().getResponse().getStatus();
            });
            var deletion=executor.submit(() -> {
                assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
                return mvc.perform(delete("/api/media/"+media).header("Authorization",owner)).andReturn().getResponse().getStatus();
            });
            start.countDown();
            int created=creation.get(10,java.util.concurrent.TimeUnit.SECONDS),deleted=deletion.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue((created==201 && deleted==409) || (created==404 && deleted==204),"create="+created+", delete="+deleted);
            assertEquals(created==201 ? 1 : 0,db.queryForObject("SELECT COUNT(*) FROM post_media",Integer.class));
            assertEquals(created==201 ? 1 : 0,db.queryForObject("SELECT COUNT(*) FROM media",Integer.class));
            assertEquals(created==201 ? 1 : 0,db.queryForObject("SELECT COUNT(*) FROM posts",Integer.class));
        } finally { executor.shutdownNow(); }
    }
}
