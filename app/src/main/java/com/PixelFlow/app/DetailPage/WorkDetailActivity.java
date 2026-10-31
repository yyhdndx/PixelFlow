package com.PixelFlow.app.DetailPage;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GestureDetectorCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import android.view.ViewGroup;

import com.PixelFlow.app.AudioPlayer;
import com.PixelFlow.app.DAO.PersistState.LikeStatus;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.API.UserProfile;
import com.PixelFlow.app.Hashtag.HashtagActivity;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.UserProfileActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.PixelFlow.app.util.DateUtils;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.nex3z.flowlayout.FlowLayout;

import java.util.ArrayList;
import java.util.List;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkDetailActivity extends AppCompatActivity implements GestureDetector.OnGestureListener {
    // 顶部作者区（修改：移除MaterialToolbar，添加backButton）
    private View backgroundOverlay;
    private View topBar;
    private View scrollContainer;

    private boolean isDragging = false;
    private boolean dragEligible;
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
    private ImagePagerAdapter imageAdapter;
    private Bundle restoredPlayback;

    // 记录点赞状态是否发生改变，用于返回
    private boolean isChanged;

    private LikeStatus loadedLike;
    private boolean likeSaving;
    private CommunityClient.Job likeJob;
    private CommunityClient.Job followJob;
    private boolean following;
    private boolean followLoaded;
    private boolean followSaving;
    // Only reads are cancellable by the view. Writes stay in ApiClient's serial queue.
    private CommunityClient.Job favoriteReadJob;
    private int favoriteVersion;
    private long favoriteSession;
    private boolean favoriteActive;
    private boolean favorited;
    private boolean favoriteLoaded;
    private boolean favoriteSaving;
    private long viewingStarted;
    private long viewingSession;
    private String viewingUser;
    private boolean clickTracked;
    private FullScreenMediaDialog fullscreenImageDialog;

    // 拖拽退出相关
    private View contentLayout;
    private View bottomBar;
    private GestureDetectorCompat gestureDetector;

    // 音乐播放相关
    private AudioPlayer audioPlayerManager;
    private ImageView musicControlButton;
    private WorkItem.Music currentMusic;
    private boolean musicUserPaused = false;

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
        restoredPlayback = savedInstanceState == null ? null : savedInstanceState.getBundle("media.playback");
        setContentView(R.layout.activity_detail);

        // 获取传递的数据
        workItem = (WorkItem) getIntent().getSerializableExtra("work_item");
        if (workItem == null) {
            finish();
            return;
        }

        initViews();

        // 初始化手势检测
        gestureDetector = new GestureDetectorCompat(this, this);

        isChanged=false;

        setupData();
        setupListeners();
        loadInteractionState();
    }

    // 拖拽未达到退出阈值时回弹复位
    private void resetDragState() {
        imageContainer.animate().translationY(0f).alpha(1f).rotation(0f).setDuration(150).start();
        contentLayout.animate().alpha(1f).setDuration(150).start();
        topBar.animate().alpha(1f).setDuration(150).start();
        bottomBar.animate().alpha(1f).setDuration(150).start();
        backgroundOverlay.animate().alpha(0.7f).setDuration(150).start();
    }

    // 淡出并退出详情页（配合 overridePendingTransition 的淡入淡出）
    private void finishWithFade() {
        returnWithResult();
    }
    private void animateMusicButtonClick() {
        // 缩放动画
        musicControlButton.animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(100)
                .withEndAction(() -> {
                    musicControlButton.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(100)
                            .start();
                })
                .start();

        // 透明度动画
        musicControlButton.animate()
                .alpha(0.5f)
                .setDuration(50)
                .withEndAction(() -> {
                    musicControlButton.animate()
                            .alpha(0.7f)
                            .setDuration(150)
                            .start();
                })
                .start();
    }

    private void updateMusicButtonState() {
        if (musicControlButton == null || audioPlayerManager == null || currentMusic == null) {
            return;
        }

        boolean isPlaying = audioPlayerManager.isPlaying();
        WorkItem.Music playingMusic = audioPlayerManager.getCurrentMusic();

        // 判断是否正在播放当前音乐
        boolean isPlayingCurrent = isPlaying && playingMusic != null;

        if (isPlayingCurrent) {
            // 正在播放
            musicControlButton.setImageResource(R.drawable.ic_volume_high);
        } else {
            // 未播放
            musicControlButton.setImageResource(R.drawable.ic_volume_off);
        }
    }

    /** 视频片段播放状态变化时，联动背景音乐播放/暂停；用户手动暂停后不再被自动恢复。 */
    private void onVideoPlaybackChanged(boolean playing) {
        if (currentMusic == null || audioPlayerManager == null) return;
        if (playing) {
            if (musicUserPaused) return;
            if (!audioPlayerManager.isPlaying()) {
                WorkItem.Music loaded = audioPlayerManager.getCurrentMusic();
                if (loaded != null && loaded.equals(currentMusic)) {
                    audioPlayerManager.resume();
                } else {
                    audioPlayerManager.playMusic(currentMusic);
                }
            }
        } else {
            audioPlayerManager.pauseMusic();
        }
        updateMusicButtonState();
    }

    private void setupMusicButton() {
        if (currentMusic == null || audioPlayerManager == null) {
            if (musicControlButton != null) {
                musicControlButton.setVisibility(View.GONE);
            }
            return;
        }

        musicControlButton.setVisibility(View.VISIBLE);

        // 设置点击监听器
        musicControlButton.setOnClickListener(v -> {
            if (currentMusic == null || audioPlayerManager == null) return;

            // 执行点击动画
            animateMusicButtonClick();

            // 检查当前播放的音乐
            WorkItem.Music playingMusic = audioPlayerManager.getCurrentMusic();

            if (playingMusic != null && playingMusic.equals(currentMusic)) {
                // 正在播放当前音乐：切换播放/暂停
                audioPlayerManager.togglePlayPause();
                musicUserPaused = !audioPlayerManager.isPlaying();
            } else {
                // 播放其他音乐或没有播放：播放当前音乐
                audioPlayerManager.playMusic(currentMusic);
                musicUserPaused = false;
            }

            // 延迟更新按钮状态，确保播放状态已经改变
            musicControlButton.postDelayed(this::updateMusicButtonState, 1000);
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector == null || imageContainer == null) return super.onTouchEvent(event);
        boolean handled = gestureDetector.onTouchEvent(event);

        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (isDragging) resetDragState();
            isDragging = false;
            dragEligible = false;
        }

        // 处理手指抬起事件
        if (event.getAction() == MotionEvent.ACTION_UP && isDragging) {
            isDragging = false;
            float currentY = imageContainer.getTranslationY();

            // 未达到退出阈值则回弹，否则淡出退出
            if (Math.abs(currentY) < getWindow().getDecorView().getHeight() * 0.3f) {
                resetDragState();
            } else {
                finishWithFade();
            }
        }

        if (event.getActionMasked() == MotionEvent.ACTION_UP) dragEligible = false;
        return handled || super.onTouchEvent(event);
    }

    @Override
    public void onShowPress(MotionEvent e) {}

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
        if (!dragEligible || e2.getPointerCount() != 1) return false;
        float deltaX = e2.getRawX() - dragStartX;
        float deltaY = e2.getRawY() - dragStartY;

        // 水平滑动交给 ViewPager2 切页，不参与拖拽退出手势
        if (Math.abs(deltaX) > Math.abs(deltaY)) return false;
        if (Math.abs(deltaY) < ViewConfiguration.get(this).getScaledTouchSlop()) return false;

        isDragging = true;

        // 直接应用拖拽位移（仅垂直）
        imageContainer.setTranslationY(deltaY);

        // 根据拖拽距离实时调整透明度
        float dragDistance = Math.abs(deltaY);
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

        // Media should stay level even when the image dismiss gesture is active.
        imageContainer.setRotation(0f);

        return true;
    }

    @Override
    public void onLongPress(@NonNull MotionEvent e) {}

    @Override
    public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
        // Distance is checked on UP; a short gesture must not dismiss the detail screen.
        return false;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        dragEligible = imageAdapter != null && imageAdapter.getItemCount() > 0
                && imageAdapter.getItemViewType(imagePager.getCurrentItem()) == 0
                && imageContainer.getGlobalVisibleRect(bounds)
                && bounds.contains((int)e.getRawX(), (int)e.getRawY());
        dragStartX = e.getRawX();
        dragStartY = e.getRawY();
        isDragging = false;
        return dragEligible;
    }

    private void loadInteractionState() {
        refreshServerLike();
        // 游客也能看详情：不读取需要登录的关注状态，避免弹出登录提示。
        if (ApiClient.getInstance(this).isLoggedIn()) refreshServerFollow();
    }

    private boolean canUpdateInteraction() { return !isFinishing() && !isDestroyed(); }

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

        // 音乐
        musicControlButton = findViewById(R.id.music_control_button);
        audioPlayerManager = AudioPlayer.createForScreen(this);
        currentMusic = workItem.getMusic();

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
    }

    private void setupData() {
        backButton.setOnClickListener(v -> returnWithResult());

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
        // A card may belong to a previous session; confirm state before displaying it.
        favorited = false;
        updateFavoriteButton();

        // 音乐
        setupMusicButton();
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

        // 点击作者进入主页（作者 ID 缺失时不响应）
        View authorContainer = findViewById(R.id.author_container);
        if (authorContainer != null && workItem.getAuthor() != null
                && workItem.getAuthor().getUserId() != null
                && !workItem.getAuthor().getUserId().isEmpty()) {
            authorContainer.setOnClickListener(v -> startActivity(new Intent(this, UserProfileActivity.class)
                    .putExtra(UserProfileActivity.EXTRA_USER_ID, workItem.getAuthor().getUserId())));
        }

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

        // 设置图片/视频高度（详情页按真实宽高比填满，不裁切不留白）
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        ViewGroup.LayoutParams params = imagePager.getLayoutParams();
        params.height = workItem.getDetailDisplayHeight(screenWidth);
        imagePager.setLayoutParams(params);

        // 设置适配器
        imageAdapter = new ImagePagerAdapter(this, mediaItems);
        imageAdapter.setVideoPlaybackListener(this::onVideoPlaybackChanged);
        imageAdapter.setImageClickListener(this::openFullScreenImage);
        imageAdapter.restorePlayback(restoredPlayback);
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

        int initialPage = restoredPlayback == null ? Math.max(0, workItem.getFirstVideoPosition())
                : restoredPlayback.getInt("page", 0);
        initialPage = Math.max(0, Math.min(initialPage, mediaItems.size() - 1));
        imagePager.setCurrentItem(initialPage, false);
        imageAdapter.onPageChanged(initialPage);
        updateProgressDots(initialPage);
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
            highlightHashtagsInContent(content,workItem.getHashtags());
        } else {
            contentText.setVisibility(View.GONE);
        }
    }

    private void highlightHashtagsInContent(String content, List<WorkItem.Hashtag> hashtags) {
        if (content == null || content.isEmpty() || hashtags == null || hashtags.isEmpty()) {
            TextView contentText = findViewById(R.id.content_text);
            contentText.setText(content);
            return;
        }

        // 创建SpannableString来实现部分文本高亮
        SpannableString spannableString = new SpannableString(content);
        int primaryColor = getResources().getColor(R.color.primary_color);

        // 创建全局的点击处理
        for (WorkItem.Hashtag hashtag : hashtags) {
            int start = hashtag.getStart();
            int end = hashtag.getEnd();

            // 确保索引在有效范围内
            if (start >= 0 && end <= content.length() && start < end) {
                // 获取话题词文本（去掉#号）
                String tagText = content.substring(start, end);
                String tagName = tagText.replace("#", "");

                // 创建可点击的Span
                ClickableSpan clickableSpan = new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        // 跳转到话题词页面
                        navigateToHashtagPage(tagName);
                    }

                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(primaryColor);
                        ds.setUnderlineText(false);
                        ds.setFakeBoldText(true);
                    }
                };

                // 添加点击事件
                spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                // 设置蓝色高亮颜色
                ForegroundColorSpan colorSpan = new ForegroundColorSpan(primaryColor);
                spannableString.setSpan(colorSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                // 设置粗体
                StyleSpan styleSpan = new StyleSpan(Typeface.BOLD);
                spannableString.setSpan(styleSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        // 设置到TextView
        TextView contentText = findViewById(R.id.content_text);
        contentText.setText(spannableString);
        contentText.setMovementMethod(LinkMovementMethod.getInstance());
        contentText.setHighlightColor(Color.TRANSPARENT); // 移除点击背景色
    }

    private void navigateToHashtagPage(String tagName) {
        Intent intent = new Intent(this, HashtagActivity.class);
        intent.putExtra("hashtag", tagName);
        startActivity(intent);
        // 添加页面切换动画
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void openFullScreenImage(String url) {
        if (isFinishing() || isDestroyed() || fullscreenImageDialog != null) return;
        FullScreenMediaDialog dialog = new FullScreenMediaDialog(this, url);
        fullscreenImageDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (fullscreenImageDialog == dialog) fullscreenImageDialog = null;
        });
        Window window = dialog.getWindow();
        if (window != null) window.setWindowAnimations(R.style.FullScreenDialogAnimation);
        dialog.show();
    }

    private void setupListeners() {
        // 关注按钮
        followButton.setOnClickListener(v -> toggleFollowStatus());

        // 点赞按钮
        likeButton.setOnClickListener(v -> toggleLikeStatus());

        // 收藏按钮
        collectButton.setOnClickListener(v -> toggleFavoriteStatus());

        // 分享按钮
        shareButton.setOnClickListener(v -> shareWork());

        // 评论为后续能力，本轮不做；评论框/按钮暂为占位。
    }

    private void updateFollowButton() {
        boolean hasAuthor = workItem.getAuthor() != null && workItem.getAuthor().getUserId() != null
                && !workItem.getAuthor().getUserId().isEmpty();
        boolean followingNow = followLoaded && following;
        followButton.setEnabled(hasAuthor && !followSaving);
        followButton.setText(!hasAuthor ? "作者信息缺失" : followingNow ? "已关注" : "关注");
        followButton.setBackgroundColor(getResources().getColor(
                followingNow ? R.color.gray_200 : android.R.color.transparent));
        followButton.setTextColor(getResources().getColor(
                followingNow ? R.color.gray_600 : R.color.primary_color));
        followButton.setStrokeWidth(followingNow ? 0 : 2);
    }

    private void refreshServerFollow() {
        // 未登录时直接展示可点击的“关注”按钮，点击时跳转登录，不请求关注状态也不弹提示。
        if (!ApiClient.getInstance(this).isLoggedIn()) { updateFollowButton(); return; }
        String userId = workItem.getAuthor() == null ? null : workItem.getAuthor().getUserId();
        if (userId == null || userId.isEmpty()) { updateFollowButton(); return; }
        if (followJob != null) followJob.cancel();
        followLoaded = false;
        followSaving = true;
        updateFollowButton();
        followJob = ApiClient.getInstance(this).getUserProfile(userId, serverFollowCallback());
    }

    private CommunityClient.Callback<UserProfile> serverFollowCallback() {
        return new CommunityClient.Callback<UserProfile>() {
            public void onSuccess(UserProfile profile) {
                if (!canUpdateInteraction()) return;
                followJob = null; followSaving = false;
                following = profile.isFollowing;
                followLoaded = true;
                updateFollowButton();
            }
            public void onFailure(String message) {
                if (!canUpdateInteraction()) return;
                followJob = null; followSaving = false;
                followLoaded = false;
                updateFollowButton();
                Toast.makeText(WorkDetailActivity.this, message + "；点击关注按钮可重新读取状态", Toast.LENGTH_LONG).show();
            }
        };
    }

    private void toggleFollowStatus() {
        if (followSaving) return;
        String userId = workItem.getAuthor() == null ? null : workItem.getAuthor().getUserId();
        if (userId == null || userId.isEmpty()) return;
        if (!ApiClient.getInstance(this).isLoggedIn()) {
            startActivity(new Intent(this, com.PixelFlow.app.AccountActivity.class));
            return;
        }
        if (!followLoaded) { refreshServerFollow(); return; }
        boolean target = !following;
        following = target;
        followSaving = true;
        updateFollowButton();
        followJob = ApiClient.getInstance(this).setFollow(userId, target, new CommunityClient.Callback<Void>() {
            public void onSuccess(Void ignored) {
                if (!canUpdateInteraction()) return;
                followJob = null; followSaving = false;
                followLoaded = true;
                updateFollowButton();
            }
            public void onFailure(String message) {
                if (!canUpdateInteraction()) return;
                followJob = null; followSaving = false;
                following = !target;
                updateFollowButton();
                Toast.makeText(WorkDetailActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateLikeButton() {
        boolean liked = loadedLike != null && loadedLike.isLiked();
        likeButton.setEnabled(!likeSaving);
        likeButton.setSelected(liked);
        likeButton.clearColorFilter();
        likeButton.setImageResource(liked ? R.drawable.ic_thumb_up : R.drawable.ic_thumb_up_outline);
        // 颜色由 like_icon_tint 选择器按 state_selected 驱动
        likeButton.setColorFilter(getResources().getColor(
                liked ? R.color.like_active : R.color.profile_muted));
    }

    private void updateFavoriteButton() {
        collectButton.setEnabled(favoriteActive && !favoriteSaving);
        collectButton.setContentDescription(getString(favoriteSaving ? R.string.favorite_state_loading
                : !favoriteLoaded ? R.string.favorite_state_retry
                : favorited ? R.string.favorite_action_remove : R.string.favorite_action_add));
        collectButton.setColorFilter(getResources().getColor(
                favorited ? R.color.profile_accent : R.color.profile_muted));
    }

    private void toggleFavoriteStatus() {
        if (!canUpdateFavorite(favoriteVersion) || favoriteSaving) return;
        if (!ApiClient.getInstance(this).isLoggedIn()) {
            startActivity(new Intent(this, com.PixelFlow.app.AccountActivity.class));
            return;
        }
        if (!favoriteLoaded) { refreshServerFavorite(); return; }
        boolean target = !favorited;
        final int version = ++favoriteVersion;
        favoriteSaving = true;
        updateFavoriteButton();
        ApiClient.getInstance(this).setFavorite(workItem.getId(), target, new CommunityClient.Callback<PostItem>() {
            public void onSuccess(PostItem post) {
                if (!canUpdateFavorite(version)) return;
                applyFavorite(post);
                Toast.makeText(WorkDetailActivity.this, favorited ? R.string.favorite_added
                        : R.string.favorite_removed, Toast.LENGTH_SHORT).show();
            }
            public void onFailure(String message) {
                if (!canUpdateFavorite(version)) return;
                // 网络失败不证明已回滚，重新读取服务器状态核对。
                refreshServerFavorite();
                Toast.makeText(WorkDetailActivity.this, message + "；正在核对服务器结果", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void refreshServerFavorite() {
        if (!favoriteActive) return;
        if (favoriteReadJob != null) favoriteReadJob.cancel();
        final int version = ++favoriteVersion;
        favoriteLoaded = false;
        favoriteSaving = true;
        updateFavoriteButton();
        favoriteReadJob = ApiClient.getInstance(this).getPost(workItem.getId(), new CommunityClient.Callback<PostItem>() {
            public void onSuccess(PostItem post) {
                if (!canUpdateFavorite(version)) return;
                favoriteReadJob = null;
                applyFavorite(post);
            }
            public void onFailure(String message) {
                if (!canUpdateFavorite(version)) return;
                favoriteReadJob = null; favoriteSaving = false;
                favoriteLoaded = false;
                favorited = false;
                workItem.setFavorited(false);
                updateFavoriteButton();
                Toast.makeText(WorkDetailActivity.this, message + "；点击收藏按钮可重新读取状态", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void applyFavorite(PostItem post) {
        favoriteSaving = false;
        favoriteLoaded = true;
        favorited = post.isFavorited();
        workItem.setFavorited(favorited);
        updateFavoriteButton();
    }

    private boolean canUpdateFavorite(int version) {
        if (!favoriteActive || !canUpdateInteraction() || version != favoriteVersion) return false;
        if (favoriteSession != ApiClient.getInstance(this).sessionVersion()) {
            resumeFavorite();
            return false;
        }
        return true;
    }

    private void clearFavorite() {
        ++favoriteVersion;
        if (favoriteReadJob != null) favoriteReadJob.cancel();
        favoriteReadJob = null;
        favoriteLoaded = false;
        favoriteSaving = false;
        favorited = false;
        if (workItem != null) workItem.setFavorited(false);
        if (collectButton != null) updateFavoriteButton();
    }

    private void resumeFavorite() {
        clearFavorite();
        favoriteSession = ApiClient.getInstance(this).sessionVersion();
        // This read is queued after any write submitted before leaving/recreating the page.
        refreshServerFavorite();
    }

    private void refreshServerLike() {
        if (likeJob != null) likeJob.cancel();
        loadedLike = null;
        likeSaving = true;
        updateLikeButton();
        likeJob = ApiClient.getInstance(this).getPost(workItem.getId(), serverLikeCallback());
    }

    private CommunityClient.Callback<PostItem> serverLikeCallback() {
        return new CommunityClient.Callback<PostItem>() {
            public void onSuccess(PostItem post) {
                if (!canUpdateInteraction()) return;
                likeJob = null; likeSaving = false;
                loadedLike = new LikeStatus(post.getPostId(), post.isLiked(), post.getLikeCount());
                workItem.setLiked(post.isLiked());
                workItem.setLikeCount(post.getLikeCount());
                updateLikeButton();
            }
            public void onFailure(String message) {
                if (!canUpdateInteraction()) return;
                likeJob = null; likeSaving = false;
                loadedLike = null;
                updateLikeButton();
                Toast.makeText(WorkDetailActivity.this, message + "；点击点赞按钮可重新读取状态", Toast.LENGTH_LONG).show();
            }
        };
    }

    private void toggleLikeStatus() {
        if (likeSaving) return;
        if (!ApiClient.getInstance(this).isLoggedIn()) {
            startActivity(new Intent(this, com.PixelFlow.app.AccountActivity.class));
            return;
        }
        if (loadedLike == null) { refreshServerLike(); return; }
        likeSaving = true;
        isChanged = true;
        updateLikeButton();
        likeJob = ApiClient.getInstance(this).setLike(workItem.getId(), !loadedLike.isLiked(), serverLikeCallback());
    }

    private void toggleMusicButton(){
        if(currentMusic==null){
            return ;
        }
    }

    private void shareWork() {
        // 分享功能
        // Intent shareIntent = new Intent(Intent.ACTION_SEND);
        // shareIntent.setType("text/plain");
        // shareIntent.putExtra(Intent.EXTRA_TEXT, "分享作品: " + workItem.getTitle());
        // startActivity(Intent.createChooser(shareIntent, "分享到"));
    }

    // 返回结果并淡出退出
    private void returnWithResult() {
        // 立即返回，不等待任何延迟
        Intent resultIntent = new Intent();

        if (isChanged) {
            resultIntent.putExtra("work_id", workItem.getId());
            LikeStatus likeStatus = loadedLike;

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
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    public void onBackPressed() {
        returnWithResult();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (imageAdapter != null) outState.putBundle("media.playback", imageAdapter.savePlayback());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (fullscreenImageDialog != null) fullscreenImageDialog.dismiss();
        if (imageAdapter != null) imageAdapter.releaseAllPlayers();
        if (likeJob != null) likeJob.cancel();
        if (followJob != null) followJob.cancel();
        favoriteActive = false;
        clearFavorite();
        super.onDestroy();
        if (imagePager != null) {
            imagePager.setAdapter(null);
        }
        if (audioPlayerManager != null) {
            audioPlayerManager.release();
            audioPlayerManager = null;
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (workItem != null) {
            ApiClient api = ApiClient.getInstance(this);
            viewingStarted = android.os.SystemClock.elapsedRealtime();
            viewingSession = api.sessionVersion();
            viewingUser = api.username();
            if (!clickTracked && api.isLoggedIn()) {
                api.trackEvent(workItem.getId(),"CLICK",null,"DETAIL");
                clickTracked = true;
            }
        }
        if (workItem != null && likeButton != null && !likeSaving) refreshServerLike();
        if (workItem != null && followButton != null && !followSaving) refreshServerFollow();
        if (workItem != null && collectButton != null) {
            favoriteActive = true;
            resumeFavorite();
        }
        if (imageAdapter != null) {
            imageAdapter.resumeCurrentVideo();
        }

        // 注册音频播放器到MainActivity（如果需要）
        if (audioPlayerManager != null) {
            audioPlayerManager.updateMuteState(MainActivity.isGlobalMuted());
            MainActivity.registerAudioPlayer(audioPlayerManager);
        }

        // 更新音乐按钮状态
        updateMusicButtonState();
    }

    @Override
    protected void onStop() {
        favoriteActive = false;
        clearFavorite();
        super.onStop();
    }

    @Override
    protected void onPause() {
        if (workItem != null && viewingStarted > 0) {
            ApiClient api = ApiClient.getInstance(this);
            long duration = Math.min(1800000, android.os.SystemClock.elapsedRealtime() - viewingStarted);
            if (duration >= 1000 && api.isLoggedIn() && viewingSession == api.sessionVersion()
                    && viewingUser.equals(api.username()))
                api.trackEvent(workItem.getId(),"DWELL",duration,"DETAIL");
            viewingStarted = 0;
        }
        super.onPause();
        if (audioPlayerManager != null) {
            audioPlayerManager.pauseMusic();
        }
        if (imageAdapter != null) {
            imageAdapter.pauseCurrentVideo();
        }
    }
}
