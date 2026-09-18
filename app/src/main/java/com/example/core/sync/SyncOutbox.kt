package com.example.core.sync

import com.example.data.database.SyncOutboxDao
import com.example.data.entity.SyncOutboxEntity
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import kotlinx.coroutines.tasks.await
import java.util.UUID

class SyncOutbox(
    private val dao: SyncOutboxDao,
    private val nodeClient: NodeClient,
    private val messageClient: MessageClient
) {
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

    suspend fun flush() {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) return
        dao.pending().forEach { item ->
            runCatching {
                nodes.forEach { node -> messageClient.sendMessage(node.id, item.path, item.payload).await() }
            }.onSuccess {
                dao.updateStatus(item.id, SyncOutboxEntity.STATUS_DELIVERED, item.retryCount, null)
            }.onFailure { error ->
                dao.updateStatus(
                    item.id,
                    SyncOutboxEntity.STATUS_FAILED,
                    item.retryCount + 1,
                    error.message ?: error.javaClass.simpleName
                )
            }
        }
        dao.pruneDelivered(System.currentTimeMillis() - DELIVERED_RETENTION_MS)
    }

    companion object {
        private const val DELIVERED_RETENTION_MS = 24 * 60 * 60 * 1000L
    }
}
