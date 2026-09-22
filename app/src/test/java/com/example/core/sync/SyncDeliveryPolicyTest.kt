package com.example.core.sync

import com.example.data.entity.SyncOutboxEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDeliveryPolicyTest {
    @Test
    fun pendingItemIsReadyImmediately() {
        assertTrue(SyncDeliveryPolicy.shouldAttempt(item(status = SyncOutboxEntity.STATUS_PENDING), 1_000))
    }

    @Test
    fun deliveredItemWaitsForAppliedAcknowledgementTimeout() {
        val delivered = item(status = SyncOutboxEntity.STATUS_DELIVERED, updatedAt = 1_000)
        assertFalse(SyncDeliveryPolicy.shouldAttempt(delivered, 10_000))
        assertTrue(SyncDeliveryPolicy.shouldAttempt(delivered, 16_000))
    }

    @Test
    fun failedAndAppliedItemsRequireNoAutomaticAttempt() {
        assertFalse(SyncDeliveryPolicy.shouldAttempt(item(status = SyncOutboxEntity.STATUS_FAILED), 100_000))
        assertFalse(SyncDeliveryPolicy.shouldAttempt(item(status = SyncOutboxEntity.STATUS_APPLIED), 100_000))
    }

    @Test
    fun pendingRetryUsesBoundedBackoff() {
        val retry = item(status = SyncOutboxEntity.STATUS_PENDING, retryCount = 2, updatedAt = 1_000)
        assertFalse(SyncDeliveryPolicy.shouldAttempt(retry, 8_999))
        assertTrue(SyncDeliveryPolicy.shouldAttempt(retry, 9_000))
    }

    private fun item(
        status: String,
        retryCount: Int = 0,
        updatedAt: Long = 0
    ) = SyncOutboxEntity(
        id = "id",
        path = "/path",
        payload = byteArrayOf(),
        coalesceKey = null,
        status = status,
        retryCount = retryCount,
        updatedAt = updatedAt
    )
}
