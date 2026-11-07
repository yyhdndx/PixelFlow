package com.PixelFlow.app.API;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

public class CreationRefinementClientTest {
    MockWebServer server;
    CommunityClient client;
    static final String RESULT="{\"suggestion_id\":\"s\",\"provider\":\"deepseek\",\"title_candidates\":[],\"content_candidates\":[\"新正文\"],\"tag_suggestions\":[],\"edit_suggestions\":[],\"safety\":{\"risk_level\":\"low\",\"suggestions\":[]}}";
    @Before public void setup() throws Exception {
        server=new MockWebServer(); server.start();
        client=new CommunityClient(server.url("/").toString(),Runnable::run); client.restore("token","refresh","alice");
    }
    @After public void close() throws Exception {client.close(); server.shutdown();}
    @Test public void multipleVersionsAreNotPresentedAsOneResult() throws Exception {
        server.enqueue(new MockResponse().setBody(RESULT.replace("[\"新正文\"]","[\"一版\",\"二版\"]")));
        CompletableFuture<String> failure=new CompletableFuture<>();
        client.createAssist("标题","正文",Collections.emptyList(),"all","","",
                new CommunityClient.Callback<CreationSuggestion>() {
                    public void onSuccess(CreationSuggestion value) {failure.completeExceptionally(new AssertionError("Multiple versions accepted"));}
                    public void onFailure(String message) {failure.complete(message);}
                });
        assertTrue(failure.get(5,TimeUnit.SECONDS).contains("建议响应不完整"));
    }
    @Test public void revisionSendsOriginalAndOnePreviousVersionWithoutHistory() throws Exception {
        server.enqueue(new MockResponse().setBody(RESULT));
        CompletableFuture<CreationSuggestion> result=new CompletableFuture<>();
        List<String> tags=new ArrayList<>(Collections.singletonList("#咖啡"));
        CreationSuggestion.PreviousResult previous=new CreationSuggestion.PreviousResult("旧标题","上版正文",tags);
        tags.clear();
        client.createAssist("原标题","原正文",Collections.emptyList(),"polish_content","更口语化","",previous,
                new CommunityClient.Callback<CreationSuggestion>() {
                    public void onSuccess(CreationSuggestion value) {result.complete(value);}
                    public void onFailure(String message) {result.completeExceptionally(new Exception(message));}
                });
        assertEquals("新正文",result.get(5,TimeUnit.SECONDS).content_candidates.get(0));
        RecordedRequest request=server.takeRequest();
        assertEquals("Bearer token",request.getHeader("Authorization"));
        JsonObject body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals("原正文",body.getAsJsonObject("draft").get("content").getAsString());
        assertEquals("上版正文",body.getAsJsonObject("previous_result").get("content").getAsString());
        assertEquals(1,body.getAsJsonObject("previous_result").getAsJsonArray("tags").size());
        assertEquals("更口语化",body.get("prompt").getAsString());
        assertFalse(body.has("history"));
    }
}
