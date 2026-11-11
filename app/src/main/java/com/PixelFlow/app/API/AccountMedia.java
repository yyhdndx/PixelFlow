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
            // Third-party/public images receive no account headers and no account cache.
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> req = Glide.with(view.getContext().getApplicationContext())
                    .load(url).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
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
        binding.job = api.getMediaContent(url, new CommunityClient.Callback<byte[]>() {
            private boolean current() {
                return view.getTag(R.id.account_media_binding) == binding && session == api.sessionVersion();
            }
            public void onSuccess(byte[] bytes) {
                if (!current()) return;
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> req = Glide.with(view.getContext().getApplicationContext())
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
            public void onFailure(String ignored) {
                if (!current()) return;
                if (onDone != null) onDone.run();
                view.setImageResource(R.drawable.ic_image_error);
            }
        });
    }
}
