package com.PixelFlow.app.DetailPage;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GestureDetectorCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import com.PixelFlow.app.DAO.DataType;
import com.PixelFlow.app.DAO.PersistState.FollowStatus;
import com.PixelFlow.app.DAO.PersistState.LikeStatus;
import com.PixelFlow.app.DAO.StorageManager;
import com.PixelFlow.app.DAO.impl.GenericStorageRepository;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.PixelFlow.app.util.DateUtils;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.nex3z.flowlayout.FlowLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkDetailActivity extends AppCompatActivity implements GestureDetector.OnGestureListener {
    // 顶部作者区（修改：移除MaterialToolbar，添加backButton）
    private View backgroundOverlay;
    private View topBar;
    private View scrollContainer;

    private boolean isDragging = false;
    private float dragStartX, dragStartY;

    private ImageView backButton;  // 新增：返回按钮
    private ImageView authorAvatar;
    private TextView authorName;
    private MaterialButton followButton;

    // 图片区域
    private ViewPager2 imagePager;
    private LinearLayout progressIndicator;
    private ViewGroup imageContainer;

    // 内容区域
    private TextView titleText;
    private TextView contentText;
    private FlowLayout tagsContainer;
    private TextView publishDate;

    // 底部交互区（保持所有你原有的元素）
    private EditText commentInput;  // 保持
    private ImageView likeButton;
    private ImageView commentButton;  // 保持
    private ImageView collectButton;  // 保持
    private ImageView shareButton;

    // 数据
    private WorkItem workItem;
    private GenericStorageRepository<FollowStatus> followRepository;
    private GenericStorageRepository<LikeStatus> likeRepository;
    private ImagePagerAdapter imageAdapter;

    // 记录点赞状态是否发生改变，用于返回
    private boolean isChanged;

    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();

    // 动画相关
    private View contentLayout;
    private View bottomBar;
    private GestureDetectorCompat gestureDetector;
    private boolean isAnimating = false;
    private boolean isExiting = false;
    private Rect startRect;

    // 截图相关
    private ImageView screenshotBackground;
    private Bitmap waterfallScreenshot;
    private boolean hasScreenshot = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Detail);

        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);

        // 让内容延伸到状态栏下面
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // 启用窗口内容转场动画
        getWindow().setEnterTransition(null);
        getWindow().setExitTransition(null);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        // 获取传递的数据
        workItem = (WorkItem) getIntent().getSerializableExtra("work_item");
        if (workItem == null) {
            finish();
            return;
        }

        byte[] screenshotBytes = getIntent().getByteArrayExtra("waterfall_screenshot");
        assert screenshotBytes != null;
        waterfallScreenshot = BitmapFactory.decodeByteArray(screenshotBytes, 0, screenshotBytes.length);
        hasScreenshot = true;

        initViews();

        // 获取转场动画参数
        startRect = new Rect(
                getIntent().getIntExtra("start_left", 0),
                getIntent().getIntExtra("start_top", 0),
                getIntent().getIntExtra("start_left", 0) + getIntent().getIntExtra("start_width", 0),
                getIntent().getIntExtra("start_top", 0) + getIntent().getIntExtra("start_height", 0)
        );

        // 初始化手势检测
        gestureDetector = new GestureDetectorCompat(this, this);

        isChanged=false;

        // 初始化数据（在后台线程）
        databaseExecutor.execute(() -> {
            initRepositories();
            ensureDataExists();

            // 数据准备好后，更新UI
            runOnUiThread(() -> {
                setupData();
                setupListeners();
                // 延迟进行入场动画
                getWindow().getDecorView().postDelayed(this::runEnterAnimation, 20);
            });
        });
    }

    private void runEnterAnimation() {
        if (isAnimating || startRect.isEmpty()) return;

        isAnimating = true;

        // 1. 立即显示所有内容（避免闪烁）
        scrollContainer.setAlpha(1f);
        scrollContainer.setVisibility(View.VISIBLE);

        // 2. 准备初始状态（同步进行）
        prepareEnterInitialState();

        // 3. 执行无缝入场动画
        executeSeamlessEnterAnimation();
    }

    private void prepareEnterInitialState() {
        // 获取结束位置
        int[] location = new int[2];
        imageContainer.getLocationOnScreen(location);
        Rect endRect = new Rect(
                location[0],
                location[1],
                location[0] + imageContainer.getWidth(),
                location[1] + imageContainer.getHeight()
        );

        // 计算缩放比例
        float scaleX = (float) startRect.width() / endRect.width();
        float scaleY = (float) startRect.height() / endRect.height();

        // 设置图片容器初始状态
        imageContainer.setScaleX(scaleX);
        imageContainer.setScaleY(scaleY);
        imageContainer.setTranslationX(startRect.left - endRect.left);
        imageContainer.setTranslationY(startRect.top - endRect.top);
        imageContainer.setAlpha(1f);

        // 设置背景遮罩初始状态
        backgroundOverlay.setAlpha(0f);

        // 设置内容初始状态 - 轻微透明，但不是完全透明
        topBar.setAlpha(0.3f);
        topBar.setTranslationY(-10f);

        contentLayout.setAlpha(0.3f);
        contentLayout.setTranslationY(10f);

        bottomBar.setAlpha(0.3f);
        bottomBar.setTranslationY(10f);

        // 设置scrollContainer可见但透明
        scrollContainer.setAlpha(0.3f);
    }

    private void executeSeamlessEnterAnimation() {
        // 1. 同时执行图片放大和背景淡入（核心动画）
        AnimatorSet coreAnimation = new AnimatorSet();

        // 图片放大动画
        ObjectAnimator scaleXAnim = ObjectAnimator.ofFloat(imageContainer, "scaleX", 1f);
        ObjectAnimator scaleYAnim = ObjectAnimator.ofFloat(imageContainer, "scaleY", 1f);
        ObjectAnimator transXAnim = ObjectAnimator.ofFloat(imageContainer, "translationX", 0f);
        ObjectAnimator transYAnim = ObjectAnimator.ofFloat(imageContainer, "translationY", 0f);

        // 背景淡入
        ObjectAnimator bgAlphaAnim = ObjectAnimator.ofFloat(backgroundOverlay, "alpha", 0.7f);

        // 同时执行
        coreAnimation.playTogether(scaleXAnim, scaleYAnim, transXAnim, transYAnim, bgAlphaAnim);
        coreAnimation.setDuration(250);
        coreAnimation.setInterpolator(new DecelerateInterpolator());

        // 2. 内容区域淡入动画（与核心动画同时开始，但稍微延迟）
        AnimatorSet contentAnimation = new AnimatorSet();

        // 顶部栏动画
        ObjectAnimator topBarAlpha = ObjectAnimator.ofFloat(topBar, "alpha", 1f);
        ObjectAnimator topBarTrans = ObjectAnimator.ofFloat(topBar, "translationY", 0f);

        // 内容区域动画
        ObjectAnimator contentAlpha = ObjectAnimator.ofFloat(contentLayout, "alpha", 1f);
        ObjectAnimator contentTrans = ObjectAnimator.ofFloat(contentLayout, "translationY", 0f);

        // 底部栏动画
        ObjectAnimator bottomBarAlpha = ObjectAnimator.ofFloat(bottomBar, "alpha", 1f);
        ObjectAnimator bottomBarTrans = ObjectAnimator.ofFloat(bottomBar, "translationY", 0f);

        // scrollContainer整体淡入
        ObjectAnimator scrollAlpha = ObjectAnimator.ofFloat(scrollContainer, "alpha", 1f);

        contentAnimation.playTogether(topBarAlpha, topBarTrans, contentAlpha, contentTrans,
                bottomBarAlpha, bottomBarTrans, scrollAlpha);
        contentAnimation.setDuration(200);
        contentAnimation.setStartDelay(40);
        contentAnimation.setInterpolator(new DecelerateInterpolator());

        // 3. 组合动画
        AnimatorSet finalAnimation = new AnimatorSet();
        finalAnimation.playTogether(coreAnimation, contentAnimation);
        finalAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isAnimating = false;
            }
        });

        finalAnimation.start();
    }

    // 退场动画
    private void runExitAnimation() {
        if (isAnimating || isExiting) return;

        isAnimating = true;
        isExiting = true;

        // 执行固定大小淡出动画
        executeFixedSizeFadeOutAnimation();
    }

    private void executeFixedSizeFadeOutAnimation() {
        // 获取当前位置
        int[] location = new int[2];
        imageContainer.getLocationOnScreen(location);
        Rect currentRect = new Rect(
                location[0],
                location[1],
                location[0] + imageContainer.getWidth(),
                location[1] + imageContainer.getHeight()
        );

        // 计算移动到瀑布流卡片位置的偏移量
        float targetTranslateX = startRect.left - currentRect.left;
        float targetTranslateY = startRect.top - currentRect.top;

        // 1. 准备截图背景
        prepareScreenshotBackground();

        // 2. 创建所有动画
        AnimatorSet backgroundReveal = createScreenshotRevealAnimation();
        AnimatorSet contentFadeOut = createContentFadeOutAnimation();
        AnimatorSet imageAnimation = createImageReturnAnimation(targetTranslateX, targetTranslateY);

        // 3. 组合动画：截图背景淡入 + 内容淡出 + 图片移动
        AnimatorSet finalAnimation = new AnimatorSet();
        if (hasScreenshot) {
            // 有截图时，让截图背景逐渐显示
            finalAnimation.playTogether(backgroundReveal, contentFadeOut, imageAnimation);
        } else {
            // 没有截图时，使用纯色背景淡入
            AnimatorSet fallbackBackground = createFallbackBackgroundAnimation();
            finalAnimation.playTogether(fallbackBackground, contentFadeOut, imageAnimation);
        }

        finalAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                returnWithResult();
            }

            @Override
            public void onAnimationStart(Animator animation) {
                // 确保图片在最上层
                imageContainer.bringToFront();
                // 重置图片的缩放
                imageContainer.setScaleX(1f);
                imageContainer.setScaleY(1f);
            }
        });

        finalAnimation.start();
    }

    private void prepareScreenshotBackground() {
        if (hasScreenshot) {
            screenshotBackground.setVisibility(View.VISIBLE);
            screenshotBackground.setAlpha(0f);
            screenshotBackground.setScaleX(1.02f); // 轻微放大，增加动态感
            screenshotBackground.setScaleY(1.02f);
        }
    }

    private AnimatorSet createScreenshotRevealAnimation() {
        AnimatorSet revealSet = new AnimatorSet();

        // 截图背景淡入（透明度从0到1）
        ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(screenshotBackground, "alpha", 0f, 1f);

        // 截图背景轻微缩小回正常大小（从1.02到1.0）
        ObjectAnimator scaleXAnim = ObjectAnimator.ofFloat(screenshotBackground, "scaleX", 1.02f, 1f);
        ObjectAnimator scaleYAnim = ObjectAnimator.ofFloat(screenshotBackground, "scaleY", 1.02f, 1f);

        // 稍微向上移动一点点，模拟"浮现"效果
        ObjectAnimator translateYAnim = ObjectAnimator.ofFloat(screenshotBackground, "translationY", 20f, 0f);

        revealSet.playTogether(alphaAnim, scaleXAnim, scaleYAnim, translateYAnim);
        revealSet.setDuration(400);
        revealSet.setInterpolator(new DecelerateInterpolator());

        return revealSet;
    }

    private AnimatorSet createFallbackBackgroundAnimation() {
        // 没有截图时的备选方案：简单的白色背景淡入
        AnimatorSet fallbackSet = new AnimatorSet();

        View fallbackBackground = new View(this);
        fallbackBackground.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        fallbackBackground.setBackgroundColor(Color.WHITE);
        fallbackBackground.setAlpha(0f);

        // 添加到布局中
        FrameLayout rootLayout = findViewById(R.id.root_layout);
        if (rootLayout != null) {
            rootLayout.addView(fallbackBackground, 0);
        }

        ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(fallbackBackground, "alpha", 0f, 1f);
        fallbackSet.play(alphaAnim);
        fallbackSet.setDuration(300);

        return fallbackSet;
    }

    private AnimatorSet createContentFadeOutAnimation() {
        AnimatorSet fadeOutSet = new AnimatorSet();

        // 背景遮罩淡出（从0.7到0）
        ObjectAnimator bgFade = ObjectAnimator.ofFloat(backgroundOverlay, "alpha", 0.7f, 0f);

        // 顶部栏、内容区域、底部栏同时淡出
        ObjectAnimator topBarFade = ObjectAnimator.ofFloat(topBar, "alpha", 1f, 0f);
        ObjectAnimator contentFade = ObjectAnimator.ofFloat(contentLayout, "alpha", 1f, 0f);
        ObjectAnimator bottomBarFade = ObjectAnimator.ofFloat(bottomBar, "alpha", 1f, 0f);

        // scrollContainer整体淡出
        ObjectAnimator scrollFade = ObjectAnimator.ofFloat(scrollContainer, "alpha", 1f, 0f);

        fadeOutSet.playTogether(bgFade, topBarFade, contentFade, bottomBarFade, scrollFade);
        fadeOutSet.setDuration(350);
        fadeOutSet.setInterpolator(new AccelerateInterpolator());

        return fadeOutSet;
    }

    private AnimatorSet createImageReturnAnimation(float targetTranslateX, float targetTranslateY) {
        AnimatorSet imageSet = new AnimatorSet();

        // 图片淡出（透明度从1降到0.2，不完全消失）
        ObjectAnimator imageAlphaAnim = ObjectAnimator.ofFloat(imageContainer, "alpha", 1f, 0.2f);

        // 图片移动到瀑布流位置
        ObjectAnimator imageTransXAnim = ObjectAnimator.ofFloat(imageContainer, "translationX", targetTranslateX);
        ObjectAnimator imageTransYAnim = ObjectAnimator.ofFloat(imageContainer, "translationY", targetTranslateY);

        // 图片缩放回瀑布流卡片大小
        float startScaleX = imageContainer.getScaleX();
        float startScaleY = imageContainer.getScaleY();
        float targetScaleX = (float) startRect.width() / imageContainer.getWidth();
        float targetScaleY = (float) startRect.height() / imageContainer.getHeight();

        ObjectAnimator scaleXAnim = ObjectAnimator.ofFloat(imageContainer, "scaleX", startScaleX, targetScaleX);
        ObjectAnimator scaleYAnim = ObjectAnimator.ofFloat(imageContainer, "scaleY", startScaleY, targetScaleY);

        // 添加轻微旋转
        float rotationAngle = calculateRotationAngle(targetTranslateX, targetTranslateY);
        ObjectAnimator imageRotateAnim = ObjectAnimator.ofFloat(imageContainer, "rotation", rotationAngle);

        imageSet.playTogether(imageAlphaAnim, imageTransXAnim, imageTransYAnim,
                scaleXAnim, scaleYAnim, imageRotateAnim);
        imageSet.setDuration(400);
        imageSet.setInterpolator(new DecelerateInterpolator());

        return imageSet;
    }

    // 辅助方法：计算轻微的旋转角度（基于移动方向）
    private float calculateRotationAngle(float deltaX, float deltaY) {
        // 根据移动方向计算轻微旋转角度
        float angle = 0f;

        // 如果主要是水平移动
        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            angle = deltaX > 0 ? -1.5f : 1.5f; // 非常轻微的水平旋转
        }
        // 如果主要是垂直移动
        else {
            angle = deltaY > 0 ? -1f : 1f; // 更轻微的垂直旋转
        }

        return angle;
    }

    private void runDragExitAnimation(float velocityX, float velocityY, float currentX, float currentY) {
        if (isAnimating || isExiting) return;

        isAnimating = true;
        isExiting = true;

        // 简化判断：只要开始拖拽就执行快速退出
        executeDragExitAnimation(velocityX, velocityY, currentX, currentY);
    }

    private void executeDragExitAnimation(float velocityX, float velocityY, float currentX, float currentY) {
        // 计算最终位置（带预测）
        float targetX, targetY;
        float screenWidth = getWindow().getDecorView().getWidth();
        float screenHeight = getWindow().getDecorView().getHeight();

        boolean isHorizontalExit = Math.abs(velocityX)>Math.abs(velocityY);

        if (isHorizontalExit) {
            // 水平退出
            targetX = currentX > 0 ? screenWidth * 1.2f : -screenWidth * 1.2f;
            targetY = currentY + (velocityY * 0.1f);
        } else {
            // 垂直退出
            targetX = currentX + (velocityX * 0.1f);
            targetY = currentY > 0 ? screenHeight * 1.2f : -screenHeight * 1.2f;
        }

        // 计算动画时长
        float distance = (float) Math.sqrt(Math.pow(targetX - currentX, 2) + Math.pow(targetY - currentY, 2));
        float speed = (float) Math.sqrt(Math.pow(velocityX, 2) + Math.pow(velocityY, 2));
        long duration = Math.min(400L, Math.max(200L, (long) (distance / (speed > 0 ? speed * 0.005f : 3))));

        // 准备截图背景
        if (hasScreenshot) {
            screenshotBackground.setVisibility(View.VISIBLE);
            screenshotBackground.setAlpha(0f);
        }

        // 创建瀑布流背景淡入动画
        AnimatorSet backgroundReveal = new AnimatorSet();
        if (hasScreenshot) {
            ObjectAnimator screenshotAlpha = ObjectAnimator.ofFloat(screenshotBackground, "alpha", 0f, 0.7f); // 只淡入到0.7
            screenshotAlpha.setDuration(200);
            backgroundReveal.play(screenshotAlpha);
        }
        backgroundReveal.setDuration(300); // 加快背景显示

        // 1. 背景遮罩淡出
        ValueAnimator bgAnimator = ValueAnimator.ofFloat(backgroundOverlay.getAlpha(), 0f);
        bgAnimator.addUpdateListener(anim -> backgroundOverlay.setAlpha((float) anim.getAnimatedValue()));

        // 2. 图片移动和淡出（保持大小不变）
        AnimatorSet imageSet = new AnimatorSet();

        // 移动（保持当前位置作为起始）
        ValueAnimator moveX = ValueAnimator.ofFloat(currentX, targetX);
        moveX.addUpdateListener(anim -> imageContainer.setTranslationX((float) anim.getAnimatedValue()));

        ValueAnimator moveY = ValueAnimator.ofFloat(currentY, targetY);
        moveY.addUpdateListener(anim -> imageContainer.setTranslationY((float) anim.getAnimatedValue()));

        // 淡出（透明度从当前值到0）
        ValueAnimator alphaAnim = ValueAnimator.ofFloat(imageContainer.getAlpha(), 0f);
        alphaAnim.addUpdateListener(anim -> {
            float alpha = (float) anim.getAnimatedValue();
            imageContainer.setAlpha(alpha);
            // 内容也一起淡出
            contentLayout.setAlpha(alpha);
            topBar.setAlpha(alpha);
            bottomBar.setAlpha(alpha);
        });

        // 轻微旋转
        float rotationAngle = isHorizontalExit ?
                (currentX > 0 ? -2f : 2f) : (currentY > 0 ? -1f : 1f);
        ValueAnimator rotateAnim = ValueAnimator.ofFloat(0f, rotationAngle);
        rotateAnim.addUpdateListener(anim -> imageContainer.setRotation((float) anim.getAnimatedValue()));

        imageSet.playTogether(moveX, moveY, alphaAnim, rotateAnim);
        imageSet.setDuration(duration);
        imageSet.setInterpolator(new DecelerateInterpolator());

        // 组合动画
        AnimatorSet finalAnimation = new AnimatorSet();
        if (hasScreenshot) {
            finalAnimation.playTogether(backgroundReveal, imageSet);
        } else {
            finalAnimation.play(imageSet);
        }

        finalAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                returnWithResult();
            }
        });

        finalAnimation.start();
    }

    private void executeReturnAnimation() {
        // 快速回弹动画（固定大小版本）
        AnimatorSet returnAnim = new AnimatorSet();

        // 图片回到原位（位置和旋转）
        ObjectAnimator returnX = ObjectAnimator.ofFloat(imageContainer, "translationX", 0f);
        ObjectAnimator returnY = ObjectAnimator.ofFloat(imageContainer, "translationY", 0f);
        ObjectAnimator returnRotate = ObjectAnimator.ofFloat(imageContainer, "rotation", 0f);

        // 图片透明度恢复到1
        ObjectAnimator imageAlphaReturn = ObjectAnimator.ofFloat(imageContainer, "alpha", 1f);

        // 背景和内容恢复
        ObjectAnimator bgReturn = ObjectAnimator.ofFloat(backgroundOverlay, "alpha", 0.7f);
        ObjectAnimator contentReturn = ObjectAnimator.ofFloat(contentLayout, "alpha", 1f);
        ObjectAnimator topBarReturn = ObjectAnimator.ofFloat(topBar, "alpha", 1f);
        ObjectAnimator bottomBarReturn = ObjectAnimator.ofFloat(bottomBar, "alpha", 1f);

        returnAnim.playTogether(returnX, returnY, returnRotate, imageAlphaReturn,
                bgReturn, contentReturn, topBarReturn, bottomBarReturn);

        returnAnim.setDuration(150);
        returnAnim.setInterpolator(new OvershootInterpolator(0.5f));

        returnAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isAnimating = false;
                isExiting = false;
                isDragging = false;
            }
        });

        returnAnim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);

        // 处理手指抬起事件
        if (event.getAction() == MotionEvent.ACTION_UP && isDragging) {
            isDragging = false;
            float currentX = imageContainer.getTranslationX();
            float currentY = imageContainer.getTranslationY();

            // 如果没有达到退出阈值，则回弹
            if (Math.abs(currentX) < getWindow().getDecorView().getWidth() * 0.3f &&
                    Math.abs(currentY) < getWindow().getDecorView().getHeight() * 0.3f) {
                executeReturnAnimation();
            } else {
                // 如果达到阈值，执行退出动画
                float velocityX = 0;
                float velocityY = 0;
                runDragExitAnimation(velocityX, velocityY, currentX, currentY);
            }
        }

        return super.onTouchEvent(event);
    }

    @Override
    public void onShowPress(MotionEvent e) {}

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
        if (isAnimating) return false;

        isDragging = true;

        float deltaX = e2.getRawX() - dragStartX;
        float deltaY = e2.getRawY() - dragStartY;

        // 直接应用拖拽位移
        imageContainer.setTranslationY(deltaY);
        imageContainer.setTranslationX(deltaX);

        // 图片大小保持不变（scale=1）
        // 不需要缩放

        // 根据拖拽距离实时调整透明度
        float dragDistance = (float) Math.sqrt(Math.pow(deltaX, 2) + Math.pow(deltaY, 2));
        float maxDistance = getWindow().getDecorView().getHeight() * 0.3f;

        // 图片透明度（越远越透明）
        float imageAlpha = 1f - (dragDistance / maxDistance);
        imageContainer.setAlpha(Math.max(0.3f, imageAlpha)); // 最小0.3，不完全消失

        // 背景和内容透明度（更快消失）
        float contentAlpha = 1f - (dragDistance / maxDistance) * 1.5f;
        contentLayout.setAlpha(Math.max(0f, contentAlpha));
        topBar.setAlpha(Math.max(0f, contentAlpha));
        bottomBar.setAlpha(Math.max(0f, contentAlpha));
        backgroundOverlay.setAlpha(Math.max(0f, 0.7f * contentAlpha));

        // 根据拖拽方向添加非常轻微的旋转
        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            imageContainer.setRotation(deltaX * 0.01f); // 非常轻微的旋转
        } else {
            imageContainer.setRotation(deltaY * 0.005f);
        }

        return true;
    }

    @Override
    public void onLongPress(@NonNull MotionEvent e) {}

    @Override
    public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
        if (isAnimating) return false;

        float currentX = imageContainer.getTranslationX();
        float currentY = imageContainer.getTranslationY();

        runDragExitAnimation(velocityX, velocityY, currentX, currentY);
        return true;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        dragStartX = e.getRawX();
        dragStartY = e.getRawY();
        isDragging = false;
        return true;
    }

    private void ensureDataExists() {
        // 确保关注状态存在
        String authorName = workItem.getAuthorName();
        FollowStatus followStatus = followRepository.get(authorName);
        if (followStatus == null) {
            followStatus = new FollowStatus(authorName, false,System.currentTimeMillis());
            followRepository.save(authorName, followStatus);
        }

        // 确保点赞状态存在
        String workId = workItem.getId();
        LikeStatus likeStatus = likeRepository.get(workId);
        if (likeStatus == null) {
            likeStatus = new LikeStatus(workId, false, workItem.getLikeCount());
            likeRepository.save(workId, likeStatus);
        }
    }

    private void initViews() {
        // 顶部作者区（修改：初始化backButton）
        backgroundOverlay=findViewById(R.id.background_overlay);
        topBar=findViewById(R.id.top_bar);
        scrollContainer = findViewById(R.id.scroll_container);
        backButton = findViewById(R.id.back_button);  // 新增
        authorAvatar = findViewById(R.id.author_avatar);
        authorName = findViewById(R.id.author_name);
        followButton = findViewById(R.id.follow_button);

        // 图片区域
        imagePager = findViewById(R.id.image_pager);
        progressIndicator = findViewById(R.id.progress_indicator);
        imageContainer = findViewById(R.id.image_container);

        // 内容区域
        titleText = findViewById(R.id.title_text);
        contentText = findViewById(R.id.content_text);
        tagsContainer = findViewById(R.id.tags_container);
        publishDate = findViewById(R.id.publish_date);

        // 内容布局（用于动画）
        contentLayout = findViewById(R.id.content_layout);
        // 然后backgroundOverlay在它们上面
        backgroundOverlay.bringToFront();
        // 最后是内容
        scrollContainer.bringToFront();

        // 底部交互区
        bottomBar = findViewById(R.id.bottom_bar);
        commentInput = findViewById(R.id.comment_input);  // 保持
        likeButton = findViewById(R.id.like_button);
        commentButton = findViewById(R.id.comment_button);  // 保持
        collectButton = findViewById(R.id.collect_button);  // 保持
        shareButton = findViewById(R.id.share_button);

        // 初始化截图背景（添加到布局的最底层）
        screenshotBackground = new ImageView(this);
        screenshotBackground.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        screenshotBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
        screenshotBackground.setAlpha(0f);
        screenshotBackground.setVisibility(View.INVISIBLE);

        // 将截图背景添加到布局中
        screenshotBackground = findViewById(R.id.screenshot_background);

        // 如果有截图，设置到ImageView
        if (hasScreenshot && waterfallScreenshot != null) {
            screenshotBackground.setImageBitmap(waterfallScreenshot);
        }

    }

    private void initRepositories() {
        followRepository = StorageManager.getInstance(this)
                .getRepository(DataType.FOLLOW_STATUS,FollowStatus.class);
        likeRepository = StorageManager.getInstance(this)
                .getRepository(DataType.LIKE_STATUS,LikeStatus.class);
    }

    private void setupData() {
        backButton.setOnClickListener(v -> {
            if (!isAnimating) {
                runExitAnimation();
            }
        });

        // 设置作者信息
        setupAuthorInfo();

        // 设置图片
        setupImages();

        // 设置标题和内容
        setupContent();

        // 设置日期
        publishDate.setText(DateUtils.formatRelativeTime(workItem.getCreateTime()));

        // 设置点赞状态
        updateLikeButton();
    }

    private void setupAuthorInfo() {
        // 作者头像
        Glide.with(this)
                .load(workItem.getAuthorAvatarUrl())
                .placeholder(R.drawable.ic_avatar_placeholder)
                .circleCrop()
                .into(authorAvatar);

        // 作者昵称
        authorName.setText(workItem.getAuthorName());

        // 关注状态
        updateFollowButton();
    }

    private void setupImages() {
        List<WorkItem.Clip> mediaItems = workItem.getClips();
        if (mediaItems == null || mediaItems.isEmpty()) {
            // 如果没有clips，创建一个默认的图片clip
            WorkItem.Clip defaultClip = new WorkItem.Clip(0, 400, 600, workItem.getCoverUrl());
            mediaItems = new ArrayList<>();
            mediaItems.add(defaultClip);
        }

        // 设置图片/视频高度
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        ViewGroup.LayoutParams params = imagePager.getLayoutParams();
        params.height = workItem.getDisplayHeight(screenWidth);
        imagePager.setLayoutParams(params);

        // 设置适配器
        imageAdapter = new ImagePagerAdapter(this, mediaItems);
        imagePager.setAdapter(imageAdapter);

        // 设置页面切换监听，管理视频播放状态
        imagePager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (imageAdapter != null) {
                    imageAdapter.onPageChanged(position);
                }
            }
        });

        // 设置进度指示器
        setupProgressIndicator(mediaItems.size());

        // 设置预加载
        imagePager.setOffscreenPageLimit(2);

        // 添加点击监听器
        setupImageClickListener();
    }

    // setupProgressIndicator 保持原样
    private void setupProgressIndicator(int totalImages) {
        if (totalImages <= 1) {
            progressIndicator.setVisibility(View.GONE);
            return;
        }

        progressIndicator.setVisibility(View.VISIBLE);
        progressIndicator.removeAllViews();

        for (int i = 0; i < totalImages; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(8, 8);
            params.setMargins(4, 0, 4, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.progress_dot);
            progressIndicator.addView(dot);
        }

        // 监听页面切换
        imagePager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateProgressDots(position);
            }
        });

        updateProgressDots(0);
    }

    private void updateProgressDots(int currentPosition) {
        for (int i = 0; i < progressIndicator.getChildCount(); i++) {
            View dot = progressIndicator.getChildAt(i);
            if (i == currentPosition) {
                dot.setBackgroundResource(R.drawable.progress_dot_active);
            } else {
                dot.setBackgroundResource(R.drawable.progress_dot);
            }
        }
    }

    private void setupContent() {
        // 标题
        titleText.setText(workItem.getTitle());

        // 正文
        String content = workItem.getContent();
        if (content != null && !content.isEmpty()) {
            contentText.setText(content);
            extractAndDisplayTags(content);
        } else {
            contentText.setVisibility(View.GONE);
        }
    }

    private void extractAndDisplayTags(String content) {
        // 提取话题标签（格式：#话题#）
        Pattern pattern = Pattern.compile("#([^#]+)#");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String tag = matcher.group(1);
            addTagView(tag);
        }
    }

    private void setupImageClickListener() {
        // 为ViewPager2中的每个图片添加点击监听器
        imagePager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // 每次页面切换时，为当前页面的图片添加点击监听器
                setupCurrentImageClickListener(position);
            }
        });

        // 初始化第一页的点击监听器
        setupCurrentImageClickListener(0);
    }

    private void setupCurrentImageClickListener(int position) {
        // 延迟一小段时间，确保ViewPager已经创建了ViewHolder
        imagePager.postDelayed(() -> {
            RecyclerView recyclerView = (RecyclerView) imagePager.getChildAt(0);
            if (recyclerView != null) {
                RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
                if (holder instanceof ImagePagerAdapter.ImageViewHolder) {
                    ImageView imageView = ((ImagePagerAdapter.ImageViewHolder) holder).imageView;
                    if (imageView != null) {
                        imageView.setOnClickListener(v -> {
                            openFullScreenImage(position);
                        });
                    }
                }
            }
        }, 100);
    }

    private void openFullScreenImage(int position) {
        List<String> imageUrls = workItem.getImageUrls();
        if (imageUrls == null || imageUrls.isEmpty()) {
            imageUrls = new ArrayList<>();
            imageUrls.add(workItem.getCoverUrl());
        }

        // 创建并显示全屏图片对话框
        FullScreenMediaDialog dialog = new FullScreenMediaDialog(
                WorkDetailActivity.this,
                imageUrls,
                position
        );

        // 设置对话框显示和消失的动画
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(R.style.FullScreenDialogAnimation);
        }

        dialog.show();
    }

    private void addTagView(String tag) {
        TextView tagView = new TextView(this);
        tagView.setText("#" + tag);
        tagView.setTextColor(getResources().getColor(R.color.primary_color));
        tagView.setBackgroundResource(R.drawable.tag_background);
        tagView.setPadding(12, 6, 12, 6);
        tagView.setTextSize(12);

        tagView.setOnClickListener(v -> {
            // 点击话题跳转
            // Intent intent = new Intent(this, TagDetailActivity.class);
            // intent.putExtra("tag", tag);
            // startActivity(intent);
        });

        tagsContainer.addView(tagView);
    }

    private void setupListeners() {
        // 关注按钮
        followButton.setOnClickListener(v -> toggleFollowStatus());

        // 点赞按钮
        likeButton.setOnClickListener(v -> toggleLikeStatus());

        // 分享按钮
        shareButton.setOnClickListener(v -> shareWork());

        // 这里可以添加其他按钮的监听器，如评论按钮、收藏按钮等
        // commentButton.setOnClickListener(v -> { ... });
        // collectButton.setOnClickListener(v -> { ... });
        // commentInput.setOnEditorActionListener(...);
    }

    // 其他方法也要确保在后台线程调用数据库操作
    private void updateFollowButton() {
        databaseExecutor.execute(() -> {
            String authorName = workItem.getAuthorName();
            FollowStatus followStatus = followRepository.get(authorName);

            // 理论上这里不会为null，因为ensureDataExists已经创建了
            if (followStatus == null) {
                followStatus = new FollowStatus(authorName, false,System.currentTimeMillis());
                followRepository.save(authorName, followStatus);
            }

            final boolean isFollowing = followStatus.isFollowing();

            runOnUiThread(() -> {
                if (isFollowing) {
                    followButton.setText("已关注");
                    followButton.setBackgroundColor(getResources().getColor(R.color.gray_200));
                    followButton.setTextColor(getResources().getColor(R.color.gray_600));
                    // 移除边框
                    followButton.setStrokeWidth(0);
                } else {
                    followButton.setText("关注");
                    followButton.setBackgroundColor(getResources().getColor(android.R.color.transparent));
                    followButton.setTextColor(getResources().getColor(R.color.primary_color));
                    // 添加边框
                    followButton.setStrokeWidth(2);
                }
            });
        });
    }

    private void toggleFollowStatus() {
        String authorName = workItem.getAuthorName();
        FollowStatus followStatus = followRepository.get(authorName);

        // 添加空值检查
        if (followStatus == null) {
            followStatus = new FollowStatus(authorName, false, System.currentTimeMillis());
        }

        followStatus.setFollowing(!followStatus.isFollowing());
        followRepository.save(authorName, followStatus);
        updateFollowButton();
    }

    private void updateLikeButton() {
        LikeStatus likeStatus = likeRepository.get(workItem.getId());

        // 添加空值检查
        if (likeStatus == null) {
            likeStatus = new LikeStatus(workItem.getId(), false, workItem.getLikeCount());
        }

        boolean isLiked = likeStatus.isLiked();

        if (isLiked) {
            likeButton.setImageResource(R.drawable.ic_thumb_up);
            likeButton.setColorFilter(getResources().getColor(android.R.color.holo_red_dark));
        } else {
            likeButton.setImageResource(R.drawable.ic_thumb_up_outline);
            likeButton.setColorFilter(getResources().getColor(R.color.gray_600));
        }
    }

    private void toggleLikeStatus() {
        String workId = workItem.getId();
        LikeStatus likeStatus = likeRepository.get(workId);

        // 添加空值检查
        if (likeStatus == null) {
            likeStatus = new LikeStatus(workId, false, workItem.getLikeCount());
        }

        boolean currentLiked = likeStatus.isLiked();

        // 切换点赞状态
        likeStatus.setLiked(!currentLiked);

        // 更新点赞数量
        int currentLikeCount = likeStatus.getLikeCount();
        if (!currentLiked) {
            // 从未点赞变为点赞
            likeStatus.setLikeCount(currentLikeCount + 1);
        } else {
            // 从点赞变为未点赞
            likeStatus.setLikeCount(Math.max(0, currentLikeCount - 1));
        }

        // 保存到数据库
        likeRepository.save(workId, likeStatus);

        // 更新UI
        updateLikeButton();

        // 标记状态已改变
        isChanged = true;

        // 立即更新本地数据
        workItem.setLiked(likeStatus.isLiked());
        workItem.setLikeCount(likeStatus.getLikeCount());
    }

    private void shareWork() {
        // 分享功能
        // Intent shareIntent = new Intent(Intent.ACTION_SEND);
        // shareIntent.setType("text/plain");
        // shareIntent.putExtra(Intent.EXTRA_TEXT, "分享作品: " + workItem.getTitle());
        // startActivity(Intent.createChooser(shareIntent, "分享到"));
    }

    // 修改原有的 returnWithResult 方法，移除 finish() 调用（因为已经在 runExitAnimation 中调用了）
    private void returnWithResult() {
        // 立即返回，不等待任何延迟
        Intent resultIntent = new Intent();

        if (isChanged) {
            resultIntent.putExtra("work_id", workItem.getId());
            LikeStatus likeStatus = likeRepository.get(workItem.getId());

            if (likeStatus != null) {
                resultIntent.putExtra("is_liked", likeStatus.isLiked());
                resultIntent.putExtra("like_count", likeStatus.getLikeCount());
            } else {
                resultIntent.putExtra("is_liked", false);
                resultIntent.putExtra("like_count", workItem.getLikeCount());
            }

            setResult(1, resultIntent);
        } else {
            setResult(0);
        }

        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (!isAnimating && !isExiting) {
            runExitAnimation();
        }
        // 如果正在动画中，忽略返回键
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (imageAdapter != null) {
            imageAdapter.releasePlayer();
        }

        if (databaseExecutor != null && !databaseExecutor.isShutdown()) {
            databaseExecutor.shutdown();
        }
        // 释放截图内存
        if (waterfallScreenshot != null && !waterfallScreenshot.isRecycled()) {
            waterfallScreenshot.recycle();
            waterfallScreenshot = null;
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        // 在内存紧张时释放截图
        if (level >= TRIM_MEMORY_BACKGROUND) {
            if (waterfallScreenshot != null && !waterfallScreenshot.isRecycled()) {
                waterfallScreenshot.recycle();
                waterfallScreenshot = null;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (imageAdapter != null) {
            imageAdapter.pauseCurrentVideo();
        }
    }
}