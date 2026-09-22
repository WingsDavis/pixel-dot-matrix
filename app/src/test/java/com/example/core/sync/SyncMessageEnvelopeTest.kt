package com.example.core.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
