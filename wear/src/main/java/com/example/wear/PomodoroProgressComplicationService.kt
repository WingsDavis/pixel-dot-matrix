package com.example.wear

import android.os.Build
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicFloat
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant

class PomodoroProgressComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = -1f,
                min = -1f,
                max = 1500f,
                contentDescription = PlainComplicationText.Builder("Pomodoro idle").build()
            ).setText(PlainComplicationText.Builder("Pomo").build())
                .setTapAction(ComplicationActions.openTimer(this)).build()
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.RANGED_VALUE) return null
        val timer = WearComplicationState.read(this)
        val maxSeconds = timer.durationSeconds.toFloat()

        // WFF uses -1 for idle and the phase duration as the range maximum.
        val safeValue = when {
            timer.state == "PANIC_MODE" -> maxSeconds
            !timer.isRunning -> -1f
            else -> timer.remainingSeconds.toFloat()
        }

        val stateText = when (timer.state) {
            "FOCUS" -> "Focus"
            "SHORT_BREAK" -> "Short Break"
            "LONG_BREAK" -> "Long Break"
            "PANIC_MODE" -> "Panic"
            else -> "Focus"
        }

        val description = PlainComplicationText.Builder("$stateText timer").build()
        val end = timer.phaseEndEpochMs
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            timer.isRunning && timer.state != "PANIC_MODE" && timer.remainingSeconds > 0 && end != null
        ) {
            val remaining = DynamicInstant.platformTimeWithSecondsPrecision()
                .durationUntil(DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(end)))
                .toIntSeconds().asFloat()
            val bounded = DynamicFloat.onCondition(remaining.lte(0f)).use(-1f).elseUse(
                DynamicFloat.onCondition(remaining.gt(maxSeconds)).use(maxSeconds).elseUse(remaining)
            )
            RangedValueComplicationData.Builder(
                dynamicValue = bounded,
                fallbackValue = safeValue,
                min = -1f,
                max = maxSeconds,
                contentDescription = description
            )
        } else {
            RangedValueComplicationData.Builder(
                value = safeValue,
                min = -1f,
                max = maxSeconds,
                contentDescription = description
            )
        }
        return builder.setText(PlainComplicationText.Builder(stateText).build())
            .setTapAction(if (timer.state == "PANIC_MODE") ComplicationActions.openPanic(this) else ComplicationActions.openTimer(this))
            .build()
    }
}
