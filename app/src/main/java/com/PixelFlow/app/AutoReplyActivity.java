package com.PixelFlow.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
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
    private boolean loadingRules, writing;
    private CharSequence emptyText;
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
        emptyText = empty.getText();
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
        if (writing || loadingRules) return;
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
        if (loadingRules) return;
        loadingRules = true;
        adapter.setBusy(true);
        empty.setVisibility(View.GONE);
        empty.setOnClickListener(null);
        loading.setVisibility(View.VISIBLE);
        ApiClient.getInstance(this).getAutoReplies(postId, new CommunityClient.Callback<List<AutoReplyRule>>() {
            @Override public void onSuccess(List<AutoReplyRule> rules) {
                loadingRules = false;
                writing = false;
                adapter.setBusy(false);
                if (isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.GONE);
                empty.setText(emptyText);
                adapter.setData(rules);
                empty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onFailure(String message) {
                loadingRules = false;
                writing = false;
                adapter.setBusy(false);
                if (isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.GONE);
                empty.setText("加载失败：" + message + "\n点击重试");
                empty.setVisibility(View.VISIBLE);
                empty.setOnClickListener(v -> reload());
            }
        });
    }

    private void update(AutoReplyRule rule, String question, String answer, boolean enabled) {
        if (writing || loadingRules) return;
        writing = true;
        adapter.setBusy(true);
        ApiClient.getInstance(this).updateAutoReply(postId, rule.id, question, answer, enabled,
                new CommunityClient.Callback<AutoReplyRule>() {
                    @Override public void onSuccess(AutoReplyRule value) { reload(); }
                    @Override public void onFailure(String message) { toast(message); reload(); }
                });
    }

    private void confirmDelete(AutoReplyRule rule) {
        if (writing || loadingRules) return;
        new AlertDialog.Builder(this, R.style.PixelFlowAppCompatDialog)
                .setMessage("删除这条自动回复规则？")
                .setNegativeButton(R.string.auto_reply_cancel, null)
                .setPositiveButton(R.string.auto_reply_delete, (d, w) -> {
                        if (writing || loadingRules) return;
                        writing = true; adapter.setBusy(true);
                        ApiClient.getInstance(this).deleteAutoReply(postId, rule.id, new CommunityClient.Callback<Void>() {
                            @Override public void onSuccess(Void value) { reload(); }
                            @Override public void onFailure(String message) { toast(message); reload(); }
                        }); })
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
