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
import com.PixelFlow.app.creation.DraftEditorModel;
import com.PixelFlow.app.creation.DraftEditorModel.MediaEntry;
import androidx.lifecycle.ViewModelProvider;
import com.google.gson.Gson;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class CreateWorkActivity extends AppCompatActivity {
    public static final String EXTRA_POST_ID = "extra_post_id";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_CONTENT = "extra_content";
    public static final String EXTRA_MEDIA_IDS = "extra_media_ids";
    public static final String EXTRA_MEDIA_URLS = "extra_media_urls";

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
    private androidx.appcompat.app.AlertDialog assistDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_work);
        api = ApiClient.getInstance(this);
        model = new ViewModelProvider(this).get(DraftEditorModel.class);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        initView();
        setupListeners();
        loadEditingState(savedInstanceState);
        model.changes.observe(this, ignored -> render());
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
        findViewById(R.id.creation_assist_preview).setOnClickListener(v -> showAssistPreview());
        findViewById(R.id.creation_assist_cancel).setOnClickListener(v -> model.cancelAssist());

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
        tvTitle.setText(model.state.postId == null ? "创作作品" : "编辑作品");
        findViewById(R.id.creation_assist).setEnabled(model.editable() && !model.assistBusy);
        TextView assistStatus = findViewById(R.id.creation_assist_status);
        assistStatus.setText(model.assistMessage);
        assistStatus.setVisibility(model.assistMessage.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.creation_assist_preview).setVisibility(model.suggestion == null ? View.GONE : View.VISIBLE);
        findViewById(R.id.creation_assist_cancel).setVisibility(model.assistBusy ? View.VISIBLE : View.GONE);
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

        // 话题标签：后端尚未实现标签提取，这里保留输入但不参与提交。
        etHashtags.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0 && s.charAt(s.length() - 1) == ' ') {
                    String current = s.toString();
                    if (!current.endsWith(" #") && !current.endsWith("  ")) {
                        etHashtags.setText(current + "#");
                        etHashtags.setSelection(etHashtags.getText().length());
                    }
                }
            }
            @Override public void afterTextChanged(Editable s) { captureText(); }
        });

        btnAddImage.setOnClickListener(v -> openImagePicker());
        btnAddVideo.setOnClickListener(v -> Toast.makeText(this, "暂不支持视频上传，仅支持图片", Toast.LENGTH_SHORT).show());

        btnSave.setOnClickListener(v -> submitWork(false));
        btnPublish.setOnClickListener(v -> submitWork(true));
        btnCancel.setOnClickListener(v -> onBackPressed());
    }

    private void updateCharCount(int count) {
        tvCharCount.setText(count + "/5000");
        if (count > 5000) tvCharCount.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        else if (count > 4500) tvCharCount.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
        else tvCharCount.setTextColor(getResources().getColor(R.color.gray_600));
    }

    private LinearLayout assistPanel() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(20); panel.setPadding(padding, padding, padding, padding);
        return panel;
    }
    private void showAssistRequest() {
        if (!model.editable() || model.assistBusy) return;
        captureText();
        LinearLayout panel = assistPanel();
        android.widget.Spinner goal = new android.widget.Spinner(this);
        goal.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"综合优化", "优化标题", "润色正文", "根据主题写文案", "推荐话题"}));
        panel.addView(goal);
        EditText prompt = new EditText(this); prompt.setHint("主题或写作要求（可选）");
        prompt.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)}); panel.addView(prompt);
        EditText audience = new EditText(this); audience.setHint("目标读者（可选）");
        audience.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)}); panel.addView(audience);
        assistDialog = new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("AI 辅助创作")
                .setMessage("将草稿文字和写作要求发送给 DeepSeek，生成后由你选择是否采用。")
                .setView(panel).setNegativeButton("取消", null).setPositiveButton("生成建议", (d,w) -> {
                    captureText();
                    model.requestAssist(new String[]{"all","improve_title","polish_content","write_content","suggest_tags"}[goal.getSelectedItemPosition()],
                            prompt.getText().toString().trim(), audience.getText().toString().trim());
                }).create();
        assistDialog.show();
    }
    private android.widget.RadioGroup candidateChoices(LinearLayout panel, String label, List<String> candidates) {
        TextView heading = new TextView(this); heading.setText(label); panel.addView(heading);
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        android.widget.RadioButton keep = new android.widget.RadioButton(this); keep.setId(View.generateViewId());
        keep.setText("保留原文"); group.addView(keep); group.check(keep.getId());
        for (String candidate : candidates) {
            android.widget.RadioButton option = new android.widget.RadioButton(this);
            option.setId(View.generateViewId()); option.setText(candidate); option.setTag(candidate); group.addView(option);
        }
        panel.addView(group); return group;
    }
    private String selectedCandidate(android.widget.RadioGroup group) {
        View selected = group.findViewById(group.getCheckedRadioButtonId());
        return selected == null ? null : (String) selected.getTag();
    }
    private void showAssistPreview() {
        com.PixelFlow.app.API.CreationSuggestion value = model.suggestion;
        if (value == null || !model.editable()) return;
        LinearLayout panel = assistPanel();
        TextView notes = new TextView(this);
        String risk = "low".equals(value.safety.risk_level) ? "未发现规则风险" : "请留意内容风险";
        notes.setText(risk + "\n" + android.text.TextUtils.join("\n", value.safety.suggestions)
                + "\n" + android.text.TextUtils.join("\n", value.edit_suggestions));
        panel.addView(notes);
        android.widget.RadioGroup titles = candidateChoices(panel, "标题", value.title_candidates);
        android.widget.RadioGroup copies = candidateChoices(panel, "正文", value.content_candidates);
        android.widget.CheckBox tags = new android.widget.CheckBox(this);
        tags.setText("将话题加入正文：" + android.text.TextUtils.join(" ", value.tag_suggestions));
        tags.setVisibility(value.tag_suggestions.isEmpty() ? View.GONE : View.VISIBLE); panel.addView(tags);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(panel);
        assistDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("rules".equals(value.provider) ? "规则建议（未启用模型）" : "DeepSeek 建议")
                .setView(scroll).setNegativeButton("继续编辑", null).setPositiveButton("确认填入草稿", null).create();
        assistDialog.setOnShowListener(d -> assistDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            captureText();
            if (selectedCandidate(titles) == null && selectedCandidate(copies) == null && !tags.isChecked()) {
                Toast.makeText(this, "请先选择要采用的建议", Toast.LENGTH_SHORT).show(); return;
            }
            if (model.acceptAssist(selectedCandidate(titles), selectedCandidate(copies), tags.isChecked())) {
                binding = true; etTitle.setText(model.state.title); etDescription.setText(model.state.content); binding = false;
                updateSaveButtonState(); assistDialog.dismiss();
            } else Toast.makeText(this, model.assistMessage, Toast.LENGTH_LONG).show();
        }));
        assistDialog.show();
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
        boolean canPublish = hasTitle && hasMedia && model.state != null && (model.editable() || retry);
        btnSave.setEnabled(canSave);
        btnPublish.setEnabled(canPublish);
        btnSave.setBackgroundColor(getResources().getColor(canSave ? R.color.primary_color : R.color.gray_400));
        btnPublish.setBackgroundColor(getResources().getColor(canPublish ? R.color.red_500 : R.color.gray_400));
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        if (!model.editable()) return;
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        imagePickerLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleSelectedMedia(result.getData());
                }
            });

    private void handleSelectedMedia(Intent data) {
        if (!model.editable()) return;
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (!containsUri(uri) && media.size() < 9) { persistUri(uri); media.add(new MediaEntry(uri.toString(), null, null)); }
            }
        } else if (data.getData() != null) {
            Uri uri = data.getData();
            if (!containsUri(uri) && media.size() < 9) { persistUri(uri); media.add(new MediaEntry(uri.toString(), null, null)); }
        }
        if (media.size() > 9) {
            media.subList(9, media.size()).clear();
            Toast.makeText(this, "最多 9 张图片", Toast.LENGTH_SHORT).show();
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
        cardView.setRadius(dpToPx(8));
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

    private void addMoreIndicator(int moreCount) {
        MaterialCardView cardView = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(80), dpToPx(80));
        params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        cardView.setLayoutParams(params);
        cardView.setRadius(dpToPx(8));
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
        if (publishMode && media.isEmpty()) { Toast.makeText(this, "提交审核需要至少一张图片", Toast.LENGTH_SHORT).show(); return; }
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
            new androidx.appcompat.app.AlertDialog.Builder(this)
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
