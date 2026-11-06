package com.PixelFlow.app.API;

import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.R;
import com.PixelFlow.app.SearchActivity;
import com.google.gson.*;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

@RunWith(AndroidJUnit4.class)
public class GatewaySearchStreamingTest {
    @Test public void controlledStreamReachesAndroidBeforeCompletionThroughRealGateway() throws Exception {
        String base = InstrumentationRegistry.getArguments().getString("gatewayBaseUrl");
        assumeNotNull(base);
        OkHttpClient http = new OkHttpClient();
        String name = "gw" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String credentials = "{\"username\":\"" + name + "\",\"password\":\"issue27-test-password\",\"nickname\":\"网关联调\"}";
        try (Response response = http.newCall(new Request.Builder().url(base + "/api/auth/register")
                .post(RequestBody.create(credentials, MediaType.parse("application/json"))).build()).execute()) {
            assertEquals(201, response.code());
        }
        JsonObject login;
        try (Response response = http.newCall(new Request.Builder().url(base + "/api/auth/login")
                .post(RequestBody.create("{\"username\":\"" + name + "\",\"password\":\"issue27-test-password\"}", MediaType.parse("application/json"))).build()).execute()) {
            assertEquals(200, response.code());
            login = new Gson().fromJson(response.body().string(), JsonObject.class);
        }
        Field singleton = ApiClient.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        ApiClient previous = (ApiClient) singleton.get(null);
        ApiClient api = new ApiClient(base);
        api.restore(login.get("accessToken").getAsString(), login.get("refreshToken").getAsString(), name);
        singleton.set(null, api);
        AiTaskRegistry.get().clearForAccount();
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "issue27");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), SearchActivity.class);
        long started = System.nanoTime();
        try (ActivityScenario<SearchActivity> screen = ActivityScenario.launch(intent)) {
            screen.onActivity(a -> {
                ((EditText) a.findViewById(R.id.et_search)).setText("可控流验证");
                a.findViewById(R.id.btn_submit).performClick();
            });
            await(screen, false);
            long first = System.nanoTime();
            capture(new File(directory, "gateway-search-partial.png"));
            await(screen, true);
            long completed = System.nanoTime();
            assertTrue(completed - first >= java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
            capture(new File(directory, "gateway-search-completed.png"));
            JsonObject result = new JsonObject();
            result.addProperty("first_ms", (first - started) / 1_000_000.0);
            result.addProperty("total_ms", (completed - started) / 1_000_000.0);
            result.addProperty("real_model", false);
            result.addProperty("route", "Android -> Nginx -> Java -> controlled HTTP upstream");
            try (FileOutputStream output = new FileOutputStream(new File(directory, "gateway-android-result.json"))) {
                output.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            singleton.set(null, previous);
            AiTaskRegistry.get().clearForAccount();
            api.close();
        }
    }

    private void await(ActivityScenario<SearchActivity> screen, boolean complete) throws Exception {
        for (int i = 0; i < 200; i++) {
            AtomicBoolean ready = new AtomicBoolean();
            screen.onActivity(a -> ready.set(((TextView) a.findViewById(R.id.ai_answer)).getText().toString().equals("可控流首段")
                    && (!complete || a.findViewById(R.id.ai_stop).getVisibility() == android.view.View.GONE)));
            if (ready.get()) return;
            Thread.sleep(50);
        }
        fail("Gateway stream did not render expected state");
    }

    private void capture(File file) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
}
