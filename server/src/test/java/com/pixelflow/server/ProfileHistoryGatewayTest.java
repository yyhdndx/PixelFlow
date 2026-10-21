package com.pixelflow.server;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.intelligence.ProfileHistoryController;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.util.LinkedMultiValueMap;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:profile-history-gateway;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password="})
@AutoConfigureMockMvc
class ProfileHistoryGatewayTest {
    static final String PATH="/api/users/me/profile/history";
    static volatile String response="{}",receivedToken,receivedQuery,receivedMethod;
    static volatile int upstreamStatus=200,delayMillis=0;
    static final AtomicInteger requests=new AtomicInteger();
    static final ExecutorService executor=Executors.newCachedThreadPool();
    static final HttpServer upstream=createServer();
    static HttpServer createServer() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(executor);
            server.createContext("/profiles/me/history",exchange -> {
                requests.incrementAndGet(); receivedToken=exchange.getRequestHeaders().getFirst("Authorization");
                receivedQuery=exchange.getRequestURI().getRawQuery(); receivedMethod=exchange.getRequestMethod();
                int code=upstreamStatus; byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                try {
                    if (delayMillis>0) Thread.sleep(delayMillis);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.getResponseHeaders().set("Location","/profiles/me/history");
                    exchange.sendResponseHeaders(code,bytes.length);
                    try (var output=exchange.getResponseBody()) { output.write(bytes); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("pixelflow.intelligence-base-url",() -> "http://127.0.0.1:"+upstream.getAddress().getPort());
    }
    @AfterAll static void stop() { upstream.stop(0); executor.shutdownNow(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    String token,userId,otherToken,otherId;

    @BeforeEach void setup() throws Exception {
        delayMillis=0; upstreamStatus=200; requests.set(0); receivedToken=null; receivedQuery=null;
        token=account(); otherToken=account(); userId=id(token); otherId=id(otherToken);
    }
    String account() throws Exception {
        String name="hist_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        var credentials=Map.of("username",name,"password","history-test-password");
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password",credentials.get("password"),"nickname",name))))
                .andExpect(status().isCreated());
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(credentials)))
                .andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }
    String id(String value) throws Exception {
        return body(mvc.perform(get("/api/users/me").header("Authorization",value)).andExpect(status().isOk()).andReturn()).get("id").asText();
    }
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    ObjectNode page(boolean more,long... versions) {
        ObjectNode result=json.createObjectNode().put("user_id",userId).put("has_more",more);
        var items=result.putArray("items");
        for (long version:versions) {
            var item=items.addObject().put("version",version).put("rule_version","profile-rules-v1")
                    .put("generated_at",1791000000).put("window_days",30).put("timezone","UTC")
                    .put("cold_start",false).put("explanation","点击和停留规则");
            item.putArray("topics").addObject().put("name","美食").put("score",6.0).put("share",1.0);
            item.putArray("media").addObject().put("name","image").put("score",6.0).put("share",1.0);
            item.putObject("event_counts").put("CLICK",1);
        }
        if (more && versions.length>0) result.put("next_cursor",Long.toString(versions[versions.length-1]));
        else result.putNull("next_cursor");
        return result;
    }
    ResultActions request() throws Exception { return mvc.perform(get(PATH).header("Authorization",token)); }

    @Test void proxiesOnlyValidatedPaginationAndCurrentUserToken() throws Exception {
        var expected=page(true,8,5); response=expected.toString();
        var result=body(mvc.perform(get(PATH).header("Authorization",token).param("count","02").param("before_version","010")
                .param("user_id",otherId).param("userId",otherId).param("endpoint","https://example.com"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn());
        assertEquals(json.readTree(expected.toString()),result); assertEquals(token,receivedToken); assertEquals("GET",receivedMethod);
        assertEquals("count=2&before_version=10",receivedQuery); assertEquals(1,requests.get());
        response=page(false,3,1).toString();
        mvc.perform(get(PATH).header("Authorization",token).param("count","2").param("before_version","5"))
                .andExpect(status().isOk()).andExpect(jsonPath("has_more").value(false)).andExpect(jsonPath("items[1].version").value(1));
        response=page(false).toString();
        request().andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(0));
        assertEquals("count=20",receivedQuery);
    }

    @Test void historicalTopicsAreNotRewrittenAndExtraUpstreamFieldsAreNotExposed() throws Exception {
        db.update("INSERT INTO recommendation_suppressed_topics(user_id,topic) VALUES(?,?)",userId,"美食");
        var expected=page(false,4); var upstreamPage=expected.deepCopy();
        upstreamPage.put("internal_secret","do-not-expose");
        var item=(ObjectNode)upstreamPage.get("items").get(0);
        item.putArray("authors").add("private-author"); item.putObject("active_hours").put("7",12);
        ((ObjectNode)item.get("topics").get(0)).put("raw_events","private-events");
        response=upstreamPage.toString();
        assertEquals(json.readTree(expected.toString()),body(request().andExpect(status().isOk()).andReturn()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM recommendation_suppressed_topics WHERE user_id=?",Integer.class,userId));
    }

    @Test void rejectsCrossAccountResponsesAndUsesEachCallersToken() throws Exception {
        response=page(false,3).toString();
        mvc.perform(get(PATH).header("Authorization",otherToken)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("code").value("INVALID_ANALYTICS_RESPONSE"));
        assertEquals(otherToken,receivedToken);
        var otherPage=page(false,3).put("user_id",otherId); response=otherPage.toString();
        assertEquals(json.readTree(otherPage.toString()),body(mvc.perform(get(PATH).header("Authorization",otherToken)).andExpect(status().isOk()).andReturn()));
        request().andExpect(status().isBadGateway());
    }

    @Test void rejectsInvalidAndRepeatedPaginationWithoutCallingUpstream() throws Exception {
        for (String name:List.of("count","before_version")) {
            for (String value:List.of("","0","-1","+1","1.2"," 1","abc","１","9223372036854775808","9".repeat(300)))
                mvc.perform(get(PATH).header("Authorization",token).param(name,value)).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("code").value("INVALID_ARGUMENT")).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(get(PATH).header("Authorization",token).param(name,"1","2")).andExpect(status().isBadRequest());
        }
        mvc.perform(get(PATH).header("Authorization",token).param("count","51")).andExpect(status().isBadRequest());
        assertEquals(0,requests.get());
        response=page(false).toString();
        mvc.perform(get(PATH).header("Authorization",token).param("count","50").param("before_version",Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isOk());
        assertEquals("count=50&before_version="+Long.MAX_VALUE,receivedQuery);
        mvc.perform(get(PATH).header("Authorization",token).param("count","0".repeat(200)+"1")).andExpect(status().isOk());
    }

    @Test void missingExpiredAndDisabledAuthenticationNeverReachesPython() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=FALSE WHERE id=?",userId);
        request().andExpect(status().isUnauthorized());
        db.update("UPDATE users SET enabled=TRUE WHERE id=?",userId);
        db.update("UPDATE sessions SET expires_at=0 WHERE user_id=?",userId);
        request().andExpect(status().isUnauthorized());
        assertEquals(0,requests.get());
    }

    @Test void invalidShapesAndSummaryFieldsFailWithoutLeakingUpstreamBody() throws Exception {
        List<Consumer<ObjectNode>> mutations=List.of(
                p -> p.remove("user_id"), p -> p.put("items","secret"), p -> p.put("has_more",0),
                p -> p.remove("next_cursor"), p -> p.put("next_cursor","3"),
                p -> ((ObjectNode)p.get("items").get(0)).put("version",0),
                p -> ((ObjectNode)p.get("items").get(0)).put("version",1.5),
                p -> ((ObjectNode)p.get("items").get(0)).put("version",new java.math.BigInteger("9223372036854775808")),
                p -> ((ObjectNode)p.get("items").get(0)).put("cold_start",1),
                p -> ((ObjectNode)p.get("items").get(0)).put("generated_at","1"),
                p -> ((ObjectNode)p.get("items").get(0)).remove("window_days"),
                p -> ((ObjectNode)p.get("items").get(0)).remove("rule_version"),
                p -> ((ObjectNode)p.get("items").get(0)).put("timezone",false),
                p -> ((ObjectNode)p.get("items").get(0)).remove("explanation"),
                p -> ((ObjectNode)p.get("items").get(0)).putNull("topics"),
                p -> ((ObjectNode)p.get("items").get(0)).putObject("media"),
                p -> ((ObjectNode)p.get("items").get(0).get("topics").get(0)).put("score","secret"),
                p -> ((ObjectNode)p.get("items").get(0).get("topics").get(0)).put("share",new java.math.BigDecimal("1e999")),
                p -> ((ObjectNode)p.get("items").get(0).get("event_counts")).put("CLICK",-1),
                p -> ((ObjectNode)p.get("items").get(0).get("event_counts")).put("CLICK",true));
        for (var mutate:mutations) {
            var invalid=page(false,3); mutate.accept(invalid); invalid.put("private_data","upstream-secret"); response=invalid.toString();
            var error=request().andExpect(status().isBadGateway()).andExpect(jsonPath("code").value("INVALID_ANALYTICS_RESPONSE"))
                    .andExpect(header().string("Cache-Control","no-store")).andReturn();
            assertFalse(error.getResponse().getContentAsString().contains("upstream-secret"));
        }
        for (String invalid:List.of("null","[]","{broken",page(false,3)+" {}")) {
            response=invalid; request().andExpect(status().isBadGateway());
        }
    }

    @Test void rejectsInconsistentPaginationAndPreservesLongVersionPrecision() throws Exception {
        for (ObjectNode invalid:List.of(page(false,3,3),page(false,2,3),page(true,3),page(true,3,2).put("next_cursor","3"),page(true,2,1))) {
            response=invalid.toString();
            mvc.perform(get(PATH).header("Authorization",token).param("count","2")).andExpect(status().isBadGateway());
        }
        response=page(false,3,2).toString();
        mvc.perform(get(PATH).header("Authorization",token).param("count","1")).andExpect(status().isBadGateway());
        mvc.perform(get(PATH).header("Authorization",token).param("before_version","3")).andExpect(status().isBadGateway());
        response=page(false,Long.MAX_VALUE).toString();
        assertEquals(Long.MAX_VALUE,body(request().andExpect(status().isOk()).andReturn()).get("items").get(0).get("version").longValue());
    }

    @Test void upstreamFailuresAreSanitizedAndRedirectsAreNotFollowed() throws Exception {
        response="upstream-secret";
        for (int code:new int[]{400,422,429,500,503,302}) {
            upstreamStatus=code; int before=requests.get();
            var error=request().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("ANALYTICS_UNAVAILABLE"))
                    .andExpect(header().string("Cache-Control","no-store")).andReturn();
            assertFalse(error.getResponse().getContentAsString().contains(response)); assertEquals(before+1,requests.get());
        }
        upstreamStatus=401;
        request().andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("UNAUTHORIZED"));
    }

    @Test void timeoutIsBoundedAndMissingConfigurationFailsClosed() throws Exception {
        response=page(false).toString(); delayMillis=3500;
        long start=System.nanoTime(); request().andExpect(status().isServiceUnavailable());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<6);
        delayMillis=0;
        var controller=new ProfileHistoryController(json,"");
        var error=assertThrows(ApiException.class,() -> controller.history(new Actor(userId,"CREATOR","test-session"),token,
                new LinkedMultiValueMap<>(),new MockHttpServletResponse()));
        assertEquals(503,error.status);
    }
}
