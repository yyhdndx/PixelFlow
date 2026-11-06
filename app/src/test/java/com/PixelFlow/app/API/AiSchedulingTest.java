package com.PixelFlow.app.API;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AiSchedulingTest {
    private static final String FEED = "{\"status_code\":0,\"has_more\":0,\"next_cursor\":null,\"post_list\":[]}";
    private static final class Result<T> implements CommunityClient.Callback<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        public void onSuccess(T value) { result.complete(value); }
        public void onFailure(String message) { result.completeExceptionally(new IllegalStateException(message)); }
    }

    @Test public void aiQueueIsBoundedAndDoesNotBlockOrdinaryRead() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (request.getPath().equals("/api/agent/search")) {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return new MockResponse().setResponseCode(502).setBody("{}");
                    }
                    return new MockResponse().setBody(FEED);
                }
            });
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            try (CommunityClient api = new CommunityClient(server.url("/").toString(), Runnable::run)) {
                api.restore("token", "refresh", "alice");
                for (int i = 0; i < 2; i++) api.agentSearch("question" + i, null, new Result<>());
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 4; i++) api.agentSearch("queued" + i, null, new Result<>());
                Result<AgentSearchResult> rejected = new Result<>();
                api.agentSearch("overflow", null, rejected);
                try { rejected.result.get(2, TimeUnit.SECONDS); fail("queue must reject"); }
                catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("已满")); }
                Result<ApiResponse> ordinary = new Result<>();
                api.getFeed(20, null, ordinary);
                assertNotNull(ordinary.result.get(2, TimeUnit.SECONDS));
                assertEquals(1, release.getCount());
            } finally { release.countDown(); }
        }
    }

    @Test public void simultaneousUnauthorizedRequestsRotateOnlyOnce() throws Exception {
        CountDownLatch rejected = new CountDownLatch(2);
        AtomicInteger rotations = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (request.getPath().equals("/api/auth/refresh")) {
                        rotations.incrementAndGet();
                        return new MockResponse().setBody("{\"accessToken\":\"new\",\"refreshToken\":\"new-refresh\"}");
                    }
                    if ("Bearer old".equals(request.getHeader("Authorization"))) {
                        rejected.countDown();
                        rejected.await(3, TimeUnit.SECONDS);
                        return new MockResponse().setResponseCode(401).setBody("{}");
                    }
                    if (request.getPath().startsWith("/feed/")) return new MockResponse().setBody(FEED);
                    return new MockResponse().setResponseCode(502).setBody("{}");
                }
            });
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            try (CommunityClient api = new CommunityClient(server.url("/").toString(), Runnable::run)) {
                api.restore("old", "refresh", "alice");
                Result<ApiResponse> feed = new Result<>();
                Result<AgentSearchResult> ai = new Result<>();
                api.getFeed(20, null, feed);
                api.agentSearch("question", null, ai);
                assertNotNull(feed.result.get(4, TimeUnit.SECONDS));
                try { ai.result.get(4, TimeUnit.SECONDS); fail("upstream intentionally unavailable"); }
                catch (java.util.concurrent.ExecutionException expected) { }
                assertEquals(0, rejected.getCount());
                assertEquals(1, rotations.get());
            }
        }
    }
}
