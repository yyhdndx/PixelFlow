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

    // 使用 Map 来存储不同类型的实例
    private static final Map<String, GenericStorageRepository<?>> instances = new ConcurrentHashMap<>();
    private final StorageDao storageDao;
    private final Executor executor;
    private final Map<String, T> memoryCache;
    private final List<DataChangeListener<T>> listeners;
    private final Class<T> dataClass;
    private final DataType dataType;
    private final String uniqueKey; // 唯一标识符

    private GenericStorageRepository(Context context, DataType dataType, Class<T> dataClass, String uniqueKey) {
        AppDatabase database = AppDatabase.getInstance(context);
        this.storageDao = database.storageDao();
        this.executor = Executors.newSingleThreadExecutor();
        this.memoryCache = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
        this.dataType = dataType;
        this.dataClass = dataClass;
        this.uniqueKey = uniqueKey;

        // 预加载该类型的所有数据到内存缓存
        preloadDataToMemory();
    }

    /**
     * 获取指定数据类型的存储实例
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> GenericStorageRepository<T> getInstance(
            Context context, DataType dataType, Class<T> dataClass) {

        // 使用数据类型和类名作为复合键，确保每种类型都有独立的实例
        String key = createInstanceKey(dataType, dataClass);

        if (!instances.containsKey(key)) {
            instances.put(key, new GenericStorageRepository<>(context, dataType, dataClass, key));
            Log.d(TAG, "创建新的存储实例: " + key + " for " + dataClass.getSimpleName());
        } else {
            Log.d(TAG, "返回现有的存储实例: " + key);
        }

        return (GenericStorageRepository<T>) instances.get(key);
    }

    /**
     * 使用存储键获取实例（用于修复后的 StorageManager）
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> GenericStorageRepository<T> getInstance(
            Context context, String storageKey, Class<T> dataClass) {

        if (!instances.containsKey(storageKey)) {
            // 从 storageKey 解析出 DataType
            DataType dataType = extractDataTypeFromKey(storageKey);
            instances.put(storageKey, new GenericStorageRepository<>(context, dataType, dataClass, storageKey));
            Log.d(TAG, "创建新的存储实例: " + storageKey + " for " + dataClass.getSimpleName());
        } else {
            Log.d(TAG, "返回现有的存储实例: " + storageKey);
        }

        return (GenericStorageRepository<T>) instances.get(storageKey);
    }

    /**
     * 创建实例键
     */
    private static String createInstanceKey(DataType dataType, Class<?> dataClass) {
        return dataType.name() + "_" + dataClass.getSimpleName();
    }

    /**
     * 从存储键提取 DataType
     */
    private static DataType extractDataTypeFromKey(String storageKey) {
        try {
            // 假设存储键格式为 "DataType_ClassName"
            int underscoreIndex = storageKey.indexOf('_');
            if (underscoreIndex > 0) {
                String dataTypeStr = storageKey.substring(0, underscoreIndex);
                return DataType.valueOf(dataTypeStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "无法从存储键提取 DataType: " + storageKey, e);
        }

        // 默认返回 LIKE_STATUS
        return DataType.LIKE_STATUS;
    }

    private void preloadDataToMemory() {
        executor.execute(() -> {
            try {
                // 使用数据类型对应的表名
                String tableName = dataType != null ? dataType.getTableName() : uniqueKey;
                List<StorageEntity> entities = storageDao.getAllByType(tableName);

                memoryCache.clear();
                for (StorageEntity entity : entities) {
                    try {
                        T data = gson.fromJson(entity.getJsonData(), dataClass);
                        if (data != null) {
                            memoryCache.put(entity.getItemKey(), data);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析数据失败，key: " + entity.getItemKey() + ", type: " +
                                (dataClass != null ? dataClass.getSimpleName() : "null"), e);
                    }
                }

                Log.d(TAG, "预加载 " + uniqueKey + " 数据完成，共 " + memoryCache.size() + " 条记录");
            } catch (Exception e) {
                Log.e(TAG, "预加载数据失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void save(String key, T data) {
        if (key == null || data == null) {
            Log.w(TAG, "保存数据失败：key 或 data 为空");
            return;
        }

        // 更新内存缓存
        memoryCache.put(key, data);

        // 异步保存到数据库
        executor.execute(() -> {
            try {
                String jsonData = gson.toJson(data);
                String tableName = dataType != null ? dataType.getTableName() : uniqueKey;
                StorageEntity entity = new StorageEntity(tableName, key, jsonData);

                // 先删除旧的（如果存在）
                storageDao.deleteByTypeAndKey(tableName, key);
                // 插入新的
                storageDao.insert(entity);

                // 通知监听器
                notifyDataChanged(key, data);

                Log.d(TAG, "保存数据成功: " + uniqueKey + " - " + key);
            } catch (Exception e) {
                Log.e(TAG, "保存数据失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public T get(String key) {
        if (key == null) {
            return null;
        }

        // 先从内存缓存获取
        if (memoryCache.containsKey(key)) {
            return memoryCache.get(key);
        }

        // 内存中没有，同步从数据库读取
        try {
            String tableName = dataType != null ? dataType.getTableName() : uniqueKey;
            StorageEntity entity = storageDao.getByTypeAndKey(tableName, key);
            if (entity != null && entity.getJsonData() != null) {
                T data = gson.fromJson(entity.getJsonData(), dataClass);
                if (data != null) {
                    memoryCache.put(key, data);
                }
                return data;
            }
        } catch (Exception e) {
            Log.e(TAG, "获取数据失败: " + key + " - " + e.getMessage());
        }

        return null;
    }

    @Override
    public void delete(String key) {
        if (key == null) {
            return;
        }

        // 从内存缓存移除
        memoryCache.remove(key);

        // 异步从数据库删除
        executor.execute(() -> {
            try {
                String tableName = dataType != null ? dataType.getTableName() : uniqueKey;
                storageDao.deleteByTypeAndKey(tableName, key);

                // 通知监听器
                notifyDataRemoved(key);

                Log.d(TAG, "删除数据成功: " + uniqueKey + " - " + key);
            } catch (Exception e) {
                Log.e(TAG, "删除数据失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean contains(String key) {
        if (key == null) {
            return false;
        }

        if (memoryCache.containsKey(key)) {
            return true;
        }

        try {
            String tableName = dataType != null ? dataType.getTableName() : uniqueKey;
            return storageDao.exists(tableName, key) > 0;
        } catch (Exception e) {
            Log.e(TAG, "检查数据存在性失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void saveAll(Map<String, T> dataMap) {
        if (dataMap == null || dataMap.isEmpty()) {
            return;
        }

        // 更新内存缓存
        memoryCache.putAll(dataMap);

        // 异步批量保存到数据库
        executor.execute(() -> {
            try {
                String tableName = dataType != null ? dataType.getTableName() : uniqueKey;

                for (Map.Entry<String, T> entry : dataMap.entrySet()) {
                    String jsonData = gson.toJson(entry.getValue());
                    StorageEntity entity = new StorageEntity(tableName, entry.getKey(), jsonData);

                    // 使用 upsert（先删除后插入）
                    storageDao.deleteByTypeAndKey(tableName, entry.getKey());
                    storageDao.insert(entity);
                }

                Log.d(TAG, "批量保存数据成功: " + uniqueKey + " - " + dataMap.size() + " 条记录");
            } catch (Exception e) {
                Log.e(TAG, "批量保存数据失败: " + e.getMessage());
            }
        });
    }

    @Override
    public Map<String, T> getAll(List<String> keys) {
        Map<String, T> result = new HashMap<>();

        if (keys == null || keys.isEmpty()) {
            return result;
        }

        // 先从内存缓存获取
        for (String key : keys) {
            if (key != null && memoryCache.containsKey(key)) {
                result.put(key, memoryCache.get(key));
            }
        }

        // 对于内存中没有的，从数据库获取
        List<String> missingKeys = new ArrayList<>();
        for (String key : keys) {
            if (key != null && !result.containsKey(key)) {
                missingKeys.add(key);
            }
        }

        if (!missingKeys.isEmpty()) {
            try {
                String tableName = dataType != null ? dataType.getTableName() : uniqueKey;

                // 批量查询数据库
                for (String key : missingKeys) {
                    StorageEntity entity = storageDao.getByTypeAndKey(tableName, key);
                    if (entity != null && entity.getJsonData() != null) {
                        T data = gson.fromJson(entity.getJsonData(), dataClass);
                        if (data != null) {
                            result.put(key, data);
                            memoryCache.put(key, data);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "批量获取数据失败: " + e.getMessage());
            }
        }

        return result;
    }

    @Override
    public void deleteAll(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        // 从内存缓存移除
        for (String key : keys) {
            if (key != null) {
                memoryCache.remove(key);
            }
        }

        // 异步批量删除
        executor.execute(() -> {
            try {
                String tableName = dataType != null ? dataType.getTableName() : uniqueKey;

                for (String key : keys) {
                    if (key != null) {
                        storageDao.deleteByTypeAndKey(tableName, key);
                    }
                }

                Log.d(TAG, "批量删除数据成功: " + uniqueKey + " - " + keys.size() + " 条记录");
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
        if (listener != null && !listeners.contains(listener)) {
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

    // 获取所有键
    public List<String> getAllKeys() {
        return new ArrayList<>(memoryCache.keySet());
    }

    // 清空该类型的所有数据
    public void clearAll() {
        memoryCache.clear();
        executor.execute(() -> {
            try {
                String tableName = dataType != null ? dataType.getTableName() : uniqueKey;
                storageDao.deleteAllByType(tableName);
                Log.d(TAG, "清空数据成功: " + uniqueKey);
            } catch (Exception e) {
                Log.e(TAG, "清空数据失败: " + e.getMessage());
            }
        });
    }

    // 获取该存储实例的唯一键
    public String getUniqueKey() {
        return uniqueKey;
    }
}