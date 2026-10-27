package com.PixelFlow.app.API;

import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class CreationMediaClientTest {
    private MockWebServer server;
    private CommunityClient client;
    private static class Result<T> implements CommunityClient.Callback<T> {
        final CompletableFuture<T> future = new CompletableFuture<>();
        public void onSuccess(T value) { future.complete(value); }
        public void onFailure(String message) { future.completeExceptionally(new AssertionError(message)); }
        T get() throws Exception { return future.get(5, TimeUnit.SECONDS); }
        void fails() throws Exception {
            try { get(); fail("Expected failure"); } catch (ExecutionException expected) { }
        }
    }
    @Before public void setup() throws Exception {
        server = new MockWebServer(); server.start();
        client = new CommunityClient(server.url("/").toString(), Runnable::run);
        client.restore("alice-token", "refresh", "alice");
    }
    @After public void close() throws Exception { client.close(); server.shutdown(); }
    private String url() { return server.url("/api/media/image-1/content").toString(); }

    @Test public void readsExactBinaryWithAuthentication() throws Exception {
        byte[] bytes = {0, -1, 1, 127, -128};
        server.enqueue(new MockResponse().setBody(new Buffer().write(bytes)));
        Result<byte[]> result = new Result<>(); client.getMediaContent(url(), result);
        assertArrayEquals(bytes, result.get());
        assertEquals("Bearer alice-token", server.takeRequest().getHeader("Authorization"));
    }
    @Test public void expiredMediaTokenRotatesBeforeRetrying() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setBody("{\"accessToken\":\"new\",\"refreshToken\":\"next\"}"));
        server.enqueue(new MockResponse().setBody("image"));
        Result<byte[]> result = new Result<>(); client.getMediaContent(url(), result); result.get();
        assertEquals("Bearer alice-token", server.takeRequest().getHeader("Authorization"));
        RecordedRequest refresh = server.takeRequest();
        assertEquals("/api/auth/refresh", refresh.getPath()); assertNull(refresh.getHeader("Authorization"));
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"));
    }
    @Test public void rejectsOtherOriginsAndNonMediaRoutesBeforeNetwork() throws Exception {
        for (String invalid : Arrays.asList("https://example.com/api/media/image-1/content",
                url().replace("127.0.0.1", "other.invalid").replace("localhost", "other.invalid"),
                server.url("/api/users/me").toString(), url()+"?redirect=1", url()+"#secret",
                server.url("/api/media/%2e%2e/content").toString())) {
            assertFalse(invalid, client.isPrivateMediaUrl(invalid));
            Result<byte[]> result = new Result<>(); client.getMediaContent(invalid, result); result.fails();
        }
        assertEquals(0, server.getRequestCount());
    }
    @Test public void mediaRedirectDoesNotForwardCredentials() throws Exception {
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen")));
            Result<byte[]> result = new Result<>(); client.getMediaContent(url(), result); result.fails();
            assertEquals(1, server.getRequestCount()); assertEquals(0, other.getRequestCount());
        }
    }
    @Test public void guestMediaReadMakesNoRequest() throws Exception {
        client.close(); client = new CommunityClient(server.url("/").toString(), Runnable::run);
        Result<byte[]> result = new Result<>(); client.getMediaContent(url(), result); result.fails();
        assertEquals(0, server.getRequestCount());
    }
    @Test public void sendsStableCreationKeyWithoutChangingExistingPayload() throws Exception {
        String key = UUID.randomUUID().toString();
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"post_id\":\"30\",\"status\":\"DRAFT\"}"));
        Result<PostItem> result = new Result<>(); client.createPost(key, "title", "body", Collections.singletonList("m1"), result);
        assertEquals("30", result.get().getPostId());
        RecordedRequest request = server.takeRequest();
        assertEquals(key, request.getHeader("Idempotency-Key"));
        assertEquals("POST /api/posts HTTP/1.1", request.getRequestLine());
        assertEquals("{\"title\":\"title\",\"content\":\"body\",\"mediaIds\":[\"m1\"]}", request.getBody().readUtf8());
    }
}
