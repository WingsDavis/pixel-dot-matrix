package com.example.core.sync

import com.example.data.entity.SyncOutboxEntity
import java.util.Base64

data class SyncAppliedAck(
    val id: String,
    val applied: Boolean,
    val message: String? = null
) {
    fun encode(): ByteArray {
        val encodedMessage = Base64.getEncoder().encodeToString(message.orEmpty().toByteArray())
        return "$id|${if (applied) "APPLIED" else "FAILED"}|$encodedMessage".toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): SyncAppliedAck? {
            val raw = bytes.toString(Charsets.UTF_8)
            val parts = raw.split('|', limit = 3)
            if (parts.size == 1 && raw.isNotBlank()) return SyncAppliedAck(raw, true)
            if (parts.size != 3 || parts[0].isBlank()) return null
            val message = runCatching { String(Base64.getDecoder().decode(parts[2])) }.getOrNull()
            return SyncAppliedAck(parts[0], parts[1] == "APPLIED", message?.ifBlank { null })
        }
    }
}

object SyncDeliveryPolicy {
    const val MAX_RETRY_COUNT = 5
    const val APPLIED_RETENTION_MS = 24 * 60 * 60 * 1_000L
    private const val ACK_TIMEOUT_MS = 15_000L
    private const val BASE_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 60_000L

    fun shouldAttempt(item: SyncOutboxEntity, now: Long): Boolean {
        if (item.status == SyncOutboxEntity.STATUS_FAILED || item.status == SyncOutboxEntity.STATUS_APPLIED) return false
        val waitMs = if (item.status == SyncOutboxEntity.STATUS_DELIVERED || item.status == SyncOutboxEntity.STATUS_SENT) {
            ACK_TIMEOUT_MS
        } else {
            (BASE_BACKOFF_MS shl item.retryCount.coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)
        }
        return (item.status == SyncOutboxEntity.STATUS_PENDING && item.retryCount == 0) ||
            now - item.updatedAt >= waitMs
    }
}
