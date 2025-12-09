package com.PixelFlow.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.core.content.ContextCompat;

import com.PixelFlow.app.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class CreateWorkActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SINGLE_IMAGE = 100;
    private static final int REQUEST_CODE_MULTIPLE_IMAGES = 101;

    private EditText etTitle;
    private EditText etDescription;
    private EditText etHashtags;
    private MaterialButton btnAddImage;
    private MaterialButton btnAddVideo;
    private LinearLayout mediaPreviewContainer;
    private Button btnSave;
    private Button btnCancel;
    private TextView tvMediaCount;
    private TextView tvCharCount;

    private List<Uri> selectedMediaUris = new ArrayList<>();
    private boolean isVideoMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_work);

        initView();
        setupListeners();
    }

    private void initView() {
        // 标题栏
        etTitle = findViewById(R.id.et_title);
        etDescription = findViewById(R.id.et_description);
        etHashtags = findViewById(R.id.et_hashtags);
        btnAddImage = findViewById(R.id.btn_add_image);
        btnAddVideo = findViewById(R.id.btn_add_video);
        mediaPreviewContainer = findViewById(R.id.media_preview_container);
        tvMediaCount = findViewById(R.id.tv_media_count);
        tvCharCount = findViewById(R.id.tv_char_count);
        btnSave = findViewById(R.id.btn_save);
        btnCancel = findViewById(R.id.btn_cancel);

        // 设置返回按钮
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 初始化字符计数
        updateCharCount(0);
        updateMediaCount();
    }

    private void setupListeners() {
        // 标题输入监听
        etTitle.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateSaveButtonState();
            }
        });

        // 描述输入监听
        etDescription.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCharCount(s.length());
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSaveButtonState();
            }
        });

        // 话题输入监听
        etHashtags.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0 && s.charAt(s.length() - 1) == ' ') {
                    // 自动添加#号
                    String current = s.toString();
                    if (!current.endsWith(" #") && !current.endsWith("  ")) {
                        etHashtags.setText(current + "#");
                        etHashtags.setSelection(etHashtags.getText().length());
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 添加图片按钮
        btnAddImage.setOnClickListener(v -> {
            isVideoMode = false;
            if (checkStoragePermission()) {
                openImagePicker();
            } else {
                requestStoragePermission();
            }
        });

        // 添加视频按钮
        btnAddVideo.setOnClickListener(v -> {
            isVideoMode = true;
            if (checkStoragePermission()) {
                openVideoPicker();
            } else {
                requestStoragePermission();
            }
        });

        // 保存按钮
        btnSave.setOnClickListener(v -> saveWork());

        // 取消按钮
        btnCancel.setOnClickListener(v -> finish());
    }

    private void updateCharCount(int count) {
        tvCharCount.setText(count + "/500");

        if (count > 500) {
            tvCharCount.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        } else if (count > 450) {
            tvCharCount.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
        } else {
            tvCharCount.setTextColor(getResources().getColor(R.color.gray_600));
        }
    }

    private void updateMediaCount() {
        int count = selectedMediaUris.size();
        tvMediaCount.setText(count + "个文件");

        if (count > 0) {
            tvMediaCount.setTextColor(getResources().getColor(R.color.green_500));
        } else {
            tvMediaCount.setTextColor(getResources().getColor(R.color.gray_600));
        }
    }

    private void updateSaveButtonState() {
        boolean hasTitle = !etTitle.getText().toString().trim().isEmpty();
        boolean hasDescription = !etDescription.getText().toString().trim().isEmpty();
        boolean hasMedia = !selectedMediaUris.isEmpty();

        // 简化：只要有标题就可以保存，或者有媒体文件也可以
        btnSave.setEnabled(hasTitle || hasMedia);

        if (btnSave.isEnabled()) {
            btnSave.setBackgroundColor(getResources().getColor(R.color.red_500));
        } else {
            btnSave.setBackgroundColor(getResources().getColor(R.color.gray_400));
        }
    }

    private boolean checkStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    REQUEST_CODE_SINGLE_IMAGE);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_CODE_SINGLE_IMAGE);
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        imagePickerLauncher.launch(intent);
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        videoPickerLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleSelectedMedia(result.getData());
                }
            });

    private final ActivityResultLauncher<Intent> videoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleSelectedMedia(result.getData());
                }
            });

    private void handleSelectedMedia(Intent data) {
        if (data.getClipData() != null) {
            // 多选
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri mediaUri = data.getClipData().getItemAt(i).getUri();
                if (!selectedMediaUris.contains(mediaUri)) {
                    selectedMediaUris.add(mediaUri);
                }
            }
        } else if (data.getData() != null) {
            // 单选
            Uri mediaUri = data.getData();
            if (!selectedMediaUris.contains(mediaUri)) {
                selectedMediaUris.add(mediaUri);
            }
        }

        updateMediaPreviews();
        updateMediaCount();
        updateSaveButtonState();
    }

    private void updateMediaPreviews() {
        mediaPreviewContainer.removeAllViews();

        int maxPreviews = Math.min(selectedMediaUris.size(), 3); // 最多显示3个预览

        for (int i = 0; i < maxPreviews; i++) {
            Uri mediaUri = selectedMediaUris.get(i);
            addMediaPreview(mediaUri, i);
        }

        if (selectedMediaUris.size() > 3) {
            addMoreIndicator(selectedMediaUris.size() - 3);
        }
    }

    private void addMediaPreview(Uri mediaUri, int index) {
        MaterialCardView cardView = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dpToPx(80), dpToPx(80));
        params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        cardView.setLayoutParams(params);
        cardView.setRadius(dpToPx(8));
        cardView.setCardElevation(dpToPx(2));

        ImageView imageView = new ImageView(this);
        imageView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        // 这里应该使用图片加载库加载图片
        // Glide.with(this).load(mediaUri).into(imageView);
        // 临时使用占位图
        if (isVideoMode) {
            imageView.setImageResource(R.drawable.ic_video_placeholder);
        } else {
            imageView.setImageResource(R.drawable.ic_image_placeholder);
        }

        // 删除按钮
        View deleteButton = new View(this);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dpToPx(20), dpToPx(20));
        deleteParams.setMargins(dpToPx(60), dpToPx(4), 0, 0);
        deleteButton.setLayoutParams(deleteParams);
        deleteButton.setBackgroundResource(R.drawable.ic_delete_circle);
        deleteButton.setOnClickListener(v -> {
            selectedMediaUris.remove(index);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dpToPx(80), dpToPx(80));
        params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        cardView.setLayoutParams(params);
        cardView.setRadius(dpToPx(8));
        cardView.setCardElevation(dpToPx(2));
        cardView.setCardBackgroundColor(getResources().getColor(R.color.gray_200));

        TextView textView = new TextView(this);
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
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

    private void saveWork() {
        String title = etTitle.getText().toString().trim();
        String description = etDescription.getText().toString().trim();
        String hashtags = etHashtags.getText().toString().trim();

        // 这里应该将作品保存到服务器或本地数据库
        // 现在只是象征性的展示

        // 验证输入
        if (title.isEmpty() && description.isEmpty() && selectedMediaUris.isEmpty()) {
            Toast.makeText(this, "请至少填写标题、描述或添加媒体文件", Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示保存中
        btnSave.setText("保存中...");
        btnSave.setEnabled(false);

        // 模拟网络请求延迟
        new android.os.Handler().postDelayed(() -> {
            // 保存成功
            Toast.makeText(CreateWorkActivity.this, "作品创建成功！", Toast.LENGTH_SHORT).show();

            // 返回结果到MainActivity（如果需要）
            Intent resultIntent = new Intent();
            resultIntent.putExtra("work_created", true);
            setResult(RESULT_OK, resultIntent);

            finish();
        }, 1500);
    }

    @Override
    public void onBackPressed() {
        // 检查是否有未保存的内容
        String title = etTitle.getText().toString().trim();
        String description = etDescription.getText().toString().trim();

        if (!title.isEmpty() || !description.isEmpty() || !selectedMediaUris.isEmpty()) {
            // 显示确认对话框
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("放弃编辑")
                    .setMessage("您有未保存的内容，确定要退出吗？")
                    .setPositiveButton("确定", (dialog, which) -> finish())
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理资源
        selectedMediaUris.clear();
    }
}