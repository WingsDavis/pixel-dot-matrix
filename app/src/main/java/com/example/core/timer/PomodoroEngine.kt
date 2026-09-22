package com.example.core.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TimerTransitionCause { COMPLETED, MANUAL }

data class TimerTransition(
    val from: PomodoroState,
    val to: PomodoroState,
    val cause: TimerTransitionCause,
    val occurredAtEpochMs: Long = System.currentTimeMillis()
)

class PomodoroEngine(
    private val scope: CoroutineScope,
    initialSettings: TimerSettings = TimerSettings(),
    private val timerDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    var focusDuration = initialSettings.focusDurationSeconds
        private set
    var shortBreakDuration = initialSettings.shortBreakDurationSeconds
        private set
    var longBreakDuration = initialSettings.longBreakDurationSeconds
        private set
    var longBreakCadence = initialSettings.longBreakCadence
        private set
    private var autoStartBreaks = initialSettings.autoStartBreaks
    private var autoStartFocus = initialSettings.autoStartFocus
    val autoStartBreaksEnabled: Boolean get() = autoStartBreaks
    val autoStartFocusEnabled: Boolean get() = autoStartFocus

    private val _currentState = MutableStateFlow(PomodoroState.FOCUS)
    val currentState: StateFlow<PomodoroState> = _currentState.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(focusDuration)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _transitionEvents = MutableSharedFlow<TimerTransition>(extraBufferCapacity = 4)
    val transitionEvents: SharedFlow<TimerTransition> = _transitionEvents

    private var timerJob: Job? = null

    // Store pre-panic state to resume if desired, or fallback
    private var prePanicState: PomodoroState = PomodoroState.FOCUS
    private var prePanicSecondsRemaining: Int = focusDuration
    var completedFocusSessions: Int = 0
        private set

    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        startTimerJob()
    }

    fun pause() {
        if (!_isRunning.value) return
        _isRunning.value = false
        timerJob?.cancel()
    }

    fun reset() {
        pause()
        _currentState.value = PomodoroState.FOCUS
        _secondsRemaining.value = focusDuration
        completedFocusSessions = 0
    }

    fun applySettings(settings: TimerSettings, resetTimer: Boolean = false) {
        focusDuration = settings.focusDurationSeconds
        shortBreakDuration = settings.shortBreakDurationSeconds
        longBreakDuration = settings.longBreakDurationSeconds
        longBreakCadence = settings.longBreakCadence
        autoStartBreaks = settings.autoStartBreaks
        autoStartFocus = settings.autoStartFocus
        if (resetTimer) reset()
    }

    fun skip() {
        pause()
        val previous = _currentState.value
        transitionForManualSkip()
        if (previous != _currentState.value) {
            _transitionEvents.tryEmit(TimerTransition(previous, _currentState.value, TimerTransitionCause.MANUAL))
        }
    }

    fun triggerPanic() {
        if (_currentState.value == PomodoroState.PANIC_MODE) return

        // Save current progress to resume later
        prePanicState = _currentState.value
        prePanicSecondsRemaining = _secondsRemaining.value

        pause()
        _currentState.value = PomodoroState.PANIC_MODE
        _secondsRemaining.value = 0 // Wait for steps
        _isRunning.value = false // Don't auto-resolve
    }

    fun resolvePanic() {
        if (_currentState.value != PomodoroState.PANIC_MODE) return
        pause()

        // Restore pre-panic state or default to Focus
        _currentState.value = prePanicState
        _secondsRemaining.value = prePanicSecondsRemaining
    }

    private fun startTimerJob() {
        timerJob?.cancel()
        timerJob = scope.launch(timerDispatcher) {
            while (_isRunning.value) {
                delay(1000)
                if (_secondsRemaining.value > 0) {
                    _secondsRemaining.value -= 1
                } else {
                    onTimerFinished()
                    break
                }
            }
        }
    }

    private fun onTimerFinished() {
        _isRunning.value = false
        timerJob?.cancel()

        if (_currentState.value == PomodoroState.PANIC_MODE) {
            // Panic session finished. The time is up, so we automatically resolve.
            resolvePanic()
        } else {
            val previous = _currentState.value
            transitionToNextState()
            val next = _currentState.value
            _transitionEvents.tryEmit(TimerTransition(previous, next, TimerTransitionCause.COMPLETED))
            val shouldAutoStart = when (previous) {
                PomodoroState.FOCUS -> autoStartBreaks
                PomodoroState.SHORT_BREAK, PomodoroState.LONG_BREAK -> autoStartFocus
                PomodoroState.PANIC_MODE -> false
            }
            if (shouldAutoStart) start()
        }
    }

    private fun transitionToNextState() {
        when (_currentState.value) {
            PomodoroState.FOCUS -> {
                completedFocusSessions++
                if (completedFocusSessions >= longBreakCadence) {
                    _currentState.value = PomodoroState.LONG_BREAK
                    _secondsRemaining.value = longBreakDuration
                    completedFocusSessions = 0
                } else {
                    _currentState.value = PomodoroState.SHORT_BREAK
                    _secondsRemaining.value = shortBreakDuration
                }
            }
            PomodoroState.SHORT_BREAK, PomodoroState.LONG_BREAK -> {
                _currentState.value = PomodoroState.FOCUS
                _secondsRemaining.value = focusDuration
            }
            PomodoroState.PANIC_MODE -> {
                // Done on its own
            }
        }
    }

    private fun transitionForManualSkip() {
        when (_currentState.value) {
            PomodoroState.FOCUS -> {
                _currentState.value = PomodoroState.SHORT_BREAK
                _secondsRemaining.value = shortBreakDuration
            }
            PomodoroState.SHORT_BREAK -> {
                _currentState.value = PomodoroState.LONG_BREAK
                _secondsRemaining.value = longBreakDuration
                completedFocusSessions = 0
            }
            PomodoroState.LONG_BREAK -> {
                _currentState.value = PomodoroState.FOCUS
                _secondsRemaining.value = focusDuration
            }
            PomodoroState.PANIC_MODE -> Unit
        }
    }

    // Explicit setter for synchronizing external states (e.g., from Wearable device)
    fun syncState(state: PomodoroState, remainingSeconds: Int, running: Boolean) {
        _currentState.value = state
        _secondsRemaining.value = remainingSeconds
        _isRunning.value = running

        if (running) {
            startTimerJob()
        } else {
            timerJob?.cancel()
        }
    }

    fun restoreCompletedFocusSessions(count: Int) {
        completedFocusSessions = count.coerceIn(0, longBreakCadence - 1)
    }

    internal fun completeCurrentInterval() {
        pause()
        onTimerFinished()
    }

    // Adapt timer duration dynamically based on biometric feedback
    fun reduceTime(seconds: Int) {
        if (_secondsRemaining.value > seconds) {
            _secondsRemaining.value -= seconds
        } else {
            _secondsRemaining.value = 0
            onTimerFinished()
        }
    }
}
