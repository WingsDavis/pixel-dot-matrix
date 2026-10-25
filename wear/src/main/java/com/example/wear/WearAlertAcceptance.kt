package com.example.wear

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object WearAlertAcceptance {
    const val EXTRA_TOKEN = "wear_alert_acceptance"
    private data class Pending(val signal: CountDownLatch, val deadlineNs: Long)
    private val pending = ConcurrentHashMap<String, Pending>()

    fun prepare(timeoutMs: Long = 5_000L): String = synchronized(pending) {
        UUID.randomUUID().toString().also {
            pending[it] = Pending(CountDownLatch(1), System.nanoTime() + timeoutMs.coerceIn(0, 5_000) * 1_000_000)
        }
    }
    fun accept(token: String?, apply: () -> Unit = {}): Boolean = synchronized(pending) {
        val request = token?.let(pending::get) ?: return false
        if (request.signal.count == 0L || System.nanoTime() >= request.deadlineNs) return false
        apply()
        request.signal.countDown()
        true
    }
    fun await(token: String, timeoutMs: Long): Boolean = try {
        pending[token]?.signal?.await(timeoutMs, TimeUnit.MILLISECONDS) == true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
    fun release(token: String) { synchronized(pending) { pending.remove(token) } }
}
