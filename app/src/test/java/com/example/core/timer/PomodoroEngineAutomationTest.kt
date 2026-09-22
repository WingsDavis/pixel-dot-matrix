package com.example.core.timer

import com.example.core.sync.TimerAuthority
import com.example.core.sync.TimerSnapshot
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PomodoroEngineAutomationTest {
    @Test
    fun automaticCompletionUsesConfiguredCadenceAndAutoStarts() = runTest {
        val settings = TimerSettings(
            focusDurationSeconds = 60,
            shortBreakDurationSeconds = 60,
            longBreakDurationSeconds = 300,
            longBreakCadence = 2,
            autoStartBreaks = true,
            autoStartFocus = true
        )
        val engine = PomodoroEngine(this, settings, UnconfinedTestDispatcher(testScheduler))

        engine.completeCurrentInterval()
        assertEquals(PomodoroState.SHORT_BREAK, engine.currentState.value)
        assertTrue(engine.isRunning.value)
        assertEquals(1, engine.completedFocusSessions)

        engine.completeCurrentInterval()
        assertEquals(PomodoroState.FOCUS, engine.currentState.value)
        assertTrue(engine.isRunning.value)

        engine.completeCurrentInterval()
        assertEquals(PomodoroState.LONG_BREAK, engine.currentState.value)
        assertTrue(engine.isRunning.value)
        assertEquals(0, engine.completedFocusSessions)
        engine.pause()
    }

    @Test
    fun manualNextAlwaysUsesExplicitThreePhaseCycle() = runTest {
        val engine = PomodoroEngine(this, timerDispatcher = UnconfinedTestDispatcher(testScheduler))

        engine.skip()
        assertEquals(PomodoroState.SHORT_BREAK, engine.currentState.value)
        engine.skip()
        assertEquals(PomodoroState.LONG_BREAK, engine.currentState.value)
        engine.skip()
        assertEquals(PomodoroState.FOCUS, engine.currentState.value)
        assertFalse(engine.isRunning.value)
    }

    @Test
    fun processRecreationAdvancesAcrossAutoStartedIntervals() {
        val settings = TimerSettings(
            focusDurationSeconds = 60,
            shortBreakDurationSeconds = 60,
            longBreakDurationSeconds = 300,
            autoStartBreaks = true,
            autoStartFocus = true
        )
        val snapshot = snapshot(secondsRemaining = 10, updatedAt = 1_000)

        val result = TimerAutomationReconciler.reconcile(snapshot, settings, nowEpochMs = 76_000)

        assertEquals(PomodoroState.FOCUS, result.state)
        assertEquals(55, result.secondsRemaining)
        assertTrue(result.isRunning)
        assertEquals(2, result.transitions.size)
    }

    @Test
    fun processRecreationStopsAtBreakWhenAutoStartIsDisabled() {
        val settings = TimerSettings(autoStartBreaks = false, autoStartFocus = true)
        val result = TimerAutomationReconciler.reconcile(
            snapshot(secondsRemaining = 10, updatedAt = 1_000),
            settings,
            nowEpochMs = 300_000
        )

        assertEquals(PomodoroState.SHORT_BREAK, result.state)
        assertEquals(settings.shortBreakDurationSeconds, result.secondsRemaining)
        assertFalse(result.isRunning)
        assertEquals(1, result.completedFocusSessions)
    }

    private fun snapshot(secondsRemaining: Int, updatedAt: Long) = TimerSnapshot(
        revision = 1,
        sessionId = "session",
        sourceDevice = "phone",
        authority = TimerAuthority.PHONE,
        state = PomodoroState.FOCUS,
        secondsRemaining = secondsRemaining,
        isRunning = true,
        updatedAtEpochMs = updatedAt,
        anchorElapsedRealtimeMs = 0
    )
}
