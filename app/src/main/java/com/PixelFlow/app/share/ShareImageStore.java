package com.PixelFlow.app.share;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class ShareImageStore {
    private ShareImageStore() { }
    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }
    public static Uri save(Context context, Bitmap bitmap, String postId) throws IOException {
        checkCancelled();
        String name = "pixelflow_" + postId.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PixelFlow/");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore insert failed");
            boolean complete = false;
            try {
                try (OutputStream output = context.getContentResolver().openOutputStream(uri)) { encode(bitmap, output); }
                checkCancelled();
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, values, null, null) != 1) throw new IOException("MediaStore publish failed");
                complete = true;
                return uri;
            } finally { if (!complete) context.getContentResolver().delete(uri, null, null); }
        }
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PixelFlow");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create Pictures/PixelFlow");
        File file = new File(directory, name);
        boolean complete = false;
        try {
            try (OutputStream output = new FileOutputStream(file)) { encode(bitmap, output); }
            checkCancelled();
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{"image/png"}, null);
            complete = true;
            return uri;
        } finally { if (!complete) file.delete(); }
    }
    private static void encode(Bitmap bitmap, OutputStream output) throws IOException {
        if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encode failed");
        output.flush();
        checkCancelled();
    }
}
