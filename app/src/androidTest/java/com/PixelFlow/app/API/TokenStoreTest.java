package com.PixelFlow.app.API;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** Persisted-session lifecycle: restart recovery, logout clear, undecryptable fallback. */
@RunWith(AndroidJUnit4.class)
public class TokenStoreTest {
    private Context context;

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TokenStore.clear(context);
    }
    @After public void teardown() {
        TokenStore.clear(context);
    }

    @Test public void saveThenLoadRoundTripsTokens() {
        TokenStore.save(context, "access-token", "refresh-token", "alice");
        String[] fields = TokenStore.load(context);
        assertNotNull(fields);
        assertEquals(3, fields.length);
        assertEquals("access-token", fields[0]);
        assertEquals("refresh-token", fields[1]);
        assertEquals("alice", fields[2]);
    }

    @Test public void absentStoreLoadsNull() {
        assertNull(TokenStore.load(context));
    }

    @Test public void clearAfterSaveLoadsNull() {
        TokenStore.save(context, "a", "r", "u");
        TokenStore.clear(context);
        assertNull(TokenStore.load(context));
    }

    @Test public void corruptCiphertextLoadsNullWithoutCrash() {
        TokenStore.save(context, "a", "r", "u");
        context.getSharedPreferences("pixelflow_session", Context.MODE_PRIVATE)
                .edit().putString("tokens", "!!!not-valid-base64!!!").commit();
        // Undecryptable data falls back to logged-out silently and clears the corrupt entry.
        assertNull(TokenStore.load(context));
        assertNull(TokenStore.load(context));
    }

    @Test public void freshApiClientRestoresTokensFromStore() throws Exception {
        TokenStore.save(context, "restored-access", "restored-refresh", "bob");
        Field singleton = ApiClient.class.getDeclaredField("instance"); singleton.setAccessible(true);
        ApiClient previous = (ApiClient) singleton.get(null);
        singleton.set(null, null);
        ApiClient restored = null;
        try {
            restored = ApiClient.getInstance(context);
            assertTrue(restored.isLoggedIn());
            assertEquals("bob", restored.username());
        } finally {
            if (restored != null) restored.close();
            singleton.set(null, previous);
        }
    }
}
