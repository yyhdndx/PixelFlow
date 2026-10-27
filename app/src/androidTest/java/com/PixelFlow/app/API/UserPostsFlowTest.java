package com.PixelFlow.app.API;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.R;
import com.PixelFlow.app.UserProfileActivity;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UserPostsFlowTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<UserProfileActivity> screen;
    private volatile boolean empty, disabled, failPage, liked, sample;
    private volatile Gate gate;
    private final List<Gate> gates = new CopyOnWriteArrayList<>();
    private final List<String> cursors = new CopyOnWriteArrayList<>();
    private final AtomicInteger profiles = new AtomicInteger();
    private class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Gate() { gates.add(this); }
        void hold() throws InterruptedException { entered.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS)); }
        void await() throws InterruptedException { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
    }
    private MockResponse ok(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private String post(String id, boolean bob) {
        String clips = sample ? "[{\"type\":0,\"url\":\"" + server.url("/cover/" + id) + "\",\"width\":400,\"height\":300}"
                + (id.equals("20") ? ",{\"type\":1,\"url\":\"" + server.url("/sample.mp4") + "\",\"width\":400,\"height\":300}" : "") + "]" : "[]";
        String title = sample ? (id.equals("30") ? "窗边的慢时光" : "把周末留给山野") : (bob ? "Bob " : "Alice ") + id;
        return "{\"post_id\":\"" + id + "\",\"title\":\"" + title + "\",\"content\":\"记录生活\",\"status\":\"PUBLISHED\","
                + "\"author\":{\"user_id\":\"author\",\"nickname\":\"林间\",\"avatar\":\"\"},\"create_time\":1,\"clips\":" + clips
                + ",\"is_liked\":" + (!bob && liked) + ",\"like_count\":" + (liked ? 7 : 6) + "}";
    }
    private String page(String rows, boolean more) {
        return "{\"status_code\":0,\"has_more\":" + (more ? 1 : 0) + ",\"next_cursor\":"
                + (more ? "\"20\"" : "null") + ",\"post_list\":[" + rows + "]}";
    }
    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getRequestUrl().encodedPath();
                boolean bob = "Bearer bob".equals(request.getHeader("Authorization"));
                if (path.startsWith("/cover/")) return cover(path.endsWith("30"));
                if (path.equals("/api/users/author/profile")) {
                    profiles.incrementAndGet();
                    if (disabled) return ok("{}").setResponseCode(404);
                    return ok("{\"user_id\":\"author\",\"nickname\":\"林间\",\"avatar\":\"\",\"bio\":\"认真生活，慢慢记录。\","
                            + "\"post_count\":3,\"follower_count\":128,\"following_count\":36,\"is_following\":false}");
                }
                if (path.equals("/api/users/author/posts")) {
                    String cursor = request.getRequestUrl().queryParameter("cursor");
                    cursors.add(cursor == null ? "first" : cursor);
                    String body = empty ? page("", false) : cursor == null
                            ? page(post("30", bob) + "," + post("20", bob), true) : page(post("9", bob), false);
                    Gate current = gate;
                    if (current != null) { gate = null; current.hold(); }
                    if (disabled) return ok("{}").setResponseCode(404);
                    if (failPage) return ok("{\"message\":\"暂时无法加载作品\"}").setResponseCode(503);
                    return ok(body);
                }
                if (path.equals("/api/posts/30")) return ok(post("30", bob));
                if (path.equals("/api/events") || path.equals("/api/auth/logout")) return ok("{}");
                return ok("{}").setResponseCode(404);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); api.restore("alice", "refresh-alice", "alice");
        singleton.set(null, api);
    }
    private MockResponse cover(boolean warm) {
        Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Color.parseColor(warm ? "#E8D8C7" : "#C7DBD5"));
        paint.setColor(Color.parseColor(warm ? "#B58563" : "#7F9E8D"));
        canvas.drawCircle(warm ? 200 : 280, 190, warm ? 92 : 155, paint);
        paint.setColor(Color.parseColor(warm ? "#F8EEE3" : "#E5EADD"));
        canvas.drawCircle(warm ? 200 : 90, warm ? 160 : 240, warm ? 55 : 135, paint);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle();
        return new MockResponse().setHeader("Content-Type", "image/png").setBody(new okio.Buffer().write(bytes.toByteArray()));
    }
    @After public void close() throws Exception {
        for (Gate held : gates) held.release.countDown();
        if (screen != null) screen.close();
        api.close(); singleton.set(null, previous); server.shutdown();
    }
    private void launch() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), UserProfileActivity.class);
        intent.putExtra(UserProfileActivity.EXTRA_USER_ID, "author"); screen = ActivityScenario.launch(intent);
    }
    private void await(Consumer<UserProfileActivity> assertion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (true) {
            try { screen.onActivity(assertion::accept); return; }
            catch (AssertionError failure) { if (System.nanoTime() > deadline) throw failure; android.os.SystemClock.sleep(80); }
        }
    }
    private RecyclerView list(UserProfileActivity activity) { return activity.findViewById(R.id.user_works); }
    private void ready(int count) {
        await(a -> {
            assertEquals(count, list(a).getAdapter().getItemCount());
            assertEquals(View.GONE, a.findViewById(R.id.user_works_loading).getVisibility());
            assertTrue(a.findViewById(R.id.user_follow).isEnabled());
        });
    }
    private void click(int id) { screen.onActivity(a -> a.findViewById(id).performClick()); }
    @Test public void pagesAppendAndFailedPageRetriesSameCursor() {
        launch(); ready(2); failPage = true; click(R.id.user_works_more);
        await(a -> assertEquals(View.VISIBLE, a.findViewById(R.id.user_works_retry).getVisibility()));
        ready(2); failPage = false; click(R.id.user_works_retry); ready(3);
        assertEquals(Arrays.asList("first", "20", "20"), cursors);
        screen.onActivity(a -> assertEquals(View.GONE, a.findViewById(R.id.user_works_more).getVisibility()));
    }
    @Test public void emptySuccessAndFirstPageFailureAreDifferent() {
        empty = true; launch(); ready(0);
        screen.onActivity(a -> assertEquals(a.getString(R.string.author_works_empty), ((TextView)a.findViewById(R.id.user_works_status)).getText().toString()));
        failPage = true; click(R.id.user_refresh);
        await(a -> assertEquals(View.VISIBLE, a.findViewById(R.id.user_works_retry).getVisibility()));
        screen.onActivity(a -> assertFalse(a.getString(R.string.author_works_empty).equals(((TextView)a.findViewById(R.id.user_works_status)).getText().toString())));
        empty = false; failPage = false; click(R.id.user_works_retry); ready(2);
    }
    @Test public void authorDisabledBetweenPagesRemovesOldCardsAndHeader() {
        launch(); ready(2); disabled = true; click(R.id.user_works_more);
        await(a -> {
            assertEquals(0, list(a).getAdapter().getItemCount());
            assertEquals(View.GONE, a.findViewById(R.id.user_profile_card).getVisibility());
            assertEquals(a.getString(R.string.author_unavailable), ((TextView)a.findViewById(R.id.user_status)).getText().toString());
            assertFalse(a.findViewById(R.id.user_follow).isEnabled());
        });
        disabled = false; click(R.id.user_retry); ready(2);
    }
    @Test public void stoppedPageRequestCannotRestoreOldRows() throws Exception {
        launch(); ready(2); Gate held = new Gate(); gate = held; click(R.id.user_works_more); held.await();
        screen.moveToState(Lifecycle.State.CREATED);
        screen.onActivity(a -> assertEquals(0, list(a).getAdapter().getItemCount()));
        empty = true; screen.moveToState(Lifecycle.State.RESUMED); held.release.countDown(); ready(0);
        assertEquals("first", cursors.get(cursors.size() - 1));
    }
    @Test public void recreationDuringReadStartsAgainWithoutDuplicateRows() throws Exception {
        Gate held = new Gate(); gate = held; launch(); held.await(); screen.recreate(); held.release.countDown(); ready(2);
        assertEquals(Arrays.asList("first", "first"), cursors);
    }
    @Test public void accountSwitchDiscardsOldViewerLikeState() throws Exception {
        liked = true; Gate held = new Gate(); gate = held; launch(); held.await();
        CompletableFuture<Void> loggedOut = new CompletableFuture<>();
        api.logout(new CommunityClient.Callback<Void>() {
            public void onSuccess(Void value) { loggedOut.complete(null); }
            public void onFailure(String message) { loggedOut.complete(null); }
        });
        api.restore("bob", "refresh-bob", "bob"); held.release.countDown(); loggedOut.get(10, TimeUnit.SECONDS); ready(2);
        await(a -> {
            RecyclerView.ViewHolder holder = list(a).findViewHolderForAdapterPosition(0); assertNotNull(holder);
            assertEquals("Bob 30", ((TextView)holder.itemView.findViewById(R.id.author_work_title)).getText().toString());
            assertEquals(a.getString(R.string.author_work_likes, 7), ((TextView)holder.itemView.findViewById(R.id.author_work_likes)).getText().toString());
        });
    }
    @Test public void openingDetailAndReturningRefreshesLikesAndWithdrawal() {
        launch(); ready(2); int before = profiles.get();
        await(a -> {
            RecyclerView.ViewHolder holder = list(a).findViewHolderForAdapterPosition(0); assertNotNull(holder); holder.itemView.performClick();
        });
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.collect_button))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
        liked = true; androidx.test.espresso.Espresso.pressBack(); ready(2);
        assertTrue(profiles.get() > before);
        await(a -> assertEquals(a.getString(R.string.author_work_liked, 7),
                ((TextView)list(a).findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.author_work_likes)).getText().toString()));
        empty = true; screen.moveToState(Lifecycle.State.CREATED); screen.moveToState(Lifecycle.State.RESUMED); ready(0);
    }
    @Test public void profileAndGridHaveReviewableVisualState() throws Exception {
        sample = true; liked = true; launch(); ready(2);
        saveScreenshot("f05-author-profile.png");
        screen.onActivity(a -> ((androidx.core.widget.NestedScrollView)a.findViewById(R.id.user_scroll)).fullScroll(View.FOCUS_DOWN));
        saveScreenshot("f05-author-works.png");
    }
    private void saveScreenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(800);
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), name);
        try (FileOutputStream stream = new FileOutputStream(file)) { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        screenshot.recycle();
    }
}
