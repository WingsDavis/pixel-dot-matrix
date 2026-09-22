package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_logs")
data class SessionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionType: String, // "FOCUS", "SHORT_BREAK", "LONG_BREAK"
    val startTimeMillis: Long,
    val durationSeconds: Int,
    val status: String, // "COMPLETED", "INTERRUPTED", "PANIC"
    val taskId: String? = null,
    val taskName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
