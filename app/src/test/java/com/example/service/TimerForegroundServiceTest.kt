package com.example.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.app.Service
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.goal.DailyFocusGoalStore
import com.example.core.sync.TimerCommand
import com.example.core.timer.TimerRuntime
import com.example.core.timer.TimerSettings
import com.example.core.timer.TimerSettingsStore
import com.example.core.timer.runRuntimeTest
import com.example.data.database.AppDatabase
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerForegroundServiceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: AppDatabase
    private var runtime: TimerRuntime? = null

    @Before
    fun prepare() {
        TimerRuntime.clearForTest()
        listOf("timer_authority_snapshot", "panic_runtime", "task_selection").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun finish() {
        runtime?.close()
        TimerRuntime.clearForTest()
        database.close()
    }

    private suspend fun createRuntime(): TimerRuntime {
        TimerSettingsStore(context).write(TimerSettings())
        DailyFocusGoalStore(context).save(DailyFocusGoalSettings())
        return TimerRuntime(context, database, foregroundStarter = {}).also {
            runtime = it
            TimerRuntime.installForTest(it)
            it.awaitReady()
            withContext(Dispatchers.Main.immediate) { }
        }
    }

    @Test
    fun foregroundHostUsesLiveStateAndRecreationNeverReplaysToggle() = runRuntimeTest {
        val timer = createRuntime()
        val engine = timer.engine
        val first = withContext(Dispatchers.Main.immediate) { Robolectric.buildService(TimerForegroundService::class.java).create() }
        val intent = Intent(context, TimerForegroundService::class.java).setAction(TimerForegroundService.ACTION_TOGGLE)
            .putExtra(TimerForegroundService.EXTRA_REQUEST_ID, "tile-click")
        assertEquals(Service.START_STICKY, withContext(Dispatchers.Main.immediate) { first.get().onStartCommand(intent, 0, 1) })
        withContext(Dispatchers.Main.immediate) { }
        assertTrue(timer.engine.isRunning.value)
        withContext(Dispatchers.Main.immediate) { first.get().onStartCommand(intent, 0, 2) }
        assertTrue(timer.engine.isRunning.value)
        withContext(Dispatchers.Main.immediate) { first.destroy() }
        assertSame(engine, timer.engine)
        val restarted = withContext(Dispatchers.Main.immediate) { Robolectric.buildService(TimerForegroundService::class.java).create() }
        withContext(Dispatchers.Main.immediate) { restarted.get().onStartCommand(null, 0, 3) }
        assertTrue(timer.engine.isRunning.value)
        assertSame(engine, TimerRuntime.get(context).engine)
        timer.apply(TimerCommand(action = "PAUSE", baseRevision = -1))
        withContext(Dispatchers.Main.immediate) { }
        assertTrue(shadowOf(restarted.get()).isStoppedBySelf)
        withContext(Dispatchers.Main.immediate) { restarted.destroy() }
        timer.close()
    }

    @Test
    fun backgroundWearListenerControlsTheSameRuntimeWithoutActivityBroadcast() = runRuntimeTest {
        val timer = createRuntime()
        val service = withContext(Dispatchers.Main.immediate) { Robolectric.buildService(WearableSyncService::class.java).create() }
        val bytes = TimerCommand(id = "wear-start", action = "START", baseRevision = -1).encode()
        val event = object : MessageEvent {
            override fun getRequestId() = 7
            override fun getPath() = "/pomodoro/control"
            override fun getSourceNodeId() = "watch"
            override fun getData() = bytes
        }
        service.get().onMessageReceived(event)
        timer.receiveMessage(event.path, event.sourceNodeId, bytes.copyOf()).join()
        assertTrue(timer.engine.isRunning.value)
        assertTrue(context.let { shadowOf(it).broadcastIntents }.none { it.action == "com.example.wear.CONTROL_MESSAGE" })
        val pause = TimerCommand(id = "wear-pause", action = "PAUSE", baseRevision = -1).encode()
        timer.receiveMessage("/pomodoro/control", "watch", pause).join()
        assertFalse(timer.engine.isRunning.value)
        withContext(Dispatchers.Main.immediate) { service.destroy() }
        timer.close()
    }
}
