package com.example.crawlbotapplication.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface GasolineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<GasolineItem> items);

    @Query("SELECT * FROM gasoline_items ORDER BY id ASC")
    List<GasolineItem> getAllGasoline();

    @Query("DELETE FROM gasoline_items")
    void deleteAll();
}
