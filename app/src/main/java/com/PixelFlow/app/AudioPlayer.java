package com.PixelFlow.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;

public class AudioPlayer {
    private static AudioPlayer instance;
    private Context context;
    private ExoPlayer exoPlayer;
    private WorkItem.Music currentMusic;
    private boolean isPlaying = false;
    private boolean isMuted = false;
    private float originalVolume = 1.0f;

    private static final String TAG = "AudioPlayer";

    // 私有构造函数
    private AudioPlayer(Context context) {
        this.context = context.getApplicationContext();
        initExoPlayer();
    }

    // 获取单例实例
    public static synchronized AudioPlayer getInstance(Context context) {
        if (instance == null) {
            instance = new AudioPlayer(context);
        }
        return instance;
    }

    // 获取实例（不创建新实例）
    public static AudioPlayer getInstance() {
        return instance;
    }

    /** 页面独占播放器；页面销毁不会释放其他页面持有的实例。 */
    public static AudioPlayer createForScreen(Context context) {
        return new AudioPlayer(context);
    }

    public void pauseMusic() {
        if (exoPlayer != null) exoPlayer.pause();
        isPlaying = false;
        notifyPlaybackStateChanged(false);
    }

    private void initExoPlayer() {
        Log.d(TAG, "初始化ExoPlayer");

        try {
            // 配置音频属性
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .build();

            // 构建ExoPlayer实例
            exoPlayer = new ExoPlayer.Builder(context)
                    .setAudioAttributes(audioAttributes, true) // 处理音频焦点
                    .setHandleAudioBecomingNoisy(true) // 耳机拔出时暂停
                    .build();

            // 设置监听器
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Log.d(TAG, "播放状态: " + playbackState);
                    switch (playbackState) {
                        case Player.STATE_IDLE:
                            Log.d(TAG, "状态: 空闲");
                            break;
                        case Player.STATE_BUFFERING:
                            Log.d(TAG, "状态: 缓冲中");
                            break;
                        case Player.STATE_READY:
                            Log.d(TAG, "状态: 准备就绪");
                            break;
                        case Player.STATE_ENDED:
                            Log.d(TAG, "状态: 播放结束");
                            isPlaying = false;
                            notifyPlaybackStateChanged(false);
                            break;
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    Log.e(TAG, "播放错误: " + error.getMessage());
                    Log.e(TAG, "错误类型: " + error.errorCode);
                    isPlaying = false;
                    notifyPlaybackStateChanged(false);
                }

                @Override
                public void onIsPlayingChanged(boolean playing) {
                    Log.d(TAG, "播放状态变化: " + playing);
                    isPlaying = playing;
                    notifyPlaybackStateChanged(playing);
                }
            });

