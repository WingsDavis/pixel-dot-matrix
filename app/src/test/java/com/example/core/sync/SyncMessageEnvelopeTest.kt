package com.example.core.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class SyncMessageEnvelopeTest {
    @Test
    fun preservesIdAndPayloadIncludingNewlines() {
        val envelope = SyncMessageEnvelope("item-1", "line one\nline two".toByteArray())
        val decoded = SyncMessageEnvelope.decode(envelope.encode())!!
        assertEquals("item-1", decoded.id)
        assertArrayEquals(envelope.payload, decoded.payload)
    }

    @Test
    fun ignoresLegacyPayloads() {
        assertNull(SyncMessageEnvelope.decode("legacy".toByteArray()))
    }

    @Test
    fun appliedAcknowledgementPreservesFailureReason() {
        val acknowledgement = SyncAppliedAck("item-2", false, "Unsupported payload")
        assertEquals(acknowledgement, SyncAppliedAck.decode(acknowledgement.encode()))
    }

    @Test
    fun legacyAcknowledgementMeansApplied() {
        assertEquals(SyncAppliedAck("item-3", true), SyncAppliedAck.decode("item-3".toByteArray()))
    }

    @Test fun unsupportedAndMalformedAcknowledgementsAreNotApplied() {
        listOf("item-3|UNKNOWN|", "item-3|APPLIED|%%%x", "|APPLIED|", "bad/id|APPLIED|").forEach {
            assertNull(SyncAppliedAck.decode(it.toByteArray()))
        }
    }
}
