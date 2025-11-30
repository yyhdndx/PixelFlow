package com.PixelFlow.app.DAO;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

/**
 * 通用存储实体，支持任意JSON数据的存储
 */
@Entity(tableName = "storage_entities")
public class StorageEntity {
    @PrimaryKey
    @NonNull
    private String compositeKey; // 格式: dataType_key (如: like_status_1001)
    private String dataType;     // 数据类型
    private String itemKey;      // 数据项key
    private String jsonData;     // JSON格式的数据
    private long createdAt;
    private long updatedAt;
    private int version;         // 数据版本，用于迁移

    public StorageEntity(String dataType, String itemKey, String jsonData) {
        this.dataType = dataType;
        this.itemKey = itemKey;
        this.jsonData = jsonData;
        this.compositeKey = dataType + "_" + itemKey;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        this.version = 1;
    }

    // Getters and Setters
    public String getCompositeKey() { return compositeKey; }
    public void setCompositeKey(String compositeKey) { this.compositeKey = compositeKey; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public String getItemKey() { return itemKey; }
    public void setItemKey(String itemKey) { this.itemKey = itemKey; }

    public String getJsonData() { return jsonData; }
    public void setJsonData(String jsonData) {
        this.jsonData = jsonData;
        this.updatedAt = System.currentTimeMillis();
    }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}