            Log.d(TAG, "ExoPlayer初始化成功");

        } catch (Exception e) {
            Log.e(TAG, "ExoPlayer初始化失败: " + e.getMessage(), e);
        }
    }

    // 播放音乐
    public void playMusic(WorkItem.Music music) {
        if (music == null) {
            Log.e(TAG, "音乐为空");
            return;
        }

        if (!music.isValid()) {
            Log.e(TAG, "音乐无效: " + music.getUrl());
            return;
        }

        Log.d(TAG, "尝试播放音乐: " + music.getUrl() + ", URL: " + music.getUrl());

        try {
            // 如果已经在播放相同的音乐，则切换播放状态
            if (currentMusic != null && currentMusic.getUrl() != null &&
                    currentMusic.getUrl().equals(music.getUrl())) {
                togglePlayPause();
                return;
            }

            // 清理当前播放
            if (exoPlayer != null) {
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
            }

            currentMusic = music;
            MainActivity.setCurrentPlayingMusic(music);
            MainActivity.registerAudioPlayer(this);

            // 创建媒体项
            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(music.getUrl()));
            exoPlayer.setMediaItem(mediaItem);

            // 准备播放
            exoPlayer.prepare();

            // 设置音量
            float volume = music.getVolumeFloat();
            originalVolume = volume;

            // 根据全局静音状态设置音量
            if (MainActivity.isGlobalMuted()) {
                exoPlayer.setVolume(0f);
                isMuted = true;
            } else {
                exoPlayer.setVolume(volume);
                isMuted = false;
            }

            // 设置播放位置
            exoPlayer.seekTo(music.getSeekTime());

            // 开始播放
            exoPlayer.setPlayWhenReady(true);
            isPlaying = true;


        } catch (Exception e) {
            Log.e(TAG, "播放音乐失败: " + e.getMessage(), e);
            isPlaying = false;
            notifyPlaybackStateChanged(false);
        }
    }

    // 切换播放/暂停状态
    public void togglePlayPause() {
        if (exoPlayer == null) {
            Log.e(TAG, "ExoPlayer为空");
            return;
        }

        if (exoPlayer.getPlayWhenReady()) {
            // 暂停播放
            exoPlayer.pause();
            isPlaying = false;
            Log.d(TAG, "音乐暂停");
        } else {
            // 恢复播放
            exoPlayer.setPlayWhenReady(true);
            isPlaying = true;
            Log.d(TAG, "音乐恢复播放");
        }

        notifyPlaybackStateChanged(isPlaying);
    }

    // 停止播放
    public void stopMusic() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            isPlaying = false;
            currentMusic = null;
            if (MainActivity.getCurrentAudioPlayer() == this) {
                MainActivity.clearCurrentPlayingMusic();
                MainActivity.unregisterAudioPlayer();
            }
            notifyPlaybackStateChanged(false);
            Log.d(TAG, "音乐停止");
        }
    }

    // 更新静音状态
    public void updateMuteState(boolean muted) {
        isMuted = muted;
        if (exoPlayer != null) {
            if (muted) {
                exoPlayer.setVolume(0f);
            } else {
                if (currentMusic != null) {
                    exoPlayer.setVolume(currentMusic.getVolumeFloat());
                } else {
                    exoPlayer.setVolume(originalVolume);
                }
            }
        }
    }

    // 切换静音状态
    public void toggleMute() {
        boolean newMutedState = !isMuted;
        updateMuteState(newMutedState);
        MainActivity.setGlobalMuted(newMutedState);
        Log.d(TAG, "静音状态切换: " + newMutedState);
    }

    // 获取当前播放位置（毫秒）
    public long getCurrentPosition() {
        if (exoPlayer != null) {
            return exoPlayer.getCurrentPosition();
        }
        return 0;
    }

    // 获取音乐总时长（毫秒）
    public long getDuration() {
        if (exoPlayer != null) {
            return exoPlayer.getDuration();
        }
        return 0;
    }

    // 跳转到指定位置
    public void seekTo(long positionMs) {
        if (exoPlayer != null) {
            exoPlayer.seekTo(positionMs);
        }
    }

    // 检查是否正在播放
    public boolean isPlaying() {
        return isPlaying && exoPlayer != null && exoPlayer.isPlaying();
    }

    // 获取当前播放的音乐
    public WorkItem.Music getCurrentMusic() {
        return currentMusic;
    }

    // 检查是否正在播放指定音乐
    public boolean isPlayingMusic(WorkItem.Music music) {
        return isPlaying && currentMusic != null && currentMusic.equals(music);
    }

    // 设置音量（0-100）
    public void setVolume(int volume) {
        if (exoPlayer != null) {
            float volumeFloat = Math.max(0, Math.min(100, volume)) / 100.0f;
            exoPlayer.setVolume(isMuted ? 0f : volumeFloat);
            if (currentMusic != null) {
                currentMusic.setVolume(volume);
            }
            originalVolume = volumeFloat;
        }
    }

    // 释放资源
    public void release() {
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        isPlaying = false;
        currentMusic = null;
        if (MainActivity.getCurrentAudioPlayer() == this) {
            MainActivity.clearCurrentPlayingMusic();
            MainActivity.unregisterAudioPlayer();
        }
        if (instance == this) instance = null;
        Log.d(TAG, "AudioPlayer释放");
    }

    // 通知播放状态变化
    private void notifyPlaybackStateChanged(boolean playing) {
        // 这里可以添加通知UI更新状态的逻辑
        // 例如通过接口回调或LiveData
    }
}
