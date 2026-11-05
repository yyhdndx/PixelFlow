package com.PixelFlow.app.API;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.ButtonFragment.HomeFragment;
import com.PixelFlow.app.Streams.WaterfallAdapter;
import com.google.android.material.tabs.TabLayout;
import com.google.gson.*;
import okhttp3.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FollowingFeedHttpTest {
    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build();
    private final Gson gson = new Gson();
    private ActivityScenario<MainActivity> screen;
    private ApiClient api, previous;
    private Field singleton;
    private String base;

    private JsonObject request(String method, String path, String token, JsonObject input) throws Exception {
        Request.Builder request = new Request.Builder().url(base + path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        RequestBody body = input == null ? null : RequestBody.create(MediaType.parse("application/json"), gson.toJson(input));
        if (!method.equals("GET") && body == null) body = RequestBody.create(null, new byte[0]);
        try (Response response = http.newCall(request.method(method, body).build()).execute()) {
            assertTrue(path + " HTTP " + response.code(), response.isSuccessful());
            return gson.fromJson(response.body().string(), JsonObject.class);
        }
    }
    private JsonObject values(String... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.addProperty(pairs[i], pairs[i + 1]);
        return result;
    }
    private JsonObject account(String name, String password) throws Exception {
        request("POST", "/api/auth/register", null, values("username", name, "password", password, "nickname", name));
        return request("POST", "/api/auth/login", null, values("username", name, "password", password));
    }
    private WaterfallAdapter adapter(MainActivity activity) {
        try {
            for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof HomeFragment) {
                Field field = HomeFragment.class.getDeclaredField("adapter"); field.setAccessible(true);
                return (WaterfallAdapter) field.get(fragment);
            }
        } catch (Exception e) { throw new AssertionError(e); }
        throw new AssertionError("Home unavailable");
    }
    private void await(Consumer<MainActivity> assertion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) try { screen.onActivity(assertion::accept); return; }
        catch (AssertionError e) { if (System.nanoTime() > deadline) throw e; android.os.SystemClock.sleep(100); }
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "following-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
    }
    private void refresh(MainActivity activity) {
        try {
            for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof HomeFragment) {
                Method method = HomeFragment.class.getDeclaredMethod("loadData", boolean.class); method.setAccessible(true); method.invoke(fragment, true);
            }
        } catch (Exception e) { throw new AssertionError(e); }
    }
    @After public void close() throws Exception {
        if (screen != null) screen.close();
        if (api != null) api.close();
        if (singleton != null) singleton.set(null, previous);
        http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll();
    }
    @Test public void realFollowingUnfollowAndFourScreenStates() throws Exception {
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        base = args.getString("followingBaseUrl");
        Assume.assumeTrue("Requires an explicitly configured isolated local Java service", base != null);
        assertTrue(base.matches("http://(10\\.0\\.2\\.2|127\\.0\\.0\\.1):[0-9]+"));
        String password = UUID.randomUUID().toString(), run = UUID.randomUUID().toString().substring(0, 8);
        JsonObject author = account("follow_a_" + run, password), viewer = account("follow_b_" + run, password);
        String a = author.get("accessToken").getAsString(), b = viewer.get("accessToken").getAsString();
        String authorId = request("GET", "/api/users/me", a, null).get("id").getAsString();
        String admin = request("POST", "/api/auth/login", null, values("username", "admin", "password", args.getString("followingAdminPassword"))).get("accessToken").getAsString();
        Bitmap bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff608a80);
        ByteArrayOutputStream image = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, image); bitmap.recycle();
        RequestBody upload = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "following.png", RequestBody.create(MediaType.parse("image/png"), image.toByteArray())).build();
        String media;
        try (Response response = http.newCall(new Request.Builder().url(base + "/api/media").header("Authorization", "Bearer " + a).post(upload).build()).execute()) {
            assertEquals(201, response.code()); media = gson.fromJson(response.body().string(), JsonObject.class).get("mediaId").getAsString();
        }
        JsonObject draft = values("title", "关注作者的公开作品", "content", "真实服务联调：只有公开作品进入关注流");
        JsonArray mediaIds = new JsonArray(); mediaIds.add(media); draft.add("mediaIds", mediaIds);
        String id = request("POST", "/api/posts", a, draft).get("post_id").getAsString();
        request("POST", "/api/posts/" + id + "/publish", a, null);
        request("POST", "/api/admin/posts/" + id + "/moderation", admin, values("status", "PUBLISHED", "reason", "本地关注页验收"));
        request("POST", "/api/posts", a, draft);
        JsonObject follow = values("targetId", authorId); follow.addProperty("active", true);
        request("PUT", "/api/interactions/follows", b, follow);
        JsonArray cards = request("GET", "/api/feed/following?count=50", b, null).getAsJsonArray("post_list");
        assertEquals(1, cards.size()); assertEquals(id, cards.get(0).getAsJsonObject().get("post_id").getAsString());
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true); previous = (ApiClient) singleton.get(null);
        api = new ApiClient(base + "/"); api.restore(b, viewer.get("refreshToken").getAsString(), "follow_b_" + run); singleton.set(null, api);
        screen = ActivityScenario.launch(MainActivity.class);
        await(activity -> { assertEquals(1, adapter(activity).getData().size()); assertEquals(id, adapter(activity).getData().get(0).getId()); });
        screenshot("following");
        screen.onActivity(activity -> ((TabLayout)activity.findViewById(R.id.tab_layout)).getTabAt(1).select());
        await(activity -> assertTrue(adapter(activity).getData().stream().anyMatch(item -> id.equals(item.getId())))); screenshot("community");
        follow.addProperty("active", false); request("PUT", "/api/interactions/follows", b, follow);
        screen.onActivity(activity -> { ((TabLayout)activity.findViewById(R.id.tab_layout)).getTabAt(0).select(); refresh(activity); });
        await(activity -> {
            assertEquals(0, adapter(activity).getData().size());
            assertEquals(activity.getString(R.string.home_empty_following), ((TextView)activity.findViewById(R.id.feed_state_message)).getText().toString());
        }); screenshot("empty-following");
        screen.close(); screen = null; api.close(); api = new ApiClient(base + "/"); singleton.set(null, api);
        screen = ActivityScenario.launch(MainActivity.class);
        await(activity -> {
            assertEquals(View.VISIBLE, activity.findViewById(R.id.empty_state_layout).getVisibility());
            assertEquals(activity.getString(R.string.home_login_title), ((TextView)activity.findViewById(R.id.feed_state_title)).getText().toString());
        }); screenshot("guest-following");
    }
}
