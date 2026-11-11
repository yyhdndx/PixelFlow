package com.pixelflow.server.content;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 服务端图片字节缓存：media.id -> PNG 字节。
 *
 * 只缓存字节，不缓存可见性判断。可见性（归属 / 是否挂在已发布作品 / 是否被启用户作为头像）
 * 仍在 MediaService 每次请求时校验，因此缓存命中不会绕过权限检查。
 *
 * 图片上传后字节不可变，故无需 TTL，仅按字节数做 LRU 淘汰。
 */
@Component
public class MediaContentCache implements AutoCloseable {
    private final boolean enabled;
    private final Cache<String, byte[]> cache;
    private final Object[] locks = new Object[64];
    private final ScheduledExecutorService reporter;

    @Autowired
    public MediaContentCache(@Value("${pixelflow.media-cache.enabled:true}") boolean enabled,
            @Value("${pixelflow.media-cache.maximum-weight:268435456}") long maximumWeight) {
        this(enabled, maximumWeight, Ticker.systemTicker());
    }

    MediaContentCache(boolean enabled, long maximumWeight, Ticker ticker) {
        if (maximumWeight <= 0) throw new IllegalArgumentException("media-cache maximum-weight must be positive");
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .maximumWeight(maximumWeight)
                .weigher((String id, byte[] bytes) -> bytes.length)
                .ticker(ticker)
                .recordStats()
                .build();
        Arrays.setAll(locks, i -> new Object());
        reporter = enabled ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "media-content-cache-stats");
            thread.setDaemon(true);
            return thread;
        }) : null;
        if (reporter != null) {
            reporter.scheduleAtFixedRate(() -> {
                var stats = cache.stats();
                LoggerFactory.getLogger(MediaContentCache.class).info(
                        "media-content-cache hits={} misses={} hitRate={} evictions={} entries={}",
                        stats.hitCount(), stats.missCount(), stats.hitRate(), stats.evictionCount(), cache.estimatedSize());
            }, 30, 30, TimeUnit.SECONDS);
        }
    }

    /**
     * 命中则返回缓存字节，未命中用 loader 读库并写回。loader 抛异常时直接向上传播，不缓存。
     */
    public byte[] get(String id, Supplier<byte[]> loader) {
        if (!enabled) return loader.get();
        synchronized (locks[Math.floorMod(id.hashCode(), locks.length)]) {
            byte[] cached = cache.getIfPresent(id);
            if (cached != null) return cached;
            byte[] loaded = loader.get();
            if (loaded != null) cache.put(id, loaded);
            return loaded;
        }
    }

    public void invalidate(String id) {
        if (!enabled) return;
        synchronized (locks[Math.floorMod(id.hashCode(), locks.length)]) {
            cache.invalidate(id);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (reporter != null) reporter.shutdownNow();
    }
}
