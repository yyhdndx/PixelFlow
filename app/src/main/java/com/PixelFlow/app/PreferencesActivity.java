package com.PixelFlow.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PreferenceSnapshot;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Recommendation preferences: personalized toggle, suppressed topics and blocked authors (remove only). */
public class PreferencesActivity extends AccountDataActivity {
    private View loading, retry, content;
    private TextView status;
    private SwitchMaterial personalized;
    private LinearLayout topics, authors;
    private float density;
    private boolean rendering;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_preferences);
        density = getResources().getDisplayMetrics().density;
        findViewById(R.id.pref_back).setOnClickListener(v -> finish());
        loading = findViewById(R.id.pref_loading);
        status = findViewById(R.id.pref_status);
        retry = findViewById(R.id.pref_retry);
        content = findViewById(R.id.pref_content);
        personalized = findViewById(R.id.pref_switch);
        topics = findViewById(R.id.pref_topics);
        authors = findViewById(R.id.pref_authors);
        retry.setOnClickListener(v -> load());
        personalized.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            setPersonalized(checked);
        });
    }

    @Override protected void reloadPage() { load(); }

    @Override protected void clearPage() {
        content.setVisibility(View.GONE);
        topics.removeAllViews(); authors.removeAllViews();
        rendering = true;
        personalized.setChecked(false);
        rendering = false;
        loading.setVisibility(View.GONE);
        status.setVisibility(View.GONE); retry.setVisibility(View.GONE);
    }

    @Override protected void setActionsEnabled(boolean enabled) {
        if (content != null) enableChildren(content, enabled);
    }

    private void enableChildren(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enableChildren(group.getChildAt(i), enabled);
        }
    }

    private void load() {
        if (!canLoad()) return;
        final int generation = beginRead();
        content.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE); retry.setVisibility(View.GONE);
        job = api.getPreferences(new CommunityClient.Callback<PreferenceSnapshot>() {
            public void onSuccess(PreferenceSnapshot snapshot) {
                if (!alive(generation)) return;
                loading.setVisibility(View.GONE);
                apply(snapshot);
                readSucceeded();
            }
            public void onFailure(String message) {
                if (!alive(generation)) return;
                loading.setVisibility(View.GONE);
                status.setText(message); status.setVisibility(View.VISIBLE); retry.setVisibility(View.VISIBLE);
            }
        });
    }

    private void apply(PreferenceSnapshot snapshot) {
        rendering = true;
        personalized.setChecked(snapshot.isPersonalized());
        rendering = false;
        content.setVisibility(View.VISIBLE);
        renderTopics(snapshot);
        renderAuthors(snapshot);
    }

    private void renderTopics(PreferenceSnapshot snapshot) {
        topics.removeAllViews();
        if (snapshot.getSuppressedTopics() == null || snapshot.getSuppressedTopics().isEmpty()) {
            topics.addView(emptyLine(getString(R.string.preferences_topics_empty)));
            return;
        }
        for (String topic : snapshot.getSuppressedTopics())
            addRow(topics, topic, getString(R.string.preferences_remove), v -> setSuppressed(topic, false));
    }

    private void renderAuthors(PreferenceSnapshot snapshot) {
        authors.removeAllViews();
        if (snapshot.getBlockedAuthorIds() == null || snapshot.getBlockedAuthorIds().isEmpty()) {
            authors.addView(emptyLine(getString(R.string.preferences_authors_empty)));
            return;
        }
        for (String authorId : snapshot.getBlockedAuthorIds())
            addRow(authors, authorId, getString(R.string.preferences_unblock), v -> setBlocked(authorId, false));
    }

    private TextView emptyLine(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(getColor(R.color.profile_muted));
        int pad = (int)(8 * density);
        view.setPadding(0, pad, 0, pad);
        return view;
    }

    private void addRow(LinearLayout parent, String label, String actionText, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int)(8 * density);
        row.setPadding(0, pad, 0, pad);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(14);
        name.setTextColor(getColor(R.color.profile_ink));
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView actionView = new TextView(this);
        actionView.setText(actionText);
        actionView.setTextSize(14);
        actionView.setTextColor(getColor(R.color.profile_accent));
        actionView.setPadding((int)(16 * density), (int)(4 * density), 0, (int)(4 * density));
        android.util.TypedValue out = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        actionView.setBackgroundResource(out.resourceId);
        actionView.setOnClickListener(action);
        row.addView(actionView);

        parent.addView(row);
    }

    private void setPersonalized(boolean value) {
        if (!beginWrite()) return;
        api.setPersonalized(value, writeResult(null));
    }

    private void setSuppressed(String topic, boolean suppressed) {
        if (!beginWrite()) return;
        api.setSuppressedTopic(topic, suppressed, writeResult(null));
    }

    private void setBlocked(String authorId, boolean blocked) {
        if (!beginWrite()) return;
        api.setBlockedAuthor(authorId, blocked, writeResult(null));
    }
}
