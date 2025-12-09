package com.PixelFlow.app.DetailPage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.ui.PlayerView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ImagePagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // 视图类型常量
    private static final int VIEW_TYPE_IMAGE = 0;
    private static final int VIEW_TYPE_VIDEO = 1;

    // 预加载配置
    private static final int PRELOAD_LIMIT = 2; // 前后各预加载的页面数
    private static final int MAX_PLAYER_POOL_SIZE = 3; // 最大播放器池大小
    private static final int PRELOAD_WINDOW = 2; // 预加载窗口大小

    private final Context context;
    private final List<WorkItem.Clip> mediaItems;
    private final LayoutInflater inflater;

    // 播放器缓存池
    private final Map<Integer, ExoPlayer> playerPool = new HashMap<>();
    private final Map<Integer, Boolean> videoPreloaded = new HashMap<>();
    private final Map<Integer, String> playerCurrentUrl = new HashMap<>(); // 记录播放器当前播放的URL

    // 当前播放状态
    private ExoPlayer currentPlayer;
    private int currentPlayingPosition = -1;
    private int currentPagePosition = -1;

    // 监听器接口
    public interface MediaPlaybackListener {
        void onVideoStarted(int position);
        void onVideoPaused(int position);
        void onVideoCompleted(int position);
        void onVideoBuffering(int position, boolean buffering);
    }

    private MediaPlaybackListener playbackListener;

    // 数据源工厂，用于复用
    private final DefaultDataSource.Factory dataSourceFactory;

    public ImagePagerAdapter(Context context, List<WorkItem.Clip> mediaItems) {
        this.context = context;
        this.mediaItems = mediaItems;
        this.inflater = LayoutInflater.from(context);
        this.dataSourceFactory = new DefaultDataSource.Factory(context);
    }

    public void setPlaybackListener(MediaPlaybackListener listener) {
        this.playbackListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        if (mediaItems != null && position < mediaItems.size()) {
            WorkItem.Clip clip = mediaItems.get(position);
            return clip.getType() == 1 ? VIEW_TYPE_VIDEO : VIEW_TYPE_IMAGE;
        }
        return VIEW_TYPE_IMAGE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_VIDEO) {
            View view = inflater.inflate(R.layout.item_detail_video, parent, false);
            return new VideoViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_detail_image, parent, false);
            return new ImageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ImageViewHolder) {
            ((ImageViewHolder) holder).bind(mediaItems.get(position));
        } else if (holder instanceof VideoViewHolder) {
            ((VideoViewHolder) holder).bind(mediaItems.get(position), position);
        }

        // 智能预加载：当绑定ViewHolder时预加载相邻的视频
        preloadAdjacentVideos(position);
    }

    @Override
    public int getItemCount() {
        return mediaItems != null ? mediaItems.size() : 0;
    }

    /**
     * 预加载相邻的视频
     */
    private void preloadAdjacentVideos(int currentPosition) {
        int start = Math.max(0, currentPosition - PRELOAD_LIMIT);
        int end = Math.min(mediaItems.size() - 1, currentPosition + PRELOAD_LIMIT);

        for (int i = start; i <= end; i++) {
            if (i == currentPosition) continue;

            WorkItem.Clip clip = mediaItems.get(i);
            if (clip.getType() == 1 && !Boolean.TRUE.equals(videoPreloaded.get(i))) {
                preloadVideo(i, clip.getUrl());
            }
        }

        cleanupPreloadedVideos(start, end);
    }

    /**
     * 预加载视频 - 修复版：确保预加载完成后再标记
     */
    private void preloadVideo(int position, String videoUrl) {
        if (Boolean.TRUE.equals(videoPreloaded.get(position))) {
            return;
        }

        ExoPlayer player = obtainPlayerForPosition(position, videoUrl);
        if (player == null) {
            return;
        }

        // 检查是否已经是相同URL
        String currentUrl = playerCurrentUrl.get(position);
        if (currentUrl != null && currentUrl.equals(videoUrl) &&
                player.getPlaybackState() != Player.STATE_IDLE) {
            // 已经是同一个URL且已经准备，标记为预加载完成
            videoPreloaded.put(position, true);
            return;
        }

        // 准备媒体但不播放
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoUrl));
        player.setMediaItem(mediaItem);
        player.prepare();

        // 关键修复：监听准备完成后再标记为预加载完成
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    // 准备完成后标记为预加载完成
                    videoPreloaded.put(position, true);
                    playerCurrentUrl.put(position, videoUrl);

                    // 移除监听器避免内存泄漏
                    player.removeListener(this);

                    // 确保不是当前页面时暂停
                    if (position != currentPagePosition) {
                        player.setPlayWhenReady(false);
                    }
                }
            }
        });

        player.setPlayWhenReady(false);
        playerPool.put(position, player);
    }

    /**
     * 为指定位置获取或创建播放器
     */
    private ExoPlayer obtainPlayerForPosition(int position, String url) {
        ExoPlayer player = playerPool.get(position);
        if (player == null) {
            player = createLightweightPlayer();

            // 如果池子满了，清理最远的播放器
            if (playerPool.size() >= MAX_PLAYER_POOL_SIZE) {
                Integer toRemove = null;
                int maxDist = -1;
                for (int posKey : playerPool.keySet()) {
                    if (posKey == position) continue;
                    int dist = Math.abs(posKey - position);
                    if (dist > maxDist) {
                        maxDist = dist;
                        toRemove = posKey;
                    }
                }
                if (toRemove != null) {
                    ExoPlayer oldPlayer = playerPool.remove(toRemove);
                    playerCurrentUrl.remove(toRemove);
                    videoPreloaded.remove(toRemove);
                    if (oldPlayer != null && oldPlayer != currentPlayer) {
                        try {
                            oldPlayer.release();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return player;
    }

    /**
     * 创建轻量级播放器 - 恢复原始缓冲策略
     */
    private ExoPlayer createLightweightPlayer() {
        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        1500,    // 最小缓冲时间
                        5000,   // 最大缓冲时间
                        500,     // 播放开始缓冲时间
                        1500     // 恢复播放缓冲时间
                )
                .build();

        return new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build();
    }

    /**
     * 清理超出预加载范围的视频 - 修复版：不要清理当前页面附近的播放器
     */
    private void cleanupPreloadedVideos(int start, int end) {
        // 扩展清理范围，避免误清理即将使用的播放器
        int expandedStart = Math.max(0, start - 1);
        int expandedEnd = Math.min(mediaItems.size() - 1, end + 1);

        for (int position : playerPool.keySet().toArray(new Integer[0])) {
            if (position < expandedStart || position > expandedEnd) {
                // 如果不是当前播放的播放器，才清理
                if (position != currentPlayingPosition) {
                    ExoPlayer player = playerPool.remove(position);
                    playerCurrentUrl.remove(position);
                    videoPreloaded.remove(position);
                    if (player != null && player != currentPlayer) {
                        try {
                            player.release();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    /**
     * 准备媒体（如果需要）- 这是给ViewHolder调用的
     */
    private void prepareMediaIfNeeded(ExoPlayer player, int position, String url) {
        String currentUrl = playerCurrentUrl.get(position);
        if (currentUrl == null || !currentUrl.equals(url) || player.getPlaybackState() == Player.STATE_IDLE) {
            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
            player.setMediaItem(mediaItem);
            player.prepare();
            playerCurrentUrl.put(position, url);
        }
    }

    /**
     * 页面切换时调用
     */
    public void onPageChanged(int newPosition) {
        int oldPosition = currentPagePosition;
        this.currentPagePosition = newPosition;

        // 暂停旧位置的视频
        if (oldPosition >= 0 && oldPosition < getItemCount()) {
            if (currentPlayer != null && currentPlayingPosition == oldPosition) {
                currentPlayer.pause();
            }

            // 对于非当前播放的预加载视频，也暂停
            ExoPlayer oldPlayer = playerPool.get(oldPosition);
            if (oldPlayer != null && oldPlayer.isPlaying()) {
                oldPlayer.pause();
            }
        }

        // 播放新位置的视频（如果是视频类型）
        if (newPosition >= 0 && newPosition < getItemCount()) {
            WorkItem.Clip clip = mediaItems.get(newPosition);
            if (clip.getType() == 1) {
                ExoPlayer player = playerPool.get(newPosition);
                if (player != null) {
                    // 关键修复：确保播放器已准备好
                    if (player.getPlaybackState() == Player.STATE_IDLE) {
                        // 如果还没准备好，重新准备
                        prepareMediaIfNeeded(player, newPosition, clip.getUrl());

                        // 监听准备完成
                        ExoPlayer finalPlayer = player;
                        player.addListener(new Player.Listener() {
                            @Override
                            public void onPlaybackStateChanged(int playbackState) {
                                if (playbackState == Player.STATE_READY) {
                                    finalPlayer.setPlayWhenReady(true);
                                    finalPlayer.play();
                                    currentPlayer = finalPlayer;
                                    currentPlayingPosition = newPosition;
                                    finalPlayer.removeListener(this);
                                }
                            }
                        });
                    } else {
                        // 已经准备好，直接播放
                        player.setPlayWhenReady(true);
                        player.play();
                        currentPlayer = player;
                        currentPlayingPosition = newPosition;
                    }
                } else {
                    // 没有预加载的播放器，创建并准备
                    player = obtainPlayerForPosition(newPosition, clip.getUrl());
                    prepareMediaIfNeeded(player, newPosition, clip.getUrl());

                    // 监听准备完成
                    ExoPlayer finalPlayer1 = player;
                    player.addListener(new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int playbackState) {
                            if (playbackState == Player.STATE_READY) {
                                finalPlayer1.setPlayWhenReady(true);
                                finalPlayer1.play();
                                currentPlayer = finalPlayer1;
                                currentPlayingPosition = newPosition;
                                finalPlayer1.removeListener(this);
                            }
                        }
                    });
                }
            }

            // 通知UI更新
            notifyItemChanged(newPosition);
        }

        // 也更新旧位置的UI
        if (oldPosition >= 0 && oldPosition < getItemCount()) {
            notifyItemChanged(oldPosition);
        }
    }

    /**
     * 暂停当前正在播放的视频
     */
    public void pauseCurrentVideo() {
        if (currentPlayer != null && currentPlayer.isPlaying()) {
            currentPlayer.pause();
            if (playbackListener != null) {
                playbackListener.onVideoPaused(currentPlayingPosition);
            }
        }
    }

    /**
     * 释放所有资源
     */
    public void releaseAllPlayers() {
        for (ExoPlayer player : playerPool.values()) {
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {}
            }
        }
        playerPool.clear();
        playerCurrentUrl.clear();
        videoPreloaded.clear();

        if (currentPlayer != null) {
            currentPlayer.release();
            currentPlayer = null;
        }
        currentPlayingPosition = -1;
    }

    // 图片ViewHolder
    static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        ProgressBar progressBar;

        ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.detail_image);
            progressBar = itemView.findViewById(R.id.image_loading);
        }

        void bind(WorkItem.Clip clip) {
            progressBar.setVisibility(View.VISIBLE);

            Glide.with(itemView.getContext())
                    .load(clip.getUrl())
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_error)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .dontTransform()
                    .addListener(new RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<android.graphics.drawable.Drawable> target,
                                                    boolean isFirstResource) {
                            progressBar.setVisibility(View.GONE);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                       Target<android.graphics.drawable.Drawable> target,
                                                       DataSource dataSource, boolean isFirstResource) {
                            progressBar.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(imageView);
        }
    }

    // 视频ViewHolder
    class VideoViewHolder extends RecyclerView.ViewHolder {
        PlayerView playerView;
        ImageView videoCover;
        ImageView playButton;
        ProgressBar loadingProgress;
        FrameLayout videoContainer;

        private int viewHolderPosition = -1;
        // 关键：添加自己的监听器实例
        private final Player.Listener viewHolderPlayerListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                itemView.post(() -> {
                    ExoPlayer player = (ExoPlayer) playerView.getPlayer();
                    if (player == null) return;

                    switch (playbackState) {
                        case Player.STATE_BUFFERING:
                            // 只有在播放或尝试播放时才显示加载
                            if (player.isPlaying() || player.getPlayWhenReady()) {
                                loadingProgress.setVisibility(View.VISIBLE);
                            }
                            break;

                        case Player.STATE_READY:
                            loadingProgress.setVisibility(View.GONE);

                            // 如果这是当前页面且设置了自动播放，但现在才准备好
                            if (viewHolderPosition == currentPagePosition &&
                                    player.getPlayWhenReady() &&
                                    !player.isPlaying()) {
                                player.play();
                            }
                            break;

                        case Player.STATE_ENDED:
                            loadingProgress.setVisibility(View.GONE);
                            // 播放结束：显示封面和重播按钮
                            playerView.setVisibility(View.GONE);
                            videoCover.setVisibility(View.VISIBLE);
                            playButton.setVisibility(View.VISIBLE);
                            playButton.setImageResource(R.drawable.ic_replay);
                            break;

                        case Player.STATE_IDLE:
                            loadingProgress.setVisibility(View.GONE);
                            // 闲置状态：显示封面
                            if (!player.isPlaying()) {
                                playerView.setVisibility(View.GONE);
                                videoCover.setVisibility(View.VISIBLE);
                            }
                            break;
                    }
                });
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                itemView.post(() -> {
                    ExoPlayer player = (ExoPlayer) playerView.getPlayer();
                    if (player == null) return;

                    if (isPlaying) {
                        // 开始播放时隐藏封面和按钮，显示视频
                        loadingProgress.setVisibility(View.GONE);
                        videoCover.setVisibility(View.GONE);
                        playButton.setVisibility(View.GONE);
                        playerView.setVisibility(View.VISIBLE);
                    } else {
                        // 暂停：根据当前页面状态决定UI
                        if (viewHolderPosition == currentPagePosition) {
                            // 是当前页面但暂停了：隐藏视频，显示封面，不显示播放按钮
                            playerView.setVisibility(View.GONE);
                            videoCover.setVisibility(View.VISIBLE);
                            playButton.setVisibility(View.GONE); // 不显示按钮，点击封面继续播放
                        } else {
                            // 不是当前页面：隐藏视频，显示封面和播放按钮
                            playerView.setVisibility(View.GONE);
                            videoCover.setVisibility(View.VISIBLE);
                            playButton.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        };

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            playerView = itemView.findViewById(R.id.video_player_view);
            videoCover = itemView.findViewById(R.id.video_cover);
            playButton = itemView.findViewById(R.id.video_play_button);
            loadingProgress = itemView.findViewById(R.id.video_loading);
            videoContainer = itemView.findViewById(R.id.video_container);

            // 初始化播放器视图 - 关键修复：彻底隐藏内置缓冲指示器
            setupPlayerView();
        }

        private void setupPlayerView() {
            playerView.setUseController(false);
            playerView.setControllerAutoShow(true);
            playerView.setControllerHideOnTouch(true);
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); // 完全隐藏内置缓冲
            playerView.setControllerHideDuringAds(false);
            playerView.setKeepContentOnPlayerReset(true);

            // 进一步确保隐藏任何内置缓冲视图
            try {
                // 尝试查找并隐藏ExoPlayer内置的缓冲视图
                View bufferingView = playerView.findViewById(
                        com.google.android.exoplayer2.ui.R.id.exo_buffering
                );
                if (bufferingView != null) {
                    bufferingView.setVisibility(View.GONE);
                }
            } catch (Exception ignored) {}
        }

        void bind(WorkItem.Clip clip, int position) {
            this.viewHolderPosition = position;

            // 重置视图状态
            resetViewState();

            // 加载封面
            Glide.with(context)
                    .load(getVideoCoverUrl(clip))
                    .placeholder(R.drawable.ic_video_placeholder)
                    .error(R.drawable.ic_image_error)
                    .dontTransform()
                    .into(videoCover);

            // 如果是视频类型
            if (clip.getType() == 1) {
                // 获取播放器
                ExoPlayer player = obtainPlayerForPosition(position, clip.getUrl());

                // 确保媒体已准备
                prepareMediaIfNeeded(player, position, clip.getUrl());

                // 绑定播放器到视图
                attachPlayerToView(player, position);

                // 设置点击监听器
                setupClickListeners(position, clip.getUrl());

                // 关键修复：如果是当前页面
                if (position == currentPagePosition) {
                    // 先设置自动播放标志
                    player.setPlayWhenReady(true);

                    // 如果已经准备好了，立即播放
                    if (player.getPlaybackState() == Player.STATE_READY) {
                        player.play();
                        playButton.setVisibility(View.GONE);
                        videoCover.setVisibility(View.GONE);
                    }
                    // 如果还没准备好（STATE_IDLE 或 STATE_BUFFERING）
                    // 监听器会在 STATE_READY 时自动播放
                } else {
                    // 不是当前页面
                    player.setPlayWhenReady(false);
                    playButton.setVisibility(View.VISIBLE);
                }
            }
        }

        private void resetViewState() {
            // 重置时：如果是当前页面，只显示封面；否则显示封面和播放按钮
            playerView.setVisibility(View.GONE);
            videoCover.setVisibility(View.VISIBLE);

            if (viewHolderPosition == currentPagePosition) {
                // 当前页面：不显示播放按钮（等待自动播放或用户点击封面）
                playButton.setVisibility(View.GONE);
            } else {
                // 非当前页面：显示播放按钮
                playButton.setVisibility(View.VISIBLE);
                playButton.setImageResource(R.drawable.ic_play);
            }

            loadingProgress.setVisibility(View.GONE);
        }

        private void setupClickListeners(int position, String videoUrl) {
            // 清除旧的监听器避免重复绑定
            playButton.setOnClickListener(null);
            videoCover.setOnClickListener(null);
            videoContainer.setOnClickListener(null);

            // 统一的点击处理器
            View.OnClickListener clickListener = v -> {
                onMediaClicked(position, videoUrl);
            };

            playButton.setOnClickListener(clickListener);
            videoCover.setOnClickListener(clickListener);
            videoContainer.setOnClickListener(clickListener);
        }

        private void onMediaClicked(int position, String videoUrl) {
            // 如果点击的是当前正在播放的视频
            if (currentPlayingPosition == position && currentPlayer != null) {
                if (currentPlayer.isPlaying()) {
                    // 如果正在播放，点击暂停
                    currentPlayer.pause();
                    // UI更新由监听器处理
                } else {
                    // 如果已暂停，点击恢复播放
                    currentPlayer.setPlayWhenReady(true);
                    currentPlayer.play();
                    // 立即更新UI
                    playerView.setVisibility(View.VISIBLE);
                    videoCover.setVisibility(View.GONE);
                    playButton.setVisibility(View.GONE);
                }
                return;
            }

            // 播放新视频
            playVideo(position, videoUrl);
        }

        private void playVideo(int position, String videoUrl) {
            // 暂停之前的播放器
            if (currentPlayer != null && currentPlayingPosition != position) {
                currentPlayer.pause();
                // 隐藏之前的视频
                // 这里可以通过notifyItemChanged来更新UI，或者让监听器处理
            }

            // 获取或创建播放器
            ExoPlayer player = obtainPlayerForPosition(position, videoUrl);

            // 清理旧的监听器，添加新的
            player.removeListener(viewHolderPlayerListener);
            player.addListener(viewHolderPlayerListener);

            // 准备媒体
            prepareMediaIfNeeded(player, position, videoUrl);

            // 绑定到视图
            attachPlayerToView(player, position);

            // 开始播放
            player.setPlayWhenReady(true);
            player.play();

            // 更新全局状态
            currentPlayer = player;
            currentPlayingPosition = position;

            // 立即更新UI状态：显示视频，隐藏封面和按钮
            playerView.setVisibility(View.VISIBLE);
            playButton.setVisibility(View.GONE);
            videoCover.setVisibility(View.GONE);
        }


        private void attachPlayerToView(ExoPlayer player, int position) {
            // 移除旧的播放器
            Player oldPlayer = playerView.getPlayer();
            if (oldPlayer != null && oldPlayer != player) {
                playerView.setPlayer(null);
            }

            // 绑定新播放器
            playerView.setPlayer(player);

            // 重要：初始状态先隐藏视频，等待播放时才显示
            playerView.setVisibility(View.GONE);
            videoCover.setVisibility(View.VISIBLE);

            // 根据播放状态更新UI
            if (player.isPlaying()) {
                playerView.setVisibility(View.VISIBLE);
                videoCover.setVisibility(View.GONE);
                playButton.setVisibility(View.GONE);
                loadingProgress.setVisibility(
                        player.getPlaybackState() == Player.STATE_BUFFERING ?
                                View.VISIBLE : View.GONE
                );
            } else {
                playerView.setVisibility(View.GONE);
                playButton.setVisibility(View.VISIBLE);
                videoCover.setVisibility(View.VISIBLE);
                loadingProgress.setVisibility(View.GONE);
            }

            // 确保监听器已添加
            player.removeListener(viewHolderPlayerListener);
            player.addListener(viewHolderPlayerListener);
        }

        private String getVideoCoverUrl(WorkItem.Clip clip) {
            return clip.getUrl();
        }

        public void releasePlayer() {
            if (playerView != null) {
                playerView.setPlayer(null);
            }
        }

        // 添加缺失的方法
        public void releaseViewHolder() {
            releasePlayer();
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);

        if (holder instanceof VideoViewHolder) {
            VideoViewHolder videoHolder = (VideoViewHolder) holder;

            // 如果不是当前播放的视频，释放播放器视图
            if (videoHolder.viewHolderPosition != currentPlayingPosition) {
                videoHolder.releasePlayer();
            }

            // 调用新增的方法
            videoHolder.releaseViewHolder();
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        releaseAllPlayers();
    }
}