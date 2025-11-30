package com.PixelFlow.app.DAO;

import java.util.List;
import java.util.Map;

/**
 * 通用存储接口，支持多种数据类型的CRUD操作
 */
public interface StorageRepository<T> {

    // 单个操作
    void save(String key, T data);
    T get(String key);
    void delete(String key);
    boolean contains(String key);

    // 批量操作
    void saveAll(Map<String, T> dataMap);
    Map<String, T> getAll(List<String> keys);
    void deleteAll(List<String> keys);

    // 查询操作
    List<T> query(QueryCondition condition);

    // 数据变更监听
    void addDataChangeListener(DataChangeListener<T> listener);
    void removeDataChangeListener(DataChangeListener<T> listener);

    interface DataChangeListener<T> {
        void onDataChanged(String key, T newData);
        void onDataRemoved(String key);
    }

    interface QueryCondition {
        boolean test(String key, Object value);
    }
}