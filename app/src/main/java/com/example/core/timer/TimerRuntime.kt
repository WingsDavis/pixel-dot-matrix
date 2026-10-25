package com.example.core.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.room.withTransaction
import androidx.core.content.ContextCompat
import com.example.core.backup.BackupRepository
import com.example.core.goal.DailyFocusCalculator
import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.goal.DailyFocusGoalStore
import com.example.core.sync.SyncAppliedAck
import com.example.core.sync.TimerCommand
import com.example.core.sync.TimerSnapshotResolver
import com.example.core.sync.TimerSnapshotStore
import com.example.core.sync.WearableSyncManager
import com.example.core.sync.WearCommandProtocol
import com.example.data.database.AppDatabase
import com.example.data.entity.FocusTaskEntity
import com.example.data.entity.IncidentStatus
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import com.example.data.repository.FocusTaskRepository
import com.example.data.repository.PomodoroRepository
import com.example.service.PomodoroTileService
import com.example.service.TimerForegroundService
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class TimerRuntime internal constructor(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context),
    private val foregroundStarter: (Context) -> Unit = TimerForegroundService::start,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val snapshotStore: TimerSnapshotStore = TimerSnapshotStore(context),
    private val notifyTransition: (TimerTransition, TimerSettings) -> Unit = SessionTransitionNotifier(context.applicationContext)::notify,
    private val requestTileRefresh: (Context) -> Unit = PomodoroTileService::requestRefresh,
    private val publishCommandAck: suspend (SyncAppliedAck) -> Unit = { ack ->
        val request = com.google.android.gms.wearable.PutDataMapRequest.create(WearCommandProtocol.ACK_PREFIX + ack.id).apply {
            dataMap.putInt("schema_version", WearCommandProtocol.SCHEMA_VERSION)
            dataMap.putString("command_id", ack.id)
            dataMap.putByteArray("payload", ack.encode())
            dataMap.putLong("updated_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context.applicationContext).putDataItem(request).await()
    }
) {
    private val context = context.applicationContext
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        Log.e("TimerRuntime", "Background synchronization failed", error)
    })
    private val commands = Mutex()
    private val repository = PomodoroRepository(database.sessionDao(), database.panicDao())
    private val taskRepository = FocusTaskRepository(database.focusTaskDao())
    private val timerStore = TimerSettingsStore(this.context)
    private val goalStore = DailyFocusGoalStore(this.context)
    private val runtimePreferences = this.context.getSharedPreferences("panic_runtime", Context.MODE_PRIVATE)
    private val taskPreferences = this.context.getSharedPreferences("task_selection", Context.MODE_PRIVATE)
    private val timerSettingsUpdates = Channel<Pair<Long, TimerSettings>>(Channel.CONFLATED)
    private val goalUpdates = Channel<Pair<Long, DailyFocusGoalSettings>>(Channel.CONFLATED)
    private val settingsGeneration = AtomicLong()
    private val goalClock = MutableStateFlow(System.currentTimeMillis())
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _settingsPending = MutableStateFlow(false)
    val settingsPending = _settingsPending.asStateFlow()
    private val _timerSettings = MutableStateFlow(TimerSettings())
    val timerSettings = _timerSettings.asStateFlow()
    private val _goalSettings = MutableStateFlow(DailyFocusGoalSettings())
    val goalSettings = _goalSettings.asStateFlow()
    private val _goalSaveError = MutableStateFlow<String?>(null)
    val focusGoalSaveError = _goalSaveError.asStateFlow()
    private val _tasks = MutableStateFlow<List<FocusTaskEntity>>(emptyList())
    val focusTasks = _tasks.asStateFlow()
    private val _selectedTask = MutableStateFlow<FocusTaskEntity?>(null)
    val selectedTask = _selectedTask.asStateFlow()
    private val selectedTaskId = MutableStateFlow(taskPreferences.getString("selected_task_id", null))
    private val activeFocus = MutableStateFlow<FocusInterval?>(null)
    private var livePanicIncidentId: String? = runtimePreferences.getString("active_incident_id", null)
    private val _steps = MutableStateFlow(0)
    val stepsTakenInPanic = _steps.asStateFlow()
    internal val activeIncidentAudit = MutableStateFlow<PanicLogEntity?>(null)
    private var elapsedAnchor = elapsedRealtime()
    private var wallAnchor = System.currentTimeMillis()
    private var timeReceiverRegistered = false
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshGoalClock()
    }

    lateinit var engine: PomodoroEngine
        private set
    lateinit var syncManager: WearableSyncManager
        private set
    private val initialization = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        try {
            // The restore journal must finish before any timer or face settings are hydrated.
            val restore = runCatching { BackupRepository(this@TimerRuntime.context, database).finishPendingSettings() }
            _settingsPending.value = restore.isFailure
            val settings = timerStore.loadAndMigrate()
            _timerSettings.value = settings
            _goalSettings.value = goalStore.loadForBackup()
            val tasks = taskRepository.pending()
            _tasks.value = tasks
            _selectedTask.value = tasks.firstOrNull { it.id == selectedTaskId.value } ?: tasks.firstOrNull()
            val stored = snapshotStore.load()
            val pending = snapshotStore.pendingFocusIntervals()
            val restored = stored?.let { TimerAutomationReconciler.reconcile(
                if (_settingsPending.value) it.copy(isRunning = false) else it, settings, System.currentTimeMillis(), pending) }
            (pending + restored?.focusIntervals.orEmpty()).distinctBy { it.id }.forEach { saveFocusInterval(it) }
            if (stored != null && restored != null && (stored.state != restored.state || stored.isRunning != restored.isRunning ||
                stored.secondsRemaining != restored.secondsRemaining || stored.completedFocusSessions != restored.completedFocusSessions ||
                stored.activeFocusInterval != restored.activeFocusInterval)) {
                snapshotStore.save(stored.copy(
                    revision = stored.revision + 1, state = restored.state,
                    secondsRemaining = restored.secondsRemaining, isRunning = restored.isRunning,
                    updatedAtEpochMs = System.currentTimeMillis(), anchorElapsedRealtimeMs = elapsedRealtime(),
                    completedFocusSessions = restored.completedFocusSessions, activeFocusInterval = restored.activeFocusInterval
                ))
            }
            pending.forEach { snapshotStore.removePendingFocusInterval(it.id) }
            engine = PomodoroEngine(scope, settings,
                onFocusIntervalRecorded = snapshotStore::savePendingFocusInterval,
                onTick = { tick() }
            )
            syncManager = WearableSyncManager(this@TimerRuntime.context, scope, engine, ::apply, snapshotStore)
            restored?.let(engine::restore)
            captureActiveTask()
            activeFocus.value = engine.activeFocusInterval.value
            resetClockAnchor()
            if (engine.currentState.value != PomodoroState.PANIC_MODE) livePanicIncidentId = null
            startCollectors()
            restored?.transitions?.lastOrNull()?.let { notifyTransition(it, settings) }
            _ready.value = true
            requestTileRefresh(this@TimerRuntime.context)
            if (restore.isFailure) reportError(restore.exceptionOrNull() ?: error("Settings restore is pending"))
        } catch (error: Exception) {
            reportError(error)
            throw error
        }
    }

    val sessionLogs = repository.allSessions.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val dailyFocusProgress = combine(sessionLogs, _goalSettings, activeFocus, goalClock) { sessions, settings, active, now ->
        DailyFocusCalculator.calculate(sessions, settings, maxOf(now, active?.endTimeMillis ?: now), active)
    }.stateIn(scope, SharingStarted.Eagerly, DailyFocusCalculator.calculate(emptyList(), _goalSettings.value, goalClock.value))

    init { initialization.start() }

    suspend fun awaitReady() { initialization.await() }

    private fun startCollectors() {
        ContextCompat.registerReceiver(context, timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        timeReceiverRegistered = true
        scope.launch {
            while (isActive) { refreshGoalClock(); delay(15_000L) }
        }
        scope.launch(Dispatchers.IO) {
            for ((generation, settings) in timerSettingsUpdates) commands.withLock {
                if (generation == settingsGeneration.get() && !_settingsPending.value) {
                    runCatching { timerStore.write(settings) }.onFailure(::reportError)
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for ((generation, settings) in goalUpdates) commands.withLock {
                if (generation == settingsGeneration.get() && !_settingsPending.value) {
                    runCatching { goalStore.save(settings) }
                        .onSuccess { _goalSaveError.value = null }
                        .onFailure { _goalSaveError.value = "Could not save the daily target. Try changing it again."; reportError(it) }
                }
            }
        }
        scope.launch {
            dailyFocusProgress.map { it.copy(focusedSeconds = it.focusedSeconds / 60 * 60) }
                .distinctUntilChanged().collect(syncManager::pushDailyFocusGoal)
        }
        scope.launch {
            engine.focusIntervals.collect { interval ->
                commands.withLock {
                    saveFocusInterval(interval)
                    snapshotStore.removePendingFocusInterval(interval.id)
                    syncManager.pushStateToWearable(force = true)
                }
            }
        }
        scope.launch { engine.activeFocusInterval.collect { activeFocus.value = it } }
        scope.launch {
            engine.transitionEvents.collect { if (it.cause == TimerTransitionCause.COMPLETED) notifyTransition(it, _timerSettings.value) }
        }
        scope.launch {
            combine(taskRepository.pendingTasks, selectedTaskId) { tasks, id -> tasks to (tasks.firstOrNull { it.id == id } ?: tasks.firstOrNull()) }
                .collect { (tasks, selected) ->
                    _tasks.value = tasks
                    _selectedTask.value = selected
                    if (selected != null && selected.id != selectedTaskId.value) selectTask(selected.id)
                    val index = tasks.indexOfFirst { it.id == selected?.id }
                    syncManager.pushTaskSummaries(selected?.title, tasks.getOrNull(index + 1)?.title)
                }
        }
        scope.launch {
            activeIncidentAudit.value = repository.getPendingAuditLog()
        }
        scope.launch {
            combine(engine.currentState, engine.secondsRemaining, engine.isRunning) { _, _, _ -> }
                .collect {
                    commands.withLock { runCatching { syncManager.pushStateToWearable() }.onFailure(::reportError) }
                }
        }
        scope.launch {
            combine(engine.currentState, engine.isRunning, engine.secondsRemaining) { state, running, seconds ->
                Triple(state, running, (seconds + 59) / 60)
            }.distinctUntilChanged().collect { requestTileRefresh(context) }
        }
        scope.launch {
            var previousState = PomodoroState.FOCUS
            engine.currentState.collect { state ->
                if (state == PomodoroState.PANIC_MODE) {
                    _steps.value = 0
                    val id = livePanicIncidentId ?: UUID.randomUUID().toString().also {
                        check(runtimePreferences.edit().putString("active_incident_id", it).commit())
                        livePanicIncidentId = it
                    }
                    repository.insertIncidentIfAbsent(PanicLogEntity(incidentId = id, heartRateSpike = 138, taskName = selectedTask.value?.title))
                    syncManager.sendPanicTriggerMessage()
                } else if (previousState == PomodoroState.PANIC_MODE) {
                    val resolvedId = livePanicIncidentId
                    livePanicIncidentId = null
                    val steps = _steps.value
                    scope.launch {
                        val incident = resolvedId?.let { database.panicDao().getByIncidentId(it) }
                        if (incident != null && IncidentStatus.canTransition(incident.incidentStatus, IncidentStatus.PENDING_DETAIL)) {
                            val updated = incident.copy(stepsTaken = steps, resolvedAt = System.currentTimeMillis(), incidentStatus = IncidentStatus.PENDING_DETAIL)
                            repository.updatePanicLog(updated)
                            activeIncidentAudit.value = updated
                        }
                        if (runtimePreferences.getString("active_incident_id", null) == resolvedId) {
                            runtimePreferences.edit().remove("active_incident_id").commit()
                        }
                    }
                }
                previousState = state
            }
        }
    }

    private suspend fun saveFocusInterval(interval: FocusInterval) {
        val metadata = runtimePreferences.getString("task_${interval.id}", null)?.let(::JSONObject)
        val taskId = metadata?.optString("id")?.ifBlank { null } ?: if (metadata == null) selectedTask.value?.id else null
        val taskName = metadata?.optString("name")?.ifBlank { null } ?: if (metadata == null) selectedTask.value?.title else null
        val advanceTask = database.withTransaction {
            val added = repository.insertSessionIfAbsent(SessionLogEntity(
                sessionType = "FOCUS", startTimeMillis = interval.startTimeMillis, durationSeconds = interval.durationSeconds,
                status = if (interval.completed) "COMPLETED" else "INTERRUPTED", taskId = taskId, taskName = taskName,
                timestamp = interval.endTimeMillis, recordId = interval.id
            ))
            added != -1L && interval.completed && taskId != null && taskRepository.recordCompletedFocus(taskId)
        }
        if (advanceTask) selectNextTask()
        runtimePreferences.edit().remove("task_${interval.id}").commit()
    }

    private fun captureActiveTask() {
        val interval = engine.activeFocusInterval.value ?: return
        val key = "task_${interval.id}"
        if (!runtimePreferences.contains(key)) {
            val task = selectedTask.value
            val value = JSONObject().put("id", task?.id.orEmpty()).put("name", task?.title.orEmpty())
            check(runtimePreferences.edit().putString(key, value.toString()).commit()) { "Could not save the active task" }
        }
    }

    private suspend fun drainPendingFocus() {
        snapshotStore.pendingFocusIntervals().sortedBy { it.startTimeMillis }.forEach {
            saveFocusInterval(it)
            snapshotStore.removePendingFocusInterval(it.id)
        }
    }

    private fun resetClockAnchor() { elapsedAnchor = elapsedRealtime(); wallAnchor = System.currentTimeMillis() }

    private suspend fun advanceClock() = withContext(NonCancellable) {
        drainPendingFocus()
        val elapsed = ((elapsedRealtime() - elapsedAnchor).coerceAtLeast(0L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (elapsed == 0) return@withContext
        val nextElapsedAnchor = elapsedAnchor + elapsed * 1_000L
        val nextWallAnchor = wallAnchor + elapsed * 1_000L
        if (engine.isRunning.value) {
            captureActiveTask()
            val before = syncManager.currentTimerSnapshot()
            val timer = TimerAutomationReconciler.reconcile(before.copy(
                state = engine.currentState.value, secondsRemaining = engine.secondsRemaining.value,
                isRunning = true, updatedAtEpochMs = wallAnchor,
                completedFocusSessions = engine.completedFocusSessions, activeFocusInterval = engine.activeFocusInterval.value
            ), _timerSettings.value, nextWallAnchor)
            if (timer.focusIntervals.isNotEmpty()) activeFocus.value = null
            timer.focusIntervals.forEach { interval ->
                snapshotStore.savePendingFocusInterval(interval)
                saveFocusInterval(interval)
                snapshotStore.removePendingFocusInterval(interval.id)
            }
            timer.transitions.lastOrNull()?.let { notifyTransition(it, _timerSettings.value) }
            engine.restore(timer)
            captureActiveTask()
            activeFocus.value = engine.activeFocusInterval.value
        }
        elapsedAnchor = nextElapsedAnchor
        wallAnchor = nextWallAnchor
        goalClock.value = System.currentTimeMillis()
        syncManager.pushStateToWearable()
    }

    private fun tick() {
        scope.launch {
            commands.withLock {
                runCatching { advanceClock() }.onFailure { error ->
                    recoverAfterFailure()
                    reportError(error)
                }
            }
        }
    }

    suspend fun refresh() = withContext(Dispatchers.Main.immediate) {
        awaitReady()
        commands.withLock { advanceClock(); syncManager.pushStateToWearable() }
    }

    suspend fun toggle(requestId: String): SyncAppliedAck = apply(TimerCommand(id = requestId, action = "TOGGLE", baseRevision = -1, sourceDevice = "phone"))

    suspend fun apply(command: TimerCommand): SyncAppliedAck = withContext(Dispatchers.Main.immediate) {
        try {
            awaitReady()
            commands.withLock {
                require(command.id.isNotBlank() && command.id.length <= 256) { "Invalid timer command ID" }
                if (snapshotStore.wasCommandApplied(command.id)) return@withLock SyncAppliedAck(command.id, true)
                check(!_settingsPending.value) { "Finish the pending settings restore before controlling the timer" }
                advanceClock()
                check(TimerSnapshotResolver.commandIsAcceptable(command, syncManager.currentTimerSnapshot().revision)) { "Timer state changed before command arrived" }
                val action = if (command.action == "TOGGLE") {
                    check(engine.currentState.value != PomodoroState.PANIC_MODE) { "Open the app to use panic controls" }
                    if (engine.isRunning.value) "PAUSE" else "START"
                } else command.action
                if (action in START_ACTIONS) {
                    check(engine.currentState.value != PomodoroState.PANIC_MODE) { "Resolve panic mode before starting a session" }
                    foregroundStarter(context)
                }
                when (action) {
                    "START" -> engine.start()
                    "PAUSE" -> engine.pause()
                    "RESET" -> engine.reset()
                    "SKIP" -> engine.skip()
                    "PANIC" -> engine.triggerPanic()
                    "RESOLVE_PANIC" -> engine.resolvePanic()
                    "START_FOCUS" -> engine.syncState(PomodoroState.FOCUS, engine.focusDuration, true)
                    "START_SHORT_BREAK" -> engine.syncState(PomodoroState.SHORT_BREAK, engine.shortBreakDuration, true)
                    "START_LONG_BREAK" -> engine.syncState(PomodoroState.LONG_BREAK, engine.longBreakDuration, true)
                    else -> error("Unsupported timer command")
                }
                captureActiveTask()
                resetClockAnchor()
                syncManager.pushStateToWearable(force = true, commandId = command.id)
                pruneTaskMetadata()
                _error.value = null
                requestTileRefresh(context)
                SyncAppliedAck(command.id, true)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (_ready.value) recoverAfterFailure()
            reportError(error)
            SyncAppliedAck(command.id, false, error.message ?: "Could not save the timer")
        }
    }

    private fun recoverAfterFailure() {
        runCatching {
            val saved = syncManager.reloadTimerSnapshot()
            val recovered = saved?.let { TimerAutomationReconciler.reconcile(it, _timerSettings.value,
                System.currentTimeMillis(), snapshotStore.pendingFocusIntervals()) }
                ?: TimerReconciliation(PomodoroState.FOCUS, _timerSettings.value.focusDurationSeconds, false, 0, emptyList())
            engine.restore(recovered)
            resetClockAnchor()
        }.onFailure { Log.e("TimerRuntime", "Could not recover timer snapshot", it) }
    }

    private fun pruneTaskMetadata() {
        val keep = snapshotStore.pendingFocusIntervals().map { "task_${it.id}" }.toMutableSet()
        engine.activeFocusInterval.value?.let { keep += "task_${it.id}" }
        val obsolete = runtimePreferences.all.keys.filter { it.startsWith("task_") && it !in keep }
        if (obsolete.isNotEmpty()) runtimePreferences.edit().apply { obsolete.forEach(::remove) }.apply()
    }

    fun command(action: String) = scope.launch { apply(TimerCommand(action = action, baseRevision = -1, sourceDevice = "phone")) }

    fun ensureForeground() {
        if (_ready.value && engine.isRunning.value) runCatching { foregroundStarter(context) }.onFailure { foregroundUnavailable(it) }
    }

    fun reportError(error: Throwable) {
        val message = error.message ?: "Timer operation failed"
        val changed = _error.value != message
        if (changed) scope.launch { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        _error.value = message
        Log.e("TimerRuntime", _error.value, error)
        if (changed) requestTileRefresh(context)
    }

    fun foregroundUnavailable(error: Throwable) = scope.launch {
        if (_ready.value) commands.withLock {
            runCatching {
                advanceClock()
                engine.pause()
                resetClockAnchor()
                syncManager.pushStateToWearable(force = true)
            }.onFailure { Log.e("TimerRuntime", "Could not pause after foreground failure", it) }
        }
        reportError(error)
    }

    fun receiveMessage(path: String, sourceNodeId: String, payload: ByteArray) = scope.launch {
        try {
            awaitReady()
            if (path == "/incident/mark") {
                val command = TimerCommand.decode(payload) ?: return@launch
                val action = command.action.split('|', limit = 2)
                require(action[0] == "DISTRACTION") { "Unsupported incident action" }
                val capturedAt = if (action.size == 2) {
                    action[1].toLongOrNull()?.takeIf { it > 0L } ?: error("Invalid capture time")
                } else System.currentTimeMillis()
                markDistractionFromWear(command.id, capturedAt).join()
            } else syncManager.handleMessage(path, sourceNodeId, payload)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportError(error)
            TimerCommand.decode(payload)?.let { command ->
                runCatching { Wearable.getMessageClient(context).sendMessage(sourceNodeId, WearableSyncManager.PATH_TIMER_COMMAND_ACK,
                    SyncAppliedAck(command.id, false, error.message).encode()).await() }
            }
        }
    }

    fun receiveData(path: String, data: DataMap) = scope.launch {
        try {
            awaitReady()
            if (path.startsWith(WearCommandProtocol.COMMAND_PREFIX)) {
                applyDurableCommand(path, data)?.let { publishCommandAck(it) }
                return@launch
            }
            commands.withLock {
                advanceClock()
                if (syncManager.handleData(path, data)) {
                    drainPendingFocus()
                    captureActiveTask()
                    resetClockAnchor()
                    if (engine.isRunning.value) {
                        try { foregroundStarter(context) } catch (error: Exception) { engine.pause(); reportError(error) }
                    }
                    syncManager.pushStateToWearable(force = true)
                }
            }
        } catch (error: Exception) { if (error is CancellationException) throw error; reportError(error) }
    }

    internal suspend fun applyDurableCommand(path: String, data: DataMap): SyncAppliedAck? {
        val id = path.removePrefix(WearCommandProtocol.COMMAND_PREFIX)
        if (!path.startsWith(WearCommandProtocol.COMMAND_PREFIX) || !WearCommandProtocol.validId(id)) return null
        return try {
            awaitReady()
            require(data.getInt("schema_version", 0) == WearCommandProtocol.SCHEMA_VERSION) { "Unsupported command version" }
            require(data.getString("command_id") == id) { "Command identity does not match" }
            val route = data.getString("path")
            require(route in WearCommandProtocol.paths) { "Unsupported command action" }
            val command = data.getByteArray("payload")?.let(TimerCommand::decode) ?: error("Invalid command payload")
            require(command.id == id && command.sourceDevice == "wear") { "Invalid command identity" }
            when (route) {
                "/incident/mark" -> {
                    require(command.action == "DISTRACTION") { "Unsupported incident action" }
                    require(data.containsKey("created_at") && data.getLong("created_at") >= 0L) { "Invalid capture time" }
                    val capturedAt = data.getLong("created_at").takeIf { it > 0L } ?: System.currentTimeMillis()
                    markDistractionFromWear(id, capturedAt).join()
                    check(database.panicDao().getByIncidentId(id) != null) { "Incident could not be saved" }
                    SyncAppliedAck(id, true)
                }
                "/panic/trigger" -> apply(command.copy(action = "PANIC", baseRevision = -1))
                "/panic/resolve" -> apply(command.copy(action = "RESOLVE_PANIC", baseRevision = -1))
                else -> apply(command)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SyncAppliedAck(id, false, error.message ?: "Could not apply watch action")
        }
    }

    fun updateTimerSettings(update: (TimerSettings) -> TimerSettings, resetTimer: Boolean): Job {
        val generation = settingsGeneration.get()
        return scope.launch {
            commands.withLock {
                if (generation != settingsGeneration.get()) return@withLock
                if (_settingsPending.value) { reportError(IllegalStateException("Finish the pending settings restore first")); return@withLock }
                advanceClock()
                val safe = update(_timerSettings.value).sanitized()
                _timerSettings.value = safe
                engine.applySettings(safe, resetTimer)
                resetClockAnchor()
                timerSettingsUpdates.trySend(generation to safe)
                runCatching { syncManager.pushStateToWearable(force = true) }.onFailure(::reportError)
            }
        }
    }

    fun reduceTime(seconds: Int) = scope.launch {
        commands.withLock {
            advanceClock()
            engine.reduceTime(seconds)
            captureActiveTask()
            resetClockAnchor()
            runCatching { syncManager.pushStateToWearable(force = true) }.onFailure(::reportError)
        }
    }

    fun updateGoal(settings: DailyFocusGoalSettings) {
        if (_settingsPending.value) { reportError(IllegalStateException("Finish the pending settings restore first")); return }
        val safe = settings.sanitized()
        _goalSettings.value = safe
        goalClock.value = System.currentTimeMillis()
        goalUpdates.trySend(settingsGeneration.get() to safe)
    }

    fun refreshGoalClock() {
        goalClock.value = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault().id
        if (_goalSettings.value.timeZoneId != zone) updateGoal(_goalSettings.value.copy(timeZoneId = zone))
    }

    suspend fun reloadSettings() {
        awaitReady()
        withContext(Dispatchers.Main.immediate) { commands.withLock { reloadSettingsLocked() } }
    }

    suspend fun <T> withSettingsRestore(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        awaitReady()
        commands.withLock {
            settingsGeneration.incrementAndGet()
            _settingsPending.value = true
            try { block() } finally {
                withContext(NonCancellable) {
                    _settingsPending.value = withContext(Dispatchers.IO) { database.restoreSettingsDao().pending() != null }
                    if (!_settingsPending.value) reloadSettingsLocked()
                }
            }
        }
    }

    private suspend fun reloadSettingsLocked() {
        settingsGeneration.incrementAndGet()
        val timer = withContext(Dispatchers.IO) { timerStore.loadAndMigrate() }
        val goal = withContext(Dispatchers.IO) { goalStore.loadForBackup() }
        advanceClock()
        _timerSettings.value = timer
        engine.applySettings(timer)
        _goalSettings.value = goal
        _settingsPending.value = false
        refreshGoalClock()
        syncManager.refreshRestoredWatchFaceSettings()
        syncManager.pushStateToWearable(force = true)
    }

    fun setPhase(state: PomodoroState) = scope.launch {
        awaitReady()
        commands.withLock {
            advanceClock()
            val duration = when (state) {
                PomodoroState.FOCUS -> engine.focusDuration
                PomodoroState.SHORT_BREAK -> engine.shortBreakDuration
                PomodoroState.LONG_BREAK -> engine.longBreakDuration
                PomodoroState.PANIC_MODE -> 60
            }
            engine.syncState(state, duration, false)
            resetClockAnchor()
            runCatching { syncManager.pushStateToWearable(force = true) }.onFailure(::reportError)
        }
    }

    fun selectTask(id: String) = scope.launch {
        commands.withLock {
            drainPendingFocus()
            if (::engine.isInitialized) captureActiveTask()
            selectTaskNow(id)
        }
    }
    private fun selectTaskNow(id: String) {
        selectedTaskId.value = id
        _selectedTask.value = _tasks.value.firstOrNull { it.id == id } ?: selectedTask.value
        taskPreferences.edit().putString("selected_task_id", id).apply()
    }
    private suspend fun selectNextTask() {
        val next = taskRepository.pending().firstOrNull()
        selectedTaskId.value = next?.id
        _selectedTask.value = next
        taskPreferences.edit().apply { if (next == null) remove("selected_task_id") else putString("selected_task_id", next.id) }.apply()
    }
    fun addTask(title: String, estimate: Int) = scope.launch {
        if (title.isNotBlank()) { val task = taskRepository.add(title, estimate); if (selectedTask.value == null) selectTask(task.id) }
    }
    fun moveTask(id: String, offset: Int) = scope.launch { taskRepository.move(id, offset) }
    fun completeTask(id: String) = scope.launch { val selected = selectedTask.value?.id == id; taskRepository.complete(id); if (selected) selectNextTask() }
    fun skipTask(id: String) = scope.launch { val selected = selectedTask.value?.id == id; taskRepository.skip(id); if (selected) selectNextTask() }
    fun deleteTask(id: String) = scope.launch { val selected = selectedTask.value?.id == id; taskRepository.delete(id); if (selected) selectNextTask() }

    fun setSteps(steps: Int) { if (steps > _steps.value) _steps.value = steps }

    fun markDistractionFromWear(id: String, capturedAt: Long = System.currentTimeMillis()) = scope.launch {
        val existing = database.panicDao().getByIncidentId(id)
        if (existing != null) { syncManager.sendIncidentStatus(id, existing.incidentStatus); return@launch }
        val incident = PanicLogEntity(incidentId = id, incidentType = "DISTRACTION", sourceDevice = "WEAR",
            timestamp = capturedAt, incidentStatus = IncidentStatus.PENDING_DETAIL,
            resolvedAt = maxOf(System.currentTimeMillis(), capturedAt), taskName = selectedTask.value?.title)
        if (repository.insertIncidentIfAbsent(incident) != -1L) activeIncidentAudit.value = database.panicDao().getByIncidentId(id)
        syncManager.sendIncidentStatus(id, IncidentStatus.PENDING_DETAIL)
    }

    internal fun close() {
        if (timeReceiverRegistered) {
            timeReceiverRegistered = false
            context.unregisterReceiver(timeReceiver)
        }
        scope.cancel()
    }

    companion object {
        private val START_ACTIONS = setOf("START", "START_FOCUS", "START_SHORT_BREAK", "START_LONG_BREAK")
        @Volatile private var instance: TimerRuntime? = null
        fun get(context: Context): TimerRuntime = instance ?: synchronized(this) {
            instance ?: TimerRuntime(context.applicationContext).also { instance = it }
        }
        internal fun peek(): TimerRuntime? = instance
        internal fun clearForTest() = synchronized(this) { instance?.close(); instance = null }
        internal fun installForTest(runtime: TimerRuntime) = synchronized(this) { instance?.close(); instance = runtime }
    }
}
