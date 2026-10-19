package com.example.core.timer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FocusIntervalTest {
    @Test
    fun pauseAndSkipRecordOnlyElapsedFocusAndDoNotCompleteASession() = runTest {
        val engine = PomodoroEngine(backgroundScope, TimerSettings(focusDurationSeconds = 60), StandardTestDispatcher(testScheduler))
        engine.start()
        advanceTimeBy(10_000)
        runCurrent()
        engine.pause()
        val first = engine.focusIntervals.first()
        assertEquals(10, first.durationSeconds)
        assertFalse(first.completed)
        advanceTimeBy(50_000)
        engine.start()
        advanceTimeBy(5_000)
        runCurrent()
        engine.skip()
        val second = engine.focusIntervals.first()
        assertEquals(5, second.durationSeconds)
        assertFalse(second.completed)
        assertEquals(PomodoroState.SHORT_BREAK, engine.currentState.value)
    }

    @Test
    fun completionRecordsFocusBeforeBreakDurationReplacesRemainingTime() = runTest {
        val engine = PomodoroEngine(backgroundScope, TimerSettings(focusDurationSeconds = 60), StandardTestDispatcher(testScheduler))
        engine.start()
        advanceTimeBy(60_000)
        runCurrent()
        val interval = engine.focusIntervals.first()
        assertEquals(60, interval.durationSeconds)
        assertTrue(interval.completed)
        assertEquals(PomodoroState.SHORT_BREAK, engine.currentState.value)
        assertEquals(300, engine.secondsRemaining.value)
    }
}
