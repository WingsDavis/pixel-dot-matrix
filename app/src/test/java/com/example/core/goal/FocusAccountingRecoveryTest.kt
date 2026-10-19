package com.example.core.goal

import com.example.core.sync.TimerAuthority
import com.example.core.sync.TimerSnapshot
import com.example.core.timer.*
import org.junit.Assert.*
import org.junit.Test

class FocusAccountingRecoveryTest {
    private val startedAt = 1_790_000_000_000L
    private val interval = FocusInterval(startedAt, id = "stable-focus-id")
    private val snapshot = TimerSnapshot(
        revision = 1,
        sessionId = "session",
        sourceDevice = "phone",
        authority = TimerAuthority.PHONE,
        state = PomodoroState.FOCUS,
        secondsRemaining = 1500,
        isRunning = true,
        updatedAtEpochMs = startedAt,
        anchorElapsedRealtimeMs = 0,
        activeFocusInterval = interval
    )

    @Test
    fun processRecreationRetainsFirstTenMinutesWithTheSameIdentity() {
        val recovered = TimerAutomationReconciler.reconcile(snapshot, TimerSettings(), startedAt + 600_000)
        assertEquals(600, recovered.activeFocusInterval?.durationSeconds)
        assertEquals(interval.id, recovered.activeFocusInterval?.id)
        assertEquals(900, recovered.secondsRemaining)
        val progress = DailyFocusCalculator.calculate(
            emptyList(), DailyFocusGoalSettings(enabled = true, timeZoneId = "UTC"), startedAt + 600_000,
            recovered.activeFocusInterval
        )
        assertEquals(10L, progress.value)
    }

    @Test
    fun recoveredCompletionHasStableIdentityAndExactFocusDuration() {
        val first = TimerAutomationReconciler.reconcile(snapshot, TimerSettings(), startedAt + 1_600_000)
        val replay = TimerAutomationReconciler.reconcile(snapshot, TimerSettings(), startedAt + 1_600_000)
        assertEquals(listOf(interval.copy(durationSeconds = 1500, completed = true)), first.focusIntervals)
        assertEquals(first.focusIntervals, replay.focusIntervals)
        assertEquals(PomodoroState.SHORT_BREAK, first.state)
        assertEquals(startedAt + 1_500_000, first.transitions.single().occurredAtEpochMs)
    }

    @Test
    fun pendingPauseWinsOverAnOlderRunningSnapshotWithoutDoubleCounting() {
        val paused = interval.copy(durationSeconds = 600)
        val recovered = TimerAutomationReconciler.reconcile(snapshot, TimerSettings(), startedAt + 610_000, listOf(paused))
        assertFalse(recovered.isRunning)
        assertEquals(900, recovered.secondsRemaining)
        assertNull(recovered.activeFocusInterval)
        assertTrue(recovered.focusIntervals.isEmpty())
    }

    @Test
    fun pendingCompletionReconcilesOnceFromAnOlderFocusSnapshot() {
        val finished = interval.copy(durationSeconds = 1500, completed = true)
        val recovered = TimerAutomationReconciler.reconcile(snapshot, TimerSettings(), startedAt + 1_600_000, listOf(finished))
        assertEquals(listOf(finished), recovered.focusIntervals)
        assertEquals(PomodoroState.SHORT_BREAK, recovered.state)
        assertEquals(1, recovered.completedFocusSessions)
    }
}
