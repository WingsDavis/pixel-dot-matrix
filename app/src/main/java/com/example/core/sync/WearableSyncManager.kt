package com.example.core.sync

import android.content.Context
import android.util.Log
import com.example.core.timer.PomodoroEngine
import com.example.core.timer.PomodoroState
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock
import com.example.data.database.AppDatabase
import com.example.core.goal.DailyFocusProgress

enum class WatchFaceSyncPhase {
    IDLE,
    SYNCING,
    APPLIED,
    FAILED
}

data class WatchFaceSyncStatus(
    val phase: WatchFaceSyncPhase = WatchFaceSyncPhase.IDLE,
    val revision: String? = null,
    val message: String = "Not synced yet",
    val appliedAt: Long? = null
)

class WearableSyncManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: PomodoroEngine,
    private val applyTimerCommand: suspend (TimerCommand) -> SyncAppliedAck,
    private val timerSnapshotStore: TimerSnapshotStore = TimerSnapshotStore(context)
) {
    private val watchFaceConfigStore = WatchFaceConfigStore(context)
    val watchFaceConfig = watchFaceConfigStore.config
    val watchFacePresets = watchFaceConfigStore.presets
    val watchFaceRecentColors = watchFaceConfigStore.recentColors
    val syncOutboxItems by lazy { AppDatabase.getDatabase(context).syncOutboxDao().observeAll() }

    private val dataClient: DataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient: NodeClient by lazy { Wearable.getNodeClient(context) }
    private val syncOutbox by lazy {
        SyncOutbox(
            AppDatabase.getDatabase(context).syncOutboxDao(),
            nodeClient,
            messageClient
        )
    }
    private val watchFaceTransactions = WatchFaceConfigTransactions(
        scope = scope,
        currentConfig = { watchFaceConfigStore.config.value },
        saveConfig = watchFaceConfigStore::save,
        markApplied = watchFaceConfigStore::markApplied,
        publish = ::publishWatchFaceConfig,
        onFailure = { Log.e(TAG, "Failed to save or sync watch face config", it) }
    )
    val watchFaceEditorConfig: StateFlow<WatchFaceConfig> = watchFaceTransactions.editorConfig
    val watchFaceSyncStatus: StateFlow<WatchFaceSyncStatus> = watchFaceTransactions.status
    private val _connectedNodeCount = MutableStateFlow(0)
    val connectedNodeCount: StateFlow<Int> = _connectedNodeCount.asStateFlow()

    private val restoredTimerSnapshot = timerSnapshotStore.load()
    private val timerRevision = AtomicLong(restoredTimerSnapshot?.revision ?: System.currentTimeMillis())
    private var timerSessionId = restoredTimerSnapshot?.sessionId ?: UUID.randomUUID().toString()
    @Volatile
    private var localNodeId = SOURCE_PHONE
    private var lastPublishedState = engine.currentState.value
    private var currentSnapshot = restoredTimerSnapshot ?: createTimerSnapshot(timerRevision.get())
    private val dailyFocusGoals = Channel<DailyFocusProgress>(Channel.CONFLATED)
    @Volatile
    private var hasPublishedInProcess = false

    init {
        scope.launch(Dispatchers.IO) {
            localNodeId = runCatching { nodeClient.localNode.await().id }.getOrDefault(SOURCE_PHONE)
        }
        scope.launch(Dispatchers.IO) { syncOutbox.flush() }
        scope.launch(Dispatchers.IO) {
            for (progress in dailyFocusGoals) {
                val request = PutDataMapRequest.create(PATH_DAILY_FOCUS_GOAL).apply {
                    dataMap.putInt("schema_version", 1)
                    dataMap.putBoolean("enabled", progress.settings.enabled)
                    dataMap.putString("type", progress.settings.type.name)
                    dataMap.putInt("target_minutes", progress.settings.targetMinutes)
                    dataMap.putInt("target_sessions", progress.settings.targetSessions)
                    dataMap.putString("time_zone", progress.settings.timeZoneId)
                    dataMap.putInt("reset_hour", progress.settings.resetHour)
                    dataMap.putLong("day_start", progress.day.startEpochMs)
                    dataMap.putLong("day_end", progress.day.endEpochMs)
                    dataMap.putLong("focused_seconds", progress.focusedSeconds)
                    dataMap.putInt("completed_sessions", progress.completedSessions)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                runCatching { dataClient.putDataItem(request).await() }
                    .onFailure { Log.e(TAG, "Failed to publish daily focus target", it) }
            }
        }
        replayStoredWatchFaceConfig()
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                _connectedNodeCount.value = runCatching { nodeClient.connectedNodes.await().size }.getOrDefault(0)
                delay(OUTBOX_RETRY_INTERVAL_MS)
                syncOutbox.flush()
            }
        }
    }

    /**
     * Publishes current state to the Wearable Data Layer.
     * Standard path used for sync: /pomodoro/state
     */
    @Synchronized
    fun pushStateToWearable(force: Boolean = false, commandId: String? = null, newSession: Boolean = false) {
        val now = System.currentTimeMillis()
        val expectedSeconds = TimerSnapshotResolver.remainingAt(currentSnapshot, now)
        val hasMeaningfulChange = currentSnapshot.authority != TimerAuthority.PHONE ||
            currentSnapshot.state != engine.currentState.value ||
            currentSnapshot.isRunning != engine.isRunning.value ||
            kotlin.math.abs(expectedSeconds - engine.secondsRemaining.value) > 1
        val advancesRevision = hasMeaningfulChange || force || commandId != null || newSession
        if (hasPublishedInProcess && !advancesRevision) return
        if (newSession || advancesRevision && lastPublishedState != engine.currentState.value) {
            timerSessionId = UUID.randomUUID().toString()
        }
        val snapshot = if (advancesRevision) createTimerSnapshot(timerRevision.get() + 1) else currentSnapshot
        if (advancesRevision || timerSnapshotStore.load() == null) timerSnapshotStore.save(snapshot, commandId)
        lastPublishedState = engine.currentState.value
        currentSnapshot = snapshot
        timerRevision.set(snapshot.revision)
        val isPanic = snapshot.state == PomodoroState.PANIC_MODE
        scope.launch(Dispatchers.IO) {
            try {
                val request = PutDataMapRequest.create(PATH_POMODORO_STATE).apply {
                    dataMap.putString(KEY_STATE, snapshot.state.name)
                    dataMap.putString(KEY_CURRENT_PHASE, snapshot.state.name)
                    dataMap.putInt(KEY_SECONDS_REMAINING, snapshot.secondsRemaining)
                    dataMap.putBoolean(KEY_IS_RUNNING, snapshot.isRunning)
                    dataMap.putBoolean(KEY_IS_PANIC_ACTIVE, isPanic)
                    dataMap.putLong(KEY_TIMER_REVISION, snapshot.revision)
                    dataMap.putString(KEY_SESSION_ID, snapshot.sessionId)
                    dataMap.putString(KEY_SOURCE_DEVICE, SOURCE_PHONE)
                    dataMap.putString(KEY_SOURCE_NODE, snapshot.sourceDevice)
                    dataMap.putString(KEY_AUTHORITY, snapshot.authority.name)
                    dataMap.putLong(KEY_ANCHOR_ELAPSED_REALTIME, snapshot.anchorElapsedRealtimeMs)
                    dataMap.putLong(KEY_LEASE_EXPIRES_AT, snapshot.leaseExpiresAtEpochMs)
                    dataMap.putInt(KEY_FOCUS_DURATION, engine.focusDuration)
                    dataMap.putInt(KEY_SHORT_BREAK_DURATION, engine.shortBreakDuration)
                    dataMap.putInt(KEY_LONG_BREAK_DURATION, engine.longBreakDuration)
                    dataMap.putInt(KEY_LONG_BREAK_CADENCE, engine.longBreakCadence)
                    dataMap.putInt(KEY_COMPLETED_FOCUS_SESSIONS, engine.completedFocusSessions)
                    dataMap.putBoolean(KEY_AUTO_START_BREAKS, engine.autoStartBreaksEnabled)
                    dataMap.putBoolean(KEY_AUTO_START_FOCUS, engine.autoStartFocusEnabled)
                    // Ensure the update is always detected even if primitive values are same
                    dataMap.putLong(KEY_TIMESTAMP, snapshot.updatedAtEpochMs)
                }.asPutDataRequest()

                request.setUrgent()
                dataClient.putDataItem(request).await()
                hasPublishedInProcess = true
                syncOutbox.flush()
                Log.d(TAG, "Successfully pushed Pomodoro snapshot revision=${snapshot.revision} state=${snapshot.state} remaining=${snapshot.secondsRemaining}.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push state to Wearable Data Layer", e)
            }
        }
    }

    /**
     * Sends a direct message payload. Useful for immediate, non-sticky triggers (like Panic Activation)
     */
    fun sendPanicTriggerMessage() {
        scope.launch(Dispatchers.IO) {
            try {
                syncOutbox.enqueue(
                    PATH_PANIC_TRIGGER,
                    "TRIGGER_PANIC".toByteArray(StandardCharsets.UTF_8)
                )
                Log.d(TAG, "Panic trigger queued for Wear delivery.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send panic trigger message", e)
            }
        }
    }

    /**
     * Sends a direct haptic vibration trigger for Biometric Frustration Interception to the connected Watch.
     */
    fun sendFrustrationAlertMessage() {
        scope.launch(Dispatchers.IO) {
            try {
                syncOutbox.enqueue(
                    PATH_FRUSTRATION_ALERT,
                    "TRIGGER_FRUSTRATION".toByteArray(StandardCharsets.UTF_8),
                    coalesceKey = "frustration-alert"
                )
                Log.d(TAG, "Frustration alert queued for Wear delivery.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send frustration alert message", e)
            }
        }
    }

    fun sendIncidentStatus(incidentId: String, status: String) {
        scope.launch(Dispatchers.IO) {
            syncOutbox.enqueue(
                PATH_INCIDENT_STATUS,
                "$incidentId|$status".toByteArray(StandardCharsets.UTF_8),
                coalesceKey = "incident-status-$incidentId"
            )
        }
    }

    fun pushTaskSummaries(current: String?, next: String?) {
        scope.launch(Dispatchers.IO) {
            val request = PutDataMapRequest.create(PATH_TASK_SUMMARIES).apply {
                dataMap.putString(KEY_CURRENT_TASK, current.orEmpty().take(MAX_WEAR_TASK_LENGTH))
                dataMap.putString(KEY_NEXT_TASK, next.orEmpty().take(MAX_WEAR_TASK_LENGTH))
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            runCatching { dataClient.putDataItem(request).await() }
                .onFailure { error -> Log.e(TAG, "Failed to sync task summaries", error) }
        }
    }

    fun pushDailyFocusGoal(progress: DailyFocusProgress) {
        dailyFocusGoals.trySend(progress)
    }

    /**
     * Pushes the selected watch face style preference to the watch.
     */
    fun pushWatchFaceStyle(styleId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = PutDataMapRequest.create(PATH_WATCHFACE_STYLE).apply {
                    dataMap.putString(KEY_STYLE_ID, styleId)
                    dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest()

                request.setUrgent()
                dataClient.putDataItem(request).await()
                Log.d(TAG, "Successfully pushed watch face style: $styleId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push watch face style", e)
            }
        }
    }

    /**
     * Pushes custom text to be displayed on the watch face complication.
     */
    fun pushCustomText(text: String): Job {
        val config = watchFaceEditorConfig.value.copy(customText = text)
        return watchFaceTransactions.apply(previewConfig = config) { config }
    }

    /**
     * Pushes the complete watch face customization payload to the watch.
     */
    fun pushWatchFaceConfig(
        timerColor: String,
        secondsColor: String,
        idleTimeColor: String,
        customText: String,
        leftSlotMode: String,
        bottomRightSlotMode: String,
        showPanicLogo: Boolean,
        ambientStyle: String,
        themePreset: String
    ): Job {
        val config = WatchFaceConfig(
            hourColor = timerColor,
            minuteColor = idleTimeColor,
            secondColor = secondsColor,
            customText = customText,
            showLogo = showPanicLogo,
            preset = themePreset,
            leftSlotMode = leftSlotMode,
            bottomRightSlotMode = bottomRightSlotMode,
            ambientStyle = ambientStyle
        )
        return watchFaceTransactions.apply(previewConfig = config) { config }
    }

    private suspend fun publishWatchFaceConfig(config: WatchFaceConfig) {
        val request = PutDataMapRequest.create(PATH_WATCHFACE_CONFIG_V2).apply {
            dataMap.putInt(KEY_SCHEMA_VERSION, WATCHFACE_CONFIG_SCHEMA_VERSION)
            dataMap.putString(KEY_REVISION, requireNotNull(config.localRevision))
            dataMap.putString(KEY_SOURCE_DEVICE, SOURCE_PHONE)
            dataMap.putString(KEY_TIMER_COLOR, config.hourColor)
            dataMap.putString(KEY_SECONDS_COLOR, config.secondColor)
            dataMap.putString(KEY_IDLE_TIME_COLOR, config.minuteColor)
            dataMap.putString(KEY_CUSTOM_TEXT, config.customText)
            dataMap.putString(KEY_LEFT_SLOT_MODE, config.leftSlotMode)
            dataMap.putString(KEY_BOTTOM_RIGHT_SLOT_MODE, config.bottomRightSlotMode)
            dataMap.putBoolean(KEY_SHOW_PANIC_LOGO, config.showLogo)
            dataMap.putString(KEY_AMBIENT_STYLE, config.ambientStyle)
            dataMap.putString(KEY_THEME_PRESET, config.preset)
            dataMap.putLong(KEY_TIMESTAMP, config.updatedAtEpochMs)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
        Log.d(TAG, "Pushed watch face config revision=${config.localRevision}; awaiting Wear acknowledgement.")
    }

    fun pushWatchFaceLogo(pngBytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = PutDataMapRequest.create(PATH_WATCHFACE_LOGO_V1).apply {
                    dataMap.putAsset(KEY_LOGO_ASSET, Asset.createFromBytes(pngBytes))
                    dataMap.putString(KEY_REVISION, UUID.randomUUID().toString())
                    dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()

                dataClient.putDataItem(request).await()
                Log.d(TAG, "Pushed custom watch face logo asset (${pngBytes.size} bytes).")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push custom watch face logo", e)
            }
        }
    }

    /**
     * Responds to changes in data items on the shared channel.
     * This handles bidirectional synchronization of state.
     */
    fun handleData(path: String, dataMap: DataMap): Boolean {
        if (path == PATH_POMODORO_STATE) {
            if (dataMap.getString(KEY_SOURCE_DEVICE) == SOURCE_PHONE) return false
            val state = PomodoroState.valueOf(dataMap.getString(KEY_STATE) ?: return false)
            val incoming = TimerSnapshot(
                revision = dataMap.getLong(KEY_TIMER_REVISION, -1L),
                sessionId = dataMap.getString(KEY_SESSION_ID) ?: "legacy",
                sourceDevice = dataMap.getString(KEY_SOURCE_NODE) ?: dataMap.getString(KEY_SOURCE_DEVICE) ?: SOURCE_WEAR,
                authority = runCatching {
                    TimerAuthority.valueOf(dataMap.getString(KEY_AUTHORITY) ?: TimerAuthority.WEAR.name)
                }.getOrDefault(TimerAuthority.WEAR),
                state = state,
                secondsRemaining = dataMap.getInt(KEY_SECONDS_REMAINING),
                isRunning = dataMap.getBoolean(KEY_IS_RUNNING),
                updatedAtEpochMs = dataMap.getLong(KEY_TIMESTAMP, 0L),
                anchorElapsedRealtimeMs = dataMap.getLong(KEY_ANCHOR_ELAPSED_REALTIME, 0L),
                leaseExpiresAtEpochMs = dataMap.getLong(KEY_LEASE_EXPIRES_AT, 0L),
                completedFocusSessions = dataMap.getInt(KEY_COMPLETED_FOCUS_SESSIONS, 0)
            )
            val resolution = TimerSnapshotResolver.resolve(currentSnapshot, incoming,
                incoming.leaseExpiresAtEpochMs, System.currentTimeMillis())
            if (resolution is SnapshotResolution.Accept) {
                timerSnapshotStore.save(resolution.snapshot)
                currentSnapshot = resolution.snapshot
                timerRevision.set(maxOf(timerRevision.get(), incoming.revision))
                engine.syncState(state, TimerSnapshotResolver.remainingAt(incoming, System.currentTimeMillis()), incoming.isRunning)
                engine.restoreCompletedFocusSessions(incoming.completedFocusSessions)
                return true
            }
            Log.d(TAG, "Rejected Wear snapshot revision=${incoming.revision}: ${(resolution as SnapshotResolution.Reject).reason}")
        } else if (path == PATH_WATCHFACE_STATUS_V2) {
            val revision = dataMap.getString(KEY_REVISION) ?: return false
            watchFaceTransactions.acknowledge(
                revision = revision,
                success = dataMap.getBoolean(KEY_SUCCESS, false),
                message = dataMap.getString(KEY_STATUS_MESSAGE),
                appliedAt = dataMap.getLong(KEY_APPLIED_AT, 0L).takeIf { it > 0L }
            )
        }
        return false
    }

    /**
     * Responds to immediate messages (e.g., watch user tapped shortcut button to trigger Panic Mode)
     */
    suspend fun handleMessage(path: String, sourceNodeId: String, payload: ByteArray) {
        when (path) {
            PATH_SYNC_ACK -> {
                val acknowledgement = SyncAppliedAck.decode(payload) ?: return
                scope.launch(Dispatchers.IO) {
                    if (acknowledgement.applied) {
                        syncOutbox.markApplied(acknowledgement.id)
                    } else {
                        syncOutbox.markFailed(acknowledgement.id, acknowledgement.message)
                    }
                }
            }
            PATH_PANIC_TRIGGER, PATH_PANIC_RESOLVE, PATH_POMODORO_CONTROL -> {
                val decoded = TimerCommand.decode(payload) ?: return
                val command = when (path) {
                    PATH_PANIC_TRIGGER -> decoded.copy(action = "PANIC", baseRevision = -1)
                    PATH_PANIC_RESOLVE -> decoded.copy(action = "RESOLVE_PANIC", baseRevision = -1)
                    else -> decoded
                }
                val acknowledgement = applyTimerCommand(command)
                sendTimerCommandAcknowledgement(sourceNodeId, command.id, acknowledgement.applied, acknowledgement.message)
            }
        }
    }

    fun currentTimerSnapshot(): TimerSnapshot = currentSnapshot

    fun wasCommandApplied(id: String): Boolean = timerSnapshotStore.wasCommandApplied(id)

    fun reloadTimerSnapshot(): TimerSnapshot? = timerSnapshotStore.load()?.also {
        currentSnapshot = it
        timerRevision.set(it.revision)
        timerSessionId = it.sessionId
        lastPublishedState = it.state
    }

    fun retryPendingSync() {
        scope.launch(Dispatchers.IO) { syncOutbox.retryFailed() }
    }

    fun flushPendingSync() {
        scope.launch(Dispatchers.IO) { syncOutbox.flush() }
    }

    private fun sendTimerCommandAcknowledgement(
        sourceNodeId: String,
        commandId: String,
        applied: Boolean,
        reason: String?
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                messageClient.sendMessage(
                    sourceNodeId,
                    PATH_TIMER_COMMAND_ACK,
                    SyncAppliedAck(commandId, applied, reason).encode()
                ).await()
            }.onFailure { error -> Log.e(TAG, "Failed to acknowledge timer command $commandId", error) }
        }
    }

    fun resetWatchFaceLogo() {
        scope.launch(Dispatchers.IO) {
            runCatching { resetWatchFaceLogoAndAwait() }.onFailure { Log.e(TAG, "Could not queue logo reset", it) }
        }
    }

    suspend fun resetWatchFaceLogoAndAwait() {
        val request = PutDataMapRequest.create(PATH_WATCHFACE_LOGO_V1).apply {
            dataMap.putBoolean(KEY_RESET_LOGO, true)
            dataMap.putString(KEY_REVISION, UUID.randomUUID().toString())
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    fun clearWatchFacePreferences(): Job {
        val defaults = WatchFaceConfig()
        return watchFaceTransactions.apply(previewConfig = defaults) {
            watchFaceConfigStore.restore(defaults, emptyList(), emptyList())
            watchFaceConfigStore.config.value
        }
    }

    fun applyWatchFacePreset(id: String) = watchFaceTransactions.applyPreset(id, watchFaceConfigStore::preset)

    fun saveWatchFaceDraft(config: WatchFaceConfig) = watchFaceTransactions.saveDraft(config)

    fun editWatchFaceDraft(update: (WatchFaceConfig) -> WatchFaceConfig) = watchFaceTransactions.editDraft(update)

    suspend fun restoreWatchFacePreferences(settings: WatchFacePreferences) {
        watchFaceTransactions.restoreLocal {
            watchFaceConfigStore.restore(settings.config, settings.presets, settings.recentColors)
        }.await()
    }

    fun saveWatchFacePreset(name: String, config: WatchFaceConfig) = watchFaceConfigStore.savePreset(name, config)
    fun duplicateWatchFacePreset(id: String, name: String) = watchFaceConfigStore.duplicatePreset(id, name)
    fun renameWatchFacePreset(id: String, name: String) = watchFaceConfigStore.renamePreset(id, name)
    fun deleteWatchFacePreset(id: String) = watchFaceConfigStore.deletePreset(id)
    fun rememberWatchFaceColor(hex: String) = watchFaceConfigStore.rememberColor(hex)

    fun refreshRestoredWatchFaceSettings(): Job {
        val config = watchFaceEditorConfig.value
        return watchFaceTransactions.apply(previewConfig = config) {
            watchFaceConfigStore.refresh()
            config
        }
    }

    fun resetWatchFaceConfig(): Job {
        val defaults = WatchFaceConfig()
        return watchFaceTransactions.apply(previewConfig = defaults, beforePublish = ::resetWatchFaceLogoAndAwait) { defaults }
    }

    private fun replayStoredWatchFaceConfig() {
        if (watchFaceConfigStore.config.value.localRevision == null) return
        watchFaceTransactions.apply { watchFaceConfigStore.config.value }
    }

    private fun createTimerSnapshot(revision: Long) = TimerSnapshot(
        revision = revision,
        sessionId = timerSessionId,
        sourceDevice = localNodeId,
        authority = TimerAuthority.PHONE,
        state = engine.currentState.value,
        secondsRemaining = engine.secondsRemaining.value,
        isRunning = engine.isRunning.value,
        updatedAtEpochMs = System.currentTimeMillis(),
        anchorElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        completedFocusSessions = engine.completedFocusSessions,
        activeFocusInterval = engine.activeFocusInterval.value
    )

    companion object {
        private const val TAG = "WearableSyncManager"

        const val PATH_POMODORO_STATE = "/pomodoro/state"
        const val PATH_POMODORO_CONTROL = "/pomodoro/control"
        const val PATH_PANIC_TRIGGER = "/panic/trigger"
        const val PATH_PANIC_RESOLVE = "/panic/resolve"
        const val PATH_FRUSTRATION_ALERT = "/panic/frustration"
        const val PATH_WATCHFACE_STYLE = "/pomodoro/watchface_style"
        const val PATH_WATCHFACE_CONFIG = "/pomodoro/watchface_config"
        const val PATH_WATCHFACE_CONFIG_V2 = "/pomodoro/watchface/config/v2"
        const val PATH_WATCHFACE_STATUS_V2 = "/pomodoro/watchface/status/v2"
        const val PATH_WATCHFACE_LOGO_V1 = "/pomodoro/watchface/logo/v1"
        const val PATH_CUSTOM_TEXT = "/pomodoro/custom_text"
        const val PATH_SYNC_ACK = "/pomodoro/sync_ack"
        const val PATH_TIMER_COMMAND_ACK = "/pomodoro/control_ack"
        const val PATH_INCIDENT_STATUS = "/incident/status"
        const val PATH_TASK_SUMMARIES = "/pomodoro/tasks"
        const val PATH_DAILY_FOCUS_GOAL = "/pomodoro/daily_target/v1"

        const val WATCHFACE_CONFIG_SCHEMA_VERSION = 2

        const val KEY_STATE = "state"
        const val KEY_CURRENT_PHASE = "current_phase"
        const val KEY_SECONDS_REMAINING = "seconds_remaining"
        const val KEY_IS_RUNNING = "is_running"
        const val KEY_IS_PANIC_ACTIVE = "is_panic_active"
        const val KEY_STYLE_ID = "style_id"
        const val KEY_CUSTOM_TEXT = "custom_text"
        const val KEY_TIMER_COLOR = "timer_color"
        const val KEY_SECONDS_COLOR = "seconds_color"
        const val KEY_IDLE_TIME_COLOR = "idle_time_color"
        const val KEY_LEFT_SLOT_MODE = "left_slot_mode"
        const val KEY_BOTTOM_RIGHT_SLOT_MODE = "bottom_right_slot_mode"
        const val KEY_SHOW_PANIC_LOGO = "show_panic_logo"
        const val KEY_AMBIENT_STYLE = "ambient_style"
        const val KEY_THEME_PRESET = "theme_preset"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_REVISION = "revision"
        const val KEY_SOURCE_DEVICE = "source_device"
        const val KEY_SOURCE_NODE = "source_node"
        const val KEY_SUCCESS = "success"
        const val KEY_STATUS_MESSAGE = "status_message"
        const val KEY_APPLIED_AT = "applied_at"
        const val KEY_LOGO_ASSET = "logo_asset"
        const val KEY_RESET_LOGO = "reset_logo"
        const val KEY_TIMER_REVISION = "timer_revision"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_AUTHORITY = "authority"
        const val KEY_ANCHOR_ELAPSED_REALTIME = "anchor_elapsed_realtime"
        const val KEY_LEASE_EXPIRES_AT = "lease_expires_at"
        const val KEY_FOCUS_DURATION = "focus_duration"
        const val KEY_SHORT_BREAK_DURATION = "short_break_duration"
        const val KEY_LONG_BREAK_DURATION = "long_break_duration"
        const val KEY_LONG_BREAK_CADENCE = "long_break_cadence"
        const val KEY_COMPLETED_FOCUS_SESSIONS = "completed_focus_sessions"
        const val KEY_AUTO_START_BREAKS = "auto_start_breaks"
        const val KEY_AUTO_START_FOCUS = "auto_start_focus"
        const val KEY_CURRENT_TASK = "current_task"
        const val KEY_NEXT_TASK = "next_task"
        const val SOURCE_PHONE = "phone"
        const val SOURCE_WEAR = "wear"
        private const val MAX_HANDLED_COMMANDS = 256
        private const val OUTBOX_RETRY_INTERVAL_MS = 15_000L
        private const val MAX_WEAR_TASK_LENGTH = 40
    }
}
