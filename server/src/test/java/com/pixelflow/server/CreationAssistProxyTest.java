package com.pixelflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import com.pixelflow.server.intelligence.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreationAssistProxyTest {
    HttpServer upstream;
    final ObjectMapper json=new ObjectMapper();
    CreationSuggestionStore store;
    CreationAssistController controller;
    final Actor actor=new Actor("alice","CREATOR","session");
    final AtomicReference<String> token=new AtomicReference<>(), payload=new AtomicReference<>();
    String response;
    int status;
    @BeforeEach void setup() throws Exception {
        status=200;
        response="{\"user_id\":\"alice\",\"suggestion_id\":\"upstream\",\"provider\":\"deepseek\",\"safety\":{\"risk_level\":\"low\"},\"title_candidates\":[\"候选\"],\"content_candidates\":[],\"tag_suggestions\":[],\"edit_suggestions\":[]}";
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/agent/create-assist",exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] data=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        upstream.start(); store=mock(CreationSuggestionStore.class);
        controller=new CreationAssistController(json,mock(PostService.class),store,"http://127.0.0.1:"+upstream.getAddress().getPort());
    }
    @AfterEach void close() { upstream.stop(0); }
    CreationAssistController.CreateAssistRequest request() {
        return new CreationAssistController.CreateAssistRequest(new CreationAssistController.AssistDraft("原稿","正文",List.of()),"all","主题","同学");
    }
    @Test void successForwardsUserTokenAndStoresBoundPreview() throws Exception {
        var result=controller.createAssist(request(),actor,"Bearer session-token");
        assertEquals("Bearer session-token",token.get());
        assertEquals("同学",json.readTree(payload.get()).get("audience").asText());
        assertEquals("no-store",result.getHeaders().getCacheControl());
        assertNotEquals("upstream",result.getBody().get("suggestion_id").asText());
        verify(store).save(anyString(),eq(actor),eq("原稿"),eq("正文"),any());
    }
    @Test void wrongIdentityAndInvalidCandidatesAreNeverStored() {
        response=response.replace("alice","bob");
        assertThrows(ApiException.class,() -> controller.createAssist(request(),actor,"Bearer session-token"));
        response=response.replace("bob","alice").replace("[\"候选\"]","null");
        assertThrows(ApiException.class,() -> controller.createAssist(request(),actor,"Bearer session-token"));
        verifyNoInteractions(store);
    }
    @Test void upstreamFailureDoesNotStoreOrApplyAnything() {
        status=503;
        assertThrows(ApiException.class,() -> controller.createAssist(request(),actor,"Bearer session-token"));
        verifyNoInteractions(store);
    }
    @Test void revisionForwardsBothVersionsAndKeepsOriginalBinding() throws Exception {
        var body = new CreationAssistController.CreateAssistRequest(request().draft(), "all", "再短一点", "",
                new CreationAssistController.AssistDraft("上版标题", "上版正文", List.of("#咖啡")));
        controller.createAssist(body, actor, "Bearer session-token");
        var sent = json.readTree(payload.get());
        assertEquals("正文", sent.path("draft").path("content").asText());
        assertEquals("上版正文", sent.path("previous_result").path("content").asText());
        assertEquals("再短一点", sent.path("prompt").asText());
        verify(store).save(anyString(), eq(actor), eq("原稿"), eq("正文"), any());
    }
    @Test void invalidInputDoesNotReachModel() {
        var empty = new CreationAssistController.AssistDraft("", "", List.of());
        assertThrows(ApiException.class, () -> controller.createAssist(
                new CreationAssistController.CreateAssistRequest(empty,"all","", ""),actor,"Bearer x"));
        assertThrows(ApiException.class, () -> controller.createAssist(
                new CreationAssistController.CreateAssistRequest(empty,"polish_content","润色", ""),actor,"Bearer x"));
        assertThrows(ApiException.class, () -> controller.createAssist(
                new CreationAssistController.CreateAssistRequest(request().draft(),"all","", "", empty),actor,"Bearer x"));
        assertNull(payload.get()); verifyNoInteractions(store);
    }
    @Test void multipleCandidatesOrOutOfScopeChangesAreRejected() {
        response=response.replace("[\"候选\"]", "[\"一版\",\"二版\"]");
        assertThrows(ApiException.class, () -> controller.createAssist(request(),actor,"Bearer x"));
        response=response.replace("[\"一版\",\"二版\"]", "[\"候选\"]");
        assertThrows(ApiException.class, () -> controller.createAssist(
                new CreationAssistController.CreateAssistRequest(request().draft(),"polish_content","", ""),actor,"Bearer x"));
        verifyNoInteractions(store);
    }
}
