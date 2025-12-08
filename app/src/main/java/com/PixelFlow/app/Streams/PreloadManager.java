package com.PixelFlow.app.Streams;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.Pair;

import androidx.recyclerview.widget.RecyclerView;

import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.Target;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 优化的图片和视频封面预加载管理器
 * 支持智能预加载、内存管理、优先级调度
 */
public class PreloadManager implements ComponentCallbacks2 {
    private static final String TAG = "PreloadManager";

    // 预加载优先级
    public static final int PRIORITY_LOW = 1;      // 低优先级：即时预加载
    public static final int PRIORITY_MEDIUM = 2;   // 中优先级：可视区预加载
    public static final int PRIORITY_HIGH = 3;     // 高优先级：详情页预加载

    // 线程池配置
    private static final int CORE_POOL_SIZE;
    private static final int MAX_POOL_SIZE;

    static {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        CORE_POOL_SIZE = Math.max(1, availableProcessors - 1);
        MAX_POOL_SIZE = Math.min(4, availableProcessors);
    }

    private static final long KEEP_ALIVE_TIME = 30L;
    private static final int TASK_QUEUE_SIZE = 100;

    // 缓存配置
    private static final int MAX_CACHE_SIZE = 500;
    private static final long CLEANUP_INTERVAL = 5 * 60 * 1000; // 5分钟清理一次

    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService normalExecutor;
    private final ExecutorService highPriorityExecutor;

    // LRU缓存管理预加载记录
    private final LruCache<String, PreloadRecord> preloadCache;
    private final Map<String, Future<?>> pendingTasks;

    // 尺寸缓存
    private int screenWidth = -1;
    private int screenHeight = -1;
    private float density = -1f;
    private final SizeCache sizeCache;

    // 清理任务
    private final Runnable cleanupTask;

    // 单例模式（可选）
    private static PreloadManager instance;

    public static synchronized PreloadManager getInstance(Context context) {
        if (instance == null) {
            instance = new PreloadManager(context.getApplicationContext());
        }
        return instance;
    }

