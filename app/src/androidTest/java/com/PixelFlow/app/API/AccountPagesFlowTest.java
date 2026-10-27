package com.PixelFlow.app.API;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.*;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Device UI and HTTP lifecycle regressions. All traffic stays on the device's mock server. */
@RunWith(AndroidJUnit4.class)
public class AccountPagesFlowTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<Activity> scenario;
    private volatile Gate readGate, writeGate;
    private volatile boolean updated, followed, removed, personalized = true, failWrite, failRead;
    private volatile String postStatus = "DRAFT";
    private final AtomicInteger writes = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> reads = new ConcurrentHashMap<>();

    private static class Screen {
        final Class<? extends Activity> type;
        final String path;
        final int list, refresh, loading, retry, action;
        Screen(Class<? extends Activity> type, String path, int list, int refresh, int loading, int retry, int action) {
            this.type = type; this.path = path; this.list = list; this.refresh = refresh;
            this.loading = loading; this.retry = retry; this.action = action;
        }
    }
    private static final Screen WORKS = new Screen(MyWorksActivity.class, "/api/posts", R.id.my_works_list,
            R.id.my_works_refresh, R.id.my_works_loading, R.id.my_works_retry, R.id.my_work_action);
    private static final Screen FAVORITES = new Screen(FavoritesActivity.class, "/api/users/me/favorites", R.id.favorites_list,
            R.id.favorites_refresh, R.id.favorites_loading, R.id.favorites_retry, R.id.favorite_remove);
    private static final Screen CONNECTIONS = new Screen(ConnectionsActivity.class, "/api/users/me/followers", R.id.connections_list,
            R.id.connections_refresh, R.id.connections_loading, R.id.connections_retry, R.id.connection_follow);
    private static final Screen HISTORY = new Screen(HistoryProfileActivity.class, "/api/users/me/profile/history", R.id.history_list,
            R.id.history_refresh, R.id.history_loading, R.id.history_retry, 0);
    private static final Screen USER = new Screen(UserProfileActivity.class, "/api/users/author/profile", 0,
            0, R.id.user_loading, R.id.user_retry, R.id.user_follow);
    private static final Screen PREFS = new Screen(PreferencesActivity.class, "/api/recommendations/me/preferences", 0,
            0, R.id.pref_loading, R.id.pref_retry, R.id.pref_switch);
    private static final Screen[] ALL = {WORKS, FAVORITES, CONNECTIONS, HISTORY, USER, PREFS};
    private static final Screen[] WRITABLE = {WORKS, FAVORITES, CONNECTIONS, USER, PREFS};

    private static class Gate {
        final String path;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Gate(String path) { this.path = path; }
        void hold() throws InterruptedException {
            entered.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("Gate timed out: " + path);
        }
        void awaitEntry() throws InterruptedException { assertTrue(path, entered.await(10, TimeUnit.SECONDS)); }
    }

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/api/auth/logout")) return ok("{}");
                boolean bob = "Bearer bob".equals(request.getHeader("Authorization"));
                if ("GET".equals(request.getMethod())) {
                    reads.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
                    String body = body(path, request.getRequestUrl().queryParameter("cursor"), bob);
                    Gate gate = readGate;
                    if (gate != null && gate.path.equals(path)) { readGate = null; gate.hold(); }
                    return failRead ? ok("{}").setResponseCode(503) : ok(body);
                }
                writes.incrementAndGet();
                JsonObject input = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                if (path.equals("/api/interactions/follows")) followed = input.get("active").getAsBoolean();
                if (path.equals("/api/interactions/favorites")) removed = !input.get("active").getAsBoolean();
                if (path.endsWith("/publish")) postStatus = "PENDING";
                if (path.endsWith("/withdraw")) postStatus = "WITHDRAWN";
                if (path.equals(PREFS.path)) personalized = input.get("personalized").getAsBoolean();
                Gate gate = writeGate;
                if (gate != null) { writeGate = null; gate.hold(); }
                if (failWrite) return ok("{\"message\":\"结果暂时无法确认\"}").setResponseCode(503);
                if (path.equals(PREFS.path)) return ok(preferences(bob));
                return ok("{\"postId\":\"30\",\"status\":\"" + postStatus + "\",\"active\":true}");
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); singleton.set(null, api);
        api.restore("alice", "refresh-alice", "alice");
    }

    private MockResponse ok(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body);
    }
    private String label(boolean bob) { return bob ? "Bob" : updated ? "Updated" : "Alice"; }
    private String post(boolean bob) {
        return "{\"post_id\":\"30\",\"title\":\"" + label(bob) + "\",\"status\":\"" + postStatus
                + "\",\"clips\":[],\"media_ids\":[],\"author\":{\"nickname\":\"Author\"}}";
    }
    private String preferences(boolean bob) {
        return "{\"user_id\":\"" + (bob ? "bob" : "alice") + "\",\"version\":1,\"personalized\":" + (!bob && personalized)
                + ",\"suppressed_topics\":[\"" + label(bob) + "\"],\"blocked_author_ids\":[],\"excluded_post_ids\":[]}";
    }
    private String body(String path, String cursor, boolean bob) {
        if (path.equals("/api/users/author/posts")) return "{\"status_code\":0,\"has_more\":0,\"next_cursor\":null,\"post_list\":[]}";
        if (path.equals(WORKS.path)) return cursor == null ? "[" + post(bob) + "]" : "[]";
        if (path.equals(FAVORITES.path)) return "{\"status_code\":0,\"has_more\":0,\"post_list\":[" + (removed ? "" : post(bob)) + "]}";
        if (path.equals(CONNECTIONS.path)) return "{\"has_more\":0,\"user_list\":[{\"user_id\":\"author\",\"nickname\":\""
                + label(bob) + "\",\"avatar\":\"\",\"bio\":\"\",\"is_following\":" + (!bob && followed) + "}]}";
        if (path.equals(HISTORY.path)) return "{\"user_id\":\"" + (bob ? "bob" : "alice")
                + "\",\"has_more\":false,\"items\":[{\"version\":1,\"topics\":[],\"explanation\":\"" + label(bob) + "\"}]}";
        if (path.equals(USER.path)) return "{\"user_id\":\"author\",\"nickname\":\"" + label(bob)
                + "\",\"bio\":\"\",\"avatar\":\"\",\"post_count\":0,\"follower_count\":0,\"following_count\":0,\"is_following\":"
                + (!bob && followed) + "}";
        if (path.equals(PREFS.path)) return preferences(bob);
        return "{}";
    }

    @After public void tearDown() throws Exception {
        if (readGate != null) readGate.release.countDown();
        if (writeGate != null) writeGate.release.countDown();
        if (scenario != null) scenario.close();
        if (api != null) api.close();
        if (singleton != null) singleton.set(null, previous);
        if (server != null) server.shutdown();
    }

    private void launch(Screen screen) {
        if (scenario != null) scenario.close();
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), screen.type);
        intent.putExtra(UserProfileActivity.EXTRA_USER_ID, "author");
        scenario = ActivityScenario.launch(intent);
    }
    private void await(Consumer<Activity> assertion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { scenario.onActivity(assertion::accept); return; }
            catch (AssertionError e) {
                if (System.nanoTime() > deadline) throw e;
                android.os.SystemClock.sleep(100);
            }
        }
    }
    private void ready(Screen screen) {
        await(a -> {
            assertEquals(screen.path, View.GONE, a.findViewById(screen.loading).getVisibility());
            assertEquals(View.GONE, a.findViewById(screen.retry).getVisibility());
            if (screen.refresh != 0) assertFalse(((SwipeRefreshLayout)a.findViewById(screen.refresh)).isRefreshing());
            if (screen.list != 0) assertEquals(removed && screen == FAVORITES ? 0 : 1,
                    ((RecyclerView)a.findViewById(screen.list)).getAdapter().getItemCount());
            if (screen.action != 0 && !(removed && screen == FAVORITES)) {
                View action = a.findViewById(screen.action);
                assertNotNull(action); assertTrue(action.isEnabled());
            }
        });
    }
    private boolean containsText(View view, String text) {
        // RecyclerView may retain hidden holders until layout; inspect what the user can see.
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup)view;
            for (int i = 0; i < parent.getChildCount(); i++) if (containsText(parent.getChildAt(i), text)) return true;
        }
        return false;
    }
    private void labelIs(String label) { await(a -> assertTrue(containsText(a.findViewById(android.R.id.content), label))); }
    private int readCount(Screen screen) { return reads.containsKey(screen.path) ? reads.get(screen.path).get() : 0; }
    private void assertPending(Screen screen) {
        scenario.onActivity(a -> {
            assertEquals(View.VISIBLE, a.findViewById(screen.loading).getVisibility());
            View action = screen.action == 0 ? null : a.findViewById(screen.action);
            if (action != null) assertFalse(action.isEnabled());
        });
    }
    private void resetServer() {
        updated = false; followed = false; removed = false; personalized = true;
        postStatus = "DRAFT"; failRead = false; failWrite = false;
    }
    private void committed(Screen screen) {
        ready(screen);
        await(a -> {
            if (screen == WORKS) assertTrue(containsText(a.findViewById(android.R.id.content), "待审核"));
            if (screen == USER || screen == CONNECTIONS)
                assertEquals(a.getString(R.string.connection_following), ((TextView)a.findViewById(screen.action)).getText().toString());
            if (screen == PREFS) assertFalse(((SwitchMaterial)a.findViewById(screen.action)).isChecked());
        });
    }

    @Test public void allSixScreensRecoverWhenStoppedDuringRead() throws Exception {
        for (Screen screen : ALL) {
            updated = false;
            Gate gate = new Gate(screen.path); readGate = gate;
            try {
                launch(screen); gate.awaitEntry();
                scenario.moveToState(Lifecycle.State.CREATED);
                scenario.onActivity(a -> {
                    if (screen.list != 0) assertEquals(0, ((RecyclerView)a.findViewById(screen.list)).getAdapter().getItemCount());
                    if (screen.refresh != 0) assertFalse(((SwipeRefreshLayout)a.findViewById(screen.refresh)).isRefreshing());
                });
                updated = true;
                scenario.moveToState(Lifecycle.State.RESUMED);
                gate.release.countDown();
                ready(screen); labelIs("Updated");
            } finally { gate.release.countDown(); }
        }
    }

    @Test public void allSixScreensRefreshAfterReturningFromAnotherPage() {
        for (Screen screen : ALL) {
            updated = false; launch(screen); ready(screen); labelIs("Alice");
            int before = readCount(screen);
            scenario.moveToState(Lifecycle.State.CREATED);
            updated = true;
            scenario.moveToState(Lifecycle.State.RESUMED);
            ready(screen); labelIs("Updated");
            assertTrue(readCount(screen) > before);
        }
    }

    @Test public void allSixScreensDiscardOldAccountRowsAndLateResponses() throws Exception {
        for (Screen screen : ALL) {
            api.restore("alice", "refresh-alice", "alice");
            launch(screen); ready(screen); labelIs("Alice");
            scenario.moveToState(Lifecycle.State.CREATED);
            Gate old = new Gate(screen.path); readGate = old;
            try {
                scenario.moveToState(Lifecycle.State.RESUMED); old.awaitEntry();
                scenario.moveToState(Lifecycle.State.CREATED);
                CompletableFuture<Void> loggedOut = new CompletableFuture<>();
                api.logout(new CommunityClient.Callback<Void>() {
                    public void onSuccess(Void ignored) { loggedOut.complete(null); }
                    public void onFailure(String error) { loggedOut.completeExceptionally(new AssertionError(error)); }
                });
                old.release.countDown(); loggedOut.get(10, TimeUnit.SECONDS);
                api.restore("bob", "refresh-bob", "bob");
                Gate current = new Gate(screen.path); readGate = current;
                try {
                    scenario.moveToState(Lifecycle.State.RESUMED); current.awaitEntry();
                    scenario.onActivity(a -> {
                        assertFalse(screen.path, containsText(a.findViewById(android.R.id.content), "Alice"));
                        if (screen.list != 0) {
                            RecyclerView list = a.findViewById(screen.list);
                            assertEquals(screen.path, 0, list.getAdapter().getItemCount());
                            assertEquals(View.GONE, list.getVisibility());
                        }
                    });
                    current.release.countDown(); ready(screen); labelIs("Bob");
                } finally { current.release.countDown(); }
            } finally { old.release.countDown(); }
        }
    }

    @Test public void allFiveWritesSurviveRecreationAndReconcileBeforeEnabling() throws Exception {
        for (Screen screen : WRITABLE) {
            resetServer(); launch(screen); ready(screen);
            Gate gate = new Gate("write"); writeGate = gate;
            try {
                int before = writes.get();
                scenario.onActivity(a -> { a.findViewById(screen.action).performClick(); a.findViewById(screen.action).performClick(); });
                gate.awaitEntry();
                scenario.recreate();
                assertPending(screen);
                gate.release.countDown();
                committed(screen);
                assertEquals(before + 1, writes.get());
            } finally { gate.release.countDown(); }
        }
    }

    @Test public void committedWritesWithErrorResponsesAreReadBackWithoutRetrying() throws Exception {
        for (Screen screen : WRITABLE) {
            resetServer(); launch(screen); ready(screen); failWrite = true;
            Gate gate = new Gate(screen.path); readGate = gate;
            try {
                int before = writes.get();
                scenario.onActivity(a -> a.findViewById(screen.action).performClick());
                gate.awaitEntry(); assertPending(screen);
                gate.release.countDown(); committed(screen);
                assertEquals(before + 1, writes.get());
            } finally { gate.release.countDown(); }
        }
    }

    @Test public void failedReconciliationKeepsFollowDisabledUntilRetryConfirmsState() {
        launch(USER); ready(USER); failWrite = true; failRead = true;
        scenario.onActivity(a -> a.findViewById(USER.action).performClick());
        await(a -> {
            assertEquals(View.VISIBLE, a.findViewById(USER.retry).getVisibility());
            assertFalse(a.findViewById(USER.action).isEnabled());
        });
        failRead = false; failWrite = false;
        scenario.onActivity(a -> a.findViewById(USER.retry).performClick());
        committed(USER); assertEquals(1, writes.get());
        scenario.onActivity(a -> a.findViewById(USER.action).performClick());
        await(a -> {
            assertTrue(a.findViewById(USER.action).isEnabled());
            assertEquals(a.getString(R.string.connection_follow), ((TextView)a.findViewById(USER.action)).getText().toString());
        });
        assertEquals(2, writes.get());
    }
}
