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
