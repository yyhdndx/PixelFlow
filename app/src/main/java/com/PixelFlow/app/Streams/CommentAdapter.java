package com.PixelFlow.app.Streams;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.CommentPage;
import com.PixelFlow.app.R;
import com.PixelFlow.app.util.DateUtils;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

/** 作品详情页评论区：头像 + 昵称 +（回复前缀）+ 内容 + 相对时间。 */
public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.Holder> {
    private final Context context;
    private final String selfId;
    private final List<CommentPage.CommentItem> items = new ArrayList<>();
    private OnCommentActionListener actionListener;

    public interface OnCommentActionListener {
        /** 点击整条评论 → 回复；长按 → 弹操作（复制/删除自己的）。 */
        void onCommentClick(CommentPage.CommentItem item);
        void onCommentLongClick(CommentPage.CommentItem item);
    }

    public CommentAdapter(Context context, String selfId) {
        this.context = context;
        this.selfId = selfId;
    }

    public void setOnCommentActionListener(OnCommentActionListener listener) {
        this.actionListener = listener;
    }

    public void setData(List<CommentPage.CommentItem> data) {
        items.clear();
        items.addAll(data);
        notifyDataSetChanged();
    }

    public void addData(List<CommentPage.CommentItem> data) {
        int start = items.size();
        items.addAll(data);
        notifyItemRangeInserted(start, data.size());
    }

    /** 新发评论插入顶部（列表倒序，最新在前）。 */
    public void prepend(CommentPage.CommentItem item) {
        items.add(0, item);
        notifyItemInserted(0);
    }

    public void remove(String commentId) {
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(commentId)) { index = i; break; }
        }
        if (index >= 0) { items.remove(index); notifyItemRemoved(index); }
    }

    public List<CommentPage.CommentItem> getData() { return items; }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_comment, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        CommentPage.CommentItem item = items.get(position);
        holder.nickname.setText(item.authorNickname);
        Glide.with(context).load(item.authorAvatar.isEmpty() ? null : item.authorAvatar)
                .placeholder(R.drawable.profile_avatar).error(R.drawable.profile_avatar)
                .circleCrop().into(holder.avatar);
        if (item.parentNickname != null && !item.parentNickname.isEmpty()) {
            String prefix = context.getString(R.string.comments_reply_prefix, item.parentNickname);
            SpannableString text = new SpannableString(prefix + item.content);
            text.setSpan(new ForegroundColorSpan(context.getColor(R.color.profile_muted)),
                    0, prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.content.setText(text);
        } else {
            holder.content.setText(item.content);
        }
        holder.time.setText(item.createTime > 0 ? DateUtils.formatRelativeTime(item.createTime) : "");
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < items.size() && actionListener != null)
                actionListener.onCommentClick(items.get(pos));
        });
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < items.size() && actionListener != null)
                actionListener.onCommentLongClick(items.get(pos));
            return true;
        });
    }

    /** 是否当前登录用户的评论（决定长按可否删除）。 */
    public boolean isOwn(CommentPage.CommentItem item) {
        return selfId != null && !selfId.isEmpty() && selfId.equals(item.authorId);
    }

    @Override public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView nickname, content, time;
        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.c_avatar);
            nickname = itemView.findViewById(R.id.c_nickname);
            content = itemView.findViewById(R.id.c_content);
            time = itemView.findViewById(R.id.c_time);
        }
    }
}
