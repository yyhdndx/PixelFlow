package com.PixelFlow.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.PixelFlow.app.API.*;
import com.google.gson.*;

public class InterestProfileActivity extends AppCompatActivity {
    private ApiClient api;
    private CommunityClient.Job job;
    private LinearLayout topics;
    private TextView status;
    private View loading, retry;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_interest_profile);
        api = ApiClient.getInstance(this); topics = findViewById(R.id.insights_topics); status = findViewById(R.id.insights_status);
        loading = findViewById(R.id.insights_loading); retry = findViewById(R.id.insights_retry);
        findViewById(R.id.insights_back).setOnClickListener(v -> finish()); retry.setOnClickListener(v -> load());
        findViewById(R.id.insights_history).setOnClickListener(v -> startActivity(new Intent(this, HistoryProfileActivity.class)));
        findViewById(R.id.insights_preferences).setOnClickListener(v -> startActivity(new Intent(this, PreferencesActivity.class)));
    }
    @Override protected void onResume() { super.onResume(); load(); }
    private void load() {
        if (job != null) job.cancel();
        topics.removeAllViews(); topics.setVisibility(View.GONE); status.setVisibility(View.GONE); retry.setVisibility(View.GONE);
        if (!api.isLoggedIn()) { status.setText("请登录后查看兴趣画像"); status.setVisibility(View.VISIBLE); loading.setVisibility(View.GONE); return; }
        loading.setVisibility(View.VISIBLE);
        job = api.getProfile(new CommunityClient.Callback<JsonObject>() {
            public void onSuccess(JsonObject value) {
                if (isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.GONE); topics.setVisibility(View.VISIBLE);
                JsonArray entries = value.getAsJsonArray("topics");
                if (entries.size() == 0) { line(getString(R.string.insights_empty), true); line(getString(R.string.insights_empty_desc), false); }
                else {
                    line("最近的兴趣", false);
                    for (JsonElement item : entries) line(item.getAsJsonObject().get("name").getAsString(), true);
                }
            }
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.GONE); status.setText(message); status.setVisibility(View.VISIBLE); retry.setVisibility(View.VISIBLE);
            }
        });
    }
    private void line(String text, boolean emphasized) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(emphasized ? 19 : 14);
        view.setTextColor(getColor(emphasized ? R.color.profile_ink : R.color.profile_muted));
        int padding = (int)(12 * getResources().getDisplayMetrics().density); view.setPadding(0, padding, 0, padding);
        topics.addView(view);
    }
    @Override protected void onStop() { if (job != null) job.cancel(); super.onStop(); }
}
