package com.PixelFlow.app.API;

import android.content.Intent;
import android.widget.EditText;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.CreateWorkActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.creation.DraftEditorModel;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CreationAssistFlowTest {
    MockWebServer server;
    ApiClient api, previous;
    Field singleton;
    ActivityScenario<CreateWorkActivity> screen;
    static final String RESPONSE="{\"suggestion_id\":\"id\",\"provider\":\"deepseek\",\"title_candidates\":[\"新标题\"],\"content_candidates\":[\"新正文\"],\"tag_suggestions\":[\"#咖啡\"],\"edit_suggestions\":[\"突出主题\"],\"safety\":{\"risk_level\":\"low\",\"suggestions\":[],\"requires_human_review\":false}}";
    DraftEditorModel model(CreateWorkActivity a) { return new ViewModelProvider(a).get(DraftEditorModel.class); }
    @Before public void setup() throws Exception {
        server=new MockWebServer(); server.start();
        api=new ApiClient(server.url("/").toString()); api.restore("alice-token","refresh","alice");
        singleton=ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous=(ApiClient)singleton.get(null); singleton.set(null,api);
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),CreateWorkActivity.class);
        intent.putExtra(CreateWorkActivity.EXTRA_TITLE,"原标题"); intent.putExtra(CreateWorkActivity.EXTRA_CONTENT,"原正文");
        screen=ActivityScenario.launch(intent);
    }
    @After public void cleanup() throws Exception {
        if(screen!=null) screen.close(); singleton.set(null,previous); api.close(); server.shutdown();
    }
    void request(MockResponse response) {
        server.enqueue(response);
        screen.onActivity(a -> model(a).requestAssist("all","咖啡","同学"));
    }
    void awaitDone() throws Exception {
        for(int i=0;i<100;i++) {
            AtomicBoolean done=new AtomicBoolean(); screen.onActivity(a -> done.set(!model(a).assistBusy));
            if(done.get()) return; Thread.sleep(50);
        }
        fail("Assistance did not finish");
    }
    @Test public void previewSurvivesRecreationAndOnlyConfirmationChangesDraft() throws Exception {
        request(new MockResponse().setBody(RESPONSE)); awaitDone();
        screen.onActivity(a -> { assertEquals("原正文",model(a).state.content); assertNotNull(model(a).suggestion); });
        screen.recreate();
        screen.onActivity(a -> {
            assertNotNull(model(a).suggestion);
            assertTrue(model(a).acceptAssist("新标题","新正文",true));
            assertEquals("新标题",model(a).state.title);
            assertEquals("新正文\n#咖啡",model(a).state.content);
        });
        assertEquals(1,server.getRequestCount()); // No create, edit or publish request until save.
    }
    @Test public void editedDraftRejectsLateResult() throws Exception {
        request(new MockResponse().setBody(RESPONSE).setBodyDelay(400,TimeUnit.MILLISECONDS));
        screen.onActivity(a -> ((EditText)a.findViewById(R.id.et_description)).setText("用户后续编辑"));
        awaitDone();
        screen.onActivity(a -> { assertNull(model(a).suggestion); assertEquals("用户后续编辑",model(a).state.content); });
    }
    @Test public void editingAfterPreviewRejectsAcceptance() throws Exception {
        request(new MockResponse().setBody(RESPONSE)); awaitDone();
        screen.onActivity(a -> {
            ((EditText)a.findViewById(R.id.et_description)).setText("新输入");
            assertFalse(model(a).acceptAssist("新标题","新正文",false));
            assertEquals("新输入",model(a).state.content);
        });
    }
    @Test public void failurePreservesOriginalAndAllowsRetry() throws Exception {
        request(new MockResponse().setResponseCode(503)); awaitDone();
        screen.onActivity(a -> { assertEquals("原正文",model(a).state.content); assertTrue(model(a).editable()); });
        request(new MockResponse().setBody(RESPONSE)); awaitDone();
        screen.onActivity(a -> assertNotNull(model(a).suggestion));
    }
    @Test public void skipDiscardsLateResponse() throws Exception {
        request(new MockResponse().setBody(RESPONSE).setBodyDelay(400,TimeUnit.MILLISECONDS));
        screen.onActivity(a -> model(a).cancelAssist()); Thread.sleep(600);
        screen.onActivity(a -> { assertNull(model(a).suggestion); assertFalse(model(a).assistBusy); assertEquals("原正文",model(a).state.content); });
    }
    @Test public void previewDialogAppliesSelectedCandidateToVisibleEditor() throws Exception {
        request(new MockResponse().setBody(RESPONSE)); awaitDone();
        screen.onActivity(a -> a.findViewById(R.id.creation_assist_preview).performClick());
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("新标题"))
                .perform(androidx.test.espresso.action.ViewActions.click());
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("确认填入草稿"))
                .perform(androidx.test.espresso.action.ViewActions.click());
        screen.onActivity(a -> {
            assertEquals("新标题",((EditText)a.findViewById(R.id.et_title)).getText().toString());
            assertEquals("原正文",((EditText)a.findViewById(R.id.et_description)).getText().toString());
        });
        assertEquals(1,server.getRequestCount());
    }
}
