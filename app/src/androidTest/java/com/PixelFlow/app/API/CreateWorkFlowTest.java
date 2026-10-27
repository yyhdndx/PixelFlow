package com.PixelFlow.app.API;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.CreateWorkActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.creation.DraftEditorModel;
import com.google.gson.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CreateWorkFlowTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<CreateWorkActivity> screen;
    private final AtomicInteger uploads = new AtomicInteger(), creates = new AtomicInteger(), updates = new AtomicInteger(), publishes = new AtomicInteger(), imageReads = new AtomicInteger();
    private volatile boolean failPublish, commitPublish, failCreate, failRead;
    private volatile int failUploadAt;
    private volatile String status = "DRAFT", creationKey;
    private volatile JsonObject payload;
    private volatile Gate createGate;
    private final List<Gate> gates = new ArrayList<>();
    private final List<File> files = new ArrayList<>();
    private final Set<String> seenKeys = ConcurrentHashMap.newKeySet();
    private byte[] image, bobImage;
    private static final Gson GSON = new Gson();

    private class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Gate() { gates.add(this); }
        void hold() throws InterruptedException {
            entered.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS));
        }
        void awaitEntry() throws InterruptedException { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
    }
    private Application app() { return (Application) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext(); }
    private DraftEditorModel model(CreateWorkActivity a) { return new ViewModelProvider(a).get(DraftEditorModel.class); }
    private MockResponse ok(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private String post() {
        JsonObject post = new JsonObject();
        post.addProperty("post_id", "30"); post.addProperty("status", status);
        post.addProperty("title", payload.get("title").getAsString()); post.addProperty("content", payload.get("content").getAsString());
        post.add("media_ids", payload.get("mediaIds"));
        JsonArray clips = new JsonArray();
        for (JsonElement id : payload.getAsJsonArray("mediaIds")) {
            JsonObject clip = new JsonObject(); clip.addProperty("type", 0); clip.addProperty("width", 8); clip.addProperty("height", 8);
            clip.addProperty("url", server.url("/api/media/"+id.getAsString()+"/content").toString()); clips.add(clip);
        }
        post.add("clips", clips);
        return post.toString();
    }
    @Before public void setup() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.RED);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);
        bitmap.recycle(); image = bytes.toByteArray();
        bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.BLUE);
        bytes.reset(); bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes); bitmap.recycle(); bobImage = bytes.toByteArray();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getRequestUrl().encodedPath(), method = request.getMethod();
                if (path.equals("/api/auth/logout")) return ok("{}");
                if (path.equals("/api/auth/login")) return ok("{\"accessToken\":\"bob\",\"refreshToken\":\"bob-refresh\"}");
                if (path.equals("/api/media") && method.equals("POST")) {
                    int number = uploads.incrementAndGet();
                    if (number == failUploadAt) return ok("{}").setResponseCode(503);
                    return ok("{\"mediaId\":\"m"+number+"\",\"type\":0,\"width\":8,\"height\":8,\"size\":100,\"url\":\""+server.url("/api/media/m"+number+"/content")+"\"}");
                }
                if (path.matches("/api/media/m[0-9]+/content")) {
                    imageReads.incrementAndGet();
                    if (request.getHeader("Authorization") == null) return ok("{}").setResponseCode(401);
                    return new MockResponse().setHeader("Content-Type","image/png").setBody(new Buffer().write(
                            "Bearer bob".equals(request.getHeader("Authorization")) ? bobImage : image));
                }
                if (path.equals("/api/posts") && method.equals("POST")) {
                    creates.incrementAndGet(); creationKey = request.getHeader("Idempotency-Key");
                    payload = GSON.fromJson(request.getBody().readUtf8(), JsonObject.class);
                    Gate gate = createGate; if (gate != null) { createGate = null; gate.hold(); }
                    if (failCreate) return ok("{}").setResponseCode(503);
                    boolean replay = creationKey != null && !seenKeys.add(creationKey);
                    return ok(post()).setResponseCode(replay ? 200 : 201);
                }
                if (path.equals("/api/posts/30") && method.equals("PUT")) {
                    updates.incrementAndGet();
                    if (status.equals("PENDING")) return ok("{}").setResponseCode(409);
                    payload = GSON.fromJson(request.getBody().readUtf8(), JsonObject.class);
                    return ok(post());
                }
                if (path.equals("/api/posts/30") && method.equals("GET")) return failRead ? ok("{}").setResponseCode(503) : ok(post());
                if (path.equals("/api/posts/30/publish")) {
                    publishes.incrementAndGet(); if (!failPublish || commitPublish) status = "PENDING";
                    return failPublish ? ok("{}").setResponseCode(503) : ok("{\"status\":\"PENDING\"}");
                }
                if (path.equals("/api/posts") && method.equals("GET")) return ok(payload == null ? "[]" : "["+post()+"]");
                return ok("{}").setResponseCode(404);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"),0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); api.restore("alice", "refresh", "alice"); singleton.set(null,api);
    }
    @After public void teardown() throws Exception {
        for (Gate gate:gates) gate.release.countDown();
        if (screen != null) screen.close();
        api.close(); singleton.set(null,previous); server.shutdown();
        for (File file:files) file.delete();
    }
    private void await(Runnable assertion) {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (true) try { assertion.run(); return; }
        catch (AssertionError e) { if (System.nanoTime()>deadline) throw e; android.os.SystemClock.sleep(80); }
    }
    private void launch(int count) throws Exception {
        screen = ActivityScenario.launch(CreateWorkActivity.class);
        screen.onActivity(a -> ((EditText)a.findViewById(R.id.et_title)).setText("Test draft"));
        for(int i=0;i<count;i++) {
            File file = File.createTempFile("draft-test-", ".png", app().getCacheDir()); files.add(file);
            try(FileOutputStream out = new FileOutputStream(file)) { out.write(image); }
            screen.onActivity(a -> {
                try {
                    Method selected = CreateWorkActivity.class.getDeclaredMethod("handleSelectedMedia",Intent.class);
                    selected.setAccessible(true); selected.invoke(a,new Intent().setData(Uri.fromFile(file)));
                } catch (Exception e) { throw new AssertionError(e); }
            });
        }
    }
    private void click(int id) { screen.onActivity(a -> a.findViewById(id).performClick()); }
    private void readyWithDraft() {
        await(() -> screen.onActivity(a -> {
            assertFalse(model(a).busy); assertEquals("30", model(a).state.postId); assertTrue(a.findViewById(R.id.btn_publish).isEnabled());
        }));
    }
    private void finished() { await(() -> assertEquals(Lifecycle.State.DESTROYED,screen.getState())); }

    @Test public void publishRetryReusesUploadedMediaAndDraftWithoutRepeatingSave() throws Exception {
        failPublish = true; launch(1); click(R.id.btn_publish); readyWithDraft();
        assertEquals(1,uploads.get()); assertEquals(1,creates.get()); assertEquals(1,publishes.get()); assertNotNull(creationKey);
        failPublish = false; click(R.id.btn_publish); finished();
        assertEquals(1,uploads.get()); assertEquals(1,creates.get()); assertEquals(0,updates.get()); assertEquals(2,publishes.get());
    }
    @Test public void laterUploadFailureKeepsEarlierMediaAndEditorText() throws Exception {
        failUploadAt = 2; launch(2); click(R.id.btn_save);
        await(() -> screen.onActivity(a -> { assertFalse(model(a).busy); assertEquals("m1", model(a).state.media.get(0).mediaId); }));
        assertEquals(2,uploads.get()); assertEquals(0,creates.get());
        screen.recreate(); click(R.id.btn_save); finished();
        assertEquals(3,uploads.get()); assertEquals(1,creates.get());
        assertEquals("m1",payload.getAsJsonArray("mediaIds").get(0).getAsString());
        assertEquals("Test draft",payload.get("title").getAsString());
    }
    @Test public void recreationDuringCreateKeepsOnePipelineAndBlocksEditing() throws Exception {
        failPublish = true; Gate gate = new Gate(); createGate = gate;
        launch(1); click(R.id.btn_publish); gate.awaitEntry(); screen.recreate();
        screen.onActivity(a -> { assertTrue(model(a).busy); assertFalse(a.findViewById(R.id.et_title).isEnabled()); assertFalse(a.findViewById(R.id.btn_publish).isEnabled()); });
        click(R.id.btn_publish); gate.release.countDown(); readyWithDraft();
        assertEquals(1,creates.get()); assertEquals(1,uploads.get()); assertEquals(1,publishes.get());
    }
    @Test public void unknownCreateRetriesSameKeyAndRecoversDraftAcrossRecreation() throws Exception {
        failCreate = true; launch(1);
        final DraftEditorModel.State[] staleSnapshot = {null};
        screen.onActivity(a -> staleSnapshot[0] = GSON.fromJson(GSON.toJson(model(a).state),DraftEditorModel.State.class));
        click(R.id.btn_save);
        await(() -> screen.onActivity(a -> {
            assertFalse(model(a).busy); assertTrue(model(a).state.creationUnknown);
            assertTrue(a.findViewById(R.id.btn_save).isEnabled()); assertTrue(a.findViewById(R.id.btn_publish).isEnabled());
        }));
        String key = creationKey; assertNotNull(key);
        screen.recreate();
        screen.onActivity(a -> {
            assertFalse(model(a).busy); assertTrue(model(a).state.creationUnknown);
            assertTrue(a.findViewById(R.id.btn_save).isEnabled()); // retry survives recreation
            DraftEditorModel fresh = new DraftEditorModel(app());
            fresh.initialize(staleSnapshot[0], true); // durable journal supersedes stale Activity state
            assertTrue(fresh.state.creationUnknown); assertTrue(fresh.retryable());
            assertEquals(key, fresh.state.creationKey);
        });
        failCreate = false; click(R.id.btn_save);
        finished(); // retry replays the same key, recovers draft "30", then completes the save
        assertEquals(2, creates.get());
        assertEquals(key, creationKey); // same Idempotency-Key reused, no duplicate draft
        assertEquals(0, publishes.get()); assertEquals(0, updates.get());
    }
    @Test public void committedPublishErrorReadsPendingAndDoesNotResubmit() throws Exception {
        failPublish = true; commitPublish = true;
        launch(1); click(R.id.btn_publish); finished();
        assertEquals("PENDING",status); assertEquals(1,creates.get()); assertEquals(1,publishes.get());
    }
    @Test public void failedReconciliationOnlyEnablesReadRetry() throws Exception {
        failPublish = true; failRead = true; launch(1); click(R.id.btn_publish);
        await(() -> screen.onActivity(a -> { assertFalse(model(a).busy); assertTrue(model(a).state.needsReconcile); assertEquals(View.VISIBLE,a.findViewById(R.id.creation_retry).getVisibility()); }));
        click(R.id.btn_publish); assertEquals(1,publishes.get());
        failRead = false; click(R.id.creation_retry); readyWithDraft();
        assertEquals(1,publishes.get()); assertEquals(1,creates.get());
    }
    @Test public void accountChangeDuringCreateDoesNotPublishUnderNewAccount() throws Exception {
        Gate gate = new Gate(); createGate = gate; launch(1); click(R.id.btn_publish); gate.awaitEntry();
        screen.moveToState(Lifecycle.State.CREATED);
        CompletableFuture<Void> logout = new CompletableFuture<>();
        api.logout(new CommunityClient.Callback<Void>() {
            public void onSuccess(Void ignored) { logout.complete(null); }
            public void onFailure(String message) { logout.completeExceptionally(new AssertionError(message)); }
        });
        gate.release.countDown(); logout.get(10,TimeUnit.SECONDS);
        // Session invalidation can finish at STARTED, before ActivityScenario observes RESUMED.
        try { screen.moveToState(Lifecycle.State.RESUMED); }
        catch (AssertionError transition) {
            if (screen.getState() != Lifecycle.State.DESTROYED) throw transition;
        }
        finished();
        assertEquals(1,creates.get()); assertEquals(0,publishes.get());
    }
    @Test public void reopenSavedDraftLoadsPrivateImageAndEditsSamePost() throws Exception {
        launch(1); click(R.id.btn_save); finished(); screen.close();
        Intent edit = new Intent(app(),CreateWorkActivity.class);
        edit.putExtra(CreateWorkActivity.EXTRA_POST_ID,"30"); edit.putExtra(CreateWorkActivity.EXTRA_TITLE,"Test draft");
        edit.putStringArrayListExtra(CreateWorkActivity.EXTRA_MEDIA_IDS,new ArrayList<>(Collections.singletonList("m1")));
        edit.putStringArrayListExtra(CreateWorkActivity.EXTRA_MEDIA_URLS,new ArrayList<>(Collections.singletonList(server.url("/api/media/m1/content").toString())));
        screen = ActivityScenario.launch(edit); readyWithDraft();
        await(() -> assertTrue(imageReads.get()>0));
        screen.onActivity(a -> ((EditText)a.findViewById(R.id.et_title)).setText("Edited title"));
        click(R.id.btn_publish); finished();
        assertEquals(1,creates.get()); assertEquals(1,updates.get()); assertEquals(1,uploads.get());
        assertEquals("Edited title",payload.get("title").getAsString()); assertEquals("PENDING",status);
    }
    @Test public void privateImageIsFetchedAgainAndDisplaysNewAccountBytes() throws Exception {
        launch(0);
        final android.widget.ImageView[] preview = {null};
        java.util.function.Consumer<CreateWorkActivity> bind = a -> {
            preview[0] = new android.widget.ImageView(a);
            ((android.widget.LinearLayout)a.findViewById(R.id.media_preview_container)).addView(preview[0],
                    new android.widget.LinearLayout.LayoutParams(80,80));
            AccountMedia.load(preview[0],server.url("/api/media/m1/content").toString());
        };
        screen.onActivity(bind::accept);
        await(() -> screen.onActivity(a -> assertImageColor(preview[0],android.graphics.Color.RED)));
        screen.close();
        CompletableFuture<Void> loggedOut = new CompletableFuture<>(), loggedIn = new CompletableFuture<>();
        api.logout(result(loggedOut)); loggedOut.get(10,TimeUnit.SECONDS);
        api.login("bob","test-password",result(loggedIn)); loggedIn.get(10,TimeUnit.SECONDS);
        launch(0); screen.onActivity(bind::accept);
        await(() -> screen.onActivity(a -> assertImageColor(preview[0],android.graphics.Color.BLUE)));
        assertEquals(2,imageReads.get());
    }
    private CommunityClient.Callback<Void> result(CompletableFuture<Void> value) {
        return new CommunityClient.Callback<Void>() {
            public void onSuccess(Void ignored) { value.complete(null); }
            public void onFailure(String message) { value.completeExceptionally(new AssertionError(message)); }
        };
    }
    private void assertImageColor(android.widget.ImageView view, int color) {
        assertTrue(view.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
        assertEquals(color,((android.graphics.drawable.BitmapDrawable)view.getDrawable()).getBitmap().getPixel(0,0));
    }
}
