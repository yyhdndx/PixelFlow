package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:agent-suggest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationSuggestionApiTest {
    static final String SHARED="internal-test-token-1234567890123456789";
    static final String ID="12345678-1234-1234-1234-123456789abc";
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicReference<String> REPLY=new AtomicReference<>(), TOKEN=new AtomicReference<>(),
            PATH=new AtomicReference<>(), BODY=new AtomicReference<>();
    static final AtomicInteger STATUS=new AtomicInteger(200), CALLS=new AtomicInteger();
    static final HttpServer SERVER=start();
    static HttpServer start() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/moderation-suggestion",exchange->{
                CALLS.incrementAndGet(); TOKEN.set(exchange.getRequestHeaders().getFirst("Authorization"));
                PATH.set(exchange.getRequestURI().toString());
                BODY.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] bytes=REPLY.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(STATUS.get(),bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("pixelflow.intelligence-base-url",()->"http://127.0.0.1:"+SERVER.getAddress().getPort());
        p.add("PIXELFLOW_INTELLIGENCE_TOKEN",()->SHARED);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db;
    String admin,ordinary,owner; ObjectNode suggestion;
    @BeforeAll void accounts() throws Exception {
        admin=account("suggestadmin"); ordinary=account("suggestuser");
        db.update("UPDATE users SET role='ADMIN' WHERE username='suggestadmin'");
        owner=JSON.readTree(mvc.perform(get("/api/users/me").header("Authorization",ordinary))
                .andReturn().getResponse().getContentAsString()).path("id").asText();
    }
    String account(String name) throws Exception {
        var credentials=Map.of("username",name,"password","test-password-2026");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(JSON.writeValueAsString(
                Map.of("username",name,"password","test-password-2026","nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+JSON.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(JSON.writeValueAsString(credentials))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).path("accessToken").asText();
    }
    @AfterAll void stop() { SERVER.stop(0); }
    @BeforeEach void fixture() {
        STATUS.set(200); CALLS.set(0); TOKEN.set(null); BODY.set(null);
        suggestion=JSON.createObjectNode().put("suggestion_id",ID).put("post_id","1")
                .put("risk_level","low").put("recommended_action","approve")
                .put("reason","未命中任何规则，内容合规").put("policy_version","content-safety-v1")
                .put("provider","rules").put("latency_ms",12).put("trace_id",ID).put("degraded",1);
        suggestion.putArray("rule_hits"); suggestion.putArray("suggestions");
        reply();
    }
    void reply() { REPLY.set(JSON.createObjectNode().put("status_code",0).set("suggestion",suggestion).toString()); }
    String newPost(String title,String content) {
        db.update("INSERT INTO posts (author_id,title,content,clips_json,has_video,status,create_time) VALUES (?,?,?,'[]',FALSE,'PENDING',0)",
                owner,title,content);
        return db.queryForObject("SELECT MAX(id) FROM posts WHERE author_id=?",String.class,owner);
    }
    ResultActions suggestAs(String token,String body) throws Exception {
        var request=post("/api/admin/agent/moderation-suggestion").contentType("application/json").content(body);
        if (token!=null) request=request.header("Authorization",token);
        return mvc.perform(request);
    }
    @Test void permissionGateRejectsBeforeUpstream() throws Exception {
        suggestAs(null,"{\"post_id\":\"1\"}").andExpect(status().isUnauthorized());
        suggestAs(ordinary,"{\"post_id\":\"1\"}").andExpect(status().isForbidden());
        assertEquals(0,CALLS.get());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"post_id\":\"\"}","{\"post_id\":\"0\"}","{\"post_id\":\"-1\"}",
            "{\"post_id\":\"abc\"}","{\"post_id\":\"1\",\"extra\":2}","not-json"})
    void invalidBodiesNeverReachPython(String body) throws Exception {
        suggestAs(admin,body).andExpect(status().isBadRequest());
        assertEquals(0,CALLS.get());
    }
    @Test void missingPostIs404WithoutUpstream() throws Exception {
        suggestAs(admin,"{\"post_id\":\"999999\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("code").value("NOT_FOUND"));
        assertEquals(0,CALLS.get());
    }
    @Test void happyPathForwardsSnapshotWithServiceToken() throws Exception {
        String id=newPost("风景随拍","今天的晚霞真美");
        suggestion.put("post_id",id); reply();
        var result=suggestAs(admin,"{\"post_id\":\""+id+"\"}").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("status_code").value(0))
                .andExpect(jsonPath("suggestion.post_id").value(id))
                .andExpect(jsonPath("suggestion.risk_level").value("low"))
                .andExpect(jsonPath("suggestion.recommended_action").value("approve"))
                .andExpect(jsonPath("suggestion.trace_id").value(ID)).andReturn();
        assertEquals("Bearer "+SHARED,TOKEN.get()); assertNotEquals(admin,TOKEN.get());
        assertEquals("/internal/moderation-suggestion",PATH.get());
        var sent=JSON.readTree(BODY.get());
        assertEquals(id,sent.path("post_id").asText());
        assertEquals("风景随拍",sent.path("title").asText());
        assertEquals("今天的晚霞真美",sent.path("content").asText());
        assertTrue(sent.path("tags").isArray());
        assertFalse(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(SHARED));
    }
    @Test void allThreeRiskTiersPassThrough() throws Exception {
        String id=newPost("标题","正文");
        for (String[] tier:new String[][]{{"low","approve"},{"medium","review"},{"high","reject"}}) {
            suggestion.put("post_id",id).put("risk_level",tier[0]).put("recommended_action",tier[1]);
            var hits=suggestion.putArray("rule_hits");
            if (!tier[0].equals("low")) hits.add(tier[0].equals("high")?"banned_word":"contact_info");
            reply();
            suggestAs(admin,"{\"post_id\":\""+id+"\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("suggestion.risk_level").value(tier[0]))
                    .andExpect(jsonPath("suggestion.recommended_action").value(tier[1]));
        }
    }
    @Test void malformedBodyIsRejectedWithoutUpstream() throws Exception {
        suggestAs(admin,"{\"post_id\":\"").andExpect(status().isBadRequest());
        suggestAs(admin,"{\"post_id\":\"1\",\"post_id\":\"2\"}").andExpect(status().isBadRequest());
        assertEquals(0,CALLS.get());
    }
    @Test void secretsInSuggestionAreNeverEchoed() throws Exception {
        String id=newPost("t","c"); suggestion.put("post_id",id).put("system_prompt","top secret")
                .put("api_key",SHARED).put("raw_messages","private"); reply();
        var result=suggestAs(admin,"{\"post_id\":\""+id+"\"}").andExpect(status().isOk())
                .andExpect(jsonPath("suggestion.post_id").value(id)).andReturn();
        var raw=result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(raw.contains(SHARED));
        assertFalse(raw.contains("top secret"));
        assertFalse(raw.contains("private"));
        assertFalse(raw.contains("api_key"));
    }
    @ParameterizedTest @ValueSource(ints={301,401,403,404,500,503})
    void upstreamFailuresAre502(int code) throws Exception {
        String id=newPost("t","c"); suggestion.put("post_id",id); reply();
        STATUS.set(code); REPLY.set("private upstream error");
        suggestAs(admin,"{\"post_id\":\""+id+"\"}).andExpect(status().isBadGateway())
                .andExpect(jsonPath("code").value("AGENT_SUGGESTION_UNAVAILABLE"))
                .andExpect(jsonPath("requestId").isNotEmpty()).andExpect(jsonPath("suggestion").doesNotExist());
    }
    @Test void mismatchedOrMalformedSuggestionIsRejected() throws Exception {
        String id=newPost("t","c");
        suggestion.put("post_id","2"); reply();
        suggestAs(admin,"{\"post_id\":\""+id+"\"}).andExpect(status().isBadGateway());
        suggestion.put("post_id",id).put("risk_level","critical"); reply();
        suggestAs(admin,"{\"post_id\":\""+id+"\"}).andExpect(status().isBadGateway());
        suggestion.put("risk_level","low").put("recommended_action","delete"); reply();
        suggestAs(admin,"{\"post_id\":\""+id+"\"}).andExpect(status().isBadGateway());
        suggestion.put("recommended_action","approve").put("reason",""); reply();
        suggestAs(admin,"{\"post_id\":\""+id+"\"}).andExpect(status().isBadGateway());
        suggestion.put("reason","ok").withArray("rule_hits").add("BAD CODE"); reply();
        suggestAs(admin,"{\"post_id\":\""+id+"\"}).andExpect(status().isBadGateway());
    }
}
