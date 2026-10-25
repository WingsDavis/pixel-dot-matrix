package com.example.core.sync

import com.example.data.entity.SyncOutboxEntity

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
