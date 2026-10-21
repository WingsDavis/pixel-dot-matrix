package com.example.service

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.core.timer.PomodoroState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PomodoroTileServiceTest {
    @Test
    fun lockedTapOpensControlsWithoutQueueingATimerMutation() {
        var opened = 0
        var mutations = 0
        PomodoroTileService.routeClick(true, { opened++ }) { mutations++ }
        assertEquals(1, opened)
        assertEquals(0, mutations)
    }

    @Test
    fun laterUnlockedTapIsAFreshActionRatherThanADeferredToggle() {
        var mutations = 0
        PomodoroTileService.routeClick(true, {}) { mutations++ }
        assertEquals(0, mutations)
        PomodoroTileService.routeClick(false, { fail("Should not open controls") }) { mutations++ }
        assertEquals(1, mutations)
    }

    @Test
    fun statesArePhaseAwareLoadingAndPanicAreUnavailable() {
        assertEquals(Tile.STATE_UNAVAILABLE, state(ready = false).state)
        assertEquals(Tile.STATE_UNAVAILABLE, state(phase = PomodoroState.PANIC_MODE).state)
        assertEquals(Tile.STATE_UNAVAILABLE, state(pending = true).state)
        assertEquals(Tile.STATE_ACTIVE, state(running = true).state)
        assertEquals("Focus pause", state(running = true).label)
        assertEquals("Short break start", state(phase = PomodoroState.SHORT_BREAK).label)
        assertEquals(Tile.STATE_INACTIVE, state().state)
        assertEquals("Disk is full", state(error = "Disk is full").subtitle)
    }

    @Test
    fun displayedMinuteDoesNotChangeOnEverySecond() {
        assertEquals(state(seconds = 119), state(seconds = 118))
        assertNotEquals(state(seconds = 61), state(seconds = 60))
    }

    @Test
    fun deniedColdStartDoesNotReadUninitializedEngine() {
        val failed = PomodoroTileService.failureState(false, "Foreground start denied") {
            throw UninitializedPropertyAccessException("engine")
        }
        assertEquals(Tile.STATE_UNAVAILABLE, failed.state)
        assertEquals("Foreground start denied", failed.subtitle)
    }

    @Test
    fun secondaryIntentTargetsMainControlsWithoutTimerMutation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = PomodoroTileService.controlsIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(TileService.ACTION_QS_TILE_PREFERENCES, intent.action)
        assertFalse(intent.hasExtra(TimerForegroundService.EXTRA_REQUEST_ID))
    }

    private fun state(phase: PomodoroState = PomodoroState.FOCUS, running: Boolean = false,
        seconds: Int = 1500, error: String? = null, pending: Boolean = false, ready: Boolean = true) =
        PomodoroTileService.displayState(phase, running, seconds, error, pending, ready)
}
