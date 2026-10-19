package com.example.core.goal

import com.example.core.timer.FocusInterval
import com.example.data.entity.SessionLogEntity

object DailyFocusCalculator {
    fun calculate(
        sessions: List<SessionLogEntity>,
        settings: DailyFocusGoalSettings,
        nowEpochMs: Long,
        activeFocus: FocusInterval? = null
    ): DailyFocusProgress {
        val day = settings.dayAt(nowEpochMs)
        fun secondsInDay(start: Long, seconds: Int): Long {
            val end = start + seconds.coerceAtLeast(0).toLong() * 1000L
            val overlap = minOf(end, day.endEpochMs, nowEpochMs) - maxOf(start, day.startEpochMs)
            return overlap.coerceAtLeast(0L) / 1000L
        }
        val focus = sessions.filter { it.sessionType == "FOCUS" }
        val savedSeconds = focus.sumOf { secondsInDay(it.startTimeMillis, it.durationSeconds) }
        val activeSeconds = activeFocus?.let { secondsInDay(it.startTimeMillis, it.durationSeconds) } ?: 0L
        val completed = focus.count {
            it.status == "COMPLETED" && it.timestamp >= day.startEpochMs &&
                it.timestamp < day.endEpochMs && it.timestamp <= nowEpochMs
        }
        return DailyFocusProgress(settings, day, savedSeconds + activeSeconds, completed)
    }
}
