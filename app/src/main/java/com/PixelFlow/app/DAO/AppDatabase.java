package com.PixelFlow.app.DAO;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import android.content.Context;

@Database(entities = {StorageEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "PixelFlowDb";
    private static volatile AppDatabase instance;

    public abstract StorageDao storageDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            DATABASE_NAME
                    ).fallbackToDestructiveMigration()
                    .addCallback(new RoomDatabase.Callback() {

                    })
                    .build();
        }
        return instance;
    }
}
