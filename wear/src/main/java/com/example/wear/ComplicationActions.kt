package com.example.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object ComplicationActions {
    fun openTimer(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TIMER
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val ACTION_OPEN_TIMER = "com.example.wear.OPEN_TIMER"
}
