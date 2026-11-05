package com.pixelflow.server.content;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PostContentCacheTest {
    final PostContentCache.Content old=new PostContentCache.Content("旧","正文",List.of(),List.of());
    final PostContentCache.Content updated=new PostContentCache.Content("新","正文",List.of(),List.of());

    @Test void hitExpiryCapacityAndImmutableValue() {
        AtomicLong clock=new AtomicLong();
        try (var cache=new PostContentCache(true,1,Duration.ofSeconds(10),clock::get)) {
            assertSame(old,cache.get(1,() -> old));
            assertSame(old,cache.get(1,() -> fail("must hit")));
            assertEquals(1,cache.stats().hitCount());
            clock.set(Duration.ofSeconds(10).toNanos());
            assertSame(updated,cache.get(1,() -> updated));
            cache.get(2,() -> old); assertEquals(1,cache.size());
            assertThrows(UnsupportedOperationException.class,() -> old.mediaIds().add("bad"));
        }
    }
    @Test void disabledErrorsAndInvalidConfiguration() {
        try (var cache=new PostContentCache(false,1,Duration.ofSeconds(1),System::nanoTime)) {
            cache.get(1,() -> old); assertSame(updated,cache.get(1,() -> updated));
            assertEquals(0,cache.stats().requestCount());
        }
        try (var cache=new PostContentCache(true,1,Duration.ofSeconds(1),System::nanoTime)) {
            assertThrows(IllegalStateException.class,() -> cache.get(1,() -> { throw new IllegalStateException(); }));
            assertSame(updated,cache.get(1,() -> updated));
            assertEquals(1,cache.stats().loadFailureCount());
        }
        assertThrows(IllegalArgumentException.class,() -> new PostContentCache(true,0,Duration.ofSeconds(1),System::nanoTime));
        assertThrows(IllegalArgumentException.class,() -> new PostContentCache(true,1,Duration.ZERO,System::nanoTime));
    }
    @Test void commitInvalidatesRollbackDoesNotAndTransactionsBypass() {
        try (var cache=new PostContentCache(true,10,Duration.ofSeconds(10),System::nanoTime)) {
            cache.get(1,() -> old);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertSame(updated,cache.get(1,() -> updated));
                cache.invalidateAfterCommit(1);
                for (var sync:TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
            assertSame(old,cache.get(1,() -> fail("rollback must retain committed entry")));
            TransactionSynchronizationManager.initSynchronization();
            try {
                cache.invalidateAfterCommit(1);
                for (var sync:TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
            assertSame(updated,cache.get(1,() -> updated));
        }
    }
    @Test void oldInFlightLoadCannotSurviveCompletedInvalidation() throws Exception {
        try (var cache=new PostContentCache(true,10,Duration.ofSeconds(10),System::nanoTime)) {
            var pool=Executors.newFixedThreadPool(2);
            CountDownLatch loading=new CountDownLatch(1),release=new CountDownLatch(1),invalidating=new CountDownLatch(1);
            try {
                var read=pool.submit(() -> cache.get(1,() -> {
                    loading.countDown();
                    try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    return old;
                }));
                assertTrue(loading.await(5,TimeUnit.SECONDS));
                var write=pool.submit(() -> { invalidating.countDown(); cache.invalidateAfterCommit(1); });
                assertTrue(invalidating.await(5,TimeUnit.SECONDS)); release.countDown();
                assertSame(old,read.get(5,TimeUnit.SECONDS)); write.get(5,TimeUnit.SECONDS);
                assertSame(updated,cache.get(1,() -> updated));
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
}
