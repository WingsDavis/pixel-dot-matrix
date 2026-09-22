package com.example.core.sync

import com.example.core.timer.PomodoroState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSyncContractTest {
    private fun snapshot(
        revision: Long,
        source: String,
        authority: TimerAuthority = TimerAuthority.PHONE
    ) = TimerSnapshot(
        revision = revision,
        sessionId = "session",
        sourceDevice = source,
        authority = authority,
        state = PomodoroState.FOCUS,
        secondsRemaining = 1200,
        isRunning = true,
        updatedAtEpochMs = 1_000,
        anchorElapsedRealtimeMs = 500
    )

    @Test
    fun commandRoundTripPreservesIdentityAndRevision() {
        val command = TimerCommand(id = "command-1", action = "SKIP", baseRevision = 42, sourceDevice = "wear")
        assertEquals(command, TimerCommand.decode(command.encode()))
    }

    @Test
    fun staleIncomingSnapshotIsRejected() {
        val result = TimerSnapshotResolver.resolve(snapshot(4, "phone"), snapshot(3, "wear"), 0, 10)
        assertTrue(result is SnapshotResolution.Reject)
        assertEquals("stale_revision", (result as SnapshotResolution.Reject).reason)
    }

    @Test
    fun phoneRejectsUnleasedWearAuthority() {
        val result = TimerSnapshotResolver.resolve(
            snapshot(4, "phone"),
            snapshot(5, "wear", TimerAuthority.WEAR),
            wearLeaseExpiresAtEpochMs = 9,
            nowEpochMs = 10
        )
        assertEquals("phone_authoritative", (result as SnapshotResolution.Reject).reason)
    }

    @Test
    fun phoneAcceptsNewerWearSnapshotWithActiveLease() {
        val incoming = snapshot(5, "wear", TimerAuthority.WEAR).copy(leaseExpiresAtEpochMs = 20)
        val result = TimerSnapshotResolver.resolve(snapshot(4, "phone"), incoming, 20, 10)
        assertEquals(incoming, (result as SnapshotResolution.Accept).snapshot)
    }

    @Test
    fun expiredLeaseCannotReplacePhoneSnapshot() {
        val incoming = snapshot(5, "wear", TimerAuthority.WEAR).copy(leaseExpiresAtEpochMs = 9)
        val result = TimerSnapshotResolver.resolve(snapshot(4, "phone"), incoming, 9, 10)
        assertEquals("phone_authoritative", (result as SnapshotResolution.Reject).reason)
    }

    @Test
    fun simultaneousCommandsHaveOneCanonicalWinner() {
        val first = TimerCommand(id = "a", action = "START", baseRevision = 10)
        val simultaneous = TimerCommand(id = "b", action = "PAUSE", baseRevision = 10)

        assertTrue(TimerSnapshotResolver.commandIsAcceptable(first, 10))
        assertTrue(!TimerSnapshotResolver.commandIsAcceptable(simultaneous, 11))
    }
}
