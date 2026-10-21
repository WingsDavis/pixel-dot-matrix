package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.example.core.goal.DailyFocusCalculator
import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.backup.BackupRepository
import com.example.core.backup.BackupCodec
import com.example.core.backup.BackupDocument
import com.example.core.goal.DailyFocusGoalStore
import com.example.core.goal.FocusTargetType
import com.example.core.timer.PomodoroEngine
import com.example.core.timer.PomodoroState
import com.example.core.timer.TimerSettings
import com.example.core.timer.TimerSettingsStore
import com.example.core.timer.SessionTransitionNotifier
import com.example.core.timer.TimerTransitionCause
import com.example.core.timer.TimerAutomationReconciler
import com.example.core.timer.FocusInterval
import com.example.core.sync.TimerSnapshotStore
import com.example.data.database.AppDatabase
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.IncidentStatus
import com.example.data.entity.SessionLogEntity
import com.example.data.entity.FocusTaskEntity
import com.example.data.repository.PomodoroRepository
import com.example.data.repository.FocusTaskRepository
import com.example.domain.FocusInsights
import com.example.domain.InsightsCalculator
import com.example.domain.InsightsFilter
import com.example.core.sync.WearableSyncManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.net.VpnService
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.example.core.protection.DomainProfileStore

class PomodoroViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val database = AppDatabase.getDatabase(application)
    private val backupRepository = BackupRepository(application, database)
    private val restoredBackupSettings = runBlocking(Dispatchers.IO) { runCatching { backupRepository.finishPendingSettings() } }
    private val repository = PomodoroRepository(database.sessionDao(), database.panicDao())
    private val taskRepository = FocusTaskRepository(database.focusTaskDao())
    private val domainProfiles = DomainProfileStore(application)
    private val timerSettingsStore = TimerSettingsStore(application)
    private val initialTimerSettings = runBlocking(Dispatchers.IO) { timerSettingsStore.loadAndMigrate() }
    private val timerSnapshotStore = TimerSnapshotStore(application)
    private val panicRuntimePreferences = application.getSharedPreferences("panic_runtime", Context.MODE_PRIVATE)
    private var livePanicIncidentId: String? = panicRuntimePreferences.getString("active_incident_id", null)
    private val restoredTimerSnapshot = timerSnapshotStore.load()
    private val pendingFocusIntervals = timerSnapshotStore.pendingFocusIntervals()
    private val restoredTimer = restoredTimerSnapshot?.let {
        TimerAutomationReconciler.reconcile(it, initialTimerSettings, System.currentTimeMillis(), pendingFocusIntervals)
    }.also { reconciled ->
        runBlocking(Dispatchers.IO) {
            val pending = pendingFocusIntervals
            (pending + reconciled?.focusIntervals.orEmpty()).distinctBy { it.id }.forEach { interval ->
                repository.insertSessionIfAbsent(interval.toSessionLog())
            }
            if (reconciled != null && restoredTimerSnapshot != null) {
                timerSnapshotStore.save(
                    restoredTimerSnapshot.copy(
                        revision = restoredTimerSnapshot.revision + 1,
                        state = reconciled.state,
                        secondsRemaining = reconciled.secondsRemaining,
                        isRunning = reconciled.isRunning,
                        updatedAtEpochMs = System.currentTimeMillis(),
                        completedFocusSessions = reconciled.completedFocusSessions,
                        activeFocusInterval = reconciled.activeFocusInterval
                    )
                )
            }
            pending.forEach { timerSnapshotStore.removePendingFocusInterval(it.id) }
        }
    }
    private val timerSettingsUpdates = Channel<TimerSettings>(Channel.CONFLATED)
    private val transitionNotifier = SessionTransitionNotifier(application)
    private val focusGoalStore = DailyFocusGoalStore(application)
    private val _focusGoalSettings = MutableStateFlow(runBlocking(Dispatchers.IO) { focusGoalStore.load() })
    private val focusGoalUpdates = Channel<DailyFocusGoalSettings>(Channel.CONFLATED)
    private val goalClock = MutableStateFlow(System.currentTimeMillis())
    private val _focusGoalSaveError = MutableStateFlow<String?>(null)
    val focusGoalSaveError = _focusGoalSaveError.asStateFlow()
    private val _backupState = MutableStateFlow(BackupUiState(
        settingsPending = restoredBackupSettings.isFailure,
        message = if (restoredBackupSettings.isFailure) "A settings restore is pending. Retry it before importing another backup." else null
    ))
    val backupState = _backupState.asStateFlow()
    private var preparedBackup: BackupDocument? = null

    val sessionLogs: StateFlow<List<SessionLogEntity>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val panicLogs: StateFlow<List<PanicLogEntity>> = repository.allPanicLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _insightsFilter = MutableStateFlow(InsightsFilter())
    val insightsFilter = _insightsFilter.asStateFlow()
    val insights: StateFlow<FocusInsights?> = combine(
        repository.allSessions, repository.allPanicLogs, _insightsFilter,
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000L)
            }
        }
    ) { sessions, incidents, filter, _ ->
        InsightsCalculator.calculate(sessions, incidents, filter, System.currentTimeMillis())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateInsightsFilter(filter: InsightsFilter) {
        _insightsFilter.value = filter
    }

    val focusTasks: StateFlow<List<FocusTaskEntity>> = taskRepository.pendingTasks
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val taskSelectionPreferences = application.getSharedPreferences("task_selection", Context.MODE_PRIVATE)
    private val _selectedTaskId = MutableStateFlow(taskSelectionPreferences.getString("selected_task_id", null))
    val selectedTask: StateFlow<FocusTaskEntity?> = combine(focusTasks, _selectedTaskId) { tasks, selectedId ->
        tasks.firstOrNull { it.id == selectedId } ?: tasks.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val engine = PomodoroEngine(
        viewModelScope,
        initialTimerSettings,
        onFocusIntervalRecorded = timerSnapshotStore::savePendingFocusInterval
    ).apply {
        restoredTimer?.let { timer ->
            syncState(
                timer.state,
                timer.secondsRemaining,
                timer.isRunning
            )
            restoreCompletedFocusSessions(timer.completedFocusSessions)
            restoreActiveFocusInterval(timer.activeFocusInterval)
        }
    }

    private val _timerSettings = MutableStateFlow(initialTimerSettings)
    val timerSettings: StateFlow<TimerSettings> = _timerSettings.asStateFlow()

    val currentState: StateFlow<PomodoroState> = engine.currentState
    val secondsRemaining: StateFlow<Int> = engine.secondsRemaining
    val isRunning: StateFlow<Boolean> = engine.isRunning

    val dailyFocusProgress = combine(sessionLogs, _focusGoalSettings, engine.activeFocusInterval, goalClock) { sessions, settings, active, now ->
        DailyFocusCalculator.calculate(sessions, settings, maxOf(now, active?.endTimeMillis ?: now), active)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        DailyFocusCalculator.calculate(emptyList(), _focusGoalSettings.value, goalClock.value)
    )

    private val wearableSyncManager = WearableSyncManager(application, viewModelScope, engine)
    val watchFaceSyncStatus = wearableSyncManager.watchFaceSyncStatus
    val watchFaceConfig = wearableSyncManager.watchFaceConfig
    val watchFacePresets = wearableSyncManager.watchFacePresets
    val watchFaceRecentColors = wearableSyncManager.watchFaceRecentColors
    val syncOutboxItems = wearableSyncManager.syncOutboxItems
    val connectedWatchCount = wearableSyncManager.connectedNodeCount

    // Step Counter state
    private val _stepsTakenInPanic = MutableStateFlow(0)
    val stepsTakenInPanic: StateFlow<Int> = _stepsTakenInPanic.asStateFlow()

    // Incident Response Distraction Audit state
    private val _activeIncidentAudit = MutableStateFlow<PanicLogEntity?>(null)
    val activeIncidentAudit: StateFlow<PanicLogEntity?> = _activeIncidentAudit.asStateFlow()

    // Local DNS Sinkhole VPN state
    private val _isDnsSinkholeActive = MutableStateFlow(false)
    val isDnsSinkholeActive: StateFlow<Boolean> = _isDnsSinkholeActive.asStateFlow()

    val currentTaskName: StateFlow<String> = selectedTask.map { it?.title.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private var activeFocusTaskSnapshot: FocusTaskEntity? = null

    // On-Device Predictive Vulnerability Engine
    val predictiveVulnerabilityText: StateFlow<String> = panicLogs.map { logs ->
        if (logs.isEmpty()) {
            "Threat Model Score: 0%. No telemetry recorded yet. Focus is stable."
        } else {
            val calendar = java.util.Calendar.getInstance()
            val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            val currentDay = calendar.get(java.util.Calendar.DAY_OF_WEEK)

            // Calculate peak risk hours from local Room database logs
            val hourlyGroups = logs.groupBy {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = it.timestamp }
                cal.get(java.util.Calendar.HOUR_OF_DAY)
            }
            val peakHour = hourlyGroups.maxByOrNull { it.value.size }?.key ?: 15

            // Compute dynamic risk rating based on recent incidents
            val baseRisk = (30 + (logs.size * 12)).coerceAtMost(95)
            val isPeakTime = currentHour in (peakHour - 2)..(peakHour + 2)
            val riskPct = if (isPeakTime) (baseRisk + 15).coerceAtMost(98) else baseRisk

            val dayName = when (currentDay) {
                java.util.Calendar.THURSDAY -> "Thursday"
                java.util.Calendar.FRIDAY -> "Friday"
                java.util.Calendar.SATURDAY -> "Saturday"
                java.util.Calendar.SUNDAY -> "Sunday"
                else -> "afternoon"
            }

            "Vulnerability Level: $riskPct% Risk. Detected distraction pattern peak at ${String.format("%02d:00", peakHour)} on $dayName. Proactively enable DNS Sinkhole."
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Analyzing threat model telemetry...")

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var stepDetectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private var initialStepCount = -1f
    private val goalTimeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshGoalClock()
    }

    init {
        if (currentState.value != PomodoroState.PANIC_MODE) livePanicIncidentId = null
        ContextCompat.registerReceiver(
            application,
            goalTimeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshGoalClock()
        viewModelScope.launch {
            while (isActive) {
                refreshGoalClock()
                delay(15_000L)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (settings in focusGoalUpdates) {
                runCatching { focusGoalStore.save(settings) }
                    .onSuccess { _focusGoalSaveError.value = null }
                    .onFailure {
                        _focusGoalSaveError.value = "Could not save the daily target. Try changing it again."
                        Log.e("PomodoroViewModel", "Failed to save daily focus target", it)
                    }
            }
        }
        viewModelScope.launch {
            dailyFocusProgress.map { it.copy(focusedSeconds = it.focusedSeconds / 60 * 60) }
                .distinctUntilChanged()
                .collect { wearableSyncManager.pushDailyFocusGoal(it) }
        }
        viewModelScope.launch {
            engine.focusIntervals.collect { interval ->
                val task = activeFocusTaskSnapshot ?: selectedTask.value
                repository.insertSessionIfAbsent(interval.toSessionLog(task))
                wearableSyncManager.pushStateToWearable(force = true)
                timerSnapshotStore.removePendingFocusInterval(interval.id)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (settings in timerSettingsUpdates) timerSettingsStore.write(settings)
        }

        viewModelScope.launch {
            engine.transitionEvents.collect { transition ->
                if (transition.cause == TimerTransitionCause.COMPLETED) {
                    transitionNotifier.notify(transition, _timerSettings.value)
                    if (transition.from == PomodoroState.FOCUS) recordFocusForSelectedTask()
                }
            }
        }

        viewModelScope.launch {
            combine(focusTasks, selectedTask) { tasks, selected -> tasks to selected }.collect { (tasks, selected) ->
                if (selected != null && selected.id != _selectedTaskId.value) selectTask(selected.id)
                val selectedIndex = tasks.indexOfFirst { it.id == selected?.id }
                wearableSyncManager.pushTaskSummaries(
                    selected?.title,
                    tasks.getOrNull(selectedIndex + 1)?.title
                )
            }
        }

        restoredTimer?.transitions?.lastOrNull()?.let { transition ->
            transitionNotifier.notify(transition, initialTimerSettings)
        }

        // Check for pending audits
        viewModelScope.launch {
            val pendingAudit = repository.getPendingAuditLog()
            if (pendingAudit != null) {
                _activeIncidentAudit.value = pendingAudit
            }
        }

        // Synchronize state changes to wearable devices
        viewModelScope.launch {
            combine(currentState, secondsRemaining, isRunning) { _, _, _ -> }
                .collect {
                    wearableSyncManager.pushStateToWearable()
                }
        }

        viewModelScope.launch {
            var previousState = currentState.value
            var previousRunning = false
            combine(currentState, isRunning) { state, running -> state to running }.collect { (state, running) ->
                if (state == PomodoroState.FOCUS && running && (previousState != PomodoroState.FOCUS || !previousRunning)) {
                    activeFocusTaskSnapshot = selectedTask.value
                }
                previousState = state
                previousRunning = running
            }
        }

        viewModelScope.launch {
            var lastState = PomodoroState.FOCUS

            currentState.collect { state ->
                val now = System.currentTimeMillis()
                if (domainProfiles.autoActivateDuringFocus()) {
                    val shouldEnable = state == PomodoroState.FOCUS
                    if (shouldEnable != _isDnsSinkholeActive.value && (!shouldEnable || VpnService.prepare(application) == null)) {
                        setDnsSinkholeEnabled(application, shouldEnable)
                    }
                }
                if (state == PomodoroState.PANIC_MODE) {
                    // Triggered Panic Mode! Start recording steps
                    _stepsTakenInPanic.value = 0
                    initialStepCount = -1f
                    registerStepSensor()

                    // Inform the watch about immediate panic trigger message
                    wearableSyncManager.sendPanicTriggerMessage()

                    // Start Phone Haptics
                    startPhoneHaptics(application)

                    val incidentId = livePanicIncidentId ?: java.util.UUID.randomUUID().toString().also {
                        check(panicRuntimePreferences.edit().putString("active_incident_id", it).commit())
                        livePanicIncidentId = it
                    }
                    repository.insertIncidentIfAbsent(
                        PanicLogEntity(
                            incidentId = incidentId,
                            timestamp = now,
                            heartRateSpike = 138, // simulated heart rate spike triggering this
                            stepsTaken = 0,
                            resolvedAt = null,
                            taskName = selectedTask.value?.title
                        )
                    )
                } else if (lastState == PomodoroState.PANIC_MODE) {
                    // Resolved Panic Mode! Unregister step sensor and save log
                    unregisterStepSensor()
                    stopPhoneHaptics(application)
                    val resolvedIncidentId = livePanicIncidentId
                    livePanicIncidentId = null
                    viewModelScope.launch {
                        val activePanic = resolvedIncidentId?.let { database.panicDao().getByIncidentId(it) }
                        if (activePanic != null && IncidentStatus.canTransition(activePanic.incidentStatus, IncidentStatus.PENDING_DETAIL)) {
                            val updatedPanic = activePanic.copy(
                                stepsTaken = _stepsTakenInPanic.value,
                                resolvedAt = now,
                                incidentStatus = "PENDING_DETAIL"
                            )
                            repository.updatePanicLog(updatedPanic)
                            _activeIncidentAudit.value = updatedPanic
                        }
                        if (panicRuntimePreferences.getString("active_incident_id", null) == resolvedIncidentId) {
                            panicRuntimePreferences.edit().remove("active_incident_id").commit()
                        }
                    }
                }

                lastState = state
            }
        }
    }

    fun startTimer() = engine.start()
    fun pauseTimer() = engine.pause()
    fun resetTimer() = engine.reset()
    fun skipTimer() = engine.skip()

    private fun FocusInterval.toSessionLog(task: FocusTaskEntity? = null) = SessionLogEntity(
        sessionType = "FOCUS",
        startTimeMillis = startTimeMillis,
        durationSeconds = durationSeconds,
        status = if (completed) "COMPLETED" else "INTERRUPTED",
        taskId = task?.id,
        taskName = task?.title,
        timestamp = endTimeMillis,
        recordId = id
    )

    fun setDailyTargetEnabled(enabled: Boolean) = updateFocusGoal(_focusGoalSettings.value.copy(enabled = enabled))
    fun setDailyTargetType(type: FocusTargetType) = updateFocusGoal(_focusGoalSettings.value.copy(type = type))
    fun setDailyTarget(value: Int) {
        val settings = _focusGoalSettings.value
        updateFocusGoal(
            if (settings.type == FocusTargetType.MINUTES) settings.copy(targetMinutes = value)
            else settings.copy(targetSessions = value)
        )
    }
    fun setDailyResetHour(hour: Int) = updateFocusGoal(_focusGoalSettings.value.copy(resetHour = hour))

    private fun updateFocusGoal(settings: DailyFocusGoalSettings) {
        val safe = settings.sanitized()
        _focusGoalSettings.value = safe
        goalClock.value = System.currentTimeMillis()
        focusGoalUpdates.trySend(safe)
    }

    private fun refreshGoalClock() {
        goalClock.value = System.currentTimeMillis()
        val zoneId = java.util.TimeZone.getDefault().id
        if (_focusGoalSettings.value.timeZoneId != zoneId) {
            updateFocusGoal(_focusGoalSettings.value.copy(timeZoneId = zoneId))
        }
    }

    fun setFocusDurationMinutes(minutes: Int) = updateTimerSetting(
        _timerSettings.value.copy(focusDurationSeconds = minutes * 60),
        resetTimer = true
    )

    fun setShortBreakDurationMinutes(minutes: Int) = updateTimerSetting(
        _timerSettings.value.copy(shortBreakDurationSeconds = minutes * 60),
        resetTimer = true
    )

    fun setLongBreakDurationMinutes(minutes: Int) = updateTimerSetting(
        _timerSettings.value.copy(longBreakDurationSeconds = minutes * 60),
        resetTimer = true
    )

    fun setLongBreakCadence(cadence: Int) = updateTimerSetting(
        _timerSettings.value.copy(longBreakCadence = cadence),
        resetTimer = false
    )

    fun setAutoStartBreaks(enabled: Boolean) = updateTimerSetting(
        _timerSettings.value.copy(autoStartBreaks = enabled),
        resetTimer = false
    )

    fun setAutoStartFocus(enabled: Boolean) = updateTimerSetting(
        _timerSettings.value.copy(autoStartFocus = enabled),
        resetTimer = false
    )

    fun setTransitionSoundEnabled(enabled: Boolean) = updateTimerSetting(
        _timerSettings.value.copy(transitionSoundEnabled = enabled),
        resetTimer = false
    )

    fun setTransitionNotificationEnabled(enabled: Boolean) = updateTimerSetting(
        _timerSettings.value.copy(transitionNotificationEnabled = enabled),
        resetTimer = false
    )

    fun setTransitionHapticEnabled(enabled: Boolean) = updateTimerSetting(
        _timerSettings.value.copy(transitionHapticEnabled = enabled),
        resetTimer = false
    )

    private fun updateTimerSetting(settings: TimerSettings, resetTimer: Boolean) {
        val sanitized = settings.sanitized()
        _timerSettings.value = sanitized
        engine.applySettings(sanitized, resetTimer)
        timerSettingsUpdates.trySend(sanitized)
        wearableSyncManager.pushStateToWearable(force = true)
    }

    fun setTimerState(state: PomodoroState) {
        val duration = when (state) {
            PomodoroState.FOCUS -> engine.focusDuration
            PomodoroState.SHORT_BREAK -> engine.shortBreakDuration
            PomodoroState.LONG_BREAK -> engine.longBreakDuration
            PomodoroState.PANIC_MODE -> 60
        }
        engine.syncState(state, duration, false)
    }

    fun addTask(title: String, estimateSessions: Int = 1) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val task = taskRepository.add(title, estimateSessions)
            if (selectedTask.value == null) selectTask(task.id)
        }
    }

    fun selectTask(id: String) {
        _selectedTaskId.value = id
        taskSelectionPreferences.edit().putString("selected_task_id", id).apply()
    }

    fun moveTask(id: String, offset: Int) = viewModelScope.launch { taskRepository.move(id, offset) }

    fun completeTask(id: String) = viewModelScope.launch {
        val wasSelected = selectedTask.value?.id == id
        taskRepository.complete(id)
        if (wasSelected) selectNextTask()
    }

    fun skipTask(id: String) = viewModelScope.launch {
        val wasSelected = selectedTask.value?.id == id
        taskRepository.skip(id)
        if (wasSelected) selectNextTask()
    }

    fun deleteTask(id: String) = viewModelScope.launch {
        val wasSelected = selectedTask.value?.id == id
        taskRepository.delete(id)
        if (wasSelected) selectNextTask()
    }

    private suspend fun recordFocusForSelectedTask() {
        val id = selectedTask.value?.id ?: return
        if (taskRepository.recordCompletedFocus(id)) selectNextTask()
    }

    private suspend fun selectNextTask() {
        val next = taskRepository.pending().firstOrNull()
        _selectedTaskId.value = next?.id
        taskSelectionPreferences.edit().apply {
            if (next == null) remove("selected_task_id") else putString("selected_task_id", next.id)
        }.apply()
    }

    fun triggerPanic() {
        engine.triggerPanic()
    }

    fun resolvePanic() {
        if (currentState.value == PomodoroState.PANIC_MODE && _stepsTakenInPanic.value >= 50) {
            engine.resolvePanic()
        }
    }

    /**
     * Completes and saves the forensic distraction audit metadata to the SQLite database.
     */
    fun submitIncidentAudit(targetApp: String, triggerReason: String, notes: String) {
        val auditLog = _activeIncidentAudit.value ?: return
        viewModelScope.launch {
            repository.updatePanicLog(
                auditLog.copy(
                    targetApp = targetApp,
                    triggerReason = triggerReason,
                    forensicNotes = notes,
                    incidentStatus = IncidentStatus.CLOSED
                )
            )
            wearableSyncManager.sendIncidentStatus(auditLog.incidentId, IncidentStatus.CLOSED)
            _activeIncidentAudit.value = null
        }
    }

    fun markDistractionFromWear(incidentId: String) {
        viewModelScope.launch {
            if (database.panicDao().getByIncidentId(incidentId) != null) return@launch
            val incident = PanicLogEntity(
                incidentId = incidentId,
                incidentType = "DISTRACTION",
                sourceDevice = "WEAR",
                incidentStatus = IncidentStatus.PENDING_DETAIL,
                resolvedAt = System.currentTimeMillis(),
                taskName = selectedTask.value?.title
            )
            if (repository.insertIncidentIfAbsent(incident) != -1L) {
                _activeIncidentAudit.value = incident
            }
            wearableSyncManager.sendIncidentStatus(incidentId, IncidentStatus.PENDING_DETAIL)
        }
    }

    /**
     * Dismisses the active distraction audit questionnaire.
     */
    fun dismissIncidentAudit() {
        val incident = _activeIncidentAudit.value ?: return
        viewModelScope.launch {
            if (IncidentStatus.canTransition(incident.incidentStatus, IncidentStatus.DISMISSED)) {
                repository.updatePanicLog(incident.copy(incidentStatus = IncidentStatus.DISMISSED))
                wearableSyncManager.sendIncidentStatus(incident.incidentId, IncidentStatus.DISMISSED)
            }
            _activeIncidentAudit.value = null
        }
    }

    /**
     * Toggles the system-level Local DNS Sinkhole VPN service.
     */
    fun toggleDnsSinkhole(context: Context) {
        setDnsSinkholeEnabled(context, !_isDnsSinkholeActive.value)
    }

    fun setDnsSinkholeEnabled(context: Context, enabled: Boolean) {
        _isDnsSinkholeActive.value = enabled

        val intent = Intent(context, com.example.service.LocalDnsSinkholeVpnService::class.java).apply {
            action = if (enabled) com.example.service.LocalDnsSinkholeVpnService.ACTION_START else com.example.service.LocalDnsSinkholeVpnService.ACTION_STOP
        }
        try {
            if (!enabled) {
                context.stopService(intent)
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            _isDnsSinkholeActive.value = false
            Log.e("PomodoroViewModel", "Failed to transition DNS Sinkhole VPN state", e)
        }
    }

    /**
     * Sends an immediate Biometric Frustration Interception alert to the Pixel Watch.
     */
    fun triggerFrustrationAlert() {
        viewModelScope.launch {
            repository.insertIncidentIfAbsent(
                PanicLogEntity(
                    incidentType = "FRUSTRATION",
                    sourceDevice = "PHONE",
                    incidentStatus = IncidentStatus.CLOSED,
                    resolvedAt = System.currentTimeMillis(),
                    taskName = selectedTask.value?.title
                )
            )
        }
        wearableSyncManager.sendFrustrationAlertMessage()
    }

    fun temporarilyUnblockDomains(durationMs: Long) {
        domainProfiles.temporarilyUnblock(durationMs)
        viewModelScope.launch {
            repository.insertIncidentIfAbsent(
                PanicLogEntity(
                    incidentType = "PROTECTION_OVERRIDE",
                    sourceDevice = "PHONE",
                    incidentStatus = IncidentStatus.CLOSED,
                    resolvedAt = System.currentTimeMillis(),
                    forensicNotes = "DNS protection temporarily disabled for ${durationMs / 60_000} minutes"
                )
            )
        }
    }

    /**
     * Syncs the selected custom watch face style to the watch.
     */
    fun selectWatchFaceStyle(styleId: String) {
        wearableSyncManager.pushWatchFaceStyle(styleId)
    }

    /**
     * Syncs custom text to the watch face complication.
     */
    fun sendCustomWatchFaceText(text: String) {
        wearableSyncManager.pushCustomText(text)
    }

    /**
     * Syncs the full phone-authored watch face configuration to the watch.
     */
    fun syncWatchFaceConfig(
        timerColor: String,
        secondsColor: String,
        idleTimeColor: String,
        customText: String,
        leftSlotMode: String,
        bottomRightSlotMode: String,
        showPanicLogo: Boolean,
        ambientStyle: String,
        themePreset: String
    ) {
        wearableSyncManager.pushWatchFaceConfig(
            timerColor = timerColor,
            secondsColor = secondsColor,
            idleTimeColor = idleTimeColor,
            customText = customText,
            leftSlotMode = leftSlotMode,
            bottomRightSlotMode = bottomRightSlotMode,
            showPanicLogo = showPanicLogo,
            ambientStyle = ambientStyle,
            themePreset = themePreset
        )
    }

    fun applyWatchFacePreset(id: String) {
        wearableSyncManager.applyWatchFacePreset(id)
    }

    fun saveWatchFaceDraft(config: com.example.core.sync.WatchFaceConfig) {
        wearableSyncManager.saveWatchFaceDraft(config)
    }

    fun saveWatchFacePreset(name: String, config: com.example.core.sync.WatchFaceConfig) = wearableSyncManager.saveWatchFacePreset(name, config)
    fun duplicateWatchFacePreset(id: String, name: String) = wearableSyncManager.duplicateWatchFacePreset(id, name)
    fun renameWatchFacePreset(id: String, name: String) = wearableSyncManager.renameWatchFacePreset(id, name)
    fun deleteWatchFacePreset(id: String) = wearableSyncManager.deleteWatchFacePreset(id)
    fun rememberWatchFaceColor(hex: String) = wearableSyncManager.rememberWatchFaceColor(hex)

    fun resetWatchFace() {
        wearableSyncManager.resetWatchFaceConfig()
    }

    fun retryPendingSync() = wearableSyncManager.retryPendingSync()
    fun resumePendingSync() {
        refreshGoalClock()
        wearableSyncManager.flushPendingSync()
    }

    fun exportBackup(uri: Uri, kind: BackupExportKind) = backupOperation {
        val document = backupRepository.snapshot()
        withContext(Dispatchers.IO) {
            val bytes = when (kind) {
                BackupExportKind.JSON -> BackupCodec.encode(document)
                BackupExportKind.SESSIONS -> BackupCodec.sessionsCsv(document.sessions)
                BackupExportKind.INCIDENTS -> BackupCodec.incidentsCsv(document.incidents)
            }
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("The selected location could not be opened")
            stream.use { it.write(bytes); it.flush() }
        }
        _backupState.value = _backupState.value.copy(message = "Export saved. Backups are unencrypted.")
    }

    fun prepareBackupImport(uri: Uri) = backupOperation {
        val document = withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: error("The selected backup could not be opened")
            val bytes = stream.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size().toLong() + count <= BackupCodec.MAX_BYTES) { "Backup exceeds the supported size limit" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            BackupCodec.decode(bytes)
        }
        preparedBackup = document
        _backupState.value = _backupState.value.copy(message = null, preview = BackupPreview(
            document.sessions.size, document.incidents.size, document.tasks.size
        ))
    }

    fun cancelBackupImport() {
        if (_backupState.value.busy) return
        preparedBackup = null
        _backupState.value = _backupState.value.copy(preview = null)
    }

    fun confirmBackupImport() = backupOperation {
        val document = preparedBackup ?: error("Select the backup again")
        val result = backupRepository.restore(document)
        preparedBackup = null
        _backupState.value = _backupState.value.copy(preview = null, settingsPending = result.settingsPending,
            message = "Merged ${result.sessionsAdded} sessions, ${result.incidentsAdded} incidents, ${result.tasksAdded} tasks." +
                if (result.settingsPending) " Settings restore is pending; retry to finish." else " Preferences restored; watch sync queued.")
        if (!result.settingsPending) refreshRestoredSettings()
    }

    fun retryBackupSettings() = backupOperation {
        backupRepository.finishPendingSettings()
        refreshRestoredSettings()
        _backupState.value = _backupState.value.copy(settingsPending = false, message = "Preferences restored; watch sync queued.")
    }

    private suspend fun refreshRestoredSettings() {
        val timer = withContext(Dispatchers.IO) { timerSettingsStore.loadAndMigrate() }
        _timerSettings.value = timer
        engine.applySettings(timer, resetTimer = false)
        _focusGoalSettings.value = withContext(Dispatchers.IO) { focusGoalStore.load() }
        refreshGoalClock()
        withContext(Dispatchers.IO) { wearableSyncManager.refreshRestoredWatchFaceSettings() }
        wearableSyncManager.pushStateToWearable(force = true)
    }

    private fun backupOperation(block: suspend () -> Unit) = viewModelScope.launch {
        if (_backupState.value.busy) return@launch
        _backupState.value = _backupState.value.copy(busy = true, message = null)
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            _backupState.value = _backupState.value.copy(message = error.message ?: "The operation could not be completed")
            Log.e("PomodoroViewModel", "Local backup operation failed", error)
        } finally { _backupState.value = _backupState.value.copy(busy = false) }
    }

    suspend fun deleteLocalData(target: String): String = withContext(Dispatchers.IO) {
        check(!_backupState.value.busy) { "Wait for the current backup operation" }
        if (target in setOf("timer and daily target preferences", "watch face preferences", "watch artwork and face settings", "protection profiles")) {
            check(database.restoreSettingsDao().pending() == null) { "Finish the pending settings restore before deleting preferences" }
        }
        when (target) {
            "session history" -> database.sessionDao().clearAllSessions()
            "incident history" -> database.panicDao().clearAllPanicLogs()
            "tasks" -> database.focusTaskDao().clearAll()
            "pending sync operations" -> database.syncOutboxDao().clearAll()
            "timer and daily target preferences" -> {
                check(database.restoreSettingsDao().pending() == null) { "Finish the pending settings restore before resetting preferences" }
                timerSettingsStore.write(TimerSettings())
                focusGoalStore.save(DailyFocusGoalSettings())
                withContext(Dispatchers.Main) {
                    _timerSettings.value = timerSettingsStore.loadAndMigrate()
                    engine.applySettings(_timerSettings.value, resetTimer = false)
                    _focusGoalSettings.value = focusGoalStore.load()
                    refreshGoalClock()
                    wearableSyncManager.pushStateToWearable(force = true)
                }
            }
            "watch face preferences" -> wearableSyncManager.clearWatchFacePreferences()
            "custom logo media" -> { wearableSyncManager.resetWatchFaceLogoAndAwait(); return@withContext "Logo reset queued for the watch" }
            "watch artwork and face settings" -> {
                wearableSyncManager.clearWatchFacePreferences()
                wearableSyncManager.resetWatchFaceLogoAndAwait()
                return@withContext "Face preferences deleted; logo reset queued for the watch"
            }
            "protection profiles" -> domainProfiles.clearAll()
            else -> error("Unknown data category")
        }
        "Deleted $target"
    }

    fun syncWatchFaceLogo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalArgumentException("Unable to decode selected image")
                val side = minOf(source.width, source.height)
                val cropped = Bitmap.createBitmap(
                    source,
                    (source.width - side) / 2,
                    (source.height - side) / 2,
                    side,
                    side
                )
                val scaled = Bitmap.createScaledBitmap(cropped, WATCH_FACE_LOGO_SIZE, WATCH_FACE_LOGO_SIZE, true)
                val bytes = ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
                syncWatchFaceLogo(source, 1f, 0f, 0f)
            } catch (e: Exception) {
                Log.e("PomodoroViewModel", "Failed to prepare custom watch face logo", e)
            }
        }
    }

    fun syncWatchFaceLogo(source: Bitmap, zoom: Float, offsetX: Float, offsetY: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val side = minOf(source.width, source.height)
                val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
                val output = Bitmap.createBitmap(WATCH_FACE_LOGO_SIZE, WATCH_FACE_LOGO_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val scaledSide = (WATCH_FACE_LOGO_SIZE * zoom.coerceIn(1f, 2f)).toInt()
                val scaled = Bitmap.createScaledBitmap(square, scaledSide, scaledSide, true)
                val left = (WATCH_FACE_LOGO_SIZE - scaledSide) / 2f + offsetX.coerceIn(-0.3f, 0.3f) * WATCH_FACE_LOGO_SIZE
                val top = (WATCH_FACE_LOGO_SIZE - scaledSide) / 2f + offsetY.coerceIn(-0.3f, 0.3f) * WATCH_FACE_LOGO_SIZE
                canvas.drawBitmap(scaled, left, top, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                val bytes = ByteArrayOutputStream().use { outputStream ->
                    output.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.toByteArray()
                }
                wearableSyncManager.pushWatchFaceLogo(bytes)
            }.onFailure { error -> Log.e("PomodoroViewModel", "Failed to sync transformed logo", error) }
        }
    }

    // Support step simulation for manual trigger and easy testing
    fun incrementStepsSimulated() {
        if (currentState.value == PomodoroState.PANIC_MODE) {
            _stepsTakenInPanic.value += 5
        }
    }

    companion object {
        private const val WATCH_FACE_LOGO_SIZE = 256
    }

    fun setSteps(steps: Int) {
        if (steps > _stepsTakenInPanic.value) {
            _stepsTakenInPanic.value = steps
        }
    }

    // Adaptive Timers based on Biometrics
    fun receiveBiometricStressSignal() {
        if (currentState.value == PomodoroState.FOCUS) {
            // High HR detected, proactively reduce remaining focus time by 5 minutes
            engine.reduceTime(5 * 60)
        }
    }

    private var vibratorJob: kotlinx.coroutines.Job? = null

    private fun startPhoneHaptics(context: Context) {
        if (vibratorJob != null) return
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (!vibrator.hasVibrator()) return

        vibratorJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var cycle = 1
            while (isActive) {
                val duration = (150L * cycle).coerceAtMost(800L)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val effect = android.os.VibrationEffect.createOneShot(duration, (100 + (cycle * 20)).coerceAtMost(255))
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
                kotlinx.coroutines.delay(duration + 150L)
                cycle = if (cycle >= 5) 1 else cycle + 1
            }
        }
    }

    private fun stopPhoneHaptics(context: Context) {
        vibratorJob?.cancel()
        vibratorJob = null
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.cancel()
    }

    private fun registerStepSensor() {
        stepDetectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun unregisterStepSensor() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (currentState.value != PomodoroState.PANIC_MODE) return
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                _stepsTakenInPanic.value += 1
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(goalTimeReceiver)
        unregisterStepSensor()
        wearableSyncManager.unregisterListeners()
    }
}
