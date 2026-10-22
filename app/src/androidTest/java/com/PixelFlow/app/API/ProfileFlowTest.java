package com.PixelFlow.app.API;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.gson.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

/** Real UI + real HTTP against an isolated local server, never creates cloud accounts. */
@RunWith(AndroidJUnit4.class)
public class ProfileFlowTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<MainActivity> scenario;
    private volatile String aliceName = "Alice", aliceBio = "把日常，慢慢拍成故事。";
    private volatile boolean failStats, failSave, commitBeforeFailure;
    private final AtomicInteger writes = new AtomicInteger();
    private final Gson gson = new Gson();
    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                boolean bob = "Bearer bob".equals(request.getHeader("Authorization"));
                if (path.equals("/api/auth/login")) {
                    String name = gson.fromJson(request.getBody().readUtf8(), JsonObject.class).get("username").getAsString();
                    return ok("{\"accessToken\":\"" + name + "\",\"refreshToken\":\"refresh\"}");
                }
                if (path.equals("/api/auth/logout")) return ok("{}");
                if (path.equals("/api/users/me") && request.getMethod().equals("PUT")) {
                    writes.incrementAndGet();
                    if (!failSave || commitBeforeFailure) {
                        JsonObject input = gson.fromJson(request.getBody().readUtf8(), JsonObject.class);
                        aliceName = input.get("nickname").getAsString(); aliceBio = input.get("bio").getAsString();
                    }
                    if (failSave) return ok("{\"message\":\"暂时无法确认保存结果\"}").setResponseCode(503);
                    return ok(user(false));
                }
                if (path.equals("/api/users/me")) return ok(user(bob));
                if (path.equals("/api/users/me/stats")) {
                    if (failStats) return ok("{}").setResponseCode(503);
                    return ok("{\"user_id\":\"" + (bob ? "b" : "a") + "\",\"post_count\":0,\"follower_count\":12,\"following_count\":3}");
                }
                if (path.equals("/api/users/me/profile")) return ok("{\"version\":1,\"topics\":[{\"name\":\"摄影\",\"score\":3}]}");
                if (path.startsWith("/feed/")) return ok("{\"status_code\":0,\"has_more\":0,\"next_cursor\":null,\"post_list\":[]}");
                return ok("{}").setResponseCode(404);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true); previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); singleton.set(null, api);
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(a -> ((BottomNavigationView)a.findViewById(R.id.bottom_navigation)).setSelectedItemId(R.id.nav_profile));
        await(() -> onView(withId(R.id.tv_user_name)).check(matches(withText(R.string.profile_guest))));
    }
    private MockResponse ok(String body) { return new MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body); }
    private String user(boolean bob) {
        JsonObject value = new JsonObject(); value.addProperty("id", bob ? "b" : "a"); value.addProperty("username", bob ? "bob" : "alice");
        value.addProperty("nickname", bob ? "Bob" : aliceName); value.addProperty("bio", bob ? "Bob's bio" : aliceBio); value.addProperty("avatar", "");
        return value.toString();
    }
    @After public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        if (api != null) api.close();
        if (singleton != null) singleton.set(null, previous);
        if (server != null) server.shutdown();
    }
    private void login(String name) {
        onView(withId(R.id.btn_edit_profile)).perform(scrollTo(), click());
        onView(withId(R.id.account_username)).perform(replaceText(name));
        onView(withId(R.id.account_password)).perform(replaceText("test-password-2026"), closeSoftKeyboard());
        onView(withId(R.id.account_submit)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.tv_user_name)).check(matches(withText(name.equals("alice") ? aliceName : "Bob"))));
    }
    private void editor() {
        onView(withId(R.id.btn_edit_profile)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.edit_nickname)).check(matches(isEnabled())));
    }
    private void await(Runnable assertion) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { assertion.run(); return; }
            catch (AssertionError | androidx.test.espresso.NoMatchingViewException e) {
                if (System.nanoTime() > end) throw e;
                android.os.SystemClock.sleep(100);
            }
        }
    }
    private void screenshot(String name) throws Exception {
        android.os.SystemClock.sleep(400); // Allow Material field-label animations to settle before visual QA.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
    @Test public void loginEditRotateSaveAndReenterReadsServer() throws Exception {
        onView(withId(R.id.tv_posts_count)).check(matches(withText("—")));
        login("alice");
        await(() -> onView(withId(R.id.tv_followers_count)).check(matches(withText("12"))));
        onView(withId(R.id.tv_posts_count)).check(matches(withText("0")));
        screenshot("qa-profile.png");
        editor();
        onView(withId(R.id.edit_nickname)).perform(replaceText("Alice Studio"));
        onView(withId(R.id.edit_bio)).perform(replaceText("摄影与旅行"), closeSoftKeyboard());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) { activity.recreate(); break; }
        });
        await(() -> onView(withId(R.id.edit_nickname)).check(matches(withText("Alice Studio"))));
        onView(withId(R.id.edit_bio)).check(matches(withText("摄影与旅行")));
        screenshot("qa-edit-profile.png");
        onView(withId(R.id.editor_save)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.tv_user_name)).check(matches(withText("Alice Studio"))));
        onView(withId(R.id.tv_user_bio)).check(matches(withText("摄影与旅行")));
        assertEquals(1, writes.get()); editor();
        onView(withId(R.id.edit_nickname)).check(matches(withText("Alice Studio")));
        onView(withId(R.id.editor_save)).check(matches(not(isEnabled())));
        onView(withId(R.id.editor_back)).perform(click());
    }
    @Test public void failedSaveKeepsDraftAndVerifiesBeforeRetry() {
        login("alice"); editor(); failSave = true;
        onView(withId(R.id.edit_nickname)).perform(replaceText("Keep Draft"), closeSoftKeyboard());
        onView(withId(R.id.editor_save)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.editor_retry)).check(matches(isDisplayed())));
        onView(withId(R.id.edit_nickname)).check(matches(withText("Keep Draft")));
        onView(withId(R.id.editor_save)).check(matches(not(isEnabled())));
        onView(withId(R.id.editor_retry)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.editor_save)).check(matches(isEnabled())));
        onView(withId(R.id.edit_nickname)).check(matches(withText("Keep Draft"))); assertEquals(1, writes.get());
        onView(withId(R.id.editor_back)).perform(click()); onView(withText(R.string.profile_keep_editing)).perform(click());
        failSave = false; onView(withId(R.id.editor_save)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.tv_user_name)).check(matches(withText("Keep Draft")))); assertEquals(2, writes.get());
    }
    @Test public void lostWriteResponseIsConfirmedWithoutDuplicatePut() {
        login("alice"); editor(); failSave = true; commitBeforeFailure = true;
        onView(withId(R.id.edit_nickname)).perform(replaceText("Already Saved"), closeSoftKeyboard());
        onView(withId(R.id.editor_save)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.editor_retry)).check(matches(isDisplayed())));
        onView(withId(R.id.editor_retry)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.tv_user_name)).check(matches(withText("Already Saved")))); assertEquals(1, writes.get());
    }
    @Test public void statisticsFailureKeepsUnknownAndLogoutDoesNotLeakPreviousProfile() {
        failStats = true; login("alice");
        await(() -> onView(withId(R.id.profile_retry)).check(matches(isDisplayed())));
        onView(withId(R.id.tv_posts_count)).check(matches(withText("—")));
        failStats = false; onView(withId(R.id.profile_retry)).perform(scrollTo(), click());
        await(() -> onView(withId(R.id.tv_followers_count)).check(matches(withText("12"))));
        onView(withId(R.id.btn_account)).perform(scrollTo(), click());
        onView(withId(R.id.account_logout)).perform(scrollTo(), click()); onView(withText("退出登录")).perform(click());
        await(() -> onView(withId(R.id.account_heading)).check(matches(withText(R.string.account_title))));
        onView(withId(R.id.account_back)).perform(click());
        await(() -> onView(withId(R.id.tv_user_name)).check(matches(withText(R.string.profile_guest))));
        onView(withId(R.id.tv_posts_count)).check(matches(withText("—"))); login("bob");
        onView(withId(R.id.tv_user_bio)).check(matches(withText("Bob's bio")));
    }
}
