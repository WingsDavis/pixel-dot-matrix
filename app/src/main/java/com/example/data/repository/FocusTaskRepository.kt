package com.example.data.repository

import com.example.data.database.FocusTaskDao
import com.example.data.entity.FocusTaskEntity

class FocusTaskRepository(private val dao: FocusTaskDao) {
    val pendingTasks = dao.observePending()
    suspend fun pending(): List<FocusTaskEntity> = dao.pending()

    suspend fun add(title: String, estimateSessions: Int): FocusTaskEntity {
        val task = FocusTaskEntity(
            title = title.trim(),
            sortOrder = dao.maxPendingOrder() + 1,
            estimateSessions = estimateSessions.coerceIn(1, 12)
        )
        dao.upsert(task)
        return task
    }

    suspend fun complete(id: String) = updateStatus(id, FocusTaskEntity.STATUS_COMPLETED)
    suspend fun skip(id: String) = updateStatus(id, FocusTaskEntity.STATUS_SKIPPED)
    suspend fun delete(id: String) = dao.delete(id)

    suspend fun move(id: String, offset: Int) {
        val tasks = dao.pending()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + offset).coerceIn(0, tasks.lastIndex)
        if (index == target) return
        val first = tasks[index]
        val second = tasks[target]
        dao.update(first.copy(sortOrder = second.sortOrder, updatedAt = System.currentTimeMillis()))
        dao.update(second.copy(sortOrder = first.sortOrder, updatedAt = System.currentTimeMillis()))
    }

    suspend fun recordCompletedFocus(id: String): Boolean {
        val task = dao.pending().firstOrNull { it.id == id } ?: return false
        val completed = task.completedSessions + 1
        val isComplete = completed >= task.estimateSessions
        dao.update(
            task.copy(
                completedSessions = completed,
                status = if (isComplete) FocusTaskEntity.STATUS_COMPLETED else task.status,
                updatedAt = System.currentTimeMillis()
            )
        )
        return isComplete
    }

    private suspend fun updateStatus(id: String, status: String) {
        val task = dao.pending().firstOrNull { it.id == id } ?: return
        dao.update(task.copy(status = status, updatedAt = System.currentTimeMillis()))
    }
}
