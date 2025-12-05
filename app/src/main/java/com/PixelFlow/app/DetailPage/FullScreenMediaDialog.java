package com.PixelFlow.app.DetailPage;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.github.chrisbanes.photoview.PhotoView;
import java.util.List;

public class FullScreenMediaDialog extends Dialog {

    private List<String> imageUrls;
    private int currentPosition;
    private Context context;
    private ViewPager2 viewPager;
    private TextView pageIndicator;
    private ImageView closeButton;

    public FullScreenMediaDialog(@NonNull Context context, List<String> imageUrls, int currentPosition) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.context = context;
        this.imageUrls = imageUrls;
        this.currentPosition = currentPosition;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_fullscreen_image);

        // 设置窗口全屏
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);
        }

        initViews();
        setupViewPager();
    }

    private void initViews() {
        viewPager = findViewById(R.id.fullscreen_viewpager);
        pageIndicator = findViewById(R.id.page_indicator);
        closeButton = findViewById(R.id.close_button);

        // 关闭按钮点击事件
        closeButton.setOnClickListener(v -> dismiss());

        // 更新页面指示器
        updatePageIndicator(currentPosition + 1, imageUrls.size());
    }

    private void updatePageIndicator(int current, int total) {
        pageIndicator.setText(String.format("%d/%d", current, total));
        if (total <= 1) {
            pageIndicator.setVisibility(View.GONE);
        } else {
            pageIndicator.setVisibility(View.VISIBLE);
        }
    }

    private void setupViewPager() {
        FullScreenImageAdapter adapter = new FullScreenImageAdapter();
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(currentPosition, false);

        // 监听页面切换
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updatePageIndicator(position + 1, imageUrls.size());
            }
        });
    }

    // 全屏图片适配器，使用PhotoView
    private class FullScreenImageAdapter extends RecyclerView.Adapter<FullScreenImageAdapter.ImageViewHolder> {

        @NonNull
        @Override
        public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 使用PhotoView作为图片控件
            View view = View.inflate(context, R.layout.item_fullscreen_image, null);

            // 设置全屏尺寸
            view.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            return new ImageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
            String imageUrl = imageUrls.get(position);

            // 使用Glide加载图片到PhotoView
            Glide.with(context)
                    .load(imageUrl)
                    .apply(new RequestOptions()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_image_placeholder)
                            .error(R.drawable.ic_image_error))
                    .into(holder.photoView);

            // 为PhotoView添加单击关闭事件
            holder.photoView.setOnClickListener(v -> dismiss());

            // 可选：添加长按事件（例如保存图片）
            holder.photoView.setOnLongClickListener(v -> {
                // 可以在这里实现长按保存图片等功能
                showSaveImageDialog(imageUrl);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return imageUrls.size();
        }

        class ImageViewHolder extends RecyclerView.ViewHolder {
            PhotoView photoView;

            ImageViewHolder(@NonNull View itemView) {
                super(itemView);
                photoView = itemView.findViewById(R.id.fullscreen_image);

                // 配置PhotoView的缩放行为
                configurePhotoView(photoView);
            }
        }
    }

    private void configurePhotoView(PhotoView photoView) {
        // 设置缩放级别
        photoView.setMaximumScale(5.0f);  // 最大缩放倍数
        photoView.setMediumScale(3.0f);   // 中等缩放倍数
        photoView.setMinimumScale(1.0f);   // 最小缩放倍数

        // 设置双击缩放行为
        photoView.setScaleLevels(1.0f, 2.0f, 4.0f);

        // 设置缩放动画时长（毫秒）
        photoView.setZoomTransitionDuration(200);

        // 允许在缩放时超出边界
        photoView.setAllowParentInterceptOnEdge(true);

        // 设置背景颜色
        photoView.setBackgroundColor(0xFF000000);
    }

    private void showSaveImageDialog(String imageUrl) {
        // 这里可以实现保存图片的对话框
        // 例如：询问用户是否要保存图片到相册
        // 由于需要处理权限和文件保存，这里先留空
    }
}