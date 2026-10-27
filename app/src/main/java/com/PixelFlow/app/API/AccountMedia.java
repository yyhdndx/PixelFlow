package com.PixelFlow.app.API;

import android.widget.ImageView;
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
        clear(view);
        ApiClient api = ApiClient.getInstance(view.getContext());
        if (!api.isPrivateMediaUrl(url)) {
            // Third-party/public images receive no account headers and no account cache.
            Glide.with(view).load(url).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                    .placeholder(R.drawable.ic_image_placeholder).error(R.drawable.ic_image_error)
                    .centerCrop().into(view);
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
                Glide.with(view).load(bytes).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                        .error(R.drawable.ic_image_error).centerCrop().into(view);
            }
            public void onFailure(String ignored) {
                if (current()) view.setImageResource(R.drawable.ic_image_error);
            }
        });
    }
}
