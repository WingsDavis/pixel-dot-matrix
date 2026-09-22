package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.timer.PomodoroEngine
import com.example.core.timer.PomodoroState
import com.example.data.database.AppDatabase
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.IncidentStatus
import com.example.data.entity.SessionLogEntity
import com.example.data.repository.PomodoroRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import com.example.core.protection.DomainProfileStore

class PomodoroViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val database = AppDatabase.getDatabase(application)
    private val repository = PomodoroRepository(database.sessionDao(), database.panicDao())
    private val domainProfiles = DomainProfileStore(application)

    val sessionLogs: StateFlow<List<SessionLogEntity>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val panicLogs: StateFlow<List<PanicLogEntity>> = repository.allPanicLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val engine = PomodoroEngine(viewModelScope)

    val currentState: StateFlow<PomodoroState> = engine.currentState
    val secondsRemaining: StateFlow<Int> = engine.secondsRemaining
    val isRunning: StateFlow<Boolean> = engine.isRunning

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

    // Contextual Task Tracking
    private val _currentTaskName = MutableStateFlow("")
    val currentTaskName: StateFlow<String> = _currentTaskName.asStateFlow()

    fun updateTaskName(name: String) {
        _currentTaskName.value = name
    }

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

    init {
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

        // Collect engine state to handle database logging when focus session finishes
        viewModelScope.launch {
            var lastState = PomodoroState.FOCUS
            var startTime = System.currentTimeMillis()

            currentState.collect { state ->
                val now = System.currentTimeMillis()
                if (domainProfiles.autoActivateDuringFocus()) {
                    val shouldEnable = state == PomodoroState.FOCUS
                    if (shouldEnable != _isDnsSinkholeActive.value && (!shouldEnable || VpnService.prepare(application) == null)) {
                        setDnsSinkholeEnabled(application, shouldEnable)
                    }
                }
                if (lastState == PomodoroState.FOCUS && state != PomodoroState.FOCUS) {
                    // Log the focus session
                    val duration = engine.focusDuration - secondsRemaining.value
                    if (duration > 10) { // log if it was running for some duration
                        val status = if (state == PomodoroState.PANIC_MODE) "PANIC" else "COMPLETED"
                        repository.insertSession(
                            SessionLogEntity(
                                sessionType = "FOCUS",
                                startTimeMillis = startTime,
                                durationSeconds = duration,
                                status = status,
                                taskName = if (_currentTaskName.value.isNotBlank()) _currentTaskName.value else null
                            )
                        )
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

                    // Insert unresolved Panic log
                    repository.insertPanicLog(
                        PanicLogEntity(
                            timestamp = now,
                            heartRateSpike = 138, // simulated heart rate spike triggering this
                            stepsTaken = 0,
                            resolvedAt = null,
                            taskName = if (_currentTaskName.value.isNotBlank()) _currentTaskName.value else null
                        )
                    )
                } else if (lastState == PomodoroState.PANIC_MODE) {
                    // Resolved Panic Mode! Unregister step sensor and save log
                    unregisterStepSensor()
                    stopPhoneHaptics(application)
                    viewModelScope.launch {
                        val activePanic = repository.getActivePanicLog()
                        if (activePanic != null) {
                            val updatedPanic = activePanic.copy(
                                stepsTaken = _stepsTakenInPanic.value,
                                resolvedAt = now,
                                incidentStatus = "PENDING_DETAIL"
                            )
                            repository.updatePanicLog(updatedPanic)
                            _activeIncidentAudit.value = updatedPanic
                        }
                    }
                }

                lastState = state
                startTime = now
            }
        }
    }

    fun startTimer() = engine.start()
    fun pauseTimer() = engine.pause()
    fun resetTimer() = engine.reset()
    fun skipTimer() = engine.skip()

    fun setTimerState(state: PomodoroState) {
        val duration = when (state) {
            PomodoroState.FOCUS -> engine.focusDuration
            PomodoroState.SHORT_BREAK -> engine.shortBreakDuration
            PomodoroState.LONG_BREAK -> engine.longBreakDuration
            PomodoroState.PANIC_MODE -> 60
        }
        engine.syncState(state, duration, false)
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
                taskName = _currentTaskName.value.ifBlank { null }
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
        wearableSyncManager.sendFrustrationAlertMessage()
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

    fun saveWatchFacePreset(name: String, config: com.example.core.sync.WatchFaceConfig) = wearableSyncManager.saveWatchFacePreset(name, config)
    fun duplicateWatchFacePreset(id: String, name: String) = wearableSyncManager.duplicateWatchFacePreset(id, name)
    fun renameWatchFacePreset(id: String, name: String) = wearableSyncManager.renameWatchFacePreset(id, name)
    fun deleteWatchFacePreset(id: String) = wearableSyncManager.deleteWatchFacePreset(id)
    fun rememberWatchFaceColor(hex: String) = wearableSyncManager.rememberWatchFaceColor(hex)

    fun resetWatchFace() {
        wearableSyncManager.resetWatchFaceConfig()
    }

    fun retryPendingSync() = wearableSyncManager.retryPendingSync()

    fun clearSessionHistory() = viewModelScope.launch { repository.clearAllSessions() }
    fun clearIncidentHistory() = viewModelScope.launch { repository.clearAllPanicLogs() }
    fun clearSyncQueue() = viewModelScope.launch { database.syncOutboxDao().clearAll() }
    fun clearProtectionSettings() = domainProfiles.clearAll()

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
        unregisterStepSensor()
        wearableSyncManager.unregisterListeners()
    }
}
