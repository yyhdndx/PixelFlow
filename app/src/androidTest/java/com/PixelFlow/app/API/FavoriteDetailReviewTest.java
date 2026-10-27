package com.PixelFlow.app.API;

import android.content.Intent;
import android.view.View;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.FavoritesActivity;
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
    private volatile boolean bobFavorite, failWrite, failReads;
    private volatile Gate writeGate, readGate;
    private volatile CountDownLatch entered, release;
    private final AtomicInteger writes = new AtomicInteger();
    private final java.util.List<Gate> gates = new java.util.concurrent.CopyOnWriteArrayList<>();

    private class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Gate() { gates.add(this); }
        void hold() throws InterruptedException {
            entered.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("response gate timed out");
        }
        void awaitEntry() throws InterruptedException { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
    }

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getRequestUrl().encodedPath();
                boolean bob = "Bearer bob".equals(request.getHeader("Authorization"));
                if (path.equals("/api/auth/logout")) return ok("{}");
                if (path.equals("/api/auth/login")) return ok("{\"accessToken\":\"bob\",\"refreshToken\":\"refresh-bob\"}");
                if (path.equals("/api/interactions/favorites")) {
                    writes.incrementAndGet();
                    boolean target = new Gson().fromJson(request.getBody().readUtf8(), com.google.gson.JsonObject.class).get("active").getAsBoolean();
                    if (bob) bobFavorite = target; else favorite = target;
                    Gate gate = writeGate;
                    if (gate != null) { writeGate = null; gate.hold(); }
                    if (failWrite) return ok("{}").setResponseCode(503);
                    return ok("{\"active\":" + favorite + "}");
                }
                if (path.equals("/api/posts/30")) {
                    String captured = post(bob);
                    Gate gate = readGate;
                    if (gate != null) { readGate = null; gate.hold(); }
                    return failReads ? ok("{}").setResponseCode(503) : ok(captured);
                }
                if (path.equals("/api/users/me/favorites")) return ok("{\"status_code\":0,\"has_more\":0,\"post_list\":["
                        + ((bob ? bobFavorite : favorite) ? post(bob) : "") + "]}");
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
    private String post() { return post(false); }
    private String post(boolean bob) {
        return "{\"post_id\":\"30\",\"title\":\"Review\",\"content\":\"Review\",\"status\":\"PUBLISHED\",\"create_time\":1,\"clips\":[],\"author\":{\"user_id\":\"author\",\"nickname\":\"Author\",\"avatar\":\"\"},\"is_liked\":false,\"like_count\":0,\"is_favorited\":" + (bob ? bobFavorite : favorite) + "}";
    }
    private void launch() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), WorkDetailActivity.class);
        intent.putExtra("work_item", new WorkItem(new Gson().fromJson(post(), PostItem.class)));
        screen = ActivityScenario.launch(intent);
        favoriteIs(favorite);
    }
    private void await(Runnable assertion) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError | androidx.test.espresso.NoMatchingViewException e) {
                if (System.nanoTime() > end) throw e;
                android.os.SystemClock.sleep(100);
            }
        }
    }
    private void favoriteIs(boolean expected) {
        await(() -> screen.onActivity(a -> {
            View button = a.findViewById(R.id.collect_button);
            assertTrue(button.isEnabled());
            assertEquals(a.getString(expected ? R.string.favorite_action_remove : R.string.favorite_action_add), button.getContentDescription());
        }));
    }
    private void clickFavorite() { screen.onActivity(a -> a.findViewById(R.id.collect_button).performClick()); }
    private static class Result<T> implements CommunityClient.Callback<T> {
        final CompletableFuture<T> value = new CompletableFuture<>();
        public void onSuccess(T result) { value.complete(result); }
        public void onFailure(String error) { value.completeExceptionally(new AssertionError(error)); }
        T get() throws Exception { return value.get(10, TimeUnit.SECONDS); }
    }
    @After public void close() throws Exception {
        if (release != null) release.countDown();
        for (Gate gate : gates) gate.release.countDown();
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

    @Test public void backgroundReturnWaitsForInflightWriteAndDoesNotDuplicateIt() throws Exception {
        launch();
        Gate gate = new Gate(); writeGate = gate;
        clickFavorite(); gate.awaitEntry();
        screen.moveToState(Lifecycle.State.CREATED);
        screen.moveToState(Lifecycle.State.RESUMED);
        screen.onActivity(a -> assertFalse(a.findViewById(R.id.collect_button).isEnabled()));
        clickFavorite();
        gate.release.countDown(); favoriteIs(true);
        assertEquals(1, writes.get());
    }

    @Test public void recreationPreservesTheQueuedWriteAndReadsItsResult() throws Exception {
        launch();
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        Result<ApiResponse> slow = new Result<>(); api.getFeed(1, null, slow);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        clickFavorite(); screen.recreate();
        screen.onActivity(a -> assertFalse(a.findViewById(R.id.collect_button).isEnabled()));
        release.countDown(); slow.get(); favoriteIs(true);
        assertEquals(1, writes.get());
    }

    @Test public void stoppedReadRestartsWithoutShowingTheOldFavoriteState() throws Exception {
        favorite = true; launch();
        screen.moveToState(Lifecycle.State.CREATED);
        Gate gate = new Gate(); readGate = gate;
        screen.moveToState(Lifecycle.State.RESUMED); gate.awaitEntry();
        screen.moveToState(Lifecycle.State.CREATED);
        favorite = false;
        screen.moveToState(Lifecycle.State.RESUMED);
        gate.release.countDown(); favoriteIs(false);
        assertEquals(0, writes.get());
    }

    @Test public void writeResponseFailureReconcilesCommittedStateWithoutResubmitting() throws Exception {
        launch(); failWrite = true;
        Gate gate = new Gate(); readGate = gate;
        clickFavorite(); gate.awaitEntry();
        screen.onActivity(a -> assertFalse(a.findViewById(R.id.collect_button).isEnabled()));
        gate.release.countDown(); favoriteIs(true);
        assertEquals(1, writes.get());
    }

    @Test public void failedReconciliationOnlyAllowsReadRetryBeforeAnotherWrite() throws Exception {
        launch(); failWrite = true; failReads = true;
        clickFavorite();
        await(() -> screen.onActivity(a -> {
            assertTrue(a.findViewById(R.id.collect_button).isEnabled());
            assertEquals(a.getString(R.string.favorite_state_retry), a.findViewById(R.id.collect_button).getContentDescription());
        }));
        assertEquals(1, writes.get());
        failReads = false; failWrite = false;
        clickFavorite(); favoriteIs(true);
        assertEquals(1, writes.get());
        clickFavorite(); favoriteIs(false);
        assertEquals(2, writes.get());
    }

    @Test public void switchingAccountsDiscardsTheOldWriteCallback() throws Exception {
        launch();
        Gate gate = new Gate(); writeGate = gate;
        clickFavorite(); gate.awaitEntry();
        screen.moveToState(Lifecycle.State.CREATED);
        Result<Void> logout = new Result<>(), login = new Result<>();
        api.logout(logout); api.login("bob", "test-password", login);
        screen.moveToState(Lifecycle.State.RESUMED);
        screen.onActivity(a -> {
            assertFalse(a.findViewById(R.id.collect_button).isEnabled());
            assertEquals(a.getString(R.string.favorite_state_loading), a.findViewById(R.id.collect_button).getContentDescription());
        });
        gate.release.countDown(); logout.get(); login.get(); favoriteIs(false);
        assertTrue(favorite); assertFalse(bobFavorite); assertEquals(1, writes.get());
    }

    @Test public void favoritesListDetailRemoveAndImmediateReturnReadsUpdatedList() throws Exception {
        favorite = true;
        try (ActivityScenario<FavoritesActivity> listScreen = ActivityScenario.launch(FavoritesActivity.class)) {
            await(() -> listScreen.onActivity(a -> {
                RecyclerView list = a.findViewById(R.id.favorites_list);
                assertEquals(1, list.getAdapter().getItemCount());
                assertNotNull(list.findViewHolderForAdapterPosition(0));
            }));
            listScreen.onActivity(a -> ((RecyclerView)a.findViewById(R.id.favorites_list))
                    .findViewHolderForAdapterPosition(0).itemView.performClick());
            await(() -> androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.collect_button))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.withContentDescription(R.string.favorite_action_remove))));
            Gate gate = new Gate(); writeGate = gate;
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.collect_button))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            gate.awaitEntry();
            androidx.test.espresso.Espresso.pressBackUnconditionally();
            await(() -> listScreen.onActivity(a -> assertEquals(View.VISIBLE, a.findViewById(R.id.favorites_loading).getVisibility())));
            gate.release.countDown();
            await(() -> listScreen.onActivity(a -> {
                assertEquals(View.VISIBLE, a.findViewById(R.id.favorites_empty).getVisibility());
                assertEquals(0, ((RecyclerView)a.findViewById(R.id.favorites_list)).getAdapter().getItemCount());
            }));
            assertFalse(favorite); assertEquals(1, writes.get());
        }
    }
}
