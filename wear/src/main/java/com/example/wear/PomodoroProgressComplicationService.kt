package com.example.wear

import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import android.util.Log

class PomodoroProgressComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = -1f,
                min = -1f,
                max = 1500f,
                contentDescription = androidx.wear.watchface.complications.data.PlainComplicationText.Builder("Pixel Dot Matrix progress").build()
            ).setText(androidx.wear.watchface.complications.data.PlainComplicationText.Builder("Pomo").build()).build()
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        val state = prefs.getString("state", "FOCUS") ?: "FOCUS"
        val storedSeconds = prefs.getInt("seconds_remaining", 1500)
        val isRunning = prefs.getBoolean("is_running", false)
        val secondsRemaining = if (isRunning) {
            val elapsed = ((System.currentTimeMillis() - prefs.getLong("timer_updated_at", System.currentTimeMillis()))
                .coerceAtLeast(0L) / 1_000L).toInt()
            (storedSeconds - elapsed).coerceAtLeast(0)
        } else storedSeconds

        val maxSeconds = when (state) {
            "FOCUS" -> prefs.getInt("focus_duration", 25 * 60).toFloat()
            "SHORT_BREAK" -> prefs.getInt("short_break_duration", 5 * 60).toFloat()
            "LONG_BREAK" -> prefs.getInt("long_break_duration", 15 * 60).toFloat()
            "PANIC_MODE" -> 60f
            else -> 25 * 60f
        }

        // Send -1 to tell watchface to hide the session UI when idle
        val safeValue = if (!isRunning && state != "PANIC_MODE") {
            -1f
        } else {
            if (state == "PANIC_MODE") maxSeconds else secondsRemaining.toFloat().coerceIn(0f, maxSeconds)
        }

        val stateText = when (state) {
            "FOCUS" -> "Focus"
            "SHORT_BREAK" -> "Short Break"
            "LONG_BREAK" -> "Long Break"
            "PANIC_MODE" -> "Panic"
            else -> "Focus"
        }

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = safeValue,
                min = -1f,
                max = maxSeconds,
                contentDescription = androidx.wear.watchface.complications.data.PlainComplicationText.Builder("Pixel Dot Matrix progress").build()
            ).setText(androidx.wear.watchface.complications.data.PlainComplicationText.Builder(stateText).build())
                .setTapAction(ComplicationActions.openTimer(this))
                .build()
            else -> null
        }
    }
}
