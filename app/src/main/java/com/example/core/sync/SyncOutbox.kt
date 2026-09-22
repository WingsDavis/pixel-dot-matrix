package com.example.core.sync

import com.example.data.database.SyncOutboxDao
import com.example.data.entity.SyncOutboxEntity
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class SyncOutbox(
    private val dao: SyncOutboxDao,
    private val transport: SyncTransport
) {
    constructor(dao: SyncOutboxDao, nodeClient: NodeClient, messageClient: MessageClient) : this(
        dao,
        GoogleWearSyncTransport(nodeClient, messageClient)
    )

    private val flushMutex = Mutex()

    suspend fun enqueue(path: String, payload: ByteArray, coalesceKey: String? = null): String {
        if (coalesceKey != null) dao.deletePendingByCoalesceKey(coalesceKey)
        val id = UUID.randomUUID().toString()
        dao.upsert(
            SyncOutboxEntity(
                id = id,
                path = path,
                payload = payload,
                coalesceKey = coalesceKey
            )
        )
        flush()
        return id
    }

    suspend fun flush() = flushMutex.withLock {
        dao.migrateSentToDelivered()
        val nodes = transport.connectedNodeIds()
        if (nodes.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        dao.pending().filter { SyncDeliveryPolicy.shouldAttempt(it, now) }.forEach { item ->
            runCatching {
                val envelope = SyncMessageEnvelope(item.id, item.payload).encode()
                nodes.forEach { nodeId -> transport.send(nodeId, item.path, envelope) }
            }.onSuccess {
                dao.markDelivered(item.id)
            }.onFailure { error ->
                val retryCount = item.retryCount + 1
                dao.markAttemptFailure(
                    item.id,
                    if (retryCount >= SyncDeliveryPolicy.MAX_RETRY_COUNT) {
                        SyncOutboxEntity.STATUS_FAILED
                    } else {
                        SyncOutboxEntity.STATUS_PENDING
                    },
                    retryCount,
                    error.message ?: error.javaClass.simpleName
                )
            }
        }
        dao.pruneDelivered(System.currentTimeMillis() - SyncDeliveryPolicy.APPLIED_RETENTION_MS)
    }

    suspend fun markApplied(id: String) = dao.markApplied(id)

    suspend fun markFailed(id: String, reason: String?) = dao.markAttemptFailure(
        id,
        SyncOutboxEntity.STATUS_FAILED,
        SyncDeliveryPolicy.MAX_RETRY_COUNT,
        reason ?: "Watch could not apply this operation"
    )

    suspend fun retryFailed() {
        dao.retryFailed()
        flush()
    }
}

interface SyncTransport {
    suspend fun connectedNodeIds(): List<String>
    suspend fun send(nodeId: String, path: String, payload: ByteArray)
}

private class GoogleWearSyncTransport(
    private val nodeClient: NodeClient,
    private val messageClient: MessageClient
) : SyncTransport {
    override suspend fun connectedNodeIds(): List<String> = nodeClient.connectedNodes.await().map { it.id }

    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        messageClient.sendMessage(nodeId, path, payload).await()
    }
}
