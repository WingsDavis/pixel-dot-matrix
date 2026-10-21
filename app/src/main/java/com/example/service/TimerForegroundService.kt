package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.timer.PomodoroState
import com.example.core.timer.TimerRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class TimerForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var pendingCommands = 0
    private var lastStartId = 0
    private var promoted = false

    override fun onCreate() {
        super.onCreate()
        promote()
    }

    private fun promote() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
                CHANNEL, getString(R.string.timer_service_channel), NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null); enableVibration(false) })
            val notification = notification(getString(R.string.timer_tile_loading))
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
            promoted = true
        } catch (error: Exception) {
            TimerRuntime.peek()?.foregroundUnavailable(error)
            Toast.makeText(this, error.message ?: getString(R.string.timer_tile_failed), Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!promoted) promote()
        if (!promoted) return START_NOT_STICKY
        pendingCommands++
        scope.launch {
            val runtime = TimerRuntime.get(applicationContext)
            try {
                runtime.awaitReady()
                val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
                val result = if (intent?.action == ACTION_TOGGLE && requestId != null) runtime.toggle(requestId) else {
                    runtime.refresh()
                    null
                }
                if (result?.applied == false) Toast.makeText(this@TimerForegroundService,
                    result.message ?: getString(R.string.timer_tile_failed), Toast.LENGTH_LONG).show()
                if (observer == null) observer = scope.launch {
                    combine(runtime.engine.currentState, runtime.engine.isRunning, runtime.engine.secondsRemaining) { phase, running, seconds ->
                        Triple(phase, running, (seconds + 59) / 60)
                    }.distinctUntilChanged().collect { (phase, running, minutes) ->
                        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("${phaseLabel(phase)}: $minutes min remaining"))
                        else stopIfIdle(runtime)
                    }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                runtime.reportError(error)
                Toast.makeText(this@TimerForegroundService, error.message ?: getString(R.string.timer_tile_failed), Toast.LENGTH_LONG).show()
            } finally {
                pendingCommands--
                if (runtime.ready.value) stopIfIdle(runtime) else stopSelfResult(lastStartId)
            }
        }
        // A restarted host restores the snapshot. It never repeats the last toggle Intent.
        return START_STICKY
    }

    private fun stopIfIdle(runtime: TimerRuntime) {
        if (pendingCommands == 0 && !runtime.engine.isRunning.value) {
            if (stopSelfResult(lastStartId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                promoted = false
            }
        }
    }

    private fun notification(text: String): Notification {
        val controls = PendingIntent.getActivity(this, 11,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_timer_tile)
            .setContentTitle(getString(R.string.timer_tile_label))
            .setContentText(text).setContentIntent(controls).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "timer_running"
        private const val NOTIFICATION = 2206
        internal const val ACTION_TOGGLE = "com.example.timer.TOGGLE"
        internal const val EXTRA_REQUEST_ID = "request_id"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TimerForegroundService::class.java))
        }

        fun toggle(context: Context, requestId: String) {
            ContextCompat.startForegroundService(context, Intent(context, TimerForegroundService::class.java)
                .setAction(ACTION_TOGGLE).putExtra(EXTRA_REQUEST_ID, requestId))
        }

        private fun phaseLabel(phase: PomodoroState) = when (phase) {
            PomodoroState.FOCUS -> "Focus"
            PomodoroState.SHORT_BREAK -> "Short break"
            PomodoroState.LONG_BREAK -> "Long break"
            PomodoroState.PANIC_MODE -> "Panic recovery"
        }
    }
}
