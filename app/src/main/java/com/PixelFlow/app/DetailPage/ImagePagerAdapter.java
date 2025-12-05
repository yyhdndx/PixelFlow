package com.PixelFlow.app.DetailPage;

import android.content.Context;
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
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.List;

public class ImagePagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // 视图类型常量
    private static final int VIEW_TYPE_IMAGE = 0;
    private static final int VIEW_TYPE_VIDEO = 1;

    private final Context context;
    private final List<WorkItem.Clip> mediaItems;
    private final LayoutInflater inflater;

    // 播放器实例，用于视频播放
    private SimpleExoPlayer currentPlayer;
    private int currentPlayingPosition = -1;

    // 监听器接口，用于通知外部播放状态变化
    public interface MediaPlaybackListener {
        void onVideoStarted(int position);
        void onVideoPaused(int position);
        void onVideoCompleted(int position);
    }

    private MediaPlaybackListener playbackListener;

    public ImagePagerAdapter(Context context, List<WorkItem.Clip> mediaItems) {
        this.context = context;
        this.mediaItems = mediaItems;
        this.inflater = LayoutInflater.from(context);
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
    }

    @Override
    public int getItemCount() {
        return mediaItems != null ? mediaItems.size() : 0;
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
     * 释放播放器资源
     */
    public void releasePlayer() {
        if (currentPlayer != null) {
            currentPlayer.release();
            currentPlayer = null;
            currentPlayingPosition = -1;
        }
    }

    /**
     * 获取当前正在播放的位置
     */
    public int getCurrentPlayingPosition() {
        return currentPlayingPosition;
    }

    /**
     * 在页面切换时调用，用于管理视频播放状态
     */
    public void onPageChanged(int newPosition) {
        if (newPosition != currentPlayingPosition) {
            pauseCurrentVideo();
        }
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

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            playerView = itemView.findViewById(R.id.video_player_view);
            videoCover = itemView.findViewById(R.id.video_cover);
            playButton = itemView.findViewById(R.id.video_play_button);
            loadingProgress = itemView.findViewById(R.id.video_loading);
            videoContainer = itemView.findViewById(R.id.video_container);

            // 初始化播放器控件
            setupPlayerView();
        }

        void bind(WorkItem.Clip clip, int position) {
            // 重置视图状态
            playerView.setVisibility(View.GONE);
            videoCover.setVisibility(View.VISIBLE);
            playButton.setVisibility(View.VISIBLE);
            loadingProgress.setVisibility(View.GONE);

            // 加载视频封面（使用Glide从视频URL生成第一帧）
            Glide.with(context)
                    .load(clip.getUrl())
                    .placeholder(R.drawable.ic_video_placeholder)
                    .error(R.drawable.ic_image_error)
                    .into(videoCover);

            // 设置播放按钮点击事件
            playButton.setOnClickListener(v -> {
                if (currentPlayingPosition == position && currentPlayer != null) {
                    // 如果当前正在播放这个视频，则暂停
                    currentPlayer.pause();
                    playButton.setVisibility(View.VISIBLE);
                    videoCover.setVisibility(View.VISIBLE);
                    playerView.setVisibility(View.GONE);

                    if (playbackListener != null) {
                        playbackListener.onVideoPaused(position);
                    }
                } else {
                    // 播放新视频
                    playVideo(clip.getUrl(), position);
                }
            });

            // 视频容器点击事件（点击视频区域也可以播放/暂停）
            videoContainer.setOnClickListener(v -> {
                if (currentPlayingPosition == position && currentPlayer != null) {
                    if (currentPlayer.isPlaying()) {
                        currentPlayer.pause();
                        playButton.setVisibility(View.VISIBLE);
                    } else {
                        currentPlayer.play();
                        playButton.setVisibility(View.GONE);
                    }
                } else {
                    playVideo(clip.getUrl(), position);
                }
            });

            // 如果这个位置是当前正在播放的视频，恢复播放状态
            if (currentPlayingPosition == position && currentPlayer != null) {
                playerView.setVisibility(View.VISIBLE);
                videoCover.setVisibility(View.GONE);
                playButton.setVisibility(currentPlayer.isPlaying() ? View.GONE : View.VISIBLE);
                playerView.setPlayer(currentPlayer);
            }
        }

        private void setupPlayerView() {
            // 配置播放器视图
            playerView.setUseController(true);
            playerView.setControllerAutoShow(false);
            playerView.setControllerHideOnTouch(true);
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        }

        private void playVideo(String videoUrl, int position) {
            // 暂停之前正在播放的视频
            if (currentPlayer != null && currentPlayingPosition != position) {
                currentPlayer.pause();
                currentPlayer.release();
                currentPlayer = null;
            }

            // 创建新的播放器
            if (currentPlayer == null) {
                currentPlayer = new SimpleExoPlayer.Builder(context).build();

                // 设置播放器监听器
                currentPlayer.addListener(new Player.EventListener() {
                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        switch (playbackState) {
                            case Player.STATE_READY:
                                loadingProgress.setVisibility(View.GONE);
                                if (playbackListener != null) {
                                    playbackListener.onVideoStarted(position);
                                }
                                break;
                            case Player.STATE_BUFFERING:
                                loadingProgress.setVisibility(View.VISIBLE);
                                break;
                            case Player.STATE_ENDED:
                                // 播放完成，显示封面和播放按钮
                                playerView.setVisibility(View.GONE);
                                videoCover.setVisibility(View.VISIBLE);
                                playButton.setVisibility(View.VISIBLE);

                                if (playbackListener != null) {
                                    playbackListener.onVideoCompleted(position);
                                }
                                break;
                        }
                    }
                });
            }

            // 设置播放源
            MediaItem mediaItem = MediaItem.fromUri(videoUrl);
            currentPlayer.setMediaItem(mediaItem);
            currentPlayer.prepare();
            currentPlayer.play();

            // 更新UI
            playerView.setPlayer(currentPlayer);
            playerView.setVisibility(View.VISIBLE);
            videoCover.setVisibility(View.GONE);
            playButton.setVisibility(View.GONE);
            loadingProgress.setVisibility(View.VISIBLE);

            // 更新当前播放位置
            currentPlayingPosition = position;
        }

        /**
         * 释放当前ViewHolder的播放器资源
         */
        void releasePlayer() {
            if (playerView != null) {
                playerView.setPlayer(null);
            }
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);

        // 当ViewHolder被回收时，如果是VideoViewHolder，释放其播放器资源
        if (holder instanceof VideoViewHolder) {
            ((VideoViewHolder) holder).releasePlayer();
        }
    }
}