package com.PixelFlow.app.API;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.HistoryProfileActivity;
import com.PixelFlow.app.InterestProfileActivity;
import com.PixelFlow.app.R;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Current interest profile (topics/empty/failure/guest) and history profile (empty vs failure). */
@RunWith(AndroidJUnit4.class)
public class ProfileInsightsFlowTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<Activity> scenario;
    private volatile boolean emptyProfile, failProfile, failHistory;
    private final AtomicInteger profileReads = new AtomicInteger(), historyReads = new AtomicInteger();

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/api/users/me/profile")) {
                    profileReads.incrementAndGet();
                    if (failProfile) return ok("{}").setResponseCode(503);
                    return ok(emptyProfile ? "{\"version\":1,\"topics\":[]}"
                            : "{\"version\":1,\"topics\":[{\"name\":\"美食\",\"score\":3},{\"name\":\"摄影\",\"score\":2}]}");
                }
                if (path.equals("/api/users/me/profile/history")) {
                    historyReads.incrementAndGet();
                    if (failHistory) return ok("{}").setResponseCode(503);
                    return ok("{\"user_id\":\"alice\",\"has_more\":false,\"next_cursor\":null,\"items\":[]}");
                }
                return ok("{}").setResponseCode(404);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); singleton.set(null, api);
    }
    @After public void teardown() throws Exception {
        if (scenario != null) scenario.close();
        api.close(); singleton.set(null, previous); server.shutdown();
    }
    private MockResponse ok(String body) { return new MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body); }
    private void launch(Class<? extends Activity> type) {
        if (scenario != null) scenario.close();
        scenario = ActivityScenario.launch(new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), type));
    }
    private String str(int id) { return InstrumentationRegistry.getInstrumentation().getTargetContext().getString(id); }
    private void await(Runnable assertion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (true) try { assertion.run(); return; }
        catch (AssertionError e) { if (System.nanoTime() > deadline) throw e; android.os.SystemClock.sleep(80); }
    }
    private boolean hasText(View root, String text) {
        if (root.getVisibility() != View.VISIBLE) return false;
        if (root instanceof TextView && text.equals(((TextView) root).getText().toString())) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++)
                if (hasText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    @Test public void interestProfileShowsTopicsWhenAvailable() {
        api.restore("alice", "refresh", "alice");
        launch(InterestProfileActivity.class);
        await(() -> scenario.onActivity(a -> assertTrue(hasText(a.findViewById(android.R.id.content), "美食"))));
        assertEquals(1, profileReads.get());
    }
    @Test public void interestProfileEmptyShowsEmptyState() {
        emptyProfile = true;
        api.restore("alice", "refresh", "alice");
        launch(InterestProfileActivity.class);
        await(() -> scenario.onActivity(a -> assertTrue(hasText(a.findViewById(android.R.id.content), str(R.string.insights_empty)))));
        assertEquals(1, profileReads.get());
    }
    @Test public void interestProfileFailureShowsRetry() {
        failProfile = true;
        api.restore("alice", "refresh", "alice");
        launch(InterestProfileActivity.class);
        await(() -> scenario.onActivity(a -> assertEquals(View.VISIBLE, a.findViewById(R.id.insights_retry).getVisibility())));
    }
    @Test public void interestProfileNotLoggedInShowsPrompt() {
        launch(InterestProfileActivity.class);
        await(() -> scenario.onActivity(a -> assertTrue(hasText(a.findViewById(android.R.id.content), "请登录后查看兴趣画像"))));
        assertEquals(0, profileReads.get());
    }
    @Test public void historyEmptyShowsEmptyState() {
        api.restore("alice", "refresh", "alice");
        launch(HistoryProfileActivity.class);
        await(() -> scenario.onActivity(a -> assertEquals(View.VISIBLE, a.findViewById(R.id.history_empty).getVisibility())));
    }
    @Test public void historyFailureShowsRetry() {
        failHistory = true;
        api.restore("alice", "refresh", "alice");
        launch(HistoryProfileActivity.class);
        await(() -> scenario.onActivity(a -> assertEquals(View.VISIBLE, a.findViewById(R.id.history_retry).getVisibility())));
    }
}
