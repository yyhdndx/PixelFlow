package com.PixelFlow.app.DetailPage;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import java.util.List;

public class FullScreenImageDialog extends Dialog {

    private List<String> imageUrls;
    private int currentPosition;
    private Context context;
    private ViewPager2 viewPager;
    private ImageView closeButton;

    // 手势检测器，用于双击缩放和单击关闭
    private GestureDetector gestureDetector;
    private boolean isZoomed = false;

    public FullScreenImageDialog(@NonNull Context context, List<String> imageUrls, int currentPosition) {
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
        setupGestureDetector();
        setupViewPager();
    }

    private void initViews() {
        viewPager = findViewById(R.id.fullscreen_viewpager);
        closeButton = findViewById(R.id.close_button);

        // 关闭按钮点击事件
        closeButton.setOnClickListener(v -> dismiss());
    }

    private void setupGestureDetector() {
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                // 单击关闭
                dismiss();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                // 双击缩放（可选功能）
                toggleZoom();
                return true;
            }
        });
    }

    private void toggleZoom() {
        // 这里可以实现双击缩放功能，暂时留空
        // 可以使用PhotoView库来实现缩放功能
    }

    private void setupViewPager() {
        FullScreenImageAdapter adapter = new FullScreenImageAdapter();
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(currentPosition, false);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        // 将触摸事件传递给手势检测器
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // 全屏图片适配器
    private class FullScreenImageAdapter extends RecyclerView.Adapter<FullScreenImageAdapter.ImageViewHolder> {

        @NonNull
        @Override
        public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_fullscreen_image, parent, false);
            return new ImageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
            String imageUrl = imageUrls.get(position);

            // 使用Glide加载图片
            Glide.with(context)
                    .load(imageUrl)
                    .apply(new RequestOptions()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .fitCenter()
                            .placeholder(R.drawable.ic_image_placeholder)
                            .error(R.drawable.ic_image_error))
                    .into(holder.imageView);

            // 为图片添加点击事件
            holder.imageView.setOnClickListener(v -> dismiss());
        }

        @Override
        public int getItemCount() {
            return imageUrls.size();
        }

        class ImageViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;

            ImageViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.fullscreen_image);
            }
        }
    }
}
