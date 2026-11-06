package com.PixelFlow.app.API;

import android.content.Intent;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.Streams.WorkItem;
import com.PixelFlow.app.R;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SummaryStreamingFlowTest {
    @Test public void summaryAppearsBeforePointsAndRecreationKeepsOneGeneration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean failReads = new AtomicBoolean(true);
        AtomicBoolean changed = new AtomicBoolean();
        String content = new String(new char[240]).replace('\0', '文');
        String first = "event: summary\ndata: {\"seq\":1,\"summary\":\"先看到概括\"}\n\n";
        String rest = "";
        for (int i = 0; i < 3; i++) rest += "event: point\ndata: {\"seq\":" + (i + 2) + ",\"point\":\"要点" + i + "\"}\n\n";
        rest += "event: done\ndata: {\"seq\":5,\"summary\":\"先看到概括\",\"points\":[\"要点0\",\"要点1\",\"要点2\"],\"validated\":true,\"trace_id\":\"test\"}\n\n";
        final String tail = rest;
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().endsWith("/summary/stream")) {
                        calls.incrementAndGet();
                        String body = changed.get() ? (first + tail).replace("先看到概括", "修改后的概括") : first + tail;
                        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
                                .throttleBody(first.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, 1, TimeUnit.SECONDS);
                    }
                    if (request.getPath().equals("/api/posts/1")) {
                        if (failReads.get()) return new MockResponse().setResponseCode(503)
                                .setBody("{\"message\":\"作品暂时无法读取\"}");
                        return new MockResponse().setBody(
                                "{\"post_id\":\"1\",\"title\":\"长文\",\"content\":\"" + content + (changed.get() ? "新版本" : "") + "\",\"status\":\"PUBLISHED\",\"author\":{\"user_id\":\"bob\",\"nickname\":\"作者\"},\"clips\":[]}");
                    }
                    return new MockResponse().setBody("{\"status_code\":0,\"comment_list\":[],\"has_more\":0}");
                }
            });
            server.start();
            Field singleton = ApiClient.class.getDeclaredField("instance");
            singleton.setAccessible(true);
            ApiClient previous = (ApiClient) singleton.get(null);
            ApiClient api = new ApiClient(server.url("/").toString());
            api.restore("token", "refresh", "alice");
            singleton.set(null, api);
            AiTaskRegistry.get().clearForAccount();
            Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), WorkDetailActivity.class);
            intent.putExtra("work_item", new WorkItem("1", null, "长文", content, "作者", null, 0, 0, 0));
            try (ActivityScenario<WorkDetailActivity> screen = ActivityScenario.launch(intent)) {
                screen.onActivity(a -> a.findViewById(R.id.summarize_entry).performClick());
                for (int i = 0; i < 80; i++) {
                    AtomicBoolean retryVisible = new AtomicBoolean();
                    screen.onActivity(a -> retryVisible.set(a.findViewById(R.id.summary_retry).isShown()));
                    if (retryVisible.get()) break;
                    Thread.sleep(50);
                }
                screen.onActivity(a -> {
                    assertTrue(a.findViewById(R.id.summary_retry).isShown());
                    assertEquals(0, calls.get());
                    failReads.set(false);
                    a.findViewById(R.id.summary_retry).performClick();
                    for (int click = 0; click < 4; click++) a.findViewById(R.id.summarize_entry).performClick();
                });
                awaitText(screen, "先看到概括");
                screen.onActivity(a -> assertTrue(((TextView) a.findViewById(R.id.summary_body)).getText().toString()
                        .contains(a.getString(R.string.ai_stream_generating))));
                screen.onActivity(a -> a.findViewById(R.id.summarize_entry).performClick());
                screen.recreate();
                awaitText(screen, "先看到概括");
                screen.onActivity(a -> assertEquals(android.view.View.GONE,
                        a.findViewById(R.id.summary_body).getVisibility()));
                Thread.sleep(5500);
                awaitText(screen, "要点2");
                screen.onActivity(a -> a.findViewById(R.id.summarize_entry).performClick());
                awaitText(screen, "要点2");
                assertEquals(1, calls.get());
                screen.onActivity(a -> a.findViewById(R.id.summarize_entry).performClick());
                changed.set(true);
                screen.onActivity(a -> a.findViewById(R.id.summarize_entry).performClick());
                awaitText(screen, "修改后的概括");
                screen.onActivity(a -> assertFalse(((TextView) a.findViewById(R.id.summary_body))
                        .getText().toString().contains("先看到概括")));
                assertEquals(2, calls.get());
            } finally {
                singleton.set(null, previous);
                AiTaskRegistry.get().clearForAccount();
                api.close();
            }
        }
    }

    private void awaitText(ActivityScenario<WorkDetailActivity> screen, String text) throws Exception {
        for (int i = 0; i < 80; i++) {
            AtomicBoolean found = new AtomicBoolean();
            screen.onActivity(a -> found.set(((TextView) a.findViewById(R.id.summary_body)).getText().toString().contains(text)));
            if (found.get()) return;
            Thread.sleep(50);
        }
        fail("Summary was not shown before completion");
    }
}
