package com.PixelFlow.app.API;

import android.content.Intent;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.AutoReplyEditActivity;
import com.PixelFlow.app.R;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AutoReplyTaskFlowTest {
    @Test public void failedTaskDoesNotRetryOnRecreationAndChangedInputCannotApplyCandidates() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
            server.enqueue(new MockResponse().setBody("{\"suggestions\":[{\"question\":\"如何拍摄\",\"answer\":\"保持稳定\"}]}"));
            server.start();
            Field singleton = ApiClient.class.getDeclaredField("instance");
            singleton.setAccessible(true);
            ApiClient previous = (ApiClient) singleton.get(null);
            ApiClient api = new ApiClient(server.url("/").toString());
            api.restore("token", "refresh", "alice");
            singleton.set(null, api);
            AiTaskRegistry.get().clearForAccount();
            Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), AutoReplyEditActivity.class);
            intent.putExtra(AutoReplyEditActivity.EXTRA_POST_ID, "1");
            try (ActivityScenario<AutoReplyEditActivity> screen = ActivityScenario.launch(intent)) {
                screen.onActivity(a -> {
                    ((EditText) a.findViewById(R.id.ai_prompt)).setText("拍摄规则");
                    a.findViewById(R.id.ai_generate).performClick();
                });
                awaitEnabled(screen);
                screen.recreate();
                Thread.sleep(300);
                assertEquals(1, server.getRequestCount());
                screen.onActivity(a -> a.findViewById(R.id.ai_generate).performClick());
                awaitEnabled(screen);
                screen.onActivity(a -> {
                    LinearLayout candidates = a.findViewById(R.id.ai_candidates);
                    assertTrue(candidates.getChildCount() > 0);
                    CheckBox first = (CheckBox) candidates.getChildAt(0);
                    assertFalse(first.isChecked());
                    assertFalse(a.findViewById(R.id.ai_apply).isEnabled());
                    first.performClick();
                    assertTrue(a.findViewById(R.id.ai_apply).isEnabled());
                    ((EditText) a.findViewById(R.id.ai_prompt)).setText("新的描述");
                    assertFalse(a.findViewById(R.id.ai_apply).isEnabled());
                });
                assertEquals(2, server.getRequestCount());
            } finally {
                singleton.set(null, previous);
                AiTaskRegistry.get().clearForAccount();
                api.close();
            }
        }
    }

    @Test public void leavingAndReopeningRestoresRunningRulesAndStopRejectsLateResult() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    return new MockResponse().setBody("{\"suggestions\":[{\"question\":\"如何拍摄\",\"answer\":\"保持稳定\"}]}");
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
            Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), AutoReplyEditActivity.class);
            intent.putExtra(AutoReplyEditActivity.EXTRA_POST_ID, "1");
            try {
                try (ActivityScenario<AutoReplyEditActivity> first = ActivityScenario.launch(intent)) {
                    first.onActivity(a -> {
                        ((EditText) a.findViewById(R.id.ai_prompt)).setText("拍摄规则");
                        a.findViewById(R.id.ai_generate).performClick();
                    });
                    assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS));
                }
                try (ActivityScenario<AutoReplyEditActivity> reopened = ActivityScenario.launch(intent)) {
                    reopened.onActivity(a -> {
                        assertEquals("拍摄规则", ((EditText) a.findViewById(R.id.ai_prompt)).getText().toString());
                        assertEquals(android.view.View.VISIBLE, a.findViewById(R.id.ai_rules_stop).getVisibility());
                        a.findViewById(R.id.ai_rules_stop).performClick();
                        assertFalse(a.findViewById(R.id.ai_apply).isEnabled());
                    });
                    release.countDown();
                    Thread.sleep(300);
                    reopened.onActivity(a -> {
                        assertEquals(0, ((LinearLayout) a.findViewById(R.id.ai_candidates)).getChildCount());
                        assertEquals(a.getString(R.string.ai_cancelled), ((android.widget.TextView)
                                a.findViewById(R.id.ai_rules_status)).getText().toString());
                    });
                    assertEquals(1, server.getRequestCount());
                }
            } finally {
                singleton.set(null, previous);
                AiTaskRegistry.get().clearForAccount();
                api.close();
            }
        }
    }

    @Test public void editedCandidateIsSavedOnlyAfterExplicitSelectionAndApply() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"suggestions\":[{\"question\":\"如何拍摄\",\"answer\":\"保持稳定\"}]}"));
            server.enqueue(new MockResponse().setBody("{\"created\":[{\"question\":\"如何拍摄\",\"answer\":\"使用三脚架\"}]}"));
            server.start();
            Field singleton = ApiClient.class.getDeclaredField("instance");
            singleton.setAccessible(true);
            ApiClient previous = (ApiClient) singleton.get(null);
            ApiClient api = new ApiClient(server.url("/").toString());
            api.restore("token", "refresh", "alice");
            singleton.set(null, api);
            AiTaskRegistry.get().clearForAccount();
            Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), AutoReplyEditActivity.class)
                    .putExtra(AutoReplyEditActivity.EXTRA_POST_ID, "1");
            try (ActivityScenario<AutoReplyEditActivity> screen = ActivityScenario.launch(intent)) {
                screen.onActivity(a -> {
                    ((EditText) a.findViewById(R.id.ai_prompt)).setText("拍摄规则");
                    a.findViewById(R.id.ai_generate).performClick();
                });
                awaitEnabled(screen);
                assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS));
                screen.onActivity(a -> {
                    assertFalse(a.findViewById(R.id.ai_apply).isEnabled());
                    ((LinearLayout) a.findViewById(R.id.ai_candidates)).getChildAt(1).performClick();
                });
                screen.onActivity(a -> {
                    android.view.View root = android.view.inspector.WindowInspector.getGlobalWindowViews().stream()
                            .filter(view -> findText(view, "保持稳定") != null).findFirst().orElseThrow(AssertionError::new);
                    ((EditText) findText(root, "保持稳定")).setText("使用三脚架");
                    root.findViewById(android.R.id.button1).performClick();
                });
                assertEquals(1, server.getRequestCount());
                screen.onActivity(a -> {
                    ((CheckBox) ((LinearLayout) a.findViewById(R.id.ai_candidates)).getChildAt(0)).performClick();
                    assertTrue(a.findViewById(R.id.ai_apply).isEnabled());
                    a.findViewById(R.id.ai_apply).performClick();
                });
                RecordedRequest save = server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(save);
                assertEquals("POST", save.getMethod());
                assertTrue(save.getPath().contains("auto-replies"));
                String body = save.getBody().readUtf8();
                assertTrue(body, body.contains("使用三脚架"));
                assertFalse(body, body.contains("保持稳定"));
            } finally {
                singleton.set(null, previous);
                AiTaskRegistry.get().clearForAccount();
                api.close();
            }
        }
    }

    private android.view.View findText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView
                && text.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void awaitEnabled(ActivityScenario<AutoReplyEditActivity> screen) throws Exception {
        for (int i = 0; i < 80; i++) {
            AtomicBoolean ready = new AtomicBoolean();
            screen.onActivity(a -> ready.set(a.findViewById(R.id.ai_generate).isEnabled()));
            if (ready.get()) return;
            Thread.sleep(50);
        }
        fail("Rule request did not finish");
    }
}
