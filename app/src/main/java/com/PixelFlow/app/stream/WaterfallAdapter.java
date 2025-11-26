package com.PixelFlow.app.stream;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

public class WaterfallAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_LOADING = 1;

    private Context context;
    private List<WorkItem> workItems;
    private boolean isLoading = false;
    private SharedPreferences likePreferences;

    // 回调接口
    public interface OnLoadMoreListener {
        void onLoadMore();
    }

    public interface OnLikeClickListener {
        void onLikeClick(WorkItem workItem, int position);
    }

    private OnLoadMoreListener onLoadMoreListener;
    private OnLikeClickListener onLikeClickListener;

    public WaterfallAdapter(Context context) {
        this.context = context;
        this.workItems = new ArrayList<>();
        this.likePreferences = context.getSharedPreferences("like_preferences", Context.MODE_PRIVATE);
    }

    public void setOnLoadMoreListener(OnLoadMoreListener listener) {
        this.onLoadMoreListener = listener;
    }

    public void setOnLikeClickListener(OnLikeClickListener listener) {
        this.onLikeClickListener = listener;
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

    // 恢复点赞状态
    private void restoreLikeStatus() {
        for (WorkItem item : workItems) {
            boolean isLiked = likePreferences.getBoolean(item.getId(), false);
            item.setLiked(isLiked);
        }
    }

    // 保存点赞状态到持久化存储
    private void saveLikeStatus(String workId, boolean isLiked) {
        SharedPreferences.Editor editor = likePreferences.edit();
        editor.putBoolean(workId, isLiked);
        editor.apply();
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
            saveLikeStatus(workItem.getId(), isLiked);

            notifyItemChanged(position);
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

            // 使用Glide加载图片（这里用占位图代替）
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

            // 点赞点击事件
            likeContainer.setOnClickListener(v -> {
                if (onLikeClickListener != null) {
                    onLikeClickListener.onLikeClick(workItem, position);
                }
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