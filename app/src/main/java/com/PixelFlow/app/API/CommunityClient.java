package com.PixelFlow.app.API;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Serialized requests prevent competing refresh rotations; credentials stay in memory. */
public class CommunityClient implements AutoCloseable {
    public interface Callback<T> {
        void onSuccess(T value);
        void onFailure(String message);
        default void onFailure(int status, String message) { onFailure(message); }
    }
    /** Persists session tokens on change; implemented by ApiClient via TokenStore. */
    public interface TokenListener {
        void onTokens(String access, String refresh, String username);
        void onCleared();
    }
    public static final class Job {
        private volatile boolean cancelled;
        private volatile Call call;
        private String creationKey;
        public void cancel() { cancelled = true; if (call != null) call.cancel(); }
    }
    private interface Task<T> { T run(Job job, long version) throws Exception; }
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final Gson gson = new Gson();
    private final HttpUrl base;
    private final Executor delivery;
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private String access, refresh, username;
    private long generation;
    private TokenListener tokenListener;
    private final java.util.concurrent.atomic.AtomicInteger pendingEvents = new java.util.concurrent.atomic.AtomicInteger();

    public CommunityClient(String baseUrl, Executor delivery) {
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        base = parsed != null && parsed.username().isEmpty() && parsed.password().isEmpty()
                && parsed.query() == null && parsed.fragment() == null && parsed.encodedPath().equals("/")
                ? parsed : null;
        this.delivery = delivery;
    }
    public synchronized boolean isLoggedIn() { return access != null; }
    public synchronized String username() { return username == null ? "游客" : username; }
    public synchronized long sessionVersion() { return generation; }
    private synchronized void check(long expected) throws IOException {
        if (expected != generation) throw new ApiError(0, "账号状态已变化，请重试");
    }
    public void setTokenListener(TokenListener listener) { this.tokenListener = listener; }
    /** Restore a persisted session at startup; no-op when nothing is stored. */
    public synchronized void restore(String access, String refresh, String username) {
        if (access == null && refresh == null) return;
        this.access = access;
        this.refresh = refresh;
        this.username = username;
    }
    private synchronized void notifyTokens() {
        TokenListener listener = tokenListener;
        if (listener != null) listener.onTokens(access, refresh, username);
    }
    private synchronized void notifyCleared() {
        TokenListener listener = tokenListener;
        if (listener != null) listener.onCleared();
    }
    private synchronized String access(long expected) throws IOException { check(expected); return access; }
    private synchronized void install(JsonObject tokens, String name, long expected) throws IOException {
        check(expected);
        if (tokens == null || !tokens.has("accessToken") || !tokens.has("refreshToken"))
            throw new ApiError(0, "登录响应不完整，请重新登录");
        access = tokens.get("accessToken").getAsString();
        refresh = tokens.get("refreshToken").getAsString();
        username = name;
        notifyTokens();
    }
    private <T> Job submit(Task<T> task, Callback<T> callback) {
        Job job = new Job();
        long expected = sessionVersion();
        queue.execute(() -> {
            if (job.cancelled) return;
            try {
                check(expected);
                T value = task.run(job, expected);
                delivery.execute(() -> {
                    if (job.cancelled) return;
                    if (sessionVersion() != expected) callback.onFailure("账号状态已变化，请重试");
                    else callback.onSuccess(value);
                });
            } catch (Exception error) {
                String message = error instanceof ApiError ? error.getMessage()
                        : "请求未完成，请检查网络并刷新重试";
                int status = error instanceof ApiError ? ((ApiError) error).status : 0;
                delivery.execute(() -> { if (!job.cancelled) callback.onFailure(status, message); });
            }
        });
        return job;
    }
    private static final class ApiError extends IOException {
        final int status;
        ApiError(int status, String message) { super(message); this.status = status; }
    }
    private String request(Job job, String path, String method, Object body, String token) throws IOException {
        if (base == null) throw new ApiError(0, "服务地址无效，请检查 api.properties");
        if (job.cancelled) throw new IOException("cancelled");
        RequestBody requestBody = method.equals("GET") ? null : RequestBody.create(gson.toJson(body), JSON);
        return send(job, path, method, requestBody, token);
    }
    private String send(Job job, String path, String method, RequestBody body, String token) throws IOException {
        Request.Builder builder = new Request.Builder().url(base.resolve(path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if ("POST".equals(method) && "/api/posts".equals(path) && job.creationKey != null)
            builder.header("Idempotency-Key", job.creationKey);
        builder.method(method, body);
        OkHttpClient transport = isAgentAggregate(path)
                ? http.newBuilder().readTimeout(50, TimeUnit.SECONDS).build() : http;
        job.call = transport.newCall(builder.build());
        if (isAgentAggregate(path)) job.call.timeout().timeout(55, TimeUnit.SECONDS);
        if (path.equals("/api/events")) job.call.timeout().timeout(3, TimeUnit.SECONDS);
        if (job.cancelled) job.call.cancel();
        try (Response response = job.call.execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String message = "请求失败（HTTP " + response.code() + "），请重试";
                try {
                    JsonObject error = gson.fromJson(raw, JsonObject.class);
                    if (error != null && error.has("message")) message = error.get("message").getAsString();
                } catch (RuntimeException ignored) { }
                throw new ApiError(response.code(), message.length() > 200 ? "服务器返回错误" : message);
            }
            return raw;
        }
    }
    /** Agent 聚合类接口（LLM 综合，秒级响应），统一放宽读超时并允许 55s 整体超时。 */
    private static boolean isAgentAggregate(String path) {
        return path.equals("/api/agent/create-assist") || path.equals("/api/agent/search");
    }
    private String authenticated(Job job, String path, String method, Object body, long expected,
            boolean required) throws IOException {
        RequestBody requestBody = method.equals("GET") ? null : RequestBody.create(gson.toJson(body), JSON);
        return authenticatedRaw(job, path, method, requestBody, expected, required);
    }
    private String authenticatedUpload(Job job, String path, RequestBody body, long expected) throws IOException {
        return authenticatedRaw(job, path, "POST", body, expected, true);
    }
    private String authenticatedRaw(Job job, String path, String method, RequestBody body, long expected,
            boolean required) throws IOException {
        String token = access(expected);
        if (required && token == null) throw new ApiError(401, "请先在“我的 → 账号与登录”中登录");
        try { return send(job, path, method, body, token); }
        catch (ApiError failure) {
            if (failure.status != 401 || token == null) throw failure;
        }
        refreshCredentials(expected);
        return send(job, path, method, body, access(expected));
    }
    private void refreshCredentials(long expected) throws IOException {
        String refreshToken, name;
        synchronized (this) { check(expected); refreshToken = refresh; name = username; }
        JsonObject input = new JsonObject(); input.addProperty("refreshToken", refreshToken);
        try {
            // Preserve rotated tokens even if the original screen cancels during rotation.
            Job rotation = new Job();
            JsonObject tokens = gson.fromJson(request(rotation, "/api/auth/refresh", "POST", input, null), JsonObject.class);
            install(tokens, name, expected);
        } catch (ApiError failure) {
            if (failure.status == 401) synchronized (this) {
                if (generation == expected) {
                    access = null; refresh = null; username = null; generation++;
                    notifyCleared();
                }
            }
            throw failure;
        }
    }
    /** Credentials are only ever sent to this exact origin and media-content route. */
    public boolean isPrivateMediaUrl(String url) {
        HttpUrl resolved = base == null || url == null ? null : base.resolve(url);
        return resolved != null && resolved.scheme().equals(base.scheme())
                && resolved.host().equals(base.host()) && resolved.port() == base.port()
                && resolved.username().isEmpty() && resolved.password().isEmpty()
                && resolved.query() == null && resolved.fragment() == null
                && resolved.encodedPath().matches("/api/media/[A-Za-z0-9-]{1,36}/content");
    }
    public Job getMediaContent(String url, Callback<byte[]> callback) {
        return submit((job, expected) -> {
            if (!isPrivateMediaUrl(url)) throw new ApiError(400, "图片地址无效");
            String token = access(expected);
            if (token == null) throw new ApiError(401, "请先登录");
            try { return mediaBytes(job, url, token); }
            catch (ApiError error) { if (error.status != 401) throw error; }
            refreshCredentials(expected);
            return mediaBytes(job, url, access(expected));
        }, callback);
    }
    private byte[] mediaBytes(Job job, String url, String token) throws IOException {
        if (job.cancelled) throw new IOException("cancelled");
        job.call = http.newCall(new Request.Builder().url(base.resolve(url))
                .header("Authorization", "Bearer " + token).build());
        if (job.cancelled) job.call.cancel();
        try (Response response = job.call.execute()) {
            if (!response.isSuccessful()) throw new ApiError(response.code(), "图片读取失败");
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > 8 * 1024 * 1024)
                throw new ApiError(0, "图片大小无效");
            try (java.io.InputStream input = body.byteStream();
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() + length > 8 * 1024 * 1024) throw new ApiError(0, "图片过大");
                    output.write(buffer, 0, length);
                }
                return output.toByteArray();
            }
        }
    }
    public Job login(String name, String password, Callback<Void> callback) {
        JsonObject input = new JsonObject(); input.addProperty("username", name); input.addProperty("password", password);
        return submit((job, expected) -> {
            if (isLoggedIn()) throw new ApiError(409, "请先退出当前账号");
            JsonObject tokens = gson.fromJson(request(job, "/api/auth/login", "POST", input, null), JsonObject.class);
            if (!job.cancelled) install(tokens, name, expected);
            return null;
        }, callback);
    }
    public Job register(String name, String password, String nickname, Callback<Void> callback) {
        JsonObject input = new JsonObject(); input.addProperty("username", name);
        input.addProperty("password", password); input.addProperty("nickname", nickname);
        return submit((job, expected) -> { request(job, "/api/auth/register", "POST", input, null); return null; }, callback);
    }
    public Job logout(Callback<Void> callback) {
        final String oldToken;
        synchronized (this) {
            oldToken = access; access = null; refresh = null; username = null; generation++;
            notifyCleared();
        }
        return submit((job, expected) -> {
            if (oldToken != null) request(job, "/api/auth/logout", "POST", new JsonObject(), oldToken);
            return null;
        }, callback);
    }
    public Job getFeed(int count, String cursor, Callback<ApiResponse> callback) {
        return submit((job, expected) -> {
            if (count < 1 || count > 50 || (cursor != null && !cursor.matches("[1-9][0-9]{0,18}")))
                throw new ApiError(400, "分页参数无效");
            ApiResponse result = gson.fromJson(authenticated(job, "/feed/?accept_video_clip=true&count=" + count
                    + (cursor == null ? "" : "&cursor=" + cursor), "GET", null, expected, false), ApiResponse.class);
            if (result == null || result.getStatusCode() != 0 || result.getPostList() == null)
                throw new ApiError(0, "作品列表响应不完整");
            return result;
        }, callback);
    }
    /** 关键词搜索公开作品；契约见 contracts/http/java-api.yaml 的 /api/search/posts。游客可用。 */
    public Job searchPosts(String query, int count, String cursor, Callback<ApiResponse> callback) {
        return submit((job, expected) -> {
            if (query == null || query.trim().isEmpty() || query.trim().length() > 60)
                throw new ApiError(400, "搜索关键词需为 1–60 字");
            if (count < 1 || count > 50 || (cursor != null && !cursor.matches("[1-9][0-9]{0,18}")))
                throw new ApiError(400, "分页参数无效");
            String encoded = java.net.URLEncoder.encode(query.trim(), java.nio.charset.StandardCharsets.UTF_8);
            ApiResponse result = gson.fromJson(authenticated(job, "/api/search/posts?q=" + encoded
                    + "&count=" + count + (cursor == null ? "" : "&cursor=" + cursor),
                    "GET", null, expected, false), ApiResponse.class);
            if (result == null || result.getStatusCode() != 0 || result.getPostList() == null)
                throw new ApiError(0, "搜索结果响应不完整");
            return result;
        }, callback);
    }
    public Job getRecommendationFeed(Callback<RecommendationFeed> callback) {
        return submit((job, expected) -> {
            RecommendationFeed feed = gson.fromJson(
                    authenticated(job, "/api/recommendations/me/feed", "GET", null, expected, true),
                    RecommendationFeed.class);
            if (feed == null || feed.getPostList() == null)
                throw new ApiError(0, "推荐列表响应不完整");
            return feed;
        }, callback);
    }
    public Job getConnections(boolean followers, int count, String cursor, Callback<ConnectionPage> callback) {
        return submit((job, expected) -> {
            if (count < 1 || count > 50 || (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,36}")))
                throw new ApiError(400, "分页参数无效");
            String path = (followers ? "/api/users/me/followers" : "/api/users/me/following")
                    + "?count=" + count + (cursor == null ? "" : "&cursor=" + cursor);
            ConnectionPage page = ConnectionPage.parse(gson.fromJson(
                    authenticated(job, path, "GET", null, expected, true), JsonObject.class));
            return page;
        }, callback);
    }
    /** 消息通知分页；type 为 MENTION（@我）/REPLY（回复）/LIKE（赞）。 */
    public Job getNotifications(String type, String cursor, Callback<NotificationPage> callback) {
        return submit((job, expected) -> {
            if (!type.equals("MENTION") && !type.equals("REPLY") && !type.equals("LIKE"))
                throw new ApiError(400, "通知类型无效");
            if (cursor != null && !cursor.matches("[1-9][0-9]{0,18}"))
                throw new ApiError(400, "分页参数无效");
            NotificationPage page = NotificationPage.parse(gson.fromJson(
                    authenticated(job, "/api/users/me/notifications?type=" + type + "&count=20"
                            + (cursor == null ? "" : "&cursor=" + cursor), "GET", null, expected, true),
                    JsonObject.class));
            return page;
        }, callback);
    }
    public Job getUnreadNotificationCount(Callback<Integer> callback) {
        return submit((job, expected) -> {
            JsonObject result = gson.fromJson(authenticated(job, "/api/users/me/notifications/unread-count",
                    "GET", null, expected, true), JsonObject.class);
            if (result == null || !result.has("count")) throw new ApiError(0, "未读数响应不完整");
            return result.get("count").getAsInt();
        }, callback);
    }
    /** 全部标记已读。 */
    public Job markNotificationsRead(Callback<Void> callback) {
        return submit((job, expected) -> {
            authenticated(job, "/api/users/me/notifications/read", "POST", new JsonObject(), expected, true);
            return null;
        }, callback);
    }
    public Job getNotificationPreferences(Callback<java.util.Set<String>> callback) {
        return submit((job, expected) -> parseMuted(authenticated(job, "/api/users/me/notification-preferences",
                "GET", null, expected, true)), callback);
    }
    public Job setNotificationPreferences(java.util.Set<String> muted, Callback<java.util.Set<String>> callback) {
        JsonObject input = new JsonObject();
        JsonArray array = new JsonArray();
        for (String type : muted) array.add(type);
        input.add("mutedTypes", array);
        return submit((job, expected) -> parseMuted(authenticated(job, "/api/users/me/notification-preferences",
                "PUT", input, expected, true)), callback);
    }
    private java.util.Set<String> parseMuted(String raw) throws ApiError {
        JsonObject result = gson.fromJson(raw, JsonObject.class);
        if (result == null || !result.has("muted_types") || !result.get("muted_types").isJsonArray())
            throw new ApiError(0, "通知偏好响应不完整");
        java.util.Set<String> muted = new java.util.LinkedHashSet<>();
        for (JsonElement item : result.getAsJsonArray("muted_types"))
            if (item.isJsonPrimitive()) muted.add(item.getAsString());
        return muted;
    }
    /** 作品评论分页（倒序）。 */
    public Job getComments(String postId, String cursor, Callback<CommentPage> callback) {
        return submit((job, expected) -> {
            if (postId == null || !postId.matches("[1-9][0-9]{0,18}"))
                throw new ApiError(400, "作品 ID 无效");
            if (cursor != null && !cursor.matches("[1-9][0-9]{0,18}"))
                throw new ApiError(400, "分页参数无效");
            CommentPage page = CommentPage.parse(gson.fromJson(
                    authenticated(job, "/api/posts/" + postId + "/comments?count=20"
                            + (cursor == null ? "" : "&cursor=" + cursor), "GET", null, expected, true),
                    JsonObject.class));
            return page;
        }, callback);
    }
    /** 发表评论；parentCommentId 为 null 表示一级评论（回复）。 */
    public Job createComment(String postId, String content, String parentCommentId,
                             Callback<CommentPage.CommentItem> callback) {
        return submit((job, expected) -> {
            if (postId == null || !postId.matches("[1-9][0-9]{0,18}"))
                throw new ApiError(400, "作品 ID 无效");
            if (content == null || content.trim().isEmpty() || content.trim().length() > 500)
                throw new ApiError(400, "评论应为 1–500 字");
            if (parentCommentId != null && !parentCommentId.matches("[1-9][0-9]{0,18}"))
                throw new ApiError(400, "被回复评论无效");
            JsonObject input = new JsonObject();
            input.addProperty("content", content.trim());
            if (parentCommentId != null) input.addProperty("parentCommentId", parentCommentId);
            JsonObject result = gson.fromJson(authenticated(job, "/api/posts/" + postId + "/comments",
                    "POST", input, expected, true), JsonObject.class);
            return CommentPage.CommentItem.parse(result);
        }, callback);
    }
    /** 删除自己的评论（管理员可删任意）。 */
    public Job deleteComment(String commentId, Callback<Void> callback) {
        return submit((job, expected) -> {
            if (commentId == null || !commentId.matches("[1-9][0-9]{0,18}"))
                throw new ApiError(400, "评论 ID 无效");
            authenticated(job, "/api/comments/" + commentId, "DELETE", null, expected, true);
            return null;
        }, callback);
    }

    /** 作品作者读取自己配置的「场景 → 答案」规则。 */
    public Job getAutoReplies(String postId, Callback<List<AutoReplyRule>> callback) {
        return submit((job, expected) -> {
            if (postId == null || !postId.matches("[1-9][0-9]{0,18}"))
                throw new ApiError(400, "作品 ID 无效");
            return AutoReplyRule.parseList(gson.fromJson(
                    authenticated(job, "/api/posts/" + postId + "/auto-replies", "GET", null, expected, true),
                    JsonObject.class));
        }, callback);
    }

    public Job createAutoReply(String postId, String question, String answer,
                               Callback<AutoReplyRule> callback) {
        return submit((job, expected) -> {
            JsonObject input = autoReplyInput(question, answer);
            return AutoReplyRule.parse(gson.fromJson(authenticated(job,
                    "/api/posts/" + postId + "/auto-replies", "POST", input, expected, true), JsonObject.class));
        }, callback);
    }

    public Job updateAutoReply(String postId, String ruleId, String question, String answer,
                               boolean enabled, Callback<AutoReplyRule> callback) {
        return submit((job, expected) -> {
            JsonObject input = autoReplyInput(question, answer);
            input.addProperty("enabled", enabled);
            return AutoReplyRule.parse(gson.fromJson(authenticated(job,
                    "/api/posts/" + postId + "/auto-replies/" + ruleId, "PUT", input, expected, true),
                    JsonObject.class));
        }, callback);
    }

    public Job deleteAutoReply(String postId, String ruleId, Callback<Void> callback) {
        return submit((job, expected) -> {
            authenticated(job, "/api/posts/" + postId + "/auto-replies/" + ruleId, "DELETE", null, expected, true);
            return null;
        }, callback);
    }

    /** 让 Agent 按作品上下文把作者描述拆成若干「场景 → 答案」候选。 */
    public Job suggestAutoReplies(String postId, String prompt, Callback<List<AutoReplyRule>> callback) {
        return submit((job, expected) -> {
            if (prompt == null || prompt.trim().isEmpty() || prompt.trim().length() > 500)
                throw new ApiError(400, "请用一句话描述你想自动回复什么");
            JsonObject input = new JsonObject();
            input.addProperty("prompt", prompt.trim());
            JsonObject result = gson.fromJson(authenticated(job,
                    "/api/posts/" + postId + "/auto-replies/suggest", "POST", input, expected, true),
                    JsonObject.class);
            List<AutoReplyRule> suggestions = new ArrayList<>();
            if (result.has("suggestions") && result.get("suggestions").isJsonArray()) {
                JsonArray array = result.getAsJsonArray("suggestions");
                for (int i = 0; i < array.size(); i++) {
                    if (array.get(i).isJsonObject()) suggestions.add(AutoReplyRule.parse(array.get(i).getAsJsonObject()));
                }
            }
            return suggestions;
        }, callback);
    }
    /** 批量创建规则（AI 候选多选后一次应用）。 */
    public Job batchCreateAutoReplies(String postId, List<AutoReplyRule> rules,
                                      Callback<List<AutoReplyRule>> callback) {
        return submit((job, expected) -> {
            if (rules == null || rules.isEmpty()) throw new ApiError(400, "请先勾选要应用的规则");
            JsonArray array = new JsonArray();
            for (AutoReplyRule rule : rules) {
                JsonObject item = autoReplyInput(rule.question, rule.answer);
                array.add(item);
            }
            JsonObject input = new JsonObject();
            input.add("rules", array);
            JsonObject result = gson.fromJson(authenticated(job,
                    "/api/posts/" + postId + "/auto-replies/batch", "POST", input, expected, true),
                    JsonObject.class);
            List<AutoReplyRule> created = new ArrayList<>();
            if (result.has("created") && result.get("created").isJsonArray()) {
                JsonArray createdArray = result.getAsJsonArray("created");
                for (int i = 0; i < createdArray.size(); i++) {
                    if (createdArray.get(i).isJsonObject())
                        created.add(AutoReplyRule.parse(createdArray.get(i).getAsJsonObject()));
                }
            }
            return created;
        }, callback);
    }
    private JsonObject autoReplyInput(String question, String answer) throws IOException {
        if (question == null || question.trim().isEmpty() || question.trim().length() > 200)
            throw new ApiError(400, "场景描述应为 1–200 字");
        if (answer == null || answer.trim().isEmpty() || answer.trim().length() > 300)
            throw new ApiError(400, "回复内容应为 1–300 字");
        JsonObject input = new JsonObject();
        input.addProperty("question", question.trim());
        input.addProperty("answer", answer.trim());
        return input;
    }

    public Job getUserProfile(String userId, Callback<UserProfile> callback) {
        return submit((job, expected) -> {
            if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,36}"))
                throw new ApiError(400, "用户 ID 无效");
            UserProfile profile = UserProfile.parse(gson.fromJson(
                    authenticated(job, "/api/users/" + userId + "/profile", "GET", null, expected, true), JsonObject.class));
            if (!userId.equals(profile.id)) throw new ApiError(0, "用户资料归属异常，请刷新重试");
            return profile;
        }, callback);
    }
    public Job getUserPosts(String userId, int count, String cursor, Callback<UserPostsPage> callback) {
        return submit((job, expected) -> {
            if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,36}") || count < 1 || count > 50)
                throw new ApiError(400, "用户或分页参数无效");
            try { if (cursor != null) UserPostsPage.positiveId(cursor); }
            catch (IllegalArgumentException invalid) { throw new ApiError(400, "分页参数无效"); }
            String raw = authenticated(job, "/api/users/" + userId + "/posts?count=" + count
                    + (cursor == null ? "" : "&cursor=" + cursor), "GET", null, expected, true);
            try { return UserPostsPage.parse(gson.fromJson(raw, JsonObject.class), userId, count, cursor); }
            catch (RuntimeException invalid) { throw new ApiError(0, "作者作品响应异常，请刷新重试"); }
        }, callback);
    }
    public Job getPost(String id, Callback<PostItem> callback) {
        return submit((job, expected) -> post(job, id, expected), callback);
    }
    public Job getCurrentUser(Callback<CurrentUser> callback) {
        return submit((job, expected) -> currentUser(authenticated(job, "/api/users/me", "GET", null, expected, true)), callback);
    }
    public Job getUserStats(String userId, Callback<UserStats> callback) {
        return submit((job, expected) -> {
            try {
                return UserStats.parse(gson.fromJson(authenticated(job, "/api/users/me/stats", "GET", null, expected, true), JsonObject.class), userId);
            } catch (IllegalArgumentException | ArithmeticException error) {
                throw new ApiError(0, "统计响应不完整，请刷新重试");
            }
        }, callback);
    }
    public Job updateCurrentUser(String userId, String nickname, String bio, Callback<CurrentUser> callback) {
        return submit((job, expected) -> {
            if (nickname == null || nickname.trim().isEmpty() || nickname.trim().length() > 40 || bio == null || bio.length() > 60)
                throw new ApiError(400, "昵称需为 1–40 字，简介最多 60 字");
            JsonObject input = new JsonObject(); input.addProperty("nickname", nickname.trim()); input.addProperty("bio", bio);
            CurrentUser result = currentUser(authenticated(job, "/api/users/me", "PUT", input, expected, true));
            if (!result.id.equals(userId)) throw new ApiError(0, "资料归属异常，请重新登录");
            return result;
        }, callback);
    }
    public Job setAvatar(String mediaId, Callback<AvatarResult> callback) {
        return submit((job, expected) -> {
            if (mediaId == null || !mediaId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new ApiError(400, "头像图片无效，请重新选择");
            JsonObject input = new JsonObject(); input.addProperty("mediaId", mediaId);
            return avatarResult(authenticated(job, "/api/users/me/avatar", "PUT", input, expected, true));
        }, callback);
    }
    public Job clearAvatar(Callback<AvatarResult> callback) {
        return submit((job, expected) ->
                avatarResult(authenticated(job, "/api/users/me/avatar", "DELETE", new JsonObject(), expected, true)), callback);
    }
    private AvatarResult avatarResult(String raw) throws IOException {
        try { return AvatarResult.parse(gson.fromJson(raw, JsonObject.class)); }
        catch (RuntimeException error) { throw new ApiError(0, "头像响应不完整，请刷新重试"); }
    }
    private CurrentUser currentUser(String raw) throws IOException {
        try { return CurrentUser.parse(gson.fromJson(raw, JsonObject.class)); }
        catch (RuntimeException error) { throw new ApiError(0, "资料响应不完整，请刷新重试"); }
    }
    public Job getProfile(Callback<JsonObject> callback) {
        return submit((job, expected) -> {
            JsonObject result = gson.fromJson(authenticated(job, "/api/users/me/profile", "GET", null, expected, true), JsonObject.class);
            if (result == null || !result.has("version") || !result.has("topics") || !result.get("topics").isJsonArray())
                throw new ApiError(0, "画像响应不完整");
            if (!result.get("version").isJsonPrimitive() || !result.getAsJsonPrimitive("version").isNumber())
                throw new ApiError(0, "画像版本无效");
            for (com.google.gson.JsonElement entry : result.getAsJsonArray("topics")) {
                if (!entry.isJsonObject()) throw new ApiError(0, "画像标签无效");
                JsonObject topic = entry.getAsJsonObject();
                if (!topic.has("name") || !topic.get("name").isJsonPrimitive()
                        || !topic.getAsJsonPrimitive("name").isString() || !topic.has("score")
                        || !topic.get("score").isJsonPrimitive() || !topic.getAsJsonPrimitive("score").isNumber())
                    throw new ApiError(0, "画像标签无效");
            }
            return result;
        }, callback);
    }
    private PostItem post(Job job, String id, long expected) throws IOException {
        validateId(id);
        PostItem post = gson.fromJson(authenticated(job, "/api/posts/" + id, "GET", null, expected, false), PostItem.class);
        if (post == null || !id.equals(post.getPostId())) throw new ApiError(0, "作品响应不完整");
        return post;
    }
    public Job setLike(String id, boolean active, Callback<PostItem> callback) {
        return submit((job, expected) -> {
            validateId(id);
            JsonObject input = new JsonObject(); input.addProperty("targetId", id); input.addProperty("active", active);
            authenticated(job, "/api/interactions/likes", "PUT", input, expected, true);
            return post(job, id, expected);
        }, callback);
    }
    public Job setFollow(String targetId, boolean active, Callback<Void> callback) {
        return submit((job, expected) -> {
            if (targetId == null || !targetId.matches("[A-Za-z0-9_-]{1,36}"))
                throw new ApiError(400, "用户 ID 无效");
            JsonObject input = new JsonObject();
            input.addProperty("targetId", targetId); input.addProperty("active", active);
            authenticated(job, "/api/interactions/follows", "PUT", input, expected, true);
            return null;
        }, callback);
    }
    public Job setFavorite(String id, boolean active, Callback<PostItem> callback) {
        return submit((job, expected) -> {
            validateId(id);
            JsonObject input = new JsonObject();
            input.addProperty("targetId", id); input.addProperty("active", active);
            authenticated(job, "/api/interactions/favorites", "PUT", input, expected, true);
            return post(job, id, expected);
        }, callback);
    }
    public Job getFavorites(int count, String cursor, Callback<ApiResponse> callback) {
        return submit((job, expected) -> {
            if (count < 1 || count > 50 || (cursor != null && !cursor.matches("[1-9][0-9]{0,18}")))
                throw new ApiError(400, "分页参数无效");
            String path = "/api/users/me/favorites?count=" + count
                    + (cursor == null ? "" : "&cursor=" + cursor);
            ApiResponse result = gson.fromJson(
                    authenticated(job, path, "GET", null, expected, true), ApiResponse.class);
            if (result == null || result.getStatusCode() != 0 || result.getPostList() == null)
                throw new ApiError(0, "收藏列表响应不完整");
            return result;
        }, callback);
    }
    public Job uploadMedia(byte[] bytes, String contentType, Callback<MediaItem> callback) {
        return submit((job, expected) -> {
            if (bytes == null || bytes.length == 0) throw new ApiError(400, "图片为空");
            if (bytes.length > 8 * 1024 * 1024) throw new ApiError(400, "图片超过 8 MiB，请先压缩");
            MediaType type = MediaType.parse(contentType == null || contentType.isEmpty() ? "image/jpeg" : contentType);
            RequestBody file = RequestBody.create(type, bytes);
            RequestBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "upload.jpg", file).build();
            return MediaItem.parse(gson.fromJson(
                    authenticatedUpload(job, "/api/media", multipart, expected), JsonObject.class));
        }, callback);
    }
    public Job createPost(String title, String content, List<String> mediaIds, Callback<PostItem> callback) {
        return submit((job, expected) -> draft(job, null, title, content, mediaIds, expected), callback);
    }
    public Job createPost(String key, String title, String content, List<String> mediaIds, Callback<PostItem> callback) {
        return submit((job, expected) -> {
            if (key == null || !key.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
                throw new ApiError(400, "创建请求标识无效");
            job.creationKey = key;
            return draft(job, null, title, content, mediaIds, expected);
        }, callback);
    }
    public Job createAssist(String title, String content, List<String> tags, String goal, String prompt,
            String audience, Callback<CreationSuggestion> callback) {
        return submit((job, expected) -> {
            if (title.length() > 120 || content.length() > 5000 || prompt.length() > 200 || audience.length() > 64)
                throw new ApiError(400, "输入内容过长，请精简后重试");
            JsonObject draft = new JsonObject();
            draft.addProperty("title", title); draft.addProperty("content", content);
            draft.add("tags", gson.toJsonTree(tags));
            JsonObject input = new JsonObject(); input.add("draft", draft);
            input.addProperty("goal", goal); input.addProperty("prompt", prompt); input.addProperty("audience", audience);
            String raw = authenticated(job, "/api/agent/create-assist", "POST", input, expected, true);
            CreationSuggestion result = gson.fromJson(raw, CreationSuggestion.class);
            if (result == null || !result.valid()) throw new ApiError(502, "建议响应不完整，请重试");
            return result;
        }, callback);
    }

    /** Agent 智能搜索与多帖总结；契约见 docs/issues/21-java-agent-搜索接口.md。仅登录用户，游客 401。 */
    public Job agentSearch(String question, String sessionId, Callback<AgentSearchResult> callback) {
        return submit((job, expected) -> {
            if (question == null || question.trim().isEmpty() || question.trim().length() > 200)
                throw new ApiError(400, "问题需为 1–200 字");
            JsonObject input = new JsonObject();
            input.addProperty("question", question.trim());
            if (sessionId != null && !sessionId.isEmpty()) input.addProperty("session_id", sessionId);
            AgentSearchResult result = gson.fromJson(
                    authenticated(job, "/api/agent/search", "POST", input, expected, true),
                    AgentSearchResult.class);
            if (result == null || result.status_code != 0 || result.answer == null)
                throw new ApiError(502, "AI 总结响应不完整，请重试");
            return result;
        }, callback);
    }

    public Job updatePost(String id, String title, String content, List<String> mediaIds, Callback<PostItem> callback) {
        return submit((job, expected) -> draft(job, id, title, content, mediaIds, expected), callback);
    }
    private PostItem draft(Job job, String id, String title, String content, List<String> mediaIds, long expected) throws IOException {
        if (title == null || title.length() > 120 || content == null || content.length() > 5000)
            throw new ApiError(400, "标题最多 120 字，正文最多 5000 字");
        if (mediaIds != null) {
            if (mediaIds.size() > 9) throw new ApiError(400, "最多 9 张图片");
            for (String mid : mediaIds)
                if (mid == null || !mid.matches("[A-Za-z0-9-]{1,36}")) throw new ApiError(400, "图片 ID 无效");
        }
        JsonObject input = new JsonObject();
        input.addProperty("title", title);
        input.addProperty("content", content);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        if (mediaIds != null) for (String mid : mediaIds) arr.add(mid);
        input.add("mediaIds", arr);
        String path = id == null ? "/api/posts" : "/api/posts/" + id;
        String raw = authenticated(job, path, id == null ? "POST" : "PUT", input, expected, true);
        PostItem post = gson.fromJson(raw, PostItem.class);
        if (post == null || post.getPostId() == null || post.getPostId().isEmpty())
            throw new ApiError(0, "作品响应不完整");
        return post;
    }
    public Job publishPost(String id, Callback<PostStatus> callback) {
        return submit((job, expected) -> statusAction(job, id, "publish", expected), callback);
    }
    public Job withdrawPost(String id, Callback<PostStatus> callback) {
        return submit((job, expected) -> statusAction(job, id, "withdraw", expected), callback);
    }
    private PostStatus statusAction(Job job, String id, String action, long expected) throws IOException {
        validateId(id);
        PostStatus status = gson.fromJson(
                authenticated(job, "/api/posts/" + id + "/" + action, "POST", new JsonObject(), expected, true),
                PostStatus.class);
        if (status == null || status.getStatus() == null || status.getStatus().isEmpty())
            throw new ApiError(0, "作品状态响应不完整");
        return status;
    }
    public Job getProfileHistory(int count, String beforeVersion, Callback<ProfileHistoryPage> callback) {
        return submit((job, expected) -> {
            if (count < 1 || count > 50 || (beforeVersion != null && !beforeVersion.matches("[1-9][0-9]{0,18}")))
                throw new ApiError(400, "分页参数无效");
            String path = "/api/users/me/profile/history?count=" + count
                    + (beforeVersion == null ? "" : "&before_version=" + beforeVersion);
            ProfileHistoryPage page = gson.fromJson(
                    authenticated(job, path, "GET", null, expected, true), ProfileHistoryPage.class);
            if (page == null || page.getItems() == null)
                throw new ApiError(0, "历史画像响应不完整");
            return page;
        }, callback);
    }
    public Job getPreferences(Callback<PreferenceSnapshot> callback) {
        return submit((job, expected) -> preferences(
                authenticated(job, "/api/recommendations/me/preferences", "GET", null, expected, true)), callback);
    }
    public Job setPersonalized(boolean personalized, Callback<PreferenceSnapshot> callback) {
        return submit((job, expected) -> {
            JsonObject input = new JsonObject(); input.addProperty("personalized", personalized);
            return preferences(authenticated(job, "/api/recommendations/me/preferences", "PUT", input, expected, true));
        }, callback);
    }
    public Job setBlockedAuthor(String authorId, boolean blocked, Callback<PreferenceSnapshot> callback) {
        return submit((job, expected) -> {
            if (authorId == null || !authorId.matches("[A-Za-z0-9_-]{1,36}"))
                throw new ApiError(400, "作者 ID 无效");
            JsonObject input = new JsonObject(); input.addProperty("blocked", blocked);
            return preferences(authenticated(job, "/api/recommendations/me/blocked-authors/" + authorId,
                    "PUT", input, expected, true));
        }, callback);
    }
    public Job setSuppressedTopic(String topic, boolean suppressed, Callback<PreferenceSnapshot> callback) {
        return submit((job, expected) -> {
            if (topic == null || topic.trim().isEmpty() || topic.trim().length() > 64)
                throw new ApiError(400, "标签无效");
            JsonObject input = new JsonObject(); input.addProperty("suppressed", suppressed);
            return preferences(authenticated(job, "/api/users/me/profile/topics/" + topic.trim(),
                    "PUT", input, expected, true));
        }, callback);
    }
    private PreferenceSnapshot preferences(String raw) throws IOException {
        PreferenceSnapshot result = gson.fromJson(raw, PreferenceSnapshot.class);
        if (result == null || result.getUserId() == null || result.getUserId().isEmpty())
            throw new ApiError(0, "偏好响应不完整");
        return result;
    }
    public Job getMyPosts(int count, String cursor, Callback<MyPostsPage> callback) {
        return submit((job, expected) -> {
            if (count < 1 || count > 50 || (cursor != null && !cursor.matches("[1-9][0-9]{0,18}")))
                throw new ApiError(400, "分页参数无效");
            String path = "/api/posts?count=" + count + (cursor == null ? "" : "&cursor=" + cursor);
            long boundary;
            try { boundary = cursor == null ? Long.MAX_VALUE : Long.parseLong(cursor); }
            catch (NumberFormatException invalid) { throw new ApiError(400, "分页参数无效"); }
            PostItem[] rows = gson.fromJson(authenticated(job, path, "GET", null, expected, true), PostItem[].class);
            if (rows == null || rows.length > count) throw new ApiError(0, "作品列表响应不完整");
            java.util.ArrayList<PostItem> posts = new java.util.ArrayList<>();
            for (PostItem post : rows) {
                if (post == null || post.getPostId() == null || !post.getPostId().matches("[1-9][0-9]{0,18}")
                        || post.getStatus() == null || !post.getStatus().matches("DRAFT|PENDING|PUBLISHED|REJECTED|WITHDRAWN"))
                    throw new ApiError(0, "作品列表响应不完整");
                long id;
                try { id = Long.parseLong(post.getPostId()); }
                catch (NumberFormatException invalid) { throw new ApiError(0, "作品 ID 无效"); }
                // Reject repeated boundaries or unordered pages instead of looping or duplicating rows.
                if (id >= boundary) throw new ApiError(0, "作品分页顺序异常，请刷新重试");
                boundary = id;
                posts.add(post);
            }
            return new MyPostsPage(posts);
        }, callback);
    }
    private void validateId(String id) throws IOException {
        if (id == null || !id.matches("[1-9][0-9]{0,17}")) throw new ApiError(400, "作品 ID 无效");
    }
    /** Best-effort telemetry, capped at one pending job; never blocks the UI or reports fake success. */
    public void trackEvent(String id, String type, Long durationMs, String scene) {
        if (!isLoggedIn() || !pendingEvents.compareAndSet(0, 1)) return;
        JsonObject event = new JsonObject();
        event.addProperty("eventId", java.util.UUID.randomUUID().toString());
        event.addProperty("postId", id);
        event.addProperty("type", type);
        event.addProperty("occurredAt", System.currentTimeMillis() / 1000);
        event.addProperty("scene", scene);
        if (durationMs != null) event.addProperty("durationMs", durationMs);
        submit((job, expected) -> {
            validateId(id);
            if (!type.matches("EXPOSURE|CLICK|DWELL")) throw new ApiError(400, "不支持的遥测类型");
            // Telemetry does not rotate credentials; business requests own refresh recovery.
            String token = access(expected);
            if (token == null) throw new ApiError(401, "会话已失效");
            request(job, "/api/events", "POST", event, token);
            return null;
        }, new Callback<Object>() {
            public void onSuccess(Object ignored) { pendingEvents.set(0); }
            public void onFailure(String ignored) { pendingEvents.set(0); }
        });
    }
    @Override public void close() { queue.shutdownNow(); http.dispatcher().cancelAll(); http.connectionPool().evictAll(); }
}
