package com.example.crawlbotapplication.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface WeatherDao {

    @Query("SELECT * FROM weather_items ORDER BY id DESC LIMIT 1")
    WeatherItem getLatestWeather();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(WeatherItem weather);

    @Query("DELETE FROM weather_items")
    void deleteAll();
}
