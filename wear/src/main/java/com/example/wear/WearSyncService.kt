package com.example.wear

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.example.core.sync.WearCommandProtocol
import com.example.core.sync.SyncAppliedAck

class WearSyncService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val item = event.dataItem
                if (item.uri.path?.startsWith(WearCommandProtocol.ACK_PREFIX) == true) {
                    val data = DataMapItem.fromDataItem(item).dataMap
                    val id = item.uri.path!!.removePrefix(WearCommandProtocol.ACK_PREFIX)
                    val payload = data.getByteArray("payload")
                    if (data.getInt("schema_version", 0) == WearCommandProtocol.SCHEMA_VERSION &&
                        data.getString("command_id") == id && payload?.let(SyncAppliedAck::decode)?.id == id
                    ) payload?.let(::acknowledgeCommand)
                } else if (item.uri.path == "/pomodoro/watchface_style") {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val styleId = dataMap.getString("style_id")
                    if (styleId != null) {
                        Log.d(TAG, "Received new watch face style: $styleId")
                        val prefs = getSharedPreferences("watchface_prefs", MODE_PRIVATE)
                        prefs.edit().putString("style_id", styleId).apply()
                    }
                } else if (item.uri.path == "/pomodoro/state") {
                    applyTimerState(DataMapItem.fromDataItem(item).dataMap)
                } else if (item.uri.path == WearDailyFocusGoal.PATH) {
                    if (WearDailyFocusGoal.save(this, DataMapItem.fromDataItem(item).dataMap)) {
                        ComplicationUpdates.goal(this)
                    }
                } else if (item.uri.path == "/pomodoro/tasks") {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    getSharedPreferences("pomodoro_sync_prefs", MODE_PRIVATE).edit()
                        .putString("current_task", dataMap.getString("current_task").orEmpty().take(40))
                        .putString("next_task", dataMap.getString("next_task").orEmpty().take(40))
                        .apply()
                } else if (
                    item.uri.path == PATH_WATCHFACE_CONFIG_LEGACY ||
                    item.uri.path == PATH_WATCHFACE_CONFIG_V2
                ) {
                    applyWatchFaceConfig(DataMapItem.fromDataItem(item).dataMap)
                } else if (item.uri.path == PATH_WATCHFACE_LOGO_V1) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    if (dataMap.getBoolean(KEY_RESET_LOGO, false)) {
                        deleteFile(LOGO_FILENAME)
                        requestLogoComplicationUpdate()
                    } else {
                        dataMap.getAsset(KEY_LOGO_ASSET)?.let(::storeLogoAsset)
                    }
                }
            }
        }
    }

    internal fun applyTimerState(data: com.google.android.gms.wearable.DataMap): Boolean {
        val state = data.getString("state") ?: "FOCUS"
        if (state !in setOf("FOCUS", "SHORT_BREAK", "LONG_BREAK", "PANIC_MODE")) return false
        val prefs = getSharedPreferences("pomodoro_sync_prefs", MODE_PRIVATE)
        synchronized(prefs) {
            val revision = data.getLong("timer_revision", -1L)
            if (revision < prefs.getLong("timer_revision", -1L)) return false
            val source = data.getString("source_device") ?: "phone"
            prefs.edit()
                .putString("state", state)
                .putInt("seconds_remaining", data.getInt("seconds_remaining").coerceIn(0, 86_400))
                .putBoolean("is_running", data.getBoolean("is_running"))
                .putLong("timer_revision", revision)
                .putString("session_id", data.getString("session_id"))
                .putLong("timer_updated_at", data.getLong("timestamp", System.currentTimeMillis()).coerceAtLeast(0L))
                .putLong("wear_lease_expires_at", data.getLong("lease_expires_at", 0L))
                .putBoolean("wear_authority", source == "wear" && data.getString("authority") == "WEAR")
                .putInt("focus_duration", data.getInt("focus_duration", prefs.getInt("focus_duration", 1500)))
                .putInt("short_break_duration", data.getInt("short_break_duration", prefs.getInt("short_break_duration", 300)))
                .putInt("long_break_duration", data.getInt("long_break_duration", prefs.getInt("long_break_duration", 900)))
                .putInt("long_break_cadence", data.getInt("long_break_cadence", prefs.getInt("long_break_cadence", 4)))
                .putInt("completed_focus_sessions", data.getInt("completed_focus_sessions", prefs.getInt("completed_focus_sessions", 0)))
                .putBoolean("auto_start_breaks", data.getBoolean("auto_start_breaks", prefs.getBoolean("auto_start_breaks", false)))
                .putBoolean("auto_start_focus", data.getBoolean("auto_start_focus", prefs.getBoolean("auto_start_focus", false)))
                .apply()
        }
        ComplicationUpdates.timer(this)
        return true
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        Log.d(TAG, "Watch background listener received message path: ${messageEvent.path}")
        if (messageEvent.path == PATH_TIMER_COMMAND_ACK) {
            acknowledgeCommand(messageEvent.data)
            return
        }
        val envelope = decodeSyncEnvelope(messageEvent.data)
        val payload = envelope?.payload ?: messageEvent.data
        if (envelope != null && wasEnvelopeApplied(envelope.id)) {
            acknowledge(envelope, messageEvent.sourceNodeId, true, null)
            return
        }
        val result = runCatching {
            when (messageEvent.path) {
                "/incident/status" -> {
                    val incidentId = String(payload).substringBefore('|')
                    WearCommandOutbox(this, Wearable.getNodeClient(this), Wearable.getMessageClient(this))
                        .acknowledge(incidentId)
                }
                "/pomodoro/custom_text" -> {
                    val customText = String(payload)
                    Log.d(TAG, "Received custom text via message: $customText")
                    getSharedPreferences("pomodoro_sync_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("custom_text", customText)
                        .commit()
                    ComplicationUpdates.text(this)
                }
                "/panic/trigger" -> dispatchAlert("panic", envelope?.id) { token ->
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("FORCE_PANIC_UI", true)
                        putExtra(WearAlertAcceptance.EXTRA_TOKEN, token)
                    }
                    startActivity(intent)
                    Log.d(TAG, "Successfully started Wear MainActivity to trigger active intervention.")
                }
                "/panic/frustration" -> dispatchAlert("frustration", envelope?.id) { token ->
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("TRIGGER_FRUSTRATION", true)
                        putExtra(WearAlertAcceptance.EXTRA_TOKEN, token)
                    }
                    startActivity(intent)
                    Log.d(TAG, "Successfully started Wear MainActivity for Frustration Interception.")
                }
                else -> if (envelope != null) error("Unsupported sync path ${messageEvent.path}")
            }
        }
        if (result.isSuccess && envelope != null) rememberAppliedEnvelope(envelope.id)
        // A blocked Activity launch is transient; no applied/terminal receipt means the phone retries.
        envelope?.takeUnless { result.isFailure && messageEvent.path in setOf("/panic/trigger", "/panic/frustration") }?.let {
            acknowledge(it, messageEvent.sourceNodeId, result.isSuccess, result.exceptionOrNull()?.message)
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "Failed to apply ${messageEvent.path}", it) }
    }

    private fun acknowledgeCommand(payload: ByteArray) {
        runCatching {
            WearCommandOutbox(this, Wearable.getNodeClient(this), Wearable.getMessageClient(this)).acknowledge(payload)
        }.onFailure { Log.e(TAG, "Could not save command acknowledgement", it) }
    }

    private fun decodeSyncEnvelope(bytes: ByteArray): SyncEnvelope? {
        val raw = bytes.toString(Charsets.UTF_8)
        if (!raw.startsWith("sync1|")) return null
        val body = raw.removePrefix("sync1|")
        val split = body.indexOf('|')
        if (split <= 0) return null
        return runCatching {
            SyncEnvelope(
                body.substring(0, split),
                android.util.Base64.decode(body.substring(split + 1), android.util.Base64.DEFAULT)
            )
        }.getOrNull()
    }

    private fun acknowledge(envelope: SyncEnvelope, sourceNodeId: String, applied: Boolean, reason: String?) {
        val encodedReason = android.util.Base64.encodeToString(reason.orEmpty().toByteArray(), android.util.Base64.NO_WRAP)
        val payload = "${envelope.id}|${if (applied) "APPLIED" else "FAILED"}|$encodedReason".toByteArray()
        Wearable.getMessageClient(this)
            .sendMessage(sourceNodeId, "/pomodoro/sync_ack", payload)
            .addOnFailureListener { error -> Log.e(TAG, "Failed to acknowledge sync item", error) }
    }

    private fun wasEnvelopeApplied(id: String): Boolean =
        getSharedPreferences(APPLIED_ENVELOPES_PREFS, MODE_PRIVATE)
            .getStringSet(KEY_APPLIED_ENVELOPES, emptySet())
            .orEmpty()
            .contains(id)

    private fun rememberAppliedEnvelope(id: String) {
        val preferences = getSharedPreferences(APPLIED_ENVELOPES_PREFS, MODE_PRIVATE)
        val ids = preferences.getStringSet(KEY_APPLIED_ENVELOPES, emptySet()).orEmpty().toMutableSet()
        if (ids.size >= MAX_APPLIED_ENVELOPES) ids.clear()
        ids += id
        preferences.edit().putStringSet(KEY_APPLIED_ENVELOPES, ids).commit()
    }

    private data class SyncEnvelope(val id: String, val payload: ByteArray)

    private fun requestOwnedComplicationUpdates() {
        ComplicationUpdates.config(this)
    }

    private fun storeLogoAsset(asset: Asset) {
        Wearable.getDataClient(this).getFdForAsset(asset)
            .addOnSuccessListener { result ->
                runCatching {
                    result.inputStream.use { input ->
                        openFileOutput(LOGO_FILENAME, MODE_PRIVATE).use { output -> input.copyTo(output) }
                    }
                }.onSuccess {
                    Log.d(TAG, "Stored custom watch face logo")
                    requestLogoComplicationUpdate()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to store custom watch face logo", error)
                }
            }
            .addOnFailureListener { error -> Log.e(TAG, "Failed to read logo asset", error) }
    }

    private fun requestLogoComplicationUpdate() {
        ComplicationUpdates.logo(this)
    }

    private fun applyWatchFaceConfig(dataMap: com.google.android.gms.wearable.DataMap) {
        val schemaVersion = dataMap.getInt(KEY_SCHEMA_VERSION, 1)
        val revision = dataMap.getString(KEY_REVISION)
            ?: "legacy-${dataMap.getLong(KEY_TIMESTAMP, System.currentTimeMillis())}"

        try {
            val configSaved = getSharedPreferences("watchface_config_prefs", MODE_PRIVATE).edit()
                .putInt(KEY_SCHEMA_VERSION, schemaVersion)
                .putString(KEY_REVISION, revision)
                .putString("timer_color", dataMap.getString("timer_color") ?: "#ff00e5ff")
                .putString("seconds_color", dataMap.getString("seconds_color") ?: "#ff00e5ff")
                .putString("idle_time_color", dataMap.getString("idle_time_color") ?: "#ffffffff")
                .putString("custom_text", dataMap.getString("custom_text") ?: "")
                .putString("left_slot_mode", dataMap.getString("left_slot_mode") ?: "custom_text")
                .putString("bottom_right_slot_mode", dataMap.getString("bottom_right_slot_mode") ?: "date")
                .putBoolean("show_panic_logo", dataMap.getBoolean("show_panic_logo", true))
                .putString("ambient_style", dataMap.getString("ambient_style") ?: "dim")
                .putString("theme_preset", dataMap.getString("theme_preset") ?: "cyan")
                .putLong(KEY_TIMESTAMP, dataMap.getLong(KEY_TIMESTAMP, System.currentTimeMillis()))
                .commit()

            val complicationTextSaved = getSharedPreferences("pomodoro_sync_prefs", MODE_PRIVATE).edit()
                .putString("custom_text", dataMap.getString("custom_text") ?: "")
                .commit()

            if (!configSaved || !complicationTextSaved) {
                throw IllegalStateException("Wear settings could not be persisted")
            }

            requestOwnedComplicationUpdates()
            publishConfigAcknowledgement(revision, schemaVersion, true, "Complications synced")
            Log.d(
                TAG,
                "Applied watch face config revision=$revision schema=$schemaVersion preset=${dataMap.getString("theme_preset")}"
            )
        } catch (e: Exception) {
            publishConfigAcknowledgement(
                revision,
                schemaVersion,
                false,
                e.message ?: "Unable to apply configuration"
            )
            Log.e(TAG, "Failed to apply watch face config revision=$revision", e)
        }
    }

    private fun publishConfigAcknowledgement(
        revision: String,
        schemaVersion: Int,
        success: Boolean,
        message: String
    ) {
        val request = PutDataMapRequest.create(PATH_WATCHFACE_STATUS_V2).apply {
            dataMap.putInt(KEY_SCHEMA_VERSION, schemaVersion)
            dataMap.putString(KEY_REVISION, revision)
            dataMap.putBoolean(KEY_SUCCESS, success)
            dataMap.putString(KEY_STATUS_MESSAGE, message)
            dataMap.putLong(KEY_APPLIED_AT, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(this).putDataItem(request)
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to publish config acknowledgement revision=$revision", error)
            }
    }

    internal fun dispatchAlert(
        kind: String,
        eventId: String?,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0),
        acknowledgementTimeoutMs: Long = 5_000L,
        launch: (String) -> Unit
    ): Boolean {
        require(kind in setOf("panic", "frustration"))
        val preferences = getSharedPreferences("wear_alert_dispatch", MODE_PRIVATE)
        synchronized(preferences) {
            val last = preferences.getLong("${kind}_elapsed", -1)
            val duplicate = if (eventId != null) preferences.getString("${kind}_id", null) == eventId else {
                preferences.getInt("${kind}_boot", -1) == bootCount && last >= 0 &&
                    nowElapsedMs >= last && nowElapsedMs - last < LAUNCH_DEBOUNCE_MS
            }
            if (duplicate) return false
            val token = WearAlertAcceptance.prepare(acknowledgementTimeoutMs)
            try {
                launch(token)
                check(WearAlertAcceptance.await(token, acknowledgementTimeoutMs)) { "Watch app did not accept the alert" }
            } finally { WearAlertAcceptance.release(token) }
            check(preferences.edit().putString("${kind}_id", eventId)
                .putLong("${kind}_elapsed", nowElapsedMs).putInt("${kind}_boot", bootCount).commit()) {
                "Could not remember alert delivery"
            }
            return true
        }
    }

    companion object {
        private const val TAG = "WearSyncService"
        private const val LAUNCH_DEBOUNCE_MS = 1_500L
        private const val PATH_WATCHFACE_CONFIG_LEGACY = "/pomodoro/watchface_config"
        private const val PATH_WATCHFACE_CONFIG_V2 = "/pomodoro/watchface/config/v2"
        private const val PATH_WATCHFACE_STATUS_V2 = "/pomodoro/watchface/status/v2"
        private const val PATH_WATCHFACE_LOGO_V1 = "/pomodoro/watchface/logo/v1"
        private const val PATH_TIMER_COMMAND_ACK = "/pomodoro/control_ack"
        private const val APPLIED_ENVELOPES_PREFS = "applied_sync_envelopes"
        private const val KEY_APPLIED_ENVELOPES = "ids"
        private const val MAX_APPLIED_ENVELOPES = 256
        private const val KEY_LOGO_ASSET = "logo_asset"
        private const val KEY_RESET_LOGO = "reset_logo"
        const val LOGO_FILENAME = "watchface_logo.png"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_REVISION = "revision"
        private const val KEY_SUCCESS = "success"
        private const val KEY_STATUS_MESSAGE = "status_message"
        private const val KEY_APPLIED_AT = "applied_at"
        private const val KEY_TIMESTAMP = "timestamp"
    }
}
