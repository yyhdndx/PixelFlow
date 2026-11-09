package com.PixelFlow.app.share;

import android.Manifest;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.R;
import com.PixelFlow.app.ui.ActionSheetDialog;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PostShareController {
    private final AppCompatActivity activity;
    private final String postId;
    private final ApiClient api;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> permission;
    private CommunityClient.Job read;
    private Dialog sheet, loading;
    private ShareImageJob imageJob;
    private Future<?> task;
    private boolean busy, closed, awaitingPermission;
    private int generation;

    public PostShareController(AppCompatActivity activity, String postId) {
        this.activity = activity; this.postId = postId;
        api = ApiClient.getInstance(activity);
        permission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!awaitingPermission || closed) return;
            awaitingPermission = false; busy = false;
            if (granted) confirm(2); else toast(R.string.share_permission_denied);
        });
    }

    public void show() {
        if (busy || sheet != null || !active()) return;
        fetch(post -> {
            sheet = ActionSheetDialog.show(activity, activity.getString(R.string.share_title), post.getTitle(), Arrays.asList(
                    option(android.R.drawable.ic_menu_set_as, R.string.share_copy, 0),
                    option(android.R.drawable.ic_menu_share, R.string.share_text, 1),
                    option(android.R.drawable.ic_menu_gallery, R.string.share_image, 2)));
            sheet.setOnDismissListener(dialog -> sheet = null);
        });
    }

    private ActionSheetDialog.Option option(int icon, int label, int action) {
        return new ActionSheetDialog.Option(icon, activity.getString(label), () -> confirm(action));
    }

    private void confirm(int action) {
        if (busy || !active()) return;
        if (action == 2 && Build.VERSION.SDK_INT <= 28
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            busy = true; awaitingPermission = true;
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        fetch(post -> {
            String link;
            try (InputStream input = activity.getAssets().open("api.properties")) {
                Properties values = new Properties(); values.load(input);
                link = ShareConfig.postLink(values, postId);
            } catch (Exception invalid) { toast(R.string.share_config_error); return; }
            if (action == 0) {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.share_title), link));
                    toast(R.string.share_copied);
                }
            } else if (action == 1) send(ShareIntents.text(post, link));
            else generate(post, link);
        });
    }

    private interface Ready { void accept(PostItem post); }
    private void fetch(Ready ready) {
        busy = true;
        final int version = ++generation;
        final long session = api.sessionVersion();
        read = api.getPost(postId, new CommunityClient.Callback<PostItem>() {
            private boolean current() { return active() && version == generation; }
            public void onSuccess(PostItem post) {
                if (!current()) return;
                busy = false; read = null;
                if (session != api.sessionVersion()) return;
                if (!ShareData.isPublic(post, postId)) { toast(R.string.share_not_public); return; }
                ready.accept(post);
            }
            public void onFailure(String message) {
                if (!current()) return;
                busy = false; read = null; toast(R.string.share_load_failed);
            }
        });
    }

    private void generate(PostItem post, String link) {
        busy = true;
        ProgressDialog progress = new ProgressDialog(activity, R.style.PixelFlowPlatformDialog);
        progress.setMessage(activity.getString(R.string.share_generating));
        progress.setCancelable(false);
        progress.setButton(Dialog.BUTTON_NEGATIVE, activity.getString(R.string.share_cancel), (dialog, which) -> cancel());
        progress.show(); loading = progress;
        final int version = generation;
        ShareImageJob job = new ShareImageJob(activity, api); imageJob = job;
        task = worker.submit(() -> job.run(post, link, new ShareImageJob.Callback() {
            public void complete(android.net.Uri uri) {
                if (!active() || generation != version) return;
                finishImage(); toast(R.string.share_saved); send(ShareIntents.image(uri));
            }
            public void failed() {
                if (!active() || generation != version) return;
                finishImage(); toast(R.string.share_failed);
            }
        }));
    }

    private void finishImage() {
        busy = false; imageJob = null; task = null;
        if (loading != null) loading.dismiss(); loading = null;
    }

    private void send(Intent intent) {
        try { activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_title))); }
        catch (android.content.ActivityNotFoundException missing) { toast(R.string.share_no_target); }
    }

    private boolean active() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private void toast(int message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }

    public void cancel() {
        ++generation;
        if (read != null) read.cancel(); read = null;
        if (imageJob != null) imageJob.cancel();
        if (task != null) task.cancel(true);
        if (sheet != null) sheet.dismiss(); sheet = null;
        awaitingPermission = false;
        finishImage();
    }

    public void close() { cancel(); closed = true; worker.shutdownNow(); }
}
