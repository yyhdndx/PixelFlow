package com.PixelFlow.app.copilot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.CopilotAction;
import com.PixelFlow.app.API.CopilotReply;
import com.PixelFlow.app.CreateWorkActivity;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全屏 Copilot 会话页：从 AI 历史进入后像聊天一样展开整段对话，
 * 左上角返回回到历史列表。
 */
public class CopilotChatActivity extends AppCompatActivity {

    private static final String EXTRA_SCREEN = "screen";
    private static final String EXTRA_POST = "post_id";
    private static final String EXTRA_DRAFT = "draft_id";
    private static final String EXTRA_VERSION = "draft_version";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_LABEL = "label";

    private String screen = "detail";
    @Nullable private String postId;
    @Nullable private String draftId;
    private int draftVersion;
    private String title = "";
    private String label;

    private CopilotStore.Session session;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private MaterialButton send;
    private TextView status;
    private CommunityClient.Job running;
    private boolean busy;

    public static void open(Context context, String screen, @Nullable String postId, @Nullable String draftId,
                            int draftVersion, @Nullable String title, @Nullable String label) {
        Intent intent = new Intent(context, CopilotChatActivity.class);
        intent.putExtra(EXTRA_SCREEN, screen);
        intent.putExtra(EXTRA_POST, postId);
        intent.putExtra(EXTRA_DRAFT, draftId);
        intent.putExtra(EXTRA_VERSION, draftVersion);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_LABEL, label);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_copilot_chat);

        screen = getIntent().getStringExtra(EXTRA_SCREEN);
        if (screen == null || screen.isEmpty()) screen = "detail";
        postId = getIntent().getStringExtra(EXTRA_POST);
        draftId = getIntent().getStringExtra(EXTRA_DRAFT);
        draftVersion = getIntent().getIntExtra(EXTRA_VERSION, 0);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "";
        label = getIntent().getStringExtra(EXTRA_LABEL);

        messages = findViewById(R.id.chat_messages);
        scroll = findViewById(R.id.chat_scroll);
        input = findViewById(R.id.chat_input);
        send = findViewById(R.id.chat_send);
        status = findViewById(R.id.chat_status);

        findViewById(R.id.chat_back).setOnClickListener(v -> finish());
        MaterialButton jump = findViewById(R.id.chat_jump);
        jump.setText("create".equals(screen) ? R.string.copilot_jump_draft
                : "messages".equals(screen) ? R.string.copilot_jump_messages : R.string.copilot_jump_detail);
        jump.setOnClickListener(v -> jumpToContext());
        // 目前没有私信详情页，私信上下文不显示跳转入口
        if ("messages".equals(screen) || "general".equals(screen)) jump.setVisibility(View.GONE);
        if (label == null || label.isEmpty()) {
            label = "general".equals(screen) ? "通用对话" : CopilotStore.label(screen, title, 0);
        }
        ((TextView) findViewById(R.id.chat_label)).setText(label);

        String targetId = "detail".equals(screen) ? postId
                : "create".equals(screen) ? draftId
                : "general".equals(screen) ? "" : "messages";
        String key = CopilotStore.sessionKey(screen, targetId);
        session = CopilotStore.load(this, key);
        if (session == null) {
            session = new CopilotStore.Session();
            session.key = key;
            session.label = label == null || label.isEmpty() ? CopilotStore.label(screen, title, 0) : label;
            session.screen = screen;
            session.postId = postId;
            session.draftId = draftId;
            session.draftVersion = draftVersion;
        }
        render();

        input.setHint(hintFor(screen));
        send.setOnClickListener(v -> submit());
        if (!ApiClient.getInstance(this).isLoggedIn()) {
            setStatus(getString(R.string.copilot_login_first));
            send.setEnabled(false);
            input.setEnabled(false);
        }
    }

    private int hintFor(String screen) {
        if ("general".equals(screen)) return R.string.copilot_hint_general;
        if ("create".equals(screen)) return R.string.copilot_hint_create;
        if ("messages".equals(screen)) return R.string.copilot_hint_messages;
        return R.string.copilot_hint_detail;
    }

    /** 回到这段对话对应的页面：帖子 → 详情页；草稿 → 编辑页；私信 → 消息页。 */
    private void jumpToContext() {
        if ("messages".equals(screen)) {
            Intent intent = new Intent(this, com.PixelFlow.app.MainActivity.class);
            intent.putExtra("open_tab", "messages");
            startActivity(intent);
            return;
        }
        final String targetId = "create".equals(screen) ? draftId : postId;
        if (targetId == null || targetId.isEmpty()) {
            android.widget.Toast.makeText(this, getString(R.string.copilot_jump_failed, "上下文缺失"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ApiClient.getInstance(this).getPost(targetId, new CommunityClient.Callback<com.PixelFlow.app.API.PostItem>() {
            @Override public void onSuccess(com.PixelFlow.app.API.PostItem post) {
                if (post == null) {
                    android.widget.Toast.makeText(CopilotChatActivity.this,
                            getString(R.string.copilot_jump_failed, "内容不存在"), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if ("create".equals(screen)) {
                    Intent intent = new Intent(CopilotChatActivity.this, CreateWorkActivity.class);
                    intent.putExtra(CreateWorkActivity.EXTRA_POST_ID, post.getPostId());
                    intent.putExtra(CreateWorkActivity.EXTRA_TITLE, post.getTitle() == null ? "" : post.getTitle());
                    intent.putExtra(CreateWorkActivity.EXTRA_CONTENT, post.getContent() == null ? "" : post.getContent());
                    java.util.ArrayList<String> ids = new java.util.ArrayList<>();
                    if (post.getMediaIds() != null) ids.addAll(post.getMediaIds());
                    intent.putStringArrayListExtra(CreateWorkActivity.EXTRA_MEDIA_IDS, ids);
                    java.util.ArrayList<String> urls = new java.util.ArrayList<>();
                    if (post.getClips() != null) {
                        for (com.PixelFlow.app.API.PostItem.Clip clip : post.getClips()) {
                            if (clip.getType() != 1 && clip.getUrl() != null && !clip.getUrl().isEmpty()) {
                                urls.add(clip.getUrl());
                            }
                        }
                    }
                    intent.putStringArrayListExtra(CreateWorkActivity.EXTRA_MEDIA_URLS, urls);
                    startActivity(intent);
                } else {
                    Intent intent = new Intent(CopilotChatActivity.this, WorkDetailActivity.class);
                    intent.putExtra("work_item", new WorkItem(post));
                    startActivity(intent);
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                }
            }
            @Override public void onFailure(String message) {
                android.widget.Toast.makeText(CopilotChatActivity.this,
                        getString(R.string.copilot_jump_failed, message == null ? "网络异常" : message),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void submit() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!ApiClient.getInstance(this).isLoggedIn()) {
            setStatus(getString(R.string.copilot_login_first));
            return;
        }
        busy = true;
        send.setEnabled(false);
        setStatus(getString(R.string.copilot_loading));

        CopilotStore.Entry userEntry = new CopilotStore.Entry();
        userEntry.role = "user";
        userEntry.text = text;
        userEntry.at = System.currentTimeMillis();
        session.entries.add(userEntry);
        session.updatedAt = userEntry.at;
        CopilotStore.save(this, session);
        render();
        input.setText("");

        List<Map<String, String>> history = new ArrayList<>();
        int start = Math.max(0, session.entries.size() - 10);
        for (int i = start; i < session.entries.size(); i++)
            history.add(Map.of("role", session.entries.get(i).role, "content", session.entries.get(i).text));

        ArrayList<String> conversations = new ArrayList<>();
        running = ApiClient.getInstance(this).copilot(screen, postId, draftId,
                conversations, text, history, new CommunityClient.Callback<CopilotReply>() {
                    @Override public void onSuccess(CopilotReply value) { finishReply(value); }
                    @Override public void onFailure(String message) {
                        busy = false;
                        send.setEnabled(true);
                        setStatus(TextUtils.isEmpty(message) ? getString(R.string.copilot_failed) : message);
                    }
                });
    }

    private void finishReply(CopilotReply value) {
        busy = false;
        send.setEnabled(true);
        setStatus("");
        CopilotStore.Entry entry = new CopilotStore.Entry();
        entry.role = "assistant";
        entry.text = value.reply;
        entry.at = System.currentTimeMillis();
        entry.actions.addAll(value.actions);
        session.entries.add(entry);
        session.updatedAt = entry.at;
        CopilotStore.save(this, session);
        render();
    }

    private void render() {
        if (messages == null || session == null) return;
        messages.removeAllViews();
        if (session.entries.isEmpty()) {
            TextView intro = new TextView(this);
            intro.setText(getString("general".equals(screen) ? R.string.copilot_intro_general : R.string.copilot_intro));
            intro.setTextColor(Color.parseColor("#8A8A8E"));
            intro.setTextSize(13);
            intro.setPadding(dp(4), dp(8), dp(4), dp(8));
            messages.addView(intro);
        }
        for (CopilotStore.Entry entry : session.entries) {
            messages.addView(bubble(entry));
            for (CopilotAction action : entry.actions) messages.addView(actionCard(action));
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private View bubble(CopilotStore.Entry entry) {
        boolean user = "user".equals(entry.role);
        TextView bubble = new TextView(this);
        // 先开启可选，再以 SPANNABLE 缓冲写入文本，长按才会出现选择/复制
        bubble.setTextIsSelectable(true);
        bubble.setText(entry.text, TextView.BufferType.SPANNABLE);
        bubble.setTextSize(14);
        bubble.setTextColor(Color.parseColor("#1A1A1A"));
        bubble.setLineSpacing(dp(2), 1f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(user ? Color.parseColor("#EFEDFF") : Color.WHITE);
        bg.setCornerRadius(dp(14));
        bubble.setBackground(bg);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        // 自己的消息靠右、AI 靠左，两侧留出空白，像正常聊天窗口
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        if (user) {
            params.gravity = android.view.Gravity.END;
            params.leftMargin = dp(56);
        } else {
            params.gravity = android.view.Gravity.START;
            params.rightMargin = dp(56);
        }
        bubble.setLayoutParams(params);
        return bubble;
    }

    private View actionCard(CopilotAction action) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_copilot_action, messages, false);
        ((TextView) card.findViewById(R.id.action_title)).setText(action.summaryTitle());
        TextView actionBody = card.findViewById(R.id.action_body);
        actionBody.setText(bodyOf(action));
        actionBody.setTextIsSelectable(true);
        TextView rationale = card.findViewById(R.id.action_rationale);
        rationale.setText(action.rationale);
        rationale.setVisibility(action.rationale.isEmpty() ? View.GONE : View.VISIBLE);

        MaterialButton apply = card.findViewById(R.id.action_apply);
        MaterialButton ignore = card.findViewById(R.id.action_ignore);
        MaterialButton regenerate = card.findViewById(R.id.action_regenerate);

        boolean pending = CopilotAction.PENDING.equals(action.status);
        apply.setVisibility(pending ? View.VISIBLE : View.GONE);
        ignore.setVisibility(pending ? View.VISIBLE : View.GONE);
        regenerate.setVisibility(CopilotAction.STALE.equals(action.status) ? View.VISIBLE : View.GONE);
        TextView statusChip = card.findViewById(R.id.action_status);
        if (CopilotAction.APPLIED.equals(action.status)) {
            statusChip.setText("✓ " + getString(R.string.copilot_applied));
            statusChip.setTextColor(getColor(R.color.profile_accent));
            statusChip.setBackgroundResource(R.drawable.bg_action_status);
            statusChip.setVisibility(View.VISIBLE);
            rationale.setVisibility(View.GONE);
            card.findViewById(R.id.action_body).setAlpha(0.75f);
        } else if (CopilotAction.IGNORED.equals(action.status)) {
            statusChip.setText(getString(R.string.copilot_ignored));
            statusChip.setTextColor(getColor(R.color.profile_muted));
            statusChip.setBackgroundResource(R.drawable.bg_action_status);
            statusChip.setVisibility(View.VISIBLE);
            rationale.setVisibility(View.GONE);
            card.findViewById(R.id.action_body).setAlpha(0.6f);
        } else {
            statusChip.setVisibility(View.GONE);
        }

        apply.setOnClickListener(v -> applyAction(action));
        ignore.setOnClickListener(v -> {
            action.status = CopilotAction.IGNORED;
            persistAndRender();
        });
        regenerate.setOnClickListener(v -> {
            action.status = CopilotAction.IGNORED;
            persistAndRender();
            regenerate();
        });
        return card;
    }

    private String bodyOf(CopilotAction action) {
        if (CopilotAction.TYPE_CREATE_DRAFT.equals(action.type)) {
            String title = action.title == null ? "" : action.title;
            String content = action.content == null ? "" : action.content;
            if (content.length() > 300) content = content.substring(0, 300) + "…";
            return title.isEmpty() ? content : title + "\n" + content;
        }
        if (CopilotAction.TYPE_DRAFT.equals(action.type)) {
            String text = action.content;
            if (text.length() > 300) text = text.substring(0, 300) + "…";
            return text;
        }
        if (CopilotAction.TYPE_MESSAGES.equals(action.type)) {
            StringBuilder sb = new StringBuilder();
            for (CopilotAction.Message message : action.messages) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("· ").append(message.text);
            }
            return sb.toString();
        }
        return action.text;
    }

    /** 应用「新建草稿」提案：调创建接口落成草稿，再跳进编辑页。 */
    private void createDraft(CopilotAction action) {
        String title = action.title == null ? "" : action.title;
        String content = action.content == null ? "" : action.content;
        if (title.trim().isEmpty() && content.trim().isEmpty()) {
            setStatus("草稿内容为空");
            return;
        }
        ApiClient api = ApiClient.getInstance(this);
        String key = java.util.UUID.randomUUID().toString();
        api.createPost(key, title, content, java.util.Collections.emptyList(),
                new CommunityClient.Callback<com.PixelFlow.app.API.PostItem>() {
                    @Override public void onSuccess(com.PixelFlow.app.API.PostItem post) {
                        action.status = CopilotAction.APPLIED;
                        persistAndRender();
                        setStatus("");
                        android.widget.Toast.makeText(CopilotChatActivity.this,
                                R.string.copilot_draft_created, android.widget.Toast.LENGTH_SHORT).show();
                        if (post != null && post.getPostId() != null) {
                            Intent intent = new Intent(CopilotChatActivity.this, com.PixelFlow.app.CreateWorkActivity.class);
                            intent.putExtra(com.PixelFlow.app.CreateWorkActivity.EXTRA_POST_ID, post.getPostId());
                            intent.putExtra(com.PixelFlow.app.CreateWorkActivity.EXTRA_TITLE, title);
                            intent.putExtra(com.PixelFlow.app.CreateWorkActivity.EXTRA_CONTENT, content);
                            startActivity(intent);
                        }
                    }
                    @Override public void onFailure(String message) {
                        setStatus(TextUtils.isEmpty(message) ? getString(R.string.copilot_failed) : message);
                    }
                });
    }
    private void applyAction(CopilotAction action) {
        ApiClient api = ApiClient.getInstance(this);
        setStatus(getString(R.string.copilot_loading));
        CommunityClient.Callback<Boolean> callback = new CommunityClient.Callback<Boolean>() {
            @Override public void onSuccess(Boolean value) {
                action.status = CopilotAction.APPLIED;
                if (CopilotAction.TYPE_DRAFT.equals(action.type)) session.draftVersion = draftVersion + 1;
                setStatus("");
                persistAndRender();
            }
            @Override public void onFailure(String message) {
                setStatus(TextUtils.isEmpty(message) ? getString(R.string.copilot_failed) : message);
            }
            @Override public void onFailure(int code, String message) {
                if (code == 409) {
                    action.status = CopilotAction.STALE;
                    setStatus(getString(R.string.copilot_conflict));
                    persistAndRender();
                } else {
                    setStatus(TextUtils.isEmpty(message) ? getString(R.string.copilot_failed) : message);
                }
            }
        };

        if (CopilotAction.TYPE_CREATE_DRAFT.equals(action.type)) {
            createDraft(action);
            return;
        }
        if (CopilotAction.TYPE_COMMENT.equals(action.type)) {
            api.createComment(postId, action.text, null,
                    new CommunityClient.Callback<com.PixelFlow.app.API.CommentPage.CommentItem>() {
                        @Override public void onSuccess(com.PixelFlow.app.API.CommentPage.CommentItem value) {
                            callback.onSuccess(Boolean.TRUE);
                        }
                        @Override public void onFailure(String message) { callback.onFailure(message); }
                    });
        } else if (CopilotAction.TYPE_DRAFT.equals(action.type)) {
            api.getPost(draftId, new CommunityClient.Callback<com.PixelFlow.app.API.PostItem>() {
                @Override public void onSuccess(com.PixelFlow.app.API.PostItem value) {
                    int version = value == null || value.getContentVersion() == null ? 0 : value.getContentVersion();
                    api.applyCopilotDraft(draftId, action.title, action.content, version, callback);
                }
                @Override public void onFailure(String message) { callback.onFailure(message); }
            });
        } else if (CopilotAction.TYPE_MESSAGES.equals(action.type)) {
            List<Map<String, String>> items = new ArrayList<>();
            for (CopilotAction.Message message : action.messages) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("conversation_id", message.conversationId);
                row.put("content", message.text);
                items.add(row);
            }
            api.sendMessagesBatch(items, callback);
        }
    }

    private void regenerate() {
        for (int i = session.entries.size() - 1; i >= 0; i--) {
            if ("user".equals(session.entries.get(i).role)) {
                input.setText(session.entries.get(i).text);
                break;
            }
        }
        submit();
    }

    private void persistAndRender() {
        CopilotStore.save(this, session);
        render();
    }

    private void setStatus(String text) {
        if (status == null) return;
        status.setText(text);
        status.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        if (running != null) running.cancel();
        super.onDestroy();
    }
}
