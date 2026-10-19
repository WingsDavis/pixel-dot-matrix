package com.example.wear

import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import java.time.Instant
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
        val progress = WearDailyFocusGoal.read(this) ?: return NoDataComplicationData()
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(progress.shortText).build(),
            PlainComplicationText.Builder("Daily focus target").build()
        ).setTapAction(ComplicationActions.openTimer(this))
            .setValidTimeRange(TimeRange.between(Instant.ofEpochMilli(progress.day.startEpochMs), Instant.ofEpochMilli(progress.day.endEpochMs)))
            .build()
    }
}
