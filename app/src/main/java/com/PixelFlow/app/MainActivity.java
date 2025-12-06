package com.PixelFlow.app;

import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.PixelFlow.app.Streams.PreloadManager;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements HomeFragment.OnScrollListener {

    private BottomNavigationView bottomNavigationView;
    private FloatingActionButton fabCamera;
    private boolean isFabVisible = true;

    private PreloadManager preloadManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
        setupBottomNavigation();
        setupCameraButton();

        // 默认显示首页
        if (savedInstanceState == null) {
            showFragment(HomeFragment.newInstance());
            bottomNavigationView.setSelectedItemId(R.id.nav_home);
        }

        preloadManager = new PreloadManager(this);
    }

    private void initView() {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        fabCamera = findViewById(R.id.fab_camera);
    }

    private void setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;

            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                selectedFragment = HomeFragment.newInstance();
            }
            // 其他导航项可以在这里添加
            // else if (itemId == R.id.nav_friends) {
            //     selectedFragment = FriendsFragment.newInstance();
            // } else if (itemId == R.id.nav_messages) {
            //     selectedFragment = MessagesFragment.newInstance();
            // } else if (itemId == R.id.nav_profile) {
            //     selectedFragment = ProfileFragment.newInstance();
            // }

            if (selectedFragment != null) {
                showFragment(selectedFragment);
                return true;
            }

            return false;
        });
    }

    private void setupCameraButton() {
        fabCamera.setOnClickListener(v -> {
            // 处理相机按钮点击事件
            openCamera();
        });
    }

    private void openCamera() {
        // 这里实现打开相机的逻辑
        // 例如：启动相机Activity、打开相机Fragment、请求权限等

        // 示例：显示一个提示
        // Toast.makeText(this, "打开相机", Toast.LENGTH_SHORT).show();

        // 后续你可以在这里添加真实的相机功能
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    // 实现HomeFragment的滚动监听接口
    @Override
    public void onScrollDown() {
        // 向下滚动时隐藏FAB
        if (isFabVisible && fabCamera != null) {
            hideFab();
        }
    }

    @Override
    public void onScrollUp() {
        // 向上滚动时显示FAB
        if (!isFabVisible && fabCamera != null) {
            showFab();
        }
    }

    @Override
    public void onScrollToTop() {
        // 滚动到顶部时显示FAB
        if (!isFabVisible && fabCamera != null) {
            showFab();
        }
    }

    private void hideFab() {
        if (fabCamera != null) {
            // 使用动画将FAB滑出屏幕
            fabCamera.animate()
                    .translationY(fabCamera.getHeight() + getBottomNavigationHeight() + 50)
                    .setInterpolator(new AccelerateInterpolator())
                    .setDuration(250)
                    .withEndAction(() -> isFabVisible = false)
                    .start();
        }
    }

    private void showFab() {
        if (fabCamera != null) {
            // 使用动画将FAB滑入屏幕
            fabCamera.animate()
                    .translationY(0)
                    .setInterpolator(new DecelerateInterpolator())
                    .setDuration(250)
                    .withEndAction(() -> isFabVisible = true)
                    .start();
        }
    }

    private int getBottomNavigationHeight() {
        View bottomNav = findViewById(R.id.bottom_navigation);
        return bottomNav != null ? bottomNav.getHeight() : 0;
    }

    public void preloadForDetailPage(WorkItem workItem) {
        if (workItem != null && preloadManager != null) {
            List<WorkItem> items = new ArrayList<>();
            items.add(workItem);
            preloadManager.preloadWorkItems(items, PreloadManager.PRIORITY_HIGH);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (preloadManager != null) {
            preloadManager.release();
            preloadManager = null;
        }
    }
}