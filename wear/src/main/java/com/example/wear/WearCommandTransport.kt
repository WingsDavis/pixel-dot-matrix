package com.example.wear

import android.net.Uri
import com.example.core.sync.TimerCommand
import com.example.core.sync.WearCommandProtocol
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import java.util.concurrent.TimeUnit

internal interface WearCommandTransport {
    fun publish(id: String, path: String, payload: ByteArray, createdAt: Long, attempt: Int, updatedAt: Long)
    fun sendLegacy(path: String, payload: ByteArray, createdAt: Long): Boolean
    fun remove(id: String) {}
}

internal class GoogleWearCommandTransport(
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
    private val messageClient: MessageClient
) : WearCommandTransport {
    override fun publish(id: String, path: String, payload: ByteArray, createdAt: Long, attempt: Int, updatedAt: Long) {
        Tasks.await(dataClient.putDataItem(request(id, path, payload, createdAt, attempt, updatedAt)), 10, TimeUnit.SECONDS)
    }

    override fun sendLegacy(path: String, payload: ByteArray, createdAt: Long): Boolean {
        val message = legacyPayload(path, payload, createdAt)
        val nodes = Tasks.await(nodeClient.connectedNodes, 10, TimeUnit.SECONDS)
        var delivered = false
        nodes.forEach { node ->
            if (runCatching {
                Tasks.await(messageClient.sendMessage(node.id, path, message), 10, TimeUnit.SECONDS)
            }.isSuccess) delivered = true
        }
        return delivered
    }

    override fun remove(id: String) {
        removalUris(id).forEach { uri ->
            Tasks.await(dataClient.deleteDataItems(uri, DataClient.FILTER_LITERAL), 10, TimeUnit.SECONDS)
        }
    }

    companion object {
        internal fun legacyPayload(path: String, payload: ByteArray, createdAt: Long): ByteArray {
            if (path != "/incident/mark" || createdAt <= 0L) return payload
            val command = requireNotNull(TimerCommand.decode(payload)) { "Invalid incident command" }
            require(command.action == "DISTRACTION") { "Invalid incident action" }
            return command.copy(action = "DISTRACTION|$createdAt").encode()
        }

        internal fun removalUris(id: String): List<Uri> {
            require(WearCommandProtocol.validId(id)) { "Invalid command ID" }
            return listOf(WearCommandProtocol.COMMAND_PREFIX, WearCommandProtocol.ACK_PREFIX).map {
                Uri.Builder().scheme("wear").authority("*").path(it + id).build()
            }
        }

        internal fun request(
            id: String, path: String, payload: ByteArray, createdAt: Long, attempt: Int, updatedAt: Long
        ): PutDataRequest = PutDataMapRequest.create(WearCommandProtocol.COMMAND_PREFIX + id).apply {
            dataMap.putInt("schema_version", WearCommandProtocol.SCHEMA_VERSION)
            dataMap.putString("command_id", id)
            dataMap.putString("path", path)
            dataMap.putByteArray("payload", payload)
            dataMap.putLong("created_at", createdAt)
            dataMap.putInt("attempt", attempt)
            dataMap.putLong("updated_at", updatedAt)
        }.asPutDataRequest().setUrgent()
    }
}
