package com.PixelFlow.app.DAO;

import android.content.Context;
import android.util.Log;

import com.PixelFlow.app.DAO.PersistState.LikeStatus;
import com.PixelFlow.app.DAO.impl.GenericStorageRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * 存储管理器，统一管理所有数据类型的存储实例
 */
public class StorageManager {
    private static final String TAG = "StorageManager";
    private static StorageManager instance;

    private final Context context;
    // 使用复合键：DataType + Class 类型，确保每种类型都有唯一的仓库
    private final Map<String, GenericStorageRepository<?>> repositories;

    private StorageManager(Context context) {
        this.context = context.getApplicationContext();
        this.repositories = new HashMap<>();
    }

    public static synchronized StorageManager getInstance(Context context) {
        if (instance == null) {
            instance = new StorageManager(context);
        }
        return instance;
    }

    /**
     * 获取指定数据类型的存储仓库
     */
    @SuppressWarnings("unchecked")
    public <T> GenericStorageRepository<T> getRepository(DataType dataType, Class<T> dataClass) {
        // 创建唯一的键：DataType + Class全名
        String key = createKey(dataType, dataClass);

        if (!repositories.containsKey(key)) {
            synchronized (this) {
                if (!repositories.containsKey(key)) {
                    GenericStorageRepository<T> repository =
                            GenericStorageRepository.getInstance(context, key, dataClass);
                    repositories.put(key, repository);
                    Log.d(TAG, "创建存储仓库: " + key + " for " + dataClass.getSimpleName());
                }
            }
        }
        return (GenericStorageRepository<T>) repositories.get(key);
    }

    /**
     * 创建唯一的存储键
     */
    private String createKey(DataType dataType, Class<?> dataClass) {
        return dataType.name() + "_" + dataClass.getSimpleName();
    }

    /**
     * 预加载所有类型的数据到内存（应用启动时调用）
     */
    public void preloadAllData() {
        Log.d(TAG, "开始预加载所有数据...");
        // 这里可以按需预加载常用数据类型
        getRepository(DataType.LIKE_STATUS, LikeStatus.class);
        // 未来可以添加其他数据类型的预加载
    }

    /**
     * 清理所有内存缓存
     */
    public void clearAllMemoryCache() {
        for (GenericStorageRepository<?> repository : repositories.values()) {
            repository.clearAll();
        }
        Log.d(TAG, "清理所有内存缓存完成");
    }

    /**
     * 获取存储统计信息
     */
    public void printStorageStats() {
        Log.d(TAG, "存储管理器统计:");
        Log.d(TAG, "管理的存储仓库数量: " + repositories.size());
        for (String key : repositories.keySet()) {
            Log.d(TAG, " - " + key + ": " + repositories.get(key));
        }
    }

    /**
     * 清理特定类型的损坏数据（用于修复类型混淆问题）
     */
    public void clearCorruptedData(DataType dataType, Class<?> dataClass) {
        String key = createKey(dataType, dataClass);
        GenericStorageRepository<?> repository = repositories.get(key);
        if (repository != null) {
            repository.clearAll();
            Log.d(TAG, "已清理损坏数据: " + key);
        }
    }
}