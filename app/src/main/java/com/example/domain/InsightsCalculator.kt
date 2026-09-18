package com.example.domain

import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity

data class FocusInsights(
    val completedFocusSessions: Int,
    val focusMinutes: Int,
    val completionRatePercent: Int,
    val averageUninterruptedMinutes: Int,
    val rapidRecoveries: Int,
    val averageRecoverySeconds: Int?,
    val resilienceScore: Int,
    val topDistractionReason: String?
)

object InsightsCalculator {
    fun calculate(
        sessions: List<SessionLogEntity>,
        incidents: List<PanicLogEntity>,
        fromEpochMs: Long = Long.MIN_VALUE,
        toEpochMs: Long = Long.MAX_VALUE
    ): FocusInsights {
        val focus = sessions.asSequence()
            .filter { it.sessionType == "FOCUS" && it.timestamp in fromEpochMs..toEpochMs }
            .toList()
        val completed = focus.filter { it.status == "COMPLETED" }
        val relevantIncidents = incidents.filter { it.timestamp in fromEpochMs..toEpochMs }
        val recoverySeconds = relevantIncidents.mapNotNull { incident ->
            incident.resolvedAt?.let { ((it - incident.timestamp).coerceAtLeast(0L) / 1000L).toInt() }
        }
        val rapidRecoveries = recoverySeconds.count { it < 180 }
        val resilience = (
            completed.size * 10 + rapidRecoveries * 5 - relevantIncidents.count { it.resolvedAt == null } * 5
        ).coerceAtLeast(0)

        return FocusInsights(
            completedFocusSessions = completed.size,
            focusMinutes = completed.sumOf { it.durationSeconds } / 60,
            completionRatePercent = if (focus.isEmpty()) 0 else completed.size * 100 / focus.size,
            averageUninterruptedMinutes = if (completed.isEmpty()) 0 else completed.sumOf { it.durationSeconds } / completed.size / 60,
            rapidRecoveries = rapidRecoveries,
            averageRecoverySeconds = recoverySeconds.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            resilienceScore = resilience,
            topDistractionReason = relevantIncidents.mapNotNull { it.triggerReason?.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        )
    }
}
