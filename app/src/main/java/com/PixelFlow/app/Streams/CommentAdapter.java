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

/**
 * 作品详情页两级评论区：一级评论平铺，回复缩进显示在其所属一级评论下方，
 * 回复标“回复 @某人”。数据里只可能存在两级，回复的回复也会挂回同一一级评论。
 */
public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.Holder> {
    private final Context context;
    private String selfId;
    /** 一级评论（每条自带 replies）。 */
    private final List<CommentPage.CommentItem> roots = new ArrayList<>();
    /** 展开成扁平行，供 RecyclerView 使用。 */
    private final List<Row> rows = new ArrayList<>();
    private OnCommentActionListener actionListener;

    public interface OnCommentActionListener {
        /** 点某条评论旁的「回复」→ 回复这个人；anchor 是被点的那一行，用来把它保持在输入框上方。 */
        void onCommentReplyClick(CommentPage.CommentItem item, android.view.View anchor);
        void onCommentLongClick(CommentPage.CommentItem item);
    }

    private static final class Row {
        final CommentPage.CommentItem item;
        final boolean reply;
        Row(CommentPage.CommentItem item, boolean reply) { this.item = item; this.reply = reply; }
    }

    public CommentAdapter(Context context, String selfId) {
        this.context = context;
        this.selfId = selfId;
    }

    public void setOnCommentActionListener(OnCommentActionListener listener) {
        this.actionListener = listener;
    }

    /** 当前用户 ID 是异步拿到的，拿到后回填，否则长按自己的评论不会出现「删除」。 */
    public void setSelfId(String selfId) {
        this.selfId = selfId;
    }

    public void setData(List<CommentPage.CommentItem> data) {
        roots.clear();
        roots.addAll(data);
        rebuildRows();
        notifyDataSetChanged();
    }

    public void addData(List<CommentPage.CommentItem> data) {
        int start = rows.size();
        roots.addAll(data);
        rebuildRows();
        notifyItemRangeInserted(start, rows.size() - start);
    }

    /** 新发的一级评论插到顶部。 */
    public void prepend(CommentPage.CommentItem item) {
        if (!item.isRoot()) { addReply(item); return; }
        roots.add(0, item);
        rebuildRows();
        notifyItemInserted(0);
    }

    /** 新发的回复插到所属一级评论的回复末尾；找不到对应一级评论时忽略。 */
    public void addReply(CommentPage.CommentItem item) {
        if (item.rootId == null) return;
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).id.equals(item.rootId)) {
                roots.get(i).replies.add(item);
                rebuildRows();
                notifyItemInserted(indexOfRow(item.id));
                return;
            }
        }
    }

    /** 删除评论；删一级评论时它的回复一起移除（服务端也是级联删除）。 */
    public void remove(String commentId) {
        boolean changed = false;
        for (int i = roots.size() - 1; i >= 0; i--) {
            CommentPage.CommentItem root = roots.get(i);
            if (root.id.equals(commentId)) {
                roots.remove(i);
                changed = true;
            } else {
                changed |= root.replies.removeIf(reply -> reply.id.equals(commentId));
            }
        }
        if (changed) {
            rebuildRows();
            notifyDataSetChanged();
        }
    }

    public List<CommentPage.CommentItem> getData() { return roots; }

    private void rebuildRows() {
        rows.clear();
        for (CommentPage.CommentItem root : roots) {
            rows.add(new Row(root, false));
            for (CommentPage.CommentItem reply : root.replies) rows.add(new Row(reply, true));
        }
    }

    private int indexOfRow(String commentId) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).item.id.equals(commentId)) return i;
        return Math.max(0, rows.size() - 1);
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_comment, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Row row = rows.get(position);
        CommentPage.CommentItem item = row.item;

        int startDp = row.reply ? 52 : 16;
        holder.itemView.setPaddingRelative(dp(startDp), dp(10), dp(16), dp(10));
        ViewGroup.LayoutParams avatarParams = holder.avatar.getLayoutParams();
        int avatarSize = dp(row.reply ? 24 : 32);
        if (avatarParams.width != avatarSize || avatarParams.height != avatarSize) {
            avatarParams.width = avatarSize;
            avatarParams.height = avatarSize;
            holder.avatar.setLayoutParams(avatarParams);
        }

        holder.nickname.setText(item.authorNickname);
        holder.auto.setVisibility(item.auto ? View.VISIBLE : View.GONE);
        Glide.with(context).load(item.authorAvatar.isEmpty() ? null : item.authorAvatar)
                .placeholder(R.drawable.profile_avatar).error(R.drawable.profile_avatar)
                .circleCrop().into(holder.avatar);

        if (row.reply && item.parentNickname != null && !item.parentNickname.isEmpty()) {
            String prefix = context.getString(R.string.comments_reply_prefix, item.parentNickname);
            SpannableString text = new SpannableString(prefix + item.content);
            text.setSpan(new ForegroundColorSpan(context.getColor(R.color.profile_muted)),
                    0, prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.content.setText(text);
        } else {
            holder.content.setText(item.content);
        }
        holder.time.setText(item.createTime > 0 ? DateUtils.formatRelativeTime(item.createTime) : "");
        holder.reply.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < rows.size() && actionListener != null)
                actionListener.onCommentReplyClick(rows.get(pos).item, holder.itemView);
        });
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < rows.size() && actionListener != null)
                actionListener.onCommentLongClick(rows.get(pos).item);
            return true;
        });
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 是否当前登录用户的评论（决定长按可否删除）。 */
    public boolean isOwn(CommentPage.CommentItem item) {
        return selfId != null && !selfId.isEmpty() && selfId.equals(item.authorId);
    }

    @Override public int getItemCount() { return rows.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView nickname, content, time, reply, auto;
        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.c_avatar);
            nickname = itemView.findViewById(R.id.c_nickname);
            content = itemView.findViewById(R.id.c_content);
            time = itemView.findViewById(R.id.c_time);
            reply = itemView.findViewById(R.id.c_reply);
            auto = itemView.findViewById(R.id.c_auto);
        }
    }
}
