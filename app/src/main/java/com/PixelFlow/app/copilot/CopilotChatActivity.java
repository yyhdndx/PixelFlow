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
import android.widget.ImageButton;
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
    private static final String EXTRA_KEY = "session_key";

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
        open(context, screen, null, postId, draftId, draftVersion, title, label);
    }

    /** 通用对话每次「新建」用独立会话 key，避免复用同一条会话。 */
    public static void open(Context context, String screen, @Nullable String sessionKey,
                            @Nullable String postId, @Nullable String draftId,
                            int draftVersion, @Nullable String title, @Nullable String label) {
        Intent intent = new Intent(context, CopilotChatActivity.class);
        if (sessionKey != null && !sessionKey.isEmpty()) intent.putExtra(EXTRA_KEY, sessionKey);
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
        setupQuickJump();

        findViewById(R.id.chat_back).setOnClickListener(v -> finish());
        if (label == null || label.isEmpty()) {
            label = "general".equals(screen) ? "通用对话" : CopilotStore.label(screen, title, 0);
        }
        ((TextView) findViewById(R.id.chat_label)).setText(label);

        String targetId = "detail".equals(screen) ? postId
                : "create".equals(screen) ? draftId
                : "general".equals(screen) ? "" : "messages";
        String savedKey = getIntent().getStringExtra(EXTRA_KEY);
        String key = savedKey != null && !savedKey.isEmpty() ? savedKey : CopilotStore.sessionKey(screen, targetId);
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

        compressThenSend(text);
    }

    /** 超过 12 条未折叠对话时，先把最早 6 条压成摘要，再发请求；压缩失败就保留原文继续。 */
    private void compressThenSend(String text) {
        int pending = session.entries.size() - 1 - session.compressedCount;
        if (pending <= 12) { dispatch(text); return; }
        List<Map<String, String>> batch = new ArrayList<>();
        int end = Math.min(session.entries.size() - 1, session.compressedCount + 6);
        for (int i = session.compressedCount; i < end; i++)
            batch.add(Map.of("role", session.entries.get(i).role, "content", session.entries.get(i).text));
        if (batch.isEmpty()) { dispatch(text); return; }
        setStatus(getString(R.string.copilot_compressing));
        running = ApiClient.getInstance(this).compressContext(session.summary, batch, new CommunityClient.Callback<String>() {
            @Override public void onSuccess(String value) {
                if (value != null && !value.isEmpty()) {
                    session.summary = value;
                    session.compressedCount += batch.size();
                    CopilotStore.save(CopilotChatActivity.this, session);
                }
                dispatch(text);
            }
            @Override public void onFailure(String message) { dispatch(text); }
        });
    }

    private void dispatch(String text) {
        setStatus(getString(R.string.copilot_loading));
        List<Map<String, String>> history = new ArrayList<>();
        int start = Math.max(session.compressedCount, session.entries.size() - 10);
        for (int i = start; i < session.entries.size(); i++)
            history.add(Map.of("role", session.entries.get(i).role, "content", session.entries.get(i).text));
        ArrayList<String> conversations = new ArrayList<>();
        running = ApiClient.getInstance(this).copilot(screen, postId, draftId,
                conversations, text, session.summary, history, new CommunityClient.Callback<CopilotReply>() {
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
        entry.references.addAll(value.references);
        session.entries.add(entry);
        session.updatedAt = entry.at;
        CopilotStore.save(this, session);
        render();
    }

    private final java.util.List<View> userViews = new java.util.ArrayList<>();
    private int jumpIndex = -1;
    private ImageButton jumpPrev, jumpNext, jumpBottom;
    /** 悬浮跳转：在我的发言之间上下移动，或回到底部。 */
    private void setupQuickJump() {
        jumpPrev = findViewById(R.id.chat_jump_prev);
        jumpNext = findViewById(R.id.chat_jump_next);
        jumpBottom = findViewById(R.id.chat_jump_bottom);
        jumpPrev.setOnClickListener(v -> jumpToUserMessage(-1));
        jumpNext.setOnClickListener(v -> jumpToUserMessage(1));
        jumpBottom.setOnClickListener(v -> {
            jumpIndex = -1;
            scroll.smoothScrollTo(0, messages.getHeight());
        });
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateJumpButtons());
        updateJumpButtons();
    }

    private void jumpToUserMessage(int step) {
        if (userViews.isEmpty()) return;
        if (jumpIndex < 0) jumpIndex = step > 0 ? 0 : userViews.size() - 1;
        else jumpIndex = Math.max(0, Math.min(userViews.size() - 1, jumpIndex + step));
        View target = userViews.get(jumpIndex);
        int offset = Math.max(0, target.getTop() - dp(8));
        scroll.smoothScrollTo(0, offset);
        target.animate().alpha(0.45f).setDuration(120).withEndAction(
                () -> target.animate().alpha(1f).setDuration(220).start()).start();
    }

    private void updateJumpButtons() {
        boolean hasUser = !userViews.isEmpty();
        if (jumpPrev != null) jumpPrev.setVisibility(hasUser ? View.VISIBLE : View.GONE);
        if (jumpNext != null) jumpNext.setVisibility(hasUser ? View.VISIBLE : View.GONE);
        if (jumpBottom != null) {
            boolean atBottom = scroll.getChildAt(0) != null
                    && scroll.getScrollY() + scroll.getHeight() >= scroll.getChildAt(0).getHeight() - dp(8);
            jumpBottom.setVisibility(atBottom ? View.GONE : View.VISIBLE);
        }
    }
    private void render() {
        if (messages == null || session == null) return;
        messages.removeAllViews();
        userViews.clear();
        if (session.entries.isEmpty()) {
            TextView intro = new TextView(this);
            intro.setText(getString("general".equals(screen) ? R.string.copilot_intro_general : R.string.copilot_intro));
            intro.setTextColor(Color.parseColor("#8A8A8E"));
            intro.setTextSize(13);
            intro.setPadding(dp(4), dp(8), dp(4), dp(8));
            messages.addView(intro);
        }
        for (CopilotStore.Entry entry : session.entries) {
            View bubbleView = bubble(entry);
            messages.addView(bubbleView);
            if ("user".equals(entry.role)) userViews.add(bubbleView);
            for (CopilotAction action : entry.actions) messages.addView(actionCard(action));
        }
        scroll.post(() -> {
            scroll.fullScroll(View.FOCUS_DOWN);
            scroll.postDelayed(this::updateJumpButtons, 120);
        });
    }

    private View bubble(CopilotStore.Entry entry) {
        boolean user = "user".equals(entry.role);
        TextView text = new TextView(this);
        // 先开启可选，再以 SPANNABLE 缓冲写入文本，长按才会出现选择/复制
        text.setTextIsSelectable(true);
        text.setText(entry.text, TextView.BufferType.SPANNABLE);
        text.setTextSize(14);
        text.setTextColor(Color.parseColor("#1A1A1A"));
        text.setLineSpacing(dp(2), 1f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(user ? Color.parseColor("#EFEDFF") : Color.WHITE);
        bg.setCornerRadius(dp(14));
        View links = user ? null : linksView(entry);
        TextView bubble;
        if (links == null) {
            bubble = text;
            bubble.setBackground(bg);
            bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        } else {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(bg);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            card.addView(text);
            card.addView(links);
            bubble = null;
            return wrapBubble(card, true);
        }
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

    private View wrapBubble(View content) { return wrapBubble(content, false); }

    private View wrapBubble(View content, boolean fullWidth) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                fullWidth ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        params.gravity = android.view.Gravity.START;
        params.rightMargin = dp(fullWidth ? 12 : 56);
        content.setLayoutParams(params);
        return content;
    }

    /** 消息内的跳转入口：已应用的提案指向对应稿件；多条时折叠。 */
    private View linksView(CopilotStore.Entry entry) {
        java.util.List<String[]> targets = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (com.PixelFlow.app.API.CopilotReference ref : entry.references) {
            if (ref.postId.isEmpty()) continue;
            String label = ref.title.isEmpty() ? "查看帖子" : "《" + ref.title + "》";
            if (seen.contains("detail:" + ref.postId)) continue;
            seen.add("detail:" + ref.postId);
            targets.add(new String[]{label, "detail", ref.postId});
        }
        for (CopilotAction action : entry.actions) {
            if (!CopilotAction.APPLIED.equals(action.status)) continue;
            String kind = null, id = null, label = null;
            if (CopilotAction.TYPE_CREATE_DRAFT.equals(action.type) && !action.postId.isEmpty()) {
                kind = "editor"; id = action.postId;
                label = action.title.isEmpty() ? "查看新建草稿" : "查看草稿《" + action.title + "》";
            } else if (CopilotAction.TYPE_DRAFT.equals(action.type) && session.draftId != null) {
                kind = "editor"; id = session.draftId;
                label = action.title.isEmpty() ? "查看草稿" : "查看草稿《" + action.title + "》";
            } else if (CopilotAction.TYPE_COMMENT.equals(action.type) && session.postId != null) {
                kind = "detail"; id = session.postId;
                label = session.label != null && session.label.contains("《") ? "查看" + session.label : "查看帖子";
            }
            if (kind == null || seen.contains(kind + ":" + id)) continue;
            seen.add(kind + ":" + id);
            targets.add(new String[]{label, kind, id});
        }
        if (targets.isEmpty()) return null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.topMargin = dp(6);
        box.setLayoutParams(boxParams);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (String[] target : targets) rows.addView(linkChip(target[0], target[1], target[2]));

        if (targets.size() <= 3) {
            for (String[] target : targets) box.addView(linkChip(target[0], target[1], target[2]));
            return box;
        }
        rows.setVisibility(View.GONE);
        rows.setPadding(0, dp(2), 0, 0);
        TextView header = new TextView(this);
        header.setText(targets.size() + " 条结果  ⌄");
        header.setTextSize(12);
        header.setTextColor(getColor(R.color.profile_accent));
        header.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.profile_soft));
        bg.setCornerRadius(dp(12));
        header.setBackground(bg);
        header.setOnClickListener(v -> {
            boolean expand = rows.getVisibility() != View.VISIBLE;
            rows.setVisibility(expand ? View.VISIBLE : View.GONE);
            header.setText(targets.size() + " 条结果  " + (expand ? "⌃" : "⌄"));
        });
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(header, headerParams);
        box.addView(rows);
        return box;
    }

    private TextView linkChip(String label, String kind, String id) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setTextColor(getColor(R.color.profile_accent));
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.profile_soft));
        bg.setCornerRadius(dp(12));
        chip.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> {
            if ("editor".equals(kind)) openEditor(id); else openDetail(id);
        });
        return chip;
    }

    private void openEditor(String postId) {
        ApiClient.getInstance(this).getPost(postId, new CommunityClient.Callback<com.PixelFlow.app.API.PostItem>() {
            @Override public void onSuccess(com.PixelFlow.app.API.PostItem post) {
                if (post == null) return;
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
                        if (clip.getType() != 1 && clip.getUrl() != null && !clip.getUrl().isEmpty()) urls.add(clip.getUrl());
                    }
                }
                intent.putStringArrayListExtra(CreateWorkActivity.EXTRA_MEDIA_URLS, urls);
                startActivity(intent);
            }
            @Override public void onFailure(String message) {
                setStatus(TextUtils.isEmpty(message) ? "打开失败" : message);
            }
        });
    }

    private void openDetail(String postId) {
        ApiClient.getInstance(this).getPost(postId, new CommunityClient.Callback<com.PixelFlow.app.API.PostItem>() {
            @Override public void onSuccess(com.PixelFlow.app.API.PostItem post) {
                if (post == null) return;
                Intent intent = new Intent(CopilotChatActivity.this, com.PixelFlow.app.DetailPage.WorkDetailActivity.class);
                intent.putExtra("work_item", new com.PixelFlow.app.Streams.WorkItem(post));
                startActivity(intent);
            }
            @Override public void onFailure(String message) {
                setStatus(TextUtils.isEmpty(message) ? "打开失败" : message);
            }
        });
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
                        action.postId = post == null ? "" : (post.getPostId() == null ? "" : post.getPostId());
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
