package com.PixelFlow.app.creation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.*;

public final class DraftImage {
    public static byte[] compress(android.content.ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int w = bounds.outWidth, h = bounds.outHeight;
        if (w <= 0 || h <= 0) throw new IOException("无法读取图片");

        int sample = 1;
        while ((long) (w / sample) * (h / sample) > 4_000_000L || (w / sample) > 8192 || (h / sample) > 8192) {
            sample <<= 1;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = resolver.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("无法解码图片");
        try {
            long pixels = (long) bmp.getWidth() * bmp.getHeight();
            if (pixels > 4_000_000L) {
                float scale = (float) Math.sqrt(4_000_000.0 / pixels);
                int nw = Math.max(1, (int) (bmp.getWidth() * scale));
                int nh = Math.max(1, (int) (bmp.getHeight() * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            int quality = 90;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
            while (out.size() > 8 * 1024 * 1024 && quality > 40) {
                quality -= 10;
                out.reset();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
            }
            return out.toByteArray();
        } finally {
            bmp.recycle();
        }
    }

}
