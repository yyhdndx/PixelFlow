package com.PixelFlow.app.DAO.impl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.PixelFlow.app.DAO.*;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Disk is authoritative. Reads and writes are ordered; callbacks run on the main thread. */
public class GenericStorageRepository<T> implements StorageRepository<T> {
    private static final Map<String, GenericStorageRepository<?>> instances = new HashMap<>();
    private static final Gson gson = new Gson();
    private final SerialStore store;
    private final Class<T> dataClass;
    private final DataType dataType;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<DataChangeListener<T>> listeners = new CopyOnWriteArrayList<>();

    private GenericStorageRepository(Context context, DataType type, Class<T> model) {
        dataType = type;
        dataClass = model;
        StorageDao dao = AppDatabase.getInstance(context).storageDao();
        String table = type.getTableName();
        store = new SerialStore(new SerialStore.Backend() {
            public String read(String key) {
                StorageEntity row = dao.getByTypeAndKey(table, key);
                return row == null ? null : row.getJsonData();
            }
            public Map<String, String> readAll() {
                Map<String, String> result = new HashMap<>();
                for (StorageEntity row : dao.getAllByType(table)) {
                    result.put(row.getItemKey(), row.getJsonData());
                }
                return result;
            }
            public void writeAll(Map<String, String> values) {
                List<StorageEntity> rows = new ArrayList<>();
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    rows.add(new StorageEntity(table, entry.getKey(), entry.getValue()));
                }
                dao.insertAll(rows);
            }
            public void deleteAll(List<String> keys) { dao.deleteKeys(table, keys); }
            public void clear() { dao.deleteAllByType(table); }
        });
    }

    @SuppressWarnings("unchecked")
    public static synchronized <T> GenericStorageRepository<T> getInstance(
            Context context, DataType type, Class<T> model) {
        String key = RepositoryKey.create(type, model);
        if (!instances.containsKey(key)) {
            instances.put(key, new GenericStorageRepository<>(context, type, model));
        }
        return (GenericStorageRepository<T>) instances.get(key);
    }

    public static synchronized <T> GenericStorageRepository<T> getInstance(
            Context context, String key, Class<T> model) {
        DataType type = RepositoryKey.parseType(key);
        if (!key.equals(RepositoryKey.create(type, model))) {
            throw new IllegalArgumentException("Repository key does not match model");
        }
        return getInstance(context, type, model);
    }

    public interface ResultCallback<R> {
        void onSuccess(R value);
        void onError(Throwable error);
    }

    private <R> void deliver(CompletableFuture<R> future, ResultCallback<R> callback) {
        future.whenComplete((value, error) -> main.post(() -> {
            if (error != null) {
                if (callback != null) callback.onError(error);
                else Log.e("StorageRepository", "Storage operation failed", error);
            } else if (callback != null) callback.onSuccess(value);
        }));
    }

    private T decode(String value) {
        return value == null ? null : gson.fromJson(value, dataClass);
    }

    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Use asynchronous storage APIs on the main thread");
        }
    }

    public void getAsync(String key, ResultCallback<T> callback) {
        Objects.requireNonNull(key, "key");
        deliver(store.read(key).thenApply(this::decode), callback);
    }

    public void saveAsync(String key, T value, ResultCallback<Void> callback) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        // Serialize before enqueueing so callers cannot mutate a pending write.
        String snapshot = gson.toJson(value);
        CompletableFuture<Void> future = store.writeAll(Collections.singletonMap(key, snapshot));
        deliver(future, new ResultCallback<Void>() {
            public void onSuccess(Void ignored) {
                for (DataChangeListener<T> listener : listeners) {
                    listener.onDataChanged(key, decode(snapshot));
                }
                if (callback != null) callback.onSuccess(null);
            }
            public void onError(Throwable error) {
                if (callback != null) callback.onError(error);
                else Log.e("StorageRepository", "Save failed", error);
            }
        });
    }

    @Override public void save(String key, T value) { saveAsync(key, value, null); }

    /** Background-only compatibility API. Failures propagate instead of pretending data is missing. */
    @Override public T get(String key) {
        requireWorker();
        return key == null ? null : decode(store.read(key).join());
    }

    @Override public boolean contains(String key) { return get(key) != null; }

    @Override public void delete(String key) {
        if (key == null) return;
        deliver(store.deleteAll(Collections.singletonList(key)), new ResultCallback<Void>() {
            public void onSuccess(Void ignored) {
                for (DataChangeListener<T> listener : listeners) listener.onDataRemoved(key);
            }
            public void onError(Throwable error) { Log.e("StorageRepository", "Delete failed", error); }
        });
    }

    @Override public void saveAll(Map<String, T> values) {
        if (values == null || values.isEmpty()) return;
        Map<String, String> snapshots = new HashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            snapshots.put(Objects.requireNonNull(entry.getKey()),
                    gson.toJson(Objects.requireNonNull(entry.getValue())));
        }
        deliver(store.writeAll(snapshots), null);
    }

    @Override public Map<String, T> getAll(List<String> keys) {
        requireWorker();
        Map<String, T> result = new HashMap<>();
        if (keys != null) for (String key : keys) {
            T value = get(key);
            if (value != null) result.put(key, value);
        }
        return result;
    }

    @Override public void deleteAll(List<String> keys) {
        if (keys != null && !keys.isEmpty()) deliver(store.deleteAll(keys), null);
    }

    @Override public List<T> query(QueryCondition condition) {
        requireWorker();
        List<T> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : store.readAll().join().entrySet()) {
            T value = decode(entry.getValue());
            if (condition.test(entry.getKey(), value)) result.add(value);
        }
        return result;
    }

    @Override public void addDataChangeListener(DataChangeListener<T> listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }
    @Override public void removeDataChangeListener(DataChangeListener<T> listener) {
        listeners.remove(listener);
    }

    public DataType getDataType() { return dataType; }
    public String getUniqueKey() { return RepositoryKey.create(dataType, dataClass); }
    public List<String> getAllKeys() {
        requireWorker();
        return new ArrayList<>(store.readAll().join().keySet());
    }
    public void clearMemoryCache() { deliver(store.clearCache(), null); }
    public void clearAll() { deliver(store.clear(), null); }
}