    public PreloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());

        // 初始化线程池
        this.normalExecutor = createThreadPool("Preload-Normal", Thread.NORM_PRIORITY - 1);
        this.highPriorityExecutor = createThreadPool("Preload-High", Thread.NORM_PRIORITY);

        // 初始化缓存
        this.preloadCache = new LruCache<String, PreloadRecord>(MAX_CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, PreloadRecord value) {
                return 1; // 每个记录算1个单位
            }

            @Override
            protected void entryRemoved(boolean evicted, String key,
                                        PreloadRecord oldValue, PreloadRecord newValue) {

            }
        };

        this.pendingTasks = new ConcurrentHashMap<>();
        this.sizeCache = new SizeCache();

        // 注册内存回调
        this.context.registerComponentCallbacks(this);

        // 初始化清理任务
        this.cleanupTask = new Runnable() {
            @Override
            public void run() {
                cleanupExpiredRecords();
                mainHandler.postDelayed(this, CLEANUP_INTERVAL);
            }
        };

        // 启动定期清理
        mainHandler.postDelayed(cleanupTask, CLEANUP_INTERVAL);
    }

    /**
     * 创建线程池
     */
    private ExecutorService createThreadPool(String namePrefix, int threadPriority) {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(TASK_QUEUE_SIZE),
                new PreloadThreadFactory(namePrefix, threadPriority),
                new ThreadPoolExecutor.DiscardOldestPolicy() // 丢弃最旧的任务
        );
    }

    /**
     * 自定义线程工厂
     */
    private static class PreloadThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final int threadPriority;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        PreloadThreadFactory(String namePrefix, int threadPriority) {
            this.namePrefix = namePrefix + "-";
            this.threadPriority = threadPriority;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement()) {
                @Override
                public void run() {
                    android.os.Process.setThreadPriority(threadPriority);
                    super.run();
                }
            };
            return t;
        }
    }

    /**
     * 预加载记录
     */
    private static class PreloadRecord {
        final String url;
        final int priority;
        final long timestamp;
        final int width;
        final int height;
        final boolean isVideo;

        PreloadRecord(String url, int priority, int width, int height, boolean isVideo) {
            this.url = url;
            this.priority = priority;
            this.timestamp = System.currentTimeMillis();
            this.width = width;
            this.height = height;
            this.isVideo = isVideo;
        }

        boolean isExpired() {
            // 根据优先级设置不同的过期时间
            long expiryTime;
            switch (priority) {
                case PRIORITY_LOW:
                    expiryTime = 60 * 1000; // 1分钟
                    break;
                case PRIORITY_MEDIUM:
                    expiryTime = 3 * 60 * 1000; // 3分钟
                    break;
                case PRIORITY_HIGH:
                    expiryTime = 6 * 60 * 1000; // 6分钟
                    break;
                default:
                    expiryTime = 6 * 60 * 1000;
            }
            return System.currentTimeMillis() - timestamp > expiryTime;
        }
    }

    /**
     * 尺寸缓存
     */
    private class SizeCache {
        private final Map<String, Pair<Integer, Integer>> cache = new ConcurrentHashMap<>();

        Pair<Integer, Integer> getCachedSize(String key) {
            return cache.get(key);
        }

        void cacheSize(String key, int width, int height) {
            cache.put(key, new Pair<>(width, height));
        }

        void clear() {
            cache.clear();
        }
    }

    /**
     * 批量预加载工作项（智能分批处理）
     */
    public void preloadWorkItems(List<WorkItem> workItems, int priority) {
        if (workItems == null || workItems.isEmpty()) {
            return;
        }

        ExecutorService executor = (priority == PRIORITY_HIGH) ?
                highPriorityExecutor : normalExecutor;

        // 根据优先级分批处理
        int batchSize = calculateBatchSize(priority, workItems.size());

        for (int i = 0; i < workItems.size(); i += batchSize) {
            int end = Math.min(i + batchSize, workItems.size());
            List<WorkItem> batch = workItems.subList(i, end);

            final int batchStart = i;
            final int batchEnd = end - 1;

            executor.submit(() -> {
                for (WorkItem item : batch) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    preloadWorkItem(item, priority);
                }

                if (priority == PRIORITY_HIGH && batchEnd - batchStart > 0) {
                    // 高优先级预加载完成后，可以触发一次缓存优化
                    optimizeCache();
                }
            });
        }
    }

    /**
     * 智能预加载（根据滑动状态）
     */
    public void smartPreload(List<WorkItem> visibleItems, List<WorkItem> upcomingItems,
                             int scrollState, boolean isFastScroll) {
        switch (scrollState) {
            case RecyclerView.SCROLL_STATE_IDLE:
                // 静止时：预加载当前可见项的高质量版本
                preloadWorkItems(visibleItems, PRIORITY_HIGH);

                // 预加载即将出现的项的中等质量版本
                if (!upcomingItems.isEmpty()) {
                    preloadWorkItems(upcomingItems.subList(0,
                            Math.min(upcomingItems.size(), 3)), PRIORITY_MEDIUM);
                }
                break;

            case RecyclerView.SCROLL_STATE_DRAGGING:
                if (isFastScroll) {
                    // 快速拖动：只预加载低质量版本
                    preloadWorkItems(visibleItems, PRIORITY_LOW);
                } else {
                    // 慢速拖动：预加载中等质量版本
                    preloadWorkItems(visibleItems, PRIORITY_MEDIUM);
                }
                break;

            case RecyclerView.SCROLL_STATE_SETTLING:
                // 惯性滚动：预加载低质量版本
                preloadWorkItems(visibleItems, PRIORITY_LOW);
                break;
        }
    }

    /**
     * 预加载单个工作项
     */
    private void preloadWorkItem(WorkItem workItem, int priority) {
        if (workItem == null) {
            return;
        }

        String cacheKey = generateCacheKey(workItem, priority);
        PreloadRecord existingRecord = preloadCache.get(cacheKey);

        // 检查是否需要重新预加载
        if (existingRecord != null && !shouldRepreload(existingRecord, priority)) {
            return;
        }

        // 取消可能存在的相同任务
        cancelPendingTask(cacheKey);

        // 提交新的预加载任务
        Future<?> future = (priority == PRIORITY_HIGH ?
                highPriorityExecutor : normalExecutor).submit(() -> {
            try {
                if (workItem.getFirstImageUrl() == null) {
                    preloadVideoCover(workItem, priority, cacheKey);
                } else {
                    preloadImage(workItem, priority, cacheKey);
                }
            } catch (Exception e) {
                // 静默失败，不打印堆栈
                preloadCache.remove(cacheKey);
                pendingTasks.remove(cacheKey);
            }
        });

        pendingTasks.put(cacheKey, future);
    }

    /**
     * 预加载图片
     */
    private void preloadImage(WorkItem workItem, int priority, String cacheKey) {
        String imageUrl = workItem.getFirstImageUrl();
        if (imageUrl == null || imageUrl.isEmpty()) {
            imageUrl = workItem.getCoverUrl();
        }

        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }

        // 获取目标尺寸
        Pair<Integer, Integer> targetSize = getTargetSize(workItem, priority, false);

        // 执行预加载
        try {
            // 使用Glide的submit()方法在后台线程执行
            Glide.with(context)
                    .asDrawable()
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .onlyRetrieveFromCache(false)
                    .submit(targetSize.first, targetSize.second)
                    .get(); // 阻塞直到完成

            // 记录预加载成功
            PreloadRecord record = new PreloadRecord(
                    imageUrl, priority, targetSize.first, targetSize.second, false
            );
            preloadCache.put(cacheKey, record);

        } catch (Exception e) {
            // 预加载失败，移除记录
            preloadCache.remove(cacheKey);
            throw new RuntimeException("Image preload failed", e);
        } finally {
            pendingTasks.remove(cacheKey);
        }
    }

    /**
     * 预加载视频封面
     */
    private void preloadVideoCover(WorkItem workItem, int priority, String cacheKey) {
        String videoUrl = workItem.getFirstVideoUrl();
        if (videoUrl == null || videoUrl.isEmpty()) {
            return;
        }

        // 获取目标尺寸
        Pair<Integer, Integer> targetSize = getTargetSize(workItem, priority, true);

        // 执行预加载
        try {
            Glide.with(context)
                    .asBitmap()
                    .load(videoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .placeholder(R.drawable.ic_video_placeholder)
                    .submit(targetSize.first, targetSize.second)
                    .get(); // 阻塞直到完成

            // 记录预加载成功
            PreloadRecord record = new PreloadRecord(
                    videoUrl, priority, targetSize.first, targetSize.second, true
            );
            preloadCache.put(cacheKey, record);

        } catch (Exception e) {
            // 预加载失败，移除记录
            preloadCache.remove(cacheKey);
            throw new RuntimeException("Video cover preload failed", e);
        } finally {
            pendingTasks.remove(cacheKey);
        }
    }

    /**
     * 获取目标尺寸
     */
    private Pair<Integer, Integer> getTargetSize(WorkItem workItem, int priority, boolean isVideo) {
        String sizeKey = generateSizeKey(workItem, priority, isVideo);
        Pair<Integer, Integer> cachedSize = sizeCache.getCachedSize(sizeKey);

        if (cachedSize != null) {
            return cachedSize;
        }

        int cardWidth = getCardWidth();
        int targetWidth, targetHeight;

        if (isVideo) {
            // 视频封面尺寸
            switch (priority) {
                case PRIORITY_LOW:
                    targetWidth = targetHeight = Math.max(40, cardWidth / 8);
                    break;
                case PRIORITY_MEDIUM:
                    targetWidth = targetHeight = Math.max(80, cardWidth / 4);
                    break;
                case PRIORITY_HIGH:
                    targetWidth = targetHeight = cardWidth / 2;
                    break;
                default:
                    targetWidth = targetHeight = 80;
            }
        } else {
            // 图片尺寸
            switch (priority) {
                case PRIORITY_LOW:
                    targetWidth = Math.max(60, cardWidth / 8);
                    targetHeight = workItem.getDisplayHeight(targetWidth);
                    break;
                case PRIORITY_MEDIUM:
                    targetWidth = Math.max(120, cardWidth / 2);
                    targetHeight = workItem.getDisplayHeight(targetWidth);
                    break;
                case PRIORITY_HIGH:
                    targetWidth = cardWidth;
                    targetHeight = workItem.getDisplayHeight(targetWidth);
                    break;
                default:
                    targetWidth = 120;
                    targetHeight = 160;
            }
        }

        // 限制最大尺寸
        targetWidth = Math.min(targetWidth, getMaxTextureSize());
        targetHeight = Math.min(targetHeight, getMaxTextureSize());

        Pair<Integer, Integer> size = new Pair<>(targetWidth, targetHeight);
        sizeCache.cacheSize(sizeKey, targetWidth, targetHeight);

        return size;
    }

    /**
     * 获取卡片宽度（带缓存）
     */
    private int getCardWidth() {
        if (screenWidth < 0) {
            updateDisplayMetrics();
        }

        // 计算每个卡片的宽度（考虑间距）
        int totalPaddingPx = (int) (12 * density); // 4dp * 3
        return (screenWidth - totalPaddingPx) / 2;
    }

    /**
     * 更新显示尺寸
     */
    private synchronized void updateDisplayMetrics() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        density = metrics.density;
    }

    /**
     * 获取最大纹理尺寸（避免OOM）
     */
    private int getMaxTextureSize() {
        return 1024;
    }

    /**
     * 生成缓存键
     */
    private String generateCacheKey(WorkItem workItem, int priority) {
        String url = workItem.getFirstImageUrl();
        if (url == null) {
            url = workItem.getFirstVideoUrl();
        }
        if (url == null) {
            url = workItem.getCoverUrl();
        }
        return (url != null ? url : "unknown") + "_" + priority;
    }

    /**
     * 生成尺寸缓存键
     */
    private String generateSizeKey(WorkItem workItem, int priority, boolean isVideo) {
        return workItem.getId() + "_" + priority + "_" + (isVideo ? "video" : "image");
    }

    /**
     * 检查是否需要重新预加载
     */
    private boolean shouldRepreload(PreloadRecord record, int newPriority) {
        // 如果新优先级更高，需要重新预加载
        if (newPriority > record.priority) {
            return true;
        }

        // 如果记录已过期，需要重新预加载
        if (record.isExpired()) {
            return true;
        }

        return false;
    }

    /**
     * 取消待处理的任务
     */
    private void cancelPendingTask(String cacheKey) {
        Future<?> future = pendingTasks.get(cacheKey);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            pendingTasks.remove(cacheKey);
        }
    }

    /**
     * 计算批次大小
     */
    private int calculateBatchSize(int priority, int totalItems) {
        switch (priority) {
            case PRIORITY_LOW:
                return Math.min(10, totalItems);
            case PRIORITY_MEDIUM:
                return Math.min(5, totalItems);
            case PRIORITY_HIGH:
                return Math.min(3, totalItems);
            default:
                return Math.min(5, totalItems);
        }
    }

    /**
     * 清理过期记录
     */
    private void cleanupExpiredRecords() {
        Map<String, PreloadRecord> snapshot = new HashMap<>(preloadCache.snapshot());

        for (Map.Entry<String, PreloadRecord> entry : snapshot.entrySet()) {
            if (entry.getValue().isExpired()) {
                preloadCache.remove(entry.getKey());
            }
        }

        // 清理尺寸缓存
        if (sizeCache.cache.size() > MAX_CACHE_SIZE * 2) {
            sizeCache.clear();
        }
    }

    /**
     * 优化缓存策略
     */
    private void optimizeCache() {
        // 可以在这里实现更复杂的缓存优化策略
        // 例如：根据最近使用频率调整缓存条目
    }

    /**
     * 清除特定工作项的预加载
     */
    public void clearPreloadForItem(WorkItem workItem) {
        for (int priority = PRIORITY_LOW; priority <= PRIORITY_HIGH; priority++) {
            String cacheKey = generateCacheKey(workItem, priority);
            cancelPendingTask(cacheKey);
            preloadCache.remove(cacheKey);
        }
    }

    /**
     * 清除所有预加载缓存
     */
    public void clearAllPreloads() {
        // 取消所有待处理任务
        for (Future<?> future : pendingTasks.values()) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        pendingTasks.clear();

        // 清除缓存
        preloadCache.evictAll();
        sizeCache.clear();
    }

    /**
     * 释放资源
     */
    public void release() {
        // 取消清理任务
        mainHandler.removeCallbacks(cleanupTask);

        // 清除所有预加载
        clearAllPreloads();

        // 关闭线程池
        normalExecutor.shutdownNow();
        highPriorityExecutor.shutdownNow();

        // 注销内存回调
        context.unregisterComponentCallbacks(this);

        // 重置尺寸缓存
        screenWidth = -1;
        screenHeight = -1;
        density = -1f;
    }

    /**
     * 内存回调接口实现
     */
    @Override
    public void onTrimMemory(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // 应用在后台，内存极低
                release();
                break;

            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                // 应用在后台，内存较低
                clearAllPreloads();
                break;

            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                // 应用在前台，内存开始紧张
                optimizeCache();
                break;

            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                // 应用在前台，内存较低
                // 清理一半缓存
                int targetSize = preloadCache.size() / 2;
                while (preloadCache.size() > targetSize) {
                    preloadCache.trimToSize(targetSize);
                }
                break;

            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                // 应用在前台，内存极低
                clearAllPreloads();
                break;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // 配置改变时更新尺寸缓存
        updateDisplayMetrics();
        sizeCache.clear();
    }

    @Override
    public void onLowMemory() {
        // 低内存时清除所有缓存
        clearAllPreloads();
    }

    /**
     * 获取统计信息（用于调试）
     */
    public PreloadStats getStats() {
        return new PreloadStats(
                preloadCache.size(),
                pendingTasks.size(),
                sizeCache.cache.size()
        );
    }

    /**
     * 预加载统计信息
     */
    public static class PreloadStats {
        public final int cachedItems;
        public final int pendingTasks;
        public final int cachedSizes;

        PreloadStats(int cachedItems, int pendingTasks, int cachedSizes) {
            this.cachedItems = cachedItems;
            this.pendingTasks = pendingTasks;
            this.cachedSizes = cachedSizes;
        }
    }
}