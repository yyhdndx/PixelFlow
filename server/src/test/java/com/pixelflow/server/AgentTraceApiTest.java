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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:agent-traces;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentTraceApiTest {
    static final String SHARED="internal-test-token-1234567890123456789";
    static final String ID="12345678-1234-1234-1234-123456789abc";
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicReference<String> REPLY=new AtomicReference<>(), TOKEN=new AtomicReference<>(), PATH=new AtomicReference<>();
    static final AtomicInteger STATUS=new AtomicInteger(200), CALLS=new AtomicInteger();
    static final HttpServer SERVER=start();
    static HttpServer start() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/traces",exchange->{
                CALLS.incrementAndGet(); TOKEN.set(exchange.getRequestHeaders().getFirst("Authorization")); PATH.set(exchange.getRequestURI().toString());
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
    String admin,ordinary; ObjectNode row,detail;
    @BeforeAll void accounts() throws Exception { admin=account("traceadmin"); ordinary=account("traceuser"); db.update("UPDATE users SET role='ADMIN' WHERE username='traceadmin'"); }
    String account(String name) throws Exception {
        var credentials=Map.of("username",name,"password","test-password-2026");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(JSON.writeValueAsString(Map.of("username",name,"password","test-password-2026","nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+JSON.readTree(mvc.perform(post("/api/auth/login").contentType("application/json").content(JSON.writeValueAsString(credentials))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asText();
    }
    @AfterAll void stop() { SERVER.stop(0); }
    @BeforeEach void fixture() {
        STATUS.set(200); CALLS.set(0); TOKEN.set(null);
        row=JSON.createObjectNode().put("trace_id",ID).put("user_id","another-user").put("question","😀".repeat(100))
                .put("created_at",1793526000L).put("latency_ms",2400).put("degraded",0).put("source_count",1).put("provider","deepseek");
        detail=row.deepCopy(); detail.remove("source_count");
        detail.put("session_id","").put("degraded_reason","").put("answer","回答〔1〕");
        detail.putObject("retrieval").put("query","夜景摄影").put("candidate_count",20).putArray("selected_ids").add("855");
        detail.putObject("llm").put("model","deepseek-flash").put("latency_ms",1800);
        detail.putArray("sources").addObject().put("post_id","855").put("title","夜景摄影");
        page(null);
    }
    void page(String next) {
        var page=JSON.createObjectNode(); page.putArray("traces").add(row); if(next==null) page.putNull("next_cursor"); else page.put("next_cursor",next); REPLY.set(page.toString());
    }
    void detailReply() { REPLY.set(JSON.createObjectNode().set("trace",detail).toString()); }
    ResultActions getAdmin(String path) throws Exception { return mvc.perform(get("/api/admin/agent/traces"+path).header("Authorization",admin)); }
    @Test void permissionGateRejectsAllOrdinaryReadersBeforeUpstream() throws Exception {
        for(String path:List.of("","/"+ID)) {
            mvc.perform(get("/api/admin/agent/traces"+path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/agent/traces"+path).header("Authorization",ordinary)).andExpect(status().isForbidden());
        }
        assertEquals(0,CALLS.get());
    }
    @Test void listUsesServiceTokenTruncatesUnicodeAndDropsInternalFields() throws Exception {
        row.put("api_key",SHARED).put("system_prompt","internal secret"); page("9");
        var result=getAdmin("?count=02&cursor=010").andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("status_code").value(0)).andExpect(jsonPath("next_cursor").value("9"))
                .andExpect(jsonPath("traces[0].api_key").doesNotExist()).andReturn();
        String raw=result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(raw.contains(SHARED)); assertFalse(raw.contains("internal secret"));
        assertEquals("😀".repeat(80),JSON.readTree(raw).path("traces").get(0).path("question").asText());
        assertEquals("Bearer "+SHARED,TOKEN.get()); assertNotEquals(admin,TOKEN.get());
        assertEquals("/internal/traces?count=2&cursor=10",PATH.get());
    }
    @Test void emptyPageAndDefaultCount() throws Exception {
        REPLY.set("{\"traces\":[],\"next_cursor\":null}");
        getAdmin("").andExpect(status().isOk()).andExpect(jsonPath("traces").isEmpty()).andExpect(jsonPath("next_cursor").isEmpty());
        assertEquals("/internal/traces?count=20",PATH.get());
    }
    @Test void detailProjectsNestedFieldsWithoutPromptsOrCredentials() throws Exception {
        detail.put("raw_system_prompt","private"); detail.withObject("/llm").put("api_key",SHARED).put("messages","private");
        detail.withObject("/retrieval").put("raw_prompt","private"); detail.withArray("sources").get(0).withObject("").put("authorization",SHARED);
        detailReply();
        var result=getAdmin("/"+ID).andExpect(status().isOk()).andExpect(jsonPath("trace.trace_id").value(ID))
                .andExpect(jsonPath("trace.retrieval.selected_ids[0]").value("855"))
                .andExpect(jsonPath("trace.llm.model").value("deepseek-flash")).andReturn();
        var raw=result.getResponse().getContentAsString(); assertFalse(raw.contains("private")); assertFalse(raw.contains(SHARED));
        assertEquals("/internal/traces/"+ID,PATH.get());
    }
    @ParameterizedTest @ValueSource(strings={"?count=","?count=0","?count=51","?count=-1","?count=x","?cursor=","?cursor=0","?cursor=-1","?cursor=9223372036854775808","?count=1&count=2","?cursor=1&cursor=2","?user_id=other","/not-uuid"})
    void invalidParametersNeverReachPython(String path) throws Exception { getAdmin(path).andExpect(status().isBadRequest()); assertEquals(0,CALLS.get()); }
    @Test void notFoundOnlyAppliesToDetailAndErrorTextIsNotForwarded() throws Exception {
        STATUS.set(404); REPLY.set("secret system prompt");
        getAdmin("/"+ID).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("TRACE_NOT_FOUND"));
        getAdmin("").andExpect(status().isBadGateway());
    }
    @ParameterizedTest @ValueSource(ints={301,401,403,500,503})
    void upstreamFailuresAre502(int code) throws Exception {
        STATUS.set(code); REPLY.set("private upstream error");
        getAdmin("").andExpect(status().isBadGateway()).andExpect(jsonPath("code").value("AGENT_TRACE_UNAVAILABLE"))
                .andExpect(jsonPath("requestId").isNotEmpty()).andExpect(jsonPath("traces").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings={"malformed","oversized","missing","badCursor","wrongType","secret"})
    void invalidListIsRejected(String mode) throws Exception {
        switch(mode) {
            case "malformed" -> REPLY.set("not-json");
            case "oversized" -> REPLY.set("x".repeat(65537));
            case "missing" -> { row.remove("latency_ms"); page(null); }
            case "badCursor" -> page("10");
            case "wrongType" -> { row.put("degraded","0"); page(null); }
            case "secret" -> { row.put("question",SHARED); page(null); }
        }
        getAdmin("?cursor=10").andExpect(status().isBadGateway());
    }
    @Test void mismatchedDetailAndInvalidNestedValuesAreRejected() throws Exception {
        detail.put("trace_id",UUID.randomUUID().toString()); detailReply(); getAdmin("/"+ID).andExpect(status().isBadGateway());
        detail.put("trace_id",ID); detail.withObject("/llm").put("latency_ms",-1); detailReply(); getAdmin("/"+ID).andExpect(status().isBadGateway());
    }
}
