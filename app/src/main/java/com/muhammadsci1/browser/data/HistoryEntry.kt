package com.muhammadsci1.browser.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history",
    indices = [Index(value = ["url"]), Index(value = ["visitedAt"])]
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String?,
    val visitedAt: Long = System.currentTimeMillis()
)
