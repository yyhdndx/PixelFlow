package com.PixelFlow.app.API;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.PixelFlow.app.share.*;
import com.google.gson.Gson;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class PostShareFlowTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<WorkDetailActivity> screen;
    private volatile String status = "PUBLISHED";
    private volatile boolean fail;
    private final AtomicInteger reads = new AtomicInteger();
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private String post() {
        return "{\"post_id\":\"30\",\"title\":\"Fresh title\",\"content\":\"Full content, never summary\",\"status\":\"" + status
                + "\",\"create_time\":1,\"publish_time\":2,\"comment_count\":57,\"like_count\":9,\"clips\":[],\"author\":{\"nickname\":\"Author\",\"user_id\":\"author\"}}";
    }
    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/api/posts/30")) {
                    reads.incrementAndGet();
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(fail ? "{}" : post()).setResponseCode(fail ? 503 : 200);
                }
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"items\":[],\"has_more\":false}");
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); singleton.set(null, api);
    }
    private void launch() {
        PostItem summary = new Gson().fromJson(post(), PostItem.class);
        summary.setTitle("Stale summary"); summary.setContent("summary");
        screen = ActivityScenario.launch(new Intent(context(), WorkDetailActivity.class)
                .putExtra("work_item", new WorkItem(summary)));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void share() { screen.onActivity(a -> a.findViewById(R.id.share_button).performClick()); }
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
    private void sheet() {
        await(() -> assertNotNull(controllerField("sheet")));
    }
    private void clickOption(int index) {
        android.app.Dialog dialog = (android.app.Dialog) controllerField("sheet");
        screen.onActivity(a -> ((android.widget.LinearLayout) dialog.findViewById(R.id.sheet_options)).getChildAt(index).performClick());
    }
    private Object controllerField(String name) {
        final Object[] result = new Object[1];
        screen.onActivity(a -> {
            try {
                Field controller = WorkDetailActivity.class.getDeclaredField("shareController"); controller.setAccessible(true);
                Object value = controller.get(a);
                Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); result[0] = field.get(value);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        return result[0];
    }
    @After public void close() throws Exception {
        if (screen != null) screen.close();
        if (api != null) api.close();
        if (singleton != null) singleton.set(null, previous);
        server.shutdown();
    }
    @Test public void latestFullDetailReplacesSummaryAndDuplicateClickOpensOneSheet() {
        launch();
        screen.onActivity(a -> { a.findViewById(R.id.share_button).performClick(); a.findViewById(R.id.share_button).performClick(); });
        sheet();
        android.app.Dialog panel = (android.app.Dialog) controllerField("sheet");
        screen.onActivity(a -> assertEquals("Fresh title", ((TextView) panel.findViewById(R.id.sheet_subtitle)).getText().toString()));
        int before = reads.get(); share();
        assertEquals(before, reads.get());
    }
    @Test public void copyReadsAgainAndUsesIndependentHashLink() {
        launch(); share(); sheet(); int before = reads.get();
        clickOption(0);
        await(() -> assertTrue(reads.get() > before));
        await(() -> screen.onActivity(a -> {
            ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            assertNotNull(clipboard.getPrimaryClip());
            assertEquals("http://localhost:5173/#/post/30", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        }));
    }
    @Test public void retractedPostCannotExecutePreviouslyOpenedSheet() {
        launch(); share(); sheet(); status = "DRAFT";
        int before = reads.get(); clickOption(0);
        await(() -> assertTrue(reads.get() > before));
        await(() -> assertEquals(false, controllerField("busy")));
        assertNull(controllerField("sheet")); assertNull(controllerField("imageJob"));
    }
    @Test public void allNonPublicStatusesRejectPanelAndPreviouslyOpenedActions() {
        launch();
        for (String state : new String[]{"DRAFT", "PENDING", "REJECTED", "WITHDRAWN"}) {
            status = state; share();
            await(() -> assertEquals(false, controllerField("busy"))); assertNull(controllerField("sheet"));
            status = "PUBLISHED"; share(); sheet(); status = state; clickOption(0);
            await(() -> assertEquals(false, controllerField("busy"))); assertNull(controllerField("sheet"));
        }
    }
    @Test public void destroyedScreenDismissesVisiblePanel() {
        launch(); share(); sheet();
        android.app.Dialog panel = (android.app.Dialog) controllerField("sheet");
        screen.close(); screen = null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertFalse(panel.isShowing()));
    }
    @Test public void accountSwitchRechecksActionInsteadOfUsingOldPanelSnapshot() {
        launch(); share(); sheet();
        api.logout(new CommunityClient.Callback<Void>() {
            public void onSuccess(Void ignored) { }
            public void onFailure(String ignored) { }
        });
        api.restore("new-account", "refresh", "new-user"); status = "WITHDRAWN";
        clickOption(2);
        await(() -> assertEquals(false, controllerField("busy")));
        assertNull(controllerField("loading")); assertNull(controllerField("imageJob"));
    }
    @Test public void nonPublishedAndFailedFetchRecoverWithoutStalePanel() {
        launch(); status = "DRAFT"; share();
        await(() -> assertEquals(false, controllerField("busy"))); assertNull(controllerField("sheet"));
        status = "PUBLISHED"; fail = true; share();
        await(() -> assertEquals(false, controllerField("busy"))); assertNull(controllerField("sheet"));
        fail = false; share(); sheet();
    }
    @Test public void longCardHasBoundedDimensionsAndBackendCounts() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            PostItem item = new Gson().fromJson(post(), PostItem.class);
            StringBuilder longText = new StringBuilder(); for (int i = 0; i < 1000; i++) longText.append("很长的内容\n");
            item.setTitle(longText.toString()); item.setContent(longText.toString());
            View card = ShareCardRenderer.create(context(), item, "http://localhost:5173/#/post/30", null, null);
            assertEquals(1080, card.getMeasuredWidth()); assertTrue(card.getMeasuredHeight() <= 4096);
            TextView title = card.findViewById(R.id.share_card_title), body = card.findViewById(R.id.share_card_body);
            assertTrue(title.getLineCount() <= 2); assertTrue(body.getLineCount() <= 12);
            assertTrue(body.getLayout().getEllipsisCount(body.getLineCount() - 1) > 0);
            assertEquals("9 赞 · 57 评论", ((TextView) card.findViewById(R.id.share_statistics)).getText().toString());
        });
    }
    @Test public void imageActionSavesOnceAndLaunchesReadableChooser() throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29);
        java.util.concurrent.CompletableFuture<Intent> launched = new java.util.concurrent.CompletableFuture<>();
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Instrumentation.ActivityMonitor monitor = new android.app.Instrumentation.ActivityMonitor() {
            @Override public android.app.Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
                    launched.complete(intent);
                    return new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        Uri saved = null;
        try {
            launch(); share(); sheet(); clickOption(2);
            share(); share();
            Intent chooser = launched.get(15, TimeUnit.SECONDS);
            Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            assertNotNull(send); assertEquals("image/png", send.getType());
            saved = send.getParcelableExtra(Intent.EXTRA_STREAM);
            assertNotNull(saved); assertEquals("media", saved.getAuthority());
            try (java.io.InputStream input = context().getContentResolver().openInputStream(saved)) {
                Bitmap card = android.graphics.BitmapFactory.decodeStream(input);
                assertNotNull(card); assertEquals(1080, card.getWidth()); card.recycle();
            }
            await(() -> assertEquals(false, controllerField("busy")));
            assertNull(controllerField("sheet"));
        } finally {
            instrumentation.removeMonitor(monitor);
            if (saved != null) context().getContentResolver().delete(saved, null, null);
        }
    }
    @Test public void systemIntentsContainTextAndReadablePngUri() {
        PostItem item = new Gson().fromJson(post(), PostItem.class);
        Intent text = ShareIntents.text(item, "https://example.org/#/post/30");
        assertEquals("text/plain", text.getType()); assertEquals("Fresh title\nhttps://example.org/#/post/30", text.getStringExtra(Intent.EXTRA_TEXT));
        Uri uri = Uri.parse("content://media/external/images/media/30"); Intent image = ShareIntents.image(uri);
        assertEquals("image/png", image.getType()); assertEquals(uri, image.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(uri, image.getClipData().getItemAt(0).getUri());
        assertTrue((image.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }
    @Test public void modernMediaStorePublishesReadablePngWithoutStoragePermission() throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29);
        Bitmap bitmap = Bitmap.createBitmap(1080, 300, Bitmap.Config.ARGB_8888);
        Uri uri = null;
        try {
            uri = ShareImageStore.save(context(), bitmap, "30"); assertEquals("content", uri.getScheme());
            assertEquals("media", uri.getAuthority());
            try (android.database.Cursor cursor = context().getContentResolver().query(uri,
                    new String[]{android.provider.MediaStore.Images.Media.IS_PENDING, android.provider.MediaStore.Images.Media.RELATIVE_PATH}, null, null, null)) {
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)); assertEquals("Pictures/PixelFlow/", cursor.getString(1));
            }
            try (java.io.InputStream input = context().getContentResolver().openInputStream(uri)) {
                Bitmap decoded = android.graphics.BitmapFactory.decodeStream(input); assertNotNull(decoded);
                assertEquals(1080, decoded.getWidth()); decoded.recycle();
            }
        } finally { bitmap.recycle(); if (uri != null) context().getContentResolver().delete(uri, null, null); }
    }
}
