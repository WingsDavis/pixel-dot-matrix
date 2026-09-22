package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.PanicLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PanicDao {
    @Query("SELECT * FROM panic_logs ORDER BY timestamp DESC")
    fun getAllPanicLogs(): Flow<List<PanicLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPanicLog(panicLog: PanicLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIncidentIfAbsent(panicLog: PanicLogEntity): Long

    @Update
    suspend fun updatePanicLog(panicLog: PanicLogEntity)

    @Query("SELECT * FROM panic_logs WHERE incidentType = 'PANIC' AND incidentStatus = 'OPEN' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getActivePanicLog(): PanicLogEntity?

    @Query("SELECT * FROM panic_logs WHERE incidentStatus = 'PENDING_DETAIL' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getPendingAuditLog(): PanicLogEntity?

    @Query("DELETE FROM panic_logs")
    suspend fun clearAllPanicLogs()

    @Query("SELECT * FROM panic_logs WHERE incidentId = :incidentId LIMIT 1")
    suspend fun getByIncidentId(incidentId: String): PanicLogEntity?
}
