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

    @Test public void sourceNavigationLoadsFullPostInsteadOfExcerpt() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().equals("/api/posts/1")) return new MockResponse().setBody(
                            "{\"post_id\":\"1\",\"title\":\"原文标题\",\"content\":\"完整正文，不只是摘要\",\"status\":\"PUBLISHED\",\"author\":{\"user_id\":\"bob\",\"nickname\":\"作者\"},\"clips\":[]}");
                    return new MockResponse().setBody("{\"status_code\":0,\"comment_list\":[],\"has_more\":0}");
                }
            });
            server.start();
            Field singleton = ApiClient.class.getDeclaredField("instance");
            singleton.setAccessible(true);
            ApiClient previous = (ApiClient) singleton.get(null);
            ApiClient api = new ApiClient(server.url("/").toString());
            singleton.set(null, api);
            android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                    com.PixelFlow.app.DetailPage.WorkDetailActivity.class.getName(), null, false);
            Intent intent = new Intent(instrumentation.getTargetContext(), SearchActivity.class);
            try (ActivityScenario<SearchActivity> screen = ActivityScenario.launch(intent)) {
                AgentSearchResult.Source source = new com.google.gson.Gson().fromJson(
                        "{\"post_id\":\"1\",\"title\":\"摘要标题\",\"excerpt\":\"摘要\",\"author_name\":\"作者\"}", AgentSearchResult.Source.class);
                screen.onActivity(a -> {
                    try {
                        java.lang.reflect.Method open = SearchActivity.class.getDeclaredMethod("openSourceDetail", AgentSearchResult.Source.class);
                        open.setAccessible(true);
                        open.invoke(a, source);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                android.app.Activity detail = monitor.waitForActivityWithTimeout(5000);
                assertNotNull("Source did not open detail", detail);
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    assertEquals("完整正文，不只是摘要", ((TextView) detail.findViewById(R.id.content_text)).getText().toString());
                    detail.finish();
                });
            } finally {
                instrumentation.removeMonitor(monitor);
                singleton.set(null, previous);
                api.close();
            }
        }
    }

    @Test public void submittingNewQuestionCannotDisplayDelayedOldAnswer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    if (!request.getPath().equals("/api/agent/search/stream"))
                        return new MockResponse().setBody("{\"status_code\":0,\"has_more\":0,\"post_list\":[]}");
                    boolean old = request.getBody().readUtf8().contains("旧问题");
                    calls.incrementAndGet();
                    String text = old ? "旧问题的迟到回答" : "新问题的回答";
                    String events = "event: meta\ndata: {\"request_id\":\"r\",\"seq\":1,\"session_id\":\"s\",\"trace_id\":\"t\"}\n\n"
                            + "event: sources\ndata: {\"request_id\":\"r\",\"seq\":2,\"sources\":[]}\n\n"
                            + "event: delta\ndata: {\"request_id\":\"r\",\"seq\":3,\"text\":\"" + text + "\"}\n\n"
                            + "event: done\ndata: {\"request_id\":\"r\",\"seq\":4,\"answer\":\"" + text
                            + "\",\"session_id\":\"s\",\"trace_id\":\"t\",\"sources\":[],\"validated\":true,\"degraded\":0}\n\n";
                    MockResponse response = new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events);
                    return old ? response.setBodyDelay(2, TimeUnit.SECONDS) : response;
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
                screen.onActivity(a -> {
                    ((EditText) a.findViewById(R.id.et_search)).setText("旧问题");
                    a.findViewById(R.id.btn_submit).performClick();
                });
                for (int i = 0; i < 60 && calls.get() < 1; i++) Thread.sleep(50);
                assertEquals(1, calls.get());
                screen.onActivity(a -> {
                    ((EditText) a.findViewById(R.id.et_search)).setText("新问题");
                    a.findViewById(R.id.btn_submit).performClick();
                });
                Thread.sleep(2500);
                screen.onActivity(a -> assertEquals("新问题的回答", ((TextView) a.findViewById(R.id.ai_answer)).getText().toString()));
                assertEquals(2, calls.get());
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
