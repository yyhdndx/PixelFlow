package com.PixelFlow.app.stream;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.PixelFlow.app.DAO.DataType;
import com.PixelFlow.app.DAO.PersistState.LikeStatus;
import com.PixelFlow.app.DAO.StorageManager;
import com.PixelFlow.app.DAO.impl.GenericStorageRepository;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;

import java.io.Serializable;
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
        void onStartActivityForResult(Intent intent, int requestCode);
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

    // 打开详情页的方法
    private void openDetailPage(int position) {
        if (position >= 0 && position < workItems.size()) {
            WorkItem workItem = workItems.get(position);

            // 创建详情页Intent
            Intent intent = new Intent(context, WorkDetailActivity.class);
            intent.putExtra("work_item", workItem);

            // 通知外部Activity启动详情页
            if (onWorkActionListener != null) {
                onWorkActionListener.onItemClick(workItem, position);
            }

            if (onStartActivityListener != null) {
                onStartActivityListener.onStartActivityForResult(intent, REQUEST_CODE_DETAIL);
            } else {
                // 如果没有设置监听器，直接启动Activity（但这样无法接收返回结果）
                context.startActivity(intent);
            }
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

        void bind(WorkItem workItem, int position) {
            // 设置封面图片
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int itemWidth = (screenWidth - 24) / 2; // 减去padding和margin

            ViewGroup.LayoutParams layoutParams = coverImage.getLayoutParams();
            layoutParams.height = workItem.getDisplayHeight(itemWidth);
            coverImage.setLayoutParams(layoutParams);

            // 使用Glide加载图片
            Glide.with(context)
                    .load(workItem.getCoverUrl())
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.version)
                    .into(coverImage);

            // 设置标题/正文
            titleText.setText(workItem.getTitle());

            // 设置作者信息
            authorName.setText(workItem.getAuthorName());
            Glide.with(context)
                    .load(workItem.getAuthorAvatarUrl())
                    .placeholder(R.drawable.ic_avatar_placeholder)
                    .circleCrop()
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
                openDetailPage(position);
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
    }

    // 加载更多ViewHolder
    static class LoadingViewHolder extends RecyclerView.ViewHolder {
        LoadingViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}