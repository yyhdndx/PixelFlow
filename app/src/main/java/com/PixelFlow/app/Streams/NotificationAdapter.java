package com.PixelFlow.app.Streams;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.NotificationPage;
import com.PixelFlow.app.R;
import com.PixelFlow.app.util.DateUtils;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

/** 消息通知列表（@我 / 回复 / 赞），点击跳作品详情。 */
public class NotificationAdapter extends RecyclerView.Adapter<NotificationAdapter.Holder> {
    private final Context context;
    private final List<NotificationPage.NotificationItem> items = new ArrayList<>();
    private OnNotificationClickListener clickListener;

    public interface OnNotificationClickListener {
        void onNotificationClick(NotificationPage.NotificationItem item);
    }

    public NotificationAdapter(Context context) { this.context = context; }

    public void setOnNotificationClickListener(OnNotificationClickListener listener) {
        this.clickListener = listener;
    }

    public void setData(List<NotificationPage.NotificationItem> data) {
        items.clear();
        items.addAll(data);
        notifyDataSetChanged();
    }

    public void addData(List<NotificationPage.NotificationItem> data) {
        int start = items.size();
        items.addAll(data);
        notifyItemRangeInserted(start, data.size());
    }

    public List<NotificationPage.NotificationItem> getData() { return items; }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_notification, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        NotificationPage.NotificationItem item = items.get(position);
        int textRes;
        if ("REPLY".equals(item.type)) textRes = R.string.messages_reply_text;
        else if ("LIKE".equals(item.type)) textRes = R.string.messages_like_text;
        else textRes = R.string.messages_mention_text;
        holder.text.setText(context.getString(textRes, item.actorNickname, item.postTitle));
        holder.time.setText(item.createTime > 0 ? DateUtils.formatRelativeTime(item.createTime) : "");
        Glide.with(context).load(item.actorAvatar.isEmpty() ? null : item.actorAvatar)
                .placeholder(R.drawable.profile_avatar).error(R.drawable.profile_avatar)
                .circleCrop().into(holder.avatar);
        holder.dot.setVisibility(item.read ? View.GONE : View.VISIBLE);
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < items.size() && clickListener != null)
                clickListener.onNotificationClick(items.get(pos));
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView text, time;
        View dot;
        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.n_avatar);
            text = itemView.findViewById(R.id.n_text);
            time = itemView.findViewById(R.id.n_time);
            dot = itemView.findViewById(R.id.n_dot);
        }
    }
}
