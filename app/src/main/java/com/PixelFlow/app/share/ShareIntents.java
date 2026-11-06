package com.PixelFlow.app.share;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import com.PixelFlow.app.API.PostItem;

public final class ShareIntents {
    private ShareIntents() { }
    public static Intent text(PostItem post, String link) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, post.getTitle()).putExtra(Intent.EXTRA_TEXT, ShareData.text(post, link));
    }
    public static Intent image(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("PixelFlow", uri));
        return intent;
    }
}
