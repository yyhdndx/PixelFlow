package com.PixelFlow.app.Streams;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.ProfileHistoryPage;
import com.PixelFlow.app.R;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** History profile snapshots: version, window, cold-start flag, topic weights and explanation. */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {
    private final Context context;
    private final List<ProfileHistoryPage.Item> items = new ArrayList<>();
    public HistoryAdapter(Context context) { this.context = context; }
    public void setData(List<ProfileHistoryPage.Item> data) { items.clear(); items.addAll(data); notifyDataSetChanged(); }
    public void addData(List<ProfileHistoryPage.Item> data) { int s = items.size(); items.addAll(data); notifyItemRangeInserted(s, data.size()); }
    public int size() { return items.size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_history_profile, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        ProfileHistoryPage.Item item = items.get(position);
        holder.version.setText("画像 v" + item.getVersion());
        holder.time.setText(formatTime(item.getGeneratedAt()));
        holder.window.setText("近 " + item.getWindowDays() + " 天" + (item.isColdStart() ? " · 冷启动" : ""));
        holder.topics.setText(topicsText(item.getTopics()));
        String explanation = item.getExplanation();
        if (explanation == null || explanation.isEmpty()) holder.explanation.setVisibility(View.GONE);
        else { holder.explanation.setVisibility(View.VISIBLE); holder.explanation.setText(explanation); }
    }

    private String formatTime(long seconds) {
        if (seconds <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(seconds * 1000));
    }
    private String topicsText(List<ProfileHistoryPage.Weight> topics) {
        if (topics == null || topics.isEmpty()) return "暂无兴趣标签";
        StringBuilder sb = new StringBuilder();
        for (ProfileHistoryPage.Weight w : topics) {
            if (w == null || w.getName() == null || w.getName().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(w.getName());
        }
        return sb.length() == 0 ? "暂无兴趣标签" : sb.toString();
    }

    @Override public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        TextView version, time, window, topics, explanation;
        Holder(@NonNull View itemView) {
            super(itemView);
            version = itemView.findViewById(R.id.history_version);
            time = itemView.findViewById(R.id.history_time);
            window = itemView.findViewById(R.id.history_window);
            topics = itemView.findViewById(R.id.history_topics);
            explanation = itemView.findViewById(R.id.history_explanation);
        }
    }
}
