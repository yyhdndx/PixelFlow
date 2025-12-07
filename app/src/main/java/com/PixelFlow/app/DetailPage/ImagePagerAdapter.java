package com.PixelFlow.app.DetailPage;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
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
    // 添加缺失的常量
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
     * 预加载视频
     */
    private void preloadVideo(int position, String videoUrl) {
        if (Boolean.TRUE.equals(videoPreloaded.get(position))) {
            return;
        }

        ExoPlayer player = obtainPlayerForPosition(position, videoUrl);
        if (player == null) {
            return;
        }

        // 准备媒体但不播放
        prepareMediaIfNeeded(player, position, videoUrl);
        player.setPlayWhenReady(false);

        videoPreloaded.put(position, true);
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
     * 创建轻量级播放器
     */
    private ExoPlayer createLightweightPlayer() {
        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        1000,    // 最小缓冲时间
                        15000,   // 最大缓冲时间
                        500,     // 播放开始缓冲时间
                        1000     // 恢复播放缓冲时间
                )
                .setTargetBufferBytes(-1)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        return new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build();
    }

    /**
     * 清理超出预加载范围的视频
     */
    private void cleanupPreloadedVideos(int start, int end) {
        for (int position : playerPool.keySet().toArray(new Integer[0])) {
            if (position < start || position > end) {
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

    /**
     * 准备媒体（如果需要）
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
        this.currentPagePosition = newPosition;

        if (newPosition != currentPlayingPosition) {
            pauseCurrentVideo();
        }

        preloadAdjacentVideos(newPosition);
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

    /**
     * 获取当前列表中心位置（用于预加载判断）
     */
    private int getCurrentListCenter() {
        // 简单实现：返回当前页面位置
        return currentPagePosition;
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
                    .into(imageView);

            progressBar.setVisibility(View.GONE);
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
                // 确保这个回调在正确的线程
                itemView.post(() -> {
                    if (playerView.getPlayer() == null) return;

                    switch (playbackState) {
                        case Player.STATE_BUFFERING:
                            // 关键修复：只有在尝试播放时才显示加载图标
                            ExoPlayer p = (ExoPlayer) playerView.getPlayer();
                            if (p != null && p.isPlaying()) {
                                loadingProgress.setVisibility(View.VISIBLE);
                            }
                            break;

                        case Player.STATE_READY:
                            // 关键修复：播放器就绪时，根据是否正在播放来更新UI
                            ExoPlayer player = (ExoPlayer) playerView.getPlayer();
                            if (player != null) {
                                loadingProgress.setVisibility(View.GONE);

                                // 更新播放按钮和封面
                                if (player.isPlaying()) {
                                    playButton.setVisibility(View.GONE);
                                    videoCover.setVisibility(View.GONE);
                                } else {
                                    playButton.setVisibility(View.VISIBLE);
                                    videoCover.setVisibility(View.VISIBLE);
                                }
                            }
                            break;

                        case Player.STATE_ENDED:
                            loadingProgress.setVisibility(View.GONE);
                            playButton.setVisibility(View.VISIBLE);
                            videoCover.setVisibility(View.VISIBLE);
                            break;
                    }
                });
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                itemView.post(() -> {
                    if (isPlaying) {
                        // 开始播放时隐藏加载图标
                        loadingProgress.setVisibility(View.GONE);
                        videoCover.setVisibility(View.GONE);
                        playButton.setVisibility(View.GONE);
                    } else {
                        // 暂停时显示播放按钮
                        playButton.setVisibility(View.VISIBLE);
                        videoCover.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                itemView.post(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    playButton.setVisibility(View.VISIBLE);
                    videoCover.setVisibility(View.VISIBLE);
                    playerView.setVisibility(View.GONE);
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
            playerView.setUseController(true);
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

            // 检查是否有预加载的播放器
            ExoPlayer preloadedPlayer = playerPool.get(position);
            if (preloadedPlayer != null && currentPlayingPosition == position) {
                // 如果这个位置是当前正在播放的，恢复状态
                attachPlayerToView(preloadedPlayer, position);
            } else {
                // 否则显示封面和播放按钮
                playButton.setVisibility(View.VISIBLE);
                videoCover.setVisibility(View.VISIBLE);
                loadingProgress.setVisibility(View.GONE);
            }

            setupClickListeners(position, clip.getUrl());

            // 预加载邻近视频
            if (Math.abs(position - getCurrentListCenter()) <= PRELOAD_WINDOW) {
                preloadVideo(position, clip.getUrl());
            }
        }

        private void resetViewState() {
            playerView.setVisibility(View.GONE);
            videoCover.setVisibility(View.VISIBLE);
            playButton.setVisibility(View.VISIBLE);
            loadingProgress.setVisibility(View.GONE);
        }

        private void setupClickListeners(int position, String videoUrl) {
            // 清除旧的监听器避免重复绑定
            playButton.setOnClickListener(null);
            videoContainer.setOnClickListener(null);

            playButton.setOnClickListener(v -> {
                onPlayClicked(position, videoUrl);
            });

            videoContainer.setOnClickListener(v -> {
                onPlayClicked(position, videoUrl);
            });
        }

        private void onPlayClicked(int position, String videoUrl) {
            // 切换播放/暂停或播放新视频
            if (currentPlayingPosition == position && currentPlayer != null) {
                if (currentPlayer.isPlaying()) {
                    currentPlayer.pause();
                    playButton.setVisibility(View.VISIBLE);
                    videoCover.setVisibility(View.VISIBLE);
                } else {
                    currentPlayer.play();
                    playButton.setVisibility(View.GONE);
                    videoCover.setVisibility(View.GONE);
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
            }

            // 获取或创建播放器
            ExoPlayer player = obtainPlayerForPosition(position, videoUrl);
            if (player == null) {
                return;
            }

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

            // 立即更新UI状态
            playButton.setVisibility(View.GONE);
            videoCover.setVisibility(View.GONE);
            loadingProgress.setVisibility(View.VISIBLE); // 先显示加载，等READY状态会隐藏
        }

        private void attachPlayerToView(ExoPlayer player, int position) {
            // 移除旧的播放器
            Player oldPlayer = playerView.getPlayer();
            if (oldPlayer != null && oldPlayer != player) {
                playerView.setPlayer(null);
            }

            // 绑定新播放器
            playerView.setPlayer(player);
            playerView.setVisibility(View.VISIBLE);

            // 根据播放状态更新UI
            if (player.isPlaying()) {
                playButton.setVisibility(View.GONE);
                videoCover.setVisibility(View.GONE);
                loadingProgress.setVisibility(
                        player.getPlaybackState() == Player.STATE_BUFFERING ?
                                View.VISIBLE : View.GONE
                );
            } else {
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