package com.example.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.example.MainActivity
import com.example.R
import com.example.core.timer.PomodoroState
import com.example.core.timer.TimerRuntime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class TimerTileState(val state: Int, val label: String, val subtitle: String)

class PomodoroTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        render(TimerTileState(Tile.STATE_UNAVAILABLE, getString(R.string.timer_tile_label), getString(R.string.timer_tile_loading)))
        listening = scope.launch {
            val runtime = TimerRuntime.get(applicationContext)
            try {
                runtime.awaitReady()
                runtime.refresh()
                combine(runtime.engine.currentState, runtime.engine.isRunning, runtime.engine.secondsRemaining,
                    runtime.error, runtime.settingsPending) { state, running, seconds, error, pending ->
                    displayState(state, running, seconds, error, pending)
                }.distinctUntilChanged().collect(::render)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                render(TimerTileState(Tile.STATE_UNAVAILABLE, getString(R.string.timer_tile_label), error.message ?: getString(R.string.timer_tile_failed)))
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Never queue a timer mutation across keyguard dismissal or cancellation.
        routeClick(isLocked, ::openControls) {
            val requestId = UUID.randomUUID().toString()
            val runtime = TimerRuntime.get(applicationContext)
            if (runtime.ready.value && (runtime.settingsPending.value || runtime.engine.currentState.value == PomodoroState.PANIC_MODE)) return@routeClick
            try {
                TimerForegroundService.toggle(this, requestId)
            } catch (error: Exception) {
                runtime.reportError(error)
                Toast.makeText(this, error.message ?: getString(R.string.timer_tile_failed), Toast.LENGTH_LONG).show()
                render(failureState(runtime.ready.value, error.message ?: getString(R.string.timer_tile_failed)) {
                    displayState(runtime.engine.currentState.value, runtime.engine.isRunning.value,
                        runtime.engine.secondsRemaining.value, runtime.error.value, runtime.settingsPending.value)
                })
            }
        }
    }

    private fun render(state: TimerTileState) {
        qsTile?.apply {
            this.state = state.state
            label = state.label
            contentDescription = "${state.label}: ${state.subtitle}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = state.subtitle
            updateTile()
        }
    }

    internal fun openControls() {
        val intent = controlsIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 10, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun requestRefresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, PomodoroTileService::class.java)) }
        }

        internal fun controlsIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
            action = ACTION_QS_TILE_PREFERENCES
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        internal fun routeClick(locked: Boolean, openControls: () -> Unit, action: () -> Unit) {
            if (locked) openControls() else action()
        }

        internal fun failureState(ready: Boolean, message: String, currentState: () -> TimerTileState): TimerTileState =
            if (ready) currentState() else TimerTileState(Tile.STATE_UNAVAILABLE, "Focus timer", message)

        internal fun displayState(phase: PomodoroState, running: Boolean, seconds: Int, error: String?, settingsPending: Boolean, ready: Boolean = true): TimerTileState {
            if (!ready) return TimerTileState(Tile.STATE_UNAVAILABLE, "Focus timer", "Loading timer")
            if (settingsPending) return TimerTileState(Tile.STATE_UNAVAILABLE, "Focus timer", "Finish settings restore in app")
            if (phase == PomodoroState.PANIC_MODE) return TimerTileState(Tile.STATE_UNAVAILABLE, "Panic recovery", "Open app for recovery controls")
            val label = when (phase) {
                PomodoroState.FOCUS -> "Focus"
                PomodoroState.SHORT_BREAK -> "Short break"
                PomodoroState.LONG_BREAK -> "Long break"
                PomodoroState.PANIC_MODE -> "Panic recovery"
            }
            return TimerTileState(if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
                if (error != null) "Timer error" else "$label ${if (running) "pause" else "start"}",
                error ?: "${(seconds + 59) / 60} min remaining")
        }
    }
}
