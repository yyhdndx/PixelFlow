package com.PixelFlow.app.DAO;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class SerialStoreTest {
    private static class MemoryBackend implements SerialStore.Backend {
        final Map<String, String> disk = new HashMap<>();
        boolean failWrite;
        public String read(String key) { return disk.get(key); }
        public Map<String, String> readAll() { return new HashMap<>(disk); }
        public void writeAll(Map<String, String> values) {
            if (failWrite) throw new IllegalStateException("disk unavailable");
            disk.putAll(values);
        }
        public void deleteAll(List<String> keys) { for (String key : keys) disk.remove(key); }
        public void clear() { disk.clear(); }
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(3, TimeUnit.SECONDS);
    }

    @Test public void readAfterQueuedWriteSeesLatestValue() throws Exception {
        try (SerialStore store = new SerialStore(new MemoryBackend())) {
            store.writeAll(Collections.singletonMap("a", "old"));
            store.writeAll(Collections.singletonMap("a", "new"));
            assertEquals("new", await(store.read("a")));
        }
    }

    @Test public void clearingCacheDoesNotDeleteDisk() throws Exception {
        MemoryBackend disk = new MemoryBackend();
        try (SerialStore store = new SerialStore(disk)) {
            await(store.writeAll(Collections.singletonMap("a", "value")));
            await(store.clearCache());
            assertEquals("value", await(store.read("a")));
            assertEquals("value", disk.disk.get("a"));
        }
    }

    @Test public void failedWriteDoesNotPublishUnpersistedCacheValue() throws Exception {
        MemoryBackend disk = new MemoryBackend();
        try (SerialStore store = new SerialStore(disk)) {
            await(store.writeAll(Collections.singletonMap("a", "old")));
            disk.failWrite = true;
            try {
                await(store.writeAll(Collections.singletonMap("a", "new")));
                fail("Expected write failure");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
            assertEquals("old", await(store.read("a")));
        }
    }

    @Test public void deletingThenReadingDoesNotResurrectCachedData() throws Exception {
        try (SerialStore store = new SerialStore(new MemoryBackend())) {
            store.writeAll(Collections.singletonMap("a", "value"));
            store.deleteAll(Collections.singletonList("a"));
            assertNull(await(store.read("a")));
        }
    }

    @Test public void submittedMapIsSnapshottedBeforeWorkerRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        MemoryBackend disk = new MemoryBackend() {
            @Override public String read(String key) {
                entered.countDown();
                try {
                    if (!unblock.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return null;
            }
        };
        try (SerialStore store = new SerialStore(disk)) {
            store.read("gate");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Map<String, String> values = new HashMap<>();
            values.put("a", "at submission");
            CompletableFuture<Void> write = store.writeAll(values);
            values.put("a", "mutated later");
            unblock.countDown();
            await(write);
            assertEquals("at submission", await(store.read("a")));
        } finally { unblock.countDown(); }
    }

    @Test public void readFailureIsNotReportedAsMissingRecord() throws Exception {
        MemoryBackend disk = new MemoryBackend() {
            @Override public String read(String key) { throw new IllegalStateException("unavailable"); }
        };
        try (SerialStore store = new SerialStore(disk)) {
            try {
                await(store.read("a"));
                fail("Expected read failure");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        }
    }

    @Test public void closedStoreFailsInsteadOfLeavingFuturePending() throws Exception {
        SerialStore store = new SerialStore(new MemoryBackend());
        store.close();
        try {
            await(store.read("a"));
            fail("Expected rejection");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof RejectedExecutionException);
        }
    }
}
