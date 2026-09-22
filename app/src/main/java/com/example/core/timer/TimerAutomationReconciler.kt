package com.example.core.timer

import com.example.core.sync.TimerSnapshot

data class TimerReconciliation(
    val state: PomodoroState,
    val secondsRemaining: Int,
    val isRunning: Boolean,
    val completedFocusSessions: Int,
    val transitions: List<TimerTransition>
)

object TimerAutomationReconciler {
    fun reconcile(snapshot: TimerSnapshot, settings: TimerSettings, nowEpochMs: Long): TimerReconciliation {
        var state = snapshot.state
        var remaining = snapshot.secondsRemaining.coerceAtLeast(0)
        var running = snapshot.isRunning
        var completed = snapshot.completedFocusSessions.coerceIn(0, settings.longBreakCadence - 1)
        var elapsed = if (running) ((nowEpochMs - snapshot.updatedAtEpochMs).coerceAtLeast(0L) / 1_000L).toInt() else 0
        val transitions = mutableListOf<TimerTransition>()

        while (running && elapsed >= remaining) {
            elapsed = (elapsed - remaining).coerceAtLeast(0)
            val previous = state
            when (state) {
                PomodoroState.FOCUS -> {
                    completed++
                    if (completed >= settings.longBreakCadence) {
                        completed = 0
                        state = PomodoroState.LONG_BREAK
                        remaining = settings.longBreakDurationSeconds
                    } else {
                        state = PomodoroState.SHORT_BREAK
                        remaining = settings.shortBreakDurationSeconds
                    }
                    running = settings.autoStartBreaks
                }
                PomodoroState.SHORT_BREAK, PomodoroState.LONG_BREAK -> {
                    state = PomodoroState.FOCUS
                    remaining = settings.focusDurationSeconds
                    running = settings.autoStartFocus
                }
                PomodoroState.PANIC_MODE -> running = false
            }
            if (previous != state) {
                transitions += TimerTransition(previous, state, TimerTransitionCause.COMPLETED, nowEpochMs)
            }
            if (!running || remaining <= 0) break
        }
        if (running) remaining = (remaining - elapsed).coerceAtLeast(0)

        return TimerReconciliation(state, remaining, running, completed, transitions)
    }
}
