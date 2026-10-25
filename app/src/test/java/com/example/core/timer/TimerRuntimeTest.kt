package com.example.core.timer

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.hardware.SensorManager
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.goal.DailyFocusGoalStore
import com.example.core.sync.TimerAuthority
import com.example.core.sync.TimerCommand
import com.example.core.sync.TimerSnapshot
import com.example.core.sync.TimerSnapshotStore
import com.example.core.sync.WearCommandProtocol
import com.google.android.gms.wearable.DataMap
import com.example.data.database.AppDatabase
import com.example.data.entity.FocusTaskEntity
import com.example.data.entity.IncidentStatus
import com.example.data.entity.PanicLogEntity
import com.example.ui.viewmodel.PomodoroViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import androidx.room.withTransaction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerRuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: AppDatabase
    private var runtime: TimerRuntime? = null
    private var elapsed = 0L
    private val alerts = mutableListOf<TimerTransition>()
    private var tileRefreshes = 0

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

    private suspend fun create(
        settings: TimerSettings = TimerSettings(transitionSoundEnabled = false, transitionHapticEnabled = false),
        store: TimerSnapshotStore = TimerSnapshotStore(context),
        startHost: (Context) -> Unit = {},
        acknowledgement: suspend (com.example.core.sync.SyncAppliedAck) -> Unit = {}
    ): TimerRuntime {
        TimerSettingsStore(context).write(settings)
        DailyFocusGoalStore(context).save(DailyFocusGoalSettings(enabled = true))
        return TimerRuntime(context, database, startHost, { elapsed }, store,
            { transition, _ -> alerts += transition }, { tileRefreshes++ }, acknowledgement).also {
            runtime = it
            TimerRuntime.installForTest(it)
            it.awaitReady()
            withContext(Dispatchers.Main.immediate) { }
        }
    }

    @Test
    fun persistentWatchCommandUsesTheSameAuthorityAndPublishesOnlyAppliedAck() = runRuntimeTest {
        val acknowledgements = mutableListOf<com.example.core.sync.SyncAppliedAck>()
        val timer = create(acknowledgement = { acknowledgements += it })
        val data = DataMap().apply {
            putInt("schema_version", 1)
            putString("command_id", "queued-start")
            putString("path", "/pomodoro/control")
            putByteArray("payload", TimerCommand(id = "queued-start", action = "START", baseRevision = -1).encode())
            putLong("created_at", System.currentTimeMillis())
        }
        timer.receiveData(WearCommandProtocol.COMMAND_PREFIX + "queued-start", data).join()
        timer.receiveData(WearCommandProtocol.COMMAND_PREFIX + "queued-start", data).join()
        assertTrue(timer.engine.isRunning.value)
        assertEquals(listOf(true, true), acknowledgements.map { it.applied })
        assertTrue(TimerSnapshotStore(context).wasCommandApplied("queued-start"))
        data.putInt("schema_version", 2)
        timer.receiveData(WearCommandProtocol.COMMAND_PREFIX + "queued-start", data).join()
        assertFalse(acknowledgements.last().applied)
        timer.close()
    }

    @Test
    fun durableDistractionKeepsCaptureTimeAndReloadsEditableRowIdentity() = runRuntimeTest {
        val timer = create()
        val captured = System.currentTimeMillis() - 120_000
        val data = DataMap().apply {
            putInt("schema_version", 1)
            putString("command_id", "queued-distraction")
            putString("path", "/incident/mark")
            putByteArray("payload", TimerCommand(id = "queued-distraction", action = "DISTRACTION", baseRevision = -1).encode())
            putLong("created_at", captured)
        }
        assertTrue(timer.applyDurableCommand(WearCommandProtocol.COMMAND_PREFIX + "queued-distraction", data)!!.applied)
        val stored = timer.activeIncidentAudit.value!!
        assertTrue(stored.id > 0)
        assertEquals(captured, stored.timestamp)
        database.panicDao().updatePanicLog(stored.copy(forensicNotes = "Captured detail", incidentStatus = IncidentStatus.CLOSED))
        assertEquals("Captured detail", database.panicDao().getByIncidentId(stored.incidentId)?.forensicNotes)
        assertTrue(timer.applyDurableCommand(WearCommandProtocol.COMMAND_PREFIX + "queued-distraction", data)!!.applied)
        assertEquals(1, database.panicDao().getAllPanicLogs().first().size)
        assertEquals(IncidentStatus.CLOSED, database.panicDao().getByIncidentId(stored.incidentId)?.incidentStatus)
        timer.close()
    }

    @Test
    fun legacyFirstDistractionRetainsItsCaptureTimeWhenDurableCopyArrives() = runRuntimeTest {
        val timer = create()
        val captured = System.currentTimeMillis() - 3_600_000
        val id = "legacy-first-distraction"
        timer.receiveMessage("/incident/mark", "watch", TimerCommand(id = id,
            action = "DISTRACTION|$captured", baseRevision = -1).encode()).join()
        assertEquals(captured, database.panicDao().getByIncidentId(id)!!.timestamp)
        val durable = DataMap().apply {
            putInt("schema_version", 1); putString("command_id", id)
            putString("path", "/incident/mark"); putLong("created_at", captured)
            putByteArray("payload", TimerCommand(id = id, action = "DISTRACTION", baseRevision = -1).encode())
        }
        assertTrue(timer.applyDurableCommand(WearCommandProtocol.COMMAND_PREFIX + id, durable)!!.applied)
        assertEquals(captured, database.panicDao().getByIncidentId(id)!!.timestamp)
        assertEquals(1, database.panicDao().snapshot().size)
        timer.close()
    }

    @Test
    fun failedDurableCommandDoesNotClaimAnAppliedReceipt() = runRuntimeTest {
        val timer = create(startHost = { error("Foreground start denied") })
        val data = DataMap().apply {
            putInt("schema_version", 1)
            putString("command_id", "denied-start")
            putString("path", "/pomodoro/control")
            putByteArray("payload", TimerCommand(id = "denied-start", action = "START", baseRevision = -1).encode())
        }
        val result = timer.applyDurableCommand(WearCommandProtocol.COMMAND_PREFIX + "denied-start", data)!!
        assertFalse(result.applied)
        assertEquals("Foreground start denied", result.message)
        assertFalse(timer.engine.isRunning.value)
        assertFalse(TimerSnapshotStore(context).wasCommandApplied("denied-start"))
        data.putString("command_id", "different")
        assertFalse(timer.applyDurableCommand(WearCommandProtocol.COMMAND_PREFIX + "denied-start", data)!!.applied)
        timer.close()
    }

    @Test
    fun uiAndHeadlessClientsShareOneAuthorityAfterUiIsCleared() = runRuntimeTest {
        val timer = create()
        val first = ViewModelStore()
        val second = ViewModelStore()
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context)
        val firstVm = ViewModelProvider(first, factory)[PomodoroViewModel::class.java]
        val secondVm = ViewModelProvider(second, factory)[PomodoroViewModel::class.java]
        assertSame(firstVm.engine, timer.engine)
        assertSame(secondVm.engine, timer.engine)
        assertSame(timer.syncManager, TimerRuntime.get(context).syncManager)
        first.clear()
        second.clear()
        assertTrue(timer.toggle("headless-start").applied)
        assertTrue(timer.engine.isRunning.value)
        assertTrue(shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).listeners.isEmpty())
        timer.close()
    }

    @Test
    fun coldRestoreAndDuplicateToggleRemainIdempotentAcrossRuntimeRecreation() = runRuntimeTest {
        val now = System.currentTimeMillis()
        TimerSnapshotStore(context).save(TimerSnapshot(4, "cold-session", "phone", TimerAuthority.PHONE,
            PomodoroState.FOCUS, 60, true, now - 15_000, 0, activeFocusInterval = FocusInterval(now - 15_000, id = "cold-focus")))
        val timer = create(TimerSettings(focusDurationSeconds = 60))
        assertEquals(45, timer.engine.secondsRemaining.value)
        assertTrue(timer.toggle("same-toggle").applied)
        assertFalse(timer.engine.isRunning.value)
        assertTrue(timer.toggle("same-toggle").applied)
        assertFalse(timer.engine.isRunning.value)
        timer.close()
        val restored = create(TimerSettings(focusDurationSeconds = 60))
        assertTrue(restored.toggle("same-toggle").applied)
        assertFalse(restored.engine.isRunning.value)
        restored.close()
    }

    @Test
    fun automaticFocusUsesNextTaskAfterElapsedJumpAcrossBreak() = runRuntimeTest {
        val first = FocusTaskEntity(id = "first", title = "First", sortOrder = 0, estimateSessions = 1)
        val next = FocusTaskEntity(id = "next", title = "Next", sortOrder = 1, estimateSessions = 1)
        database.focusTaskDao().upsert(first)
        database.focusTaskDao().upsert(next)
        val timer = create(automaticSettings())
        assertTrue(timer.toggle("start").applied)
        elapsed = 125_000
        timer.refresh()
        assertEquals(PomodoroState.FOCUS, timer.engine.currentState.value)
        assertEquals(55, timer.engine.secondsRemaining.value)
        assertEquals("next", timer.selectedTask.value?.id)
        assertEquals("first", database.sessionDao().snapshot().single().taskId)
        elapsed = 180_000
        timer.refresh()
        assertEquals(listOf("first", "next"), database.sessionDao().snapshot().sortedBy { it.startTimeMillis }.map { it.taskId })
        assertTrue(database.focusTaskDao().snapshot().all { it.status == FocusTaskEntity.STATUS_COMPLETED })
        timer.close()
    }

    @Test
    fun changingSelectionDoesNotRelabelInterruptedActiveFocus() = runRuntimeTest {
        database.focusTaskDao().upsert(FocusTaskEntity(id = "first", title = "First", sortOrder = 0, estimateSessions = 1))
        database.focusTaskDao().upsert(FocusTaskEntity(id = "next", title = "Next", sortOrder = 1, estimateSessions = 1))
        val timer = create(automaticSettings())
        timer.toggle("start")
        elapsed = 10_000
        timer.refresh()
        timer.selectTask("next").join()
        elapsed = 15_000
        timer.apply(TimerCommand(action = "PAUSE", baseRevision = -1))
        timer.refresh()
        val saved = database.sessionDao().snapshot().single()
        assertEquals("first", saved.taskId)
        assertEquals("First", saved.taskName)
        assertEquals(15, saved.durationSeconds)
        assertEquals("INTERRUPTED", saved.status)
        timer.close()
    }

    @Test
    fun longSleepKeepsAllFocusRecordsButOnlyAlertsForLatestTransition() = runRuntimeTest {
        database.focusTaskDao().upsert(FocusTaskEntity(id = "task", title = "Task", sortOrder = 0, estimateSessions = 12))
        val timer = create(automaticSettings())
        timer.toggle("start")
        elapsed = 610_000
        timer.refresh()
        assertEquals(PomodoroState.FOCUS, timer.engine.currentState.value)
        assertEquals(50, timer.engine.secondsRemaining.value)
        assertEquals(5, database.sessionDao().snapshot().size)
        assertEquals(5, database.focusTaskDao().snapshot().single().completedSessions)
        assertEquals(1, alerts.size)
        assertEquals(PomodoroState.FOCUS, alerts.single().to)
        timer.refresh()
        assertEquals(1, alerts.size)
        timer.close()
    }

    @Test
    fun secondTicksDoNotRebindTileAndProgressCollectsActiveFocus() = runRuntimeTest {
        val timer = create(TimerSettings(focusDurationSeconds = 600))
        timer.toggle("start")
        withContext(Dispatchers.Main.immediate) { }
        val initialRequests = tileRefreshes
        repeat(10) {
            elapsed += 1_000
            timer.refresh()
            withContext(Dispatchers.Main.immediate) { }
        }
        assertEquals(initialRequests, tileRefreshes)
        assertEquals(10L, timer.dailyFocusProgress.first { it.focusedSeconds == 10L }.focusedSeconds)
        timer.close()
    }

    @Test
    fun foregroundDenialDoesNotStartTimerAndReturnsVisibleFailure() = runRuntimeTest {
        val timer = create(startHost = { throw IllegalStateException("Foreground start denied") })
        val result = timer.toggle("denied")
        assertFalse(result.applied)
        assertFalse(timer.engine.isRunning.value)
        assertEquals("Foreground start denied", timer.error.value)
        assertFalse(TimerSnapshotStore(context).wasCommandApplied("denied"))
        timer.close()
    }

    @Test
    fun settingsRestoreDiscardsOlderQueuedEditsWithoutReplacingAuthority() = runRuntimeTest {
        val timer = create()
        val engine = timer.engine
        val sync = timer.syncManager
        timer.updateTimerSettings({ it.copy(focusDurationSeconds = 60) }, false).join()
        timer.withSettingsRestore {
            TimerSettingsStore(context).write(TimerSettings(focusDurationSeconds = 120))
            DailyFocusGoalStore(context).save(DailyFocusGoalSettings(enabled = true, targetMinutes = 45))
        }
        assertSame(engine, timer.engine)
        assertSame(sync, timer.syncManager)
        assertEquals(120, timer.engine.focusDuration)
        assertEquals(120, TimerSettingsStore(context).loadAndMigrate().focusDurationSeconds)
        assertEquals(45, timer.goalSettings.value.targetMinutes)
        assertFalse(timer.settingsPending.value)
        timer.close()
    }

    @Test
    fun postMemoryCommitFailureDoesNotAcknowledgeOrKeepNewTimerRunning() = runRuntimeTest {
        val real = context.getSharedPreferences("timer_authority_snapshot", Context.MODE_PRIVATE)
        var fail = false
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            val value = method.invoke(real, *(args ?: emptyArray()))
            if (method.name != "edit") value else Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, editorMethod, editorArgs ->
                val result = editorMethod.invoke(value, *(editorArgs ?: emptyArray()))
                if (editorMethod.name == "commit" && fail) false
                else if (result is SharedPreferences.Editor) proxy else result
            }
        } as SharedPreferences
        val timer = create(store = TimerSnapshotStore(preferences))
        withContext(Dispatchers.Main.immediate) { }
        fail = true
        val result = timer.toggle("disk-failure")
        assertFalse(result.applied)
        assertFalse(timer.engine.isRunning.value)
        assertFalse(TimerSnapshotStore(context).wasCommandApplied("disk-failure"))
        assertFalse(real.getBoolean("is_running", true))
        assertFalse(timer.toggle("disk-failure").applied)
        assertFalse(timer.engine.isRunning.value)
        assertFalse(TimerSnapshotStore(context).wasCommandApplied("disk-failure"))
        assertNotNull(timer.error.value)
        fail = false
        assertTrue(timer.toggle("disk-failure").applied)
        timer.close()
    }

    @Test
    fun cancelledRefreshCompletesSuspendedReconciliationWithoutLosingElapsedTime() = runRuntimeTest {
        val store = TimerSnapshotStore(context)
        val timer = create(automaticSettings(), store)
        timer.toggle("start")
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = async(Dispatchers.IO) {
            database.withTransaction { held.complete(Unit); release.await() }
        }
        held.await()
        elapsed = 60_000
        val refresh = async { timer.refresh() }
        withTimeout(5_000) { while (store.pendingFocusIntervals().isEmpty()) delay(1) }
        refresh.cancel()
        release.complete(Unit)
        blocker.await()
        refresh.join()
        assertTrue(refresh.isCancelled)
        assertEquals(PomodoroState.SHORT_BREAK, timer.engine.currentState.value)
        assertEquals(60, timer.engine.secondsRemaining.value)
        assertEquals(PomodoroState.SHORT_BREAK, store.load()?.state)
        elapsed = 61_000
        timer.refresh()
        assertEquals(59, timer.engine.secondsRemaining.value)
        assertEquals(60, database.sessionDao().snapshot().single().durationSeconds)
        timer.close()
    }

    @Test
    fun cancelledSettingsRestoreClearsPendingFlagAndReloadsSurvivingRuntime() = runRuntimeTest {
        val timer = create()
        val entered = CompletableDeferred<Unit>()
        val restore = launch {
            timer.withSettingsRestore {
                TimerSettingsStore(context).write(TimerSettings(focusDurationSeconds = 120))
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        restore.cancelAndJoin()
        assertFalse(timer.settingsPending.value)
        assertEquals(120, timer.engine.focusDuration)
        assertTrue(timer.toggle("after-cancelled-restore").applied)
        timer.close()
    }

    @Test
    fun unchangedColdHydrationAcceptsFirstWearStartAtPersistedRevision() = runRuntimeTest {
        val stored = TimerSnapshot(17, "paused", "phone", TimerAuthority.PHONE,
            PomodoroState.FOCUS, 1500, false, System.currentTimeMillis() - 60_000, 0)
        TimerSnapshotStore(context).save(stored)
        val timer = create()
        assertEquals(17L, timer.syncManager.currentTimerSnapshot().revision)
        assertEquals(17L, TimerSnapshotStore(context).load()?.revision)
        val start = TimerCommand(id = "first-wear-start", action = "START", baseRevision = 17)
        timer.receiveMessage("/pomodoro/control", "watch", start.encode()).join()
        assertTrue(timer.engine.isRunning.value)
        assertTrue(TimerSnapshotStore(context).wasCommandApplied(start.id))
        timer.close()
    }

    @Test
    fun queuedSettingsEditsMergeWithLatestValuesWhileReconciliationIsBusy() = runRuntimeTest {
        val store = TimerSnapshotStore(context)
        val timer = create(automaticSettings(), store)
        timer.toggle("start")
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = async(Dispatchers.IO) { database.withTransaction { held.complete(Unit); release.await() } }
        held.await()
        elapsed = 60_000
        val refresh = async { timer.refresh() }
        withTimeout(5_000) { while (store.pendingFocusIntervals().isEmpty()) delay(1) }
        val duration = timer.updateTimerSettings({ it.copy(focusDurationSeconds = 120) }, false)
        val automation = timer.updateTimerSettings({ it.copy(autoStartFocus = false) }, false)
        release.complete(Unit)
        blocker.await()
        refresh.await()
        duration.join()
        automation.join()
        assertEquals(120, timer.timerSettings.value.focusDurationSeconds)
        assertFalse(timer.timerSettings.value.autoStartFocus)
        assertEquals(120, timer.engine.focusDuration)
        timer.close()
    }

    @Test
    fun restoredPanicUsesCapturedLiveIdAndNeverRegistersHeadlessSensors() = runRuntimeTest {
        database.panicDao().insertIncidentIfAbsent(PanicLogEntity(incidentId = "live", timestamp = 100))
        database.panicDao().insertIncidentIfAbsent(PanicLogEntity(incidentId = "imported-newer", timestamp = 200))
        context.getSharedPreferences("panic_runtime", Context.MODE_PRIVATE).edit().putString("active_incident_id", "live").commit()
        TimerSnapshotStore(context).save(TimerSnapshot(1, "panic", "phone", TimerAuthority.PHONE,
            PomodoroState.PANIC_MODE, 0, false, System.currentTimeMillis(), 0))
        val timer = create()
        assertTrue(shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).listeners.isEmpty())
        assertFalse(timer.toggle("panic-toggle").applied)
        timer.apply(TimerCommand(action = "RESOLVE_PANIC", baseRevision = -1))
        database.panicDao().getAllPanicLogs().first { rows -> rows.any { it.incidentId == "live" && it.incidentStatus == IncidentStatus.PENDING_DETAIL } }
        assertEquals(IncidentStatus.OPEN, database.panicDao().getByIncidentId("imported-newer")?.incidentStatus)
        timer.close()
    }

    private fun automaticSettings() = TimerSettings(focusDurationSeconds = 60, shortBreakDurationSeconds = 60,
        longBreakDurationSeconds = 300, longBreakCadence = 8, autoStartBreaks = true, autoStartFocus = true)
}
