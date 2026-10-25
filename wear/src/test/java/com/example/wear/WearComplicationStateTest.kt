package com.example.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearComplicationStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
    }

    @Test
    fun defaultsToIdleWithoutWritingPreferences() {
        assertEquals(WearComplicationState("FOCUS", 1500, false, 1500, null), WearComplicationState.read(context, 10_000))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun readsPersistedOfflineTimerWithStableEndAnchor() {
        prefs.edit().putString("state", "SHORT_BREAK").putInt("short_break_duration", 420)
            .putInt("seconds_remaining", 300).putBoolean("is_running", true)
            .putLong("timer_updated_at", 1000L).commit()
        val original = prefs.all
        val timer = WearComplicationState.read(context, 61_000)
        assertEquals(WearComplicationState("SHORT_BREAK", 240, true, 420, 301_000L), timer)
        assertEquals(timer.phaseEndEpochMs, WearComplicationState.read(context, 121_000).phaseEndEpochMs)
        assertEquals("04:00", timer.countdownText().getTextAt(context.resources, Instant.ofEpochMilli(61_000)).toString())
        assertEquals("00:00", timer.countdownText().getTextAt(context.resources, Instant.ofEpochMilli(400_000)).toString())
        assertEquals(original, prefs.all)
    }

    @Test
    fun pausedTimerDoesNotDecay() {
        prefs.edit().putInt("seconds_remaining", 71).putLong("timer_updated_at", 1L).commit()
        val timer = WearComplicationState.read(context, Long.MAX_VALUE)
        assertEquals(71, timer.remainingSeconds)
        assertNull(timer.phaseEndEpochMs)
        assertEquals("01:11", timer.countdownText().getTextAt(context.resources, Instant.EPOCH).toString())
    }

    @Test
    fun longElapsedTimeCannotWrapRemainingSeconds() {
        prefs.edit().putInt("seconds_remaining", 1500).putBoolean("is_running", true)
            .putLong("timer_updated_at", 1L).commit()
        for (now in listOf((Int.MAX_VALUE.toLong() + 1000) * 1000, Long.MAX_VALUE)) {
            val timer = WearComplicationState.read(context, now)
            assertEquals(0, timer.remainingSeconds)
            assertFalse(timer.isRunning)
            assertEquals(1_500_001L, timer.phaseEndEpochMs)
            assertEquals("FOCUS", timer.state)
        }
    }

    @Test
    fun invalidDurationsAndRemainingValuesStayBounded() {
        for ((state, durationKey, default) in listOf(
            Triple("FOCUS", "focus_duration", 1500),
            Triple("SHORT_BREAK", "short_break_duration", 300),
            Triple("LONG_BREAK", "long_break_duration", 900)
        )) {
            for (invalid in listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)) {
                prefs.edit().putString("state", state).putInt(durationKey, invalid)
                    .putInt("seconds_remaining", Int.MAX_VALUE).commit()
                val timer = WearComplicationState.read(context, 10_000)
                assertEquals(default, timer.durationSeconds)
                assertEquals(default, timer.remainingSeconds)
            }
        }
        prefs.edit().putInt("seconds_remaining", Int.MIN_VALUE).commit()
        assertEquals(0, WearComplicationState.read(context, 10_000).remainingSeconds)
    }

    @Test
    fun futureAndExtremeAnchorsCannotOverflow() {
        prefs.edit().putBoolean("is_running", true).putInt("seconds_remaining", 300)
            .putLong("timer_updated_at", Long.MIN_VALUE).commit()
        assertEquals(290, WearComplicationState.read(context, 10_000).remainingSeconds)
        prefs.edit().putLong("timer_updated_at", Long.MAX_VALUE).commit()
        val future = WearComplicationState.read(context, 10_000)
        assertEquals(300, future.remainingSeconds)
        assertEquals(310_000L, future.phaseEndEpochMs)
        assertEquals(Long.MAX_VALUE, WearComplicationState.read(context, Long.MAX_VALUE).phaseEndEpochMs)
    }

    @Test
    fun unknownPhaseCannotPretendToBeRunningFocus() {
        prefs.edit().putString("state", "UNKNOWN").putBoolean("is_running", true).commit()
        val timer = WearComplicationState.read(context, 10_000)
        assertEquals("FOCUS", timer.state)
        assertFalse(timer.isRunning)
        assertNull(timer.phaseEndEpochMs)
    }
}
