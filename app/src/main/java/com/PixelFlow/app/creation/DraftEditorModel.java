package com.PixelFlow.app.creation;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.PixelFlow.app.API.*;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One editor owns one logical create. All state changes and API callbacks run on the main thread. */
public final class DraftEditorModel extends AndroidViewModel {
    public static final class MediaEntry {
        public String uri, url, mediaId;
        public MediaEntry(String uri, String url, String mediaId) {
            this.uri = uri; this.url = url; this.mediaId = mediaId;
        }
    }
    public static final class State {
        public String editorId = UUID.randomUUID().toString();
        public String owner, postId, title = "", content = "", tags = "";
        public String creationKey = UUID.randomUUID().toString();
        public String phase = "IDLE", status = "DRAFT", savedPayload;
        public String message = "";
        public String assistGoal, assistPrompt, assistAudience, assistIdentity, assistInputSnapshot;
        public CreationSuggestion assistPreview;
        public String appliedAssistIdentity;
        public boolean assistPendingAcceptance;
        public String previewSnapshot, previewGoal, previewTitle, previewContent;
        public List<String> previewTags;
        public String requestTitle, requestContent;
        public List<String> requestTags;
        public AssistUndo assistUndo = new AssistUndo();
        public boolean creationUnknown, needsReconcile, done, publishRequested;
        public ArrayList<MediaEntry> media = new ArrayList<>();
    }
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public State state;
    public boolean busy;
    public boolean assistBusy;
    public CreationSuggestion suggestion;
    public String assistMessage = "";
    public String streamText = "";
    private String assistSnapshot;
    private AiTaskRegistry.Task assistTask;
    private AiTaskRegistry.Subscription assistSubscription;
    private String assistKey;
    private final ApiClient api;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long session;
    private boolean cleared;
    private boolean discarded;
    public DraftEditorModel(Application app) { super(app); api = ApiClient.getInstance(app); }
    public void initialize(State initial, boolean restored) {
        if (state != null) return;
        if (restored) {
            String journal = journal().getString(initial.editorId, null);
            if (journal != null) initial = new Gson().fromJson(journal, State.class);
        }
        state = initial;
        if (state.assistUndo == null) state.assistUndo = new AssistUndo();
        suggestion = state.assistPreview;
        session = api.sessionVersion();
        if (state.owner == null) state.owner = api.username();
        if (restored && !"IDLE".equals(state.phase)) {
            state.needsReconcile = state.postId != null;
            // Unknown creates remain blocked until the backend supports durable replay (Issue 11).
            if ("CREATE".equals(state.phase) && state.postId == null) state.creationUnknown = true;
        }
        state.phase = "IDLE";
        if (state.creationUnknown) state.message = "创建结果尚未确认，可直接重试保存，服务器会复用同一作品。编辑内容仍保留在此页。";
    }
    public boolean ownsSession() {
        return api.isLoggedIn() && state != null && state.owner.equals(api.username()) && session == api.sessionVersion();
    }
    private boolean current() {
        if (cleared) return false;
        if (ownsSession()) return true;
        busy = false;
        assistBusy = false;
        suggestion = null;
        assistMessage = "";
        if (assistSubscription != null) assistSubscription.close();
        changes.setValue(changes.getValue() + 1);
        return false;
    }
    private android.content.SharedPreferences journal() {
        return getApplication().getSharedPreferences("draft_editor", android.content.Context.MODE_PRIVATE);
    }
    public boolean checkpoint() {
        if (discarded) return true;
        if (!ownsSession()) return false;
        // Write the create marker before sending POST, even after Activity state has been saved.
        // 同时记录「当前草稿」指针，供下次进入创作页时恢复。
        return journal().edit()
                .putString(state.editorId, new Gson().toJson(state))
                .putString("current_editor_id", state.editorId)
                .commit();
    }
    public void discard() {
        cancelAssist();
        discarded = true;
        journal().edit().remove(state.editorId).apply();
    }
    /** 取最近一次保存的草稿（仅同一账号、且仍是可编辑状态），供新创作页恢复；无则返回 null。 */
    public State loadLastDraft() {
        String editorId = journal().getString("current_editor_id", null);
        if (editorId == null) return null;
        String json = journal().getString(editorId, null);
        if (json == null) return null;
        State saved;
        try { saved = new Gson().fromJson(json, State.class); }
        catch (RuntimeException error) { return null; }
        if (saved == null || !Objects.equals(saved.owner, api.username())) return null;
        if (!Arrays.asList("DRAFT", "REJECTED", "WITHDRAWN").contains(saved.status)) return null;
        saved.done = false;
        saved.phase = "IDLE";
        saved.message = "";
        saved.creationUnknown = false;
        saved.needsReconcile = saved.postId != null;
        return saved;
    }
    private boolean notifyChange() {
        boolean saved = checkpoint();
        if (!saved) { busy = false; state.message = "无法保存恢复记录，请检查设备存储后重新打开编辑页。"; }
        changes.setValue(changes.getValue() + 1);
        return saved;
    }
    public boolean editable() {
        return ownsSession() && !busy && !state.creationUnknown && !state.needsReconcile
                && Arrays.asList("DRAFT", "REJECTED", "WITHDRAWN").contains(state.status);
    }
    private String textSnapshot() { return new Gson().toJson(Arrays.asList(state.title, state.content, state.tags)); }
    public void requestAssist(String goal, String prompt, String audience) {
        beginAssist(goal, prompt, audience, false);
    }
    public boolean refineAssist(String prompt) {
        return beginAssist(state.previewGoal, prompt, "", true);
    }
    public boolean suggestionStale() {
        return suggestion != null && !Objects.equals(state.previewSnapshot, textSnapshot());
    }
    private boolean assistInputError(String message) {
        assistMessage = message; changes.setValue(changes.getValue() + 1); return false;
    }
    private boolean beginAssist(String goal, String prompt, String audience, boolean refining) {
        if (!editable() || assistBusy) return false;
        prompt = prompt == null ? "" : prompt.trim();
        if (refining && (suggestion == null || suggestionStale()))
            return assistInputError("草稿已修改，请根据当前草稿重新生成");
        if (refining && prompt.isEmpty()) return assistInputError("请填写这次的修改要求");
        if (state.title.trim().isEmpty() && state.content.trim().isEmpty() && prompt.isEmpty())
            return assistInputError("先写一点内容，或告诉我你想写什么");
        if ("polish_content".equals(goal) && state.content.trim().isEmpty())
            return assistInputError("请先补充正文，或选择生成文案");
        CreationSuggestion.PreviousResult previous = refining ? new CreationSuggestion.PreviousResult(
                suggestion.title_candidates.isEmpty() ? state.previewTitle : suggestion.title_candidates.get(0),
                suggestion.content_candidates.isEmpty() ? state.previewContent : suggestion.content_candidates.get(0),
                suggestion.tag_suggestions.isEmpty() ? state.previewTags : suggestion.tag_suggestions) : null;
        // A deliberate new request is distinct from reattaching an existing task.
        String key = AiTaskRegistry.identity(api, "assist", state.editorId, UUID.randomUUID().toString());
        AiTaskRegistry.Task task;
        try { task = AiTaskRegistry.get().getOrCreate(key); }
        catch (IllegalStateException full) { return assistInputError(full.getMessage()); }
        assistKey = key;
        state.assistGoal = goal;
        state.assistPrompt = prompt;
        state.assistAudience = audience;
        assistSnapshot = textSnapshot();
        state.assistIdentity = assistKey;
        state.assistInputSnapshot = assistSnapshot;
        java.util.List<String> tags = new ArrayList<>();
        for (String tag : state.tags.split("\\s+")) if (!tag.isEmpty() && tag.length() <= 32 && tags.size() < 20) tags.add(tag);
        String title = refining ? state.previewTitle : state.title;
        String content = refining ? state.previewContent : state.content;
        if (refining) tags = new ArrayList<>(state.previewTags);
        state.requestTitle = title; state.requestContent = content; state.requestTags = tags;
        checkpoint();
        observeAssist(task);
        CommunityClient client = api;
        assistBusy = task.state == AiTaskRegistry.State.NEW || task.isRunning();
        final String instructions = prompt;
        final List<String> inputTags = tags;
        streamText = "";
        // 单一目标走流式：逐字预览；all（综合）仍走非流式（一次返回标题+正文+标签）
        if (!"all".equals(goal)) {
            AiTaskRegistry.get().start(task, callback -> client.createAssistStream(
                    title, content, inputTags, goal, instructions, audience, previous,
                    piece -> main.post(() -> {
                        if (current() && assistTask == task && task.isRunning()) {
                            streamText += piece;
                            changes.setValue(changes.getValue() + 1);
                        }
                    }),
                    AiCallbacks.forward(callback)));
        } else {
            AiTaskRegistry.get().start(task, callback -> client.createAssist(
                    title, content, inputTags, goal, instructions, audience, previous,
                    AiCallbacks.forward(callback)));
        }
        return true;
    }
    private void observeAssist(AiTaskRegistry.Task task) {
        assistTask = task;
        assistBusy = task.isRunning();
        if (assistSubscription != null) assistSubscription.close();
        String snapshot = assistSnapshot;
        assistSubscription = AiTaskRegistry.get().subscribe(task, command -> main.post(command), value -> {
            if (!current() || assistTask != value) return;
            assistBusy = value.isRunning();
            if (value.state == AiTaskRegistry.State.COMPLETED && value.result instanceof CreationSuggestion) {
                // Reattaching a completed task must not overwrite edits or the undo point.
                if (Objects.equals(state.appliedAssistIdentity, state.assistIdentity)) {
                    changes.setValue(changes.getValue() + 1);
                    return;
                }
                state.appliedAssistIdentity = state.assistIdentity;
                suggestion = (CreationSuggestion) value.result;
                state.assistPreview = suggestion; state.previewSnapshot = snapshot;
                state.previewGoal = state.assistGoal; state.previewTitle = state.requestTitle;
                state.previewContent = state.requestContent; state.previewTags = state.requestTags;
                streamText = "";
                // 自动填入标题/正文；草稿在生成期间被修改过则不覆盖（保留旧建议供复制）
                if (!suggestionStale()) {
                    String beforeTitle = state.title, beforeContent = state.content;
                    if (!suggestion.title_candidates.isEmpty()) state.title = suggestion.title_candidates.get(0);
                    if (!suggestion.content_candidates.isEmpty()) state.content = suggestion.content_candidates.get(0);
                    state.assistUndo.record(beforeTitle, beforeContent, state.title, state.content);
                    state.previewSnapshot = textSnapshot();
                    state.assistPendingAcceptance = true;
                }
                checkpoint();
                assistMessage = getApplication().getString(suggestionStale()
                        ? com.PixelFlow.app.R.string.ai_assist_changed : com.PixelFlow.app.R.string.ai_assist_ready);
            } else if (value.error != null) { streamText = ""; assistMessage = value.error; }
            else assistMessage = getApplication().getString(value.state == AiTaskRegistry.State.EXPIRED
                    ? com.PixelFlow.app.R.string.ai_expired
                    : value.state == AiTaskRegistry.State.CANCELLED ? com.PixelFlow.app.R.string.ai_cancelled
                    : value.state == AiTaskRegistry.State.WAITING ? com.PixelFlow.app.R.string.ai_waiting
                    : com.PixelFlow.app.R.string.ai_preparing);
            changes.setValue(changes.getValue() + 1);
        });
    }

