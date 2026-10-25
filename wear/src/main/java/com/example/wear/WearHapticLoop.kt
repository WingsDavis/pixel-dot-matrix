package com.example.wear

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class WearHapticLoop(
    private val scope: CoroutineScope,
    private val cancelOutput: () -> Unit
) {
    private var pattern: WearHapticPattern? = null
    private var job: Job? = null

    fun start(pattern: WearHapticPattern, block: suspend CoroutineScope.() -> Unit) {
        if (this.pattern == pattern && job?.isActive == true) return
        stop()
        this.pattern = pattern
        job = scope.launch(block = block)
    }

    fun stop(force: Boolean = false) {
        val previous = job
        val wasActive = previous?.isActive == true
        job = null
        pattern = null
        previous?.cancel()
        if (wasActive || force) cancelOutput()
    }
}
