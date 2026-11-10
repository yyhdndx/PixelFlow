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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_copilot_history);
        findViewById(R.id.copilot_history_back).setOnClickListener(v -> finish());
        findViewById(R.id.copilot_new).setOnClickListener(v -> startNewChat());

        RecyclerView list = findViewById(R.id.copilot_history_list);
        empty = findViewById(R.id.copilot_history_empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);

        findViewById(R.id.copilot_history_clear).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this, R.style.PixelFlowMaterialDialog)
                        .setTitle(R.string.copilot_history_title)
                        .setMessage(R.string.copilot_history_clear_confirm)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.copilot_history_clear, (dialog, which) -> {
                            CopilotStore.clearAll(this);
                            reload();
                            android.widget.Toast.makeText(this, R.string.copilot_history_cleared,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        })
                        .show());
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

    /** 新建对话：选一篇自己的作品作为上下文，再进入 Copilot 对话层。 */
    private void startNewChat() {
        ApiClient api = ApiClient.getInstance(this);
        if (!api.isLoggedIn()) {
            startActivity(new android.content.Intent(this, com.PixelFlow.app.AccountActivity.class));
            return;
        }
        android.widget.Toast.makeText(this, R.string.copilot_loading_posts, android.widget.Toast.LENGTH_SHORT).show();
        api.getMyPosts(20, null, new CommunityClient.Callback<MyPostsPage>() {
            @Override public void onSuccess(MyPostsPage page) {
                java.util.List<PostItem> posts = page == null ? java.util.Collections.<PostItem>emptyList() : page.getPosts();
                if (posts.isEmpty()) {
                    android.widget.Toast.makeText(CopilotHistoryActivity.this, R.string.copilot_no_posts,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] titles = new String[posts.size()];
                for (int i = 0; i < posts.size(); i++) {
                    String title = posts.get(i).getTitle();
                    titles[i] = title == null || title.trim().isEmpty() ? "无标题作品" : title;
                }
                new MaterialAlertDialogBuilder(CopilotHistoryActivity.this)
                        .setTitle(R.string.copilot_pick_post)
                        .setItems(titles, (dialog, which) -> {
                            PostItem post = posts.get(which);
                            CopilotSheet.open(CopilotHistoryActivity.this, "detail", post.getPostId(),
                                    null, 0, null, post.getTitle());
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
            @Override public void onFailure(String message) {
                android.widget.Toast.makeText(CopilotHistoryActivity.this,
                        message == null ? "加载失败" : message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void open(CopilotStore.Session session) {
        ArrayList<String> conversations = new ArrayList<>();
        CopilotSheet.open(this, session.screen, session.postId, session.draftId,
                session.draftVersion, conversations, titleOf(session));
    }

    private String titleOf(CopilotStore.Session session) {
        String label = session.label == null ? "" : session.label;
        int start = label.indexOf('《'), end = label.lastIndexOf('》');
        return start >= 0 && end > start ? label.substring(start + 1, end) : "";
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
            holder.itemView.setOnClickListener(v -> open(session));
        }

        @Override public int getItemCount() { return sessions.size(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView label, meta, preview;
        Holder(View view) {
            super(view);
            label = view.findViewById(R.id.session_label);
            meta = view.findViewById(R.id.session_meta);
            preview = view.findViewById(R.id.session_preview);
        }
    }
}
