package com.PixelFlow.app;

import androidx.lifecycle.*;
import com.PixelFlow.app.API.*;

/** Retains edits and an in-flight save across rotation; no account data is persisted to disk. */
public final class ProfileEditorModel extends ViewModel {
    final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    final CommunityClient api;
    final long session;
    CurrentUser original;
    String nickname = "", bio = "", message = "";
    boolean loading, saving, uncertain, saved;
    private CommunityClient.Job job;
    ProfileEditorModel(CommunityClient api) { this.api = api; session = api.sessionVersion(); reload(); }
    boolean validSession() { return api.isLoggedIn() && api.sessionVersion() == session; }
    boolean dirty() { return original != null && (!original.nickname.equals(nickname.trim()) || !original.bio.equals(bio)); }
    boolean canSave() { return validSession() && original != null && dirty() && !loading && !saving && !uncertain
            && !nickname.trim().isEmpty() && nickname.trim().length() <= 40 && bio.length() <= 60; }
    void draft(String name, String description) { nickname = name; bio = description; }
    private void notifyChanged() { changes.setValue(changes.getValue() + 1); }
    void reload() {
        if (!validSession() || loading || saving) return;
        final boolean keepDraft = original != null;
        final boolean verifyWrite = uncertain;
        loading = true; message = "正在读取个人资料…"; notifyChanged();
        job = api.getCurrentUser(new CommunityClient.Callback<CurrentUser>() {
            public void onSuccess(CurrentUser value) {
                loading = false;
                if (!validSession()) { invalidate(); return; }
                original = value;
                if (!keepDraft) { nickname = value.nickname; bio = value.bio; }
                uncertain = false; saved = verifyWrite && !dirty();
                message = saved ? "已确认资料保存成功" : keepDraft ? "已核对服务器资料，未保存的编辑内容已保留。" : "";
                notifyChanged();
            }
            public void onFailure(String error) {
                loading = false;
                if (!validSession()) { invalidate(); return; }
                message = error; notifyChanged();
            }
        });
    }
    void save() {
        if (!canSave()) return;
        saving = true; message = ""; notifyChanged();
        job = api.updateCurrentUser(original.id, nickname, bio, new CommunityClient.Callback<CurrentUser>() {
            public void onSuccess(CurrentUser value) {
                saving = false;
                if (!validSession()) { invalidate(); return; }
                original = value; nickname = value.nickname; bio = value.bio; saved = true; notifyChanged();
            }
            public void onFailure(String error) {
                saving = false;
                if (!validSession()) { invalidate(); return; }
                uncertain = true; message = "保存尚未确认，修改内容已保留。请先重新读取并核对。\n" + error; notifyChanged();
            }
        });
    }
    private void invalidate() { original = null; nickname = bio = ""; message = "登录状态已变化，请重新登录"; notifyChanged(); }
    @Override protected void onCleared() { if (job != null) job.cancel(); }
}
