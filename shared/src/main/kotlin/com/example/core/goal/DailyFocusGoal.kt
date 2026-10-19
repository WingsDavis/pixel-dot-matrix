package com.example.core.goal

import java.util.Calendar
import java.util.TimeZone

enum class FocusTargetType { MINUTES, SESSIONS }

data class DailyFocusGoalSettings(
    val enabled: Boolean = false,
    val type: FocusTargetType = FocusTargetType.MINUTES,
    val targetMinutes: Int = 120,
    val targetSessions: Int = 4,
    val timeZoneId: String = TimeZone.getDefault().id,
    val resetHour: Int = 0
) {
    val target: Int get() = if (type == FocusTargetType.MINUTES) targetMinutes else targetSessions

    fun sanitized() = copy(
        targetMinutes = targetMinutes.coerceIn(1, 1440),
        targetSessions = targetSessions.coerceIn(1, 100),
        timeZoneId = timeZoneId.takeIf { it in TimeZone.getAvailableIDs() } ?: TimeZone.getDefault().id,
        resetHour = resetHour.coerceIn(0, 23)
    )

    fun dayAt(nowEpochMs: Long): FocusDay {
        val zone = TimeZone.getTimeZone(timeZoneId)
        fun boundary(dayOffset: Int): Long = Calendar.getInstance(zone).apply {
            timeInMillis = nowEpochMs
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, resetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayOffset = if (nowEpochMs < boundary(0)) -1 else 0
        return FocusDay(boundary(dayOffset), boundary(dayOffset + 1))
    }
}

data class FocusDay(val startEpochMs: Long, val endEpochMs: Long)

data class DailyFocusProgress(
    val settings: DailyFocusGoalSettings,
    val day: FocusDay,
    val focusedSeconds: Long = 0,
    val completedSessions: Int = 0
) {
    val value: Long get() = if (settings.type == FocusTargetType.MINUTES) focusedSeconds / 60 else completedSessions.toLong()
    val fraction: Float get() = (value.toFloat() / settings.target).coerceIn(0f, 1f)
    val shortText: String get() = "$value/${settings.target}${if (settings.type == FocusTargetType.MINUTES) "m" else "x"}"
    val complete: Boolean get() = value >= settings.target
}