    public void cancelAssist() {
        if (assistKey != null) AiTaskRegistry.get().cancel(assistKey);
        assistBusy = false;
        assistMessage = assistKey == null ? "" : getApplication().getString(com.PixelFlow.app.R.string.ai_cancelled);
        changes.setValue(changes.getValue() + 1);
    }
    /** Confirm the visible, already filled draft without replacing its contents. */
    public boolean confirmAssist() {
        if (!editable() || assistBusy || !state.assistPendingAcceptance) return false;
        state.assistPendingAcceptance = false;
        state.assistUndo.available = false;
        clearAssistPreview();
        assistMessage = "已接受 AI 修改";
        notifyChange();
        return true;
    }
    public boolean acceptAssist(String title, String content, boolean includeTags) {
        if (!editable() || assistBusy || suggestion == null || suggestionStale()) {
            assistMessage = getApplication().getString(com.PixelFlow.app.R.string.ai_assist_retry);
            changes.setValue(changes.getValue() + 1); return false;
        }
        if (title != null && !suggestion.title_candidates.contains(title)) return false;
        if (content != null && !suggestion.content_candidates.contains(content)) return false;
        String nextContent = content == null ? state.content : content;
        // Current post API stores no separate tags: selected hashtags are explicit body text.
        if (includeTags) for (String tag : suggestion.tag_suggestions)
            if (!nextContent.contains(tag)) nextContent += "\n" + tag;
        if (nextContent.length() > 5000) { assistMessage = getApplication().getString(com.PixelFlow.app.R.string.ai_assist_too_long); return false; }
        state.assistUndo.record(state.title, state.content, title == null ? state.title : title, nextContent);
        if (title != null) state.title = title;
        state.content = nextContent;
        clearAssistPreview(); assistMessage = getApplication().getString(com.PixelFlow.app.R.string.ai_assist_applied);
        notifyChange(); return true;
    }
    private void clearAssistPreview() {
        if (assistSubscription != null) assistSubscription.close();
        assistTask = null; assistKey = null; state.assistIdentity = null;
        suggestion = null; state.assistPreview = null;
        state.assistPendingAcceptance = false;
    }
    public boolean undoAssist(boolean confirmed) {
        if (!editable() || assistBusy || !state.assistUndo.canRestore(state.title, state.content, confirmed)) return false;
        state.title = state.assistUndo.beforeTitle; state.content = state.assistUndo.beforeContent;
        state.assistUndo.available = false;
        clearAssistPreview(); assistMessage = "已撤销本次采纳";
        notifyChange(); return true;
    }
    /** An unknown create is retried with the same Idempotency-Key; the backend replays it idempotently. */
    public boolean retryable() {
        return ownsSession() && !busy && state.creationUnknown && state.postId == null;
    }
    private List<String> mediaIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (MediaEntry entry : state.media) ids.add(entry.mediaId);
        return ids;
    }
    /** 正文 + 话题标签合并：服务端不单独存标签，话题按正文文本提交。 */
    private String effectiveContent() {
        String content = state.content.trim();
        if (state.tags == null || state.tags.trim().isEmpty()) return content;
        StringBuilder merged = new StringBuilder(content);
        for (String tag : state.tags.trim().split("\\s+")) {
            if (tag.isEmpty() || content.contains(tag)) continue;
            if (merged.length() > 0) merged.append('\n');
            merged.append(tag);
        }
        return merged.toString();
    }
    private String payload() { return new Gson().toJson(Arrays.asList(state.title.trim(), effectiveContent(), mediaIds())); }
    private boolean matches(PostItem post) {
        return Objects.equals(state.title.trim(), post.getTitle()) && Objects.equals(effectiveContent(), post.getContent())
                && Objects.equals(mediaIds(), post.getMediaIds());
    }
    public void resume() {
        if (!current() || busy || state.done) return;
        if (assistTask == null && state.assistIdentity != null) {
            AiTaskRegistry.Task task = AiTaskRegistry.get().peek(state.assistIdentity);
            if (task != null) {
                assistKey = state.assistIdentity;
                assistSnapshot = state.assistInputSnapshot;
                observeAssist(task);
            } else {
                assistBusy = false;
                assistMessage = getApplication().getString(com.PixelFlow.app.R.string.ai_expired);
                changes.setValue(changes.getValue() + 1);
            }
        }
        if (state.postId != null) reconcile();
    }
    public void submit(boolean publish) {
        if (!current() || busy) return;
        cancelAssist();
        if (state.needsReconcile) { reconcile(); return; }
        if (state.creationUnknown) {
            // The backend now replays a create idempotently by Idempotency-Key, so an
            // unknown create is retried with the same key instead of being blocked.
            state.publishRequested = publish;
            busy = true;
            save();
            return;
        }
        if (!editable()) return;
        state.publishRequested = publish;
        busy = true;
        state.message = "正在准备图片…";
        if (!notifyChange()) return;
        uploadNext(0);
    }
    private void uploadNext(int index) {
        if (!current()) return;
        if (index == state.media.size()) { save(); return; }
        MediaEntry entry = state.media.get(index);
        if (entry.mediaId != null) { uploadNext(index + 1); return; }
        state.phase = "UPLOAD";
        state.message = "正在上传图片 " + (index + 1) + "/" + state.media.size();
        if (!notifyChange()) return;
        images.execute(() -> {
            try {
                byte[] bytes = DraftImage.compress(getApplication().getContentResolver(), Uri.parse(entry.uri));
                main.post(() -> {
                    if (!current()) return;
                    api.uploadMedia(bytes, "image/jpeg", new CommunityClient.Callback<MediaItem>() {
                        public void onSuccess(MediaItem item) {
                            if (!current()) return;
                            entry.mediaId = item.mediaId; entry.url = item.url;
                            if (!notifyChange()) return;
                            uploadNext(index + 1);
                        }
                        public void onFailure(String error) { fail(error); }
                    });
                });
            } catch (Exception error) {
                main.post(() -> fail("无法读取所选图片，请重新选择；已上传的图片会保留。"));
            }
        });
    }
    private void save() {
        if (!current()) return;
        if (state.postId != null && payload().equals(state.savedPayload)) { afterSave(); return; }
        boolean creating = state.postId == null;
        state.phase = creating ? "CREATE" : "SAVE";
        state.creationUnknown = creating;
        state.needsReconcile = !creating;
        state.message = "正在保存草稿…";
        if (!notifyChange()) return;
        CommunityClient.Callback<PostItem> callback = new CommunityClient.Callback<PostItem>() {
            public void onSuccess(PostItem post) {
                if (!current()) return;
                // Record the ID before any publish request, including while the Activity is stopped.
                state.postId = post.getPostId(); state.status = post.getStatus();
                state.creationUnknown = false; state.needsReconcile = false;
                state.savedPayload = payload();
                if (!notifyChange()) return;
                afterSave();
            }
            public void onFailure(String error) {
                if (!current()) return;
                if (creating) fail("创建结果尚未确认，可直接重试，服务器会复用同一作品。");
                else reconcile();
            }
        };
        if (creating) api.createPost(state.creationKey, state.title.trim(), effectiveContent(), mediaIds(), callback);
        else api.updatePost(state.postId, state.title.trim(), effectiveContent(), mediaIds(), callback);
    }
    private void afterSave() {
        if (!current()) return;
        if (!state.publishRequested) { complete("草稿已保存"); return; }
        state.phase = "PUBLISH"; state.needsReconcile = true;
        state.message = "草稿已保存，正在提交审核…";
        if (!notifyChange()) return;
        api.publishPost(state.postId, new CommunityClient.Callback<PostStatus>() {
            public void onSuccess(PostStatus result) {
                if (!current()) return;
                if (!"PENDING".equals(result.getStatus()) && !"PUBLISHED".equals(result.getStatus())) { reconcile(); return; }
                state.status = result.getStatus();
                complete("已提交审核，审核通过后公开");
            }
            public void onFailure(String error) { if (current()) reconcile(); }
        });
    }
    public void reconcile() {
        if (!current() || state.postId == null) return;
        busy = true; state.needsReconcile = true; state.phase = "CHECK";
        state.message = "正在核对服务器上的作品状态…";
        notifyChange();
        api.getPost(state.postId, new CommunityClient.Callback<PostItem>() {
            public void onSuccess(PostItem post) {
                if (!current()) return;
                String status = post.getStatus();
                if (status == null || !status.matches("DRAFT|REJECTED|WITHDRAWN|PENDING|PUBLISHED")) {
                    fail("作品状态不完整，请重新核对。"); return;
                }
                state.status = status;
                state.needsReconcile = false;
                state.savedPayload = matches(post) ? payload() : null;
                if (state.publishRequested && ("PENDING".equals(status) || "PUBLISHED".equals(status))) {
                    complete("已确认作品已提交审核或公开"); return;
                }
                fail("PENDING".equals(status) || "PUBLISHED".equals(status)
                        ? "作品已提交审核或公开，请到我的作品查看。"
                        : "已核对作品状态，已上传的图片和草稿会复用，可继续编辑或提交。");
            }
            public void onFailure(String error) { fail(error + "；请先重新核对作品状态。"); }
        });
    }
    private void fail(String message) {
        if (!current()) return;
        busy = false; state.phase = "IDLE"; state.message = message;
        notifyChange();
    }
    private void complete(String message) {
        busy = false; state.phase = "IDLE"; state.needsReconcile = false; state.done = true; state.message = message;
        notifyChange();
    }
    @Override protected void onCleared() {
        cleared = true;
        if (assistSubscription != null) assistSubscription.close();
        images.shutdownNow();
        // Already-issued server writes are not cancelled. No later stage starts after this editor closes.
    }
}
