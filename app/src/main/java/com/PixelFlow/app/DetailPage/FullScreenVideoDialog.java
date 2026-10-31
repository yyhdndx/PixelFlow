package com.PixelFlow.app.DetailPage;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import com.PixelFlow.app.R;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;

/** Moves the existing player between surfaces; the adapter retains playback/lifecycle ownership. */
public final class FullScreenVideoDialog extends Dialog {
    private final ExoPlayer player;
    private final PlayerView inlineView;
    private final Runnable retryPlayback;
    private PlayerView fullscreenView;
    private View loading, retry;
    private ImageView replay;
    private final Player.Listener listener = new Player.Listener() {
        @Override public void onEvents(Player source, Player.Events events) { render(); }
    };

    public FullScreenVideoDialog(@NonNull Context context, ExoPlayer player,
            PlayerView inlineView, Runnable retryPlayback) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.player = player;
        this.inlineView = inlineView;
        this.retryPlayback = retryPlayback;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.dialog_fullscreen_video);
        fullscreenView = findViewById(R.id.fullscreen_video_player);
        loading = findViewById(R.id.video_loading);
        retry = findViewById(R.id.video_retry);
        replay = findViewById(R.id.video_play_button);
        findViewById(R.id.fullscreen_video_touch).setOnClickListener(v -> dismiss());
        retry.setOnClickListener(v -> retryPlayback.run());
        replay.setOnClickListener(v -> retryPlayback.run());
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        player.addListener(listener);
        PlayerView.switchTargetView(player, inlineView, fullscreenView);
        fullscreenView.hideController();
        render();
    }

    private void render() {
        if (fullscreenView == null) return;
        boolean failed = player.getPlayerError() != null;
        boolean ended = player.getPlaybackState() == Player.STATE_ENDED;
        loading.setVisibility(!failed && player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        retry.setVisibility(failed ? View.VISIBLE : View.GONE);
        replay.setVisibility(!failed && ended ? View.VISIBLE : View.GONE);
        fullscreenView.setKeepScreenOn(player.isPlaying());
    }

    @Override protected void onStop() {
        player.removeListener(listener);
        if (fullscreenView != null) {
            fullscreenView.setKeepScreenOn(false);
            PlayerView.switchTargetView(player, fullscreenView, inlineView);
        }
        super.onStop();
    }
}
