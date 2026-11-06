package com.PixelFlow.app.API;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.PixelFlow.app.share.ShareEvidence;
import com.google.gson.Gson;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@ShareEvidence
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4.class)
public class LegacyShareFlowTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private ApiClient previous, api;
    private Field singleton;
    private MockWebServer server;
    private ActivityScenario<WorkDetailActivity> screen;
    private Uri saved;
    private android.content.Context context() { return instrumentation.getTargetContext(); }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024]; while (stream.read(buffer) != -1) { }
        }
    }
    @Before public void setup() throws Exception {
        assertEquals("This explicit suite requires API28", 28, android.os.Build.VERSION.SDK_INT);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(request.getPath().equals("/api/posts/28001")
                    ? "{\"post_id\":\"28001\",\"status\":\"PUBLISHED\",\"title\":\"旧版分享验收\",\"content\":\"无图分享测试\",\"create_time\":1,\"comment_count\":23,\"clips\":[],\"author\":{\"nickname\":\"测试作者\"}}"
                    : "{\"items\":[],\"has_more\":false}");
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null); api = new ApiClient(server.url("/").toString()); singleton.set(null, api);
    }
    private Object field(String name) {
        Object[] result = new Object[1];
        screen.onActivity(a -> {
            try {
                Field f = WorkDetailActivity.class.getDeclaredField("shareController"); f.setAccessible(true);
                Object controller = f.get(a); f = controller.getClass().getDeclaredField(name); f.setAccessible(true); result[0] = f.get(controller);
            } catch (Exception e) { throw new AssertionError(e); }
        }); return result[0];
    }
    private void await(Runnable assertion) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { assertion.run(); return; } catch (AssertionError e) {
                if (System.nanoTime() > until) throw e;
                android.os.SystemClock.sleep(50);
            }
        }
    }
    private void panel() {
        screen.onActivity(a -> a.findViewById(R.id.share_button).performClick());
        await(() -> assertNotNull(field("sheet")));
    }
    private void option(int index) {
        Dialog dialog = (Dialog) field("sheet");
        screen.onActivity(a -> ((LinearLayout) dialog.findViewById(R.id.sheet_options)).getChildAt(index).performClick());
    }
    private void launch() {
        PostItem post = new Gson().fromJson("{\"post_id\":\"28001\",\"title\":\"Legacy\",\"clips\":[]}", PostItem.class);
        screen = ActivityScenario.launch(new Intent(context(), WorkDetailActivity.class).putExtra("work_item", new WorkItem(post)));
    }
    private void permissionChoice(boolean allow) {
        await(() -> {
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertNotNull(root);
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId(
                    "com.android.packageinstaller:id/permission_" + (allow ? "allow" : "deny") + "_button");
            assertFalse(buttons.isEmpty());
            assertTrue(buttons.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
        });
        instrumentation.waitForIdleSync();
    }
    @Test public void deniedStorageKeepsTextSharingAvailable() throws Exception {
        shell("pm revoke com.PixelFlow.app android.permission.WRITE_EXTERNAL_STORAGE");
        try {
            launch(); panel(); option(2); permissionChoice(false);
            await(() -> assertEquals(false, field("busy")));
            assertNull(field("imageJob"));
            assertEquals(PackageManager.PERMISSION_DENIED, context().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE));
            panel(); option(0);
            await(() -> screen.onActivity(a -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                assertEquals("http://localhost:5173/#/post/28001", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            }));
        } finally { shell("pm clear-permission-flags com.PixelFlow.app android.permission.WRITE_EXTERNAL_STORAGE user-fixed"); }
    }
    @Test public void grantedStorageSavesAndSharesFileProviderImage() throws Exception {
        shell("pm clear-permission-flags com.PixelFlow.app android.permission.WRITE_EXTERNAL_STORAGE user-fixed user-set");
        CompletableFuture<Intent> sent = new CompletableFuture<>();
        android.app.Instrumentation.ActivityMonitor monitor = new android.app.Instrumentation.ActivityMonitor() {
            @Override public android.app.Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
                    sent.complete(intent); return new android.app.Instrumentation.ActivityResult(-1, null);
                } return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            launch(); panel(); option(2); permissionChoice(true);
            Intent share = sent.get(15, TimeUnit.SECONDS).getParcelableExtra(Intent.EXTRA_INTENT);
            saved = share.getParcelableExtra(Intent.EXTRA_STREAM);
            assertEquals("com.PixelFlow.app.fileprovider", saved.getAuthority());
            assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            try (InputStream input = context().getContentResolver().openInputStream(saved)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input); assertNotNull(bitmap); assertEquals(1080, bitmap.getWidth()); bitmap.recycle();
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean scanned = false;
            while (System.nanoTime() < end) {
                try (android.database.Cursor cursor = context().getContentResolver().query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[]{"_id"}, "_display_name LIKE ?", new String[]{"pixelflow_28001_%"}, null)) { scanned = cursor != null && cursor.moveToFirst(); }
                if (scanned) break; android.os.SystemClock.sleep(100);
            }
            assertTrue("Saved 28001 file must be indexed in gallery", scanned);
            assertEquals(1, context().getContentResolver().delete(saved, null, null)); saved = null;
        } finally { instrumentation.removeMonitor(monitor); }
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        if (saved != null) context().getContentResolver().delete(saved, null, null);
        if (api != null) api.close();
        if (singleton != null) singleton.set(null, previous);
        if (server != null) server.shutdown();
    }
}
