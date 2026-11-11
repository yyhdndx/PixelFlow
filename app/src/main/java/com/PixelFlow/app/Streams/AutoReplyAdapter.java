package com.PixelFlow.app.Streams;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.AutoReplyRule;
import com.PixelFlow.app.R;
import java.util.ArrayList;
import java.util.List;

/** 作品「评论自动回复」规则列表。 */
public class AutoReplyAdapter extends RecyclerView.Adapter<AutoReplyAdapter.Holder> {
    public interface Listener {
        void onToggle(AutoReplyRule rule, boolean enabled);
        void onDelete(AutoReplyRule rule);
        void onEdit(AutoReplyRule rule);
    }

    private final List<AutoReplyRule> items = new ArrayList<>();
    private Listener listener;
    private boolean busy;
    public void setBusy(boolean value) { busy = value; notifyDataSetChanged(); }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setData(List<AutoReplyRule> data) {
        items.clear();
        items.addAll(data);
        notifyDataSetChanged();
    }

    public List<AutoReplyRule> getData() { return items; }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_auto_reply, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AutoReplyRule rule = items.get(position);
        holder.toggle.setEnabled(!busy);
        holder.delete.setEnabled(!busy);
        holder.itemView.setEnabled(!busy);
        holder.question.setText(rule.question);
        holder.answer.setText(rule.answer);
        holder.toggle.setOnCheckedChangeListener(null);
        holder.toggle.setChecked(rule.enabled);
        holder.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (listener != null && checked != rule.enabled) listener.onToggle(rule, checked);
        });
        holder.delete.setOnClickListener(v -> { if (listener != null) listener.onDelete(rule); });
        holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onEdit(rule); });
    }

    @Override public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView question, answer, delete;
        final Switch toggle;
        Holder(@NonNull View itemView) {
            super(itemView);
            question = itemView.findViewById(R.id.r_question);
            answer = itemView.findViewById(R.id.r_answer);
            delete = itemView.findViewById(R.id.r_delete);
            toggle = itemView.findViewById(R.id.r_toggle);
        }
    }
}
