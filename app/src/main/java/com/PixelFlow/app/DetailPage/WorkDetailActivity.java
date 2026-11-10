package com.PixelFlow.app.DetailPage;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
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
import androidx.appcompat.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.GestureDetectorCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.ClipboardManager;
import android.content.ClipData;

import com.PixelFlow.app.AudioPlayer;
import com.PixelFlow.app.DAO.PersistState.LikeStatus;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.AiTaskRegistry;
import com.PixelFlow.app.API.CommentPage;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.CurrentUser;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.API.PostSummary;
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
    private boolean ownedByMe;
    private String postStatus;

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
    private TextView commentSend;
    private ImageView likeButton;
    private ImageView collectButton;  // 保持
    private ImageView shareButton;
    private ImageView copilotButton;

    // 评论区
    private RecyclerView commentsList;
    private TextView commentsHeader;
    private TextView commentsEmpty;
    private TextView commentsMore;
    private com.PixelFlow.app.Streams.CommentAdapter commentAdapter;
    private String commentsCursor;
    private boolean commentsHasMore;
    private boolean commentsLoading;
    private int commentsGeneration;
    private String selfUserId = "";
    private CommentPage.CommentItem replyTarget;
    private boolean commentSending;
    /** 键盘是否处于弹出状态，用于判断“退出打字”并回到默认评论态。 */
    private boolean imeWasVisible;
    private boolean imeKeyboardVisible;

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
    private AiTaskRegistry.Task summaryTask;
    private String summaryKey;
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

        // 启用窗口内容转场动画
        getWindow().setEnterTransition(null);
        getWindow().setExitTransition(null);

        super.onCreate(savedInstanceState);
        restoredPlayback = savedInstanceState == null ? null : savedInstanceState.getBundle("media.playback");
        setContentView(R.layout.activity_detail);

        // 沉浸式标志下窗口不会随键盘重排，手动用 IME insets 顶起底部栏与内容
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_layout), (view, insets) -> {
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(view.getPaddingLeft(), insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, view.getPaddingRight(), imeBottom);
            boolean imeVisible = imeBottom > 0;
            // 键盘收起 = 退出打字：回复态回到默认的「评论这条帖子」
            if (imeWasVisible && !imeVisible) cancelReplyMode();
            if (imeVisible != imeWasVisible) {
                imeWasVisible = imeVisible;
                imeKeyboardVisible = imeVisible;
                // 用返回键收键盘时输入框通常仍持有焦点，这里必须主动恢复图标
                updateCommentActions();
            } else {
                imeWasVisible = imeVisible;
                imeKeyboardVisible = imeVisible;
            }
            return insets;
        });

        // 获取传递的数据
        workItem = (WorkItem) getIntent().getSerializableExtra("work_item");
        if (workItem == null) {
            finish();
            return;
        }

        shareController = new com.PixelFlow.app.share.PostShareController(this, workItem.getId());
        getLifecycle().addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onStop(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                shareController.cancel();
            }
            @Override public void onDestroy(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                shareController.close();
            }
        });
        initViews();

        // 初始化手势检测
        gestureDetector = new GestureDetectorCompat(this, this);

        isChanged=false;

        setupData();
        if (savedInstanceState != null) {
            String restoredSummary = savedInstanceState.getString("summary.key");
            AiTaskRegistry.Task restoredTask = restoredSummary == null ? null : AiTaskRegistry.get().peek(restoredSummary);
            if (restoredTask != null) {
                summaryKey = restoredSummary;
                subscribeSummary((TextView) findViewById(R.id.summary_body), restoredTask);
            }
            if (savedInstanceState.getBoolean("summary.expanded")) {
                findViewById(R.id.summary_card).setVisibility(View.VISIBLE);
                updateSummaryToggleIcon();
            }
        }
        setupListeners();
        loadInteractionState();
        setupComments();

        // 底部操作栏浮在内容之上，给滚动内容预留同高的底部内边距，
        // 否则最后一条评论会被输入框挡住、也滚不上来。
        bottomBar.post(() -> {
            androidx.core.widget.NestedScrollView scroll = findViewById(R.id.nested_scroll_view);
            if (scroll != null && bottomBar != null && bottomBar.getHeight() > 0) {
                scroll.setClipToPadding(false);
                scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                        scroll.getPaddingRight(), bottomBar.getHeight());
            }
        });
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
        ownedByMe = getIntent().getBooleanExtra("owned", false);
        postStatus = getIntent().getStringExtra("post_status");
        if (ownedByMe) followButton.setText(R.string.detail_settings);

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
        commentSend = findViewById(R.id.comment_send);
        likeButton = findViewById(R.id.like_button);
        collectButton = findViewById(R.id.collect_button);  // 保持
        shareButton = findViewById(R.id.share_button);
        copilotButton = findViewById(R.id.copilot_button);

        // 评论区
        commentsList = findViewById(R.id.comments_list);
        commentsHeader = findViewById(R.id.comments_header);
        commentsEmpty = findViewById(R.id.comments_empty);
        commentsMore = findViewById(R.id.comments_more);
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
        setupSummarizeEntry(content);
    }

    /** 正文足够长时才提供「提炼要点」；短文强行摘要会比原文还长。 */
    private static final int MIN_SUMMARY_LENGTH = 200;

    private void setupSummarizeEntry(String content) {
        View entry = findViewById(R.id.summarize_entry);
        if (entry == null) return;
        if (content == null || content.trim().length() < MIN_SUMMARY_LENGTH) {
            entry.setVisibility(View.GONE);
            return;
        }
        entry.setVisibility(View.VISIBLE);
        entry.setOnClickListener(v -> toggleSummary());
    }

    /** 卡片展开时显示「收起」箭头，收起时显示「展开」箭头。 */
    private void updateSummaryToggleIcon() {
        android.widget.ImageButton toggle = findViewById(R.id.summary_toggle);
        if (toggle == null) return;
        boolean expanded = findViewById(R.id.summary_card).getVisibility() == View.VISIBLE;
        toggle.setImageResource(expanded ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
        toggle.setContentDescription(getString(expanded ? R.string.summary_collapse : R.string.summary_regenerate));
    }
    private AiTaskRegistry.Subscription summarySubscription;

    private void toggleSummary() {
        TextView body = findViewById(R.id.summary_body);
        View card = findViewById(R.id.summary_card);
        findViewById(R.id.summary_regen).setOnClickListener(view -> {
            if (summaryKey != null) AiTaskRegistry.get().remove(summaryKey);
            card.setVisibility(View.GONE);
            updateSummaryToggleIcon();
            toggleSummary();
        });
        findViewById(R.id.summary_stop).setOnClickListener(view -> {
            if (summaryKey != null) AiTaskRegistry.get().cancel(summaryKey);
        });
        findViewById(R.id.summary_toggle).setOnClickListener(view -> toggleSummary());
        if (card.getVisibility() == View.VISIBLE) {
            card.setVisibility(View.GONE);
            updateSummaryToggleIcon();
            findViewById(R.id.summary_regen).setVisibility(View.GONE);
            findViewById(R.id.summary_stop).setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        updateSummaryToggleIcon();
        body.setText(R.string.summarize_loading);
        String postId = workItem.getId();
        if (summaryReadJob != null) summaryReadJob.cancel();
        summaryReadJob = ApiClient.getInstance(this).getPost(postId, new CommunityClient.Callback<com.PixelFlow.app.API.PostItem>() {
            public void onSuccess(com.PixelFlow.app.API.PostItem post) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && postId.equals(workItem.getId())) attachSummary(body, post);
                });
            }
            public void onFailure(String message) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !postId.equals(workItem.getId())) return;
                    body.setText(message);
                    findViewById(R.id.summary_regen).setVisibility(
                            card.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
                    findViewById(R.id.summary_stop).setVisibility(View.GONE);
                });
            }
        });
    }

    private CommunityClient.Job summaryReadJob;

    private void attachSummary(TextView body, com.PixelFlow.app.API.PostItem post) {
        CommunityClient api = ApiClient.getInstance(this);
        String postId = workItem.getId();
        summaryKey = AiTaskRegistry.identity(api, "summary", postId, post.getTitle(), post.getContent());
        AiTaskRegistry.Task task;
        try { task = AiTaskRegistry.get().getOrCreate(summaryKey); }
        catch (IllegalStateException full) { body.setText(full.getMessage()); return; }
        subscribeSummary(body, task);
        AiTaskRegistry.get().start(task, callback -> {
            int version = task.generation;
            return api.getPostSummaryStream(postId, partial -> AiTaskRegistry.get().publish(task, version, partial),
                com.PixelFlow.app.API.AiCallbacks.forward(callback));
        });
    }

    private void subscribeSummary(TextView body, AiTaskRegistry.Task task) {
        summaryTask = task;
        if (summarySubscription != null) summarySubscription.close();
        summarySubscription = AiTaskRegistry.get().subscribe(task, this::runOnUiThread, value -> {
            if (isDestroyed() || summaryTask != value) return;
            boolean expanded = findViewById(R.id.summary_card).getVisibility() == View.VISIBLE;
            findViewById(R.id.summary_stop).setVisibility(expanded && value.isRunning() ? View.VISIBLE : View.GONE);
            findViewById(R.id.summary_regen).setVisibility(expanded && !value.isRunning()
                    && value.state != AiTaskRegistry.State.NEW && value.state != AiTaskRegistry.State.COMPLETED
                    ? View.VISIBLE : View.GONE);
            if (value.result instanceof PostSummary) body.setText(formatSummary((PostSummary) value.result));
            else if (value.result instanceof com.google.gson.JsonObject) {
                PostSummary partial = PostSummary.parse((com.google.gson.JsonObject) value.result);
                body.setText(formatSummary(partial) + "\n" + getString(value.isRunning()
                        ? R.string.ai_stream_generating : R.string.ai_stream_incomplete));
            } else if (value.error != null) body.setText(value.error);
            else body.setText(value.state == AiTaskRegistry.State.EXPIRED ? R.string.ai_expired
                    : value.state == AiTaskRegistry.State.CANCELLED ? R.string.ai_cancelled
                    : value.state == AiTaskRegistry.State.WAITING ? R.string.ai_waiting : R.string.ai_preparing);
        });
    }

    private String formatSummary(PostSummary value) {
        StringBuilder sb = new StringBuilder();
        sb.append(value.summary).append("\n\n");
        for (int i = 0; i < value.points.size(); i++) {
            sb.append(i + 1).append(". ").append(value.points.get(i)).append("\n");
        }
        sb.append("\n").append(getString(R.string.summarize_note));
        return sb.toString();
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
        followButton.setOnClickListener(v -> {
            if (ownedByMe) showOwnerSettings(); else toggleFollowStatus();
        });

        // 点赞按钮
        likeButton.setOnClickListener(v -> toggleLikeStatus());

        // 收藏按钮
        collectButton.setOnClickListener(v -> toggleFavoriteStatus());

        // 分享按钮
        shareButton.setOnClickListener(v -> shareWork());
        if (copilotButton != null) copilotButton.setOnClickListener(v -> {
            if (!ApiClient.getInstance(this).isLoggedIn()) {
                startActivity(new Intent(this, com.PixelFlow.app.AccountActivity.class));
                return;
            }
            com.PixelFlow.app.copilot.CopilotSheet.open(this, "detail", workItem.getId(), null, 0, null,
                    workItem.getTitle());
        });

        // 评论输入框：未登录点按时跳登录页（与点赞/关注一致）
        commentInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !ApiClient.getInstance(this).isLoggedIn()) {
                commentInput.clearFocus();
                startActivity(new Intent(this, com.PixelFlow.app.AccountActivity.class));
                return;
            }
            if (!hasFocus) cancelReplyMode();
            updateCommentActions();
        });
        // 输入框长按取消「回复 @xxx」状态
        commentInput.setOnLongClickListener(v -> {
            cancelReplyMode();
            return false;
        });
        // 键盘发送键提交评论
        commentInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitComment();
                return true;
            }
            return false;
        });

        // 发送按钮：输入框是多行，键盘回车只换行不会触发 IME_ACTION_SEND，
        // 所以必须有显式发送入口，否则评论无法提交。
        commentSend.setOnClickListener(v -> submitComment());
        commentInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { updateCommentSendState(); }
        });
        updateCommentSendState();
    }

    /** 输入评论时收起右侧点赞/评论/收藏/AI/分享五个图标，只留输入框和发送；不输入时恢复。 */
    private void updateCommentActions() {
        boolean inputting = commentInput != null && commentInput.hasFocus()
                && imeKeyboardVisible
                && ApiClient.getInstance(this).isLoggedIn();
        int icons = inputting ? View.GONE : View.VISIBLE;
        if (likeButton != null) likeButton.setVisibility(icons);
        if (collectButton != null) collectButton.setVisibility(icons);
        if (copilotButton != null) copilotButton.setVisibility(icons);
        if (shareButton != null) shareButton.setVisibility(icons);
        updateCommentSendState();
    }

    /** 有内容、已登录且正在输入时显示「发送」，避免空评论与游客误触。 */
    private void updateCommentSendState() {
        if (commentSend == null || commentInput == null) return;
        CharSequence text = commentInput.getText();
        boolean hasText = text != null && text.toString().trim().length() > 0;
        boolean inputting = commentInput.hasFocus() && imeKeyboardVisible
                && ApiClient.getInstance(this).isLoggedIn();
        commentSend.setVisibility(inputting && hasText ? View.VISIBLE : View.GONE);
    }

    // ==================== 评论区 ====================

    private void setupComments() {
        commentAdapter = new com.PixelFlow.app.Streams.CommentAdapter(this, selfUserId);
        commentAdapter.setOnCommentActionListener(new com.PixelFlow.app.Streams.CommentAdapter.OnCommentActionListener() {
            @Override
            public void onCommentReplyClick(CommentPage.CommentItem item, View anchor) { replyTo(item, anchor); }
            @Override
            public void onCommentLongClick(CommentPage.CommentItem item) { showCommentActions(item); }
        });
        commentsList.setLayoutManager(new LinearLayoutManager(this));
        commentsList.setAdapter(commentAdapter);
        commentsMore.setOnClickListener(v -> loadMoreComments());
        // 拉取当前用户 ID（用于「删除自己的评论」判定）
        if (ApiClient.getInstance(this).isLoggedIn()) {
            ApiClient.getInstance(this).getCurrentUser(new CommunityClient.Callback<CurrentUser>() {
                @Override public void onSuccess(CurrentUser user) {
                    selfUserId = user.id;
                    if (commentAdapter != null) commentAdapter.setSelfId(selfUserId);
                    // 作品作者才会看到「自动回复」入口
                    String authorId = workItem.getAuthor() == null ? null : workItem.getAuthor().getUserId();
                    if (authorId != null && authorId.equals(selfUserId)) {
                        View entry = findViewById(R.id.auto_reply_entry);
                        if (entry != null) {
                            entry.setVisibility(View.VISIBLE);
                            entry.setOnClickListener(v -> startActivity(new Intent(WorkDetailActivity.this,
                                    com.PixelFlow.app.AutoReplyActivity.class)
                                    .putExtra(com.PixelFlow.app.AutoReplyActivity.EXTRA_POST_ID, workItem.getId())));
                        }
                    }
                }
                @Override public void onFailure(String message) { }
            });
        }
        reloadComments();
        // 滚动到底附近自动加载下一页
        androidx.core.widget.NestedScrollView scroll = findViewById(R.id.nested_scroll_view);
        if (scroll != null) {
            scroll.setOnScrollChangeListener(new androidx.core.widget.NestedScrollView.OnScrollChangeListener() {
                @Override
                public void onScrollChange(androidx.core.widget.NestedScrollView v, int scrollX, int scrollY,
                                           int oldScrollX, int oldScrollY) {
                    View child = v.getChildAt(0);
                    if (child != null && scrollY + v.getHeight() >= child.getHeight() - 200) loadMoreComments();
                    // 悬浮顶部栏：下滑收起、上滑立刻滑回；回到顶部一定显示
                    if (topBar == null) return;
                    if (scrollY <= 0) { showTopBar(); showBottomBar(); }
                    else if (scrollY > oldScrollY + 8) { hideTopBar(); hideBottomBar(); }
                    else if (scrollY < oldScrollY - 8) { showTopBar(); showBottomBar(); }
                }
            });
        }
    }

    /** 悬浮顶部栏：下滑收起、上滑滑回。 */
    private boolean topBarHidden;

    private void hideTopBar() {
        if (topBar == null || topBarHidden) return;
        topBarHidden = true;
        topBar.animate().translationY(-topBar.getHeight()).setDuration(180).start();
    }

    private void showTopBar() {
        if (topBar == null || !topBarHidden) return;
        topBarHidden = false;
        topBar.animate().translationY(0f).setDuration(180).start();
    }
    /** Copilot 应用评论后，回到前台时刷新评论区。 */
    public void refreshCommentsAfterCopilot() { reloadComments(); }

    private void reloadComments() {
        commentsGeneration++;
        loadCommentsPage(null);
    }

    private void loadMoreComments() {
        if (!commentsHasMore || commentsCursor == null || commentsLoading) return;
        loadCommentsPage(commentsCursor);
    }

    private void loadCommentsPage(String cursor) {
        final int gen = commentsGeneration;
        commentsLoading = true;
        ApiClient.getInstance(this).getComments(workItem.getId(), cursor,
                new CommunityClient.Callback<CommentPage>() {
                    @Override public void onSuccess(CommentPage page) {
                        if (gen != commentsGeneration) return;
                        commentsLoading = false;
                        commentsCursor = page.nextCursor;
                        commentsHasMore = page.hasMore;
                        if (cursor == null) commentAdapter.setData(page.items);
                        else commentAdapter.addData(page.items);
                        updateCommentViews();
                    }
                    @Override public void onFailure(String message) {
                        if (gen != commentsGeneration) return;
                        commentsLoading = false;
                        updateCommentViews();
                    }
                });
    }

    private void updateCommentViews() {
        int count = commentAdapter.getItemCount();
        commentsHeader.setText(count > 0
                ? getString(R.string.comments_header) + " " + count
                : getString(R.string.comments_header));
        commentsEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        if (commentsHasMore && commentsCursor != null) {
            commentsMore.setVisibility(View.VISIBLE);
            commentsMore.setText("加载更多");
        } else {
            commentsMore.setVisibility(View.GONE);
        }
    }

    /** 点某条评论旁的「回复」→ 进入回复这个人的状态（默认是评论这条帖子）。 */
    private void replyTo(CommentPage.CommentItem item, View anchor) {
        if (!ApiClient.getInstance(this).isLoggedIn()) {
            startActivity(new Intent(this, com.PixelFlow.app.AccountActivity.class));
            return;
        }
        showBottomBar();
        replyTarget = item;
        commentInput.setHint(getString(R.string.comments_reply_hint, item.authorNickname));
        commentInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(commentInput, InputMethodManager.SHOW_IMPLICIT);
        // 键盘弹出会压缩内容区，稍后把被回复的那行滚到输入框上方，避免看不到在回复谁
        final int anchorTop = anchor == null ? -1 : contentTopOf(anchor);
        if (anchorTop >= 0) commentInput.postDelayed(() -> scrollBelowAnchor(anchorTop), 400);
    }

    /** 被点那一行相对 ScrollView 内容的纵坐标。 */
    private int contentTopOf(View child) {
        androidx.core.widget.NestedScrollView scroll = findViewById(R.id.nested_scroll_view);
        if (scroll == null) return -1;
        int y = 0;
        View current = child;
        while (current != null && current != scroll) {
            y += current.getTop();
            if (!(current.getParent() instanceof View)) return -1;
            current = (View) current.getParent();
        }
        return current == scroll ? y : -1;
    }

    private void scrollBelowAnchor(int anchorTop) {
        androidx.core.widget.NestedScrollView scroll = findViewById(R.id.nested_scroll_view);
        if (scroll == null) return;
        scroll.scrollTo(0, Math.max(0, anchorTop - dp(120)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 底部栏与顶部栏一起受滚动方向控制：下滑收起、上滑滑回。 */
    private boolean bottomBarHidden;

    private void showBottomBar() {
        if (bottomBar == null || !bottomBarHidden) return;
        bottomBarHidden = false;
        bottomBar.animate().translationY(0f).setDuration(180).start();
    }

    private void hideBottomBar() {
        if (bottomBar == null || bottomBarHidden || bottomBar.getHeight() == 0) return;
        bottomBarHidden = true;
        bottomBar.animate().translationY(bottomBar.getHeight()).setDuration(180).start();
    }

    /** 退出打字后回到默认：输入框重新表示“评论这条帖子”，而不是回复某个人。 */
    private void cancelReplyMode() {
        if (replyTarget == null) return;
        replyTarget = null;
        if (commentInput != null) commentInput.setHint(R.string.comments_input_hint);
    }

    /** 长按评论：自己的可删除，所有人可复制。 */
    private void showCommentActions(CommentPage.CommentItem item) {
        boolean own = commentAdapter != null && commentAdapter.isOwn(item);
        String[] options = own
                ? new String[]{getString(R.string.comments_dialog_copy), getString(R.string.comments_dialog_delete)}
                : new String[]{getString(R.string.comments_dialog_copy)};
        new android.app.AlertDialog.Builder(this, R.style.PixelFlowPlatformDialog)
                .setItems(options, (dialog, which) -> {
                    if (getString(R.string.comments_dialog_copy).equals(options[which])) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("comment", item.content));
                            android.widget.Toast.makeText(this, R.string.comments_copied, android.widget.Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        deleteComment(item);
                    }
                })
                .show();
    }

    private void deleteComment(CommentPage.CommentItem item) {
        ApiClient.getInstance(this).deleteComment(item.id, new CommunityClient.Callback<Void>() {
            @Override public void onSuccess(Void value) {
                // 删掉的评论可能正是当前回复对象（或它的所属一级评论），清掉回复态避免发到已删除目标
                if (replyTarget != null && (item.id.equals(replyTarget.id)
                        || item.id.equals(replyTarget.rootId))) {
                    cancelReplyMode();
                }
                commentAdapter.remove(item.id);
                updateCommentViews();
                android.widget.Toast.makeText(WorkDetailActivity.this, R.string.comments_deleted_toast,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            @Override public void onFailure(String message) {
                android.widget.Toast.makeText(WorkDetailActivity.this, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void submitComment() {
        if (commentSending) return;
        String content = commentInput.getText() == null ? "" : commentInput.getText().toString().trim();
        if (content.isEmpty()) return;
        if (!ApiClient.getInstance(this).isLoggedIn()) {
            startActivity(new Intent(this, com.PixelFlow.app.AccountActivity.class));
            return;
        }
        String parentId = replyTarget == null ? null : replyTarget.id;
        commentSending = true;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(commentInput.getWindowToken(), 0);
        ApiClient.getInstance(this).createComment(workItem.getId(), content, parentId,
                new CommunityClient.Callback<CommentPage.CommentItem>() {
                    @Override public void onSuccess(CommentPage.CommentItem item) {
                        commentSending = false;
                        commentInput.setText("");
                        commentInput.clearFocus();
                        replyTarget = null;
                        commentInput.setHint(R.string.comments_input_hint);
                        commentAdapter.prepend(item);
                        // 一级评论命中自动回复时服务端会再插一条，重新拉一遍才看得到
                        reloadComments();
                        updateCommentViews();
                        isChanged = true;
                    }
                    @Override public void onFailure(String message) {
                        commentSending = false;
                        android.widget.Toast.makeText(WorkDetailActivity.this, message,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
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

    /** 作者本人的作品：右上角「设置」里提供撤回。 */
    private void showOwnerSettings() {
        if (!"PUBLISHED".equals(postStatus) && !"PENDING".equals(postStatus)) {
            Toast.makeText(this, R.string.detail_withdraw_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.PixelFlowMaterialDialog)
                .setTitle(R.string.detail_settings)
                .setItems(new CharSequence[]{getString(R.string.detail_withdraw)}, (dialog, which) -> withdrawOwnWork())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void withdrawOwnWork() {
        ApiClient.getInstance(this).withdrawPost(workItem.getId(), new CommunityClient.Callback<com.PixelFlow.app.API.PostStatus>() {
            @Override public void onSuccess(com.PixelFlow.app.API.PostStatus value) {
                Toast.makeText(WorkDetailActivity.this, R.string.detail_withdrawn, Toast.LENGTH_SHORT).show();
                finish();
            }
            @Override public void onFailure(String message) {
                Toast.makeText(WorkDetailActivity.this,
                        message == null ? getString(R.string.copilot_failed) : message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateLikeButton() {
        boolean liked = loadedLike != null && loadedLike.isLiked();
        likeButton.setEnabled(!likeSaving);
        likeButton.setSelected(liked);
        // 矢量本身带颜色（已赞 #D81E06 / 未赞 #8A8A8E），不要再 setColorFilter 覆盖
        likeButton.clearColorFilter();
        likeButton.setImageResource(liked ? R.drawable.ic_thumb_up : R.drawable.ic_thumb_up_outline);
    }

    private void updateFavoriteButton() {
        collectButton.setEnabled(favoriteActive && !favoriteSaving);
        collectButton.setContentDescription(getString(favoriteSaving ? R.string.favorite_state_loading
                : !favoriteLoaded ? R.string.favorite_state_retry
                : favorited ? R.string.favorite_action_remove : R.string.favorite_action_add));
        // 已收藏用黄色实心星（矢量自带填充与描边），未收藏保持灰色描边。
        // 注意：不能写成 setColorFilter(favorited ? null : color)，三元表达式会拆箱 null 导致 NPE。
        if (favorited) {
            collectButton.setImageResource(R.drawable.ic_star_filled);
            collectButton.clearColorFilter();
        } else {
            collectButton.setImageResource(R.drawable.ic_star_outline);
            collectButton.setColorFilter(getResources().getColor(R.color.profile_muted));
        }
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

    private com.PixelFlow.app.share.PostShareController shareController;

    private void shareWork() {
        if (shareController != null) shareController.show();
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
        View summary = findViewById(R.id.summary_body);
        outState.putBoolean("summary.expanded", summary != null && summary.getVisibility() == View.VISIBLE);
        outState.putString("summary.key", summaryKey);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (summarySubscription != null) summarySubscription.close();
        if (summaryReadJob != null) summaryReadJob.cancel();
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
        if (summaryTask != null && !AiTaskRegistry.get().isCurrent(summaryTask)) {
            if (summarySubscription != null) summarySubscription.close();
            summaryTask = null;
            ((TextView) findViewById(R.id.summary_body)).setText("");
            findViewById(R.id.summary_card).setVisibility(View.GONE);
            updateSummaryToggleIcon();
            findViewById(R.id.summary_regen).setVisibility(View.GONE);
            findViewById(R.id.summary_stop).setVisibility(View.GONE);
        }
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

