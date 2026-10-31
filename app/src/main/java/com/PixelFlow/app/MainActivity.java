package com.PixelFlow.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Toast;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.ButtonFragment.HomeFragment;
import com.PixelFlow.app.ButtonFragment.ProfileFragment;
import com.PixelFlow.app.Streams.PreloadManager;
import com.PixelFlow.app.Streams.WorkItem;
import com.PixelFlow.app.ui.ActionSheetDialog;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
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
            // The navigation listener creates the page; avoid two initial Home fragments/requests.
            bottomNavigationView.setSelectedItemId(R.id.nav_home);
        }

        preloadManager = new PreloadManager(this);

        initializeUIForSplashTransition();

        // 延迟播放主界面进入动画
        new Handler().postDelayed(this::playMainUIEntranceAnimation, 300); // 与SplashActivity的退出动画同步
    }

    private void initializeUIForSplashTransition() {
        // 初始状态：全部透明
        bottomNavigationView.setAlpha(0f);
        fabCamera.setAlpha(0f);

        // 底部导航从下方隐藏
        bottomNavigationView.setTranslationY(bottomNavigationView.getHeight() / 2);

        // FAB初始状态：缩小并旋转
        fabCamera.setScaleX(0f);
        fabCamera.setScaleY(0f);
        fabCamera.setRotation(-90f);
    }

    private void playMainUIEntranceAnimation() {
        // 1. 底部导航淡入上滑
        bottomNavigationView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(600)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        if (!isFabVisible) return;
        // 2. FAB弹跳进入（有节奏感）
        fabCamera.animate()
                .alpha(1f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(800)
                .setInterpolator(new BounceInterpolator())
                .setStartDelay(200)
                .withEndAction(() -> {
                    // 最后添加一个轻微的脉冲效果
                    fabCamera.animate()
                            .scaleX(1.05f)
                            .scaleY(1.05f)
                            .setDuration(150)
                            .withEndAction(() -> {
                                fabCamera.animate()
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .setDuration(100)
                                        .start();
                            })
                            .start();
                })
                .start();

        // 3. 播放轻微波纹效果
        playSubtleRippleEffect();
    }

    private void playSubtleRippleEffect() {
        // 在主界面也添加一个轻微的波纹效果
        View rippleView = new View(this);
        rippleView.setBackgroundResource(R.drawable.circle_ripple);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(100, 100);
        params.gravity = Gravity.CENTER;
        rippleView.setLayoutParams(params);

        FrameLayout rootLayout = findViewById(android.R.id.content);
        if (rootLayout != null) {
            rootLayout.addView(rippleView);

            rippleView.setAlpha(0.5f);
            rippleView.animate()
                    .scaleX(3f)
                    .scaleY(3f)
                    .alpha(0f)
                    .setDuration(800)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> rootLayout.removeView(rippleView))
                    .start();
        }
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
            } else if (itemId == R.id.nav_profile) {
                selectedFragment = ProfileFragment.newInstance();
            }
            // 其他导航项可以在这里添加
            // else if (itemId == R.id.nav_friends) {
            //     selectedFragment = FriendsFragment.newInstance();
            // } else if (itemId == R.id.nav_messages) {
            //     selectedFragment = MessagesFragment.newInstance();
            // }

            if (selectedFragment != null) {
                showFragment(selectedFragment);

                // 根据选择的Fragment调整FAB可见性
                if (selectedFragment instanceof ProfileFragment) {
                    // 在Profile页面隐藏FAB
                    if (isFabVisible && fabCamera != null) {
                        hideFab();
                    }
                } else if (selectedFragment instanceof HomeFragment) {
                    // 在Home页面恢复FAB
                    if (!isFabVisible && fabCamera != null) {
                        showFab();
                    }
                }

                return true;
            }

            return false;
        });
    }

    private void setupCameraButton() {
        fabCamera.setOnClickListener(v -> {
            // 处理相机按钮点击事件
            showCreateOptions();
        });
    }

    private void showCreateOptions() {
        // 未登录时直接跳转登录页，与点赞/关注保持一致，不弹提示框。
        if (!ApiClient.getInstance(this).isLoggedIn()) {
            startActivity(new Intent(this, AccountActivity.class));
            return;
        }
        List<ActionSheetDialog.Option> options = new ArrayList<>();
        options.add(new ActionSheetDialog.Option(R.drawable.ic_camera, "拍摄新作品", this::launchCamera));
        options.add(new ActionSheetDialog.Option(R.drawable.ic_photo, "从相册创建", this::createWorkFromGallery));
        options.add(new ActionSheetDialog.Option(R.drawable.ic_edit_note, "创建空白作品", this::createBlankWork));
        ActionSheetDialog.show(this, "创建作品", "选择创建方式", options);
    }

    /** 拍摄新作品：输出到 FileProvider 临时文件，成功后带入创建页 */
    private Uri pendingCameraUri;
    private final ActivityResultLauncher<Uri> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(), success -> {
                if (!success || pendingCameraUri == null) return;
                List<String> uris = new ArrayList<>();
                uris.add(pendingCameraUri.toString());
                pendingCameraUri = null;
                createWorkWithUris(uris);
            });

    private void launchCamera() {
        File dir = new File(getCacheDir(), "camera");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File target = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
        pendingCameraUri = FileProvider.getUriForFile(this, "com.PixelFlow.app.fileprovider", target);
        try {
            cameraLauncher.launch(pendingCameraUri);
        } catch (android.content.ActivityNotFoundException e) {
            pendingCameraUri = null;
            Toast.makeText(this, "未找到可用的相机应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void createBlankWork() {
        // 直接跳转到创建作品界面
        Intent intent = new Intent(MainActivity.this, CreateWorkActivity.class);
        startActivity(intent);

        // 可选：添加转场动画
        overridePendingTransition(R.anim.slide_in_up, R.anim.slide_out_down);
    }

    private void createWorkFromGallery() {
        // 进入创建页后立即拉起图片选择，选完直接出现在草稿区
        Intent intent = new Intent(MainActivity.this, CreateWorkActivity.class);
        intent.putExtra(CreateWorkActivity.EXTRA_AUTO_PICK, true);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_up, R.anim.slide_out_down);
    }

    private void createWorkWithUris(List<String> uris) {
        Intent intent = new Intent(MainActivity.this, CreateWorkActivity.class);
        intent.putStringArrayListExtra(CreateWorkActivity.EXTRA_INITIAL_URIS, new ArrayList<>(uris));
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_up, R.anim.slide_out_down);
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    // 实现HomeFragment的滚动监听接口
    @Override
    public void onScrollDown() {
        // 发布入口已移入底部导航，不遮挡作品，滚动时保持可用。
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

    private void playRippleEffect() {
        // 创建一个圆形的View作为波纹
        View rippleView = new View(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                200, 200);
        params.gravity = Gravity.CENTER;
        rippleView.setLayoutParams(params);
        rippleView.setBackgroundResource(R.drawable.circle_ripple);

        // 添加到根布局
        FrameLayout rootLayout = findViewById(android.R.id.content);
        if (rootLayout != null) {
            rootLayout.addView(rippleView);

            // 播放波纹动画
            Animation rippleAnim = AnimationUtils.loadAnimation(this, R.anim.ripple_circle);
            rippleAnim.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}

                @Override
                public void onAnimationEnd(Animation animation) {
                    // 动画结束后移除波纹View
                    rootLayout.removeView(rippleView);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
            rippleView.startAnimation(rippleAnim);
        }
    }

    private void playSecondaryAnimations() {
        // 1. 底部导航栏上滑进入
        bottomNavigationView.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(500)
                .setInterpolator(new DecelerateInterpolator())
                .setStartDelay(100)
                .start();

        // 2. FAB弹跳进入（更生动）
        fabCamera.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(700)
                .setInterpolator(new BounceInterpolator())
                .setStartDelay(300)
                .withStartAction(() -> {
                    // 添加一点旋转效果
                    fabCamera.setRotation(-90f);
                })
                .withEndAction(() -> {
                    // 最后来一个小小的旋转回正
                    fabCamera.animate()
                            .rotation(0f)
                            .setDuration(200)
                            .setInterpolator(new OvershootInterpolator())
                            .start();
                })
                .start();
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
