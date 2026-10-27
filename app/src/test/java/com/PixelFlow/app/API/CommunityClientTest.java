package com.PixelFlow.app.API;

import com.google.gson.Gson;
import com.PixelFlow.app.Streams.WorkItem;
import okhttp3.mockwebserver.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

public class CommunityClientTest {
    private MockWebServer server;
    private CommunityClient client;
    private CountDownLatch feedEntered, releaseFeed;
    private final Queue<String[]> replies = new ConcurrentLinkedQueue<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private static final String TOKENS = "{\"accessToken\":\"first\",\"refreshToken\":\"refresh-first\"}";
    private static final String FEED = "{\"status_code\":0,\"has_more\":0,\"next_cursor\":null,\"post_list\":[]}";
    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                requests.add(request.getMethod() + " " + request.getPath()
                        + " " + request.getHeader("Authorization") + " " + request.getBody().readUtf8());
                if (releaseFeed != null && request.getPath().startsWith("/feed/")) {
                    feedEntered.countDown();
                    try { releaseFeed.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                String[] reply = replies.poll();
                if (reply == null) reply = new String[]{"500", "{}"};
                return new MockResponse().setResponseCode(Integer.parseInt(reply[0]))
                        .setHeader("Content-Type", "application/json").setBody(reply[1]);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        client = new CommunityClient(server.url("/").toString(), Runnable::run);
    }
    @After public void close() throws Exception { client.close(); server.shutdown(); }
    private void reply(int status, String body) { replies.add(new String[]{String.valueOf(status), body}); }
    private static class Result<T> implements CommunityClient.Callback<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        public void onSuccess(T value) { result.complete(value); }
        public void onFailure(String message) { result.completeExceptionally(new IllegalStateException(message)); }
        T get() throws Exception { return result.get(5, TimeUnit.SECONDS); }
        void fails() throws Exception {
            try { get(); fail("Expected failure"); } catch (ExecutionException expected) { }
        }
    }
    private void login() throws Exception {
        reply(200, TOKENS);
        Result<Void> result = new Result<>(); client.login("alice", "test-password", result); result.get();
    }
    private String ownPost(String id, String status) {
        return "{\"post_id\":\"" + id + "\",\"status\":\"" + status + "\",\"title\":\"作品\",\"clips\":[],\"media_ids\":[]}";
    }
    private MyPostsPage myPosts(int count, String cursor) throws Exception {
        Result<MyPostsPage> result = new Result<>();
        client.getMyPosts(count, cursor, result);
        return result.get();
    }
    @Test public void myPostsEmptyArrayIsSuccessfulEnd() throws Exception {
        login(); reply(200, "[]");
        MyPostsPage page = myPosts(20, null);
        assertTrue(page.getPosts().isEmpty());
        assertFalse(page.canLoadMore()); assertNull(page.getNextCursor());
        assertTrue(requests.get(1).startsWith("GET /api/posts?count=20 Bearer first"));
    }
    @Test public void myPostsMapsAllStatusesAndUsesLastIdCursor() throws Exception {
        login();
        String[] statuses = {"DRAFT", "PENDING", "PUBLISHED", "REJECTED", "WITHDRAWN"};
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) rows.add(ownPost(Integer.toString(9 - i), statuses[i]));
        reply(200, "[" + String.join(",", rows) + "]");
        MyPostsPage page = myPosts(20, null);
        assertEquals(5, page.getPosts().size()); assertEquals("5", page.getNextCursor());
        assertTrue(page.canLoadMore());
        for (int i = 0; i < statuses.length; i++) assertEquals(statuses[i], page.getPosts().get(i).getStatus());
    }
    @Test public void myPostsContinuesAfterShortPageUntilEmptyResponse() throws Exception {
        login(); reply(200, "[" + ownPost("30", "DRAFT") + "]");
        MyPostsPage first = myPosts(20, null);
        reply(200, "[" + ownPost("17", "PUBLISHED") + "]");
        MyPostsPage second = myPosts(20, first.getNextCursor());
        assertTrue(second.canLoadMore()); assertEquals("17", second.getNextCursor());
        reply(200, "[]"); assertFalse(myPosts(20, second.getNextCursor()).canLoadMore());
        assertTrue(requests.get(2).contains("count=20&cursor=30"));
        assertTrue(requests.get(3).contains("count=20&cursor=17"));
    }
    @Test public void myPostsRejectsDuplicateBoundariesAndUnorderedRows() throws Exception {
        login();
        for (String body : Arrays.asList("[" + ownPost("20", "DRAFT") + "]",
                "[" + ownPost("19", "DRAFT") + "," + ownPost("19", "DRAFT") + "]",
                "[" + ownPost("18", "DRAFT") + "," + ownPost("19", "DRAFT") + "]")) {
            reply(200, body);
            Result<MyPostsPage> result = new Result<>(); client.getMyPosts(20, "20", result); result.fails();
        }
    }
    @Test public void myPostsRejectsMalformedOrIncompleteRows() throws Exception {
        login();
        for (String body : Arrays.asList("null", "{}", "not json", "[null]", "[{}]",
                "[{\"post_id\":\"1\"}]", "[" + ownPost("1", "UNKNOWN") + "]",
                "[" + ownPost("9223372036854775808", "DRAFT") + "]", FEED)) {
            reply(200, body);
            Result<MyPostsPage> result = new Result<>(); client.getMyPosts(20, null, result); result.fails();
        }
    }
    @Test public void myPostsValidatesPagingBeforeSendingRequest() throws Exception {
        login();
        for (String cursor : Arrays.asList("0", "-1", "1&admin=true", "9223372036854775808")) {
            Result<MyPostsPage> result = new Result<>(); client.getMyPosts(20, cursor, result); result.fails();
        }
        for (int count : new int[]{0, 51}) {
            Result<MyPostsPage> result = new Result<>(); client.getMyPosts(count, null, result); result.fails();
        }
        assertEquals(1, requests.size());
    }
    @Test public void myPostsAuthRefreshAndServerFailuresUseNormalSessionPolicy() throws Exception {
        Result<MyPostsPage> guest = new Result<>(); client.getMyPosts(20, null, guest); guest.fails();
        assertTrue(requests.isEmpty());
        login(); reply(401, "{}"); reply(200, "{\"accessToken\":\"second\",\"refreshToken\":\"next\"}");
        reply(200, "[]"); assertTrue(myPosts(20, null).getPosts().isEmpty());
        assertTrue(requests.get(3).contains("Bearer second"));
        reply(503, "{}");
        Result<MyPostsPage> failed = new Result<>(); client.getMyPosts(20, null, failed); failed.fails();
    }
    @Test public void sendsCursorAndMapsRealLikeCount() throws Exception {
        reply(200, "{\"status_code\":0,\"has_more\":1,\"next_cursor\":\"7\",\"post_list\":[{\"post_id\":\"8\",\"like_count\":0,\"is_liked\":true}]}");
        Result<ApiResponse> result = new Result<>(); client.getFeed(12, "20", result);
        ApiResponse page = result.get();
        assertEquals("7", page.getNextCursor());
        assertTrue(requests.get(0).contains("cursor=20"));
        WorkItem item = new WorkItem(page.getPostList().get(0));
        assertEquals(0, item.getLikeCount()); assertTrue(item.isLiked());
        assertEquals(0, new WorkItem(new Gson().fromJson("{\"post_id\":\"1\"}", PostItem.class)).getLikeCount());
    }
    @Test public void refreshesOnceThenReplaysWithNewToken() throws Exception {
        login(); reply(401, "{}");
        reply(200, "{\"accessToken\":\"second\",\"refreshToken\":\"refresh-second\"}");
        reply(200, FEED); reply(200, FEED);
        Result<ApiResponse> first = new Result<>(), second = new Result<>();
        client.getFeed(12, null, first); client.getFeed(12, null, second);
        first.get(); second.get();
        assertEquals(5, requests.size());
        assertTrue(requests.get(1).contains("Bearer first"));
        assertTrue(requests.get(2).contains("/api/auth/refresh null"));
        assertTrue(requests.get(2).contains("refresh-first"));
        assertTrue(requests.get(3).contains("Bearer second"));
        assertTrue(requests.get(4).contains("Bearer second"));
    }
    @Test public void invalidRefreshClearsSessionWithoutInfiniteRetry() throws Exception {
        login(); reply(401, "{}"); reply(401, "{}");
        Result<ApiResponse> result = new Result<>(); client.getFeed(12, null, result); result.fails();
        assertFalse(client.isLoggedIn()); assertEquals(3, requests.size());
    }
    @Test public void guestCannotLikeAndFailureDoesNotInventSuccess() throws Exception {
        Result<PostItem> guest = new Result<>(); client.setLike("1", true, guest); guest.fails();
        assertEquals(0, requests.size());
        login(); reply(500, "{}");
        Result<PostItem> failed = new Result<>(); client.setLike("1", true, failed); failed.fails();
        assertEquals(2, requests.size());
    }
    @Test public void likeReadsAuthoritativeStateAndLogoutRemovesToken() throws Exception {
        login(); reply(200, "{\"active\":true}");
        reply(200, "{\"post_id\":\"1\",\"is_liked\":true,\"like_count\":3}");
        Result<PostItem> like = new Result<>(); client.setLike("1", true, like);
        assertEquals(3, like.get().getLikeCount());
        assertTrue(requests.get(1).contains("PUT /api/interactions/likes Bearer first"));
        reply(200, "{}");
        Result<Void> logout = new Result<>(); client.logout(logout);
        assertFalse(client.isLoggedIn()); logout.get();
        reply(200, FEED);
        Result<ApiResponse> feed = new Result<>(); client.getFeed(12, null, feed); feed.get();
        assertTrue(requests.get(4).contains(" null "));
    }
    @Test public void postDetailCarriesFavoriteState() throws Exception {
        reply(200, "{\"post_id\":\"1\",\"is_liked\":true,\"like_count\":3,\"is_favorited\":true}");
        Result<PostItem> detail = new Result<>(); client.getPost("1", detail);
        PostItem post = detail.get();
        assertTrue(post.isFavorited()); assertTrue(post.isLiked()); assertEquals(3, post.getLikeCount());
        WorkItem item = new WorkItem(post);
        assertTrue(item.isFavorited());
        assertTrue(requests.get(0).startsWith("GET /api/posts/1 null"));
    }
    @Test public void favoriteWriteReadsAuthoritativeState() throws Exception {
        login(); reply(200, "{\"active\":true}");
        reply(200, "{\"post_id\":\"1\",\"is_favorited\":true,\"is_liked\":false}");
        Result<PostItem> fav = new Result<>(); client.setFavorite("1", true, fav);
        assertTrue(fav.get().isFavorited());
        assertTrue(requests.get(1).contains("PUT /api/interactions/favorites Bearer first"));
        assertTrue(requests.get(2).contains("GET /api/posts/1 Bearer first"));
    }
    @Test public void guestCannotFavoriteAndFailureDoesNotInventSuccess() throws Exception {
        Result<PostItem> guest = new Result<>(); client.setFavorite("1", true, guest); guest.fails();
        assertEquals(0, requests.size());
        login(); reply(500, "{}");
        Result<PostItem> failed = new Result<>(); client.setFavorite("1", true, failed); failed.fails();
        assertEquals(2, requests.size());
    }
    @Test public void malformedResponseAndInvalidCursorFail() throws Exception {
        reply(200, "not json");
        Result<ApiResponse> malformed = new Result<>(); client.getFeed(12, null, malformed); malformed.fails();
        Result<ApiResponse> invalid = new Result<>(); client.getFeed(12, "1&admin=true", invalid); invalid.fails();
        assertEquals(1, requests.size());
    }
    @Test public void logoutRejectsLateAuthenticatedResponse() throws Exception {
        login(); reply(200, FEED); reply(200, "{}");
        feedEntered = new CountDownLatch(1); releaseFeed = new CountDownLatch(1);
        Result<ApiResponse> pending = new Result<>(); client.getFeed(12, null, pending);
        assertTrue(feedEntered.await(3, TimeUnit.SECONDS));
        Result<Void> logout = new Result<>(); client.logout(logout);
        releaseFeed.countDown();
        pending.fails(); logout.get();
        assertFalse(client.isLoggedIn());
    }
    @Test public void cancelledViewReceivesNoCallback() throws Exception {
        reply(200, FEED);
        feedEntered = new CountDownLatch(1); releaseFeed = new CountDownLatch(1);
        Result<ApiResponse> pending = new Result<>();
        CommunityClient.Job job = client.getFeed(12, null, pending);
        assertTrue(feedEntered.await(3, TimeUnit.SECONDS));
        job.cancel(); releaseFeed.countDown();
        Result<ApiResponse> barrier = new Result<>(); client.getFeed(0, null, barrier); barrier.fails();
        assertFalse(pending.result.isDone());
    }
    @Test public void telemetrySkipsGuestsAndDoesNotInjectUserIdentity() throws Exception {
        client.trackEvent("1", "CLICK", null, "DETAIL");
        Result<ApiResponse> barrier = new Result<>(); client.getFeed(0, null, barrier); barrier.fails();
        assertEquals(0, requests.size());
        login(); reply(200, "{\"duplicate\":false}");
        client.trackEvent("1", "DWELL", 2500L, "DETAIL");
        barrier = new Result<>(); client.getFeed(0, null, barrier); barrier.fails();
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).contains("POST /api/events Bearer first"));
        assertTrue(requests.get(1).contains("\"durationMs\":2500"));
        assertFalse(requests.get(1).contains("userId"));
        assertFalse(requests.get(1).contains("source"));
    }
    @Test public void telemetryFailureDoesNotBreakBusinessRequests() throws Exception {
        login(); reply(500, "{}"); reply(200, FEED);
        client.trackEvent("1", "CLICK", null, "DETAIL");
        Result<ApiResponse> feed = new Result<>(); client.getFeed(12, null, feed);
        assertNotNull(feed.get());
        assertTrue(client.isLoggedIn());
        assertEquals(3, requests.size());
    }
    @Test public void profileUsesAuthenticatedGatewayAndRejectsMalformedTopics() throws Exception {
        login(); reply(200, "{\"version\":1,\"topics\":[{\"name\":\"美食\",\"score\":3}]}");
        Result<com.google.gson.JsonObject> profile = new Result<>(); client.getProfile(profile);
        assertEquals(1, profile.get().get("version").getAsInt());
        assertTrue(requests.get(1).contains("GET /api/users/me/profile Bearer first"));
        reply(200, "{\"version\":1,\"topics\":[7]}");
        profile = new Result<>(); client.getProfile(profile); profile.fails();
    }
    private static final String USER = "{\"id\":\"user-a\",\"username\":\"alice\",\"nickname\":\"小明\",\"bio\":\"摄影\",\"avatar\":\"\"}";
    @Test public void readsServerProfileAndRealZeroStatistics() throws Exception {
        Result<CurrentUser> guest = new Result<>(); client.getCurrentUser(guest); guest.fails();
        assertTrue(requests.isEmpty());
        login(); reply(200, USER);
        Result<CurrentUser> me = new Result<>(); client.getCurrentUser(me);
        assertEquals("小明", me.get().nickname);
        reply(200, "{\"user_id\":\"user-a\",\"post_count\":0,\"follower_count\":2,\"following_count\":3}");
        Result<UserStats> stats = new Result<>(); client.getUserStats("user-a", stats);
        assertEquals(0, stats.get().posts); assertEquals(2, stats.get().followers); assertEquals(3, stats.get().following);
        assertTrue(requests.get(1).contains("GET /api/users/me Bearer first"));
        assertTrue(requests.get(2).contains("GET /api/users/me/stats Bearer first"));
    }
    @Test public void editsOnlyNicknameAndBioAndUsesServerResponse() throws Exception {
        login(); reply(200, USER);
        Result<CurrentUser> result = new Result<>(); client.updateCurrentUser("user-a", " 小明 ", "摄影\n旅行", result);
        assertEquals("摄影", result.get().bio);
        String sent = requests.get(1); assertTrue(sent.startsWith("PUT /api/users/me Bearer first"));
        assertTrue(sent.contains("\"nickname\":\"小明\"")); assertTrue(sent.contains("摄影\\n旅行"));
        assertFalse(sent.contains("avatar")); assertFalse(sent.contains("userId"));
        int before = requests.size();
        for (String invalid : Arrays.asList("", "   ", String.join("", Collections.nCopies(41, "a")))) {
            result = new Result<>(); client.updateCurrentUser("user-a", invalid, "", result); result.fails();
        }
        result = new Result<>(); client.updateCurrentUser("user-a", "Alice", String.join("", Collections.nCopies(61, "a")), result); result.fails();
        assertEquals(before, requests.size());
        reply(200, USER.replace("user-a", "user-b"));
        result = new Result<>(); client.updateCurrentUser("user-a", "Alice", "", result); result.fails();
    }
    @Test public void missingStatisticsAndCrossAccountStatisticsAreNotDisplayedAsZero() throws Exception {
        login();
        for (String raw : Arrays.asList("{}", "{\"user_id\":\"user-b\",\"post_count\":0,\"follower_count\":0,\"following_count\":0}",
                "{\"user_id\":\"user-a\",\"post_count\":-1,\"follower_count\":0,\"following_count\":0}",
                "{\"user_id\":\"user-a\",\"post_count\":1.5,\"follower_count\":0,\"following_count\":0}",
                "{\"user_id\":\"user-a\",\"post_count\":\"0\",\"follower_count\":0,\"following_count\":0}")) {
            reply(200, raw); Result<UserStats> result = new Result<>(); client.getUserStats("user-a", result); result.fails();
        }
        reply(200, USER.replace("\"bio\":\"摄影\",", ""));
        Result<CurrentUser> me = new Result<>(); client.getCurrentUser(me); me.fails();
    }
    @Test public void userReadRefreshesSessionAndFailedUpdateDoesNotReportSuccess() throws Exception {
        login(); reply(401, "{}"); reply(200, "{\"accessToken\":\"second\",\"refreshToken\":\"refresh-second\"}"); reply(200, USER);
        Result<CurrentUser> me = new Result<>(); client.getCurrentUser(me); assertEquals("user-a", me.get().id);
        assertTrue(requests.get(3).contains("GET /api/users/me Bearer second"));
        reply(503, "{\"message\":\"暂时不可用\"}");
        Result<CurrentUser> update = new Result<>(); client.updateCurrentUser("user-a", "Alice", "bio", update); update.fails();
        assertTrue(client.isLoggedIn()); assertEquals(5, requests.size());
    }
    @Test public void profileHistoryPaginatesByVersionCursor() throws Exception {
        login();
        reply(200, "{\"user_id\":\"user-a\",\"has_more\":true,\"next_cursor\":\"5\",\"items\":[{\"version\":10,\"explanation\":\"旧画像\"}]}");
        Result<ProfileHistoryPage> first = new Result<>(); client.getProfileHistory(20, null, first);
        ProfileHistoryPage page = first.get();
        assertEquals("user-a", page.getUserId());
        assertEquals(1, page.getItems().size());
        assertEquals(10L, page.getItems().get(0).getVersion());
        assertTrue(page.isHasMore());
        assertEquals("5", page.getNextCursor());
        assertTrue(requests.get(1).startsWith("GET /api/users/me/profile/history?count=20 Bearer first"));

        reply(200, "{\"user_id\":\"user-a\",\"has_more\":false,\"next_cursor\":null,\"items\":[]}");
        Result<ProfileHistoryPage> second = new Result<>(); client.getProfileHistory(20, "5", second);
        assertFalse(second.get().isHasMore()); assertNull(second.get().getNextCursor());
        assertTrue(requests.get(2).contains("before_version=5"));
    }
    @Test public void profileHistoryValidatesPagingAndRejectsIncomplete() throws Exception {
        login();
        for (String cursor : Arrays.asList("0", "-1", "1&x=2", "abc")) {
            Result<ProfileHistoryPage> r = new Result<>(); client.getProfileHistory(20, cursor, r); r.fails();
        }
        for (int count : new int[]{0, 51}) {
            Result<ProfileHistoryPage> r = new Result<>(); client.getProfileHistory(count, null, r); r.fails();
        }
        assertEquals(1, requests.size());
        reply(200, "{\"user_id\":\"user-a\"}");
        Result<ProfileHistoryPage> r = new Result<>(); client.getProfileHistory(20, null, r); r.fails();
    }
    @Test public void recommendationFeedParsesStrategyAndCardMetadata() throws Exception {
        login();
        reply(200, "{\"strategy\":\"personalized\",\"reason\":\"基于你的美食偏好\",\"profile_version\":42,"
                + "\"preferences_changed\":false,\"post_list\":[{\"post_id\":\"8\",\"title\":\"作品\","
                + "\"clips\":[],\"media_ids\":[],\"rec_reason\":\"喜欢美食\",\"rec_score\":0.812345}]}");
        Result<RecommendationFeed> result = new Result<>(); client.getRecommendationFeed(result);
        RecommendationFeed feed = result.get();
        assertEquals("personalized", feed.getStrategy());
        assertEquals("基于你的美食偏好", feed.getReason());
        assertEquals(42L, feed.getProfileVersion());
        assertFalse(feed.isPreferencesChanged());
        assertEquals(1, feed.getPostList().size());
        PostItem post = feed.getPostList().get(0);
        assertEquals("8", post.getPostId());
        assertEquals("喜欢美食", post.getRecReason());
        assertEquals(0.812345, post.getRecScore(), 1e-9);
        assertTrue(requests.get(1).startsWith("GET /api/recommendations/me/feed Bearer first"));
    }
    @Test public void recommendationFeedEmptyListAndPreferencesChangedAreValid() throws Exception {
        login();
        reply(200, "{\"strategy\":\"popular\",\"reason\":null,\"profile_version\":7,"
                + "\"preferences_changed\":true,\"post_list\":[]}");
        Result<RecommendationFeed> result = new Result<>(); client.getRecommendationFeed(result);
        RecommendationFeed feed = result.get();
        assertEquals("popular", feed.getStrategy());
        assertTrue(feed.isPreferencesChanged());
        assertTrue(feed.getPostList().isEmpty());
    }
    @Test public void recommendationFeedRequiresLoginAndRejectsIncomplete() throws Exception {
        Result<RecommendationFeed> guest = new Result<>(); client.getRecommendationFeed(guest); guest.fails();
        assertEquals(0, requests.size());
        login();
        for (String body : Arrays.asList("null", "{}", "not json", "{\"strategy\":\"popular\"}")) {
            reply(200, body);
            Result<RecommendationFeed> result = new Result<>(); client.getRecommendationFeed(result); result.fails();
        }
        reply(503, "{}");
        Result<RecommendationFeed> failed = new Result<>(); client.getRecommendationFeed(failed); failed.fails();
        assertTrue(client.isLoggedIn());
    }
    private static final String AVATAR_ID = "01234567-89ab-4cde-8f01-23456789abcd";
    @Test public void setAvatarSendsMediaIdAndReadsAuthoritativeAvatar() throws Exception {
        login();
        reply(200, "{\"user_id\":\"user-a\",\"avatar\":\"/api/media/" + AVATAR_ID + "/content\"}");
        Result<AvatarResult> result = new Result<>(); client.setAvatar(AVATAR_ID, result);
        assertEquals("/api/media/" + AVATAR_ID + "/content", result.get().avatar);
        assertEquals("user-a", result.get().userId);
        String sent = requests.get(1);
        assertTrue(sent.startsWith("PUT /api/users/me/avatar Bearer first"));
        assertTrue(sent.contains("\"mediaId\":\"" + AVATAR_ID + "\""));
    }
    @Test public void setAvatarRejectsInvalidMediaIdAndRequiresLogin() throws Exception {
        Result<AvatarResult> guest = new Result<>(); client.setAvatar(AVATAR_ID, guest); guest.fails();
        assertEquals(0, requests.size());
        login();
        for (String mediaId : Arrays.asList(null, "", "abc", AVATAR_ID.toUpperCase(java.util.Locale.ROOT),
                "01234567-89ab-4cde-8f01-23456789abcdx", "01234567-89AB-4CDE-8F01-23456789ABCD")) {
            Result<AvatarResult> result = new Result<>(); client.setAvatar(mediaId, result); result.fails();
        }
        assertEquals(1, requests.size()); // 仅 login，非法 mediaId 不发请求
    }
    @Test public void clearAvatarIsIdempotentAndReturnsEmptyAvatar() throws Exception {
        login();
        reply(200, "{\"user_id\":\"user-a\",\"avatar\":\"\"}");
        Result<AvatarResult> result = new Result<>(); client.clearAvatar(result);
        assertEquals("", result.get().avatar);
        assertTrue(requests.get(1).startsWith("DELETE /api/users/me/avatar Bearer first"));
    }
    @Test public void avatarResponsesRejectIncompleteOrMalformed() throws Exception {
        login();
        for (String body : Arrays.asList("null", "{}", "not json", "{\"user_id\":\"user-a\"}",
                "{\"user_id\":\"\",\"avatar\":\"\"}", "{\"user_id\":\"user-a\",\"avatar\":null}")) {
            reply(200, body);
            Result<AvatarResult> result = new Result<>(); client.setAvatar(AVATAR_ID, result); result.fails();
        }
        reply(503, "{}");
        Result<AvatarResult> failed = new Result<>(); client.clearAvatar(failed); failed.fails();
        assertTrue(client.isLoggedIn());
    }
}
