package com.PixelFlow.app;

import android.content.Context;

/** 字号偏好：小 / 标准 / 大三档，存 SharedPreferences，经 BaseActivity 全局生效。 */
public final class FontScaleUtil {
    public static final float SMALL = 0.85f;
    public static final float NORMAL = 1.0f;
    public static final float LARGE = 1.2f;

    private static final String PREFS = "display_prefs";
    private static final String KEY = "font_scale";

    private FontScaleUtil() {}

    public static float get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, NORMAL);
    }

    public static void set(Context context, float scale) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, scale).apply();
    }
}
