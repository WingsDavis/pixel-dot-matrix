package com.example.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableLongStateOf

object ComplicationActions {
    fun openTimer(context: Context): PendingIntent = open(context, ACTION_OPEN_TIMER, 100)
    fun openDailyTarget(context: Context): PendingIntent = open(context, ACTION_OPEN_DAILY_TARGET, 101)
    fun openPanic(context: Context): PendingIntent = open(context, ACTION_OPEN_PANIC, 102)

    private fun open(context: Context, destination: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = destination
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val ACTION_OPEN_TIMER = "com.example.wear.OPEN_TIMER"
    const val ACTION_OPEN_DAILY_TARGET = "com.example.wear.OPEN_DAILY_TARGET"
    const val ACTION_OPEN_PANIC = "com.example.wear.OPEN_PANIC"

    internal fun pageForAction(action: String?, phase: String): Int? = when (action) {
        ACTION_OPEN_DAILY_TARGET -> 5
        ACTION_OPEN_PANIC -> 3
        ACTION_OPEN_TIMER -> when (phase) {
            "SHORT_BREAK" -> 1
            "LONG_BREAK" -> 2
            "PANIC_MODE" -> 3
            else -> 0
        }
        else -> null
    }
}

internal class ComplicationNavigation {
    private val requestVersion = mutableLongStateOf(0L)
    private var pendingPage: Int? = null
    val version: Long get() = requestVersion.longValue

    fun request(action: String?, phase: String) {
        pendingPage = ComplicationActions.pageForAction(action, phase) ?: return
        requestVersion.longValue++
    }

    fun destination(phase: String): Int = pendingPage
        ?: ComplicationActions.pageForAction(ComplicationActions.ACTION_OPEN_TIMER, phase)!!

    fun completed(version: Long) {
        if (version == this.version) pendingPage = null
    }
}
