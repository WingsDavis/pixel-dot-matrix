package com.example.data.repository

import com.example.data.database.FocusTaskDao
import com.example.data.entity.FocusTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTaskRepositoryTest {
    @Test
    fun addMoveSkipAndCompleteMaintainCompactQueue() = runTest {
        val dao = FakeFocusTaskDao()
        val repository = FocusTaskRepository(dao)
        val first = repository.add("First", 2)
        val second = repository.add("Second", 1)
        val third = repository.add("Third", 1)

        repository.move(third.id, -1)
        assertEquals(listOf(first.id, third.id, second.id), repository.pending().map { it.id })

        assertFalse(repository.recordCompletedFocus(first.id))
        assertTrue(repository.recordCompletedFocus(first.id))
        assertEquals(listOf(third.id, second.id), repository.pending().map { it.id })

        repository.skip(third.id)
        assertEquals(listOf(second.id), repository.pending().map { it.id })
    }

    @Test
    fun deletionDoesNotRequireHistoricalSessionMutation() = runTest {
        val dao = FakeFocusTaskDao()
        val repository = FocusTaskRepository(dao)
        val task = repository.add("Disposable", 1)

        repository.delete(task.id)

        assertTrue(repository.pending().isEmpty())
    }
}

private class FakeFocusTaskDao : FocusTaskDao {
    private val tasks = linkedMapOf<String, FocusTaskEntity>()
    private val observed = MutableStateFlow<List<FocusTaskEntity>>(emptyList())

    override fun observePending(): Flow<List<FocusTaskEntity>> = observed
    override suspend fun pending(): List<FocusTaskEntity> = tasks.values
        .filter { it.status == FocusTaskEntity.STATUS_PENDING }
        .sortedWith(compareBy(FocusTaskEntity::sortOrder, FocusTaskEntity::createdAt))

    override suspend fun upsert(task: FocusTaskEntity) = update { tasks[task.id] = task }
    override suspend fun update(task: FocusTaskEntity) = update { tasks[task.id] = task }
    override suspend fun delete(id: String) = update { tasks.remove(id) }
    override suspend fun maxPendingOrder(): Int = pending().maxOfOrNull { it.sortOrder } ?: -1

    private inline fun update(block: () -> Unit) {
        block()
        observed.value = tasks.values.filter { it.status == FocusTaskEntity.STATUS_PENDING }
            .sortedWith(compareBy(FocusTaskEntity::sortOrder, FocusTaskEntity::createdAt))
    }
}
