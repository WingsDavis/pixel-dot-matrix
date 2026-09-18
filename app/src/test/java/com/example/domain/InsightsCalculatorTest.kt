package com.example.domain

import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsCalculatorTest {
    @Test
    fun calculatesDocumentedFocusAndRecoveryMetrics() {
        val sessions = listOf(
            SessionLogEntity(sessionType = "FOCUS", startTimeMillis = 0, durationSeconds = 1500, status = "COMPLETED", timestamp = 100),
            SessionLogEntity(sessionType = "FOCUS", startTimeMillis = 0, durationSeconds = 600, status = "INTERRUPTED", timestamp = 200),
            SessionLogEntity(sessionType = "SHORT_BREAK", startTimeMillis = 0, durationSeconds = 300, status = "COMPLETED", timestamp = 300)
        )
        val incidents = listOf(
            PanicLogEntity(timestamp = 400, resolvedAt = 60_400, triggerReason = "Notification"),
            PanicLogEntity(timestamp = 500, triggerReason = "Notification")
        )

        val result = InsightsCalculator.calculate(sessions, incidents)

        assertEquals(1, result.completedFocusSessions)
        assertEquals(25, result.focusMinutes)
        assertEquals(50, result.completionRatePercent)
        assertEquals(25, result.averageUninterruptedMinutes)
        assertEquals(1, result.rapidRecoveries)
        assertEquals(60, result.averageRecoverySeconds)
        assertEquals(10, result.resilienceScore)
        assertEquals("Notification", result.topDistractionReason)
    }

    @Test
    fun emptyDataProducesStableZeroMetrics() {
        val result = InsightsCalculator.calculate(emptyList(), emptyList())
        assertEquals(0, result.completionRatePercent)
        assertEquals(null, result.averageRecoverySeconds)
        assertEquals(null, result.topDistractionReason)
    }
}
