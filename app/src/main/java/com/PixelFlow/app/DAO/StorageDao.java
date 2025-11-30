package com.PixelFlow.app.DAO;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface StorageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(StorageEntity entity);

    @Update
    void update(StorageEntity entity);

    @Query("SELECT * FROM storage_entities WHERE compositeKey = :compositeKey")
    StorageEntity getByCompositeKey(String compositeKey);

    @Query("SELECT * FROM storage_entities WHERE dataType = :dataType AND itemKey = :itemKey")
    StorageEntity getByTypeAndKey(String dataType, String itemKey);

    @Query("SELECT * FROM storage_entities WHERE dataType = :dataType")
    List<StorageEntity> getAllByType(String dataType);

    @Query("SELECT * FROM storage_entities WHERE dataType IN (:dataTypes)")
    List<StorageEntity> getByTypes(List<String> dataTypes);

    @Query("DELETE FROM storage_entities WHERE compositeKey = :compositeKey")
    void deleteByCompositeKey(String compositeKey);

    @Query("DELETE FROM storage_entities WHERE dataType = :dataType AND itemKey = :itemKey")
    void deleteByTypeAndKey(String dataType, String itemKey);

    @Query("DELETE FROM storage_entities WHERE dataType = :dataType")
    void deleteAllByType(String dataType);

    @Query("SELECT COUNT(*) FROM storage_entities WHERE dataType = :dataType AND itemKey = :itemKey")
    int exists(String dataType, String itemKey);

    // 支持LiveData的查询
    @Query("SELECT * FROM storage_entities WHERE dataType = :dataType AND itemKey = :itemKey")
    LiveData<StorageEntity> getLiveData(String dataType, String itemKey);
}
