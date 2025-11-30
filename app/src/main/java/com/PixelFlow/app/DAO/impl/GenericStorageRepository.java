package com.PixelFlow.app.DAO.impl;

import android.content.Context;
import android.util.Log;

import com.PixelFlow.app.DAO.AppDatabase;
import com.PixelFlow.app.DAO.DataType;
import com.PixelFlow.app.DAO.StorageDao;
import com.PixelFlow.app.DAO.StorageEntity;
import com.PixelFlow.app.DAO.StorageRepository;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 通用存储仓库实现，支持任意数据类型的存储
 */
public class GenericStorageRepository<T> implements StorageRepository<T> {
    private static final String TAG = "GenericStorageRepo";
    private static final Gson gson = new GsonBuilder().create();

    private static GenericStorageRepository instance;
    private final StorageDao storageDao;
    private final Executor executor;
    private final Map<String, T> memoryCache;
    private final List<DataChangeListener<T>> listeners;
    private final Class<T> dataClass;
    private final DataType dataType;

    private GenericStorageRepository(Context context, DataType dataType, Class<T> dataClass) {
        AppDatabase database = AppDatabase.getInstance(context);
        this.storageDao = database.storageDao();
        this.executor = Executors.newSingleThreadExecutor();
        this.memoryCache = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
        this.dataType = dataType;
        this.dataClass = dataClass;

        // 预加载该类型的所有数据到内存缓存
        preloadDataToMemory();
    }

    /**
     * 获取指定数据类型的存储实例
     */
    @SuppressWarnings("unchecked")
    public static <T> GenericStorageRepository<T> getInstance(Context context, DataType dataType, Class<T> dataClass) {
        if (instance == null) {
            synchronized (GenericStorageRepository.class) {
                if (instance == null) {
                    instance = new GenericStorageRepository<>(context, dataType, dataClass);
                }
            }
        }
        return (GenericStorageRepository<T>) instance;
    }

    private void preloadDataToMemory() {
        executor.execute(() -> {
            try {
                List<StorageEntity> entities = storageDao.getAllByType(dataType.getTableName());
                memoryCache.clear();
                for (StorageEntity entity : entities) {
                    T data = gson.fromJson(entity.getJsonData(), dataClass);
                    memoryCache.put(entity.getItemKey(), data);
                }
                Log.d(TAG, "预加载 " + dataType + " 数据完成，共 " + memoryCache.size() + " 条记录");
            } catch (Exception e) {
                Log.e(TAG, "预加载数据失败: " + e.getMessage());
            }
        });
    }

    @Override
    public void save(String key, T data) {
        // 更新内存缓存
        memoryCache.put(key, data);

        // 异步保存到数据库
        executor.execute(() -> {
            try {
                String jsonData = gson.toJson(data);
                StorageEntity entity = new StorageEntity(dataType.getTableName(), key, jsonData);
                storageDao.insert(entity);

                // 通知监听器
                notifyDataChanged(key, data);

                Log.d(TAG, "保存数据成功: " + dataType + " - " + key);
            } catch (Exception e) {
                Log.e(TAG, "保存数据失败: " + e.getMessage());
            }
        });
    }

    @Override
    public T get(String key) {
        // 先从内存缓存获取
        if (memoryCache.containsKey(key)) {
            return memoryCache.get(key);
        }

        // 内存中没有，同步从数据库读取（注意：这里会阻塞，适合在后台线程调用）
        try {
            StorageEntity entity = storageDao.getByTypeAndKey(dataType.getTableName(), key);
            if (entity != null) {
                T data = gson.fromJson(entity.getJsonData(), dataClass);
                memoryCache.put(key, data);
                return data;
            }
        } catch (Exception e) {
            Log.e(TAG, "获取数据失败: " + e.getMessage());
        }

        return null;
    }

