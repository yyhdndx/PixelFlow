package com.PixelFlow.app.DetailPage;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GestureDetectorCompat;
import androidx.viewpager2.widget.ViewPager2;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import com.PixelFlow.app.DAO.DataType;
import com.PixelFlow.app.DAO.PersistState.FollowStatus;
import com.PixelFlow.app.DAO.PersistState.LikeStatus;
import com.PixelFlow.app.DAO.StorageManager;
import com.PixelFlow.app.DAO.impl.GenericStorageRepository;
import com.PixelFlow.app.R;
import com.PixelFlow.app.stream.WorkItem;
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
    private float startX, startY;
    private Rect startRect;
    private String transitionName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Detail);

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

        initViews();

        // 获取转场动画参数
        transitionName = getIntent().getStringExtra("transition_name");
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
                getWindow().getDecorView().postDelayed(this::runEnterAnimation, 50);
            });
        });
    }

    private void runEnterAnimation() {
        if (isAnimating || startRect.isEmpty()) return;

        isAnimating = true;

        // 获取结束位置（详情页图片容器的位置）
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

        // 设置初始状态
        imageContainer.setScaleX(scaleX);
        imageContainer.setScaleY(scaleY);
        imageContainer.setTranslationX(startRect.left - endRect.left);
        imageContainer.setTranslationY(startRect.top - endRect.top);

        contentLayout.setAlpha(0f);
        bottomBar.setAlpha(0f);
        backButton.setAlpha(0f);
        authorAvatar.setAlpha(0f);
        authorName.setAlpha(0f);
        followButton.setAlpha(0f);
        titleText.setAlpha(0f);
        contentText.setAlpha(0f);
        tagsContainer.setAlpha(0f);
        publishDate.setAlpha(0f);

        // 执行图片放大动画
        imageContainer.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0)
                .translationY(0)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 执行内容渐显动画
        contentLayout.animate()
                .alpha(1f)
                .setDuration(300)
                .setStartDelay(100)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 底部栏渐显
        bottomBar.animate()
                .alpha(1f)
                .setDuration(300)
                .setStartDelay(150)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 顶部栏渐显
        ValueAnimator topBarAnimator = ValueAnimator.ofFloat(0f, 1f);
        topBarAnimator.setDuration(300);
        topBarAnimator.setStartDelay(100);
        topBarAnimator.setInterpolator(new DecelerateInterpolator());
        topBarAnimator.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            backButton.setAlpha(alpha);
            authorAvatar.setAlpha(alpha);
            authorName.setAlpha(alpha);
            followButton.setAlpha(alpha);
        });
        topBarAnimator.start();

        // 内容文本渐显
        ValueAnimator contentAnimator = ValueAnimator.ofFloat(0f, 1f);
        contentAnimator.setDuration(300);
        contentAnimator.setStartDelay(200);
        contentAnimator.setInterpolator(new DecelerateInterpolator());
        contentAnimator.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            titleText.setAlpha(alpha);
            contentText.setAlpha(alpha);
            tagsContainer.setAlpha(alpha);
            publishDate.setAlpha(alpha);
        });
        contentAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isAnimating = false;
            }
        });
        contentAnimator.start();
    }

    // 退场动画
    private void runExitAnimation() {
        if (isAnimating || isExiting) return;

        isAnimating = true;
        isExiting = true;

        // 获取当前位置
        int[] location = new int[2];
        imageContainer.getLocationOnScreen(location);
        Rect currentRect = new Rect(
                location[0],
                location[1],
                location[0] + imageContainer.getWidth(),
                location[1] + imageContainer.getHeight()
        );

        // 计算缩放比例
        float scaleX = (float) startRect.width() / currentRect.width();
        float scaleY = (float) startRect.height() / currentRect.height();

        // 执行图片缩小动画
        imageContainer.animate()
                .scaleX(scaleX)
                .scaleY(scaleY)
                .translationX(startRect.left - currentRect.left)
                .translationY(startRect.top - currentRect.top)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 执行内容渐隐动画
        contentLayout.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 底部栏渐隐
        bottomBar.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 顶部栏渐隐
        ValueAnimator topBarAnimator = ValueAnimator.ofFloat(1f, 0f);
        topBarAnimator.setDuration(300);
        topBarAnimator.setInterpolator(new DecelerateInterpolator());
        topBarAnimator.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            backButton.setAlpha(alpha);
            authorAvatar.setAlpha(alpha);
            authorName.setAlpha(alpha);
            followButton.setAlpha(alpha);
        });
        topBarAnimator.start();

        // 内容文本渐隐
        ValueAnimator contentAnimator = ValueAnimator.ofFloat(1f, 0f);
        contentAnimator.setDuration(300);
        contentAnimator.setInterpolator(new DecelerateInterpolator());
        contentAnimator.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            titleText.setAlpha(alpha);
            contentText.setAlpha(alpha);
            tagsContainer.setAlpha(alpha);
            publishDate.setAlpha(alpha);
        });
        contentAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 动画结束后返回结果
                returnWithResult();
            }
        });
        contentAnimator.start();
    }


    private void runDragExitAnimation(float velocityX, float velocityY) {
        if (isAnimating || isExiting) return;

        isAnimating = true;
        isExiting = true;

        // 判断滑动方向
        boolean isHorizontalSwipe = Math.abs(velocityX) > Math.abs(velocityY);
        boolean isSwipeRight = velocityX > 0;
        boolean isSwipeDown = velocityY > 0;

        // 获取当前位置
        int[] location = new int[2];
        imageContainer.getLocationOnScreen(location);
        Rect currentRect = new Rect(
                location[0],
                location[1],
                location[0] + imageContainer.getWidth(),
                location[1] + imageContainer.getHeight()
        );

        // 计算最终位置
        float targetScale, targetX, targetY;

        if (isHorizontalSwipe && isSwipeRight) {
            // 右滑退出
            targetScale = 0.8f;
            targetX = getWindow().getDecorView().getWidth();
            targetY = currentRect.top;
        } else if (!isHorizontalSwipe && isSwipeDown) {
            // 下滑退出
            targetScale = 0.8f;
            targetX = currentRect.left;
            targetY = getWindow().getDecorView().getHeight();
        } else {
            // 回到原位
            targetScale = 1f;
            targetX = 0;
            targetY = 0;
            isExiting = false;
        }

        // 执行动画
        imageContainer.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationX(targetX)
                .translationY(targetY)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        contentLayout.animate()
                .alpha(targetScale == 0.8f ? 0f : 1f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        bottomBar.animate()
                .alpha(targetScale == 0.8f ? 0f : 1f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        if (targetScale == 0.8f) {
            // 退出动画完成后关闭页面
            imageContainer.animate().withEndAction(() -> {
                finish();
                overridePendingTransition(0, 0);
            });
        } else {
            // 回到原位动画完成后重置状态
            imageContainer.animate().withEndAction(() -> {
                isAnimating = false;
            });
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onDown(MotionEvent e) {
        startX = e.getRawX();
        startY = e.getRawY();
        return true;
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

        float deltaX = e2.getRawX() - startX;
        float deltaY = e2.getRawY() - startY;

        // 计算滑动比例
        float screenWidth = getWindow().getDecorView().getWidth();
        float screenHeight = getWindow().getDecorView().getHeight();

        float progressX = Math.abs(deltaX) / screenWidth;
        float progressY = Math.abs(deltaY) / screenHeight;

        // 判断是否为主要滑动方向
        boolean isHorizontalDrag = Math.abs(deltaX) > Math.abs(deltaY);
        float progress = isHorizontalDrag ? progressX : progressY;

        // 限制进度在0-1之间
        progress = Math.min(1f, progress);

        // 计算缩放比例（跟随滑动距离缩小）
        float scale = 1f - progress * 0.2f; // 最多缩小到80%

        // 更新图片容器位置和缩放
        imageContainer.setTranslationX(deltaX);
        imageContainer.setTranslationY(deltaY);
        imageContainer.setScaleX(scale);
        imageContainer.setScaleY(scale);

        // 更新内容透明度
        float alpha = 1f - progress;
        contentLayout.setAlpha(alpha);
        bottomBar.setAlpha(alpha);

        return true;
    }

    @Override
    public void onLongPress(@NonNull MotionEvent e) {}

    @Override
    public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
        if (isAnimating) return false;

        // 判断是否达到退出阈值
        float screenWidth = getWindow().getDecorView().getWidth();
        float screenHeight = getWindow().getDecorView().getHeight();

        float deltaX = e2.getRawX() - startX;
        float deltaY = e2.getRawY() - startY;

        float progressX = Math.abs(deltaX) / screenWidth;
        float progressY = Math.abs(deltaY) / screenHeight;

        boolean isHorizontalDrag = Math.abs(deltaX) > Math.abs(deltaY);
        float progress = isHorizontalDrag ? progressX : progressY;

        // 如果滑动超过屏幕的30%，或者速度足够大，则执行退出动画
        if (progress > 0.3f || Math.abs(velocityX) > 1000 || Math.abs(velocityY) > 1000) {
            runDragExitAnimation(velocityX, velocityY);
        } else {
            // 否则回到原位
            imageContainer.animate()
                    .translationX(0)
                    .translationY(0)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            contentLayout.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            bottomBar.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

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

        // 底部交互区（保持所有你原有的元素）
        bottomBar = findViewById(R.id.bottom_bar);
        commentInput = findViewById(R.id.comment_input);  // 保持
        likeButton = findViewById(R.id.like_button);
        commentButton = findViewById(R.id.comment_button);  // 保持
        collectButton = findViewById(R.id.collect_button);  // 保持
        shareButton = findViewById(R.id.share_button);
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
        // 获取图片列表
        List<String> imageUrls = workItem.getImageUrls();
        if (imageUrls == null || imageUrls.isEmpty()) {
            imageUrls = new ArrayList<>();
            imageUrls.add(workItem.getCoverUrl());
        }

        // 设置ViewPager2适配器
        imageAdapter = new ImagePagerAdapter(this, imageUrls);
        imagePager.setAdapter(imageAdapter);

        // 设置图片高度
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        ViewGroup.LayoutParams params = imagePager.getLayoutParams();
        params.height = workItem.getDisplayHeight(screenWidth);
        imagePager.setLayoutParams(params);

        // 设置进度指示器
        setupProgressIndicator(imageUrls.size());
    }

    private void setupProgressIndicator(int totalImages) {
        if (totalImages <= 1) {
            progressIndicator.setVisibility(View.GONE);
            return;
        }

        progressIndicator.setVisibility(View.VISIBLE);
        progressIndicator.removeAllViews();

        // 为每张图片创建一个进度点
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

        // 设置初始选中状态
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
        Intent resultIntent = new Intent();

        if (isChanged) {
            resultIntent.putExtra("work_id", workItem.getId());
            LikeStatus likeStatus = likeRepository.get(workItem.getId());

            // 添加空值检查
            if (likeStatus != null) {
                resultIntent.putExtra("is_liked", likeStatus.isLiked());
                resultIntent.putExtra("like_count", likeStatus.getLikeCount());
            } else {
                // 如果likeStatus为null，使用默认值
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
    protected void onDestroy() {
        super.onDestroy();
        if (databaseExecutor != null && !databaseExecutor.isShutdown()) {
            databaseExecutor.shutdown();
        }
    }
}