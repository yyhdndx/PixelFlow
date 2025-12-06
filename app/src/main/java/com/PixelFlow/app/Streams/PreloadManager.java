package com.PixelFlow.app.Streams;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 图片和视频封面预加载管理器
 */
public class PreloadManager {
    private static final String TAG = "PreloadManager";

    // 预加载优先级
    public static final int PRIORITY_LOW = 1;      // 低优先级：即时预加载
    public static final int PRIORITY_MEDIUM = 2;   // 中优先级：可视区预加载
    public static final int PRIORITY_HIGH = 3;     // 高优先级：详情页预加载

    private final Context context;
    private final Handler mainHandler;
    private final Set<String> preloadedUrls; // 防止重复预加载

    // 屏幕尺寸和卡片宽度缓存
    private int cardWidth = -1;
    private int cardHeight = -1;

    public PreloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.preloadedUrls = new HashSet<>();
    }

    /**
     * 批量预加载工作项
     */
    public void preloadWorkItems(List<WorkItem> workItems, int priority) {
        if (workItems == null || workItems.isEmpty()) return;

        // 使用后台线程进行预加载
        new Thread(() -> {
            for (WorkItem item : workItems) {
                if (item != null) {
                    preloadWorkItem(item, priority);
                }
            }
        }).start();
    }

    /**
     * 预加载单个工作项
     */
    private void preloadWorkItem(WorkItem workItem, int priority) {
        try {
            if (workItem.getFirstImageUrl()==null) {
                // 预加载视频封面
                preloadVideoCover(workItem, priority);
            } else {
                // 预加载图片
                preloadImage(workItem, priority);
            }
        } catch (Exception e) {
            Log.e(TAG, "预加载失败: " + e.getMessage());
        }
    }

    /**
     * 预加载图片
     */
    private void preloadImage(WorkItem workItem, int priority) {
        String imageUrl = workItem.getFirstImageUrl();
        if (imageUrl == null || imageUrl.isEmpty()) {
            imageUrl = workItem.getCoverUrl();
        }

        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }

        // 检查是否已预加载过
        String cacheKey = imageUrl + "_" + priority;
        if (preloadedUrls.contains(cacheKey)) {
            return;
        }
        preloadedUrls.add(cacheKey);

        int cardWidth = getCardWidth();
        int targetWidth, targetHeight;

        switch (priority) {
            case PRIORITY_LOW:
                // 低质量：1/8尺寸，最小60px
                targetWidth = Math.max(60, cardWidth / 8);
                targetHeight = workItem.getDisplayHeight(targetWidth);
                break;

            case PRIORITY_MEDIUM:
                // 中等质量：1/2尺寸
                targetWidth = Math.max(120, cardWidth / 2);
                targetHeight = workItem.getDisplayHeight(targetWidth);
                break;

            case PRIORITY_HIGH:
                // 高质量：原尺寸
                targetWidth = cardWidth;
                targetHeight = workItem.getDisplayHeight(targetWidth);
                break;

            default:
                targetWidth = 120;
                targetHeight = 160;
        }

        // 在主线程执行Glide预加载
        String finalImageUrl = imageUrl;
        mainHandler.post(() -> {
            try {
                Glide.with(context)
                        .load(finalImageUrl)
                        .override(targetWidth, targetHeight)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .onlyRetrieveFromCache(false)
                        .preload();

                Log.d(TAG, "图片预加载成功: " + finalImageUrl + " 尺寸: " + targetWidth + "x" + targetHeight);
            } catch (Exception e) {
                Log.e(TAG, "图片预加载异常: " + e.getMessage());
                preloadedUrls.remove(cacheKey);
            }
        });
    }

    /**
     * 预加载视频封面
     */
    private void preloadVideoCover(WorkItem workItem, int priority) {
        String videoUrl = workItem.getFirstVideoUrl();
        if (videoUrl == null || videoUrl.isEmpty()) {
            return;
        }

        // 检查是否已预加载过
        String cacheKey = videoUrl + "_" + priority;
        if (preloadedUrls.contains(cacheKey)) {
            return;
        }
        preloadedUrls.add(cacheKey);

        int cardWidth = getCardWidth();
        int targetSize;

        switch (priority) {
            case PRIORITY_LOW:
                // 超小缩略图：40px
                targetSize = 40;
                break;

            case PRIORITY_MEDIUM:
                // 中等质量：卡片宽度的1/4，最小80px
                targetSize = Math.max(80, cardWidth / 4);
                break;

            case PRIORITY_HIGH:
                // 高质量：卡片宽度的1/2
                targetSize = cardWidth / 2;
                break;

            default:
                targetSize = 80;
        }

        // 在主线程执行Glide预加载
        mainHandler.post(() -> {
            try {
                Glide.with(context)
                        .asBitmap()
                        .load(videoUrl)
                        .override(targetSize, targetSize)
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .placeholder(R.drawable.ic_video_placeholder)
                        .preload();

                Log.d(TAG, "视频封面预加载成功: " + videoUrl + " 尺寸: " + targetSize);
            } catch (Exception e) {
                Log.e(TAG, "视频封面预加载异常: " + e.getMessage());
                preloadedUrls.remove(cacheKey);
            }
        });
    }

    /**
     * 获取卡片宽度（计算一次并缓存）
     */
    private int getCardWidth() {
        if (cardWidth > 0) {
            return cardWidth;
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;

        // 计算每个卡片的宽度（考虑间距）
        // 左padding(4dp) + 右padding(4dp) + 卡片间距(2dp*2) = 12dp
        float density = metrics.density;
        int totalPaddingPx = (int) (12 * density);
        cardWidth = (screenWidth - totalPaddingPx) / 2;

        return cardWidth;
    }

    /**
     * 清理预加载缓存
     */
    public void clearPreloadCache() {
        preloadedUrls.clear();
    }

    /**
     * 释放资源
     */
    public void release() {
        clearPreloadCache();
        mainHandler.removeCallbacksAndMessages(null);
    }
}