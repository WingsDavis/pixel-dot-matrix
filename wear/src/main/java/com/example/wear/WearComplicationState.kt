package com.example.wear

import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import java.time.Instant
import java.util.Locale

data class WearComplicationState(
    val state: String,
    val remainingSeconds: Int,
    val isRunning: Boolean,
    val durationSeconds: Int,
    val phaseEndEpochMs: Long?
) {
    fun countdownText(surroundingText: String? = null): ComplicationText = phaseEndEpochMs?.let {
        TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.STOPWATCH,
            CountDownTimeReference(Instant.ofEpochMilli(it))
        ).setDisplayAsNow(false).setText(surroundingText).build()
    } ?: PlainComplicationText.Builder(
        String.format(Locale.getDefault(), "%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)
            .let { surroundingText?.replace("^1", it) ?: it }
    ).build()

    companion object {
        fun read(context: Context, nowEpochMs: Long = System.currentTimeMillis()): WearComplicationState {
            val prefs = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
            val storedState = prefs.getString("state", "FOCUS")
                ?.takeIf { it in setOf("FOCUS", "SHORT_BREAK", "LONG_BREAK", "PANIC_MODE") }
            val state = storedState ?: "FOCUS"
            val duration = when (state) {
                "SHORT_BREAK" -> prefs.getInt("short_break_duration", 300).takeIf { it in 60..1500 } ?: 300
                "LONG_BREAK" -> prefs.getInt("long_break_duration", 900).takeIf { it in 300..2700 } ?: 900
                "PANIC_MODE" -> 60
                else -> prefs.getInt("focus_duration", 1500).takeIf { it in 60..3600 } ?: 1500
            }
            val storedSeconds = prefs.getInt("seconds_remaining", duration).coerceIn(0, duration)
            val running = storedState != null && prefs.getBoolean("is_running", false)
            val now = nowEpochMs.coerceAtLeast(0L)
            val updatedAt = prefs.getLong("timer_updated_at", now).coerceIn(0L, now)
            val elapsed = if (running) ((now - updatedAt) / 1000L).coerceAtMost(storedSeconds.toLong()) else 0L
            val durationMs = storedSeconds * 1000L
            val end = if (running) updatedAt.coerceAtMost(Long.MAX_VALUE - durationMs) + durationMs else null
            val remaining = (storedSeconds - elapsed).toInt()
            return WearComplicationState(state, remaining,
                running && (remaining > 0 || state == "PANIC_MODE"), duration, end)
        }
    }
}
