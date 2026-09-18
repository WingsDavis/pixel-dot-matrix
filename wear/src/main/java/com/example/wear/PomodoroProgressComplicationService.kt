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
        val secondsRemaining = prefs.getInt("seconds_remaining", 1500)
        val isRunning = prefs.getBoolean("is_running", false)

        val maxSeconds = when (state) {
            "FOCUS" -> 25 * 60f
            "SHORT_BREAK" -> 5 * 60f
            "LONG_BREAK" -> 15 * 60f
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
