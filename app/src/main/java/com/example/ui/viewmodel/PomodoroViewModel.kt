package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.backup.BackupRepository
import com.example.core.backup.BackupCodec
import com.example.core.backup.BackupDocument
import com.example.core.goal.DailyFocusGoalStore
import com.example.core.goal.FocusTargetType
import com.example.core.timer.TimerRuntime
import com.example.core.timer.PomodoroState
import com.example.core.timer.TimerSettings
import com.example.core.timer.TimerSettingsStore
import com.example.data.database.AppDatabase
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.IncidentStatus
import com.example.data.repository.PomodoroRepository
import com.example.domain.FocusInsights
import com.example.domain.InsightsCalculator
import com.example.domain.InsightsFilter
import android.content.Intent
import com.example.core.media.LogoTransform
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.example.core.protection.DomainProfileStore

class PomodoroViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val runtime = TimerRuntime.get(application).also { runBlocking(Dispatchers.IO) { it.awaitReady() } }
    private val database = AppDatabase.getDatabase(application)
    private val backupRepository = BackupRepository(application, database,
        restoreFace = { runtime.syncManager.restoreWatchFacePreferences(it) })
    private val repository = PomodoroRepository(database.sessionDao(), database.panicDao())
    private val domainProfiles = DomainProfileStore(application)
    private val timerSettingsStore = TimerSettingsStore(application)
    private val focusGoalStore = DailyFocusGoalStore(application)
    private val _focusGoalSettings = runtime.goalSettings
    val focusGoalSaveError = runtime.focusGoalSaveError
    private val _backupState = MutableStateFlow(BackupUiState(
        settingsPending = runtime.settingsPending.value,
        message = if (runtime.settingsPending.value) "A settings restore is pending. Retry it before importing another backup." else null
    ))
    val backupState = _backupState.asStateFlow()
    private var preparedBackup: BackupDocument? = null

    val sessionLogs = runtime.sessionLogs

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

    val focusTasks = runtime.focusTasks
    val selectedTask = runtime.selectedTask
    val engine = runtime.engine
    val timerSettings = runtime.timerSettings

    val currentState: StateFlow<PomodoroState> = engine.currentState
    val secondsRemaining: StateFlow<Int> = engine.secondsRemaining
    val isRunning: StateFlow<Boolean> = engine.isRunning

    val dailyFocusProgress = runtime.dailyFocusProgress

    private val wearableSyncManager = runtime.syncManager
    val watchFaceSyncStatus = wearableSyncManager.watchFaceSyncStatus
    val watchFaceConfig = wearableSyncManager.watchFaceConfig
    val watchFaceEditorConfig = wearableSyncManager.watchFaceEditorConfig
    val watchFacePresets = wearableSyncManager.watchFacePresets
    val watchFaceRecentColors = wearableSyncManager.watchFaceRecentColors
    val watchFaceLogoState = wearableSyncManager.watchFaceLogoState
    val syncOutboxItems = wearableSyncManager.syncOutboxItems
    val connectedWatchCount = wearableSyncManager.connectedNodeCount

    // Step Counter state
    val stepsTakenInPanic = runtime.stepsTakenInPanic

    // Incident Response Distraction Audit state
    private val _activeIncidentAudit = runtime.activeIncidentAudit
    val activeIncidentAudit: StateFlow<PanicLogEntity?> = _activeIncidentAudit.asStateFlow()

    // Local DNS Sinkhole VPN state
    private val _isDnsSinkholeActive = MutableStateFlow(false)
    val isDnsSinkholeActive: StateFlow<Boolean> = _isDnsSinkholeActive.asStateFlow()

    val currentTaskName: StateFlow<String> = selectedTask.map { it?.title.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

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
    private val uiActive = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(currentState, uiActive) { state, active -> state == PomodoroState.PANIC_MODE && active }
                .distinctUntilChanged().collect { active ->
                if (active) {
                    registerStepSensor()
                    startPhoneHaptics(application)
                } else {
                    unregisterStepSensor()
                    stopPhoneHaptics(application)
                }
            }
        }
        viewModelScope.launch {
            currentState.collect { state ->
                if (domainProfiles.autoActivateDuringFocus()) {
                    val shouldEnable = state == PomodoroState.FOCUS
                    if (shouldEnable != _isDnsSinkholeActive.value && (!shouldEnable || VpnService.prepare(application) == null)) {
                        setDnsSinkholeEnabled(application, shouldEnable)
                    }
                }
            }
        }
    }

    fun startTimer() = runtime.command("START")
    fun pauseTimer() = runtime.command("PAUSE")
    fun resetTimer() = runtime.command("RESET")
    fun skipTimer() = runtime.command("SKIP")
    fun resolvePanicManually() = runtime.command("RESOLVE_PANIC")
    fun setUiActive(active: Boolean) {
        uiActive.value = active
        if (!active) {
            unregisterStepSensor()
            stopPhoneHaptics(getApplication())
            runtime.ensureForeground()
        }
    }

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
        runtime.updateGoal(settings)
    }

    private fun refreshGoalClock() {
        runtime.refreshGoalClock()
    }

    fun setFocusDurationMinutes(minutes: Int) = updateTimerSetting(
        { it.copy(focusDurationSeconds = minutes * 60) },
        resetTimer = true
    )

    fun setShortBreakDurationMinutes(minutes: Int) = updateTimerSetting(
        { it.copy(shortBreakDurationSeconds = minutes * 60) },
        resetTimer = true
    )

    fun setLongBreakDurationMinutes(minutes: Int) = updateTimerSetting(
        { it.copy(longBreakDurationSeconds = minutes * 60) },
        resetTimer = true
    )

    fun setLongBreakCadence(cadence: Int) = updateTimerSetting(
        { it.copy(longBreakCadence = cadence) },
        resetTimer = false
    )

    fun setAutoStartBreaks(enabled: Boolean) = updateTimerSetting(
        { it.copy(autoStartBreaks = enabled) },
        resetTimer = false
    )

    fun setAutoStartFocus(enabled: Boolean) = updateTimerSetting(
        { it.copy(autoStartFocus = enabled) },
        resetTimer = false
    )

    fun setTransitionSoundEnabled(enabled: Boolean) = updateTimerSetting(
        { it.copy(transitionSoundEnabled = enabled) },
        resetTimer = false
    )

    fun setTransitionNotificationEnabled(enabled: Boolean) = updateTimerSetting(
        { it.copy(transitionNotificationEnabled = enabled) },
        resetTimer = false
    )

    fun setTransitionHapticEnabled(enabled: Boolean) = updateTimerSetting(
        { it.copy(transitionHapticEnabled = enabled) },
        resetTimer = false
    )

    private fun updateTimerSetting(settings: (TimerSettings) -> TimerSettings, resetTimer: Boolean) {
        runtime.updateTimerSettings(settings, resetTimer)
    }

    fun setTimerState(state: PomodoroState) {
        runtime.setPhase(state)
    }

    fun addTask(title: String, estimateSessions: Int = 1) = runtime.addTask(title, estimateSessions)
    fun selectTask(id: String) = runtime.selectTask(id)
    fun moveTask(id: String, offset: Int) = runtime.moveTask(id, offset)
    fun completeTask(id: String) = runtime.completeTask(id)
    fun skipTask(id: String) = runtime.skipTask(id)
    fun deleteTask(id: String) = runtime.deleteTask(id)

    fun triggerPanic() {
        runtime.command("PANIC")
    }

    fun resolvePanic() {
        if (currentState.value == PomodoroState.PANIC_MODE && stepsTakenInPanic.value >= 50) {
            runtime.command("RESOLVE_PANIC")
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
        runtime.markDistractionFromWear(incidentId)
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

    fun editWatchFaceDraft(update: (com.example.core.sync.WatchFaceConfig) -> com.example.core.sync.WatchFaceConfig) =
        wearableSyncManager.editWatchFaceDraft(update)

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
        runtime.scope.launch { runCatching { runtime.refresh() }.onFailure(runtime::reportError) }
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
        val result = runtime.withSettingsRestore { backupRepository.restore(document) }
        preparedBackup = null
        _backupState.value = _backupState.value.copy(preview = null, settingsPending = result.settingsPending,
            message = "Merged ${result.sessionsAdded} sessions, ${result.incidentsAdded} incidents, ${result.tasksAdded} tasks." +
                if (result.settingsPending) " Settings restore is pending; retry to finish." else " Preferences restored; watch sync queued.")
        if (!result.settingsPending) refreshRestoredSettings()
    }

    fun retryBackupSettings() = backupOperation {
        runtime.withSettingsRestore { backupRepository.finishPendingSettings() }
        _backupState.value = _backupState.value.copy(settingsPending = false, message = "Preferences restored; watch sync queued.")
    }

    private suspend fun refreshRestoredSettings() {
        runtime.reloadSettings()
    }

    private fun backupOperation(block: suspend () -> Unit) = viewModelScope.launch {
        if (_backupState.value.busy) return@launch
        _backupState.value = _backupState.value.copy(busy = true, message = null)
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            _backupState.value = _backupState.value.copy(message = error.message ?: "The operation could not be completed")
            Log.e("PomodoroViewModel", "Local backup operation failed", error)
        } finally { _backupState.value = _backupState.value.copy(busy = false, settingsPending = runtime.settingsPending.value) }
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
                runtime.withSettingsRestore {
                    timerSettingsStore.write(TimerSettings())
                    focusGoalStore.save(DailyFocusGoalSettings())
                }
            }
            "watch face preferences" -> deleteFacePreferences()
            "custom logo media" -> { wearableSyncManager.resetWatchFaceLogoAndAwait(); return@withContext "Logo reset queued for the watch" }
            "watch artwork and face settings" -> {
                deleteFacePreferences()
                wearableSyncManager.resetWatchFaceLogoAndAwait()
                return@withContext "Face preferences deleted; logo reset queued for the watch"
            }
            "protection profiles" -> domainProfiles.clearAll()
            else -> error("Unknown data category")
        }
        "Deleted $target"
    }

    private suspend fun deleteFacePreferences() {
        wearableSyncManager.clearWatchFacePreferences().join()
        val saved = wearableSyncManager.watchFaceConfig.value
        check(saved.copy(localRevision = null, appliedRevision = null, updatedAtEpochMs = 0L) == com.example.core.sync.WatchFaceConfig() &&
            wearableSyncManager.watchFacePresets.value.isEmpty() && wearableSyncManager.watchFaceRecentColors.value.isEmpty()) {
            "Could not confirm deletion of watch-face preferences"
        }
    }

    fun selectWatchFaceLogo(uri: Uri) = wearableSyncManager.selectWatchFaceLogo(uri)
    fun editWatchFaceLogo(update: (LogoTransform) -> LogoTransform) = wearableSyncManager.editWatchFaceLogo(update)
    fun applyWatchFaceLogo() = wearableSyncManager.applyWatchFaceLogo()
    fun retryWatchFaceLogo() = wearableSyncManager.retryWatchFaceLogo()
    fun resetWatchFaceLogo() = wearableSyncManager.resetWatchFaceLogo()

    // Support step simulation for manual trigger and easy testing
    fun incrementStepsSimulated() {
        if (currentState.value == PomodoroState.PANIC_MODE) {
            runtime.setSteps(stepsTakenInPanic.value + 5)
        }
    }

    fun setSteps(steps: Int) {
        runtime.setSteps(steps)
    }

    // Adaptive Timers based on Biometrics
    fun receiveBiometricStressSignal() {
        if (currentState.value == PomodoroState.FOCUS) {
            // High HR detected, proactively reduce remaining focus time by 5 minutes
            runtime.reduceTime(5 * 60)
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        stepDetectorSensor?.let {
            runCatching { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                .onFailure { error -> Log.e("PomodoroViewModel", "Step sensor unavailable", error) }
        }
    }

    private fun unregisterStepSensor() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (currentState.value != PomodoroState.PANIC_MODE) return
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                runtime.setSteps(stepsTakenInPanic.value + 1)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        super.onCleared()
        unregisterStepSensor()
        stopPhoneHaptics(getApplication())
    }
}
