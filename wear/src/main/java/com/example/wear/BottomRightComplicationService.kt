package com.example.wear

import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BottomRightComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val text = PlainComplicationText.Builder("THU 09").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, text).build()
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null

        val syncPrefs = getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        val configPrefs = getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
        val mode = configPrefs.getString("bottom_right_slot_mode", "date") ?: "date"
        val state = syncPrefs.getString("state", "FOCUS") ?: "FOCUS"
        val secondsRemaining = syncPrefs.getInt("seconds_remaining", 1500)
        val isRunning = syncPrefs.getBoolean("is_running", false)

        val value = when (mode) {
            "hidden" -> null
            "date" -> SimpleDateFormat("EEE dd", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
            "phase" -> state.toDisplayText()
            "timer" -> if (isRunning || state == "PANIC_MODE") secondsRemaining.toClockText() else "IDLE"
            "battery" -> "WATCH"
            else -> SimpleDateFormat("EEE dd", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
        }

        if (value == null) return NoDataComplicationData()

        val text = PlainComplicationText.Builder(value).build()
        return ShortTextComplicationData.Builder(text, text)
            .setTapAction(ComplicationActions.openTimer(this))
            .build()
    }

    private fun String.toDisplayText(): String {
        return when (this) {
            "FOCUS" -> "FOCUS"
            "SHORT_BREAK" -> "SHORT"
            "LONG_BREAK" -> "LONG"
            "PANIC_MODE" -> "PANIC"
            else -> this
        }
    }

    private fun Int.toClockText(): String {
        val minutes = this / 60
        val seconds = this % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
