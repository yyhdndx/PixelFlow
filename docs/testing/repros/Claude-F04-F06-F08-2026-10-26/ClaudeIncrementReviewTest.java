package com.PixelFlow.app.API;

import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import androidx.lifecycle.Lifecycle;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.ButtonFragment.HomeFragment;
import com.PixelFlow.app.ButtonFragment.ProfileFragment;
import com.PixelFlow.app.Streams.WaterfallAdapter;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.tabs.TabLayout;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Independent acceptance cases. Written only in the frozen review worktree. */
@RunWith(AndroidJUnit4.class)
public class ClaudeIncrementReviewTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<MainActivity> screen;
    private final CountDownLatch releaseWorker = new CountDownLatch(1);
    private final CountDownLatch releaseFeed = new CountDownLatch(1);
    private final CountDownLatch feedEntered = new CountDownLatch(1);
    private volatile boolean holdFeed;
    private final AtomicInteger uploads = new AtomicInteger(), sets = new AtomicInteger(), deletes = new AtomicInteger(), recommendations = new AtomicInteger();
    private static final String FEED = "{\"status_code\":0,\"has_more\":0,\"next_cursor\":null,\"post_list\":[{\"post_id\":\"30\",\"title\":\"Community item\",\"content\":\"ordinary feed\",\"status\":\"PUBLISHED\",\"author\":{\"user_id\":\"alice\",\"nickname\":\"Alice\",\"avatar\":\"\"},\"clips\":[],\"create_time\":1,\"is_liked\":false,\"like_count\":0}]}";
    private MockResponse ok(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest r) throws InterruptedException {
                String path = r.getRequestUrl().encodedPath();
                if (path.startsWith("/feed/")) {
                    if (holdFeed) { holdFeed = false; feedEntered.countDown(); releaseFeed.await(15, TimeUnit.SECONDS); }
                    return ok(FEED);
                }
                if (path.equals("/api/users/me")) return ok("{\"id\":\"alice\",\"username\":\"alice\",\"nickname\":\"Alice\",\"bio\":\"hello\",\"avatar\":\"\"}");
                if (path.equals("/api/users/me/stats")) return ok("{\"user_id\":\"alice\",\"post_count\":0,\"follower_count\":0,\"following_count\":0}");
                if (path.equals("/api/media")) {
                    uploads.incrementAndGet();
                    return ok("{\"mediaId\":\"11111111-1111-1111-1111-111111111111\",\"url\":\"" + server.url("/image") + "\",\"contentType\":\"image/jpeg\",\"type\":0,\"width\":10,\"height\":10,\"size\":100}");
                }
                if (path.equals("/api/users/me/avatar")) {
                    if ("DELETE".equals(r.getMethod())) deletes.incrementAndGet(); else sets.incrementAndGet();
                    return ok("{\"user_id\":\"alice\",\"avatar\":\"\"}");
                }
                if (path.equals("/api/recommendations/me/feed")) { recommendations.incrementAndGet(); return ok("{}").setResponseCode(503); }
                if (path.equals("/api/events")) return ok("{}");
                return ok("{}").setResponseCode(404);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); api.restore("alice", "refresh", "alice"); singleton.set(null, api);
    }
    @After public void teardown() throws Exception {
        releaseWorker.countDown(); releaseFeed.countDown();
        if (screen != null) screen.close();
        api.close(); singleton.set(null, previous); server.shutdown();
    }
    private Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private <T extends Fragment> T fragment(MainActivity a, Class<T> type) {
        for (Fragment f : a.getSupportFragmentManager().getFragments()) if (type.isInstance(f) && f.isAdded()) return type.cast(f);
        throw new AssertionError("Fragment not ready: " + type);
    }
    private void await(Consumer<MainActivity> assertion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (true) try { screen.onActivity(assertion::accept); return; }
        catch (AssertionError e) { if (System.nanoTime() > deadline) throw e; android.os.SystemClock.sleep(80); }
    }
    private void launchProfile() {
        screen = ActivityScenario.launch(MainActivity.class);
        screen.onActivity(a -> ((BottomNavigationView) a.findViewById(R.id.bottom_navigation)).setSelectedItemId(R.id.nav_profile));
        await(a -> {
            ProfileFragment f = fragment(a, ProfileFragment.class);
            assertEquals(View.GONE, f.requireView().findViewById(R.id.profile_loading).getVisibility());
            assertTrue(f.requireView().findViewById(R.id.btn_edit_profile).isEnabled());
        });
    }
    private Uri image() throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "review-avatar.png");
        Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle(); return Uri.fromFile(file);
    }
    @Test public void avatarWorksWhileProfileRemainsResumed_control() throws Exception {
        launchProfile(); Uri uri = image();
        screen.onActivity(a -> invoke(fragment(a, ProfileFragment.class), "onAvatarPicked", new Class<?>[]{Uri.class}, uri));
        await(a -> assertEquals("control must complete upload and avatar write", 1, sets.get()));
        assertEquals(1, uploads.get());
    }
    @Test public void pickerResultBeforeOnResumeMustStillUpload() throws Exception {
        launchProfile(); Uri uri = image();
        screen.moveToState(Lifecycle.State.STARTED);
        CountDownLatch workerDone = new CountDownLatch(1);
        // ActivityResultRegistry delivers pending results at ON_START, before ON_RESUME.
        screen.onActivity(a -> {
            ProfileFragment f = fragment(a, ProfileFragment.class);
            ExecutorService executor = (ExecutorService) field(f, "avatarExecutor");
            executor.execute(() -> { try { releaseWorker.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            invoke(f, "onAvatarPicked", new Class<?>[]{Uri.class}, uri);
            executor.execute(workerDone::countDown);
        });
        screen.moveToState(Lifecycle.State.RESUMED);
        releaseWorker.countDown(); assertTrue(workerDone.await(10, TimeUnit.SECONDS));
        await(a -> assertEquals("picker return must survive onResume profile refresh", 1, sets.get()));
    }
    @Test public void confirmedClearMustSurviveLeavingPage() throws Exception {
        launchProfile(); holdFeed = true;
        api.getFeed(12, null, new CommunityClient.Callback<ApiResponse>() {
            public void onSuccess(ApiResponse v) {} public void onFailure(String m) {}
        });
        assertTrue(feedEntered.await(10, TimeUnit.SECONDS));
        screen.onActivity(a -> invoke(fragment(a, ProfileFragment.class), "clearAvatar", new Class<?>[]{}));
        screen.moveToState(Lifecycle.State.CREATED);
        releaseFeed.countDown();
        CountDownLatch drained = new CountDownLatch(1);
        api.getFeed(12, null, new CommunityClient.Callback<ApiResponse>() {
            public void onSuccess(ApiResponse v) { drained.countDown(); } public void onFailure(String m) { drained.countDown(); }
        });
        assertTrue(drained.await(10, TimeUnit.SECONDS));
        assertEquals("confirmed avatar clear must reach server after navigation", 1, deletes.get());
    }
    @Test public void failedRecommendationMustNotDisplayCommunityItems() {
        screen = ActivityScenario.launch(MainActivity.class);
        await(a -> {
            HomeFragment f = fragment(a, HomeFragment.class);
            assertFalse((Boolean) field(f, "requestInFlight"));
            assertEquals(1, ((WaterfallAdapter) field(f, "adapter")).getData().size());
        });
        screen.onActivity(a -> ((TabLayout) a.findViewById(R.id.tab_layout)).getTabAt(4).select());
        await(a -> { assertEquals(1, recommendations.get()); assertFalse((Boolean) field(fragment(a, HomeFragment.class), "requestInFlight")); });
        screen.onActivity(a -> assertEquals("failed recommendation must not reuse another tab's rows", 0,
                ((WaterfallAdapter) field(fragment(a, HomeFragment.class), "adapter")).getData().size()));
    }
}
