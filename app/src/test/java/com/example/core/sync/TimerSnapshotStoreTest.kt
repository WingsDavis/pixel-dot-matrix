package com.example.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.timer.PomodoroState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerSnapshotStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.getSharedPreferences("timer_authority_snapshot", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun snapshotSurvivesStoreRecreation() {
        val snapshot = TimerSnapshot(
            revision = 42,
            sessionId = "session",
            sourceDevice = "phone-node",
            authority = TimerAuthority.PHONE,
            state = PomodoroState.SHORT_BREAK,
            secondsRemaining = 240,
            isRunning = true,
            updatedAtEpochMs = 10_000,
            anchorElapsedRealtimeMs = 5_000
        )

        TimerSnapshotStore(context).save(snapshot)

        assertEquals(snapshot, TimerSnapshotStore(context).load())
    }
}
