package com.example.domain

import com.example.data.entity.IncidentStatus
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.system.measureNanoTime

class InsightsCalculatorTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = at("2026-10-19 12:00")

    @Test
    fun documentedMetricsIncludeInterruptedWorkButOnlyPanicRecovery() {
        val result = calculate(
            listOf(session("2026-10-19 09:00", 1500), session("2026-10-19 10:00", 600, "INTERRUPTED"),
                session("2026-10-19 11:00", 300).copy(sessionType = "SHORT_BREAK")),
            listOf(
                incident("2026-10-19 10:01", 60, "Notification"),
                incident("2026-10-19 10:03", reason = "Notification"),
                incident("2026-10-19 10:04", 0, "Boredom").copy(incidentType = "DISTRACTION")
            )
        )
        assertEquals(35L, result.focusMinutes)
        assertEquals(1, result.completedFocusSessions)
        assertEquals(2, result.endedFocusIntervals)
        assertEquals(50, result.completionRatePercent)
        assertEquals(1050L, result.averageUninterruptedSeconds)
        assertEquals(1, result.rapidRecoveries)
        assertEquals(60L, result.averageRecoverySeconds)
        assertEquals(10L, result.resilienceScore)
        assertEquals(listOf(IncidentCategoryCount("Notification", 2), IncidentCategoryCount("Boredom", 1)), result.categories)
    }

    @Test
    fun emptyAndIncidentOnlyDatasetsDoNotInventAveragesOrCompletionRates() {
        val empty = calculate(emptyList())
        assertEquals(0L, empty.focusMinutes)
        assertNull(empty.completionRatePercent)
        assertNull(empty.averageUninterruptedSeconds)
        assertNull(empty.averageRecoverySeconds)
        assertTrue(empty.history.isEmpty())
        assertTrue(empty.buckets.all { it.focusSeconds == 0L })
        val incidentOnly = calculate(emptyList(), listOf(incident("2026-10-19 10:00", 90)))
        assertEquals(90L, incidentOnly.averageRecoverySeconds)
        assertEquals(1, incidentOnly.incidentCount)
        assertNull(incidentOnly.completionRatePercent)
    }

    @Test
    fun clipsFocusAtDateBoundariesAndKeepsPartialWork() {
        val sessions = listOf(session("2026-10-18 23:50", 1200), session("2026-10-19 09:00", 1800))
        val today = calculate(sessions, filter = InsightsFilter(InsightsPeriod.TODAY))
        assertEquals(40L, today.focusMinutes)
        assertEquals(2, today.completedFocusSessions)
        assertEquals(40L * 60, today.buckets.single().focusSeconds)
        assertEquals(50L, calculate(sessions).focusMinutes)

        val previous = calculate(sessions, filter = custom("2026-10-18", "2026-10-18"))
        assertEquals(10L, previous.focusMinutes)
        assertEquals(0, previous.endedFocusIntervals)
        assertEquals(1, previous.history.size)
        assertNull(previous.averageUninterruptedSeconds)
    }

    @Test
    fun taskAndIncidentCategoryFiltersHaveIndependentScopes() {
        val sessions = listOf(session("2026-10-19 09:00", 600).copy(taskName = "Build"),
            session("2026-10-19 10:00", 300).copy(taskName = "Read"),
            session("2026-10-19 11:00", 60).copy(taskName = " "))
        val incidents = listOf(
            incident("2026-10-19 09:20", 90, "Notification").copy(taskName = "Build"),
            incident("2026-10-19 09:30", 20, "Boredom").copy(taskName = "Build"),
            incident("2026-10-19 10:20", 10, "Notification").copy(taskName = "Read")
        )
        val result = calculate(sessions, incidents, InsightsFilter(taskName = "Build", incidentCategory = "Notification"))
        assertEquals(10L, result.focusMinutes)
        assertEquals(1, result.history.size)
        assertEquals(1, result.incidentCount)
        assertEquals(90L, result.averageRecoverySeconds)
        assertEquals(listOf("", "Build", "Read"), result.taskNames)
        assertEquals(listOf("Boredom", "Notification"), result.categoryNames)
        assertEquals(1L, calculate(sessions, filter = InsightsFilter(taskName = "")).focusMinutes)
    }

    @Test
    fun invalidFutureAndDismissedRecordsCannotCreateFastRecoveries() {
        val incidents = listOf(
            incident("2026-10-19 10:00", -1),
            incident("2026-10-19 11:59", 120),
            incident("2026-10-19 10:00", 60).copy(incidentStatus = IncidentStatus.DISMISSED),
            incident("2026-10-19 13:00", 30),
            incident("2026-10-19 10:00", 1).copy(incidentType = "FRUSTRATION"),
            incident("2026-10-19 10:00", 1).copy(incidentType = "PROTECTION_OVERRIDE")
        )
        val result = calculate(listOf(session("2026-10-19 13:00", 600), session("2026-10-19 10:00", -10)), incidents)
        assertEquals(0L, result.focusMinutes)
        assertNull(result.averageUninterruptedSeconds)
        assertNull(result.averageRecoverySeconds)
        assertEquals(0, result.rapidRecoveries)
        assertEquals(4, result.incidentCount)
    }

    @Test
    fun datePresetsIncludeTheirFirstDayButNotThePrecedingDay() {
        val sessions = listOf("2026-09-19", "2026-09-20", "2026-10-12", "2026-10-13")
            .map { session("$it 09:00", 60) }
        assertEquals(1L, calculate(sessions).focusMinutes)
        assertEquals(3L, calculate(sessions, filter = InsightsFilter(InsightsPeriod.LAST_30_DAYS)).focusMinutes)
        assertEquals(4L, calculate(sessions, filter = InsightsFilter(InsightsPeriod.ALL_TIME)).focusMinutes)
        assertEquals(0L, calculate(sessions, filter = InsightsFilter(InsightsPeriod.TODAY)).focusMinutes)
    }

    @Test
    fun exactlyThreeMinutesIsNotAFastRecovery() {
        val result = calculate(emptyList(), listOf(incident("2026-10-19 09:00", 179),
            incident("2026-10-19 10:00", 180)))
        assertEquals(1, result.rapidRecoveries)
        assertEquals(179L, result.averageRecoverySeconds)
    }

    @Test
    fun localDatesRespectBothDstBoundariesAndMondayWeeks() {
        val zone = TimeZone.getTimeZone("America/New_York")
        listOf("2026-03-08" to 23, "2026-11-01" to 25).forEach { (date, hours) ->
            val start = at("$date 00:00", zone)
            val row = SessionLogEntity(sessionType = "FOCUS", startTimeMillis = start,
                durationSeconds = hours * 3600, status = "COMPLETED", timestamp = start + hours * 3_600_000L)
            val result = InsightsCalculator.calculate(listOf(row), emptyList(), custom(date, date), row.timestamp + 60_000, zone)
            assertEquals(zone.id, result.timeZoneId)
            assertEquals(hours * 60L, result.focusMinutes)
            assertEquals(hours * 3_600_000L, result.toEpochMs - result.fromEpochMs)
            assertEquals(hours * 3600L, result.buckets.single().focusSeconds)
        }
        val weekly = calculate(listOf(session("2026-10-18 09:00", 600), session("2026-10-19 09:00", 1200)),
            filter = InsightsFilter(period = InsightsPeriod.ALL_TIME, weekly = true))
        assertEquals(at("2026-10-19 00:00"), weekly.buckets.last().startEpochMs)
        assertEquals(1200L, weekly.buckets.last().focusSeconds)
        assertEquals(600L, weekly.buckets[weekly.buckets.lastIndex - 1].focusSeconds)
    }

    @Test
    fun tenThousandRecordsAreCompleteAndAggregateWithinInteractiveBudget() {
        val start = at("2025-01-01 00:00")
        val sessions = List(10_000) { index ->
            SessionLogEntity(id = index + 1, sessionType = "FOCUS", startTimeMillis = start + index * 3_600_000L,
                durationSeconds = 1500, status = if (index % 2 == 0) "COMPLETED" else "INTERRUPTED",
                timestamp = start + index * 3_600_000L + 1_500_000L)
        }
        var result: FocusInsights? = null
        val elapsedMs = measureNanoTime {
            result = InsightsCalculator.calculate(sessions, emptyList(), InsightsFilter(InsightsPeriod.ALL_TIME), now, utc)
        } / 1_000_000
        assertEquals(10_000, result!!.history.size)
        assertEquals(250_000L, result!!.focusMinutes)
        assertEquals(5000, result!!.completedFocusSessions)
        assertEquals(50, result!!.completionRatePercent)
        assertEquals(10_000, result!!.history.first().id)
        assertTrue("10k aggregation took ${elapsedMs}ms", elapsedMs < 2000)
        println("Insights 10,000 records: ${elapsedMs}ms")
    }

    @Test
    fun largeDurationsUseLongSums() {
        val sessions = List(10_000) { SessionLogEntity(sessionType = "FOCUS", startTimeMillis = 0,
            durationSeconds = Int.MAX_VALUE, status = "COMPLETED", timestamp = Int.MAX_VALUE * 1000L) }
        val result = InsightsCalculator.calculate(sessions, emptyList(), InsightsFilter(InsightsPeriod.ALL_TIME),
            at("2100-01-01 00:00"), utc)
        assertEquals(Int.MAX_VALUE.toLong() * 10_000, result.focusSeconds)
        assertEquals(Int.MAX_VALUE.toLong(), result.averageUninterruptedSeconds)
    }

    private fun calculate(sessions: List<SessionLogEntity>, incidents: List<PanicLogEntity> = emptyList(),
        filter: InsightsFilter = InsightsFilter()) = InsightsCalculator.calculate(sessions, incidents, filter, now, utc)

    private fun session(start: String, seconds: Int, status: String = "COMPLETED") = SessionLogEntity(
        sessionType = "FOCUS", startTimeMillis = at(start), durationSeconds = seconds, status = status,
        timestamp = at(start) + seconds.coerceAtLeast(0) * 1000L)

    private fun incident(start: String, recoverySeconds: Int? = null, reason: String? = null) = PanicLogEntity(
        timestamp = at(start), resolvedAt = recoverySeconds?.let { at(start) + it * 1000L }, triggerReason = reason)

    private fun custom(start: String, end: String) = InsightsFilter(InsightsPeriod.CUSTOM,
        customStartUtcMs = at("$start 00:00"), customEndUtcMs = at("$end 00:00"))

    private fun at(value: String, zone: TimeZone = utc) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .apply { timeZone = zone }.parse(value)!!.time
}
