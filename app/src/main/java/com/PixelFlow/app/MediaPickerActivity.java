package com.PixelFlow.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用内相册选择页：替代系统文件入口，提供与产品一致的选图体验。
 * 进入时申请一次相册读取权限（Android 13+ 为 READ_MEDIA_IMAGES），
 * 网格多选、数字角标、最多可选数量限制，确认后通过 EXTRA_SELECTED_URIS 回传。
 */
public class MediaPickerActivity extends AppCompatActivity {

    public static final String EXTRA_MAX_COUNT = "extra_max_count";
    public static final String EXTRA_SELECTED_URIS = "extra_selected_uris";

    private static final int DEFAULT_MAX_COUNT = 9;
    private static final int GRID_SPAN = 3;

    private final List<String> allUris = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private int maxCount = DEFAULT_MAX_COUNT;
    private PickerAdapter adapter;
    private TextView btnDone;
    private RecyclerView recycler;
    private LinearLayout emptyView;
    private TextView emptyTitle;
    private TextView emptyAction;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) loadImages();
                else showPermissionDenied();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_picker);

        maxCount = getIntent().getIntExtra(EXTRA_MAX_COUNT, DEFAULT_MAX_COUNT);
        if (maxCount <= 0) maxCount = DEFAULT_MAX_COUNT;

        ImageButton btnBack = findViewById(R.id.picker_back);
        btnBack.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.picker_title);
        title.setText("选择图片");
        btnDone = findViewById(R.id.picker_done);
        btnDone.setOnClickListener(v -> confirm());
        updateDoneButton();

        recycler = findViewById(R.id.picker_recycler);
        emptyView = findViewById(R.id.picker_empty);
        emptyTitle = findViewById(R.id.picker_empty_title);
        emptyAction = findViewById(R.id.picker_empty_action);

        adapter = new PickerAdapter();
        recycler.setLayoutManager(new GridLayoutManager(this, GRID_SPAN));
        recycler.setAdapter(adapter);

        ensurePermissionThenLoad();
    }

    private String requiredPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void ensurePermissionThenLoad() {
        if (ContextCompat.checkSelfPermission(this, requiredPermission())
                == PackageManager.PERMISSION_GRANTED) {
            loadImages();
        } else {
            permissionLauncher.launch(requiredPermission());
        }
    }

    /** 查询系统媒体库，按加入时间倒序。放在后台线程，避免大图库卡顿。 */
    private void loadImages() {
        emptyView.setVisibility(View.GONE);
        recycler.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<String> uris = new ArrayList<>();
            String[] projection = {MediaStore.Images.Media._ID};
            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                                .appendPath(String.valueOf(id)).build().toString());
                    }
                }
            }
            List<String> result = uris;
            runOnUiThread(() -> onImagesLoaded(result));
        });
    }

    private void onImagesLoaded(List<String> uris) {
        allUris.clear();
        allUris.addAll(uris);
        adapter.notifyDataSetChanged();
        if (allUris.isEmpty()) {
            emptyTitle.setText("相册里还没有图片");
            emptyAction.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    private void showPermissionDenied() {
        recycler.setVisibility(View.GONE);
        emptyTitle.setText("需要相册权限才能选择图片");
        emptyAction.setText("去授权");
        emptyAction.setVisibility(View.VISIBLE);
        emptyAction.setOnClickListener(v -> {
            if (shouldShowRequestPermissionRationale(requiredPermission())) {
                permissionLauncher.launch(requiredPermission());
            } else {
                // 用户勾选「不再询问」后，引导到系统设置页
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });
        emptyView.setVisibility(View.VISIBLE);
    }

    private void toggleSelect(String uri, int position) {
        if (selected.contains(uri)) {
            selected.remove(uri);
        } else {
            if (selected.size() >= maxCount) {
                Toast.makeText(this, "最多选择 " + maxCount + " 张图片", Toast.LENGTH_SHORT).show();
                return;
            }
            selected.add(uri);
        }
        adapter.notifyItemChanged(position);
        updateDoneButton();
    }

    private void updateDoneButton() {
        boolean has = !selected.isEmpty();
        btnDone.setEnabled(has);
        btnDone.setText(has ? "完成（" + selected.size() + "）" : "完成");
        btnDone.setAlpha(has ? 1f : 0.4f);
    }

    private void confirm() {
        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_SELECTED_URIS, new ArrayList<>(selected));
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_media_picker, parent, false);
            // 正方形格子：宽度 = 屏宽 / 列数
            int size = parent.getWidth() / GRID_SPAN;
            view.setLayoutParams(new RecyclerView.LayoutParams(size, size));
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String uri = allUris.get(position);
            Glide.with(MediaPickerActivity.this)
                    .load(uri)
                    .centerCrop()
                    .override(360, 360)
                    .into(holder.image);
            boolean isSelected = selected.contains(uri);
            holder.badge.setSelected(isSelected);
            if (isSelected) {
                int index = 0;
                for (String s : selected) {
                    index++;
                    if (s.equals(uri)) break;
                }
                holder.badge.setText(String.valueOf(index));
                holder.image.setAlpha(0.85f);
            } else {
                holder.badge.setText("");
                holder.image.setAlpha(1f);
            }
            holder.itemView.setOnClickListener(v ->
                    toggleSelect(uri, holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return allUris.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView badge;

            Holder(@NonNull View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.picker_item_image);
                badge = itemView.findViewById(R.id.picker_item_badge);
            }
        }
    }
}
