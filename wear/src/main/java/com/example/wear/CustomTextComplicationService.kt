package com.example.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import android.content.Context
import androidx.wear.watchface.complications.data.TimeRange
import java.time.Instant

class CustomTextComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val text = PlainComplicationText.Builder("CUSTOM").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, text)
                .setTapAction(ComplicationActions.openTimer(this)).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text, text)
                .setTapAction(ComplicationActions.openTimer(this)).build()
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        val configPrefs = getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
        val state = prefs.getString("state", "FOCUS") ?: "FOCUS"
        val customText = configPrefs.getString("custom_text", prefs.getString("custom_text", null)).orEmpty()
        val leftSlotMode = configPrefs.getString("left_slot_mode", "custom_text") ?: "custom_text"
        val hr = prefs.getInt("current_hr", 74)
        val isRunning = prefs.getBoolean("is_running", false)
        val isActive = isRunning || state == "PANIC_MODE"
        val dailyGoal = if (leftSlotMode == "daily_target") WearDailyFocusGoal.read(this) else null

        val displayStr: String? = when {
            leftSlotMode == "hidden" -> null
            isActive && leftSlotMode == "custom_text" -> null
            leftSlotMode == "custom_text" -> customText.ifBlank { null }
            leftSlotMode == "phase" -> state.toDisplayText()
            leftSlotMode == "heart_rate" -> "$hr BPM"
            leftSlotMode == "next_break" -> state.toNextBreakText()
            leftSlotMode == "streak" -> "STREAK 0"
            leftSlotMode == "daily_target" -> dailyGoal?.shortText
            else -> customText.ifBlank { null }
        }

        if (displayStr == null) {
            return androidx.wear.watchface.complications.data.NoDataComplicationData()
        }

        val text = PlainComplicationText.Builder(displayStr).build()

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, text).apply {
                dailyGoal?.let { setValidTimeRange(TimeRange.between(Instant.ofEpochMilli(it.day.startEpochMs), Instant.ofEpochMilli(it.day.endEpochMs))) }
            }.build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text, text).apply {
                dailyGoal?.let { setValidTimeRange(TimeRange.between(Instant.ofEpochMilli(it.day.startEpochMs), Instant.ofEpochMilli(it.day.endEpochMs))) }
            }.build()
            else -> null
        }
    }

    private fun String.toDisplayText(): String {
        return when (this) {
            "FOCUS" -> "FOCUS"
            "SHORT_BREAK" -> "SHORT BREAK"
            "LONG_BREAK" -> "LONG BREAK"
            "PANIC_MODE" -> "PANIC"
            else -> this
        }
    }

    private fun String.toNextBreakText(): String {
        return when (this) {
            "FOCUS" -> "NEXT BREAK"
            "SHORT_BREAK", "LONG_BREAK" -> "NEXT FOCUS"
            "PANIC_MODE" -> "RECOVER"
            else -> "NEXT BREAK"
        }
    }

}
