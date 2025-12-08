package com.PixelFlow.app;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.PixelFlow.app.Streams.PreloadManager;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements HomeFragment.OnScrollListener {

    private BottomNavigationView bottomNavigationView;
    private FloatingActionButton fabCamera;
    private boolean isFabVisible = true;

    private PreloadManager preloadManager;

    // 全局音乐状态管理
    private static boolean isGlobalMuted = false; // 全局静音状态，冷启后重置为false
    private static WeakReference<AudioPlayer> currentAudioPlayerRef = null; // 当前活动的音频播放器
    private static WorkItem.Music currentPlayingMusic = null; // 当前播放的音乐

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

    // 获取全局静音状态
    public static boolean isGlobalMuted() {
        return isGlobalMuted;
    }

    // 设置全局静音状态
    public static void setGlobalMuted(boolean muted) {
        isGlobalMuted = muted;

        // 如果有活动的音频播放器，更新其状态
        AudioPlayer player = getCurrentAudioPlayer();
        if (player != null) {
            player.updateMuteState(muted);
        }

        // 如果静音了，清除当前播放的音乐记录
        if (muted) {
            clearCurrentPlayingMusic();
        }
    }

    // 切换全局静音状态
    public static void toggleGlobalMuted() {
        setGlobalMuted(!isGlobalMuted);
    }

    // 注册当前活动的音频播放器
    public static void registerAudioPlayer(AudioPlayer player) {
        currentAudioPlayerRef = new WeakReference<>(player);
    }

    // 注销音频播放器
    public static void unregisterAudioPlayer() {
        if (currentAudioPlayerRef != null) {
            currentAudioPlayerRef.clear();
            currentAudioPlayerRef = null;
        }
    }

    // 获取当前活动的音频播放器
    public static AudioPlayer getCurrentAudioPlayer() {
        return currentAudioPlayerRef != null ? currentAudioPlayerRef.get() : null;
    }

    // 设置当前播放的音乐
    public static void setCurrentPlayingMusic(WorkItem.Music music) {
        currentPlayingMusic = music;
    }

    // 获取当前播放的音乐
    public static WorkItem.Music getCurrentPlayingMusic() {
        return currentPlayingMusic;
    }

    // 清空当前播放的音乐
    public static void clearCurrentPlayingMusic() {
        currentPlayingMusic = null;
    }

    // 获取播放器的单例
    public static AudioPlayer getGlobalAudioPlayer(Context context) {
        return AudioPlayer.getInstance(context);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (preloadManager != null) {
            preloadManager.release();
            preloadManager = null;
        }

        unregisterAudioPlayer();
        clearCurrentPlayingMusic();
    }
}