package com.example.core.sync

import android.content.Context
import android.util.Log
import com.example.core.timer.PomodoroEngine
import com.example.core.timer.PomodoroState
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val engine: PomodoroEngine
) : DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private val watchFaceConfigStore = WatchFaceConfigStore(context)
    private val timerSnapshotStore = TimerSnapshotStore(context)
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
    private val _watchFaceSyncStatus = MutableStateFlow(WatchFaceSyncStatus())
    val watchFaceSyncStatus: StateFlow<WatchFaceSyncStatus> = _watchFaceSyncStatus.asStateFlow()
    private val _connectedNodeCount = MutableStateFlow(0)
    val connectedNodeCount: StateFlow<Int> = _connectedNodeCount.asStateFlow()

    @Volatile
    private var pendingWatchFaceRevision: String? = null
    private val restoredTimerSnapshot = timerSnapshotStore.load()
    private val timerRevision = AtomicLong(restoredTimerSnapshot?.revision ?: System.currentTimeMillis())
    private var timerSessionId = restoredTimerSnapshot?.sessionId ?: UUID.randomUUID().toString()
    @Volatile
    private var localNodeId = SOURCE_PHONE
    private var lastPublishedState = engine.currentState.value
    private var currentSnapshot = restoredTimerSnapshot ?: createTimerSnapshot(timerRevision.get())
    private val handledCommandIds = LinkedHashSet<String>()
    @Volatile
    private var hasPublishedInProcess = false

    init {
        // Register listeners to handle incoming sync updates bidirectionally
        dataClient.addListener(this)
        messageClient.addListener(this)
        scope.launch(Dispatchers.IO) {
            localNodeId = runCatching { nodeClient.localNode.await().id }.getOrDefault(SOURCE_PHONE)
        }
        scope.launch(Dispatchers.IO) { syncOutbox.flush() }
        scope.launch(Dispatchers.IO) { replayStoredWatchFaceConfig() }
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
    fun pushStateToWearable() {
        val now = System.currentTimeMillis()
        val expectedSeconds = TimerSnapshotResolver.remainingAt(currentSnapshot, now)
        val hasMeaningfulChange = currentSnapshot.authority != TimerAuthority.PHONE ||
            currentSnapshot.state != engine.currentState.value ||
            currentSnapshot.isRunning != engine.isRunning.value ||
            kotlin.math.abs(expectedSeconds - engine.secondsRemaining.value) > 1
        if (hasPublishedInProcess && !hasMeaningfulChange) return
        if (lastPublishedState != engine.currentState.value) {
            timerSessionId = UUID.randomUUID().toString()
            lastPublishedState = engine.currentState.value
        }
        val snapshot = createTimerSnapshot(timerRevision.incrementAndGet())
        currentSnapshot = snapshot
        timerSnapshotStore.save(snapshot)
        val isPanic = snapshot.state == PomodoroState.PANIC_MODE
        scope.launch(Dispatchers.IO) {
            try {
                val goalPrefs = context.getSharedPreferences("focus_goal_prefs", Context.MODE_PRIVATE)
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
                    dataMap.putInt("daily_focus_minutes", goalPrefs.getInt("daily_focus_minutes", 0))
                    dataMap.putInt("daily_target_minutes", goalPrefs.getInt("daily_target_minutes", 120))
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
    fun pushCustomText(text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                syncOutbox.enqueue(
                    PATH_CUSTOM_TEXT,
                    text.toByteArray(),
                    coalesceKey = "watchface-custom-text"
                )
                Log.d(TAG, "Queued custom text for Wear delivery: $text")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push custom text", e)
            }
        }
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
    ) {
        val revision = UUID.randomUUID().toString()
        watchFaceConfigStore.save(
            WatchFaceConfig(
                hourColor = timerColor,
                minuteColor = idleTimeColor,
                secondColor = secondsColor,
                customText = customText,
                showLogo = showPanicLogo,
                preset = themePreset,
                localRevision = revision,
                appliedRevision = watchFaceConfigStore.config.value.appliedRevision
            )
        )
        pendingWatchFaceRevision = revision
        _watchFaceSyncStatus.value = WatchFaceSyncStatus(
            phase = WatchFaceSyncPhase.SYNCING,
            revision = revision,
            message = "Waiting for watch acknowledgement"
        )

        scope.launch(Dispatchers.IO) {
            try {
                val request = PutDataMapRequest.create(PATH_WATCHFACE_CONFIG_V2).apply {
                    dataMap.putInt(KEY_SCHEMA_VERSION, WATCHFACE_CONFIG_SCHEMA_VERSION)
                    dataMap.putString(KEY_REVISION, revision)
                    dataMap.putString(KEY_SOURCE_DEVICE, "phone")
                    dataMap.putString(KEY_TIMER_COLOR, timerColor)
                    dataMap.putString(KEY_SECONDS_COLOR, secondsColor)
                    dataMap.putString(KEY_IDLE_TIME_COLOR, idleTimeColor)
                    dataMap.putString(KEY_CUSTOM_TEXT, customText)
                    dataMap.putString(KEY_LEFT_SLOT_MODE, leftSlotMode)
                    dataMap.putString(KEY_BOTTOM_RIGHT_SLOT_MODE, bottomRightSlotMode)
                    dataMap.putBoolean(KEY_SHOW_PANIC_LOGO, showPanicLogo)
                    dataMap.putString(KEY_AMBIENT_STYLE, ambientStyle)
                    dataMap.putString(KEY_THEME_PRESET, themePreset)
                    dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest()

                request.setUrgent()
                dataClient.putDataItem(request).await()
                Log.d(TAG, "Pushed watch face config revision=$revision; awaiting Wear acknowledgement.")
            } catch (e: Exception) {
                _watchFaceSyncStatus.value = WatchFaceSyncStatus(
                    phase = WatchFaceSyncPhase.FAILED,
                    revision = revision,
                    message = e.message ?: "Unable to send configuration"
                )
                Log.e(TAG, "Failed to push watch face config", e)
            }
        }
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
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val item = event.dataItem
                if (item.uri.path == PATH_POMODORO_STATE) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    if (dataMap.getString(KEY_SOURCE_DEVICE) == SOURCE_PHONE) continue
                    val stateStr = dataMap.getString(KEY_STATE) ?: continue
                    val seconds = dataMap.getInt(KEY_SECONDS_REMAINING)
                    val isRunning = dataMap.getBoolean(KEY_IS_RUNNING)

                    try {
                        val state = PomodoroState.valueOf(stateStr)
                        val incoming = TimerSnapshot(
                            revision = dataMap.getLong(KEY_TIMER_REVISION, -1L),
                            sessionId = dataMap.getString(KEY_SESSION_ID) ?: "legacy",
                            sourceDevice = dataMap.getString(KEY_SOURCE_NODE)
                                ?: dataMap.getString(KEY_SOURCE_DEVICE)
                                ?: SOURCE_WEAR,
                            authority = runCatching {
                                TimerAuthority.valueOf(dataMap.getString(KEY_AUTHORITY) ?: TimerAuthority.WEAR.name)
                            }.getOrDefault(TimerAuthority.WEAR),
                            state = state,
                            secondsRemaining = seconds,
                            isRunning = isRunning,
                            updatedAtEpochMs = dataMap.getLong(KEY_TIMESTAMP, 0L),
                            anchorElapsedRealtimeMs = dataMap.getLong(KEY_ANCHOR_ELAPSED_REALTIME, 0L),
                            leaseExpiresAtEpochMs = dataMap.getLong(KEY_LEASE_EXPIRES_AT, 0L)
                        )
                        val resolution = TimerSnapshotResolver.resolve(
                            local = currentSnapshot,
                            incoming = incoming,
                            wearLeaseExpiresAtEpochMs = incoming.leaseExpiresAtEpochMs,
                            nowEpochMs = System.currentTimeMillis()
                        )
                        if (resolution is SnapshotResolution.Accept) {
                            currentSnapshot = resolution.snapshot
                            timerSnapshotStore.save(resolution.snapshot)
                            timerRevision.set(maxOf(timerRevision.get(), incoming.revision))
                            scope.launch(Dispatchers.Main) {
                                val reconciledSeconds = TimerSnapshotResolver.remainingAt(
                                    incoming,
                                    System.currentTimeMillis()
                                )
                                engine.syncState(state, reconciledSeconds, isRunning)
                            }
                        } else if (resolution is SnapshotResolution.Reject) {
                            Log.d(TAG, "Rejected Wear snapshot revision=${incoming.revision}: ${resolution.reason}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error matching synchronized state name: $stateStr", e)
                    }
                } else if (item.uri.path == PATH_WATCHFACE_STATUS_V2) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val revision = dataMap.getString(KEY_REVISION) ?: continue
                    if (pendingWatchFaceRevision != null && pendingWatchFaceRevision != revision) {
                        continue
                    }

                    val success = dataMap.getBoolean(KEY_SUCCESS, false)
                    val appliedAt = dataMap.getLong(KEY_APPLIED_AT, 0L).takeIf { it > 0L }
                    _watchFaceSyncStatus.value = WatchFaceSyncStatus(
                        phase = if (success) WatchFaceSyncPhase.APPLIED else WatchFaceSyncPhase.FAILED,
                        revision = revision,
                        message = dataMap.getString(KEY_STATUS_MESSAGE)
                            ?: if (success) "Complications synced" else "Watch rejected configuration",
                        appliedAt = appliedAt
                    )
                    if (success) pendingWatchFaceRevision = null
                    if (success) watchFaceConfigStore.markApplied(revision)
                }
            }
        }
    }

    /**
     * Responds to immediate messages (e.g., watch user tapped shortcut button to trigger Panic Mode)
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PATH_SYNC_ACK -> {
                val id = String(messageEvent.data, StandardCharsets.UTF_8)
                scope.launch(Dispatchers.IO) { syncOutbox.markApplied(id) }
            }
            PATH_PANIC_TRIGGER -> {
                scope.launch(Dispatchers.Main) {
                    engine.triggerPanic()
                    pushStateToWearable()
                }
            }
            PATH_PANIC_RESOLVE -> {
                scope.launch(Dispatchers.Main) {
                    // Resolve panic if we are in panic mode and remote confirmed it
                    if (engine.currentState.value == PomodoroState.PANIC_MODE) {
                        engine.resolvePanic()
                    }
                }
            }
            PATH_POMODORO_CONTROL -> {
                val command = TimerCommand.decode(messageEvent.data) ?: return
                scope.launch(Dispatchers.Main) {
                    if (!TimerSnapshotResolver.commandIsAcceptable(command, timerRevision.get())) {
                        Log.d(TAG, "Rejected stale timer command id=${command.id} base=${command.baseRevision} current=${timerRevision.get()}")
                        return@launch
                    }
                    if (!markCommandHandled(command.id)) return@launch
                    when (command.action) {
                        "START" -> engine.start()
                        "PAUSE" -> engine.pause()
                        "SKIP" -> engine.skip()
                        "RESET" -> engine.reset()
                        "START_FOCUS" -> {
                            engine.pause()
                            engine.syncState(PomodoroState.FOCUS, engine.focusDuration, true)
                        }
                        "START_SHORT_BREAK" -> {
                            engine.pause()
                            engine.syncState(PomodoroState.SHORT_BREAK, engine.shortBreakDuration, true)
                        }
                        "START_LONG_BREAK" -> {
                            engine.pause()
                            engine.syncState(PomodoroState.LONG_BREAK, engine.longBreakDuration, true)
                        }
                    }
                    pushStateToWearable()
                }
            }
        }
    }

    fun unregisterListeners() {
        dataClient.removeListener(this)
        messageClient.removeListener(this)
    }

    fun retryPendingSync() {
        scope.launch(Dispatchers.IO) { syncOutbox.flush() }
    }

    fun resetWatchFaceLogo() {
        scope.launch(Dispatchers.IO) {
            val request = PutDataMapRequest.create(PATH_WATCHFACE_LOGO_V1).apply {
                dataMap.putBoolean(KEY_RESET_LOGO, true)
                dataMap.putString(KEY_REVISION, UUID.randomUUID().toString())
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request).await()
        }
    }

    fun applyWatchFacePreset(id: String) {
        watchFaceConfigStore.preset(id)?.config?.let(watchFaceConfigStore::save)
    }

    fun saveWatchFacePreset(name: String, config: WatchFaceConfig) = watchFaceConfigStore.savePreset(name, config)
    fun duplicateWatchFacePreset(id: String, name: String) = watchFaceConfigStore.duplicatePreset(id, name)
    fun renameWatchFacePreset(id: String, name: String) = watchFaceConfigStore.renamePreset(id, name)
    fun deleteWatchFacePreset(id: String) = watchFaceConfigStore.deletePreset(id)
    fun rememberWatchFaceColor(hex: String) = watchFaceConfigStore.rememberColor(hex)

    fun resetWatchFaceConfig() {
        val defaults = watchFaceConfigStore.reset()
        resetWatchFaceLogo()
        pushWatchFaceConfig(
            timerColor = defaults.hourColor,
            secondsColor = defaults.secondColor,
            idleTimeColor = defaults.minuteColor,
            customText = defaults.customText,
            leftSlotMode = "custom_text",
            bottomRightSlotMode = "date",
            showPanicLogo = defaults.showLogo,
            ambientStyle = "dim",
            themePreset = defaults.preset
        )
    }

    private fun replayStoredWatchFaceConfig() {
        val config = watchFaceConfigStore.config.value
        if (config.localRevision == null) return
        pushWatchFaceConfig(
            timerColor = config.hourColor,
            secondsColor = config.secondColor,
            idleTimeColor = config.minuteColor,
            customText = config.customText,
            leftSlotMode = "custom_text",
            bottomRightSlotMode = "date",
            showPanicLogo = config.showLogo,
            ambientStyle = "dim",
            themePreset = config.preset
        )
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
        anchorElapsedRealtimeMs = SystemClock.elapsedRealtime()
    )

    @Synchronized
    private fun markCommandHandled(commandId: String): Boolean {
        if (!handledCommandIds.add(commandId)) return false
        while (handledCommandIds.size > MAX_HANDLED_COMMANDS) {
            handledCommandIds.remove(handledCommandIds.first())
        }
        return true
    }

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
        const val PATH_INCIDENT_STATUS = "/incident/status"

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
        const val SOURCE_PHONE = "phone"
        const val SOURCE_WEAR = "wear"
        private const val MAX_HANDLED_COMMANDS = 256
        private const val OUTBOX_RETRY_INTERVAL_MS = 15_000L
    }
}
