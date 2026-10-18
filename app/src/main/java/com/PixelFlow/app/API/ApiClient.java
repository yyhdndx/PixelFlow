package com.PixelFlow.app.API;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.util.Properties;

public final class ApiClient extends CommunityClient {
    private static ApiClient instance;
    private ApiClient(Context context) {
        super(configuredUrl(context), command -> new Handler(Looper.getMainLooper()).post(command));
    }
    public static synchronized ApiClient getInstance(Context context) {
        if (instance == null) instance = new ApiClient(context.getApplicationContext());
        return instance;
    }
    private static String configuredUrl(Context context) {
        try (InputStream input = context.getAssets().open("api.properties")) {
            Properties values = new Properties(); values.load(input);
            return values.getProperty("url", "").trim();
        } catch (Exception ignored) { return ""; }
    }
}
