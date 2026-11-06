package com.PixelFlow.app.share;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.PostItem;
import com.google.gson.Gson;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ShareImageJobTest {
    private MockWebServer server;
    private ApiClient api;
    private ShareImageJob job;
    private ExecutorService worker;
    private Uri saved;
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Before public void setup() throws Exception {
        server = new MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        java.lang.reflect.Constructor<ApiClient> constructor = ApiClient.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true); api = constructor.newInstance(server.url("/").toString());
        api.restore("test-token", "test-refresh", "test-user");
        worker = Executors.newSingleThreadExecutor();
    }
    @After public void cleanup() throws Exception {
        if (job != null) job.cancel();
        worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        if (saved != null) context().getContentResolver().delete(saved, null, null);
        api.close(); server.shutdown();
    }
    private byte[] asset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
            return output.toByteArray();
        }
    }
    private Bitmap generate(String url, int type) throws Exception {
        PostItem post = new Gson().fromJson("{\"post_id\":\"30\",\"title\":\"Share test\",\"content\":\"Full content\",\"status\":\"PUBLISHED\",\"create_time\":1,\"clips\":[{\"type\":" + type + ",\"url\":\"" + url + "\"}]}", PostItem.class);
        CompletableFuture<Uri> result = new CompletableFuture<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> job = new ShareImageJob(context(), api));
        worker.submit(() -> job.run(post, "https://example.org/#/post/30", new ShareImageJob.Callback() {
            public void complete(Uri uri) { result.complete(uri); }
            public void failed() { result.completeExceptionally(new AssertionError("Image generation failed")); }
        }));
        saved = result.get(15, TimeUnit.SECONDS);
        try (InputStream input = context().getContentResolver().openInputStream(saved)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input); assertNotNull(bitmap);
            assertEquals(1080, bitmap.getWidth()); assertTrue(bitmap.getHeight() <= 4096); return bitmap;
        }
    }
    private int gradientPixel() throws Exception {
        final Bitmap[] image = new Bitmap[1];
        PostItem post = new Gson().fromJson("{\"post_id\":\"30\",\"title\":\"Share test\",\"content\":\"Full content\",\"create_time\":1}", PostItem.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> image[0] = ShareCardRenderer.render(context(), post, "https://example.org/#/post/30", null, null));
        try { return image[0].getPixel(200, 200); } finally { image[0].recycle(); }
    }
    @Test public void authenticatedImageUsesExistingMediaClientAndProducesCover() throws Exception {
        server.enqueue(new MockResponse().setBody(new Buffer().write(asset("moment-1.jpg"))));
        Bitmap bitmap = generate(server.url("/api/media/abc/content").toString(), 0);
        try {
            assertNotEquals(gradientPixel(), bitmap.getPixel(200, 200));
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("Bearer test-token", request.getHeader("Authorization"));
        } finally { bitmap.recycle(); }
    }
    @Test public void videoUsesGlideFrameWithoutForwardingAccountToken() throws Exception {
        server.enqueue(new MockResponse().setBody(new Buffer().write(asset("flowers.mp4"))));
        Bitmap bitmap = generate(server.url("/video.mp4").toString(), 1);
        try {
            assertNotEquals(gradientPixel(), bitmap.getPixel(200, 200));
            assertNull(server.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization"));
        } finally { bitmap.recycle(); }
    }
    @Test public void slowImageFallsBackWithinTwoSecondLoadingBudget() throws Exception {
        server.enqueue(new MockResponse().setBody(new Buffer().write(asset("moment-1.jpg"))).setBodyDelay(5, TimeUnit.SECONDS));
        long started = System.nanoTime();
        Bitmap bitmap = generate(server.url("/slow.jpg").toString(), 0);
        try {
            assertEquals(gradientPixel(), bitmap.getPixel(200, 200));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4500);
        } finally { bitmap.recycle(); }
    }
    @Test public void cancelledAfterPublishingBeforeDeliveryDeletesAlbumEntry() throws Exception {
        unpublishedDeliveryRace(false);
    }
    @Test public void accountSwitchAfterPublishingBeforeDeliveryDeletesAlbumEntry() throws Exception {
        unpublishedDeliveryRace(true);
    }
    private void unpublishedDeliveryRace(boolean switchAccount) throws Exception {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        android.database.ContentObserver observer = new android.database.ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                blocked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }
        };
        context().getContentResolver().registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        Future<?> running = null;
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> job = new ShareImageJob(context(), api));
            PostItem post = new PostItem(); post.setPostId("race-test");
            running = worker.submit(() -> job.run(post, "https://example.org/#/post/race-test", new ShareImageJob.Callback() {
                public void complete(Uri uri) { delivered.set(true); }
                public void failed() { }
            }));
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (saved == null && System.nanoTime() < end) {
                try (android.database.Cursor cursor = context().getContentResolver().query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[]{"_id", "is_pending"}, "_display_name LIKE ?", new String[]{"pixelflow_race-test_%"}, "date_added DESC")) {
                    if (cursor != null && cursor.moveToFirst() && cursor.getInt(1) == 0)
                        saved = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                }
                if (saved == null) android.os.SystemClock.sleep(20);
            }
            assertNotNull("Image must have published before invalidation", saved);
            if (switchAccount) {
                server.enqueue(new MockResponse().setBody("{}"));
                api.logout(new com.PixelFlow.app.API.CommunityClient.Callback<Void>() {
                    public void onSuccess(Void ignored) { }
                    public void onFailure(String ignored) { }
                });
                api.restore("other", "refresh-other", "other-user");
            } else job.cancel();
            release.countDown(); running.get(5, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertFalse(delivered.get());
            try (android.database.Cursor cursor = context().getContentResolver().query(saved, new String[]{"_id"}, null, null, null)) {
                assertTrue(cursor == null || cursor.getCount() == 0);
            }
            saved = null;
        } finally {
            release.countDown(); context().getContentResolver().unregisterContentObserver(observer);
            if (running != null) running.get(5, TimeUnit.SECONDS);
        }
    }
    @Test public void cancelledGenerationCannotDeliverOrSave() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> job = new ShareImageJob(context(), api));
        job.cancel();
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        PostItem post = new PostItem(); post.setPostId("30");
        worker.submit(() -> job.run(post, "https://example.org/#/post/30", new ShareImageJob.Callback() {
            public void complete(Uri uri) { delivered.set(true); }
            public void failed() { delivered.set(true); }
        })).get(5, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertFalse(delivered.get());
    }
}
