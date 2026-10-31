package com.PixelFlow.app.Streams;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.ArrayList;
import java.util.List;
import android.os.Handler;
import android.os.Looper;

public class WaterfallAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_LOADING = 1;
    private static final int TYPE_END = 2;
    public static final int REQUEST_CODE_DETAIL = 1001; // 改为public，方便外部使用

    private Context context;
    private List<WorkItem> workItems;
    private boolean isLoading = false;
    private boolean endVisible = false;
    private final java.util.Map<String, CommunityClient.Job> likeJobs = new java.util.HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean released;
    private final java.util.Set<String> exposed = new java.util.HashSet<>();

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
        } else if (viewType == TYPE_END) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_end, parent, false);
            StaggeredGridLayoutManager.LayoutParams lp = new StaggeredGridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setFullSpan(true);
            view.setLayoutParams(lp);
            return new EndViewHolder(view);
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
        // 末尾“没有更多了”提示无需绑定数据
    }

    @Override
    public int getItemCount() {
        return workItems.size() + ((isLoading || endVisible) ? 1 : 0);
    }

    @Override
    public int getItemViewType(int position) {
        if (position == workItems.size() && (isLoading || endVisible)) {
            return isLoading ? TYPE_LOADING : TYPE_END;
        }
        return TYPE_ITEM;
    }

    // 数据操作方法
    public void setData(List<WorkItem> newItems) {
        exposed.clear();
        workItems.clear();
        workItems.addAll(newItems);

        notifyDataSetChanged();
    }

    public void addData(List<WorkItem> newItems) {
        int startPosition = workItems.size();
        workItems.addAll(newItems);

        notifyItemRangeInserted(startPosition, newItems.size());
    }

    public void setLoading(boolean loading) {
        boolean hadFooter = isLoading || endVisible;
        isLoading = loading;
        if (loading) endVisible = false;
        boolean hasFooter = isLoading || endVisible;
        if (hadFooter != hasFooter) {
            if (hasFooter) notifyItemInserted(workItems.size());
            else notifyItemRemoved(workItems.size());
        }
    }

    public void setEndVisible(boolean visible) {
        boolean hadFooter = isLoading || endVisible;
        endVisible = visible;
        if (visible) isLoading = false;
        boolean hasFooter = isLoading || endVisible;
        if (hadFooter != hasFooter) {
            if (hasFooter) notifyItemInserted(workItems.size());
            else notifyItemRemoved(workItems.size());
        }
    }

    public boolean isLoading() {
        return isLoading;
    }
    @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        int position=holder.getBindingAdapterPosition();
        if (!released && position>=0 && position<workItems.size()) {
            String id=workItems.get(position).getId();
            if (exposed.add(id)) ApiClient.getInstance(context).trackEvent(id,"EXPOSURE",null,"HOME");
        }
    }

    public List<WorkItem> getData() {
        return workItems;
    }

    public void release() {
        released = true;
        for (CommunityClient.Job job : likeJobs.values()) job.cancel();
        likeJobs.clear();
        mainHandler.removeCallbacksAndMessages(null);
        onWorkActionListener = null;
        onStartActivityListener = null;
    }

    public void updateLikeStatus(int position, boolean liked) {
        if (released || position < 0 || position >= workItems.size()) return;
        WorkItem item = workItems.get(position);
        if (likeJobs.containsKey(item.getId())) return;
        CommunityClient.Job job = ApiClient.getInstance(context).setLike(item.getId(), liked,
                new CommunityClient.Callback<PostItem>() {
            public void onSuccess(PostItem post) {
                likeJobs.remove(item.getId());
                if (released) return;
                for (int i = 0; i < workItems.size(); i++) {
                    WorkItem current = workItems.get(i);
                    if (current.getId().equals(post.getPostId())) {
                        current.setLiked(post.isLiked());
                        current.setLikeCount(post.getLikeCount());
                        notifyItemChanged(i);
                    }
                }
            }
            public void onFailure(String message) {
                likeJobs.remove(item.getId());
                if (!released) android.widget.Toast.makeText(context,
                        message + "；可下拉刷新确认状态", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        likeJobs.put(item.getId(), job);
    }

    public void handleDetailResult(int resultCode, Intent data) {
        // 仅更新详情页返回的单条卡片点赞状态，保留滚动位置与列表顺序。
        if (released || data == null) return;
        String workId = data.getStringExtra("work_id");
        if (workId == null) return;
        boolean liked = data.getBooleanExtra("is_liked", false);
        int likeCount = data.getIntExtra("like_count", -1);
        for (int i = 0; i < workItems.size(); i++) {
            WorkItem item = workItems.get(i);
            if (workId.equals(item.getId())) {
                item.setLiked(liked);
                if (likeCount >= 0) item.setLikeCount(likeCount);
                notifyItemChanged(i);
                return;
            }
        }
    }

    // 作品ViewHolder
    class WorkViewHolder extends RecyclerView.ViewHolder {
        ImageView coverImage;
        TextView titleText;
        ImageView likeIcon;

        WorkViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImage = itemView.findViewById(R.id.cover_image);
            titleText = itemView.findViewById(R.id.title_text);
            likeIcon = itemView.findViewById(R.id.like_icon);
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
            boolean hasVideo = workItem.getFirstVideoPosition() >= 0;
            itemView.findViewById(R.id.work_video_badge).setVisibility(hasVideo ? View.VISIBLE : View.GONE);
            coverImage.setContentDescription(hasVideo
                    ? context.getString(R.string.video_work_description, workItem.getTitle())
                    : workItem.getTitle());
            // 计算图片尺寸
            DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
            int screenWidth = displayMetrics.widthPixels;

            // 双列卡片：扣除卡片左右 4dp 边距后计算内容宽度，卡片之间留出间隙。
            int cardMargin = (int) (4 * displayMetrics.density);
            int itemWidth = screenWidth / 2 - cardMargin * 2;

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

            // 设置点赞状态
            updateLikeUI(workItem);

            // 点赞按钮点击事件
            likeIcon.setOnClickListener(v -> {
                if (onWorkActionListener != null) {
                    int current = getBindingAdapterPosition();
                    if (current != RecyclerView.NO_POSITION)
                        onWorkActionListener.onLikeClick(workItems.get(current), current);
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
                likeIcon.setColorFilter(context.getResources().getColor(R.color.gray_600));
            }
        }

        private void openDetailPageWithAnimation(WorkItem workItem, int position) {
            // 创建详情页Intent（改用淡入淡出，不传共享元素与截图位置）
            Intent intent = new Intent(context, WorkDetailActivity.class);
            intent.putExtra("work_item", workItem);

            // 通过监听器启动Activity
            if (onStartActivityListener != null) {
                onStartActivityListener.onStartActivityForResult(intent, WaterfallAdapter.REQUEST_CODE_DETAIL, null);
            } else {
                context.startActivity(intent);
            }
            if (context instanceof Activity) {
                ((Activity) context).overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
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

    // 没有更多了ViewHolder
    static class EndViewHolder extends RecyclerView.ViewHolder {
        EndViewHolder(@NonNull View itemView) {
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
