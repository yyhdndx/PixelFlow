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
        public boolean creationUnknown, needsReconcile, done, publishRequested;
        public ArrayList<MediaEntry> media = new ArrayList<>();
    }
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public State state;
    public boolean busy;
    public boolean assistBusy;
    public CreationSuggestion suggestion;
    public String assistMessage = "";
    private String assistSnapshot;
    private CommunityClient.Job assistJob;
    private int assistGeneration;
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
        return journal().edit().putString(state.editorId, new Gson().toJson(state)).commit();
    }
    public void discard() { discarded = true; journal().edit().remove(state.editorId).apply(); }
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
        if (!editable() || assistBusy) return;
        suggestion = null; assistSnapshot = textSnapshot(); assistBusy = true;
        assistMessage = "正在生成建议…";
        int request = ++assistGeneration;
        changes.setValue(changes.getValue() + 1);
        java.util.List<String> tags = new ArrayList<>();
        for (String tag : state.tags.split("\\s+")) if (!tag.isEmpty() && tag.length() <= 32 && tags.size() < 20) tags.add(tag);
        assistJob = api.createAssist(state.title, state.content, tags, goal, prompt, audience,
                new CommunityClient.Callback<CreationSuggestion>() {
            public void onSuccess(CreationSuggestion value) {
                if (!current() || request != assistGeneration) return;
                assistBusy = false;
                if (!assistSnapshot.equals(textSnapshot())) {
                    assistMessage = "草稿已修改，请重新生成建议。";
                } else { suggestion = value; assistMessage = "建议已生成，确认后才会填入草稿。"; }
                changes.setValue(changes.getValue() + 1);
            }
            public void onFailure(String error) {
                if (!current() || request != assistGeneration) return;
                assistBusy = false; assistMessage = "生成失败，原稿已保留。可重试或继续手动编辑。";
                changes.setValue(changes.getValue() + 1);
            }
        });
    }
    public void cancelAssist() {
        ++assistGeneration;
        if (assistJob != null) assistJob.cancel();
        assistBusy = false; suggestion = null; assistMessage = "";
        changes.setValue(changes.getValue() + 1);
    }
    public boolean acceptAssist(String title, String content, boolean includeTags) {
        if (!editable() || suggestion == null || !textSnapshot().equals(assistSnapshot)) {
            assistMessage = "草稿已修改，请重新生成建议。"; suggestion = null;
            changes.setValue(changes.getValue() + 1); return false;
        }
        if (title != null && !suggestion.title_candidates.contains(title)) return false;
        if (content != null && !suggestion.content_candidates.contains(content)) return false;
        String nextContent = content == null ? state.content : content;
        // Current post API stores no separate tags: selected hashtags are explicit body text.
        if (includeTags) for (String tag : suggestion.tag_suggestions)
            if (!nextContent.contains(tag)) nextContent += "\n" + tag;
        if (nextContent.length() > 5000) { assistMessage = "加入话题后正文超过 5000 字，请减少内容。"; return false; }
        if (title != null) state.title = title;
        state.content = nextContent;
        suggestion = null; assistMessage = "已填入草稿，保存或提交审核后生效。";
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
    private String payload() { return new Gson().toJson(Arrays.asList(state.title.trim(), state.content.trim(), mediaIds())); }
    private boolean matches(PostItem post) {
        return Objects.equals(state.title.trim(), post.getTitle()) && Objects.equals(state.content.trim(), post.getContent())
                && Objects.equals(mediaIds(), post.getMediaIds());
    }
    public void resume() {
        if (!current() || busy || state.done) return;
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
        if (creating) api.createPost(state.creationKey, state.title.trim(), state.content.trim(), mediaIds(), callback);
        else api.updatePost(state.postId, state.title.trim(), state.content.trim(), mediaIds(), callback);
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
        if (assistJob != null) assistJob.cancel();
        images.shutdownNow();
        // Already-issued server writes are not cancelled. No later stage starts after this editor closes.
    }
}
