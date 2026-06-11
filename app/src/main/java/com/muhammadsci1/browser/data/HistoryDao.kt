package com.muhammadsci1.browser.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 100): List<HistoryEntry>

    @Query("DELETE FROM history")
    suspend fun clear()
}
