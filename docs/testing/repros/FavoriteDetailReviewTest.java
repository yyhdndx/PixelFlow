package com.PixelFlow.app.API;

import android.content.Intent;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.gson.Gson;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Acceptance regression: a user-confirmed write must survive leaving the detail screen. */
@RunWith(AndroidJUnit4.class)
public class FavoriteDetailReviewTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<WorkDetailActivity> screen;
    private volatile boolean favorite;
    private volatile CountDownLatch entered, release;
    private final AtomicInteger writes = new AtomicInteger();

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/api/interactions/favorites")) {
                    writes.incrementAndGet();
                    favorite = new Gson().fromJson(request.getBody().readUtf8(), com.google.gson.JsonObject.class).get("active").getAsBoolean();
                    return ok("{\"active\":" + favorite + "}");
                }
                if (path.equals("/api/posts/30")) return ok(post());
                if (path.equals("/api/users/author/profile")) return ok("{\"user_id\":\"author\",\"nickname\":\"Author\",\"bio\":\"\",\"avatar\":\"\",\"post_count\":1,\"follower_count\":0,\"following_count\":0,\"is_following\":false}");
                if (path.equals("/feed/")) {
                    if (entered != null) {
                        entered.countDown();
                        if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("queue gate timed out");
                    }
                    return ok("{\"status_code\":0,\"has_more\":0,\"post_list\":[]}");
                }
                if (path.equals("/api/events")) return ok("{\"duplicate\":false}");
                return ok("{}").setResponseCode(404);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); api.restore("alice", "refresh", "alice");
        singleton.set(null, api);
    }
    private MockResponse ok(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private String post() {
        return "{\"post_id\":\"30\",\"title\":\"Review\",\"content\":\"Review\",\"status\":\"PUBLISHED\",\"create_time\":1,\"clips\":[],\"author\":{\"user_id\":\"author\",\"nickname\":\"Author\",\"avatar\":\"\"},\"is_liked\":false,\"like_count\":0,\"is_favorited\":" + favorite + "}";
    }
    private void launch() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), WorkDetailActivity.class);
        intent.putExtra("work_item", new WorkItem(new Gson().fromJson(post(), PostItem.class)));
        screen = ActivityScenario.launch(intent);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                screen.onActivity(a -> assertTrue(((ImageView)a.findViewById(R.id.collect_button)).isEnabled()));
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > end) throw e;
                android.os.SystemClock.sleep(100);
            }
        }
    }
    private static class Result<T> implements CommunityClient.Callback<T> {
        final CompletableFuture<T> value = new CompletableFuture<>();
        public void onSuccess(T result) { value.complete(result); }
        public void onFailure(String error) { value.completeExceptionally(new AssertionError(error)); }
        T get() throws Exception { return value.get(10, TimeUnit.SECONDS); }
    }
    @After public void close() throws Exception {
        if (release != null) release.countDown();
        if (screen != null) screen.close();
        if (api != null) api.close();
        if (singleton != null) singleton.set(null, previous);
        if (server != null) server.shutdown();
    }

    @Test public void ordinaryFavoriteWriteReadsServerState() throws Exception {
        launch();
        screen.onActivity(a -> a.findViewById(R.id.collect_button).performClick());
        Result<PostItem> state = new Result<>(); api.getPost("30", state);
        assertTrue(state.get().isFavorited()); assertEquals(1, writes.get());
    }

    @Test public void leavingDetailMustNotDiscardQueuedFavoriteWrite() throws Exception {
        launch();
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        Result<ApiResponse> slowRead = new Result<>(); api.getFeed(1, null, slowRead);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        screen.onActivity(a -> a.findViewById(R.id.collect_button).performClick());
        screen.close(); screen = null;
        release.countDown(); slowRead.get();
        Result<PostItem> state = new Result<>(); api.getPost("30", state);
        PostItem actual = state.get();
        assertEquals("Leaving detail must keep the queued write", 1, writes.get());
        assertTrue(actual.isFavorited());
    }
}
