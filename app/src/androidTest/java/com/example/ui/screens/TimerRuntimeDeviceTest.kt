package com.example.ui.screens

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.core.sync.TimerCommand
import com.example.core.sync.TimerSnapshotStore
import com.example.core.timer.TimerRuntime
import com.example.core.timer.PomodoroState
import com.example.data.database.AppDatabase
import com.example.service.TimerForegroundService
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerRuntimeDeviceTest {
    @Test
    fun focusCompletesAndRecordsOneIntervalAfterUiCloses() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Timer mutations require -PisolatedQa=true", context.packageName.endsWith(".qa"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val runtime = TimerRuntime.get(context)
        runtime.awaitReady()
        val previous = runtime.timerSettings.value
        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            notifications.cancel(2205)
            runtime.updateTimerSettings({ previous.copy(
                focusDurationSeconds = 60, shortBreakDurationSeconds = 60,
                autoStartBreaks = true, autoStartFocus = false,
                transitionNotificationEnabled = true, transitionSoundEnabled = false,
                transitionHapticEnabled = false
            ) }, resetTimer = true).join()
            assertTrue(runtime.apply(TimerCommand(action = "START", baseRevision = -1)).applied)
            val intervalId = runtime.engine.activeFocusInterval.value!!.id
            withTimeout(10_000) { while (!hostIsForeground(context)) delay(100) }
            scenario.close()
            withTimeout(75_000) { while (runtime.engine.currentState.value == PomodoroState.FOCUS) delay(250) }
            assertEquals(PomodoroState.SHORT_BREAK, runtime.engine.currentState.value)
            assertTrue(runtime.engine.isRunning.value)
            assertTrue(hostIsForeground(context))
            val records = AppDatabase.getDatabase(context).sessionDao().snapshot().filter { it.recordId == intervalId }
            assertEquals(1, records.size)
            assertEquals(60, records.single().durationSeconds)
            assertEquals("COMPLETED", records.single().status)
            withTimeout(10_000) { while (notifications.activeNotifications.none { it.id == 2205 }) delay(100) }
            val transition = notifications.activeNotifications.single { it.id == 2205 }
            assertEquals("Short break ready", transition.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        } finally {
            runtime.apply(TimerCommand(action = "PAUSE", baseRevision = -1))
            runtime.updateTimerSettings({ previous }, resetTimer = true).join()
            notifications.cancel(2205)
            scenario.close()
        }
    }

    @Test
    fun closingUiKeepsOneForegroundTimerAndDuplicatePauseIsHarmless() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Timer mutations require -PisolatedQa=true", context.packageName.endsWith(".qa"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val runtime = TimerRuntime.get(context)
        try {
            runtime.awaitReady()
            assertTrue(runtime.apply(TimerCommand(action = "RESET", baseRevision = -1)).applied)
            val engine = runtime.engine
            assertTrue(runtime.apply(TimerCommand(action = "START", baseRevision = -1)).applied)
            withTimeout(10_000) { while (!hostIsForeground(context)) delay(100) }
            val before = engine.secondsRemaining.value
            scenario.close()
            delay(2_200)
            runtime.refresh()
            assertSame(engine, TimerRuntime.get(context).engine)
            assertTrue(engine.isRunning.value)
            assertTrue(hostIsForeground(context))
            assertTrue(engine.secondsRemaining.value <= before - 2)
            val pause = TimerCommand(id = "qa-headless-pause-${UUID.randomUUID()}", action = "PAUSE", baseRevision = -1)
            assertTrue(runtime.apply(pause).applied)
            assertTrue(runtime.apply(pause).applied)
            assertFalse(engine.isRunning.value)
            assertFalse(TimerSnapshotStore(context).load()!!.isRunning)
            withTimeout(10_000) { while (hostIsForeground(context)) delay(100) }
        } finally {
            runtime.apply(TimerCommand(action = "PAUSE", baseRevision = -1))
            scenario.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun hostIsForeground(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(50).any {
            it.service.className == TimerForegroundService::class.java.name && it.foreground
        }
}
