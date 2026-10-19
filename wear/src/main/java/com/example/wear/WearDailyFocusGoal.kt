package com.example.wear

import android.content.Context
import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.goal.DailyFocusProgress
import com.example.core.goal.FocusDay
import com.example.core.goal.FocusTargetType
import com.google.android.gms.wearable.DataMap

object WearDailyFocusGoal {
    const val PATH = "/pomodoro/daily_target/v1"
    private const val PREFERENCES = "daily_focus_goal"

    fun save(context: Context, data: DataMap): Boolean {
        if (data.getInt("schema_version", 0) != 1) return false
        val start = data.getLong("day_start", 0L)
        val end = data.getLong("day_end", 0L)
        if (start < 0 || end <= start) return false
        val type = runCatching { FocusTargetType.valueOf(data.getString("type").orEmpty()) }.getOrNull() ?: return false
        val settings = DailyFocusGoalSettings(
            enabled = data.getBoolean("enabled", false),
            type = type,
            targetMinutes = data.getInt("target_minutes", 120),
            targetSessions = data.getInt("target_sessions", 4),
            timeZoneId = data.getString("time_zone") ?: "UTC",
            resetHour = data.getInt("reset_hour", 0)
        ).sanitized()
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", settings.enabled)
            .putString("type", settings.type.name)
            .putInt("target_minutes", settings.targetMinutes)
            .putInt("target_sessions", settings.targetSessions)
            .putString("time_zone", settings.timeZoneId)
            .putInt("reset_hour", settings.resetHour)
            .putLong("day_start", start)
            .putLong("day_end", end)
            .putLong("focused_seconds", data.getLong("focused_seconds", 0L).coerceAtLeast(0))
            .putInt("completed_sessions", data.getInt("completed_sessions", 0).coerceAtLeast(0))
            .commit()
    }

    fun read(context: Context, now: Long = System.currentTimeMillis()): DailyFocusProgress? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean("enabled", false)) return null
        val day = FocusDay(preferences.getLong("day_start", 0L), preferences.getLong("day_end", 0L))
        if (now < day.startEpochMs || now >= day.endEpochMs) return null
        val settings = DailyFocusGoalSettings(
            enabled = true,
            type = runCatching { FocusTargetType.valueOf(preferences.getString("type", "MINUTES")!!) }
                .getOrDefault(FocusTargetType.MINUTES),
            targetMinutes = preferences.getInt("target_minutes", 120),
            targetSessions = preferences.getInt("target_sessions", 4),
            timeZoneId = preferences.getString("time_zone", "UTC") ?: "UTC",
            resetHour = preferences.getInt("reset_hour", 0)
        ).sanitized()
        return DailyFocusProgress(
            settings,
            day,
            preferences.getLong("focused_seconds", 0L).coerceAtLeast(0),
            preferences.getInt("completed_sessions", 0).coerceAtLeast(0)
        )
    }
}
