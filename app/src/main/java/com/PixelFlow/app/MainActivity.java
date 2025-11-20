package com.PixelFlow.app;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 关键代码：使用activity_main.xml布局
        setContentView(R.layout.activity_main);

        // 现在Activity就知道使用哪个布局文件了
        // 接下来可以初始化视图组件
        initView();
    }

    private void initView() {
        // 这里可以找到布局中的视图组件
        // 比如：BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
    }
}
