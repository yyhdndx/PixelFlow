package com.PixelFlow.app;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.Window;
import androidx.appcompat.app.AppCompatActivity;

/** 所有 Activity 的基类：统一字号偏好（小/标准/大）+ 沉浸式隐藏系统栏。 */
public abstract class BaseActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.fontScale = FontScaleUtil.get(newBase);
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    /** 与首页一致：沉浸式隐藏状态栏（时间/电量）与导航栏。 */
    private void hideSystemBars() {
        Window window = getWindow();
        if (window == null) return;
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
