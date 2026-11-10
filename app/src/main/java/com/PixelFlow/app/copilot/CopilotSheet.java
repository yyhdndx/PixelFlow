package com.PixelFlow.app.copilot;

import android.app.Activity;
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
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.CopilotAction;
import com.PixelFlow.app.API.CopilotReply;
import com.PixelFlow.app.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copilot 对话层：带上下文标签的半屏对话，AI 只产出提案，
 * 应用/忽略由用户点按决定；草稿版本冲突时只允许重新生成。
 */
public class CopilotSheet extends BottomSheetDialogFragment {
    private static final String ARG_SCREEN = "screen";
    private static final String ARG_POST = "post_id";
    private static final String ARG_DRAFT = "draft_id";
    private static final String ARG_VERSION = "draft_version";
    private static final String ARG_CONVERSATIONS = "conversation_ids";
    private static final String ARG_TITLE = "title";

    private String screen, postId, draftId, title;
    private int draftVersion;
    private ArrayList<String> conversationIds = new ArrayList<>();
    private CopilotStore.Session session;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private MaterialButton send;
    private TextView status;
    private CommunityClient.Job running;
    private boolean busy;

    public static CopilotSheet open(Activity activity, String screen, String postId, String draftId,
                                    int draftVersion, ArrayList<String> conversationIds, String title) {
        CopilotSheet sheet = new CopilotSheet();
        Bundle args = new Bundle();
        args.putString(ARG_SCREEN, screen);
        args.putString(ARG_POST, postId);
        args.putString(ARG_DRAFT, draftId);
        args.putInt(ARG_VERSION, draftVersion);
        args.putStringArrayList(ARG_CONVERSATIONS, conversationIds);
        args.putString(ARG_TITLE, title);
        sheet.setArguments(args);
        sheet.show(((androidx.fragment.app.FragmentActivity) activity).getSupportFragmentManager(), "copilot");
        return sheet;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_copilot, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments() == null ? new Bundle() : getArguments();
        screen = args.getString(ARG_SCREEN, "detail");
        postId = args.getString(ARG_POST);
        draftId = args.getString(ARG_DRAFT);
        draftVersion = args.getInt(ARG_VERSION, 0);
        title = args.getString(ARG_TITLE, "");
        ArrayList<String> ids = args.getStringArrayList(ARG_CONVERSATIONS);
        if (ids != null) conversationIds = ids;

        String targetId = "detail".equals(screen) ? postId : "create".equals(screen) ? draftId : "messages";
        String label = CopilotStore.label(screen, title, conversationIds.size());
        String key = CopilotStore.sessionKey(screen, targetId);

        messages = view.findViewById(R.id.copilot_messages);
        scroll = view.findViewById(R.id.copilot_scroll);
        input = view.findViewById(R.id.copilot_input);
        send = view.findViewById(R.id.copilot_send);
        status = view.findViewById(R.id.copilot_status);
        ((TextView) view.findViewById(R.id.copilot_label)).setText(label);
        view.findViewById(R.id.copilot_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.copilot_back).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.copilot_history).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CopilotHistoryActivity.class)));

        applyDefaults(view);
        session = CopilotStore.load(requireContext(), key);
        if (session == null) {
            session = new CopilotStore.Session();
            session.key = key;
            session.label = label;
            session.screen = screen;
            session.postId = postId;
            session.draftId = draftId;
            session.draftVersion = draftVersion;
        }
        render();

        send.setOnClickListener(v -> submit());
        if (!ApiClient.getInstance(requireContext()).isLoggedIn()) {
            setStatus(getString(R.string.copilot_login_first));
            send.setEnabled(false);
            input.setEnabled(false);
        }
    }


    /** 不同页面的默认指令：输入框提示 + 一键填写的建议胶囊。 */
    private void applyDefaults(View view) {
        int hint;
        int[] suggestions;
        if ("create".equals(screen)) {
            hint = R.string.copilot_hint_create;
            suggestions = new int[]{R.string.copilot_suggest_create_1, R.string.copilot_suggest_create_2};
        } else if ("messages".equals(screen)) {
            hint = R.string.copilot_hint_messages;
            suggestions = new int[]{R.string.copilot_suggest_messages_1, R.string.copilot_suggest_messages_2};
        } else {
            hint = R.string.copilot_hint_detail;
            suggestions = new int[]{R.string.copilot_suggest_detail_1, R.string.copilot_suggest_detail_2};
        }
        input.setHint(hint);
        LinearLayout row = view.findViewById(R.id.copilot_suggestions);
        row.removeAllViews();
        for (int res : suggestions) {
            TextView chip = new TextView(requireContext());
            chip.setText(res);
            chip.setTextSize(13);
            chip.setTextColor(requireContext().getColor(R.color.profile_accent));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(requireContext().getColor(R.color.profile_soft));
            bg.setCornerRadius(dp(16));
            chip.setBackground(bg);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> {
                input.setText(chip.getText());
                input.setSelection(input.getText().length());
            });
            row.addView(chip);
        }
    }
    private void submit() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!ApiClient.getInstance(requireContext()).isLoggedIn()) {
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
        CopilotStore.save(requireContext(), session);
        render();
        input.setText("");

        List<Map<String, String>> history = new ArrayList<>();
        int start = Math.max(0, session.entries.size() - 10);
        for (int i = start; i < session.entries.size(); i++)
            history.add(Map.of("role", session.entries.get(i).role, "content", session.entries.get(i).text));

        final String screenArg = screen, postArg = postId, draftArg = draftId;
        running = ApiClient.getInstance(requireContext()).copilot(screenArg, postArg, draftArg,
                conversationIds, text, history, new CommunityClient.Callback<CopilotReply>() {
                    @Override public void onSuccess(CopilotReply value) {
                        Activity activity = getActivity();
                        if (activity == null) return;
                        activity.runOnUiThread(() -> finishReply(value));
                    }
                    @Override public void onFailure(String message) {
                        Activity activity = getActivity();
                        if (activity == null) return;
                        activity.runOnUiThread(() -> {
                            busy = false; send.setEnabled(true);
                            setStatus(TextUtils.isEmpty(message) ? getString(R.string.copilot_failed) : message);
                        });
                    }
                });
    }

    private void finishReply(CopilotReply value) {
        busy = false;
        send.setEnabled(true);
        setStatus(value.actions.isEmpty() ? "" : null);
        CopilotStore.Entry entry = new CopilotStore.Entry();
        entry.role = "assistant";
        entry.text = value.reply;
        entry.at = System.currentTimeMillis();
        entry.actions.addAll(value.actions);
        session.entries.add(entry);
        session.updatedAt = entry.at;
        CopilotStore.save(requireContext(), session);
        render();
    }

    private void render() {
        if (messages == null || session == null) return;
        messages.removeAllViews();
        if (session.entries.isEmpty()) {
            TextView intro = new TextView(requireContext());
            intro.setText(getString(R.string.copilot_intro));
            intro.setTextColor(Color.parseColor("#8A8A8E"));
            intro.setTextSize(13);
            intro.setPadding(dp(4), dp(8), dp(4), dp(8));
            messages.addView(intro);
        }
        for (CopilotStore.Entry entry : session.entries) {
            messages.addView(bubble(entry));
            for (CopilotAction action : entry.actions) messages.addView(actionCard(action, entry));
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private View bubble(CopilotStore.Entry entry) {
        boolean user = "user".equals(entry.role);
        TextView bubble = new TextView(requireContext());
        // 先开启可选，再以 SPANNABLE 缓冲写入文本，长按才会出现选择/复制
        bubble.setTextIsSelectable(true);
        bubble.setText(entry.text, TextView.BufferType.SPANNABLE);
        bubble.setTextSize(14);
        bubble.setTextColor(Color.parseColor(user ? "#1A1A1A" : "#1A1A1A"));
        bubble.setLineSpacing(dp(2), 1f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(user ? Color.parseColor("#EFEDFF") : Color.WHITE);
        bg.setCornerRadius(dp(14));
        bubble.setBackground(bg);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        bubble.setLayoutParams(params);
        return bubble;
    }

    private View actionCard(CopilotAction action, CopilotStore.Entry owner) {
        View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_copilot_action, messages, false);
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
        if (!pending && !CopilotAction.STALE.equals(action.status))
            rationale.setText(CopilotAction.APPLIED.equals(action.status)
                    ? getString(R.string.copilot_applied) : getString(R.string.copilot_ignored));

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

    private void applyAction(CopilotAction action) {
        ApiClient api = ApiClient.getInstance(requireContext());
        setStatus(getString(R.string.copilot_loading));
        CommunityClient.Callback<Boolean> callback = new CommunityClient.Callback<Boolean>() {
            @Override public void onSuccess(Boolean value) {
                Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> {
                    action.status = CopilotAction.APPLIED;
                    if (CopilotAction.TYPE_COMMENT.equals(action.type) && getActivity()
                            instanceof com.PixelFlow.app.DetailPage.WorkDetailActivity) {
                        ((com.PixelFlow.app.DetailPage.WorkDetailActivity) getActivity()).refreshCommentsAfterCopilot();
                    }
                    if (CopilotAction.TYPE_DRAFT.equals(action.type)) session.draftVersion = draftVersion + 1;
                    setStatus("");
                    persistAndRender();
                });
            }
            @Override public void onFailure(String message) {
                Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> setStatus(TextUtils.isEmpty(message)
                        ? getString(R.string.copilot_failed) : message));
            }
            @Override public void onFailure(int status, String message) {
                Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> {
                    if (status == 409) {
                        action.status = CopilotAction.STALE;
                        setStatus(getString(R.string.copilot_conflict));
                        persistAndRender();
                    } else setStatus(TextUtils.isEmpty(message)
                            ? getString(R.string.copilot_failed) : message);
                });
            }
        };

        if (CopilotAction.TYPE_COMMENT.equals(action.type)) {
            api.createComment(postId, action.text, null,
                    new CommunityClient.Callback<com.PixelFlow.app.API.CommentPage.CommentItem>() {
                        @Override public void onSuccess(com.PixelFlow.app.API.CommentPage.CommentItem value) {
                            callback.onSuccess(Boolean.TRUE);
                        }
                        @Override public void onFailure(String message) { callback.onFailure(message); }
                    });
        } else if (CopilotAction.TYPE_DRAFT.equals(action.type)) {
            // 先取最新 content_version 再应用：用户手动改过时服务端返回 409，只能重新生成
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
        CopilotStore.save(requireContext(), session);
        render();
    }

    private void setStatus(String text) {
        if (status == null) return;
        if (text == null) { status.setVisibility(View.GONE); return; }
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroyView() {
        if (running != null) running.cancel();
        super.onDestroyView();
    }
}
