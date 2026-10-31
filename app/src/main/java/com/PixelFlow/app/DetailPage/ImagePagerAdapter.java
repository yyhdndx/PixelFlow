package com.PixelFlow.app.DetailPage;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.MainActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.WorkItem;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ui.PlayerView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Only the selected page owns a player. Preparing an offscreen page cannot start audio. */
public class ImagePagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    /** Reports whether the currently shown video clip is actively playing. */
    public interface VideoPlaybackListener {
        void onVideoPlaybackChanged(boolean playing);
    }

    public interface ImageClickListener {
        void onImageClick(String url);
    }

    private final Context context;
    private final List<WorkItem.Clip> mediaItems;
    private final Map<Integer, Long> positions = new HashMap<>();
    private final Map<Integer, VideoViewHolder> holders = new HashMap<>();
    private ExoPlayer currentPlayer;
    private int currentPagePosition = -1;
    private boolean hostResumed;
    private boolean playRequested = true;
    private VideoPlaybackListener playbackListener;
    private ImageClickListener imageClickListener;
    private FullScreenVideoDialog fullscreenDialog;
    private boolean restoreFullscreen;

    public ImagePagerAdapter(Context context, List<WorkItem.Clip> mediaItems) {
        this.context = context;
        this.mediaItems = mediaItems;
    }

    public void setVideoPlaybackListener(VideoPlaybackListener listener) {
        this.playbackListener = listener;
    }

    public void setImageClickListener(ImageClickListener listener) {
        imageClickListener = listener;
    }

    public void restorePlayback(Bundle state) {
        if (state == null) return;
        currentPagePosition = state.getInt("page", -1);
        positions.put(currentPagePosition, state.getLong("position", 0));
        playRequested = state.getBoolean("playing", true);
        restoreFullscreen = state.getBoolean("fullscreen", false);
    }

    public Bundle savePlayback() {
        Bundle state = new Bundle();
        state.putInt("page", currentPagePosition);
        state.putLong("position", currentPlayer == null
                ? positions.getOrDefault(currentPagePosition, 0L) : currentPlayer.getCurrentPosition());
        state.putBoolean("playing", playRequested);
        state.putBoolean("fullscreen", fullscreenDialog != null || restoreFullscreen);
        return state;
    }

    @Override public int getItemCount() { return mediaItems.size(); }
    @Override public int getItemViewType(int position) { return mediaItems.get(position).getType() == 1 ? 1 : 0; }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return type == 1 ? new VideoViewHolder(inflater.inflate(R.layout.item_detail_video, parent, false))
                : new ImageViewHolder(inflater.inflate(R.layout.item_detail_image, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ImageViewHolder) ((ImageViewHolder) holder).bind(mediaItems.get(position));
        else ((VideoViewHolder) holder).bind(position);
    }

    public void onPageChanged(int position) {
        if (position < 0 || position >= getItemCount()) return;
        if (position != currentPagePosition) {
            releasePlayer();
            currentPagePosition = position;
            playRequested = true;
        }
        ensurePlayer();
        renderVideos();
    }

    public void resumeCurrentVideo() {
        hostResumed = true;
        ensurePlayer();
        if (currentPlayer != null) {
            currentPlayer.setVolume(MainActivity.isGlobalMuted() ? 0f : 1f);
            currentPlayer.setPlayWhenReady(playRequested);
        }
        renderVideos();
    }

    public void pauseCurrentVideo() {
        // Clear foreground permission even while buffering; no READY callback may restart playback.
        hostResumed = false;
        if (currentPlayer != null) currentPlayer.pause();
        renderVideos();
    }

    private void ensurePlayer() {
        if (!hostResumed || currentPlayer != null || currentPagePosition < 0
                || currentPagePosition >= getItemCount() || getItemViewType(currentPagePosition) != 1) return;
        ExoPlayer player = new ExoPlayer.Builder(context).build();
        currentPlayer = player;
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MOVIE).build(), true);
        player.setVolume(MainActivity.isGlobalMuted() ? 0f : 1f);
        player.addListener(new Player.Listener() {
            @Override public void onEvents(Player source, Player.Events events) {
                if (currentPlayer == source) renderVideos();
            }
            @Override public void onPlayWhenReadyChanged(boolean value, int reason) {
                if (currentPlayer == player && hostResumed) playRequested = value;
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (currentPlayer == player && playbackListener != null) {
                    playbackListener.onVideoPlaybackChanged(isPlaying);
                }
            }
        });
        player.setMediaItem(MediaItem.fromUri(mediaItems.get(currentPagePosition).getUrl()));
        player.seekTo(positions.getOrDefault(currentPagePosition, 0L));
        player.prepare();
        player.setPlayWhenReady(playRequested);
    }

    private void playOrRetry(int position) {
        if (!hostResumed || position != currentPagePosition) return;
        ensurePlayer();
        if (currentPlayer == null) return;
        playRequested = true;
        if (currentPlayer.getPlayerError() != null || currentPlayer.getPlaybackState() == Player.STATE_IDLE)
            currentPlayer.prepare();
        if (currentPlayer.getPlaybackState() == Player.STATE_ENDED) currentPlayer.seekTo(0);
        currentPlayer.play();
        renderVideos();
    }

    private void renderVideos() {
        for (VideoViewHolder holder : holders.values()) holder.render();
        if (restoreFullscreen && hostResumed && holders.containsKey(currentPagePosition)) {
            restoreFullscreen = false;
            openFullscreen(currentPagePosition);
        }
    }

    private void openFullscreen(int position) {
        if (!hostResumed || position != currentPagePosition || fullscreenDialog != null) return;
        ensurePlayer();
        VideoViewHolder holder = holders.get(position);
        if (currentPlayer == null || holder == null) return;
        FullScreenVideoDialog dialog = new FullScreenVideoDialog(context, currentPlayer,
                holder.playerView, () -> playOrRetry(position));
        fullscreenDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            // An old Dialog can deliver its dismissal after a new one was opened.
            if (fullscreenDialog == dialog) {
                fullscreenDialog = null;
                renderVideos();
            }
        });
        dialog.show();
    }

    private void dismissFullscreen() {
        if (fullscreenDialog != null) {
            FullScreenVideoDialog old = fullscreenDialog;
            fullscreenDialog = null;
            old.dismiss();
        }
    }

    private void releasePlayer() {
        dismissFullscreen();
        ExoPlayer old = currentPlayer;
        if (old == null) return;
        positions.put(currentPagePosition, old.getCurrentPosition());
        currentPlayer = null;
        for (VideoViewHolder holder : holders.values()) holder.playerView.setPlayer(null);
        old.release();
        if (playbackListener != null) playbackListener.onVideoPlaybackChanged(false);
    }

    public void releaseAllPlayers() {
        hostResumed = false;
        releasePlayer();
    }

    class ImageViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final ProgressBar progressBar;
        ImageViewHolder(View view) {
            super(view);
            imageView = view.findViewById(R.id.detail_image);
            progressBar = view.findViewById(R.id.image_loading);
        }
        void bind(WorkItem.Clip clip) {
            imageView.setOnClickListener(v -> {
                if (imageClickListener != null) imageClickListener.onImageClick(clip.getUrl());
            });
            progressBar.setVisibility(View.VISIBLE);
            Glide.with(itemView).load(clip.getUrl()).placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_error).dontTransform()
                    .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                        @Override public boolean onLoadFailed(@Nullable GlideException error, Object model,
                                Target<android.graphics.drawable.Drawable> target, boolean first) {
                            progressBar.setVisibility(View.GONE);
                            return false;
                        }
                        @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                Object model, Target<android.graphics.drawable.Drawable> target,
                                DataSource source, boolean first) {
                            progressBar.setVisibility(View.GONE);
                            return false;
                        }
                    }).into(imageView);
        }
    }

    class VideoViewHolder extends RecyclerView.ViewHolder {
        final PlayerView playerView;
        final ImageView cover, playButton;
        final View retry;
        final ProgressBar loading;
        final View fullscreenButton;
        float touchX, touchY;
        boolean tapPossible;
        int position = -1;
        VideoViewHolder(View view) {
            super(view);
            playerView = view.findViewById(R.id.video_player_view);
            cover = view.findViewById(R.id.video_cover);
            playButton = view.findViewById(R.id.video_play_button);
            loading = view.findViewById(R.id.video_loading);
            retry = view.findViewById(R.id.video_retry);
            fullscreenButton = view.findViewById(R.id.video_fullscreen_button);
            playerView.setUseController(true);
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
            playerView.setControllerAutoShow(true);
            playButton.setOnClickListener(v -> playOrRetry(position));
            retry.setOnClickListener(v -> playOrRetry(position));
            fullscreenButton.setOnClickListener(v -> openFullscreen(position));
            cover.setOnClickListener(v -> openFullscreen(position));
            playerView.setOnClickListener(v -> openFullscreen(position));
            int slop = ViewConfiguration.get(context).getScaledTouchSlop();
            // PlayerView 2.16 treats every DOWN/UP as a click. A drag must not open a dialog.
            playerView.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touchX = event.getX(); touchY = event.getY(); tapPossible = true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    if (Math.abs(event.getX() - touchX) > slop || Math.abs(event.getY() - touchY) > slop)
                        tapPossible = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                        || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                    tapPossible = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (tapPossible) v.performClick();
                    tapPossible = false;
                    return true;
                }
                return false;
            });
        }
        void bind(int nextPosition) {
            detach();
            position = nextPosition;
            holders.put(position, this);
            // The service puts a poster before its video. Do not download an MP4 just to show its cover.
            String poster = position > 0 && mediaItems.get(position - 1).getType() == 0
                    ? mediaItems.get(position - 1).getUrl() : null;
            Glide.with(itemView).load(poster).placeholder(R.drawable.ic_video_placeholder)
                    .error(R.drawable.ic_video_placeholder).into(cover);
            render();
            if (restoreFullscreen) renderVideos();
        }
        void render() {
            ExoPlayer player = position == currentPagePosition ? currentPlayer : null;
            ExoPlayer inlinePlayer = fullscreenDialog == null ? player : null;
            if (playerView.getPlayer() != inlinePlayer) playerView.setPlayer(inlinePlayer);
            boolean failed = player != null && player.getPlayerError() != null;
            boolean ended = player != null && player.getPlaybackState() == Player.STATE_ENDED;
            playerView.setVisibility(player != null && !failed ? View.VISIBLE : View.GONE);
            cover.setVisibility(player == null || failed ? View.VISIBLE : View.GONE);
            retry.setVisibility(failed ? View.VISIBLE : View.GONE);
            playButton.setVisibility(!failed && (player == null || ended) ? View.VISIBLE : View.GONE);
            playButton.setImageResource(ended ? R.drawable.ic_replay : R.drawable.ic_play_circle);
            playButton.setContentDescription(context.getString(ended ? R.string.video_replay : R.string.play_video));
            loading.setVisibility(player != null && !failed && hostResumed
                    && player.getPlaybackState() == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            itemView.setKeepScreenOn(player != null && hostResumed && player.isPlaying());
        }
        void detach() {
            if (holders.get(position) == this) holders.remove(position);
            playerView.setPlayer(null);
            itemView.setKeepScreenOn(false);
        }
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof VideoViewHolder) ((VideoViewHolder) holder).detach();
        super.onViewRecycled(holder);
    }

    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView view) {
        releaseAllPlayers();
        holders.clear();
        super.onDetachedFromRecyclerView(view);
    }
}
