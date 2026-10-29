package com.PixelFlow.app.API;

import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.ButtonFragment.HomeFragment;
import com.PixelFlow.app.Streams.WaterfallAdapter;
import com.google.android.material.tabs.TabLayout;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** User-visible loading/retry behavior, pagination cursors and session/lifecycle races. */
@RunWith(AndroidJUnit4.class)
public class HomeFeedFlowTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<MainActivity> screen;
    private final AtomicInteger firstPages = new AtomicInteger(), nextPages = new AtomicInteger(), recommendations = new AtomicInteger();
    private final List<String> cursors = new CopyOnWriteArrayList<>();
    private final List<String> feedAccounts = new CopyOnWriteArrayList<>();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private volatile boolean failFirst, failNext, empty, holdFirst, missingRecommendations;

    private MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private String posts(boolean bob, boolean next) {
        StringBuilder value = new StringBuilder("[");
        int size = next ? 2 : 12;
        for (int i = 0; i < size; i++) {
            if (i > 0) value.append(',');
            int id = (bob ? 200 : 100) - (next ? 12 : 0) - i;
            value.append("{\"post_id\":\"").append(id).append("\",\"title\":\"").append(bob ? "Bob " : "Alice ")
                    .append(id).append("\",\"content\":\"A community moment\",\"status\":\"PUBLISHED\",\"author\":{\"user_id\":\"author\",\"nickname\":\"PixelFlow\",\"avatar\":\"\"},\"clips\":[],\"create_time\":1,\"is_liked\":")
                    .append(!bob).append(",\"is_favorited\":false,\"like_count\":0}");
        }
        return value.append(']').toString();
    }
    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getRequestUrl().encodedPath();
                boolean bob = "Bearer bob".equals(request.getHeader("Authorization"));
                if (path.equals("/feed/")) {
                    feedAccounts.add(bob ? "bob" : "alice-or-guest");
                    String cursor = request.getRequestUrl().queryParameter("cursor");
                    if (cursor == null) {
                        firstPages.incrementAndGet();
                        if (holdFirst) { entered.countDown(); release.await(30, TimeUnit.SECONDS); }
                        if (failFirst) return json("{\"message\":\"First page offline\"}").setResponseCode(503);
                    } else {
                        nextPages.incrementAndGet(); cursors.add(cursor);
                        if (failNext) return json("{\"message\":\"Next page offline\"}").setResponseCode(503);
                    }
                    return json("{\"status_code\":0,\"has_more\":" + (empty || cursor != null ? 0 : 1)
                            + ",\"next_cursor\":" + (empty || cursor != null ? "null" : (bob ? "\"189\"" : "\"89\""))
                            + ",\"post_list\":" + (empty ? "[]" : posts(bob, cursor != null)) + "}");
                }
                if (path.equals("/api/recommendations/me/feed")) {
                    recommendations.incrementAndGet();
                    if (missingRecommendations) return json("{}").setResponseCode(404);
                    return json("{\"strategy\":\"popular\",\"reason\":\"cold_start\",\"profile_version\":0,\"preferences_changed\":false,\"post_list\":" + posts(bob, true) + "}");
                }
                if (path.equals("/api/events") || path.equals("/api/auth/logout")) return json("{}");
                return json("{}").setResponseCode(404);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); api.restore("alice", "refresh", "alice"); singleton.set(null, api);
    }
    @After public void teardown() throws Exception {
        release.countDown();
        if (screen != null) screen.close();
        api.close(); singleton.set(null, previous); server.shutdown();
    }
    private HomeFragment home(MainActivity a) {
        for (Fragment f : a.getSupportFragmentManager().getFragments()) if (f instanceof HomeFragment && f.isAdded()) return (HomeFragment) f;
        throw new AssertionError("Home fragment unavailable");
    }
    private Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private WaterfallAdapter adapter(MainActivity a) { return (WaterfallAdapter) field(home(a), "adapter"); }
    private void load(MainActivity a, boolean refresh) {
        try { Method m = HomeFragment.class.getDeclaredMethod("loadData", boolean.class); m.setAccessible(true); m.invoke(home(a), refresh); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void await(Consumer<MainActivity> assertion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) try { screen.onActivity(assertion::accept); return; }
        catch (AssertionError e) { if (System.nanoTime() > deadline) throw e; android.os.SystemClock.sleep(80); }
    }
    private void ready(int count) {
        await(a -> {
            assertFalse((Boolean) field(home(a), "requestInFlight"));
            assertEquals(count, adapter(a).getData().size());
        });
    }
    private void launch() { screen = ActivityScenario.launch(MainActivity.class); }
    private void clickRetry() { screen.onActivity(a -> a.findViewById(R.id.feed_page_retry).performClick()); }

    @Test public void firstPageFailureHasExplicitRetryAndOnlyTwoActualSources() {
        failFirst = true; launch();
        await(a -> {
            assertEquals(View.VISIBLE, a.findViewById(R.id.empty_state_layout).getVisibility());
            assertEquals(a.getString(R.string.home_error_title), ((TextView)a.findViewById(R.id.feed_state_title)).getText().toString());
            assertEquals(2, ((TabLayout)a.findViewById(R.id.tab_layout)).getTabCount());
        });
        failFirst = false; screen.onActivity(a -> a.findViewById(R.id.retry_button).performClick()); ready(12);
        assertEquals(0, nextPages.get());
    }
    @Test public void failedPaginationRetainsCardsAndRetriesTheSameCursorOnlyOnRequest() {
        launch(); ready(12); failNext = true;
        screen.onActivity(a -> {
            assertEquals(View.GONE, a.findViewById(R.id.feed_page_status).getVisibility());
            androidx.recyclerview.widget.RecyclerView list = a.findViewById(R.id.recycler_view);
            list.scrollBy(0, list.getHeight() * 4);
        });
        await(a -> assertTrue((Boolean) field(home(a), "paginationPaused")));
        screen.onActivity(a -> {
            assertEquals(12, adapter(a).getData().size());
            assertEquals(View.VISIBLE, a.findViewById(R.id.feed_page_retry).getVisibility());
            for (int i = 0; i < 4; i++) load(a, false);
        });
        assertEquals(1, nextPages.get());
        failNext = false; clickRetry(); ready(14);
        assertEquals(java.util.Arrays.asList("89", "89"), cursors);
        screen.onActivity(a -> {
            assertEquals(View.GONE, a.findViewById(R.id.feed_page_retry).getVisibility());
            assertEquals(14, adapter(a).getData().stream().map(p -> p.getId()).distinct().count());
        });
    }
    @Test public void failedRefreshRetainsCardsAndRetryStartsAtFirstPage() {
        launch(); ready(12); failFirst = true; screen.onActivity(a -> load(a, true));
        await(a -> assertTrue((Boolean) field(home(a), "paginationPaused")));
        screen.onActivity(a -> assertEquals(12, adapter(a).getData().size()));
        failFirst = false; clickRetry(); ready(12);
        assertEquals(0, nextPages.get()); assertTrue(firstPages.get() >= 3);
    }
    @Test public void stoppedRequestResumesWithoutStuckSpinner() throws Exception {
        holdFirst = true; launch(); assertTrue(entered.await(10, TimeUnit.SECONDS));
        screen.moveToState(Lifecycle.State.CREATED); release.countDown(); screen.moveToState(Lifecycle.State.RESUMED);
        ready(12); assertTrue(firstPages.get() >= 2);
    }
    @Test public void changedAccountDuringRequestReloadsWithoutOldViewerState() throws Exception {
        launch(); ready(12); holdFirst = true;
        screen.onActivity(a -> {
            load(a, true);
            assertTrue("Switch while the visible Home page is loading", (Boolean) field(home(a), "requestInFlight"));
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        // Session methods are synchronized. Do not wait for UI idleness while the refresh
        // spinner runs: ActivityScenario.onActivity can otherwise outwait the HTTP timeout.
        api.logout(new CommunityClient.Callback<Void>() { public void onSuccess(Void v) {} public void onFailure(String m) {} });
        api.restore("bob", "bob-refresh", "bob");
        release.countDown();
        await(a -> {
            assertFalse((Boolean) field(home(a), "requestInFlight"));
            assertEquals("Requests=" + feedAccounts + "; current=" + api.username()
                    + "; displayed=" + field(home(a), "displayedAccount") + "; message="
                    + ((TextView)a.findViewById(R.id.feed_state_message)).getText(), 12, adapter(a).getData().size());
            assertEquals("Requests=" + feedAccounts + "; current=" + api.username()
                    + "; displayed=" + field(home(a), "displayedAccount"), "200", adapter(a).getData().get(0).getId());
            assertFalse(adapter(a).getData().get(0).isLiked());
        });
    }
    @Test public void recreationPreservesRecommendationSource() {
        launch(); ready(12);
        screen.onActivity(a -> ((TabLayout)a.findViewById(R.id.tab_layout)).getTabAt(1).select()); ready(2);
        int ordinary = firstPages.get(); screen.recreate(); ready(2);
        screen.onActivity(a -> assertEquals(1, ((TabLayout)a.findViewById(R.id.tab_layout)).getSelectedTabPosition()));
        assertEquals(ordinary, firstPages.get()); assertTrue(recommendations.get() >= 2);
    }
    @Test public void emptyCommunityIsDistinctFromFailure() {
        empty = true; launch();
        await(a -> {
            assertEquals(View.VISIBLE, a.findViewById(R.id.empty_state_layout).getVisibility());
            assertEquals(a.getString(R.string.home_empty_title), ((TextView)a.findViewById(R.id.feed_state_title)).getText().toString());
            assertEquals(a.getString(R.string.home_refresh), ((TextView)a.findViewById(R.id.retry_button)).getText().toString());
        });
    }
    @Test public void unavailableRecommendationExplainsItAndCommunityStillWorks() {
        missingRecommendations = true; launch(); ready(12);
        screen.onActivity(a -> ((TabLayout)a.findViewById(R.id.tab_layout)).getTabAt(1).select()); ready(0);
        screen.onActivity(a -> {
            assertEquals(a.getString(R.string.home_recommendations_unavailable), ((TextView)a.findViewById(R.id.feed_state_message)).getText().toString());
            ((TabLayout)a.findViewById(R.id.tab_layout)).getTabAt(0).select();
        });
        ready(12);
    }
}
