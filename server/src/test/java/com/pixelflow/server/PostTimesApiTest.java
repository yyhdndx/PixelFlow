package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:post-times;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long",
        "pixelflow.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class PostTimesApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String author,visitor,admin,authorId;
    final String prefix="times_"+UUID.randomUUID().toString().replace("-","").substring(0,10);
    final Map<String,Object> draft=Map.of("title","时间测试摄影","content","正文 #时间测试",
            "clips",List.of(Map.of("type",0,"width",1,"height",1,"url","https://example.com/test.png")));

    @BeforeEach void setup() throws Exception {
        author=account(prefix+"a"); visitor=account(prefix+"b"); admin=account(prefix+"admin");
        db.update("UPDATE users SET role='ADMIN' WHERE username=?",prefix+"admin");
        authorId=db.queryForObject("SELECT id FROM users WHERE username=?",String.class,prefix+"a");
    }
    @AfterEach void cleanup() {
        String users="SELECT id FROM users WHERE username LIKE ?";
        String posts="SELECT id FROM posts WHERE author_id IN ("+users+")";
        for (String table:List.of("post_creation_idempotency","moderation_records","post_media","behavior_events"))
            db.update("DELETE FROM "+table+" WHERE post_id IN ("+posts+")",prefix+"%");
        db.update("DELETE FROM interactions WHERE user_id IN ("+users+")",prefix+"%");
        db.update("DELETE FROM posts WHERE author_id IN ("+users+")",prefix+"%");
        db.update("DELETE FROM sessions WHERE user_id IN ("+users+")",prefix+"%");
        db.update("DELETE FROM users WHERE username LIKE ?",prefix+"%");
    }
    String account(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(
                Map.of("username",name,"password","post-times-password","nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(
                Map.of("username",name,"password","post-times-password")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }
    JsonNode read(String path,String token) throws Exception {
        var request=get(path); if (token!=null) request.header("Authorization",token);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode create(String key,int status) throws Exception {
        var request=post("/api/posts").header("Authorization",author).contentType("application/json").content(json.writeValueAsString(draft));
        if (key!=null) request.header("Idempotency-Key",key);
        return json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    void transition(String id,String action) throws Exception {
        mvc.perform(post("/api/posts/"+id+"/"+action).header("Authorization",author)).andExpect(status().isOk());
    }
    void moderate(String id,String state) throws Exception {
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content(json.writeValueAsString(Map.of("status",state,"reason","时间验收")))).andExpect(status().isOk());
    }
    JsonNode detail(String id) throws Exception { return read("/api/posts/"+id,author); }
    void times(JsonNode card,Long submitted,Long reviewed,Long published) {
        for (var field:List.of("submit_time","review_time","publish_time")) assertTrue(card.has(field),field);
        assertEquals(submitted,card.get("submit_time").isNull() ? null : card.get("submit_time").longValue());
        assertEquals(reviewed,card.get("review_time").isNull() ? null : card.get("review_time").longValue());
        assertEquals(published,card.get("publish_time").isNull() ? null : card.get("publish_time").longValue());
    }
    long recent(JsonNode card,String field) {
        long value=card.get(field).longValue(); assertTrue(value>=Instant.now().getEpochSecond()-10); return value;
    }

    @Test void rejectionResubmissionApprovalAndWithdrawalKeepLatestTimes() throws Exception {
        String key=UUID.randomUUID().toString(); var created=create(key,201); String id=created.get("post_id").asText();
        long creation=created.get("create_time").longValue(); times(created,null,null,null);
        transition(id,"publish"); long first=recent(detail(id),"submit_time"); times(detail(id),first,null,null);
        db.update("UPDATE posts SET submit_time=1 WHERE id=?",Long.parseLong(id));
        transition(id,"publish"); times(detail(id),1L,null,null);
        moderate(id,"REJECTED"); long rejected=recent(detail(id),"review_time"); times(detail(id),1L,rejected,null);
        mvc.perform(put("/api/posts/"+id).header("Authorization",author).contentType("application/json").content(json.writeValueAsString(draft)))
                .andExpect(status().isOk()); times(detail(id),1L,rejected,null);
        transition(id,"publish"); long submitted=recent(detail(id),"submit_time"); assertTrue(submitted>1);
        moderate(id,"PUBLISHED"); long approved=recent(detail(id),"review_time"); times(detail(id),submitted,approved,approved);
        assertEquals(approved,db.queryForObject("SELECT created_at FROM moderation_records WHERE post_id=? AND after_status='PUBLISHED'",Long.class,Long.parseLong(id)));
        db.update("UPDATE posts SET review_time=2,publish_time=2 WHERE id=?",Long.parseLong(id));
        moderate(id,"PUBLISHED"); times(detail(id),submitted,2L,2L);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM moderation_records WHERE post_id=?",Integer.class,Long.parseLong(id)));
        var replay=create(key,200); assertEquals(id,replay.get("post_id").asText()); times(replay,submitted,2L,2L);
        transition(id,"withdraw"); times(detail(id),submitted,2L,2L);
        db.update("UPDATE posts SET submit_time=1 WHERE id=?",Long.parseLong(id));
        transition(id,"publish"); long again=recent(detail(id),"submit_time"); times(detail(id),again,2L,2L);
        moderate(id,"PUBLISHED"); long republished=recent(detail(id),"publish_time"); assertTrue(republished>2);
        moderate(id,"WITHDRAWN"); times(detail(id),again,republished,republished);
        assertEquals(creation,detail(id).get("create_time").longValue());
    }

    @Test void allCardEndpointsIncludeNullableAndPublishedTimes() throws Exception {
        var created=create(null,201); String id=created.get("post_id").asText();
        times(read("/api/posts",author).get(0),null,null,null);
        transition(id,"publish"); long submitted=detail(id).get("submit_time").longValue();
        times(read("/api/admin/posts",admin).get(0),submitted,null,null);
        moderate(id,"PUBLISHED"); long published=detail(id).get("publish_time").longValue();
        mvc.perform(put("/api/interactions/favorites").header("Authorization",visitor).contentType("application/json")
                .content(json.writeValueAsString(Map.of("targetId",id,"active",true)))).andExpect(status().isOk());
        mvc.perform(put("/api/interactions/likes").header("Authorization",visitor).contentType("application/json")
                .content(json.writeValueAsString(Map.of("targetId",id,"active",true)))).andExpect(status().isOk());
        for (String path:List.of("/feed/","/api/search/posts?q=时间测试","/api/users/"+authorId+"/posts","/api/users/me/favorites")) {
            var cards=read(path,visitor).get("post_list"); JsonNode card=null;
            for (var item:cards) if (item.get("post_id").asText().equals(id)) card=item;
            assertNotNull(card,path); times(card,submitted,published,published);
            assertTrue(card.get("is_liked").booleanValue()); assertTrue(card.get("is_favorited").booleanValue());
            assertEquals(created.get("create_time"),card.get("create_time"));
        }
        times(read("/api/posts/"+id,null),submitted,published,published);
        times(read("/api/admin/posts?status=PUBLISHED",admin).get(0),submitted,published,published);
    }

    @Test void forbiddenAndInvalidTransitionsLeaveTimesUnchanged() throws Exception {
        String id=create(null,201).get("post_id").asText();
        for (String action:List.of("publish","withdraw"))
            mvc.perform(post("/api/posts/"+id+"/"+action).header("Authorization",visitor)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",visitor).contentType("application/json")
                .content("{\"status\":\"PUBLISHED\",\"reason\":\"拒绝\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content("{\"status\":\"PUBLISHED\",\"reason\":\"非法状态\"}")).andExpect(status().isConflict());
        times(detail(id),null,null,null);
        transition(id,"publish"); moderate(id,"PUBLISHED"); var before=detail(id);
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",author)).andExpect(status().isConflict());
        assertEquals(before,detail(id));
    }
}
