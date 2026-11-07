package com.PixelFlow.app.API;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.InputStream;

public final class ApiClient extends CommunityClient {
    private static ApiClient instance;
    private ApiClient(Context context) {
        super(configuredUrl(context), command -> new Handler(Looper.getMainLooper()).post(command),
                new File(context.getCacheDir(), "http-cache"));
        String[] saved = TokenStore.load(context);
        if (saved != null) restore(saved[0], saved[1], saved[2]);
        setTokenListener(new TokenListener() {
            public void onTokens(String access, String refresh, String username) {
                TokenStore.save(context, access, refresh, username);
            }
            public void onCleared() { TokenStore.clear(context); }
        });
    }
    ApiClient(String baseUrl) {
        super(baseUrl, command -> new Handler(Looper.getMainLooper()).post(command));
    }
    public static synchronized ApiClient getInstance(Context context) {
        if (instance == null) instance = new ApiClient(context.getApplicationContext());
        return instance;
    }
    private static String configuredUrl(Context context) {
        try (InputStream input = context.getAssets().open("api.properties")) {
            return ApiConfiguration.readUrl(input);
        } catch (Exception ignored) { return ""; }
    }
}
