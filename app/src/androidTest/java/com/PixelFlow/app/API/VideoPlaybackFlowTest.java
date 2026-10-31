package com.PixelFlow.app.API;

import android.content.Intent;
import android.app.Dialog;
import android.view.View;
import android.view.MotionEvent;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewpager2.widget.ViewPager2;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.AudioPlayer;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.gson.Gson;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

/** Uses an actual H.264 MP4 through HTTP and the device decoder, not a mocked player. */
@RunWith(AndroidJUnit4.class)
public class VideoPlaybackFlowTest {
    private MockWebServer server;
    private ApiClient api, previous;
    private Field singleton;
    private ActivityScenario<WorkDetailActivity> screen;
    private ActivityScenario<MainActivity> home;
    private byte[] video, poster, music;
    private volatile boolean failVideo, holdVideo;
    private final CountDownLatch videoEntered = new CountDownLatch(1), releaseVideo = new CountDownLatch(1);
    private final AtomicInteger videoRequests = new AtomicInteger();
    private volatile String acceptsVideo;

    private byte[] asset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
            return output.toByteArray();
        }
    }
    private MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    /** 生成一段 1 秒 440Hz 的合法 WAV，供真实音频解码验证音乐联动。 */
    private byte[] tone() throws java.io.IOException {
        int sampleRate = 8000, samples = sampleRate, dataSize = samples * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'R','I','F','F'}); writeIntLE(out, 36 + dataSize); out.write(new byte[]{'W','A','V','E'});
        out.write(new byte[]{'f','m','t',' '}); writeIntLE(out, 16); writeShortLE(out, 1); writeShortLE(out, 1);
        writeIntLE(out, sampleRate); writeIntLE(out, sampleRate * 2); writeShortLE(out, 2); writeShortLE(out, 16);
        out.write(new byte[]{'d','a','t','a'}); writeIntLE(out, dataSize);
        for (int i = 0; i < samples; i++) {
            short v = (short)(Math.sin(2 * Math.PI * 440 * i / sampleRate) * 6000);
            out.write(v & 0xff); out.write((v >> 8) & 0xff);
        }
        return out.toByteArray();
    }
    private void writeIntLE(ByteArrayOutputStream out, int v) { out.write(v & 0xff); out.write((v >> 8) & 0xff); out.write((v >> 16) & 0xff); out.write((v >> 24) & 0xff); }
    private void writeShortLE(ByteArrayOutputStream out, int v) { out.write(v & 0xff); out.write((v >> 8) & 0xff); }
    private String post(boolean withVideo) {
        String image = "{\"type\":0,\"width\":960,\"height\":540,\"url\":\"" + server.url("/poster.jpg") + "\"}";
        String clip = ",{\"type\":1,\"width\":1280,\"height\":720,\"url\":\"" + server.url("/video.mp4") + "\"}";
        return "{\"post_id\":\"" + (withVideo ? "30" : "29") + "\",\"title\":\"" + (withVideo ? "Video" : "Image")
                + "\",\"content\":\"Playback test\",\"status\":\"PUBLISHED\",\"create_time\":1,\"clips\":[" + image
                + (withVideo ? clip : "") + "],\"author\":{\"user_id\":\"author\",\"nickname\":\"Author\",\"avatar\":\"\"},"
                + "\"is_liked\":false,\"like_count\":0,\"is_favorited\":false}";
    }
    private String postWithMusic() {
        String image = "{\"type\":0,\"width\":960,\"height\":540,\"url\":\"" + server.url("/poster.jpg") + "\"}";
        String clip = ",{\"type\":1,\"width\":1280,\"height\":720,\"url\":\"" + server.url("/video.mp4") + "\"}";
        String music = "{\"volume\":60,\"seek_time\":0,\"url\":\"" + server.url("/music.wav") + "\"}";
        return "{\"post_id\":\"30\",\"title\":\"Music\",\"content\":\"Music linkage test\",\"status\":\"PUBLISHED\","
                + "\"create_time\":1,\"clips\":[" + image + clip + "],"
                + "\"author\":{\"user_id\":\"author\",\"nickname\":\"Author\",\"avatar\":\"\"},"
                + "\"music\":" + music + ",\"is_liked\":false,\"like_count\":0,\"is_favorited\":false}";
    }

    @Before public void setup() throws Exception {
        video = asset("sunset.mp4"); poster = asset("sunset-poster.jpg"); music = tone();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/video.mp4")) {
                    videoRequests.incrementAndGet(); videoEntered.countDown();
                    if (holdVideo && !releaseVideo.await(20, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                    if (failVideo) return new MockResponse().setResponseCode(404);
                    int offset = 0;
                    String range = request.getHeader("Range");
                    if (range != null) offset = Integer.parseInt(range.substring(6).split("-", -1)[0]);
                    if (offset >= video.length) return new MockResponse().setResponseCode(416);
                    MockResponse response = new MockResponse().setHeader("Content-Type", "video/mp4")
                            .setHeader("Accept-Ranges", "bytes").setBody(new Buffer().write(video, offset, video.length - offset));
                    if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes " + offset + "-" + (video.length - 1) + "/" + video.length);
                    return response;
                }
                if (path.equals("/poster.jpg")) return new MockResponse().setHeader("Content-Type", "image/jpeg").setBody(new Buffer().write(poster));
                if (path.equals("/music.wav")) {
                    int offset = 0;
                    String range = request.getHeader("Range");
                    if (range != null) offset = Integer.parseInt(range.substring(6).split("-", -1)[0]);
                    if (offset >= music.length) return new MockResponse().setResponseCode(416);
                    MockResponse response = new MockResponse().setHeader("Content-Type", "audio/wav")
                            .setHeader("Accept-Ranges", "bytes").setBody(new Buffer().write(music, offset, music.length - offset));
                    if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes " + offset + "-" + (music.length - 1) + "/" + music.length);
                    return response;
                }
                if (path.equals("/feed/")) {
                    acceptsVideo = request.getRequestUrl().queryParameter("accept_video_clip");
                    return json("{\"status_code\":0,\"has_more\":0,\"post_list\":[" + post(true) + "," + post(false) + "]}");
                }
                if (path.equals("/api/posts/30")) return json(post(true));
                if (path.equals("/api/events")) return json("{}");
                return json("{}").setResponseCode(404);
            }
        });
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (ApiClient) singleton.get(null);
        api = new ApiClient(server.url("/").toString()); singleton.set(null, api);
    }
    @After public void close() throws Exception {
        releaseVideo.countDown();
        if (screen != null) screen.close();
        if (home != null) home.close();
        if (api != null) api.close();
        if (singleton != null) singleton.set(null, previous);
        if (server != null) server.shutdown();
    }
    private Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private ExoPlayer player(WorkDetailActivity a) {
        return (ExoPlayer) field(field(a, "imageAdapter"), "currentPlayer");
    }
    private AudioPlayer music(WorkDetailActivity a) { return (AudioPlayer) field(a, "audioPlayerManager"); }
    private Dialog fullscreen(WorkDetailActivity a) { return (Dialog) field(field(a,"imageAdapter"),"fullscreenDialog"); }
    private ViewPager2 pager(WorkDetailActivity a) { return a.findViewById(R.id.image_pager); }
    private View videoPage(WorkDetailActivity a) {
        RecyclerView list = (RecyclerView) pager(a).getChildAt(0);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(1);
        assertNotNull(holder); return holder.itemView;
    }
    private void waitFor(Runnable assertion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) try { assertion.run(); return; }
        catch (AssertionError error) {
            if (System.nanoTime() > deadline) throw error;
            android.os.SystemClock.sleep(80);
        }
    }
    private void await(Consumer<WorkDetailActivity> assertion) { waitFor(() -> screen.onActivity(assertion::accept)); }
    private void captureEvidence(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        java.io.File folder = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null), "video-fullscreen");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(folder,name+".png"))) {
            assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        } finally { screenshot.recycle(); }
    }
    private void launch() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), WorkDetailActivity.class);
        intent.putExtra("work_item", new WorkItem(new Gson().fromJson(post(true), PostItem.class)));
        screen = ActivityScenario.launch(intent);
    }
    private void launchWithMusic() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), WorkDetailActivity.class);
        intent.putExtra("work_item", new WorkItem(new Gson().fromJson(postWithMusic(), PostItem.class)));
        screen = ActivityScenario.launch(intent);
    }
    private void playing() {
        await(a -> {
            assertEquals(1, pager(a).getCurrentItem());
            ExoPlayer p = player(a); assertNotNull(p); assertNull(p.getPlayerError());
            assertTrue(p.isPlaying()); assertTrue(p.getCurrentPosition() > 0);
            assertTrue(p.getVideoSize().width > 0);
            PlayerView view = videoPage(a).findViewById(R.id.video_player_view);
            assertEquals(View.VISIBLE, view.getVisibility()); assertSame(p, view.getPlayer());
            assertTrue(view.getUseController());
        });
    }

    @Test public void homeIdentifiesVideoWithoutStartingOffscreenPlayback() {
        home = ActivityScenario.launch(MainActivity.class);
        waitFor(() -> home.onActivity(a -> {
            RecyclerView list = a.findViewById(R.id.recycler_view);
            RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0), second = list.findViewHolderForAdapterPosition(1);
            assertNotNull(first); assertNotNull(second);
            assertEquals(View.VISIBLE, first.itemView.findViewById(R.id.work_video_badge).getVisibility());
            assertEquals(View.GONE, second.itemView.findViewById(R.id.work_video_badge).getVisibility());
        }));
        assertEquals("true", acceptsVideo); assertEquals(0, videoRequests.get());
    }

    @Test public void posterFirstPostOpensVideoAndSupportsPauseSeekAndRecreation() {
        launch(); playing();
        screen.onActivity(a -> {
            PlayerView view = videoPage(a).findViewById(R.id.video_player_view);
            view.showController();
            View pause = view.findViewById(com.google.android.exoplayer2.ui.R.id.exo_pause);
            assertNotNull(pause); assertTrue(pause.performClick());
            player(a).seekTo(1700);
        });
        await(a -> { assertFalse(player(a).getPlayWhenReady()); assertTrue(player(a).getCurrentPosition() >= 1500); });
        screen.recreate();
        await(a -> {
            assertEquals(1, pager(a).getCurrentItem()); assertNotNull(player(a));
            assertEquals(Player.STATE_READY, player(a).getPlaybackState());
            assertFalse(player(a).getPlayWhenReady());
            assertTrue(Math.abs(player(a).getCurrentPosition() - 1700) < 500);
        });
    }

    @Test public void bufferedResponseCannotStartPlaybackInBackground() throws Exception {
        holdVideo = true; launch(); assertTrue(videoEntered.await(10, TimeUnit.SECONDS));
        screen.moveToState(Lifecycle.State.CREATED);
        releaseVideo.countDown();
        await(a -> { assertEquals(Player.STATE_READY, player(a).getPlaybackState());
            assertFalse(player(a).getPlayWhenReady()); assertFalse(player(a).isPlaying()); });
        screen.moveToState(Lifecycle.State.RESUMED); playing();
    }

    @Test public void switchingToImageStopsVideoAndReturningResumesIt() {
        launch(); playing();
        screen.onActivity(a -> pager(a).setCurrentItem(0, false));
        await(a -> { assertEquals(0, pager(a).getCurrentItem()); assertNull(player(a));
            assertNull(((PlayerView)videoPage(a).findViewById(R.id.video_player_view)).getPlayer()); });
        screen.onActivity(a -> pager(a).setCurrentItem(1, false)); playing();
        final ExoPlayer[] old = new ExoPlayer[1];
        screen.onActivity(a -> old[0] = player(a)); screen.close(); screen = null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(old[0].isPlaying()); assertEquals(Player.STATE_IDLE, old[0].getPlaybackState());
        });
    }

    @Test public void failedVideoShowsRetryAndRecoversWithRealMedia() {
        failVideo = true; launch();
        await(a -> assertEquals(View.VISIBLE, videoPage(a).findViewById(R.id.video_retry).getVisibility()));
        failVideo = false;
        screen.onActivity(a -> videoPage(a).findViewById(R.id.video_retry).performClick()); playing();
        assertTrue(videoRequests.get() >= 2);
    }

    @Test public void completedVideoCanReplay() {
        launch(); playing();
        screen.onActivity(a -> player(a).seekTo(player(a).getDuration() - 150));
        await(a -> {
            assertEquals(Player.STATE_ENDED, player(a).getPlaybackState());
            assertEquals(View.VISIBLE, videoPage(a).findViewById(R.id.video_play_button).getVisibility());
        });
        screen.onActivity(a -> videoPage(a).findViewById(R.id.video_play_button).performClick()); playing();
        screen.onActivity(a -> assertTrue(player(a).getCurrentPosition() < 5000));
    }

    @Test public void backgroundMusicFollowsVideoPlayback() {
        launchWithMusic(); playing();
        await(a -> assertTrue(music(a).isPlaying())); // 视频起播后背景音乐自动开始
        screen.onActivity(a -> player(a).pause());
        await(a -> assertFalse(music(a).isPlaying())); // 视频暂停时音乐同步暂停
        screen.onActivity(a -> player(a).play());
        await(a -> assertTrue(music(a).isPlaying())); // 视频恢复时音乐同步恢复
    }

    @Test public void tappingVideoOpensFullscreen() throws Exception {
        launch(); playing();
        final ExoPlayer[] original = new ExoPlayer[1];
        screen.onActivity(a -> { original[0] = player(a); player(a).pause(); player(a).seekTo(1700); });
        await(a -> assertEquals(Player.STATE_READY,player(a).getPlaybackState()));
        captureEvidence("inline");
        onView(withId(R.id.video_player_view)).perform(click());
        onView(withId(R.id.fullscreen_video_player)).check(matches(isDisplayed()));
        await(a -> {
            PlayerView large = fullscreen(a).findViewById(R.id.fullscreen_video_player);
            assertFalse(large.getUseController());
            assertFalse(large.isControllerVisible());
            assertSame(original[0],large.getPlayer());
            assertNull(((PlayerView)videoPage(a).findViewById(R.id.video_player_view)).getPlayer());
            assertFalse(player(a).getPlayWhenReady());
            assertTrue(Math.abs(player(a).getCurrentPosition()-1700)<500);
            assertTrue(large.getHeight()>videoPage(a).getHeight());
            assertEquals(0f,large.getRotation(),0.001f);
        });
        captureEvidence("fullscreen");
        onView(withId(R.id.fullscreen_video_touch)).perform(click());
        await(a -> {
            assertNull(fullscreen(a));
            assertSame(original[0],player(a));
            assertSame(player(a),((PlayerView)videoPage(a).findViewById(R.id.video_player_view)).getPlayer());
            assertFalse(player(a).getPlayWhenReady());
            assertFalse(a.isFinishing());
        });
    }

    @Test public void fullscreenKeepsPauseSeekAndStateAcrossRecreation() {
        launch(); playing();
        screen.onActivity(a -> ((PlayerView)videoPage(a).findViewById(R.id.video_player_view)).showController());
        await(a -> {
            View pause=videoPage(a).findViewById(com.google.android.exoplayer2.ui.R.id.exo_pause);
            assertTrue(pause.isShown()); assertTrue(pause.getWidth()>0);
        });
        onView(withId(com.google.android.exoplayer2.ui.R.id.exo_pause)).perform(click());
        onView(withId(R.id.video_fullscreen_button)).perform(click());
        screen.onActivity(a -> player(a).seekTo(2200));
        await(a -> assertFalse(player(a).getPlayWhenReady()));
        screen.recreate();
        await(a -> {
            assertNotNull(fullscreen(a)); assertTrue(fullscreen(a).isShowing());
            PlayerView large = fullscreen(a).findViewById(R.id.fullscreen_video_player);
            assertSame(player(a),large.getPlayer());
            assertEquals(Player.STATE_READY,player(a).getPlaybackState());
            assertFalse(player(a).getPlayWhenReady());
            assertTrue(Math.abs(player(a).getCurrentPosition()-2200)<500);
        });
        androidx.test.espresso.Espresso.pressBack();
        await(a -> { assertNull(fullscreen(a)); assertFalse(a.isFinishing()); });
    }

    @Test public void fullscreenBufferingCannotPlayInBackgroundAndDestroyReleasesPlayer() throws Exception {
        holdVideo = true; launch(); assertTrue(videoEntered.await(10,TimeUnit.SECONDS));
        onView(withId(R.id.video_fullscreen_button)).perform(click());
        screen.moveToState(Lifecycle.State.CREATED);
        releaseVideo.countDown();
        await(a -> {
            assertEquals(Player.STATE_READY,player(a).getPlaybackState());
            assertFalse(player(a).getPlayWhenReady());
        });
        screen.moveToState(Lifecycle.State.RESUMED);
        await(a -> { assertTrue(player(a).isPlaying()); assertTrue(fullscreen(a).isShowing()); });
        final ExoPlayer[] old = new ExoPlayer[1];
        final Dialog[] dialog = new Dialog[1];
        screen.onActivity(a -> { old[0]=player(a); dialog[0]=fullscreen(a); });
        screen.close(); screen=null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(dialog[0].isShowing()); assertFalse(old[0].isPlaying());
            assertEquals(Player.STATE_IDLE,old[0].getPlaybackState());
        });
    }

    @Test public void mixedMediaImageAfterVideoOpensItsOwnUrl() {
        com.google.gson.JsonObject mixed = com.google.gson.JsonParser.parseString(post(true)).getAsJsonObject();
        com.google.gson.JsonObject extra = mixed.getAsJsonArray("clips").get(0).getAsJsonObject().deepCopy();
        String expected = server.url("/poster.jpg?image=after-video").toString();
        extra.addProperty("url",expected);
        mixed.getAsJsonArray("clips").add(extra);
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), WorkDetailActivity.class);
        intent.putExtra("work_item",new WorkItem(new Gson().fromJson(mixed,PostItem.class)));
        screen=ActivityScenario.launch(intent); playing();
        screen.onActivity(a -> pager(a).setCurrentItem(2,false));
        await(a -> {
            RecyclerView list=(RecyclerView)pager(a).getChildAt(0);
            RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(2);
            assertNotNull(holder);
        });
        onView(org.hamcrest.Matchers.allOf(withId(R.id.detail_image),isDisplayed())).perform(click());
        await(a -> {
            Dialog dialog=(Dialog)field(a,"fullscreenImageDialog");
            assertNotNull(dialog); assertTrue(dialog.isShowing());
            assertTrue(dialog.getWindow().getDecorView().hasWindowFocus());
            assertEquals(expected,field(dialog,"imageUrl"));
            assertNull(player(a));
        });
        onView(withId(R.id.fullscreen_photo_view)).check(matches(isDisplayed()));
        androidx.test.espresso.Espresso.pressBack();
        await(a -> assertFalse(a.isFinishing()));
    }

    @Test public void unhandledVideoDragCannotTiltOrDismissDetail() {
        launch(); playing();
        screen.onActivity(a -> {
            int[] xy = new int[2];
            View media = a.findViewById(R.id.image_container);
            media.getLocationOnScreen(xy);
            long time = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,xy[0]+50,xy[1]+50,0);
            MotionEvent move = MotionEvent.obtain(time,time+100,MotionEvent.ACTION_MOVE,xy[0]+50,xy[1]+170,0);
            MotionEvent cancel = MotionEvent.obtain(time,time+110,MotionEvent.ACTION_CANCEL,xy[0]+50,xy[1]+170,0);
            try {
                // Exercise the Activity fallback when a child leaves an event unhandled.
                a.onTouchEvent(down); a.onTouchEvent(move);
                assertEquals(0f,media.getRotation(),0.001f);
                assertEquals(0f,media.getTranslationY(),0.001f);
                a.onTouchEvent(cancel);
                assertFalse(a.isFinishing());
            } finally { down.recycle(); move.recycle(); cancel.recycle(); }
        });
    }

    @Test public void draggingOrCancellingOnPlayerDoesNotOpenFullscreen() {
        launch(); playing();
        screen.onActivity(a -> {
            PlayerView view=videoPage(a).findViewById(R.id.video_player_view);
            for (boolean cancel : new boolean[]{false,true}) {
                long time=android.os.SystemClock.uptimeMillis();
                MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,40,40,0);
                MotionEvent middle=MotionEvent.obtain(time,time+100,cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_MOVE,40,160,0);
                MotionEvent up=MotionEvent.obtain(time,time+200,MotionEvent.ACTION_UP,40,160,0);
                try {
                    view.dispatchTouchEvent(down); view.dispatchTouchEvent(middle); view.dispatchTouchEvent(up);
                    assertNull(fullscreen(a));
                    assertFalse(a.isFinishing());
                } finally { down.recycle(); middle.recycle(); up.recycle(); }
            }
        });
    }
}
