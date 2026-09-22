package com.example.core.sync

import com.example.data.database.SyncOutboxDao
import com.example.data.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncOutboxIntegrationTest {
    @Test
    fun disconnectedOperationDeliversOnceAndStopsAfterAppliedAck() = runTest {
        val dao = FakeSyncOutboxDao()
        val transport = FakeSyncTransport()
        val outbox = SyncOutbox(dao, transport)

        val id = outbox.enqueue("/test", "payload".toByteArray(), "latest-test")
        assertEquals(SyncOutboxEntity.STATUS_PENDING, dao.item(id)?.status)

        transport.connected = true
        outbox.flush()
        assertEquals(SyncOutboxEntity.STATUS_DELIVERED, dao.item(id)?.status)
        assertEquals(1, transport.sent.size)
        assertEquals(id, SyncMessageEnvelope.decode(transport.sent.single())?.id)

        outbox.flush()
        assertEquals(1, transport.sent.size)

        outbox.markApplied(id)
        outbox.markApplied(id)
        outbox.flush()
        assertEquals(SyncOutboxEntity.STATUS_APPLIED, dao.item(id)?.status)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun newerCoalescedOperationReplacesOfflinePendingValue() = runTest {
        val dao = FakeSyncOutboxDao()
        val outbox = SyncOutbox(dao, FakeSyncTransport())

        outbox.enqueue("/config", "old".toByteArray(), "config")
        val latestId = outbox.enqueue("/config", "new".toByteArray(), "config")

        assertEquals(listOf(latestId), dao.pending().map { it.id })
    }
}

private class FakeSyncTransport : SyncTransport {
    var connected = false
    val sent = mutableListOf<ByteArray>()

    override suspend fun connectedNodeIds(): List<String> = if (connected) listOf("watch") else emptyList()

    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        sent += payload
    }
}

private class FakeSyncOutboxDao : SyncOutboxDao {
    private val items = linkedMapOf<String, SyncOutboxEntity>()
    private val observed = MutableStateFlow<List<SyncOutboxEntity>>(emptyList())

    fun item(id: String) = items[id]

    override suspend fun upsert(item: SyncOutboxEntity) = update { items[item.id] = item }
    override suspend fun pending() = items.values.filter { it.status in setOf("PENDING", "FAILED", "SENT", "DELIVERED") }
    override fun observeAll(): Flow<List<SyncOutboxEntity>> = observed
    override suspend fun deletePendingByCoalesceKey(key: String) = update {
        items.entries.removeAll { it.value.coalesceKey == key && it.value.status in setOf("PENDING", "FAILED", "SENT", "DELIVERED") }
    }
    override suspend fun updateStatus(id: String, status: String, retryCount: Int, error: String?, updatedAt: Long) = update {
        items[id]?.let { items[id] = it.copy(status = status, retryCount = retryCount, lastError = error, updatedAt = updatedAt) }
    }
    override suspend fun markDelivered(id: String, updatedAt: Long) = update {
        items[id]?.takeIf { it.status != "APPLIED" }?.let { items[id] = it.copy(status = "DELIVERED", lastError = null, updatedAt = updatedAt) }
    }
    override suspend fun markAttemptFailure(id: String, status: String, retryCount: Int, error: String?, updatedAt: Long) = update {
        items[id]?.takeIf { it.status != "APPLIED" }?.let {
            items[id] = it.copy(status = status, retryCount = retryCount, lastError = error, updatedAt = updatedAt)
        }
    }
    override suspend fun markApplied(id: String, updatedAt: Long) = update {
        items[id]?.let { items[id] = it.copy(status = "APPLIED", lastError = null, updatedAt = updatedAt) }
    }
    override suspend fun retryFailed(updatedAt: Long) = update {
        items.replaceAll { _, item ->
            if (item.status == "FAILED") item.copy(status = "PENDING", retryCount = 0, lastError = null, updatedAt = updatedAt) else item
        }
    }
    override suspend fun migrateSentToDelivered() = update {
        items.replaceAll { _, item -> if (item.status == "SENT") item.copy(status = "DELIVERED") else item }
    }
    override suspend fun pruneDelivered(before: Long) = update {
        items.entries.removeAll { it.value.status == "APPLIED" && it.value.updatedAt < before }
    }
    override suspend fun clearAll() = update { items.clear() }

    private inline fun update(block: () -> Unit) {
        block()
        observed.value = items.values.toList()
    }
}
