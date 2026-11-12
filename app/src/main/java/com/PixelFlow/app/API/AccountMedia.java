package com.PixelFlow.app.API;

import android.widget.ImageView;
import androidx.annotation.Nullable;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

/** Private image bytes never enter Glide's disk/memory cache or redirect credentials. */
public final class AccountMedia {
    private AccountMedia() { }
    private static final class Binding {
        CommunityClient.Job job;
    }

    /** 私有媒体仅内存缓存（不落盘、会话内有效），避免每次进详情页都重新下载。 */
    private static final android.util.LruCache<String, byte[]> PRIVATE_BYTES =
            new android.util.LruCache<String, byte[]>(32 * 1024 * 1024) {
                @Override protected int sizeOf(String key, byte[] value) { return value.length; }
            };

    private static void bindBytes(ImageView view, byte[] bytes, boolean centerCrop, Runnable onDone) {
        com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> req =
                Glide.with(view.getContext().getApplicationContext())
                        .load(bytes).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                        .error(R.drawable.ic_image_error);
        if (centerCrop) req = req.centerCrop();
        req.listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
            @Override public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e, Object model,
                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean first) {
                if (onDone != null) onDone.run();
                return false;
            }
            @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                    com.bumptech.glide.load.DataSource source, boolean first) {
                if (onDone != null) onDone.run();
                return false;
            }
        }).into(view);
    }
    public static void clear(ImageView view) {
        Object previous = view.getTag(R.id.account_media_binding);
        if (previous instanceof Binding && ((Binding) previous).job != null) ((Binding) previous).job.cancel();
        view.setTag(R.id.account_media_binding, null);
        // clear() must also work during Activity destruction, when with(view) rejects new managers.
        Glide.with(view.getContext().getApplicationContext()).clear(view);
        view.setImageResource(R.drawable.ic_image_placeholder);
    }
    public static void load(ImageView view, String url) {
        loadInto(view, url, true, null);
    }

    /**
     * 详情页用：不裁切（保留原始宽高比），并回调加载完成/失败以便隐藏进度条。
     * 私有媒体带账号凭证拉字节流，公开媒体直接 Glide。
     */
    public static void loadDetail(ImageView view, String url, Runnable onDone) {
        loadInto(view, url, false, onDone);
    }

    private static void loadInto(ImageView view, String url, boolean centerCrop, Runnable onDone) {
        clear(view);
        ApiClient api = ApiClient.getInstance(view.getContext());
        // 统一用 application context：返回/销毁时 Glide.with(view) 可能因 Activity 已销毁而抛异常
        if (!api.isPrivateMediaUrl(url)) {
            // Third-party/public images receive no account headers and use normal Glide caching.
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> req = Glide.with(view.getContext().getApplicationContext())
                    .load(url).diskCacheStrategy(DiskCacheStrategy.ALL).timeout(5000)
                    .placeholder(R.drawable.ic_image_placeholder).error(R.drawable.ic_image_error);
            if (centerCrop) req = req.centerCrop();
            req.listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                @Override public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e, Object model,
                        com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean first) {
                    if (onDone != null) onDone.run();
                    return false;
                }
                @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                        com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                        com.bumptech.glide.load.DataSource source, boolean first) {
                    if (onDone != null) onDone.run();
                    return false;
                }
            }).into(view);
            return;
        }
        Binding binding = new Binding();
        view.setTag(R.id.account_media_binding, binding);
        long session = api.sessionVersion();
        // 命中内存缓存直接复用，避免重复下载
        byte[] cached = PRIVATE_BYTES.get(url);
        if (cached != null) {
            bindBytes(view, cached, centerCrop, onDone);
            return;
        }
        binding.job = api.getMediaContent(url, new CommunityClient.Callback<byte[]>() {
            private boolean current() {
                return view.getTag(R.id.account_media_binding) == binding && session == api.sessionVersion();
            }
            public void onSuccess(byte[] bytes) {
                if (!current()) return;
                PRIVATE_BYTES.put(url, bytes);
                bindBytes(view, bytes, centerCrop, onDone);
            }
            public void onFailure(String ignored) {
                if (!current()) return;
                if (onDone != null) onDone.run();
                view.setImageResource(R.drawable.ic_image_error);
            }
        });
    }
}
