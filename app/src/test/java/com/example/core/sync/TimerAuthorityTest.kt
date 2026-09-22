package com.example.core.sync

import com.example.core.timer.PomodoroState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerAuthorityTest {
    @Test
    fun runningSnapshotReconcilesElapsedOfflineTime() {
        val snapshot = TimerSnapshot(
            revision = 7,
            sessionId = "session",
            sourceDevice = "phone",
            authority = TimerAuthority.PHONE,
            state = PomodoroState.FOCUS,
            secondsRemaining = 100,
            isRunning = true,
            updatedAtEpochMs = 1_000,
            anchorElapsedRealtimeMs = 50
        )

        assertEquals(99, TimerSnapshotResolver.remainingAt(snapshot, 2_200))
        assertEquals(92, TimerSnapshotResolver.remainingAt(snapshot, 9_000))
    }

    @Test
    fun pausedSnapshotDoesNotDrift() {
        val snapshot = TimerSnapshot(7, "session", "phone", TimerAuthority.PHONE, PomodoroState.FOCUS, 100, false, 1_000, 50)
        assertEquals(100, TimerSnapshotResolver.remainingAt(snapshot, 10_000))
    }

    @Test
    fun commandFreshnessRequiresCurrentRevisionAndAllowsLegacyCommands() {
        assertTrue(TimerSnapshotResolver.commandIsAcceptable(TimerCommand(action = "PAUSE", baseRevision = 100), 100))
        assertFalse(TimerSnapshotResolver.commandIsAcceptable(TimerCommand(action = "PAUSE", baseRevision = 98), 100))
        assertFalse(TimerSnapshotResolver.commandIsAcceptable(TimerCommand(action = "PAUSE", baseRevision = 90), 100))
        assertTrue(TimerSnapshotResolver.commandIsAcceptable(TimerCommand(action = "RESET", baseRevision = -1), 100))
    }

    @Test
    fun futureClockSkewNeverAddsTime() {
        val snapshot = TimerSnapshot(7, "session", "watch", TimerAuthority.WEAR, PomodoroState.FOCUS, 100, true, 10_000, 50)
        assertEquals(100, TimerSnapshotResolver.remainingAt(snapshot, 1_000))
    }
}
