package com.PixelFlow.app.share;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PostItem;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.FutureTarget;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class ShareImageJob {
    interface Callback { void complete(Uri uri); void failed(); }
    private final Context context;
    private final ApiClient api;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RequestManager glide;
    private final List<FutureTarget<?>> targets = new ArrayList<>();
    private final List<File> temporary = new ArrayList<>();
    private final List<CommunityClient.Job> reads = new ArrayList<>();
    private final long session;
    private volatile boolean cancelled;

    ShareImageJob(Context context, ApiClient api) {
        this.context = context.getApplicationContext(); this.api = api;
        glide = Glide.with(this.context); session = api.sessionVersion();
    }
    void cancel() {
        cancelled = true;
        synchronized (reads) { for (CommunityClient.Job job : reads) job.cancel(); }
    }
    void run(PostItem post, String link, Callback callback) {
        Bitmap output = null;
        Uri saved = null;
        FutureTask<Boolean> delivery = null;
        FutureTask<Bitmap> rendering = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            PostItem.Clip clip = post.getClips() == null || post.getClips().isEmpty() ? null : post.getClips().get(0);
            Bitmap cover = load(clip == null ? null : clip.getUrl(), clip != null && clip.getType() == 1, 1080, 720, deadline);
            Bitmap avatar = load(post.getAuthor() == null ? null : post.getAuthor().getAvatar(), false, 96, 96, deadline);
            check();
            rendering = new FutureTask<>(() -> cancelled ? null : ShareCardRenderer.render(context, post, link, cover, avatar));
            main.post(rendering);
            output = rendering.get();
            check();
            saved = ShareImageStore.save(context, output, post.getPostId());
            Uri uri = saved;
            delivery = new FutureTask<>(() -> {
                if (cancelled) return false;
                if (session != api.sessionVersion()) { callback.failed(); return false; }
                callback.complete(uri);
                return true;
            });
            main.post(delivery);
            if (delivery.get()) saved = null;
        } catch (Exception | OutOfMemoryError error) {
            main.post(() -> { if (!cancelled) callback.failed(); });
        } finally {
            if (saved != null) {
                boolean interrupted = Thread.interrupted();
                boolean delivered = false;
                if (delivery != null) {
                    try { delivered = delivery.get(); } catch (Exception ignored) { }
                }
                if (!delivered) {
                    try { context.getContentResolver().delete(saved, null, null); } catch (RuntimeException ignored) { }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (rendering != null && output == null) {
                boolean interrupted = Thread.interrupted();
                try { output = rendering.get(); } catch (Exception ignored) { }
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (output != null) output.recycle();
            for (FutureTarget<?> target : targets) glide.clear(target);
            for (File file : temporary) file.delete();
        }
    }
    private void check() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted() || session != api.sessionVersion()) throw new InterruptedException();
    }
    private Bitmap load(String url, boolean video, int width, int height, long deadline) throws InterruptedException {
        check();
        if (url == null || url.isEmpty() || System.nanoTime() >= deadline) return null;
        CommunityClient.Job read = null;
        try {
            Object model = url;
            if (api.isPrivateMediaUrl(url)) {
                CompletableFuture<byte[]> bytes = new CompletableFuture<>();
                synchronized (reads) {
                    check();
                    read = api.getMediaContent(url, new CommunityClient.Callback<byte[]>() {
                        public void onSuccess(byte[] value) { bytes.complete(value); }
                        public void onFailure(String message) { bytes.completeExceptionally(new IllegalStateException(message)); }
                    });
                    reads.add(read);
                }
                byte[] content = bytes.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (video) {
                    File file = File.createTempFile("share-video-", ".mp4", context.getCacheDir()); temporary.add(file);
                    try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(content); }
                    model = file;
                } else model = content;
            }
            check();
            if (video && model instanceof String) {
                FutureTarget<File> download = glide.downloadOnly().load(model)
                        .diskCacheStrategy(DiskCacheStrategy.DATA).submit();
                targets.add(download);
                model = download.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            com.bumptech.glide.RequestBuilder<Bitmap> builder = glide.asBitmap().load(model)
                    .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).disallowHardwareConfig()
                    .override(width, height).centerCrop();
            if (video) builder = builder.frame(0);
            FutureTarget<Bitmap> target = builder.submit(width, height); targets.add(target);
            return target.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception ignored) { return null; }
        finally { if (read != null) read.cancel(); }
    }
}
