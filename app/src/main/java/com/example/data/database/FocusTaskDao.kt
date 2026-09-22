package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.FocusTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusTaskDao {
    @Query("SELECT * FROM focus_tasks WHERE status = 'PENDING' ORDER BY sortOrder ASC, createdAt ASC")
    fun observePending(): Flow<List<FocusTaskEntity>>

    @Query("SELECT * FROM focus_tasks WHERE status = 'PENDING' ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun pending(): List<FocusTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: FocusTaskEntity)

    @Update
    suspend fun update(task: FocusTaskEntity)

    @Query("DELETE FROM focus_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM focus_tasks WHERE status = 'PENDING'")
    suspend fun maxPendingOrder(): Int
}
