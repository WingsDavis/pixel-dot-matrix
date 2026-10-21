package com.example.core.timer

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

// Pump Android's real main queue without fast-forwarding the runtime's recurring jobs.
internal fun runRuntimeTest(block: suspend CoroutineScope.() -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val result = scope.async { block() }
    val deadline = System.nanoTime() + 30_000_000_000L
    try {
        while (!result.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        check(result.isCompleted) { "Runtime test did not finish within 30 seconds" }
        runBlocking { result.await() }
    } finally {
        scope.cancel()
    }
}
