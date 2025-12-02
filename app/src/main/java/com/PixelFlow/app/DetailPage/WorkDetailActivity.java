package com.PixelFlow.app.DetailPage;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import android.view.ViewGroup;

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

public class WorkDetailActivity extends AppCompatActivity {
    // 顶部作者区（不再使用MaterialToolbar）
    private ImageView backButton;
    private ImageView authorAvatar;
    private TextView authorName;
    private MaterialButton followButton;

    // 图片区域
    private ViewPager2 imagePager;
    private LinearLayout progressIndicator;

    // 内容区域
    private TextView titleText;
    private TextView contentText;
    private FlowLayout tagsContainer;
    private TextView publishDate;

    // 底部交互区
    private ImageView likeButton;
    private ImageView shareButton;

    // 数据
    private WorkItem workItem;
    private GenericStorageRepository<FollowStatus> followRepository;
    private GenericStorageRepository<LikeStatus> likeRepository;
    private ImagePagerAdapter imageAdapter;

    // 记录点赞状态是否发生改变，用于返回
    private boolean isChanged;

    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Detail);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        // 获取传递的数据
        workItem = (WorkItem) getIntent().getSerializableExtra("work_item");
        if (workItem == null) {
            finish();
            return;
        }

        initViews();

        isChanged=false;

        // 初始化数据（在后台线程）
        databaseExecutor.execute(() -> {
            initRepositories();
            ensureDataExists();

            // 数据准备好后，更新UI
            runOnUiThread(() -> {
                setupData();
                setupListeners();
            });
        });
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
        // 顶部作者区（不再使用MaterialToolbar）
        backButton = findViewById(R.id.back_button);
        authorAvatar = findViewById(R.id.author_avatar);
        authorName = findViewById(R.id.author_name);
        followButton = findViewById(R.id.follow_button);

        // 图片区域
        imagePager = findViewById(R.id.image_pager);
        progressIndicator = findViewById(R.id.progress_indicator);

        // 内容区域
        titleText = findViewById(R.id.title_text);
        contentText = findViewById(R.id.content_text);
        tagsContainer = findViewById(R.id.tags_container);
        publishDate = findViewById(R.id.publish_date);

        // 底部交互区
        likeButton = findViewById(R.id.like_button);
        shareButton = findViewById(R.id.share_button);
    }

    private void initRepositories() {
        followRepository = StorageManager.getInstance(this)
                .getRepository(DataType.FOLLOW_STATUS,FollowStatus.class);
        likeRepository = StorageManager.getInstance(this)
                .getRepository(DataType.LIKE_STATUS,LikeStatus.class);
    }

    private void setupData() {
        // 设置返回按钮
        backButton.setOnClickListener(v -> {
            returnWithResult();
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
        int imageHeight = workItem.getDisplayHeight(screenWidth);
        params.height = imageHeight;
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (databaseExecutor != null && !databaseExecutor.isShutdown()) {
            databaseExecutor.shutdown();
        }
    }
}