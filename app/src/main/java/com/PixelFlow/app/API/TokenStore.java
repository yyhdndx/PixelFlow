package com.PixelFlow.app.API;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Session tokens at rest: AES-GCM under an Android Keystore key, ciphertext in private prefs.
 * Never plaintext SharedPreferences; a missing/corrupt key falls back to logged-out silently.
 */
public final class TokenStore {
    private static final String PREFS = "pixelflow_session";
    private static final String KEY_ALIAS = "pixelflow_session_key";
    private static final String KEY_DATA = "tokens";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final Gson GSON = new Gson();

    private TokenStore() {}

    public static void save(Context context, String access, String refresh, String username) {
        try {
            String json = GSON.toJson(new String[]{access, refresh, username});
            byte[] ciphertext = encrypt(json.getBytes(StandardCharsets.UTF_8));
            prefs(context).edit().putString(KEY_DATA,
                    Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply();
        } catch (Exception ignored) {
            // 持久化失败不阻断本次登录；下次启动回退到未登录。
        }
    }

    /** Returns {access, refresh, username}, or null when absent or undecryptable. */
    public static String[] load(Context context) {
        try {
            String encoded = prefs(context).getString(KEY_DATA, null);
            if (encoded == null || encoded.isEmpty()) return null;
            byte[] plain = decrypt(Base64.decode(encoded, Base64.NO_WRAP));
            String[] fields = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), String[].class);
            if (fields == null || fields.length < 3) return null;
            return fields;
        } catch (Exception ignored) {
            clear(context);
            return null;
        }
    }

    public static void clear(Context context) {
        try {
            prefs(context).edit().remove(KEY_DATA).apply();
        } catch (Exception ignored) {
            // 存储层异常不影响会话内存状态。
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static byte[] encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
        return out;
    }

    private static byte[] decrypt(byte[] data) throws Exception {
        if (data.length <= IV_LENGTH) throw new IllegalArgumentException("ciphertext too short");
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, IV_LENGTH);
        byte[] encrypted = new byte[data.length - IV_LENGTH];
        System.arraycopy(data, IV_LENGTH, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(encrypted);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS))
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
