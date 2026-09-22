package com.example.core.timer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class SessionTransitionNotifier(private val context: Context) {
    fun notify(transition: TimerTransition, settings: TimerSettings) {
        if (settings.transitionNotificationEnabled) showNotification(transition)
        if (settings.transitionSoundEnabled) playSound()
        if (settings.transitionHapticEnabled) playHaptic()
    }

    private fun showNotification(transition: TimerTransition) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Session transitions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Focus and break transition alerts"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (transition.to) {
            PomodoroState.FOCUS -> "Focus session ready"
            PomodoroState.SHORT_BREAK -> "Short break ready"
            PomodoroState.LONG_BREAK -> "Long break ready"
            PomodoroState.PANIC_MODE -> "Panic mode"
        }
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(if (transition.to == PomodoroState.FOCUS) "Return to your task." else "Step away and reset.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    private fun playSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build()
                }
                play()
            }
        }
    }

    private fun playHaptic() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(60L, 80))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60L)
        }
    }

    private companion object {
        const val CHANNEL_ID = "session_transitions"
        const val NOTIFICATION_ID = 2205
    }
}
