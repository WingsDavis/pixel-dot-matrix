package com.example.core.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PomodoroEngine(
    private val scope: CoroutineScope
) {
    // Configurable Durations (in seconds)
    var focusDuration = 25 * 60
    var shortBreakDuration = 5 * 60
    var longBreakDuration = 15 * 60

    private val _currentState = MutableStateFlow(PomodoroState.FOCUS)
    val currentState: StateFlow<PomodoroState> = _currentState.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(focusDuration)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var timerJob: Job? = null

    // Store pre-panic state to resume if desired, or fallback
    private var prePanicState: PomodoroState = PomodoroState.FOCUS
    private var prePanicSecondsRemaining: Int = focusDuration
    private var completedFocusSessions: Int = 0

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
    }

    fun skip() {
        pause()
        transitionForManualSkip()
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
        timerJob = scope.launch(Dispatchers.Main) {
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
            transitionToNextState()
        }
    }

    private fun transitionToNextState() {
        when (_currentState.value) {
            PomodoroState.FOCUS -> {
                completedFocusSessions++
                if (completedFocusSessions >= 4) {
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
