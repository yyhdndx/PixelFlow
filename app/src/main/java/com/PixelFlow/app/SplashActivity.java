package com.PixelFlow.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private FrameLayout logoContainer;  // 保持为FrameLayout
    private ImageView logoImageView;
    private TextView appNameTextView;
    private FrameLayout rootLayout;     // 改为FrameLayout

    // 动画配置
    private static final int FADE_IN_DURATION = 1200;     // 淡入时间
    private static final int POSTER_DISPLAY_TIME = 1800;  // 海报展示时间
    private static final int FADE_OUT_DURATION = 800;     // 淡出时间

    private boolean isAnimationCompleted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_splash);

        // 隐藏状态栏和导航栏
        hideSystemUI();

        initView();
        startSplashAnimation();
    }

    private void hideSystemUI() {
        // 全屏显示
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private void initView() {
        // 获取布局中的各个View - 使用正确的类型
        logoContainer = findViewById(R.id.logo_container);
        logoImageView = findViewById(R.id.iv_logo);
        appNameTextView = findViewById(R.id.tv_app_name);
        rootLayout = findViewById(R.id.root_layout);

        // 初始状态：海报稍微缩小并透明
        logoContainer.setScaleX(0.85f);
        logoContainer.setScaleY(0.85f);
        logoContainer.setAlpha(0f);

        // App名称初始隐藏
        appNameTextView.setAlpha(0f);
        appNameTextView.setTranslationY(40f);
    }

    private void startSplashAnimation() {
        // 第一阶段：海报优雅地淡入并放大
        AnimatorSet enterAnimator = new AnimatorSet();

        // 淡入动画
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(logoContainer, "alpha", 0f, 1f);
        fadeIn.setDuration(FADE_IN_DURATION);
        fadeIn.setInterpolator(new DecelerateInterpolator());

        // 平滑放大动画
        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(logoContainer, "scaleX", 0.85f, 1f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(logoContainer, "scaleY", 0.85f, 1f);
        scaleUpX.setDuration(FADE_IN_DURATION);
        scaleUpY.setDuration(FADE_IN_DURATION);
        scaleUpX.setInterpolator(new OvershootInterpolator(0.5f));
        scaleUpY.setInterpolator(new OvershootInterpolator(0.5f));

        // App名称动画（在淡入后期开始）
        ObjectAnimator appNameFadeIn = ObjectAnimator.ofFloat(appNameTextView, "alpha", 0f, 1f);
        ObjectAnimator appNameSlideUp = ObjectAnimator.ofFloat(appNameTextView, "translationY", 40f, 0f);
        appNameFadeIn.setDuration(600);
        appNameSlideUp.setDuration(600);
        appNameFadeIn.setStartDelay(FADE_IN_DURATION - 400);
        appNameSlideUp.setStartDelay(FADE_IN_DURATION - 400);

        enterAnimator.playTogether(fadeIn, scaleUpX, scaleUpY, appNameFadeIn, appNameSlideUp);

        enterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 给用户足够时间欣赏海报
                new Handler().postDelayed(() -> {
                    if (!isAnimationCompleted) {
                        startExitAnimation();
                    }
                }, POSTER_DISPLAY_TIME);
            }
        });

        enterAnimator.start();

        // 添加水波纹效果（延迟执行）
        new Handler().postDelayed(() -> {
            if (!isAnimationCompleted) {
                createRippleWave();
            }
        }, FADE_IN_DURATION / 2);
    }

    private void startExitAnimation() {
        if (isAnimationCompleted) return;

        // 第二阶段：优雅地淡出
        AnimatorSet exitAnimator = new AnimatorSet();

        // 整体淡出
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(logoContainer, "alpha", 1f, 0f);
        fadeOut.setDuration(FADE_OUT_DURATION);
        fadeOut.setInterpolator(new AccelerateInterpolator());

        // App名称也淡出
        ObjectAnimator appNameFadeOut = ObjectAnimator.ofFloat(appNameTextView, "alpha", 1f, 0f);
        appNameFadeOut.setDuration(FADE_OUT_DURATION - 200);

        exitAnimator.playTogether(fadeOut, appNameFadeOut);

        exitAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                goToMainActivity();
            }
        });

        exitAnimator.start();
    }

    private void createRippleWave() {
        // 创建一个波纹效果
        View rippleView = new View(this);
        rippleView.setBackgroundResource(R.drawable.circle_ripple);

        // 计算大小
        int size = (int) (logoContainer.getWidth() * 0.8f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = android.view.Gravity.CENTER;
        rippleView.setLayoutParams(params);

        // 添加到根布局中
        if (rootLayout != null) {
            rootLayout.addView(rippleView);

            // 初始状态
            rippleView.setScaleX(0.5f);
            rippleView.setScaleY(0.5f);
            rippleView.setAlpha(0.3f);

            // 动画效果
            rippleView.animate()
                    .scaleX(1.5f)
                    .scaleY(1.5f)
                    .alpha(0f)
                    .setDuration(1200)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (rippleView.getParent() != null) {
                            ((ViewGroup) rippleView.getParent()).removeView(rippleView);
                        }
                    })
                    .start();
        }
    }

    private void goToMainActivity() {
        if (isAnimationCompleted) return;

        isAnimationCompleted = true;

        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);

        // 平滑的过渡动画
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        // 短暂延迟后结束当前Activity
        new Handler().postDelayed(() -> finish(), 200);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    public void onBackPressed() {
        // 动画期间禁用返回键
        if (!isAnimationCompleted) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isAnimationCompleted = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isAnimationCompleted = true;
    }
}