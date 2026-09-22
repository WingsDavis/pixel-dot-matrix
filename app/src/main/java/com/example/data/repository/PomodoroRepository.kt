package com.example.data.repository

import com.example.data.database.PanicDao
import com.example.data.database.SessionDao
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import kotlinx.coroutines.flow.Flow

class PomodoroRepository(
    private val sessionDao: SessionDao,
    private val panicDao: PanicDao
) {
    val allSessions: Flow<List<SessionLogEntity>> = sessionDao.getAllSessions()
    val allPanicLogs: Flow<List<PanicLogEntity>> = panicDao.getAllPanicLogs()

    suspend fun insertSession(session: SessionLogEntity): Long {
        return sessionDao.insertSession(session)
    }

    suspend fun deleteSessionById(id: Int) {
        sessionDao.deleteSessionById(id)
    }

    suspend fun clearAllSessions() {
        sessionDao.clearAllSessions()
    }

    suspend fun insertPanicLog(panicLog: PanicLogEntity): Long {
        return panicDao.insertPanicLog(panicLog)
    }

    suspend fun insertIncidentIfAbsent(incident: PanicLogEntity): Long = panicDao.insertIncidentIfAbsent(incident)

    suspend fun updatePanicLog(panicLog: PanicLogEntity) {
        panicDao.updatePanicLog(panicLog)
    }

    suspend fun getActivePanicLog(): PanicLogEntity? {
        return panicDao.getActivePanicLog()
    }

    suspend fun getPendingAuditLog(): PanicLogEntity? {
        return panicDao.getPendingAuditLog()
    }

    suspend fun clearAllPanicLogs() {
        panicDao.clearAllPanicLogs()
    }
}
