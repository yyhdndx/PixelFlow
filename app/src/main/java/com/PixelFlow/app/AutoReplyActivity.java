package com.PixelFlow.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.AutoReplyRule;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.Streams.AutoReplyAdapter;
import java.util.List;

/**
 * 作品作者配置「场景 → 答案」：Agent 判断一级评论是否属于该场景，命中才以作者身份自动回复。
 * 新增/编辑走独立页面（AutoReplyEditActivity），这里只负责列表与删除。
 */
public class AutoReplyActivity extends AppCompatActivity {
    public static final String EXTRA_POST_ID = "auto_reply_post_id";

    private String postId;
    private AutoReplyAdapter adapter;
    private ProgressBar loading;
    private TextView empty;
    private ActivityResultLauncher<Intent> editorLauncher;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        postId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_POST_ID);
        if (postId == null || !postId.matches("[1-9][0-9]{0,18}")) { finish(); return; }
        setContentView(R.layout.activity_auto_reply);
        findViewById(R.id.auto_back).setOnClickListener(v -> finish());
        findViewById(R.id.auto_add).setOnClickListener(v -> openEditor(null));

        editorLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> { if (result.getResultCode() == RESULT_OK) reload(); });

        loading = findViewById(R.id.auto_loading);
        empty = findViewById(R.id.auto_empty);
        adapter = new AutoReplyAdapter();
        adapter.setListener(new AutoReplyAdapter.Listener() {
            @Override public void onToggle(AutoReplyRule rule, boolean enabled) {
                update(rule, rule.question, rule.answer, enabled);
            }
            @Override public void onDelete(AutoReplyRule rule) { confirmDelete(rule); }
            @Override public void onEdit(AutoReplyRule rule) { openEditor(rule); }
        });
        RecyclerView list = findViewById(R.id.auto_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        reload();
    }

    private void openEditor(AutoReplyRule rule) {
        Intent intent = new Intent(this, AutoReplyEditActivity.class)
                .putExtra(AutoReplyEditActivity.EXTRA_POST_ID, postId);
        if (rule != null) {
            intent.putExtra(AutoReplyEditActivity.EXTRA_RULE_ID, rule.id)
                    .putExtra(AutoReplyEditActivity.EXTRA_QUESTION, rule.question)
                    .putExtra(AutoReplyEditActivity.EXTRA_ANSWER, rule.answer);
        }
        editorLauncher.launch(intent);
    }

    private void reload() {
        loading.setVisibility(View.VISIBLE);
        ApiClient.getInstance(this).getAutoReplies(postId, new CommunityClient.Callback<List<AutoReplyRule>>() {
            @Override public void onSuccess(List<AutoReplyRule> rules) {
                loading.setVisibility(View.GONE);
                adapter.setData(rules);
                empty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onFailure(String message) {
                loading.setVisibility(View.GONE);
                toast(message);
            }
        });
    }

    private void update(AutoReplyRule rule, String question, String answer, boolean enabled) {
        ApiClient.getInstance(this).updateAutoReply(postId, rule.id, question, answer, enabled,
                new CommunityClient.Callback<AutoReplyRule>() {
                    @Override public void onSuccess(AutoReplyRule value) { reload(); }
                    @Override public void onFailure(String message) { toast(message); reload(); }
                });
    }

    private void confirmDelete(AutoReplyRule rule) {
        com.PixelFlow.app.ui.PixelFlowDialogs.destructive(this)
                .setTitle(R.string.dialog_delete_rule_title)
                .setMessage(R.string.dialog_delete_rule_message)
                .setNegativeButton(R.string.auto_reply_cancel, null)
                .setPositiveButton(R.string.auto_reply_delete, (d, w) ->
                        ApiClient.getInstance(this).deleteAutoReply(postId, rule.id, new CommunityClient.Callback<Void>() {
                            @Override public void onSuccess(Void value) { reload(); }
                            @Override public void onFailure(String message) { toast(message); }
                        }))
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
