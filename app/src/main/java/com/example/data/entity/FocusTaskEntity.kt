package com.example.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "focus_tasks", indices = [Index("status"), Index("sortOrder")])
data class FocusTaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sortOrder: Int,
    val status: String = STATUS_PENDING,
    val estimateSessions: Int = 1,
    val completedSessions: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}
