package com.example.core.goal

import com.example.core.timer.FocusInterval
import com.example.data.entity.SessionLogEntity
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class DailyFocusGoalTest {
    private val settings = DailyFocusGoalSettings(enabled = true, timeZoneId = "UTC")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    private fun session(start: String, minutes: Int, completed: Boolean = true) = SessionLogEntity(
        sessionType = "FOCUS",
        startTimeMillis = time(start),
        durationSeconds = minutes * 60,
        status = if (completed) "COMPLETED" else "INTERRUPTED",
        timestamp = time(start) + minutes * 60_000L
    )

    @Test
    fun splitsFocusAcrossMidnightAndCountsCompletionOnItsEndDay() {
        val sessions = listOf(session("2026-10-18T23:50:00Z", 20))
        val result = DailyFocusCalculator.calculate(sessions, settings, time("2026-10-19T00:30:00Z"))
        assertEquals(600L, result.focusedSeconds)
        assertEquals(1, result.completedSessions)
        assertEquals(10L, result.value)
    }

    @Test
    fun excludesBreaksAndPausedTimeAndOnlyCountsRealCompletions() {
        val sessions = listOf(
            session("2026-10-19T08:00:00Z", 5, false),
            session("2026-10-19T09:00:00Z", 20),
            session("2026-10-19T10:00:00Z", 10).copy(sessionType = "SHORT_BREAK")
        )
        val now = time("2026-10-19T11:00:00Z")
        val minutes = DailyFocusCalculator.calculate(sessions, settings.copy(targetMinutes = 10), now)
        val count = DailyFocusCalculator.calculate(sessions, settings.copy(type = FocusTargetType.SESSIONS), now)
        assertEquals(25L, minutes.value)
        assertEquals(1L, count.value)
        assertEquals(1f, minutes.fraction)
        assertEquals("25/10m", minutes.shortText)
        assertEquals(3, sessions.size)
    }

    @Test
    fun resetHourDefinesHalfOpenDayAndEditsLeaveHistoryIntact() {
        val sessions = listOf(session("2026-10-19T03:50:00Z", 10))
        val copy = sessions.toList()
        val resetSettings = settings.copy(resetHour = 4)
        val before = resetSettings.dayAt(time("2026-10-19T03:59:00Z"))
        val after = resetSettings.dayAt(time("2026-10-19T04:00:00Z"))
        assertEquals(before.endEpochMs, after.startEpochMs)
        val result = DailyFocusCalculator.calculate(sessions, resetSettings, time("2026-10-19T04:00:00Z"))
        assertEquals(0L, result.focusedSeconds)
        assertEquals(1, result.completedSessions)
        assertEquals(copy, sessions)
    }

    @Test
    fun daylightSavingDaysUseCalendarBoundariesInsteadOfTwentyFourHours() {
        val newYork = settings.copy(timeZoneId = "America/New_York")
        val spring = newYork.dayAt(time("2026-03-08T16:00:00Z"))
        val autumn = newYork.dayAt(time("2026-11-01T16:00:00Z"))
        assertEquals(23 * 60 * 60_000L, spring.endEpochMs - spring.startEpochMs)
        assertEquals(25 * 60 * 60_000L, autumn.endEpochMs - autumn.startEpochMs)
    }

    @Test
    fun timezoneChangeRecalculatesPeriodWithoutRewritingHistory() {
        val sessions = listOf(session("2026-10-18T23:00:00Z", 25))
        val now = time("2026-10-19T00:30:00Z")
        assertEquals(0L, DailyFocusCalculator.calculate(sessions, settings, now).value)
        assertEquals(25L, DailyFocusCalculator.calculate(sessions, settings.copy(timeZoneId = "America/Los_Angeles"), now).value)
    }

    @Test
    fun runningFocusAddsMinutesButNeverCompletesASession() {
        val now = time("2026-10-19T12:10:00Z")
        val result = DailyFocusCalculator.calculate(
            emptyList(), settings, now, FocusInterval(now - 5 * 60_000L, durationSeconds = 300)
        )
        assertEquals(5L, result.value)
        assertEquals(0, result.completedSessions)
        assertFalse(settings.copy(enabled = false).enabled)
    }
}
