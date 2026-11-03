package com.PixelFlow.app;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.AutoReplyRule;
import com.PixelFlow.app.API.CommunityClient;
import java.util.ArrayList;
import java.util.List;

/**
 * 新增/编辑一条自动回复规则。新增时可在同一页用 AI 把一句话拆成多条候选，
 * 勾选（可全选）后一次「应用选中」批量创建；也可以只手动填一条保存。
 */
public class AutoReplyEditActivity extends AppCompatActivity {
    public static final String EXTRA_POST_ID = "auto_reply_post_id";
    public static final String EXTRA_RULE_ID = "auto_reply_rule_id";
    public static final String EXTRA_QUESTION = "auto_reply_question";
    public static final String EXTRA_ANSWER = "auto_reply_answer";

    private String postId;
    private String ruleId;
    private EditText question;
    private EditText answer;
    private EditText aiPrompt;
    private Button aiGenerate;
    private Button aiApply;
    private ProgressBar aiLoading;
    private LinearLayout candidates;
    private View aiBlock;
    private View selectRow;
    private CheckBox selectAll;
    private TextView selectedCount;

    private final List<Candidate> candidateList = new ArrayList<>();

    private static final class Candidate {
        final AutoReplyRule rule;
        final CheckBox box;
        Candidate(AutoReplyRule rule, CheckBox box) { this.rule = rule; this.box = box; }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        postId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_POST_ID);
        if (postId == null || !postId.matches("[1-9][0-9]{0,18}")) { finish(); return; }
        ruleId = getIntent().getStringExtra(EXTRA_RULE_ID);
        setContentView(R.layout.activity_auto_reply_edit);

        findViewById(R.id.edit_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.edit_title))
                .setText(ruleId == null ? R.string.auto_reply_add : R.string.auto_reply_edit_title);
        findViewById(R.id.edit_save).setOnClickListener(v -> saveManual());

        question = findViewById(R.id.rule_question);
        answer = findViewById(R.id.rule_answer);
        aiPrompt = findViewById(R.id.ai_prompt);
        aiGenerate = findViewById(R.id.ai_generate);
        aiApply = findViewById(R.id.ai_apply);
        aiLoading = findViewById(R.id.ai_loading);
        candidates = findViewById(R.id.ai_candidates);
        aiBlock = findViewById(R.id.ai_block);
        selectRow = findViewById(R.id.ai_select_row);
        selectAll = findViewById(R.id.ai_select_all);
        selectedCount = findViewById(R.id.ai_selected_count);

        if (ruleId != null) {
            // 编辑已有规则时不显示 AI 区，避免误以为是改写这条
            aiBlock.setVisibility(View.GONE);
            ((TextView) findViewById(R.id.manual_title)).setText(R.string.auto_reply_title);
            question.setText(getIntent().getStringExtra(EXTRA_QUESTION));
            answer.setText(getIntent().getStringExtra(EXTRA_ANSWER));
        } else {
            aiGenerate.setOnClickListener(v -> generate());
            selectAll.setOnClickListener(v -> {
                boolean checked = selectAll.isChecked();
                for (Candidate item : candidateList) item.box.setChecked(checked);
                updateSelection();
            });
            aiApply.setOnClickListener(v -> applySelected());
        }
    }

    private void generate() {
        String prompt = aiPrompt.getText() == null ? "" : aiPrompt.getText().toString().trim();
        if (prompt.isEmpty()) { toast(getString(R.string.auto_reply_ai_need_prompt)); return; }
        aiGenerate.setEnabled(false);
        aiLoading.setVisibility(View.VISIBLE);
        ApiClient.getInstance(this).suggestAutoReplies(postId, prompt,
                new CommunityClient.Callback<List<AutoReplyRule>>() {
                    @Override public void onSuccess(List<AutoReplyRule> suggestions) {
                        aiGenerate.setEnabled(true);
                        aiLoading.setVisibility(View.GONE);
                        renderCandidates(suggestions);
                    }
                    @Override public void onFailure(String message) {
                        aiGenerate.setEnabled(true);
                        aiLoading.setVisibility(View.GONE);
                        toast(message);
                    }
                });
    }

    /** 候选列表：每条一个复选框，默认不勾选，交给作者决定。 */
    private void renderCandidates(List<AutoReplyRule> suggestions) {
        candidates.removeAllViews();
        candidateList.clear();
        if (suggestions == null || suggestions.isEmpty()) {
            selectRow.setVisibility(View.GONE);
            toast(getString(R.string.auto_reply_ai_empty));
            return;
        }
        for (AutoReplyRule rule : suggestions) {
            CheckBox box = new CheckBox(this);
            box.setText(getString(R.string.auto_reply_ai_candidate, rule.question, rule.answer));
            box.setTextSize(13);
            box.setPadding(0, dp(6), 0, dp(6));
            box.setOnClickListener(v -> updateSelection());
            candidates.addView(box, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            candidateList.add(new Candidate(rule, box));
        }
        selectAll.setChecked(false);
        selectRow.setVisibility(View.VISIBLE);
        updateSelection();
    }

    private void updateSelection() {
        int selected = 0;
        for (Candidate item : candidateList) if (item.box.isChecked()) selected++;
        aiApply.setEnabled(selected > 0);
        selectedCount.setText(getString(R.string.auto_reply_selected_count, selected, candidateList.size()));
        selectAll.setChecked(selected > 0 && selected == candidateList.size());
    }

    private void applySelected() {
        List<AutoReplyRule> selected = new ArrayList<>();
        for (Candidate item : candidateList) if (item.box.isChecked()) selected.add(item.rule);
        if (selected.isEmpty()) { toast(getString(R.string.auto_reply_apply_empty)); return; }
        aiApply.setEnabled(false);
        AiApiHolder.api(this).batchCreateAutoReplies(postId, selected,
                new CommunityClient.Callback<List<AutoReplyRule>>() {
                    @Override public void onSuccess(List<AutoReplyRule> created) {
                        toast(getString(R.string.auto_reply_applied, created.size()));
                        setResult(RESULT_OK);
                        finish();
                    }
                    @Override public void onFailure(String message) {
                        aiApply.setEnabled(true);
                        toast(message);
                    }
                });
    }

    private void saveManual() {
        String q = question.getText() == null ? "" : question.getText().toString().trim();
        String a = answer.getText() == null ? "" : answer.getText().toString().trim();
        if (q.isEmpty() || a.isEmpty()) { toast(getString(R.string.auto_reply_need_both)); return; }
        if (ruleId == null) {
            AiApiHolder.api(this).createAutoReply(postId, q, a, doneCallback());
        } else {
            AiApiHolder.api(this).updateAutoReply(postId, ruleId, q, a, true, doneCallback());
        }
    }

    private CommunityClient.Callback<AutoReplyRule> doneCallback() {
        return new CommunityClient.Callback<AutoReplyRule>() {
            @Override public void onSuccess(AutoReplyRule value) {
                setResult(RESULT_OK);
                finish();
            }
            @Override public void onFailure(String message) { toast(message); }
        };
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    /** 简单持有 ApiClient，避免在每个回调里重复取。 */
    private static final class AiApiHolder {
        static ApiClient api(AutoReplyEditActivity activity) { return ApiClient.getInstance(activity); }
    }
}
