package com.pixelflow.server.content;

import com.github.benmanes.caffeine.cache.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Component
public class PostContentCache implements AutoCloseable {
    public record Content(String title,String content,List<PostService.Clip> clips,List<String> mediaIds) {
        public Content { clips=List.copyOf(clips); mediaIds=List.copyOf(mediaIds); }
    }
    private final boolean enabled;
    private final Cache<Long,Content> cache;
    private final Object[] locks=new Object[64];
    private final ScheduledExecutorService reporter;

    @org.springframework.beans.factory.annotation.Autowired
    public PostContentCache(@Value("${pixelflow.local-cache.enabled:true}") boolean enabled,
            @Value("${pixelflow.local-cache.maximum-size:1000}") long maximumSize,
            @Value("${pixelflow.local-cache.ttl:10s}") Duration ttl) {
        this(enabled,maximumSize,ttl,Ticker.systemTicker());
    }
    PostContentCache(boolean enabled,long maximumSize,Duration ttl,Ticker ticker) {
        if (maximumSize<=0 || ttl.isZero() || ttl.isNegative())
            throw new IllegalArgumentException("local-cache maximum-size and ttl must be positive");
        this.enabled=enabled;
        cache=Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(ttl).ticker(ticker).recordStats().build();
        Arrays.setAll(locks,i -> new Object());
        reporter=enabled ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread=new Thread(task,"post-content-cache-stats"); thread.setDaemon(true); return thread;
        }) : null;
        if (reporter!=null) reporter.scheduleAtFixedRate(() -> {
            var stats=cache.stats();
            LoggerFactory.getLogger(PostContentCache.class).info(
                    "post-content-cache hits={} misses={} hitRate={} loads={} failures={} averageLoadNanos={} evictions={} entries={}",
                    stats.hitCount(),stats.missCount(),stats.hitRate(),stats.loadSuccessCount(),stats.loadFailureCount(),
                    stats.averageLoadPenalty(),stats.evictionCount(),cache.estimatedSize());
        },30,30,TimeUnit.SECONDS);
    }
    public Content get(long id,Supplier<Content> loader) {
        if (!enabled || TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        synchronized (locks[Math.floorMod(Long.hashCode(id),locks.length)]) {
            return cache.get(id,key -> loader.get());
        }
    }
    public void invalidateAfterCommit(long id) {
        if (!enabled) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(id); }
            });
        } else invalidate(id);
    }
    private void invalidate(long id) {
        synchronized (locks[Math.floorMod(Long.hashCode(id),locks.length)]) { cache.invalidate(id); }
    }
    public void clear() { cache.invalidateAll(); }
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() { return cache.stats(); }
    long size() { cache.cleanUp(); return cache.estimatedSize(); }
    @PreDestroy public void close() { if (reporter!=null) reporter.shutdownNow(); }
}
