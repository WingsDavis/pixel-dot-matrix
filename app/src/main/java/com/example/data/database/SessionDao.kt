package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.SessionLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM session_logs ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<SessionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionLogEntity): Long

    @Query("DELETE FROM session_logs WHERE id = :id")
    suspend fun deleteSessionById(id: Int)

    @Query("DELETE FROM session_logs")
    suspend fun clearAllSessions()
}
