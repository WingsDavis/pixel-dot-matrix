package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncOutboxEntity)

    @Query("SELECT * FROM sync_outbox WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAt ASC")
    suspend fun pending(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SyncOutboxEntity>>

    @Query("DELETE FROM sync_outbox WHERE coalesceKey = :key AND status IN ('PENDING', 'FAILED')")
    suspend fun deletePendingByCoalesceKey(key: String)

    @Query("UPDATE sync_outbox SET status = :status, retryCount = :retryCount, lastError = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        retryCount: Int,
        error: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM sync_outbox WHERE status = 'DELIVERED' AND updatedAt < :before")
    suspend fun pruneDelivered(before: Long)
}
