package com.PixelFlow.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.AutoReplyRule;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.Streams.AutoReplyAdapter;
import java.util.List;

/**
 * 作品作者配置「场景 → 答案」：Agent 判断一级评论是否属于该场景，命中才以作者身份自动回复。
 * 答案逐字使用这里填写的内容，模型不生成新文案。
 */
public class AutoReplyActivity extends AppCompatActivity {
    public static final String EXTRA_POST_ID = "auto_reply_post_id";

    private String postId;
    private AutoReplyAdapter adapter;
    private ProgressBar loading;
    private TextView empty;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        postId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_POST_ID);
        if (postId == null || !postId.matches("[1-9][0-9]{0,18}")) { finish(); return; }
        setContentView(R.layout.activity_auto_reply);
        findViewById(R.id.auto_back).setOnClickListener(v -> finish());
        findViewById(R.id.auto_add).setOnClickListener(v -> showRuleDialog(null));
        loading = findViewById(R.id.auto_loading);
        empty = findViewById(R.id.auto_empty);
        adapter = new AutoReplyAdapter();
        adapter.setListener(new AutoReplyAdapter.Listener() {
            @Override public void onToggle(AutoReplyRule rule, boolean enabled) { update(rule, rule.question, rule.answer, enabled); }
            @Override public void onDelete(AutoReplyRule rule) { confirmDelete(rule); }
            @Override public void onEdit(AutoReplyRule rule) { showRuleDialog(rule); }
        });
        RecyclerView list = findViewById(R.id.auto_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        reload();
    }

    private void reload() {
        loading.setVisibility(View.VISIBLE);
        ApiClient.getInstance(this).getAutoReplies(postId, new CommunityClient.Callback<List<AutoReplyRule>>() {
            @Override public void onSuccess(List<AutoReplyRule> rules) {
                loading.setVisibility(View.GONE);
                adapter.setData(rules);
                empty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onFailure(String message) {
                loading.setVisibility(View.GONE);
                toast(message);
            }
        });
    }

    /** rule 为 null 表示新增，否则编辑。 */
    private void showRuleDialog(AutoReplyRule rule) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);

        // AI 提炼区：带着这篇作品的上下文，把一句话拆成多条「场景 → 答案」
        TextView aiTitle = new TextView(this);
        aiTitle.setText(R.string.auto_reply_ai_title);
        aiTitle.setTextSize(13);
        aiTitle.setTextColor(getResources().getColor(R.color.primary_color));
        aiTitle.setPadding(0, 0, 0, dp(4));
        EditText aiPrompt = new EditText(this);
        aiPrompt.setHint(R.string.auto_reply_ai_hint);
        aiPrompt.setMaxLines(3);
        Button aiGenerate = new Button(this);
        aiGenerate.setText(R.string.auto_reply_ai_generate);
        LinearLayout aiResults = new LinearLayout(this);
        aiResults.setOrientation(LinearLayout.VERTICAL);

        if (rule == null) {
            box.addView(aiTitle);
            box.addView(aiPrompt, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(aiGenerate, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(aiResults);
            TextView manualHint = new TextView(this);
            manualHint.setText(R.string.auto_reply_manual_hint);
            manualHint.setTextSize(12);
            manualHint.setTextColor(getResources().getColor(R.color.profile_muted));
            manualHint.setPadding(0, dp(8), 0, dp(4));
            box.addView(manualHint);
        }

        EditText question = new EditText(this);
        question.setHint(R.string.auto_reply_question_hint);
        question.setMaxLines(3);
        if (rule != null) question.setText(rule.question);
        EditText answer = new EditText(this);
        answer.setHint(R.string.auto_reply_answer_hint);
        answer.setMaxLines(4);
        if (rule != null) answer.setText(rule.answer);
        box.addView(question, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(answer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(rule == null ? R.string.auto_reply_add : R.string.auto_reply_title)
                .setView(box)
                .setNegativeButton(R.string.auto_reply_cancel, null)
                .setPositiveButton(R.string.auto_reply_save, null)
                .create();

        aiGenerate.setOnClickListener(v -> {
            String prompt = aiPrompt.getText() == null ? "" : aiPrompt.getText().toString().trim();
            if (prompt.isEmpty()) { toast("先用一句话描述你想自动回复什么"); return; }
            aiGenerate.setEnabled(false);
            aiGenerate.setText(R.string.auto_reply_ai_generating);
            ApiClient.getInstance(this).suggestAutoReplies(postId, prompt,
                    new CommunityClient.Callback<List<AutoReplyRule>>() {
                        @Override public void onSuccess(List<AutoReplyRule> suggestions) {
                            aiGenerate.setEnabled(true);
                            aiGenerate.setText(R.string.auto_reply_ai_generate);
                            renderSuggestions(aiResults, suggestions, question, answer);
                        }
                        @Override public void onFailure(String message) {
                            aiGenerate.setEnabled(true);
                            aiGenerate.setText(R.string.auto_reply_ai_generate);
                            toast(message);
                        }
                    });
        });

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String q = question.getText() == null ? "" : question.getText().toString().trim();
            String a = answer.getText() == null ? "" : answer.getText().toString().trim();
            if (q.isEmpty() || a.isEmpty()) { toast("场景和回复内容都要填写"); return; }
            dialog.dismiss();
            if (rule == null) create(q, a);
            else update(rule, q, a, rule.enabled);
        }));
        dialog.show();
    }

    /** AI 候选：点一条就填进上面的场景/回复输入框，作者可以再改。 */
    private void renderSuggestions(LinearLayout container, List<AutoReplyRule> suggestions,
                                   EditText question, EditText answer) {
        container.removeAllViews();
        if (suggestions == null || suggestions.isEmpty()) {
            toast("AI 没有提炼出规则，把想法说得更具体些试试");
            return;
        }
        for (AutoReplyRule item : suggestions) {
            TextView row = new TextView(this);
            row.setText(getString(R.string.auto_reply_ai_candidate, item.question, item.answer));
            row.setTextSize(13);
            row.setPadding(0, dp(8), 0, dp(8));
            row.setTextColor(getResources().getColor(R.color.primary_color));
            row.setOnClickListener(v -> {
                question.setText(item.question);
                answer.setText(item.answer);
                toast("已填入，可继续修改后保存");
            });
            container.addView(row);
        }
    }

    private void create(String question, String answer) {
        ApiClient.getInstance(this).createAutoReply(postId, question, answer,
                new CommunityClient.Callback<AutoReplyRule>() {
                    @Override public void onSuccess(AutoReplyRule value) { reload(); }
                    @Override public void onFailure(String message) { toast(message); }
                });
    }

    private void update(AutoReplyRule rule, String question, String answer, boolean enabled) {
        ApiClient.getInstance(this).updateAutoReply(postId, rule.id, question, answer, enabled,
                new CommunityClient.Callback<AutoReplyRule>() {
                    @Override public void onSuccess(AutoReplyRule value) { reload(); }
                    @Override public void onFailure(String message) { toast(message); reload(); }
                });
    }

    private void confirmDelete(AutoReplyRule rule) {
        new AlertDialog.Builder(this)
                .setMessage("删除这条自动回复规则？")
                .setNegativeButton(R.string.auto_reply_cancel, null)
                .setPositiveButton(R.string.auto_reply_delete, (d, w) ->
                        ApiClient.getInstance(this).deleteAutoReply(postId, rule.id, new CommunityClient.Callback<Void>() {
                            @Override public void onSuccess(Void value) { reload(); }
                            @Override public void onFailure(String message) { toast(message); }
                        }))
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
