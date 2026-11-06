package com.PixelFlow.app.share;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.R;
import com.PixelFlow.app.util.DateUtils;

public final class ShareCardRenderer {
    public static final int WIDTH = 1080, MAX_HEIGHT = 4096;
    private ShareCardRenderer() { }

    public static View create(Context context, PostItem post, String link, Bitmap cover, Bitmap avatar) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.densityDpi = 160;
        config.fontScale = 1;
        Context pixels = context.createConfigurationContext(config);
        View root = LayoutInflater.from(pixels).inflate(R.layout.share_card, null);
        ImageView image = root.findViewById(R.id.share_cover);
        image.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(94, 105, 210), Color.rgb(170, 209, 235)}));
        if (cover != null) image.setImageBitmap(cover);
        ImageView portrait = root.findViewById(R.id.share_avatar);
        if (avatar != null) portrait.setImageBitmap(avatar);
        else portrait.setImageResource(R.drawable.ic_avatar_placeholder);
        text(root, R.id.share_card_title, post.getTitle());
        text(root, R.id.share_card_body, post.getContent());
        String author = post.getAuthor() == null ? null : post.getAuthor().getNickname();
        text(root, R.id.share_author, author == null || author.isEmpty() ? context.getString(R.string.share_author_fallback) : author);
        text(root, R.id.share_time, DateUtils.formatRelativeTime(ShareData.timestamp(post)));
        text(root, R.id.share_statistics, context.getString(R.string.share_stats, post.getLikeCount(), ShareData.comments(post)));
        text(root, R.id.share_link, link);
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(MAX_HEIGHT, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, WIDTH, root.getMeasuredHeight());
        return root;
    }

    public static Bitmap render(Context context, PostItem post, String link, Bitmap cover, Bitmap avatar) {
        View root = create(context, post, link, cover, avatar);
        Bitmap output = Bitmap.createBitmap(WIDTH, root.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        try { root.draw(new Canvas(output)); return output; }
        catch (RuntimeException | OutOfMemoryError error) { output.recycle(); throw error; }
    }

    private static void text(View root, int id, String value) {
        ((TextView) root.findViewById(id)).setText(value == null ? "" : value);
    }
}
