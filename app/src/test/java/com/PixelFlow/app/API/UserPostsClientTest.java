package com.PixelFlow.app.API;

import com.google.gson.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class UserPostsClientTest {
    private MockWebServer server;
    private CommunityClient client;
    @Before public void setup() throws Exception {
        server = new MockWebServer(); server.start();
        client = new CommunityClient(server.url("/").toString(), Runnable::run);
        client.restore("alice", "refresh-alice", "alice");
    }
    @After public void close() throws Exception { client.close(); server.shutdown(); }
    private static class Result implements CommunityClient.Callback<UserPostsPage> {
        final CompletableFuture<UserPostsPage> future = new CompletableFuture<>();
        int status;
        public void onSuccess(UserPostsPage page) { future.complete(page); }
        public void onFailure(String message) { future.completeExceptionally(new Exception(message)); }
        public void onFailure(int code, String message) { status = code; onFailure(message); }
        UserPostsPage get() throws Exception { return future.get(5, TimeUnit.SECONDS); }
        void fails() throws Exception { try { get(); fail("Expected rejected response"); } catch (ExecutionException expected) { } }
    }
    private String post(String id) {
        return "{\"post_id\":\"" + id + "\",\"status\":\"PUBLISHED\",\"title\":\"作品\","
                + "\"author\":{\"user_id\":\"author\"},\"clips\":[],\"is_liked\":true,\"like_count\":3}";
    }
    private String page(String rows, int more, String next) {
        return "{\"status_code\":0,\"has_more\":" + more + ",\"next_cursor\":"
                + (next == null ? "null" : "\"" + next + "\"") + ",\"post_list\":[" + rows + "]}";
    }
    private Result request(String body, String cursor) {
        server.enqueue(new MockResponse().setBody(body));
        Result result = new Result(); client.getUserPosts("author", 20, cursor, result); return result;
    }
    @Test public void readsAuthenticatedPagesAndKeepsViewerLikeWithoutRequiringFavoriteField() throws Exception {
        UserPostsPage first = request(page(post("30") + "," + post("20"), 1, "20"), null).get();
        assertTrue(first.hasMore); assertEquals("20", first.nextCursor); assertTrue(first.posts.get(0).isLiked());
        assertEquals(3, first.posts.get(0).getLikeCount());
        UserPostsPage second = request(page(post("9"), 0, null), first.nextCursor).get();
        assertFalse(second.hasMore); assertEquals(1, second.posts.size());
        RecordedRequest start = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("/api/users/author/posts?count=20", start.getPath());
        assertEquals("Bearer alice", start.getHeader("Authorization"));
        assertEquals("/api/users/author/posts?count=20&cursor=20", server.takeRequest().getPath());
    }
    @Test public void emptyAndLongMaxIdAreValid() throws Exception {
        assertTrue(request(page("", 0, null), null).get().posts.isEmpty());
        assertEquals(Long.toString(Long.MAX_VALUE), request(page(post(Long.toString(Long.MAX_VALUE)), 0, null), null).get().posts.get(0).getPostId());
    }
    @Test public void rejectsWrongAuthorPrivatePostsAndMalformedEnvelope() throws Exception {
        String valid = page(post("30"), 0, null);
        for (String invalid : new String[]{"{}", "[]", valid.replace("author\"}", "other\"}"),
                valid.replace("PUBLISHED", "DRAFT"), valid.replace("\"is_liked\":true", "\"is_liked\":1"),
                valid.replace("\"has_more\":0", "\"has_more\":false"), valid.replace("\"status_code\":0", "\"status_code\":500")})
            request(invalid, null).fails();
    }
    @Test public void rejectsDuplicateOutOfOrderAndContradictoryCursors() throws Exception {
        for (String invalid : new String[]{page(post("30"), 0, null), page(post("20") + "," + post("20"), 0, null),
                page(post("10") + "," + post("20"), 0, null), page(post("20"), 1, "19"),
                page(post("20"), 0, "20"), page("", 1, "20"), page(post("20"), 1, null)})
            request(invalid, "30").fails();
    }
    @Test public void invalidInputsFailWithoutNetwork() throws Exception {
        for (String cursor : new String[]{"0", "-1", "20&user_id=bob", "9223372036854775808"}) {
            Result result = new Result(); client.getUserPosts("author", 20, cursor, result); result.fails(); assertEquals(400, result.status);
        }
        Result badAuthor = new Result(); client.getUserPosts("../me", 20, null, badAuthor); badAuthor.fails();
        Result badCount = new Result(); client.getUserPosts("author", 51, null, badCount); badCount.fails();
        assertEquals(0, server.getRequestCount());
    }
    @Test public void refreshesExpiredSessionAndPreservesNotFoundStatus() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
        server.enqueue(new MockResponse().setBody("{\"accessToken\":\"new-token\",\"refreshToken\":\"new-refresh\"}"));
        server.enqueue(new MockResponse().setBody(page("", 0, null)));
        Result result = new Result(); client.getUserPosts("author", 20, null, result); result.get();
        server.takeRequest(); assertEquals("/api/auth/refresh", server.takeRequest().getPath());
        assertEquals("Bearer new-token", server.takeRequest().getHeader("Authorization"));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"code\":\"USER_NOT_FOUND\",\"message\":\"用户不可见\"}"));
        Result missing = new Result(); client.getUserPosts("author", 20, null, missing); missing.fails(); assertEquals(404, missing.status);
    }
    @Test public void guestCannotRequestPrivateViewerState() throws Exception {
        client.close(); client = new CommunityClient(server.url("/").toString(), Runnable::run);
        Result result = new Result(); client.getUserPosts("author", 20, null, result); result.fails();
        assertEquals(0, server.getRequestCount());
    }
}
