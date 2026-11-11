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
import com.PixelFlow.app.API.AiTaskRegistry;
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
    private AiTaskRegistry.Task suggestTask;
    private AiTaskRegistry.Subscription suggestSubscription;

    @Override protected void onResume() {
        super.onResume();
        if (suggestTask != null && !AiTaskRegistry.get().isCurrent(suggestTask)) {
            if (suggestSubscription != null) suggestSubscription.close();
            suggestTask = null;
            renderedRules = null;
            candidateList.clear();
            candidates.removeAllViews();
            selectRow.setVisibility(View.GONE);
            aiApply.setEnabled(false);
            aiLoading.setVisibility(View.GONE);
            findViewById(R.id.ai_rules_stop).setVisibility(View.GONE);
            findViewById(R.id.ai_rules_status).setVisibility(View.GONE);
            aiGenerate.setEnabled(true);
        }
    }

    @Override protected void onDestroy() {
        if (suggestSubscription != null) suggestSubscription.close();
        super.onDestroy();
    }
    private String suggestKey;
    private String candidatePrompt;
    private List<AutoReplyRule> renderedRules;

    private static final class Candidate {
        AutoReplyRule rule;
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
            findViewById(R.id.ai_rules_stop).setOnClickListener(v -> {
                if (suggestKey != null) AiTaskRegistry.get().cancel(suggestKey);
            });
            selectAll.setOnClickListener(v -> {
                boolean checked = selectAll.isChecked();
                for (Candidate item : candidateList) item.box.setChecked(checked);
                updateSelection();
            });
            aiApply.setOnClickListener(v -> applySelected());
            if (savedInstanceState != null && savedInstanceState.containsKey("ai_prompt_snapshot")) {
                String restored = savedInstanceState.getString("ai_prompt_snapshot");
                aiPrompt.setText(savedInstanceState.getString("ai_current_prompt", restored));
                String key = AiTaskRegistry.identity(ApiClient.getInstance(this), "rules", postId, restored);
                AiTaskRegistry.Task existing = AiTaskRegistry.get().peek(key);
                if (existing != null) attachSuggestions(existing, restored);
            }
            if (suggestTask == null) {
                AiTaskRegistry.Task existing = AiTaskRegistry.get().latest(ApiClient.getInstance(this), "rules", postId);
                if (existing != null) {
                    String prompt = com.google.gson.JsonParser.parseString(existing.key).getAsJsonArray()
                            .get(4).getAsJsonArray().get(1).getAsString();
                    aiPrompt.setText(prompt);
                    attachSuggestions(existing, prompt);
                }
            }
            aiPrompt.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                    aiGenerate.setEnabled(suggestTask == null || !suggestTask.isRunning()
                            || !text.toString().trim().equals(candidatePrompt));
                    updateSelection();
                }
                public void afterTextChanged(android.text.Editable text) { }
            });
        }
    }

    private void attachSuggestions(AiTaskRegistry.Task task, String prompt) {
        suggestTask = task;
        candidatePrompt = prompt;
        suggestKey = AiTaskRegistry.identity(ApiClient.getInstance(this), "rules", postId, prompt);
        if (suggestSubscription != null) suggestSubscription.close();
        suggestSubscription = AiTaskRegistry.get().subscribe(task, this::runOnUiThread, value -> {
            if (isDestroyed() || suggestTask != value) return;
            boolean sameInput = prompt.equals(aiPrompt.getText().toString().trim());
            aiGenerate.setEnabled(!value.isRunning() || !sameInput);
            aiLoading.setVisibility(value.isRunning() ? View.VISIBLE : View.GONE);
            findViewById(R.id.ai_rules_stop).setVisibility(value.isRunning() ? View.VISIBLE : View.GONE);
            TextView status = findViewById(R.id.ai_rules_status);
            status.setText(value.error != null ? value.error : getString(
                    value.state == AiTaskRegistry.State.EXPIRED ? R.string.ai_expired
                    : value.state == AiTaskRegistry.State.CANCELLED ? R.string.ai_cancelled
                    : value.state == AiTaskRegistry.State.WAITING ? R.string.ai_waiting
                    : value.isRunning() ? R.string.ai_preparing : R.string.ai_assist_ready));
            status.setVisibility(View.VISIBLE);
            if (!sameInput) {
                updateSelection();
                return;
            }
            if (value.result instanceof List && value.result != renderedRules) {
                @SuppressWarnings("unchecked") List<AutoReplyRule> rules = (List<AutoReplyRule>) value.result;
                renderedRules = rules;
                renderCandidates(rules);
            } else if (value.error != null) {
                toast(value.error);
            }
            updateSelection();
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (suggestTask != null) {
            state.putString("ai_prompt_snapshot", candidatePrompt);
            state.putString("ai_current_prompt", aiPrompt.getText().toString().trim());
        }
    }

    private void generate() {
        String prompt = aiPrompt.getText() == null ? "" : aiPrompt.getText().toString().trim();
        if (prompt.isEmpty()) { toast(getString(R.string.auto_reply_ai_need_prompt)); return; }
        CommunityClient api = ApiClient.getInstance(this);
        String nextKey = AiTaskRegistry.identity(api, "rules", postId, prompt);
        if (suggestKey != null && !suggestKey.equals(nextKey)) AiTaskRegistry.get().cancel(suggestKey);
        suggestKey = nextKey;
        AiTaskRegistry.Task previous = AiTaskRegistry.get().peek(suggestKey);
        if (previous != null && !previous.isRunning() && previous.state != AiTaskRegistry.State.COMPLETED
                && previous.state != AiTaskRegistry.State.NEW) AiTaskRegistry.get().remove(suggestKey);
        candidatePrompt = prompt;
        renderedRules = null;
        candidates.removeAllViews();
        candidateList.clear();
        selectRow.setVisibility(View.GONE);
        aiApply.setEnabled(false);
        AiTaskRegistry.Task task;
        try { task = AiTaskRegistry.get().getOrCreate(suggestKey); }
        catch (IllegalStateException full) { toast(full.getMessage()); return; }
        attachSuggestions(task, prompt);
        String target = postId;
        AiTaskRegistry.get().start(task, callback -> api.suggestAutoReplies(target, prompt,
                com.PixelFlow.app.API.AiCallbacks.forward(callback)));
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
            if (rule == null || rule.question.trim().isEmpty() || rule.answer.trim().isEmpty()
                    || rule.question.length() > 500 || rule.answer.length() > 2000) continue;
            CheckBox box = new CheckBox(this);
            box.setButtonDrawable(R.drawable.notification_checkbox);
            box.setButtonTintList(null);
            box.setText(getString(R.string.auto_reply_ai_candidate, rule.question, rule.answer));
            box.setTextSize(13);
            box.setPadding(0, dp(6), 0, dp(6));
            box.setOnClickListener(v -> updateSelection());
            candidates.addView(box, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            Candidate candidate = new Candidate(rule, box);
            candidateList.add(candidate);
            Button edit = new Button(this);
            int paddingLeft = edit.getPaddingLeft(), paddingTop = edit.getPaddingTop();
            int paddingRight = edit.getPaddingRight(), paddingBottom = edit.getPaddingBottom();
            edit.setBackgroundResource(R.drawable.auto_reply_edit_button);
            edit.setBackgroundTintList(null);
            edit.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
            edit.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this, R.color.dialog_button_primary));
            edit.setAllCaps(false);
            edit.setText(R.string.ai_rule_edit);
            edit.setOnClickListener(view -> editCandidate(candidate));
            candidates.addView(edit);
        }
        selectAll.setChecked(false);
        selectRow.setVisibility(View.VISIBLE);
        updateSelection();
    }

    private void editCandidate(Candidate candidate) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText q = new EditText(this);
        q.setTextColor(getColor(R.color.profile_ink));
        q.setHintTextColor(getColor(R.color.profile_muted));
        q.setBackgroundTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.dialog_input_tint));
        q.setText(candidate.rule.question);
        q.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});
        EditText a = new EditText(this);
        a.setTextColor(getColor(R.color.profile_ink));
        a.setHintTextColor(getColor(R.color.profile_muted));
        a.setBackgroundTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.dialog_input_tint));
        a.setText(candidate.rule.answer);
        a.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        form.addView(q);
        form.addView(a);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this, R.style.PixelFlowPlatformDialog)
                .setTitle(R.string.ai_rule_edit).setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (q.getText().toString().trim().isEmpty() || a.getText().toString().trim().isEmpty()) {
                toast(getString(R.string.auto_reply_need_both));
                return;
            }
            com.google.gson.JsonObject fields = new com.google.gson.JsonObject();
            fields.addProperty("question", q.getText().toString().trim());
            fields.addProperty("answer", a.getText().toString().trim());
            candidate.rule = AutoReplyRule.parse(fields);
            candidate.box.setText(getString(R.string.auto_reply_ai_candidate, candidate.rule.question, candidate.rule.answer));
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void updateSelection() {
        int selected = 0;
        for (Candidate item : candidateList) if (item.box.isChecked()) selected++;
        aiApply.setEnabled(selected > 0 && suggestTask != null
                && suggestTask.state == AiTaskRegistry.State.COMPLETED
                && candidatePrompt != null && candidatePrompt.equals(aiPrompt.getText().toString().trim()));
        selectedCount.setText(getString(R.string.auto_reply_selected_count, selected, candidateList.size()));
        selectAll.setChecked(selected > 0 && selected == candidateList.size());
    }

    private void applySelected() {
        if (candidatePrompt == null || !candidatePrompt.equals(aiPrompt.getText().toString().trim())
                || suggestTask == null || suggestTask.state != AiTaskRegistry.State.COMPLETED) {
            toast(getString(R.string.ai_rules_input_changed));
            return;
        }
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

    private boolean manualSaving;
    private void saveManual() {
        if (manualSaving) return;
        String q = question.getText() == null ? "" : question.getText().toString().trim();
        String a = answer.getText() == null ? "" : answer.getText().toString().trim();
        if (q.isEmpty() || a.isEmpty()) { toast(getString(R.string.auto_reply_need_both)); return; }
        manualSaving = true;
        findViewById(R.id.edit_save).setEnabled(false);
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
            @Override public void onFailure(String message) {
                manualSaving = false;
                if (isFinishing() || isDestroyed()) return;
                findViewById(R.id.edit_save).setEnabled(true);
                toast(message);
            }
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
