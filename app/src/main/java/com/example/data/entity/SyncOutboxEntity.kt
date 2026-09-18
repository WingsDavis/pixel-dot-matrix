package com.example.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_outbox",
    indices = [Index("coalesceKey"), Index("status")]
)
data class SyncOutboxEntity(
    @PrimaryKey val id: String,
    val path: String,
    val payload: ByteArray,
    val coalesceKey: String?,
    val status: String = STATUS_PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_DELIVERED = "DELIVERED"
        const val STATUS_FAILED = "FAILED"
    }
}
