package com.PixelFlow.app.Streams;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.PixelFlow.app.DAO.DataType;
import com.PixelFlow.app.DAO.PersistState.LikeStatus;
import com.PixelFlow.app.DAO.StorageManager;
import com.PixelFlow.app.DAO.impl.GenericStorageRepository;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class WaterfallAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_LOADING = 1;
    public static final int REQUEST_CODE_DETAIL = 1001; // 改为public，方便外部使用

    private Context context;
    private List<WorkItem> workItems;
    private boolean isLoading = false;
    // 数据库的接口
    private GenericStorageRepository<LikeStatus> likeRepository;

    // 简化的回调接口
    public interface OnWorkActionListener {
        void onLoadMore();
        void onLikeClick(WorkItem workItem, int position);
        void onItemClick(WorkItem workItem, int position);
    }

    public interface OnStartActivityListener {
        void onStartActivityForResult(Intent intent, int requestCode, Bundle options);
    }

    private OnWorkActionListener onWorkActionListener;
    private OnStartActivityListener onStartActivityListener;

    public WaterfallAdapter(Context context) {
        this.context = context;
        this.workItems = new ArrayList<>();

        this.likeRepository = StorageManager.getInstance(context)
                .getRepository(DataType.LIKE_STATUS, LikeStatus.class);
        Log.d("WaterFall", "likeRepo初始化完成");
    }

    public void setOnWorkActionListener(OnWorkActionListener listener) {
        this.onWorkActionListener = listener;
    }

    public void setOnStartActivityListener(OnStartActivityListener listener) {
        this.onStartActivityListener = listener;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_LOADING) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_loading_more, parent, false);
            return new LoadingViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_work, parent, false);
            return new WorkViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof WorkViewHolder) {
            WorkItem workItem = workItems.get(position);
            ((WorkViewHolder) holder).bind(workItem, position);
        } else if (holder instanceof LoadingViewHolder) {
            // 加载更多视图不需要绑定数据
            // 如果到达底部且正在加载，触发加载更多
            if (position >= getItemCount() - 1 && !isLoading && onWorkActionListener != null) {
                setLoading(true);
                onWorkActionListener.onLoadMore();
            }
        }
    }

    @Override
    public int getItemCount() {
        return workItems.size() + (isLoading ? 1 : 0);
    }

    @Override
    public int getItemViewType(int position) {
        return (position == workItems.size() && isLoading) ? TYPE_LOADING : TYPE_ITEM;
    }

    // 数据操作方法
    public void setData(List<WorkItem> newItems) {
        workItems.clear();
        workItems.addAll(newItems);

        // 从持久化存储中恢复点赞状态
        restoreLikeStatus();

        notifyDataSetChanged();
    }

    public void addData(List<WorkItem> newItems) {
        int startPosition = workItems.size();
        workItems.addAll(newItems);

        // 从持久化存储中恢复点赞状态
        restoreLikeStatus();

        notifyItemRangeInserted(startPosition, newItems.size());
    }

    public void setLoading(boolean loading) {
        if (isLoading != loading) {
            isLoading = loading;
            if (loading) {
                notifyItemInserted(workItems.size());
            } else {
                notifyItemRemoved(workItems.size());
            }
        }
    }

    public boolean isLoading() {
        return isLoading;
    }

    public List<WorkItem> getData() {
        return workItems;
    }

    // 恢复点赞状态 - 使用异步
    private void restoreLikeStatus() {
        Executors.newSingleThreadExecutor().execute(() -> {
            for (WorkItem item : workItems) {
                LikeStatus likeStatus = likeRepository.get(item.getId());
                if (likeStatus != null) {
                    // 回到主线程更新UI数据
                    ((Activity) context).runOnUiThread(() -> {
                        item.setLiked(likeStatus.isLiked());
                        if (likeStatus.getLikeCount() > 0) {
                            item.setLikeCount(likeStatus.getLikeCount());
                        }
                    });
                }
            }
        });
    }

    // 保存点赞状态到持久化存储
    private void saveLikeStatus(String workId, boolean isLiked, int likeCount) {
        LikeStatus likeStatus = new LikeStatus(workId, isLiked, likeCount);
        likeRepository.save(workId, likeStatus);
    }

    // 更新点赞状态
    public void updateLikeStatus(int position, boolean isLiked) {
        if (position >= 0 && position < workItems.size()) {
            WorkItem workItem = workItems.get(position);
            workItem.setLiked(isLiked);

            if (isLiked) {
                workItem.setLikeCount(workItem.getLikeCount() + 1);
            } else {
                workItem.setLikeCount(Math.max(0, workItem.getLikeCount() - 1));
            }

            // 保存到持久化存储
            saveLikeStatus(workItem.getId(), isLiked, workItem.getLikeCount());

            notifyItemChanged(position);
        }
    }

    // 从详情页返回后更新数据 - 这个方法需要外部Activity在onActivityResult中调用
    public void handleDetailResult(int resultCode, Intent data) {
        if (resultCode == 1 && data != null) {
            String workId = data.getStringExtra("work_id");
            boolean isLiked = data.getBooleanExtra("is_liked", false);
            int likeCount = data.getIntExtra("like_count", 0);

            // 找到对应的item并更新
            for (int i = 0; i < workItems.size(); i++) {
                WorkItem item = workItems.get(i);
                if (item.getId().equals(workId)) {
                    item.setLiked(isLiked);
                    item.setLikeCount(likeCount);

                    // 保存到持久化存储
                    saveLikeStatus(workId, isLiked, likeCount);

                    // 通知更新
                    notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    // 作品ViewHolder
    class WorkViewHolder extends RecyclerView.ViewHolder {
        ImageView coverImage;
        TextView titleText;
        ImageView authorAvatar;
        TextView authorName;
        ImageView likeIcon;
        TextView likeCount;
        View likeContainer;

        WorkViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImage = itemView.findViewById(R.id.cover_image);
            titleText = itemView.findViewById(R.id.title_text);
            authorAvatar = itemView.findViewById(R.id.author_avatar);
            authorName = itemView.findViewById(R.id.author_name);
            likeIcon = itemView.findViewById(R.id.like_icon);
            likeCount = itemView.findViewById(R.id.like_count);
            likeContainer = itemView.findViewById(R.id.like_container);
        }

        private void loadImageSafely(String url, ImageView imageView,
                                     int targetWidth, int targetHeight) {
            try {
                Glide.with(context)
                        .load(url)
                        .override(targetWidth, targetHeight)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)  // 增加缓存策略
                        .timeout(5000)  // 设置5秒超时
                        .skipMemoryCache(false)  // 使用内存缓存
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .addListener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                        Target<Drawable> target, boolean isFirstResource) {
                                // 记录加载失败日志
                                Log.d("ImageLoad", "图片加载失败: " + url);
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model,
                                                           Target<Drawable> target, DataSource dataSource,
                                                           boolean isFirstResource) {
                                return false;
                            }
                        })
                        .into(imageView);
            } catch (Exception e) {
                // 防止Glide崩溃导致整个应用崩溃
                Log.e("ImageLoad", "Glide加载异常: " + e.getMessage());
                imageView.setImageResource(R.drawable.ic_image_error);
            }
        }

        /**
         * 安全地加载视频封面
         */
        private void loadVideoCoverSafely(String videoUrl, ImageView imageView,
                                          int targetWidth, int targetHeight, int position) {
            try {
                // 使用Glide的VideoDecoder提取视频第一帧
                Glide.with(context)
                        .asBitmap()
                        .load(videoUrl)
                        .override(targetWidth, targetHeight)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.DATA)  // 缓存原始数据
                        .timeout(3000)  // 视频封面提取需要更短超时时间
                        .skipMemoryCache(false)
                        .placeholder(R.drawable.ic_video_placeholder)
                        .error(R.drawable.ic_image_error)
                        .addListener(new RequestListener<Bitmap>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                        Target<Bitmap> target, boolean isFirstResource) {
                                // 视频封面提取失败，记录日志
                                Log.d("VideoCover", "视频封面提取失败，位置: " + position + ", URL: " + videoUrl);
                                // 加载失败时显示视频占位图
                                imageView.setImageResource(R.drawable.ic_video_placeholder);
                                return true;  // 返回true表示已处理错误
                            }

                            @Override
                            public boolean onResourceReady(Bitmap resource, Object model,
                                                           Target<Bitmap> target, DataSource dataSource,
                                                           boolean isFirstResource) {
                                // 记录成功日志
                                Log.d("VideoCover", "视频封面提取成功，位置: " + position);
                                return false;
                            }
                        })
                        .into(imageView);
            } catch (Exception e) {
                Log.e("VideoCover", "视频封面加载异常: " + e.getMessage());
                imageView.setImageResource(R.drawable.ic_video_placeholder);
            }
        }

        void bind(WorkItem workItem, int position) {
            // 计算图片尺寸
            DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
            int screenWidth = displayMetrics.widthPixels;

            // 计算每个卡片的宽度（考虑间距）
            // 新间距计算：左padding(4dp) + 右padding(4dp) + 卡片间距(2dp*2) = 12dp
            int totalPaddingDp = 12;
            int totalPaddingPx = (int) (totalPaddingDp * displayMetrics.density);
            int itemWidth = (screenWidth - totalPaddingPx) / 2;

            // 设置图片尺寸（保持3:4比例）
            int itemHeight = (int) (itemWidth * 1.33f);

            // 如果workItem有自己的高度计算，优先使用
            if (workItem.getDisplayHeight(itemWidth) > 0) {
                itemHeight = workItem.getDisplayHeight(itemWidth);
            }

            // 设置图片容器尺寸
            ViewGroup.LayoutParams layoutParams = coverImage.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.height = itemHeight;
            coverImage.setLayoutParams(layoutParams);

            // 加载的逻辑
            // 尽量还是去加载图片，如果实在不行再去加载视频
            String imageUrl= workItem.getFirstImageUrl();
            if(imageUrl!=null){
                // 加载照片
                loadImageSafely(imageUrl,coverImage,itemWidth,itemHeight);
            }else{
                // 加载视频
                String videoUrl= workItem.getFirstVideoUrl();
                if(videoUrl!=null){
                    loadVideoCoverSafely(videoUrl,coverImage,itemWidth,itemHeight,position);
                }else{
                    // 这咋啥都没有呢？？？
                    coverImage.setImageResource(R.drawable.ic_video_placeholder);
                }
            }

            // 设置标题/正文
            titleText.setText(workItem.getTitle());

            // 设置作者信息
            authorName.setText(workItem.getAuthorName());
            Glide.with(context)
                    .load(workItem.getAuthorAvatarUrl())
                    .placeholder(R.drawable.ic_avatar_placeholder)
                    .circleCrop()
                    .override(80, 80)  // 减小头像加载尺寸
                    .into(authorAvatar);

            // 设置点赞状态
            updateLikeUI(workItem);

            // 点赞按钮点击事件
            likeContainer.setOnClickListener(v -> {
                if (onWorkActionListener != null) {
                    onWorkActionListener.onLikeClick(workItem, position);
                }
            });

            // 整个item点击事件 - 进入详情页
            itemView.setOnClickListener(v -> {
                openDetailPageWithAnimation(workItem, position);
            });
        }

        private void updateLikeUI(WorkItem workItem) {
            if (workItem.isLiked()) {
                likeIcon.setImageResource(R.drawable.ic_thumb_up);
                likeIcon.setColorFilter(context.getResources().getColor(android.R.color.holo_red_dark));
            } else {
                likeIcon.setImageResource(R.drawable.ic_thumb_up_outline);
                likeIcon.setColorFilter(context.getResources().getColor(android.R.color.darker_gray));
            }
            likeCount.setText(String.valueOf(workItem.getLikeCount()));
        }

        private Bitmap takeScreenshot(Activity activity) {
            try {
                // 获取根视图
                View rootView = activity.getWindow().findViewById(android.R.id.content);

                rootView.setDrawingCacheEnabled(true);
                Bitmap bitmap = Bitmap.createBitmap(rootView.getDrawingCache());
                rootView.setDrawingCacheEnabled(false);

                // 压缩图片以减少传输大小
                int maxWidth = 540;
                int maxHeight = 960;
                float scale = Math.min(
                        (float) maxWidth / bitmap.getWidth(),
                        (float) maxHeight / bitmap.getHeight()
                );

                if (scale < 1) {
                    int newWidth = (int) (bitmap.getWidth() * scale);
                    int newHeight = (int) (bitmap.getHeight() * scale);
                    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
                }

                return bitmap;
            } catch (Exception e) {
                Log.e("Screenshot", "截屏失败", e);
                return null;
            }
        }

        private void openDetailPageWithAnimation(WorkItem workItem, int position) {
            // 获取屏幕位置
            int[] location = new int[2];
            coverImage.getLocationOnScreen(location);

            // 截取当前屏幕（瀑布流页面）
            Bitmap waterfallScreenshot = takeScreenshot((Activity) context);

            // 将Bitmap转换为字节数组（因为Intent不能直接传递Bitmap）
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            assert waterfallScreenshot != null;
            waterfallScreenshot.compress(Bitmap.CompressFormat.JPEG, 50, stream); // 70%质量压缩
            byte[] screenshotBytes = stream.toByteArray();


            // 创建详情页Intent
            Intent intent = new Intent(context, WorkDetailActivity.class);
            intent.putExtra("work_item", workItem);
            intent.putExtra("waterfall_screenshot", screenshotBytes);
            intent.putExtra("transition_name", "image_" + workItem.getId());
            intent.putExtra("start_left", location[0]);
            intent.putExtra("start_top", location[1]);
            intent.putExtra("start_width", coverImage.getWidth());
            intent.putExtra("start_height", coverImage.getHeight());

            // 创建共享元素动画
            Bundle options = null;
            coverImage.setTransitionName("image_" + workItem.getId());
            options = ActivityOptions.makeSceneTransitionAnimation(
                    (Activity) context,
                    coverImage,
                    "image_" + workItem.getId()
            ).toBundle();

            // 通过监听器启动Activity
            if (onStartActivityListener != null) {
                onStartActivityListener.onStartActivityForResult(intent, WaterfallAdapter.REQUEST_CODE_DETAIL, options);
            } else {
                context.startActivity(intent, options);
            }

            if (context instanceof MainActivity) {
                MainActivity activity = (MainActivity) context;
                activity.preloadForDetailPage(workItem);
            }

        }
    }

    // 加载更多ViewHolder
    static class LoadingViewHolder extends RecyclerView.ViewHolder {
        LoadingViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    public WorkItem getItemAtPosition(int position) {
        if (position >= 0 && position < workItems.size()) {
            return workItems.get(position);
        }
        return null;
    }
}