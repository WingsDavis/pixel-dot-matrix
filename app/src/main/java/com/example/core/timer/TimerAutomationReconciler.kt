package com.example.core.timer

import com.example.core.sync.TimerSnapshot

data class TimerReconciliation(
    val state: PomodoroState,
    val secondsRemaining: Int,
    val isRunning: Boolean,
    val completedFocusSessions: Int,
    val transitions: List<TimerTransition>,
    val focusIntervals: List<FocusInterval> = emptyList(),
    val activeFocusInterval: FocusInterval? = null
)

object TimerAutomationReconciler {
    fun reconcile(
        snapshot: TimerSnapshot,
        settings: TimerSettings,
        nowEpochMs: Long,
        pendingIntervals: List<FocusInterval> = emptyList()
    ): TimerReconciliation {
        val recorded = pendingIntervals.firstOrNull { it.id == snapshot.activeFocusInterval?.id }
        var state = snapshot.state
        var remaining = when {
            recorded == null -> snapshot.secondsRemaining.coerceAtLeast(0)
            recorded.completed -> 0
            else -> (snapshot.secondsRemaining - recorded.durationSeconds + (snapshot.activeFocusInterval?.durationSeconds ?: 0)).coerceAtLeast(0)
        }
        var running = snapshot.isRunning && (recorded == null || recorded.completed)
        var completed = snapshot.completedFocusSessions.coerceIn(0, settings.longBreakCadence - 1)
        var phaseTime = recorded?.endTimeMillis ?: snapshot.updatedAtEpochMs
        var elapsed = if (running) ((nowEpochMs - phaseTime).coerceAtLeast(0L) / 1_000L).toInt() else 0
        val transitions = mutableListOf<TimerTransition>()
        val intervals = mutableListOf<FocusInterval>()
        if (recorded?.completed == true) intervals += recorded
        fun newFocusInterval(start: Long) = FocusInterval(
            startTimeMillis = start,
            id = java.util.UUID.nameUUIDFromBytes("${snapshot.sessionId}:focus:$start".toByteArray()).toString()
        )
        var activeFocus = if (recorded != null) null else snapshot.activeFocusInterval
            ?: if (running && state == PomodoroState.FOCUS) newFocusInterval(phaseTime) else null

        while (running && elapsed >= remaining) {
            elapsed = (elapsed - remaining).coerceAtLeast(0)
            phaseTime += remaining.toLong() * 1000L
            val previous = state
            when (state) {
                PomodoroState.FOCUS -> {
                    activeFocus?.let { intervals += it.copy(durationSeconds = it.durationSeconds + remaining, completed = true) }
                    activeFocus = null
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
                    if (running) activeFocus = newFocusInterval(phaseTime)
                }
                PomodoroState.PANIC_MODE -> running = false
            }
            if (previous != state) {
                transitions += TimerTransition(previous, state, TimerTransitionCause.COMPLETED, phaseTime)
            }
            if (!running || remaining <= 0) break
        }
        if (running) {
            remaining = (remaining - elapsed).coerceAtLeast(0)
            activeFocus = activeFocus?.let { it.copy(durationSeconds = it.durationSeconds + elapsed) }
        }

        return TimerReconciliation(state, remaining, running, completed, transitions, intervals, activeFocus)
    }
}
