package com.PixelFlow.app.DetailPage;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

import java.util.List;

/** 全屏多图查看器：左右横滑切图 + 页码指示 + 关闭 + 系统栏沉浸（无顶部露边）。 */
public class FullScreenImagePagerDialog extends Dialog {

    private final List<String> imageUrls;
    private final int startPosition;
    private TextView pageIndicator;

    public FullScreenImagePagerDialog(@NonNull Context context, List<String> imageUrls, int startPosition) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.imageUrls = imageUrls;
        this.startPosition = startPosition;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_fullscreen_media);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);
            // 真正全屏：FLAG_FULLSCREEN + 沉浸式，彻底隐藏状态栏与导航栏（不只是透明）
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        ViewPager2 pager = findViewById(R.id.fullscreen_viewpager);
        pageIndicator = findViewById(R.id.page_indicator);
        ImageView close = findViewById(R.id.close_button);
        if (close != null) close.setOnClickListener(v -> dismiss());

        pager.setAdapter(new ImageAdapter());
        pager.setCurrentItem(Math.max(0, Math.min(startPosition, imageUrls.size() - 1)), false);
        updateIndicator(pager.getCurrentItem());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { updateIndicator(position); }
        });
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
            Glide.with(FullScreenImagePagerDialog.this.getContext())
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
                photo.setOnClickListener(v -> dismiss());
            }
        }
    }
}
