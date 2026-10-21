package com.example.core.sync

import android.content.Context
import android.content.SharedPreferences
import com.example.core.timer.PomodoroState
import com.example.core.timer.FocusInterval
import org.json.JSONObject
import org.json.JSONArray

class TimerSnapshotStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))
    private var committedSnapshot = readSnapshot()
    private var committedCommands = readCommands()
    private val committedPending = preferences.all.entries.filter { it.key.startsWith(PENDING_FOCUS_PREFIX) }
        .mapNotNull { (it.value as? String)?.let(::decodeInterval) }.associateBy { it.id }.toMutableMap()

    @Synchronized
    fun load(): TimerSnapshot? = committedSnapshot

    private fun readSnapshot(): TimerSnapshot? {
        if (!preferences.contains(KEY_REVISION)) return null
        val state = runCatching {
            PomodoroState.valueOf(preferences.getString(KEY_STATE, null) ?: return null)
        }.getOrNull() ?: return null
        val authority = runCatching {
            TimerAuthority.valueOf(preferences.getString(KEY_AUTHORITY, null) ?: TimerAuthority.PHONE.name)
        }.getOrDefault(TimerAuthority.PHONE)
        return TimerSnapshot(
            revision = preferences.getLong(KEY_REVISION, -1L),
            sessionId = preferences.getString(KEY_SESSION_ID, null) ?: return null,
            sourceDevice = preferences.getString(KEY_SOURCE_NODE, SOURCE_PHONE) ?: SOURCE_PHONE,
            authority = authority,
            state = state,
            secondsRemaining = preferences.getInt(KEY_SECONDS_REMAINING, 0).coerceAtLeast(0),
            isRunning = preferences.getBoolean(KEY_IS_RUNNING, false),
            updatedAtEpochMs = preferences.getLong(KEY_UPDATED_AT, 0L),
            anchorElapsedRealtimeMs = preferences.getLong(KEY_ANCHOR, 0L),
            leaseExpiresAtEpochMs = preferences.getLong(KEY_LEASE_EXPIRES_AT, 0L),
            completedFocusSessions = preferences.getInt(KEY_COMPLETED_FOCUS_SESSIONS, 0).coerceAtLeast(0),
            activeFocusInterval = preferences.getString(KEY_ACTIVE_FOCUS, null)?.let(::decodeInterval)
        )
    }

    @Synchronized
    fun save(snapshot: TimerSnapshot, commandId: String? = null) {
        val ids = if (commandId == null) committedCommands else committedCommands.filterNot { it == commandId }.takeLast(255) + commandId
        try {
            check(writeSnapshot(preferences.edit(), snapshot, ids).commit()) { "Unable to persist timer snapshot" }
        } catch (error: Exception) {
            // commit() updates SharedPreferences memory even when its disk write fails.
            runCatching { writeSnapshot(preferences.edit(), committedSnapshot, committedCommands).commit() }
            throw error
        }
        committedSnapshot = snapshot
        committedCommands = ids
    }

    private fun writeSnapshot(editor: SharedPreferences.Editor, snapshot: TimerSnapshot?, ids: List<String>): SharedPreferences.Editor {
        editor.putString(KEY_APPLIED_COMMANDS, JSONArray(ids).toString())
        if (snapshot == null) {
            SNAPSHOT_KEYS.forEach { editor.remove(it) }
            return editor
        }
        return editor
            .putLong(KEY_REVISION, snapshot.revision)
            .putString(KEY_SESSION_ID, snapshot.sessionId)
            .putString(KEY_SOURCE_NODE, snapshot.sourceDevice)
            .putString(KEY_AUTHORITY, snapshot.authority.name)
            .putString(KEY_STATE, snapshot.state.name)
            .putInt(KEY_SECONDS_REMAINING, snapshot.secondsRemaining)
            .putBoolean(KEY_IS_RUNNING, snapshot.isRunning)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtEpochMs)
            .putLong(KEY_ANCHOR, snapshot.anchorElapsedRealtimeMs)
            .putLong(KEY_LEASE_EXPIRES_AT, snapshot.leaseExpiresAtEpochMs)
            .putInt(KEY_COMPLETED_FOCUS_SESSIONS, snapshot.completedFocusSessions)
            .putString(KEY_ACTIVE_FOCUS, snapshot.activeFocusInterval?.let(::encodeInterval))
    }

    @Synchronized
    fun wasCommandApplied(id: String): Boolean = id in committedCommands

    private fun readCommands(): List<String> {
        val values = JSONArray(preferences.getString(KEY_APPLIED_COMMANDS, "[]"))
        return List(values.length()) { values.getString(it) }
    }

    @Synchronized
    fun savePendingFocusInterval(interval: FocusInterval) {
        val key = PENDING_FOCUS_PREFIX + interval.id
        if (!preferences.edit().putString(key, encodeInterval(interval)).commit()) {
            preferences.edit().putString(key, committedPending[interval.id]?.let(::encodeInterval)).commit()
            error("Unable to persist focus accounting")
        }
        committedPending[interval.id] = interval
    }

    @Synchronized
    fun pendingFocusIntervals(): List<FocusInterval> = committedPending.values.toList()

    @Synchronized
    fun removePendingFocusInterval(id: String) {
        val key = PENDING_FOCUS_PREFIX + id
        if (!preferences.edit().remove(key).commit()) {
            preferences.edit().putString(key, committedPending[id]?.let(::encodeInterval)).commit()
            error("Unable to acknowledge focus accounting")
        }
        committedPending.remove(id)
    }

    private fun encodeInterval(interval: FocusInterval): String = JSONObject().apply {
        put("id", interval.id)
        put("start", interval.startTimeMillis)
        put("seconds", interval.durationSeconds)
        put("completed", interval.completed)
    }.toString()

    private fun decodeInterval(raw: String): FocusInterval? = runCatching {
        val json = JSONObject(raw)
        FocusInterval(json.getLong("start"), json.getInt("seconds").coerceAtLeast(0), json.getBoolean("completed"), json.getString("id"))
    }.getOrNull()

    companion object {
        private const val PREFERENCES_NAME = "timer_authority_snapshot"
        private const val KEY_REVISION = "revision"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SOURCE_NODE = "source_node"
        private const val KEY_AUTHORITY = "authority"
        private const val KEY_STATE = "state"
        private const val KEY_SECONDS_REMAINING = "seconds_remaining"
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_ANCHOR = "anchor"
        private const val KEY_LEASE_EXPIRES_AT = "lease_expires_at"
        private const val KEY_COMPLETED_FOCUS_SESSIONS = "completed_focus_sessions"
        private const val KEY_ACTIVE_FOCUS = "active_focus_interval"
        private const val KEY_APPLIED_COMMANDS = "applied_commands"
        private const val PENDING_FOCUS_PREFIX = "pending_focus_"
        private const val SOURCE_PHONE = "phone"
        private val SNAPSHOT_KEYS = listOf(KEY_REVISION, KEY_SESSION_ID, KEY_SOURCE_NODE, KEY_AUTHORITY, KEY_STATE,
            KEY_SECONDS_REMAINING, KEY_IS_RUNNING, KEY_UPDATED_AT, KEY_ANCHOR, KEY_LEASE_EXPIRES_AT,
            KEY_COMPLETED_FOCUS_SESSIONS, KEY_ACTIVE_FOCUS)
    }
}
