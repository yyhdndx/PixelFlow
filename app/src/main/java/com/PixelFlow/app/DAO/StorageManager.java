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
    private final Map<DataType, GenericStorageRepository<?>> repositories;

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
        if (!repositories.containsKey(dataType)) {
            synchronized (this) {
                if (!repositories.containsKey(dataType)) {
                    GenericStorageRepository<T> repository =
                            GenericStorageRepository.getInstance(context, dataType, dataClass);
                    repositories.put(dataType, repository);
                    Log.d(TAG, "创建存储仓库: " + dataType);
                }
            }
        }
        return (GenericStorageRepository<T>) repositories.get(dataType);
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
        for (DataType type : repositories.keySet()) {
            Log.d(TAG, " - " + type + ": " + repositories.get(type));
        }
    }
}