    @Override
    public void delete(String key) {
        // 从内存缓存移除
        memoryCache.remove(key);

        // 异步从数据库删除
        executor.execute(() -> {
            try {
                storageDao.deleteByTypeAndKey(dataType.getTableName(), key);

                // 通知监听器
                notifyDataRemoved(key);

                Log.d(TAG, "删除数据成功: " + dataType + " - " + key);
            } catch (Exception e) {
                Log.e(TAG, "删除数据失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean contains(String key) {
        return memoryCache.containsKey(key) ||
                storageDao.exists(dataType.getTableName(), key) > 0;
    }

    @Override
    public void saveAll(Map<String, T> dataMap) {
        // 更新内存缓存
        memoryCache.putAll(dataMap);

        // 异步批量保存到数据库
        executor.execute(() -> {
            try {
                List<StorageEntity> entities = new ArrayList<>();
                for (Map.Entry<String, T> entry : dataMap.entrySet()) {
                    String jsonData = gson.toJson(entry.getValue());
                    StorageEntity entity = new StorageEntity(dataType.getTableName(), entry.getKey(), jsonData);
                    entities.add(entity);
                }

                // 批量插入（这里简化处理，实际应该使用事务）
                for (StorageEntity entity : entities) {
                    storageDao.insert(entity);
                }

                Log.d(TAG, "批量保存数据成功: " + dataType + " - " + dataMap.size() + " 条记录");
            } catch (Exception e) {
                Log.e(TAG, "批量保存数据失败: " + e.getMessage());
            }
        });
    }

    @Override
    public Map<String, T> getAll(List<String> keys) {
        Map<String, T> result = new HashMap<>();

        // 先从内存缓存获取
        for (String key : keys) {
            if (memoryCache.containsKey(key)) {
                result.put(key, memoryCache.get(key));
            }
        }

        // 对于内存中没有的，从数据库获取
        List<String> missingKeys = new ArrayList<>();
        for (String key : keys) {
            if (!result.containsKey(key)) {
                missingKeys.add(key);
            }
        }

        if (!missingKeys.isEmpty()) {
            // 这里简化处理，实际应该批量查询
            for (String key : missingKeys) {
                T data = get(key); // 这会触发数据库查询
                if (data != null) {
                    result.put(key, data);
                }
            }
        }

        return result;
    }

    @Override
    public void deleteAll(List<String> keys) {
        // 从内存缓存移除
        for (String key : keys) {
            memoryCache.remove(key);
        }

        // 异步批量删除
        executor.execute(() -> {
            try {
                for (String key : keys) {
                    storageDao.deleteByTypeAndKey(dataType.getTableName(), key);
                }

                Log.d(TAG, "批量删除数据成功: " + dataType + " - " + keys.size() + " 条记录");
            } catch (Exception e) {
                Log.e(TAG, "批量删除数据失败: " + e.getMessage());
            }
        });
    }

    @Override
    public List<T> query(QueryCondition condition) {
        List<T> result = new ArrayList<>();

        // 在内存缓存中查询
        for (Map.Entry<String, T> entry : memoryCache.entrySet()) {
            if (condition.test(entry.getKey(), entry.getValue())) {
                result.add(entry.getValue());
            }
        }

        return result;
    }

    @Override
    public void addDataChangeListener(DataChangeListener<T> listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeDataChangeListener(DataChangeListener<T> listener) {
        listeners.remove(listener);
    }

    private void notifyDataChanged(String key, T newData) {
        for (DataChangeListener<T> listener : listeners) {
            listener.onDataChanged(key, newData);
        }
    }

    private void notifyDataRemoved(String key) {
        for (DataChangeListener<T> listener : listeners) {
            listener.onDataRemoved(key);
        }
    }

    // 获取该存储实例的数据类型
    public DataType getDataType() {
        return dataType;
    }

    // 清空该类型的所有数据
    public void clearAll() {
        memoryCache.clear();
        executor.execute(() -> {
            storageDao.deleteAllByType(dataType.getTableName());
            Log.d(TAG, "清空数据成功: " + dataType);
        });
    }
}
