package com.PixelFlow.app.share;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ShareFileProviderTest {
    @Test public void ownedPublicPictureCanBeReadAndDeletedThroughNarrowProvider() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap source = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
        Uri media = null;
        try {
            media = ShareImageStore.save(context, source, "fileprovider-test");
            String path;
            try (android.database.Cursor cursor = context.getContentResolver().query(media,
                    new String[]{MediaStore.Images.Media.DATA}, null, null, null)) {
                assertNotNull(cursor); assertTrue(cursor.moveToFirst()); path = cursor.getString(0);
            }
            File file = new File(path); assertTrue(file.isFile());
            Uri provider = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            assertEquals("share_pictures", provider.getPathSegments().get(0));
            try (InputStream input = context.getContentResolver().openInputStream(provider)) {
                Bitmap decoded = BitmapFactory.decodeStream(input); assertNotNull(decoded);
                assertEquals(48, decoded.getWidth()); assertEquals(32, decoded.getHeight()); decoded.recycle();
            }
            assertEquals(1, context.getContentResolver().delete(provider, null, null));
            assertFalse(file.exists());
            try {
                FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider",
                        new File(file.getParentFile().getParentFile(), "outside-share.png"));
                fail("Pictures outside PixelFlow must not be exposed");
            } catch (IllegalArgumentException expected) { }
        } finally {
            source.recycle();
            if (media != null) context.getContentResolver().delete(media, null, null);
        }
    }
}
