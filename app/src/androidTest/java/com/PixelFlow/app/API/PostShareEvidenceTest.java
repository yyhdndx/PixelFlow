package com.PixelFlow.app.API;

import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.gson.Gson;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PostShareEvidenceTest {
    private MockWebServer server;
    private ApiClient previous, api;
    private Field singleton;
    private ActivityScenario<WorkDetailActivity> screen;
    private Uri saved;
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context context() { return instrumentation.getTargetContext(); }
    private File directory() { File file = new File(context().getFilesDir(), "share-evidence"); assertTrue(file.isDirectory() || file.mkdirs()); return file; }
    private String fixture() {
        return "{\"post_id\":\"30\",\"status\":\"PUBLISHED\",\"title\":\"在 PixelFlow 分享生活中的微光\","
                + "\"content\":\"分享卡片使用最新完整正文。\\n今天走过街角，看见阳光落在窗边。把值得记录的片刻留在这里，也分享给你。\","
                + "\"create_time\":1780000000,\"publish_time\":" + (System.currentTimeMillis() / 1000 - 3600)
                + ",\"like_count\":128,\"comment_count\":57,\"clips\":[{\"type\":0,\"url\":\"" + server.url("/cover.jpg")
                + "\"}],\"author\":{\"nickname\":\"PixelFlow 测试作者\",\"avatar\":\"" + server.url("/avatar.jpg") + "\"}}";
    }
    @Before public void setup() throws Exception {
        byte[] photo;
        try (InputStream input = instrumentation.getContext().getAssets().open("moment-1.jpg"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int count; while ((count = input.read(block)) != -1) output.write(block, 0, count); photo = output.toByteArray();
        }
        server = new MockWebServer(); server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().endsWith(".jpg")) return new MockResponse().setBody(new okio.Buffer().write(photo));
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                        request.getPath().equals("/api/posts/30") ? fixture() : "{\"items\":[],\"has_more\":false}");
            }
        }); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true); previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); singleton.set(null, api);
    }
    private void launch(PostItem item) {
        screen = ActivityScenario.launch(new Intent(context(), WorkDetailActivity.class).putExtra("work_item", new WorkItem(item)));
    }
    private Dialog panel() {
        final Dialog[] dialog = new Dialog[1];
        screen.onActivity(a -> {
            try {
                Field field = WorkDetailActivity.class.getDeclaredField("shareController"); field.setAccessible(true);
                Object controller = field.get(a); field = controller.getClass().getDeclaredField("sheet"); field.setAccessible(true);
                dialog[0] = (Dialog) field.get(controller);
            } catch (Exception error) { throw new AssertionError(error); }
        }); return dialog[0];
    }
    private void share() throws Exception {
        screen.onActivity(a -> a.findViewById(R.id.share_button).performClick());
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (panel() == null && System.nanoTime() < end) android.os.SystemClock.sleep(50);
        assertNotNull(panel());
    }
    private void option(int index) {
        Dialog dialog = panel(); screen.onActivity(a -> ((LinearLayout) dialog.findViewById(R.id.sheet_options)).getChildAt(index).performClick());
    }
    private void screenshot(String name) throws Exception {
        android.os.SystemClock.sleep(900);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (OutputStream output = new FileOutputStream(new File(directory(), name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { bitmap.recycle(); }
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        try (Writer output = new OutputStreamWriter(new FileOutputStream(new File(directory(), name + ".txt")), java.nio.charset.StandardCharsets.UTF_8)) { dump(root, output, ""); }
    }
    private void dump(android.view.accessibility.AccessibilityNodeInfo node, Writer output, String indent) throws IOException {
        if (node == null) return;
        output.write(indent + node.getPackageName() + " " + node.getClassName() + " text=" + node.getText() + " desc=" + node.getContentDescription() + "\n");
        for (int i = 0; i < node.getChildCount(); i++) dump(node.getChild(i), output, indent + "  ");
    }
    private void shell(String command) throws IOException {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { byte[] block = new byte[4096]; while (input.read(block) != -1) { } }
    }
    private void waitPackage(String expected) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && expected.contentEquals(root.getPackageName())) return;
            android.os.SystemClock.sleep(100);
        }
        fail("No visible window for " + expected);
    }
    @Test public void captureActualPanelTextChooserCardAndGallery() throws Exception {
        launch(new Gson().fromJson(fixture(), PostItem.class)); share(); screenshot("share-panel");
        option(1); waitPackage("com.android.intentresolver"); screenshot("text-chooser");
        shell("input keyevent KEYCODE_BACK"); instrumentation.waitForIdleSync();
        share(); option(2); waitPackage("com.android.intentresolver"); screenshot("image-chooser");
        try (android.database.Cursor cursor = context().getContentResolver().query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{"_id"}, "_display_name LIKE ?", new String[]{"pixelflow_30_%"}, "date_added DESC")) {
            assertNotNull(cursor); assertTrue(cursor.moveToFirst());
            saved = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
        }
        try (InputStream input = context().getContentResolver().openInputStream(saved); OutputStream output = new FileOutputStream(new File(directory(), "generated-share-card.png"))) {
            byte[] block = new byte[8192]; int count; while ((count = input.read(block)) != -1) output.write(block, 0, count);
        }
        shell("input keyevent KEYCODE_BACK"); instrumentation.waitForIdleSync();
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(saved, "image/png").setPackage("com.google.android.apps.photos")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context().startActivity(view); waitPackage("com.google.android.apps.photos"); screenshot("gallery-card");
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertTrue("Photos share action must be visible", clickShare(root));
        screenshot("gallery-share-panel");
        android.view.accessibility.AccessibilityNodeInfo sharing = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(sharing);
        assertTrue("Photos must expose local image sharing targets", containsText(sharing, "Gmail") && containsText(sharing, "Messages"));
        screenshot("gallery-reshare-chooser");
        shell("input keyevent KEYCODE_BACK"); shell("input keyevent KEYCODE_BACK");
    }
    private boolean containsText(android.view.accessibility.AccessibilityNodeInfo node, String expected) {
        if (node == null) return false;
        if (node.getText() != null && expected.contentEquals(node.getText())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (containsText(node.getChild(i), expected)) return true;
        return false;
    }
    private boolean clickShare(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        String desc = String.valueOf(node.getContentDescription()), text = String.valueOf(node.getText());
        if (desc.equalsIgnoreCase("Share") || desc.equals("分享") || text.equalsIgnoreCase("Share") || text.equals("分享")) {
            if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
            android.view.accessibility.AccessibilityNodeInfo parent = node.getParent();
            if (parent != null) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    android.view.accessibility.AccessibilityNodeInfo sibling = parent.getChild(i);
                    if (sibling != null && sibling.isClickable()
                            && sibling.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
                if (parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) if (clickShare(node.getChild(i))) return true;
        return false;
    }
    @Test public void copyRealLocalPost49WithoutRemoteWrites() throws Exception {
        api.close(); api = new ApiClient("http://127.0.0.1:8080/"); singleton.set(null, api);
        CompletableFuture<PostItem> loaded = new CompletableFuture<>();
        api.getPost("49", new CommunityClient.Callback<PostItem>() {
            public void onSuccess(PostItem item) { loaded.complete(item); }
            public void onFailure(String message) { loaded.completeExceptionally(new AssertionError(message)); }
        });
        PostItem post = loaded.get(10, TimeUnit.SECONDS); assertEquals("PUBLISHED", post.getStatus());
        launch(post); share(); option(0);
        final String[] copied = new String[1];
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            screen.onActivity(a -> {
                ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip()) copied[0] = clipboard.getPrimaryClip().getItemAt(0).coerceToText(a).toString();
            });
            if ("http://localhost:5173/#/post/49".equals(copied[0])) break;
            android.os.SystemClock.sleep(100);
        }
        assertEquals("http://localhost:5173/#/post/49", copied[0]);
        try (Writer output = new FileWriter(new File(directory(), "post49-clipboard.txt"))) { output.write(copied[0] + "\n"); }
        android.os.Bundle result = new android.os.Bundle(); result.putString("post49_clipboard", copied[0]); instrumentation.sendStatus(0, result);
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        if (saved != null) context().getContentResolver().delete(saved, null, null);
        api.close(); singleton.set(null, previous); server.shutdown();
    }
}
