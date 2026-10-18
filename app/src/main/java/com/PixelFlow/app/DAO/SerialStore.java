package com.PixelFlow.app.DAO;

import java.util.*;
import java.util.concurrent.*;

/** All disk and cache operations share one queue. Cached values are immutable snapshots. */
public final class SerialStore implements AutoCloseable {
    public interface Backend {
        String read(String key);
        Map<String, String> readAll();
        void writeAll(Map<String, String> values);
        void deleteAll(List<String> keys);
        void clear();
    }

    private final Backend backend;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String> cache = new HashMap<>();

    public SerialStore(Backend backend) { this.backend = backend; }

    private <R> CompletableFuture<R> submit(Callable<R> task) {
        CompletableFuture<R> future = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try { future.complete(task.call()); }
                catch (Exception error) { future.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    public CompletableFuture<String> read(String key) {
        return submit(() -> {
            if (cache.containsKey(key)) return cache.get(key);
            String value = backend.read(key);
            if (value != null) cache.put(key, value);
            return value;
        });
    }

    public CompletableFuture<Map<String, String>> readAll() {
        return submit(() -> {
            Map<String, String> values = new HashMap<>(backend.readAll());
            cache.clear();
            cache.putAll(values);
            return values;
        });
    }

    public CompletableFuture<Void> writeAll(Map<String, String> values) {
        Map<String, String> snapshot = new HashMap<>(values);
        return submit(() -> {
            backend.writeAll(snapshot);
            cache.putAll(snapshot);
            return null;
        });
    }

    public CompletableFuture<Void> deleteAll(List<String> keys) {
        List<String> snapshot = new ArrayList<>(keys);
        return submit(() -> {
            backend.deleteAll(snapshot);
            for (String key : snapshot) cache.remove(key);
            return null;
        });
    }

    public CompletableFuture<Void> clear() {
        return submit(() -> {
            backend.clear();
            cache.clear();
            return null;
        });
    }

    public CompletableFuture<Void> clearCache() {
        return submit(() -> { cache.clear(); return null; });
    }

    @Override public void close() { worker.shutdown(); }
}
