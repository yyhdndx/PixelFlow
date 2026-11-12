package com.PixelFlow.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.AccountMedia;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.creation.DraftEditorModel;
import com.PixelFlow.app.creation.DraftEditorModel.MediaEntry;
import com.PixelFlow.app.DetailPage.FullScreenMediaDialog;
import androidx.lifecycle.ViewModelProvider;
import com.google.gson.Gson;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class CreateWorkActivity extends BaseActivity {
    public static final String EXTRA_POST_ID = "extra_post_id";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_CONTENT = "extra_content";
    public static final String EXTRA_MEDIA_IDS = "extra_media_ids";
    public static final String EXTRA_MEDIA_URLS = "extra_media_urls";
    /** 布尔：进入页面后立即拉起图片选择器（首页「从相册创建」） */
    public static final String EXTRA_AUTO_PICK = "extra_auto_pick";
    /** 字符串数组：进入页面时预置的本地图片 URI 列表（首页「拍摄新作品」） */
    public static final String EXTRA_INITIAL_URIS = "extra_initial_uris";

    private ApiClient api;
    private EditText etTitle;
    private EditText etDescription;
    private EditText etHashtags;
    private MaterialButton btnAddImage;
    private MaterialButton btnAddVideo;
    private LinearLayout mediaPreviewContainer;
    private Button btnSave;
    private Button btnPublish;
    private Button btnCancel;
    private TextView tvMediaCount;
    private TextView tvCharCount;
    private TextView tvTitle;

    private List<MediaEntry> media = new ArrayList<>();
    private DraftEditorModel model;
    private TextView operationStatus;
    private Button checkState, myWorks;
    private boolean binding;
    private android.app.Dialog assistDialog;
    private com.PixelFlow.app.API.CreationSuggestion previewShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_work);
        api = ApiClient.getInstance(this);
        model = new ViewModelProvider(this).get(DraftEditorModel.class);


        initView();
        setupListeners();
        loadEditingState(savedInstanceState);
        model.changes.observe(this, ignored -> render());
        applyInitialMedia();
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_AUTO_PICK, false)) {
            getIntent().removeExtra(EXTRA_AUTO_PICK);
            openImagePicker();
        }
    }

    private void initView() {
        etTitle = findViewById(R.id.et_title);
        etDescription = findViewById(R.id.et_description);
        etHashtags = findViewById(R.id.et_hashtags);
        btnAddImage = findViewById(R.id.btn_add_image);
        btnAddVideo = findViewById(R.id.btn_add_video);
        mediaPreviewContainer = findViewById(R.id.media_preview_container);
        tvMediaCount = findViewById(R.id.tv_media_count);
        tvCharCount = findViewById(R.id.tv_char_count);
        tvTitle = findViewById(R.id.create_title);
        btnSave = findViewById(R.id.btn_save);
        btnPublish = findViewById(R.id.btn_publish);
        btnCancel = findViewById(R.id.btn_cancel);
        findViewById(R.id.creation_assist).setOnClickListener(v -> showAssistRequest());
        findViewById(R.id.creation_assist_accept).setOnClickListener(v -> {
            captureText();
            model.confirmAssist();
        });
        findViewById(R.id.creation_assist_refine).setOnClickListener(v -> showRefineDialog());
        findViewById(R.id.creation_assist_cancel).setOnClickListener(v -> model.cancelAssist());
        findViewById(R.id.creation_assist_undo).setOnClickListener(v -> {
            captureText();
            if (model.state.assistUndo.needsConfirmation(model.state.title, model.state.content)) {
                new androidx.appcompat.app.AlertDialog.Builder(this, R.style.PixelFlowAppCompatDialog)
                        .setMessage(R.string.ai_creation_undo_confirm)
                        .setNegativeButton(R.string.ai_creation_cancel, null)
                        .setPositiveButton(R.string.ai_creation_undo, (dialog, which) -> undoAssist(true)).show();
            } else undoAssist(false);
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        operationStatus = findViewById(R.id.creation_status);
        checkState = findViewById(R.id.creation_retry);
        myWorks = findViewById(R.id.creation_my_works);
        checkState.setOnClickListener(v -> { if (!model.busy) model.reconcile(); });
        myWorks.setOnClickListener(v -> {
            if (model.busy) return;
            startActivity(new Intent(this, MyWorksActivity.class));
        });

        updateCharCount(0);
        updateMediaCount();
        updateSaveButtonState();
    }

    private void loadEditingState(Bundle saved) {
        if (model.state == null) {
            DraftEditorModel.State initial;
            boolean restored = saved != null && saved.containsKey("draft_editor");
            if (restored) initial = new Gson().fromJson(saved.getString("draft_editor"), DraftEditorModel.State.class);
            else {
                initial = new DraftEditorModel.State();
                initial.postId = getIntent().getStringExtra(EXTRA_POST_ID);
                initial.title = textExtra(EXTRA_TITLE);
                initial.content = textExtra(EXTRA_CONTENT);
                ArrayList<String> ids = getIntent().getStringArrayListExtra(EXTRA_MEDIA_IDS);
                ArrayList<String> urls = getIntent().getStringArrayListExtra(EXTRA_MEDIA_URLS);
                if (ids != null) for (int i = 0; i < ids.size(); i++)
                    initial.media.add(new MediaEntry(null, urls != null && i < urls.size() ? urls.get(i) : null, ids.get(i)));
                initial.needsReconcile = initial.postId != null;
                // 空白创作（未指定作品、未带初始图片/自动选图）时，恢复上次保存的草稿
                if (initial.postId == null
                        && !getIntent().getBooleanExtra(EXTRA_AUTO_PICK, false)
                        && getIntent().getStringArrayListExtra(EXTRA_INITIAL_URIS) == null) {
                    DraftEditorModel.State lastDraft = model.loadLastDraft();
                    if (lastDraft != null) initial = lastDraft;
                }
            }
            model.initialize(initial, restored);
        }
        media = model.state.media;
        binding = true;
        etTitle.setText(model.state.title);
        etDescription.setText(model.state.content);
        etHashtags.setText(model.state.tags);
        binding = false;
        render();
    }
    private String textExtra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }
    /** 话题按空白拆分，词首自动补单个 #（保留手动输入的 #，重复 # 归一为单 #）。 */
    private static String normalizeHashtags(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replaceAll("(^|\\s)(?=[^\\s#])", "$1#");
    }
    private void captureText() {
        if (binding || model.state == null) return;
        model.state.title = etTitle.getText().toString();
        model.state.content = etDescription.getText().toString();
        model.state.tags = etHashtags.getText().toString();
    }
    private void render() {
        if (model.state == null || isFinishing()) return;
        if (!model.ownsSession()) {
            clearMediaPreviews();
            finish();
            return;
        }
        if (model.state.done && model.ownsSession()) {
            Toast.makeText(this, model.state.message, Toast.LENGTH_LONG).show();
            finish(); return;
        }
        View copilotEntry = findViewById(R.id.copilot_entry);
        if (copilotEntry != null) copilotEntry.setOnClickListener(v -> {
            if (!com.PixelFlow.app.API.ApiClient.getInstance(this).isLoggedIn()) {
                startActivity(new Intent(this, com.PixelFlow.app.AccountActivity.class));
                return;
            }
            if (model.state.postId == null) {
                android.widget.Toast.makeText(this, R.string.copilot_need_draft, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            com.PixelFlow.app.copilot.CopilotSheet.open(this, "create", null, model.state.postId, 0, null,
                    tvTitle.getText().toString());
        });
        // Bind model changes atomically: watchers must not copy the old second field back.
        binding = true;
        try {
            if (!etTitle.getText().toString().equals(model.state.title)) etTitle.setText(model.state.title);
            if (!etDescription.getText().toString().equals(model.state.content)) etDescription.setText(model.state.content);
        } finally { binding = false; }
        tvTitle.setText(model.state.postId == null ? "创作作品" : "编辑作品");
        findViewById(R.id.creation_assist).setEnabled(model.editable() && !model.assistBusy);
        TextView assistStatus = findViewById(R.id.creation_assist_status);
        assistStatus.setText(model.assistMessage);
        assistStatus.setVisibility(model.assistMessage.isEmpty() ? View.GONE : View.VISIBLE);
        TextView assistStream = findViewById(R.id.creation_assist_stream);
        assistStream.setText(model.streamText);
        assistStream.setVisibility(model.assistBusy && !model.streamText.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.creation_assist_actions).setVisibility(
                model.state.assistPendingAcceptance ? View.VISIBLE : View.GONE);
        findViewById(R.id.creation_assist_accept).setEnabled(model.editable() && !model.assistBusy);
        findViewById(R.id.creation_assist_refine).setEnabled(
                model.editable() && !model.assistBusy && model.suggestion != null && !model.suggestionStale());
        findViewById(R.id.creation_assist_cancel).setVisibility(model.assistBusy ? View.VISIBLE : View.GONE);
        findViewById(R.id.creation_assist_undo).setEnabled(model.editable() && !model.assistBusy);
        refreshAssistPreview();
        operationStatus.setText(model.state.message);
        operationStatus.setVisibility(model.state.message.isEmpty() ? View.GONE : View.VISIBLE);
        checkState.setVisibility(model.state.needsReconcile && !model.busy ? View.VISIBLE : View.GONE);
        myWorks.setVisibility(model.state.creationUnknown || (!model.editable() && !model.busy) ? View.VISIBLE : View.GONE);
        myWorks.setEnabled(!model.busy);
        etTitle.setEnabled(model.editable());
        etDescription.setEnabled(model.editable());
        etHashtags.setEnabled(model.editable());
        btnAddImage.setEnabled(model.editable());
        btnCancel.setEnabled(!model.busy);
        updateMediaPreviews();
        updateMediaCount();
        updateSaveButtonState();
    }

    private void setupListeners() {
        etTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { captureText(); updateSaveButtonState(); }
        });

        etDescription.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCharCount(s.length());
            }
            @Override public void afterTextChanged(Editable s) { captureText(); updateSaveButtonState(); }
        });

        // 话题标签：按空格拆分后自动补 #，保留手动输入的 #（重复 # 归一为单 #）。
        etHashtags.addTextChangedListener(new TextWatcher() {
            private boolean normalizing = false;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (normalizing) return;
                String normalized = normalizeHashtags(s.toString());
                if (!normalized.equals(s.toString())) {
                    normalizing = true;
                    etHashtags.setText(normalized);
                    etHashtags.setSelection(normalized.length());
                    normalizing = false;
                }
                captureText();
            }
        });

        btnAddImage.setOnClickListener(v -> openImagePicker());
        btnAddVideo.setOnClickListener(v -> Toast.makeText(this, "暂不支持视频上传，仅支持图片", Toast.LENGTH_SHORT).show());

        btnSave.setOnClickListener(v -> submitWork(false));
        btnPublish.setOnClickListener(v -> submitWork(true));
        btnCancel.setOnClickListener(v -> onBackPressed());
    }

    private void updateCharCount(int count) {
        tvCharCount.setText(count + "/5000");
        if (count > 5000) tvCharCount.setTextColor(getResources().getColor(R.color.profile_error));
        else if (count > 4500) tvCharCount.setTextColor(getResources().getColor(R.color.profile_accent));
        else tvCharCount.setTextColor(getResources().getColor(R.color.profile_muted));
    }

    /** 组装一个圆角白底、宽度近贴边的 AI 弹窗（顶部对齐，键盘弹出也不重排） */
    private android.app.Dialog aiDialog(View content) {
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.AiAssistDialog);
        dialog.setContentView(content);
        dialog.setOnShowListener(d -> {
            android.view.Window w = dialog.getWindow();
            if (w == null) return;
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = getResources().getDisplayMetrics().widthPixels - dpToPx(44);
            lp.gravity = android.view.Gravity.TOP;
            lp.y = dpToPx(48);
            w.setAttributes(lp);
        });
        return dialog;
    }

    private static final int[] GOAL_CHIP_IDS = {
            R.id.chip_goal_all, R.id.chip_goal_title, R.id.chip_goal_content, R.id.chip_goal_write, R.id.chip_goal_tags };
    private static final String[] GOAL_KEYS =
            {"all", "improve_title", "polish_content", "write_content", "suggest_tags"};

    private void showAssistRequest() {
        if (!model.editable() || model.assistBusy) return;
        captureText();
        View view = getLayoutInflater().inflate(R.layout.dialog_ai_assist_request, null);
        com.google.android.material.chip.ChipGroup goals = view.findViewById(R.id.cg_goals);
        EditText prompt = view.findViewById(R.id.et_ai_prompt);
        prompt.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        previewShown = null;
        if (model.state.assistPrompt != null) prompt.setText(model.state.assistPrompt);
        for (int i = 0; i < GOAL_KEYS.length; i++)
            if (GOAL_KEYS[i].equals(model.state.assistGoal)) goals.check(GOAL_CHIP_IDS[i]);
        view.findViewById(R.id.btn_ai_cancel).setOnClickListener(v -> assistDialog.dismiss());
        view.findViewById(R.id.btn_ai_generate).setOnClickListener(v -> {
            int checked = goals.getCheckedChipId();
            String goal = GOAL_KEYS[0];
            for (int i = 0; i < GOAL_CHIP_IDS.length; i++)
                if (GOAL_CHIP_IDS[i] == checked) { goal = GOAL_KEYS[i]; break; }
            captureText();
            model.requestAssist(goal, prompt.getText().toString().trim(), "");
            if (model.assistBusy) assistDialog.dismiss();
            else prompt.setError(model.assistMessage);
        });
        assistDialog = aiDialog(view);
        assistDialog.show();
    }

    /** 继续修改：底部弹出输入框，输入新要求后基于当前结果继续生成。 */
    private void showRefineDialog() {
        captureText();
        if (model.suggestion == null || !model.editable()) return;
        EditText input = new EditText(this);
        input.setTextColor(getColor(R.color.profile_ink));
        input.setHintTextColor(getColor(R.color.profile_muted));
        input.setBackgroundTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.dialog_input_tint));
        input.setHint(R.string.ai_creation_revision_hint);
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        input.setText(model.state.assistPrompt == null ? "" : model.state.assistPrompt);
        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.PixelFlowAppCompatDialog)
                .setTitle(R.string.ai_creation_refine)
                .setView(input)
                .setNegativeButton(R.string.ai_creation_cancel, null)
                .setPositiveButton(R.string.ai_creation_generate, (dialog, which) -> {
                    captureText();
                    if (!model.refineAssist(input.getText().toString().trim()))
                        Toast.makeText(this, model.assistMessage, Toast.LENGTH_LONG).show();
                }).show();
    }

    /** 候选项卡片：同组点击单选；tag 为 null 表示「保留原文」 */
    private View candidateCard(LinearLayout parent, String label, String tag) {
        View card = getLayoutInflater().inflate(R.layout.item_ai_candidate, parent, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dpToPx(8));
        card.setLayoutParams(params);
        ((TextView) card.findViewById(R.id.candidate_text)).setText(label);
        card.setTag(tag);
        card.setOnClickListener(v -> {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                boolean on = child == v;
                child.setSelected(on);
                child.findViewById(R.id.candidate_dot).setSelected(on);
            }
        });
        return card;
    }

    private static String selectedCandidate(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.isSelected()) return (String) child.getTag();
        }
        return null;
    }

    /** 候选组：首项为默认选中的「保留原文」 */
    private void fillCandidates(LinearLayout container, List<String> candidates) {
        View keep = candidateCard(container, getString(R.string.ai_creation_keep_original), null);
        container.addView(keep);
        if (candidates != null) for (String candidate : candidates) container.addView(candidateCard(container, candidate, candidate));
        keep.setSelected(true);
        keep.findViewById(R.id.candidate_dot).setSelected(true);
    }

    private void showAssistPreview() {
        captureText();
        com.PixelFlow.app.API.CreationSuggestion value = model.suggestion;
        if (value == null || !model.editable()) return;
        View view = getLayoutInflater().inflate(R.layout.dialog_ai_assist_preview, null);
        boolean rules = "rules".equals(value.provider);
        ((TextView) view.findViewById(R.id.tv_preview_badge)).setText(
                rules ? R.string.ai_creation_badge_rules : R.string.ai_creation_badge_model);

        boolean low = "low".equals(value.safety.risk_level);
        TextView tvRisk = view.findViewById(R.id.tv_risk);
        tvRisk.setText(low ? R.string.ai_creation_risk_ok : R.string.ai_creation_risk_warn);
        tvRisk.setTextColor(getResources().getColor(low ? R.color.green_500 : R.color.profile_error));
        view.findViewById(R.id.v_risk_dot).setBackgroundResource(low ? R.drawable.bg_ai_dot_on : R.drawable.bg_ai_dot_warn);

        List<String> notes = new ArrayList<>();
        if (value.safety.suggestions != null) notes.addAll(value.safety.suggestions);
        if (value.edit_suggestions != null) notes.addAll(value.edit_suggestions);
        TextView tvNotes = view.findViewById(R.id.tv_ai_notes);
        if (notes.isEmpty()) tvNotes.setVisibility(View.GONE);
        else {
            tvNotes.setText(android.text.TextUtils.join("\n", notes));
            tvNotes.setVisibility(View.VISIBLE);
        }

        final LinearLayout titles = view.findViewById(R.id.container_titles);
        fillCandidates(titles, value.title_candidates);
        final LinearLayout copies = view.findViewById(R.id.container_copies);
        fillCandidates(copies, value.content_candidates);
        titles.setVisibility(value.title_candidates.isEmpty() ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.label_titles).setVisibility(titles.getVisibility());
        copies.setVisibility(value.content_candidates.isEmpty() ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.label_copies).setVisibility(copies.getVisibility());

        final com.google.android.material.chip.Chip chipTags = view.findViewById(R.id.chip_tags);
        boolean hasTags = value.tag_suggestions != null && !value.tag_suggestions.isEmpty();
        if (hasTags) {
            chipTags.setText(getString(R.string.ai_creation_section_tags) + "："
                    + android.text.TextUtils.join("  ", value.tag_suggestions));
        }
        chipTags.setVisibility(hasTags ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.tv_tags_label).setVisibility(hasTags ? View.VISIBLE : View.GONE);

        view.findViewById(R.id.btn_ai_edit).setOnClickListener(v -> assistDialog.dismiss());
        EditText revision = view.findViewById(R.id.et_ai_revision);
        view.findViewById(R.id.btn_ai_refine).setOnClickListener(v -> {
            captureText();
            if (model.refineAssist(revision.getText().toString())) assistDialog.dismiss();
            else revision.setError(model.assistMessage);
        });
        view.findViewById(R.id.btn_ai_refresh).setOnClickListener(v -> {
            assistDialog.dismiss(); showAssistRequest();
        });
        view.findViewById(R.id.btn_ai_copy).setOnClickListener(v -> {
            List<String> parts = new ArrayList<>();
            parts.addAll(value.title_candidates); parts.addAll(value.content_candidates); parts.addAll(value.tag_suggestions);
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI 创作建议", android.text.TextUtils.join("\n\n", parts)));
            Toast.makeText(this, "已复制建议", Toast.LENGTH_SHORT).show();
        });
        view.findViewById(R.id.btn_ai_apply).setOnClickListener(v -> {
            captureText();
            String pickedTitle = selectedCandidate(titles);
            String pickedCopy = selectedCandidate(copies);
            if (pickedTitle == null && pickedCopy == null && !chipTags.isChecked()) {
                Toast.makeText(this, R.string.ai_creation_pick_first, Toast.LENGTH_SHORT).show();
                return;
            }
            if (model.acceptAssist(pickedTitle, pickedCopy, chipTags.isChecked())) {
                binding = true;
                etTitle.setText(model.state.title);
                etDescription.setText(model.state.content);
                binding = false;
                updateSaveButtonState();
                assistDialog.dismiss();
            } else Toast.makeText(this, model.assistMessage, Toast.LENGTH_LONG).show();
        });
        assistDialog = aiDialog(view);
        previewShown = value;
        assistDialog.show();
        refreshAssistPreview();
    }

    private void refreshAssistPreview() {
        if (assistDialog == null || !assistDialog.isShowing() || previewShown == null) return;
        if (!model.ownsSession() || model.suggestion == null) { assistDialog.dismiss(); previewShown = null; return; }
        if (previewShown != model.suggestion) {
            assistDialog.dismiss(); showAssistPreview(); return;
        }
        boolean stale = model.suggestionStale();
        ((TextView) assistDialog.findViewById(R.id.ai_preview_status)).setText(stale
                ? getString(R.string.ai_assist_changed) : model.assistMessage);
        boolean enabled = model.editable() && !model.assistBusy && !stale;
        assistDialog.findViewById(R.id.btn_ai_apply).setEnabled(enabled);
        assistDialog.findViewById(R.id.btn_ai_refine).setEnabled(enabled);
        assistDialog.findViewById(R.id.et_ai_revision).setEnabled(enabled);
        assistDialog.findViewById(R.id.btn_ai_refresh).setVisibility(stale ? View.VISIBLE : View.GONE);
        assistDialog.findViewById(R.id.btn_ai_refresh).setEnabled(model.editable() && !model.assistBusy);
    }

    private void undoAssist(boolean confirmed) {
        captureText();
        if (!model.undoAssist(confirmed)) return;
        binding = true;
        etTitle.setText(model.state.title); etDescription.setText(model.state.content);
        binding = false;
        updateSaveButtonState();
    }

    private void updateMediaCount() {
        tvMediaCount.setText(media.size() + " 张图片");
        tvMediaCount.setTextColor(getResources().getColor(media.size() > 0 ? R.color.green_500 : R.color.gray_600));
    }

    private void updateSaveButtonState() {
        boolean hasTitle = !etTitle.getText().toString().trim().isEmpty();
        boolean hasContent = !etDescription.getText().toString().trim().isEmpty();
        boolean hasMedia = !media.isEmpty();
        boolean retry = model.state != null && model.retryable();
        boolean canSave = (hasTitle || hasContent || hasMedia) && model.state != null && (model.editable() || retry);
        // 提交审核：有标题即可点，缺图时在 submitWork 里给引导，避免禁用按钮让用户无从得知原因
        boolean canPublish = hasTitle && model.state != null && (model.editable() || retry);
        btnSave.setEnabled(canSave);
        btnPublish.setEnabled(canPublish);
        // 按钮外观由 bg_button_primary / bg_button_secondary 的启用状态选择器承载，不再用纯色覆盖圆角
    }

    private void openImagePicker() {
        if (!model.editable()) return;
        // 应用内相册选择页：不再走系统文件入口
        Intent intent = new Intent(this, MediaPickerActivity.class);
        intent.putExtra(MediaPickerActivity.EXTRA_MAX_COUNT, Math.max(1, 9 - media.size()));
        mediaPickerLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> mediaPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    List<String> uris = result.getData().getStringArrayListExtra(MediaPickerActivity.EXTRA_SELECTED_URIS);
                    if (uris != null) addSelectedUris(uris);
                }
            });

    /** 应用内选图页回传的 MediaStore 地址：并入草稿媒体区 */
    private void addSelectedUris(List<String> uris) {
        if (!model.editable()) return;
        for (String value : uris) {
            if (value == null) continue;
            Uri uri = Uri.parse(value);
            if (!containsUri(uri) && media.size() < 9) {
                media.add(new MediaEntry(uri.toString(), null, null));
            }
        }
        updateMediaPreviews();
        updateMediaCount();
        updateSaveButtonState();
    }

    /** 首页「拍摄新作品」带入的初始图片：并入草稿媒体区 */
    private void applyInitialMedia() {
        ArrayList<String> uris = getIntent().getStringArrayListExtra(EXTRA_INITIAL_URIS);
        if (uris == null || uris.isEmpty()) return;
        getIntent().removeExtra(EXTRA_INITIAL_URIS);
        if (!model.editable()) return;
        for (String value : uris) {
            if (value == null) continue;
            Uri uri = Uri.parse(value);
            if (!containsUri(uri) && media.size() < 9) {
                persistUri(uri);
                media.add(new MediaEntry(uri.toString(), null, null));
            }
        }
        updateMediaPreviews();
        updateMediaCount();
        updateSaveButtonState();
    }

    private void persistUri(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { /* Read errors are surfaced if the provider later revokes access. */ }
    }

    private boolean containsUri(Uri uri) {

        for (MediaEntry entry : media) {
            if (entry.uri != null && entry.uri.equals(uri.toString())) return true;
        }
        return false;
    }

    private void updateMediaPreviews() {
        clearMediaPreviews();
        mediaPreviewContainer.removeAllViews();
        int maxPreviews = Math.min(media.size(), 3);
        for (int i = 0; i < maxPreviews; i++) {
            addMediaPreview(media.get(i), i);
        }
        if (media.size() > 3) {
            addMoreIndicator(media.size() - 3);
        }
    }

    private void addMediaPreview(MediaEntry entry, int index) {
        MaterialCardView cardView = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(80), dpToPx(80));
        params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        cardView.setLayoutParams(params);
        cardView.setRadius(dpToPx(12));
        cardView.setCardElevation(dpToPx(2));

        ImageView imageView = new ImageView(this);
        imageView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (entry.mediaId != null) {
            AccountMedia.load(imageView, entry.url);
        } else {
            com.bumptech.glide.Glide.with(this).load(Uri.parse(entry.uri))
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_error).centerCrop().into(imageView);
        }
        imageView.setOnClickListener(v -> openFullScreen(entry));

        View deleteButton = new View(this);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dpToPx(20), dpToPx(20));
        deleteParams.setMargins(dpToPx(60), dpToPx(4), 0, 0);
        deleteButton.setLayoutParams(deleteParams);
        deleteButton.setBackgroundResource(R.drawable.ic_delete_circle);
        deleteButton.setOnClickListener(v -> {
            if (!model.editable()) return;
            media.remove(index);
            updateMediaPreviews();
            updateMediaCount();
            updateSaveButtonState();
        });

        cardView.addView(imageView);
        cardView.addView(deleteButton);
        mediaPreviewContainer.addView(cardView);
    }

    /** 点击已上传图片：全屏查看；私有媒体带鉴权取字节，本地 URI 直接加载。 */
    private void openFullScreen(MediaEntry entry) {
        if (entry.mediaId != null && entry.url != null) {
            api.getMediaContent(entry.url, new CommunityClient.Callback<byte[]>() {
                @Override public void onSuccess(byte[] bytes) {
                    new FullScreenMediaDialog(CreateWorkActivity.this, bytes).show();
                }
                @Override public void onFailure(String message) {
                    Toast.makeText(CreateWorkActivity.this, "图片加载失败", Toast.LENGTH_SHORT).show();
                }
            });
        } else if (entry.uri != null) {
            new FullScreenMediaDialog(CreateWorkActivity.this, entry.uri).show();
        }
    }

    private void addMoreIndicator(int moreCount) {
        MaterialCardView cardView = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(80), dpToPx(80));
        params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        cardView.setLayoutParams(params);
        cardView.setRadius(dpToPx(12));
        cardView.setCardElevation(dpToPx(2));
        cardView.setCardBackgroundColor(getResources().getColor(R.color.gray_200));

        TextView textView = new TextView(this);
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        textView.setText("+" + moreCount);
        textView.setTextSize(16);
        textView.setTextColor(getResources().getColor(R.color.gray_600));
        textView.setGravity(View.TEXT_ALIGNMENT_CENTER);

        cardView.addView(textView);
        mediaPreviewContainer.addView(cardView);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void submitWork(boolean publishMode) {
        if (model.busy || (!model.editable() && !model.retryable())) return;
        String title = etTitle.getText().toString().trim();
        String content = etDescription.getText().toString().trim();
        if (title.length() > 120) { Toast.makeText(this, "标题最多 120 字", Toast.LENGTH_SHORT).show(); return; }
        if (content.length() > 5000) { Toast.makeText(this, "正文最多 5000 字", Toast.LENGTH_SHORT).show(); return; }
        if (publishMode && title.isEmpty()) { Toast.makeText(this, "提交审核需要标题", Toast.LENGTH_SHORT).show(); return; }
        if (publishMode && media.isEmpty()) {
            Toast.makeText(this, "提交审核需要先添加至少一张图片，点下方「添加图片」", Toast.LENGTH_LONG).show();
            return;
        }
        if (media.isEmpty() && title.isEmpty() && content.isEmpty()) {
            Toast.makeText(this, "请填写标题或内容，或添加图片", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!api.isLoggedIn()) {
            startActivity(new Intent(this, AccountActivity.class));
            return;
        }
        captureText();
        model.submit(publishMode);
    }

    private void clearMediaPreviews() {
        for (int i = 0; i < mediaPreviewContainer.getChildCount(); i++) {
            View card = mediaPreviewContainer.getChildAt(i);
            if (card instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) card;
                for (int j = 0; j < group.getChildCount(); j++)
                    if (group.getChildAt(j) instanceof ImageView) AccountMedia.clear((ImageView) group.getChildAt(j));
            }
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        captureText();
        model.checkpoint();
        out.putString("draft_editor", new Gson().toJson(model.state));
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume();
        if (!model.ownsSession()) {
            clearMediaPreviews();
            Toast.makeText(this, "账号已变化，请重新打开创作页", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        model.resume();
        render();
    }
    @Override protected void onStop() {
        captureText();
        model.checkpoint();
        clearMediaPreviews();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (model.busy) {
            Toast.makeText(this, "正在上传，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        String title = etTitle.getText().toString().trim();
        String description = etDescription.getText().toString().trim();
        if (!title.isEmpty() || !description.isEmpty() || !media.isEmpty()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.PixelFlowMaterialDialog)
                    .setTitle("放弃编辑")
                    .setMessage("您有未保存的内容，确定要退出吗？")
                    .setPositiveButton("确定", (dialog, which) -> { model.discard(); finish(); })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (assistDialog != null) assistDialog.dismiss();
        clearMediaPreviews();
        super.onDestroy();
    }
}
