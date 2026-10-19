package com.example.crawlbotapplication.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ChangeEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<ChangeEventItem> events);

    @Query("SELECT * FROM client_change_events ORDER BY eventId DESC")
    List<ChangeEventItem> getAllEvents();

    @Query("SELECT * FROM client_change_events WHERE isNotified = 0 ORDER BY eventId ASC")
    List<ChangeEventItem> getUnnotifiedEvents();

    @Query("UPDATE client_change_events SET isNotified = 1 WHERE eventId = :id")
    void markNotified(int id);
}
