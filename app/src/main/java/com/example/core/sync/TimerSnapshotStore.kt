package com.example.core.sync

import android.content.Context
import com.example.core.timer.PomodoroState

class TimerSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TimerSnapshot? {
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
            completedFocusSessions = preferences.getInt(KEY_COMPLETED_FOCUS_SESSIONS, 0).coerceAtLeast(0)
        )
    }

    fun save(snapshot: TimerSnapshot) {
        preferences.edit()
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
            .apply()
    }

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
        private const val SOURCE_PHONE = "phone"
    }
}
