package com.PixelFlow.app.Streams;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.R;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

/** My-posts list: cover, title, status label, and a status-dependent action button. */
public class MyWorksAdapter extends RecyclerView.Adapter<MyWorksAdapter.Holder> {
    public interface OnActionListener {
        void onPublish(PostItem post);
        void onWithdraw(PostItem post);
        void onEdit(PostItem post);
        void onView(PostItem post);
    }
    private boolean actionsEnabled;
    private final Context context;
    private final List<PostItem> items = new ArrayList<>();
    private OnActionListener listener;
    public MyWorksAdapter(Context context) { this.context = context; }
    public void setOnActionListener(OnActionListener l) { this.listener = l; }
    public void setActionsEnabled(boolean enabled) {
        if (actionsEnabled == enabled) return;
        actionsEnabled = enabled;
        notifyDataSetChanged();
    }
    public void setData(List<PostItem> data) { items.clear(); items.addAll(data); notifyDataSetChanged(); }
    public void addData(List<PostItem> data) { int s = items.size(); items.addAll(data); notifyItemRangeInserted(s, data.size()); }
    public List<PostItem> getData() { return items; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_my_work, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        PostItem post = items.get(position);
        String title = post.getTitle();
        holder.title.setText(title == null || title.isEmpty() ? "（无标题）" : title);
        String status = post.getStatus();
        holder.status.setText(statusText(status));
        holder.status.setTextColor(context.getColor(statusColor(status)));
        com.PixelFlow.app.API.AccountMedia.load(holder.cover, firstImageUrl(post));
        bindAction(holder, post);
        // 草稿/被驳回/已撤回点进编辑页；已发布点进详情；待审核只读。
        boolean editable = "DRAFT".equals(status) || "REJECTED".equals(status) || "WITHDRAWN".equals(status);
        boolean published = "PUBLISHED".equals(status);
        holder.itemView.setClickable(editable || published);
        holder.itemView.setOnClickListener((editable || published) && listener != null ? v -> {
            if (!actionsEnabled) return;
            if (editable) listener.onEdit(post); else listener.onView(post);
        } : null);
    }

    private void bindAction(Holder holder, PostItem post) {
        String status = post.getStatus();
        boolean canPublish = "DRAFT".equals(status) || "REJECTED".equals(status) || "WITHDRAWN".equals(status);
        boolean canWithdraw = "PENDING".equals(status) || "PUBLISHED".equals(status) || "DRAFT".equals(status);
        holder.action.setEnabled(actionsEnabled);
        holder.action.setVisibility(View.GONE);
        holder.action.setOnClickListener(null);
        if (canPublish) {
            holder.action.setVisibility(View.VISIBLE);
            holder.action.setText(R.string.my_work_publish);
            holder.action.setBackgroundTintList(ColorStateList.valueOf(context.getColor(R.color.profile_accent)));
            holder.action.setTextColor(context.getColor(R.color.white));
            holder.action.setOnClickListener(v -> { if (actionsEnabled && listener != null) listener.onPublish(post); });
        } else if (canWithdraw) {
            holder.action.setVisibility(View.VISIBLE);
            holder.action.setText(R.string.my_work_withdraw);
            holder.action.setBackgroundTintList(ColorStateList.valueOf(context.getColor(R.color.profile_soft)));
            holder.action.setTextColor(context.getColor(R.color.profile_accent));
            holder.action.setOnClickListener(v -> { if (actionsEnabled && listener != null) listener.onWithdraw(post); });
        }
    }

    private String statusText(String status) {
        if (status == null) return "未知";
        switch (status) {
            case "DRAFT": return "草稿";
            case "PENDING": return "待审核";
            case "PUBLISHED": return "已发布";
            case "REJECTED": return "已驳回";
            case "WITHDRAWN": return "已撤回";
            default: return status;
        }
    }
    private int statusColor(String status) {
        if (status == null) return R.color.profile_muted;
        switch (status) {
            case "DRAFT": return R.color.profile_muted;
            case "PENDING": return R.color.profile_accent;
            case "PUBLISHED": return R.color.green_500;
            case "REJECTED": return R.color.red_500;
            case "WITHDRAWN": return R.color.gray_600;
            default: return R.color.profile_muted;
        }
    }
    private String firstImageUrl(PostItem post) {
        if (post.getClips() != null) {
            for (PostItem.Clip clip : post.getClips()) {
                if (clip.getType() != 1 && clip.getUrl() != null && !clip.getUrl().isEmpty()) return clip.getUrl();
            }
        }
        return null;
    }

    @Override public int getItemCount() { return items.size(); }
    @Override public void onViewRecycled(@NonNull Holder holder) {
        com.PixelFlow.app.API.AccountMedia.clear(holder.cover);
        super.onViewRecycled(holder);
    }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView cover;
        TextView title, status;
        MaterialButton action;
        Holder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.my_work_cover);
            title = itemView.findViewById(R.id.my_work_title);
            status = itemView.findViewById(R.id.my_work_status);
            action = itemView.findViewById(R.id.my_work_action);
        }
    }
}
