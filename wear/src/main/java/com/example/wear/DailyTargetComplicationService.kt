package com.example.wear

import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class DailyTargetComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val text = PlainComplicationText.Builder("45/120m").build()
        return ShortTextComplicationData.Builder(text, PlainComplicationText.Builder("Daily focus target").build())
            .setTapAction(ComplicationActions.openTimer(this)).build()
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val prefs = getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        val focused = prefs.getInt("daily_focus_minutes", 0).coerceAtLeast(0)
        val target = prefs.getInt("daily_target_minutes", 120).coerceAtLeast(1)
        val value = "${focused.coerceAtMost(target)}/${target}m"
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(value).build(),
            PlainComplicationText.Builder("Daily focus target").build()
        ).setTapAction(ComplicationActions.openTimer(this)).build()
    }
}
