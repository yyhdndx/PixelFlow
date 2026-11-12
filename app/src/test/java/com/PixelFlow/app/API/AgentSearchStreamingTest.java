package com.PixelFlow.app.API;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AgentSearchStreamingTest {
    @Test public void thirtySecondGenerationDoesNotDelayOrdinaryWritesAndQueuedTaskExpires() throws Exception {
        java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
        String prefix = "event: meta\ndata: {\"request_id\":\"r\",\"seq\":1,\"session_id\":\"s\",\"trace_id\":\"t\"}\n\n"
                + "event: sources\ndata: {\"request_id\":\"r\",\"seq\":2,\"sources\":[]}\n\n"
                + "event: delta\ndata: {\"request_id\":\"r\",\"seq\":3,\"text\":\"first\"}\n\n";
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    if (request.getPath().equals("/api/agent/search/stream")) {
                        started.incrementAndGet();
                        return new MockResponse().setHeader("Content-Type", "text/event-stream")
                                .setBody(prefix + new String(new char[100]).replace("\0", ": heartbeat\n\n"))
                                .throttleBody(prefix.length(), 8, TimeUnit.SECONDS);
                    }
                    if (request.getPath().equals("/api/interactions/likes")) return new MockResponse().setBody("{\"post_id\":\"1\",\"is_liked\":true,\"like_count\":1}");
                    if (request.getPath().equals("/api/posts/1")) return new MockResponse().setBody("{\"post_id\":\"1\",\"title\":\"作品\"}");
                    if (request.getPath().endsWith("/comments")) return new MockResponse().setBody("{\"comment_id\":\"1\",\"post_id\":\"1\",\"content\":\"普通评论\"}");
                    return new MockResponse().setBody("{\"status_code\":0,\"has_more\":0,\"post_list\":[]}");
                }
            });
            try (CommunityClient api = new CommunityClient(server.url("/").toString(), Runnable::run)) {
                api.restore("token", "refresh", "alice");
                CountDownLatch first = new CountDownLatch(2);
                CommunityClient.Callback<AgentSearchResult> ignored = new CommunityClient.Callback<AgentSearchResult>() {
                    public void onSuccess(AgentSearchResult result) { }
                    public void onFailure(String error) { }
                };
                CommunityClient.Job one = api.agentSearchStream("one", null, partial -> {
                    if (partial.get("answer").getAsString().equals("first")) first.countDown();
                }, ignored);
                CommunityClient.Job two = api.agentSearchStream("two", null, partial -> {
                    if (partial.get("answer").getAsString().equals("first")) first.countDown();
                }, ignored);
                assertTrue(first.await(3, TimeUnit.SECONDS));
                CompletableFuture<String> expired = new CompletableFuture<>();
                api.agentSearchStream("queued", null, partial -> { }, new CommunityClient.Callback<AgentSearchResult>() {
                    public void onSuccess(AgentSearchResult result) { expired.complete("unexpected success"); }
                    public void onFailure(String error) { expired.complete(error); }
                });
                CompletableFuture<PostItem> like = new CompletableFuture<>();
                api.setLike("1", true, new CommunityClient.Callback<PostItem>() {
                    public void onSuccess(PostItem post) { like.complete(post); }
                    public void onFailure(String error) { like.completeExceptionally(new IllegalStateException(error)); }
                });
                CompletableFuture<CommentPage.CommentItem> comment = new CompletableFuture<>();
                api.createComment("1", "普通评论", null, new CommunityClient.Callback<CommentPage.CommentItem>() {
                    public void onSuccess(CommentPage.CommentItem item) { comment.complete(item); }
                    public void onFailure(String error) { comment.completeExceptionally(new IllegalStateException(error)); }
                });
                assertEquals("1", like.get(2, TimeUnit.SECONDS).getPostId());
                assertEquals("普通评论", comment.get(2, TimeUnit.SECONDS).content);
                assertTrue(expired.get(33, TimeUnit.SECONDS).contains("等待超时"));
                assertEquals(2, started.get());
                one.cancel();
                two.cancel();
            }
        }
    }

    @Test public void networkDeliversPartialBeforeCompletionAndOrdinaryFeedRemainsAvailable() throws Exception {
        String meta = "event: meta\ndata: {\"request_id\":\"r\",\"seq\":1,\"session_id\":\"s\",\"trace_id\":\"t\"}\n\n";
        String sources = "event: sources\ndata: {\"request_id\":\"r\",\"seq\":2,\"sources\":[]}\n\n";
        String delta = "event: delta\ndata: {\"request_id\":\"r\",\"seq\":3,\"text\":\"first\"}\n\n";
        String done = "event: done\ndata: {\"request_id\":\"r\",\"seq\":4,\"answer\":\"first\",\"session_id\":\"s\",\"trace_id\":\"t\",\"sources\":[],\"validated\":true,\"degraded\":0}\n\n";
        String prefix = meta + sources + delta;
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(prefix + done).throttleBody(prefix.length(), 2, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setBody("{\"status_code\":0,\"has_more\":0,\"post_list\":[]}"));
            try (CommunityClient api = new CommunityClient(server.url("/").toString(), Runnable::run)) {
                api.restore("token", "refresh", "alice");
                CountDownLatch first = new CountDownLatch(1);
                CompletableFuture<AgentSearchResult> completed = new CompletableFuture<>();
                api.agentSearchStream("question", null, partial -> {
                    if (partial.get("answer").getAsString().equals("first")) first.countDown();
                }, new CommunityClient.Callback<AgentSearchResult>() {
                    public void onSuccess(AgentSearchResult result) { completed.complete(result); }
                    public void onFailure(String error) { completed.completeExceptionally(new IllegalStateException(error)); }
                });
                assertTrue(first.await(1, TimeUnit.SECONDS));
                assertFalse(completed.isDone());
                CompletableFuture<ApiResponse> feed = new CompletableFuture<>();
                api.getFeed(20, null, new CommunityClient.Callback<ApiResponse>() {
                    public void onSuccess(ApiResponse result) { feed.complete(result); }
                    public void onFailure(String error) { feed.completeExceptionally(new IllegalStateException(error)); }
                });
                assertNotNull(feed.get(1, TimeUnit.SECONDS));
                assertFalse(completed.isDone());
                assertTrue(completed.get(4, TimeUnit.SECONDS).valid());
                assertEquals("/api/agent/search/stream", server.takeRequest().getPath());
            }
        }
    }
}
