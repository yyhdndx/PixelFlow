package com.PixelFlow.app.API;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
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
    }
    public static final class Job {
        private volatile boolean cancelled;
        private volatile Call call;
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
    private synchronized String access(long expected) throws IOException { check(expected); return access; }
    private synchronized void install(JsonObject tokens, String name, long expected) throws IOException {
        check(expected);
        if (tokens == null || !tokens.has("accessToken") || !tokens.has("refreshToken"))
            throw new ApiError(0, "登录响应不完整，请重新登录");
        access = tokens.get("accessToken").getAsString();
        refresh = tokens.get("refreshToken").getAsString();
        username = name;
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
                delivery.execute(() -> { if (!job.cancelled) callback.onFailure(message); });
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
        Request.Builder builder = new Request.Builder().url(base.resolve(path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, method.equals("GET") ? null : RequestBody.create(gson.toJson(body), JSON));
        job.call = http.newCall(builder.build());
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
    private String authenticated(Job job, String path, String method, Object body, long expected,
            boolean required) throws IOException {
        String token = access(expected);
        if (required && token == null) throw new ApiError(401, "请先在“我的 → 账号与登录”中登录");
        try { return request(job, path, method, body, token); }
        catch (ApiError failure) {
            if (failure.status != 401 || token == null) throw failure;
        }
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
                if (generation == expected) { access = null; refresh = null; username = null; generation++; }
            }
            throw failure;
        }
        return request(job, path, method, body, access(expected));
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
    public Job getPost(String id, Callback<PostItem> callback) {
        return submit((job, expected) -> post(job, id, expected), callback);
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
