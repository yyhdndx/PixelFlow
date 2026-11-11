package com.PixelFlow.app.copilot;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.MyPostsPage;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 本机 Copilot 对话历史：按上下文标签列出，可清空；不上传服务端。 */
public class CopilotHistoryActivity extends AppCompatActivity {
    private final List<CopilotStore.Session> sessions = new ArrayList<>();
    private Adapter adapter;
    private View empty;
    private boolean selecting;
    private final java.util.Set<String> selected = new java.util.HashSet<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_copilot_history);
        findViewById(R.id.copilot_history_back).setOnClickListener(v -> {
            if (selecting) exitSelection(); else finish();
        });
        findViewById(R.id.copilot_history_select).setOnClickListener(v -> enterSelection());
        findViewById(R.id.copilot_history_new).setOnClickListener(v ->
                CopilotChatActivity.open(this, "general",
                    "general:" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                    null, null, 0, "通用对话", null));
        findViewById(R.id.copilot_history_cancel).setOnClickListener(v -> exitSelection());
        findViewById(R.id.copilot_history_delete).setOnClickListener(v -> deleteSelected());
        findViewById(R.id.copilot_history_select_all).setOnClickListener(v -> toggleSelectAll());

        RecyclerView list = findViewById(R.id.copilot_history_list);
        empty = findViewById(R.id.copilot_history_empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);

    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        sessions.clear();
        sessions.addAll(CopilotStore.all(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
    }


    private void open(CopilotStore.Session session) {
        ArrayList<String> conversations = new ArrayList<>();
        CopilotChatActivity.open(this, session.screen, session.key, session.postId, session.draftId,
                session.draftVersion, titleOf(session), session.label);
    }

    private String titleOf(CopilotStore.Session session) {
        String label = session.label == null ? "" : session.label;
        int start = label.indexOf('《'), end = label.lastIndexOf('》');
        return start >= 0 && end > start ? label.substring(start + 1, end) : "";
    }

    /** 进入多选：顶部换成 取消 / 删除，列表显示勾选框。 */
    private void enterSelection() {
        selecting = true;
        selected.clear();
        applySelectionUi();
    }

    private void exitSelection() {
        selecting = false;
        selected.clear();
        applySelectionUi();
    }

    /** 全选 / 取消全选。 */
    private void toggleSelectAll() {
        if (!sessions.isEmpty() && selected.size() == sessions.size()) {
            selected.clear();
        } else {
            selected.clear();
            for (CopilotStore.Session session : sessions) selected.add(session.key);
        }
        adapter.notifyDataSetChanged();
        updateDeleteLabel();
    }

    private void applySelectionUi() {
        findViewById(R.id.copilot_history_select).setVisibility(selecting ? View.GONE : View.VISIBLE);
        findViewById(R.id.copilot_history_new).setVisibility(selecting ? View.GONE : View.VISIBLE);
        findViewById(R.id.copilot_history_cancel).setVisibility(selecting ? View.VISIBLE : View.GONE);
        findViewById(R.id.copilot_history_select_all).setVisibility(selecting ? View.VISIBLE : View.GONE);
        findViewById(R.id.copilot_history_delete).setVisibility(selecting ? View.VISIBLE : View.GONE);
        updateDeleteLabel();
        adapter.notifyDataSetChanged();
    }

    private void updateDeleteLabel() {
        TextView delete = findViewById(R.id.copilot_history_delete);
        delete.setText(R.string.copilot_delete);
        delete.setAlpha(selected.isEmpty() ? 0.5f : 1f);
        delete.setEnabled(!selected.isEmpty());
        TextView selectAll = findViewById(R.id.copilot_history_select_all);
        selectAll.setText(selected.size() == sessions.size() && !sessions.isEmpty()
                ? R.string.copilot_select_none : R.string.copilot_select_all);
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        int count = selected.size();
        CopilotStore.delete(this, selected);
        selecting = false;
        selected.clear();
        applySelectionUi();
        reload();
        android.widget.Toast.makeText(this, getString(R.string.copilot_deleted, count),
                android.widget.Toast.LENGTH_SHORT).show();
    }
    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_copilot_session, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            CopilotStore.Session session = sessions.get(position);
            holder.label.setText(session.label);
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(session.updatedAt));
            holder.meta.setText(getString(R.string.copilot_history_count, session.entries.size()) + " · " + time);
            String preview = "";
            for (int i = session.entries.size() - 1; i >= 0; i--) {
                String text = session.entries.get(i).text;
                if (text != null && !text.isEmpty()) { preview = text; break; }
            }
            holder.preview.setText(preview);
            holder.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            holder.check.setChecked(selected.contains(session.key));
            holder.itemView.setOnClickListener(v -> {
                if (selecting) {
                    if (selected.contains(session.key)) selected.remove(session.key);
                    else selected.add(session.key);
                    notifyItemChanged(holder.getBindingAdapterPosition());
                    updateDeleteLabel();
                } else {
                    open(session);
                }
            });
        }

        @Override public int getItemCount() { return sessions.size(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView label, meta, preview;
        final android.widget.CheckBox check;
        Holder(View view) {
            super(view);
            label = view.findViewById(R.id.session_label);
            meta = view.findViewById(R.id.session_meta);
            preview = view.findViewById(R.id.session_preview);
            check = view.findViewById(R.id.session_check);
        }
    }
}
