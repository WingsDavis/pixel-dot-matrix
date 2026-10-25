package com.example.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
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
        if (request.complicationType != ComplicationType.SHORT_TEXT &&
            request.complicationType != ComplicationType.LONG_TEXT
        ) return null

        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        val configPrefs = getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
        val timer = WearComplicationState.read(this, now)
        val customText = configPrefs.getString("custom_text", prefs.getString("custom_text", null)).orEmpty()
        val leftSlotMode = configPrefs.getString("left_slot_mode", "custom_text") ?: "custom_text"
        val nextPhase = if (timer.state == "FOCUS") "BREAK" else "FOCUS"
        val isActive = timer.isRunning || timer.state == "PANIC_MODE"
        val dailyGoal = if (leftSlotMode == "daily_target") WearDailyFocusGoal.read(this, now) else null
        val heartRateAt = if (leftSlotMode == "heart_rate") prefs.getLong("current_hr_at", 0L) else 0L
        val heartRate = if (heartRateAt in 1L..now && now - heartRateAt < 300_000L) {
            prefs.getInt("current_hr", 0).takeIf { it > 0 }
        } else null

        val displayText: ComplicationText? = when {
            leftSlotMode == "hidden" -> null
            isActive && leftSlotMode == "custom_text" -> null
            leftSlotMode == "custom_text" -> customText.ifBlank { null }?.let { PlainComplicationText.Builder(it).build() }
            leftSlotMode == "phase" -> PlainComplicationText.Builder(timer.state.toDisplayText()).build()
            leftSlotMode == "heart_rate" -> heartRate?.let { PlainComplicationText.Builder("$it BPM").build() }
            leftSlotMode == "next_break" -> if (timer.state == "PANIC_MODE") {
                PlainComplicationText.Builder("RECOVER").build()
            } else timer.countdownText(
                if (request.complicationType == ComplicationType.LONG_TEXT) "$nextPhase IN ^1" else "${nextPhase.first()} ^1"
            )
            leftSlotMode == "daily_target" -> dailyGoal?.let { PlainComplicationText.Builder(it.shortText).build() }
            else -> null
        }

        if (displayText == null) return NoDataComplicationData()
        val title = if (leftSlotMode == "next_break" && timer.state != "PANIC_MODE") {
            PlainComplicationText.Builder(if (timer.isRunning) nextPhase else "PAUSED").build()
        } else null
        val description = if (leftSlotMode == "next_break" && timer.state != "PANIC_MODE") {
            timer.countdownText("${if (timer.isRunning) "" else "Paused. "}$nextPhase IN ^1")
        } else displayText
        val tap = when {
            leftSlotMode == "daily_target" -> ComplicationActions.openDailyTarget(this)
            timer.state == "PANIC_MODE" -> ComplicationActions.openPanic(this)
            else -> ComplicationActions.openTimer(this)
        }
        val validity = when {
            dailyGoal != null -> TimeRange.between(
                Instant.ofEpochMilli(dailyGoal.day.startEpochMs), Instant.ofEpochMilli(dailyGoal.day.endEpochMs - 1)
            )
            leftSlotMode == "heart_rate" -> TimeRange.between(
                Instant.ofEpochMilli(heartRateAt),
                Instant.ofEpochMilli(heartRateAt.coerceAtMost(Long.MAX_VALUE - 299_999L) + 299_999L)
            )
            else -> null
        }

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(displayText, description).apply {
                setTapAction(tap)
                title?.let { setTitle(it) }
                validity?.let { setValidTimeRange(it) }
            }.build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(displayText, description).apply {
                setTapAction(tap)
                title?.let { setTitle(it) }
                validity?.let { setValidTimeRange(it) }
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

}
