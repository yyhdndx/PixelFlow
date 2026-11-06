package com.PixelFlow.app.API;

import android.content.Intent;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.R;
import com.PixelFlow.app.SearchActivity;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SearchStreamingFlowTest {
    @Test public void partialAnswerSurvivesRecreationWithoutDuplicateGeneration() throws Exception {
        String prefix = "event: meta\ndata: {\"request_id\":\"r\",\"seq\":1,\"session_id\":\"s\",\"trace_id\":\"t\"}\n\n"
                + "event: sources\ndata: {\"request_id\":\"r\",\"seq\":2,\"sources\":[]}\n\n"
                + "event: delta\ndata: {\"request_id\":\"r\",\"seq\":3,\"text\":\"先出现的正文\"}\n\n";
        String done = "event: done\ndata: {\"request_id\":\"r\",\"seq\":4,\"answer\":\"先出现的正文\",\"session_id\":\"s\",\"trace_id\":\"t\",\"sources\":[],\"validated\":true,\"degraded\":0}\n\n";
        AtomicInteger calls = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().equals("/api/agent/search/stream")) {
                        calls.incrementAndGet();
                        return new MockResponse().setHeader("Content-Type", "text/event-stream")
                                .setBody(prefix + done).throttleBody(prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, 5, TimeUnit.SECONDS);
                    }
                    return new MockResponse().setBody("{\"status_code\":0,\"has_more\":0,\"post_list\":[]}");
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
            Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), SearchActivity.class);
            try (ActivityScenario<SearchActivity> screen = ActivityScenario.launch(intent)) {
                screen.onActivity(activity -> {
                    ((EditText) activity.findViewById(R.id.et_search)).setText("咖啡");
                    activity.findViewById(R.id.btn_submit).performClick();
                });
                awaitAnswer(screen);
                screen.onActivity(activity -> assertEquals(InstrumentationRegistry.getInstrumentation().getTargetContext().getString(R.string.ai_stream_generating),
                        ((TextView) activity.findViewById(R.id.ai_degraded)).getText().toString()));
                screen.recreate();
                awaitAnswer(screen);
                assertEquals(1, calls.get());
                Thread.sleep(5500);
                screen.onActivity(activity -> assertEquals("先出现的正文", ((TextView) activity.findViewById(R.id.ai_answer)).getText().toString()));
                assertEquals(1, calls.get());
                screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
                api.restore("bob-token", "bob-refresh", "bob");
                screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
                screen.onActivity(activity -> {
                    assertEquals("", ((TextView) activity.findViewById(R.id.ai_answer)).getText().toString());
                    assertEquals(android.view.View.GONE, activity.findViewById(R.id.ai_card).getVisibility());
                });
                assertEquals(1, calls.get());
            } finally {
                singleton.set(null, previous);
                AiTaskRegistry.get().clearForAccount();
                api.close();
            }
        }
    }

    private void awaitAnswer(ActivityScenario<SearchActivity> screen) throws Exception {
        for (int i = 0; i < 60; i++) {
            AtomicBoolean visible = new AtomicBoolean();
            screen.onActivity(activity -> visible.set(((TextView) activity.findViewById(R.id.ai_answer)).getText().toString().equals("先出现的正文")));
            if (visible.get()) return;
            Thread.sleep(50);
        }
        fail("Partial answer was not rendered before completion");
    }
}
