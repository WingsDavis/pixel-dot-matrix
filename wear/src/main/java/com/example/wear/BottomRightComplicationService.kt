package com.example.wear

import android.content.Context
import android.os.BatteryManager
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.wear.watchface.complications.data.TimeFormatComplicationText
import androidx.wear.watchface.complications.data.TimeFormatStyle
import androidx.wear.watchface.complications.data.TimeRange
import java.time.Instant

class BottomRightComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val text = PlainComplicationText.Builder("THU 09").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, text)
                .setTapAction(ComplicationActions.openTimer(this)).build()
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null

        val now = System.currentTimeMillis()
        val configPrefs = getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
        val mode = configPrefs.getString("bottom_right_slot_mode", "date") ?: "date"
        val timer = WearComplicationState.read(this, now)
        val dailyGoal = if (mode == "daily_target") WearDailyFocusGoal.read(this, now) else null

        val text: ComplicationText? = when (mode) {
            "hidden" -> null
            "date" -> TimeFormatComplicationText.Builder("EEE dd").setStyle(TimeFormatStyle.UPPER_CASE).build()
            "phase" -> PlainComplicationText.Builder(timer.state.toDisplayText()).build()
            "timer" -> when {
                timer.state == "PANIC_MODE" -> PlainComplicationText.Builder("PANIC").build()
                timer.isRunning -> timer.countdownText()
                else -> PlainComplicationText.Builder("IDLE").build()
            }
            "battery" -> getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
                ?.let { PlainComplicationText.Builder("$it%").build() }
            "daily_target" -> dailyGoal?.let { PlainComplicationText.Builder(it.shortText).build() }
            else -> null
        }

        if (text == null) return NoDataComplicationData()

        return ShortTextComplicationData.Builder(text, text)
            .setTapAction(when {
                mode == "daily_target" -> ComplicationActions.openDailyTarget(this)
                timer.state == "PANIC_MODE" && (mode == "timer" || mode == "phase") -> ComplicationActions.openPanic(this)
                else -> ComplicationActions.openTimer(this)
            })
            .apply {
                dailyGoal?.let {
                    setValidTimeRange(TimeRange.between(Instant.ofEpochMilli(it.day.startEpochMs), Instant.ofEpochMilli(it.day.endEpochMs - 1)))
                }
            }
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

}
