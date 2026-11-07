package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:agent-search;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class AgentSearchApiTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicReference<String> RESPONSE=new AtomicReference<>(), TOKEN=new AtomicReference<>(), PAYLOAD=new AtomicReference<>(), PYTHON=new AtomicReference<>();
    static final AtomicInteger STATUS=new AtomicInteger(200), CALLS=new AtomicInteger();
    static final HttpServer UPSTREAM=start();
    static HttpServer start() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/agent/search",exchange->{
                CALLS.incrementAndGet(); TOKEN.set(exchange.getRequestHeaders().getFirst("Authorization"));
                PAYLOAD.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] bytes; int status=STATUS.get();
                try {
                    if(PYTHON.get()!=null) {
                        var request=HttpRequest.newBuilder(URI.create(PYTHON.get()+"/agent/search")).header("Authorization",TOKEN.get())
                                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(PAYLOAD.get())).build();
                        var reply=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofByteArray());
                        bytes=reply.body(); status=reply.statusCode();
                    } else bytes=RESPONSE.get().getBytes(StandardCharsets.UTF_8);
                } catch(Exception e) { status=503; bytes="unavailable".getBytes(StandardCharsets.UTF_8); }
                exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { p.add("pixelflow.intelligence-base-url",()->"http://127.0.0.1:"+UPSTREAM.getAddress().getPort()); }
    @AfterAll static void stop() { UPSTREAM.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @LocalServerPort int javaPort;
    String token,user; long postId; ObjectNode response;
    @BeforeEach void setup() throws Exception {
        PYTHON.set(null); STATUS.set(200); CALLS.set(0);
        String username="s"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        String credentials=JSON.writeValueAsString(Map.of("username",username,"password","search-test-password","nickname","作者"));
        mvc.perform(post("/api/auth/register").contentType("application/json").content(credentials)).andExpect(status().isCreated());
        var login=JSON.readTree(mvc.perform(post("/api/auth/login").contentType("application/json").content(JSON.writeValueAsString(Map.of("username",username,"password","search-test-password")))).andReturn().getResponse().getContentAsString());
        token="Bearer "+login.path("accessToken").asText();
        user=db.queryForObject("SELECT id FROM users WHERE username=?",String.class,username);
        db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,'[]',FALSE,'PUBLISHED',1)",user,"夜景摄影","夜景摄影需要稳定手机");
        postId=db.queryForObject("SELECT MAX(id) FROM posts WHERE author_id=?",Long.class,user);
        response=JSON.createObjectNode().put("user_id",user).put("answer","保持手机稳定〔1〕").put("session_id",UUID.randomUUID().toString())
                .put("trace_id",UUID.randomUUID().toString()).put("degraded",0);
        response.putArray("sources").addObject().put("post_id",Long.toString(postId)).put("title","夜景摄影").put("excerpt","夜景摄影需要稳定手机").put("author_name","作者");
        response.putArray("steps"); RESPONSE.set(response.toString());
    }
    ResultActions search(String body) throws Exception { return mvc.perform(post("/api/agent/search").header("Authorization",token).contentType("application/json").content(body)); }
    @Test void successForwardsIdentityAndOnlyExposesContractFields() throws Exception {
        var result=search("{\"question\":\"夜景摄影\"}").andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("status_code").value(0)).andExpect(jsonPath("user_id").doesNotExist()).andExpect(jsonPath("steps").doesNotExist()).andReturn();
        assertEquals(6,JSON.readTree(result.getResponse().getContentAsString()).size());
        assertEquals(token,TOKEN.get()); assertEquals("",JSON.readTree(PAYLOAD.get()).path("session_id").asText());
        search(JSON.writeValueAsString(Map.of("question","再详细些","session_id",response.path("session_id").asText()))).andExpect(status().isOk());
    }
    @Test void zeroResultsUsesExplicitMessageEvenIfUpstreamInventsAnAnswer() throws Exception {
        response.putArray("sources"); response.put("answer","无依据的回答"); RESPONSE.set(response.toString());
        search("{\"question\":\"没有匹配\"}").andExpect(status().isOk()).andExpect(jsonPath("sources").isEmpty())
                .andExpect(jsonPath("answer").value("社区内暂未找到相关作品，可以换个说法再试。"));
    }
    @Test void anonymousAndInvalidInputNeverCallPython() throws Exception {
        mvc.perform(post("/api/agent/search").contentType("application/json").content("{\"question\":\"摄影\"}")).andExpect(status().isUnauthorized());
        for(String input:List.of("{}","{\"question\":null}","{\"question\":\" \"}","{\"question\":\"摄影\",\"session_id\":\"bad\"}","{\"question\":\"摄影\",\"user_id\":\"other\"}",JSON.writeValueAsString(Map.of("question","字".repeat(201)))))
            search(input).andExpect(status().isBadRequest());
        assertEquals(0,CALLS.get());
        search(JSON.writeValueAsString(Map.of("question","😀".repeat(200)))).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","PENDING","REJECTED","WITHDRAWN","deleted","disabled","changed"})
    void unavailableSourceRejectsWholeAnswer(String state) throws Exception {
        switch(state) {
            case "deleted" -> db.update("DELETE FROM posts WHERE id=?",postId);
            case "disabled" -> { db.update("INSERT INTO users(id,username,password_hash,nickname,role,enabled) VALUES(?,?,'unused','作者','CREATOR',FALSE)","disabled-"+postId,"disabled-"+postId); db.update("UPDATE posts SET author_id=? WHERE id=?","disabled-"+postId,postId); }
            case "changed" -> db.update("UPDATE posts SET content='新的内容' WHERE id=?",postId);
            default -> db.update("UPDATE posts SET status=? WHERE id=?",state,postId);
        }
        search("{\"question\":\"摄影\"}").andExpect(status().isBadGateway()).andExpect(jsonPath("answer").doesNotExist()).andExpect(jsonPath("sources").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings={"identity","citation","missing","malformed","oversized","session","duplicate"})
    void invalidUpstreamFailsClosed(String mode) throws Exception {
        switch(mode) {
            case "identity" -> response.put("user_id","other");
            case "citation" -> response.put("answer","错误来源〔2〕");
            case "missing" -> response.remove("degraded");
            case "session" -> response.put("session_id","not-a-uuid");
            case "duplicate" -> response.withArray("sources").add(response.path("sources").get(0).deepCopy());
        }
        RESPONSE.set(mode.equals("malformed") ? "not-json" : mode.equals("oversized") ? "x".repeat(65537) : response.toString());
        search("{\"question\":\"摄影\"}").andExpect(status().isBadGateway()).andExpect(jsonPath("answer").doesNotExist());
    }
    @Test void upstreamFailureDoesNotAffectPublicSearch() throws Exception {
        STATUS.set(503); RESPONSE.set("private error text");
        search("{\"question\":\"摄影\"}").andExpect(status().isBadGateway()).andExpect(jsonPath("code").value("AGENT_SEARCH_UNAVAILABLE"));
        mvc.perform(get("/api/search/posts?q=夜景")).andExpect(status().isOk());
    }

    @Test void streamingRouteAuthenticatesAndDeliversSafeErrorAfterInvalidUpstream() throws Exception {
        mvc.perform(post("/api/agent/search/stream").contentType("application/json").content("{\"question\":\"摄影\"}"))
                .andExpect(status().isUnauthorized());
        var result = mvc.perform(post("/api/agent/search/stream").header("Authorization", token)
                .contentType("application/json").content("{\"question\":\"摄影\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
        assertTrue(result.getResponse().getContentAsString().contains("event: error"));
        assertFalse(result.getResponse().getContentAsString().contains("event: done"));
    }

    @Test @EnabledIfEnvironmentVariable(named="ISSUE21_PYTHON",matches=".+")
    void actualPythonServiceNormalEmptyAndStopped() throws Exception {
        int port; try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        Path directory=Files.createTempDirectory("issue21-python-");
        var builder=new ProcessBuilder(System.getenv("ISSUE21_PYTHON"),"../intelligence/tests/fixtures/issue21_server.py",Integer.toString(port),Integer.toString(javaPort),directory.resolve("analytics.db").toString());
        builder.environment().put("PYTHONPATH",Path.of("../intelligence/src").toAbsolutePath().toString());
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("python.log").toFile());
        var process=builder.start();
        String base="http://127.0.0.1:"+port;
        try {
            var client=HttpClient.newHttpClient(); boolean ready=false;
            for(int i=0;i<100 && process.isAlive();i++) {
                try { ready=client.send(HttpRequest.newBuilder(URI.create(base+"/health")).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200; }
                catch(Exception ignored) {}
                if(ready) break; Thread.sleep(100);
            }
            assertTrue(ready,"Python failed to start; see "+directory);
            PYTHON.set(base);
            search("{\"question\":\"夜景摄影\"}").andExpect(status().isOk()).andExpect(jsonPath("degraded").value(0)).andExpect(jsonPath("sources[0].post_id").exists());
            search("{\"question\":\"完全不存在的火星矿石\"}").andExpect(status().isOk()).andExpect(jsonPath("sources").isEmpty()).andExpect(jsonPath("answer").value("社区内暂未找到相关作品，可以换个说法再试。"));
            process.destroy(); assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
            search("{\"question\":\"摄影\"}").andExpect(status().isBadGateway());
            mvc.perform(get("/api/search/posts?q=夜景")).andExpect(status().isOk());
        } finally { PYTHON.set(null); process.destroyForcibly(); }
    }
}
