package com.PixelFlow.app;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatActivity;

/** 所有 Activity 的基类：统一应用用户字号偏好（小/标准/大）。 */
public abstract class BaseActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.fontScale = FontScaleUtil.get(newBase);
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }
}
