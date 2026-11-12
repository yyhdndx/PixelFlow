package com.PixelFlow.app.DetailPage;

import com.PixelFlow.app.BaseActivity;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.github.chrisbanes.photoview.PhotoView;

import java.util.ArrayList;
import java.util.List;

/** 全屏多图查看器（Activity 版）：真正全屏，左右横滑切图 + 页码 + 关闭。 */
public class FullScreenImageActivity extends BaseActivity {

    public static final String EXTRA_IMAGE_URLS = "extra_image_urls";
    public static final String EXTRA_START_POSITION = "extra_start_position";

    private List<String> imageUrls = new ArrayList<>();
    private int startPosition;
    private TextView pageIndicator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_fullscreen_media);

        List<String> urls = getIntent().getStringArrayListExtra(EXTRA_IMAGE_URLS);
        if (urls != null) imageUrls = urls;
        startPosition = getIntent().getIntExtra(EXTRA_START_POSITION, 0);
        if (imageUrls.isEmpty()) { finish(); return; }

        ViewPager2 pager = findViewById(R.id.fullscreen_viewpager);
        pageIndicator = findViewById(R.id.page_indicator);
        ImageView close = findViewById(R.id.close_button);
        if (close != null) close.setOnClickListener(v -> finish());

        pager.setAdapter(new ImageAdapter());
        pager.setCurrentItem(Math.max(0, Math.min(startPosition, imageUrls.size() - 1)), false);
        updateIndicator(pager.getCurrentItem());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { updateIndicator(position); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    private void hideSystemBars() {
        android.view.Window window = getWindow();
        if (window == null) return;
        // 经典沉浸式（API < 30）
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        // 现代 API（API 30+）
        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void updateIndicator(int position) {
        if (pageIndicator != null) {
            pageIndicator.setText((position + 1) + " / " + imageUrls.size());
        }
    }

    private final class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_fullscreen_image, parent, false);
            return new Holder(view);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Glide.with(FullScreenImageActivity.this)
                    .load(imageUrls.get(position))
                    .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.ALL).override(2048, 2048))
                    .into(holder.photo);
        }

        @Override public int getItemCount() { return imageUrls.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final PhotoView photo;
            Holder(View itemView) {
                super(itemView);
                photo = itemView.findViewById(R.id.fullscreen_image);
                photo.setMaximumScale(5.0f);
                photo.setMediumScale(3.0f);
                photo.setMinimumScale(1.0f);
                photo.setOnClickListener(v -> finish());
            }
        }
    }
}
