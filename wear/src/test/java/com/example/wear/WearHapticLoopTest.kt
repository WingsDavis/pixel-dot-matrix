package com.example.wear

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WearHapticLoopTest {
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.Unconfined)
    private var outputCancellations = 0
    private val loop = WearHapticLoop(scope) { outputCancellations++ }

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun switchesFrustrationToPanicAndBackByCancellingTheOldJobFirst() {
        val events = mutableListOf<String>()
        startRecording(WearHapticPattern.FRUSTRATION, events)
        startRecording(WearHapticPattern.PANIC_PULSE, events)
        startRecording(WearHapticPattern.FRUSTRATION, events)

        assertEquals(listOf("start:FRUSTRATION", "stop:FRUSTRATION", "start:PANIC_PULSE",
            "stop:PANIC_PULSE", "start:FRUSTRATION"), events)
        assertEquals(2, outputCancellations)
        assertEquals(1, parent.children.count())
        loop.stop()
        assertEquals("stop:FRUSTRATION", events.last())
        assertEquals(3, outputCancellations)
    }

    @Test fun identicalActivePatternKeepsTheSameJobAndDoesNotRestart() {
        var starts = 0
        loop.start(WearHapticPattern.PANIC_PULSE) {
            starts++
            awaitCancellation()
        }
        val original = parent.children.single()
        loop.start(WearHapticPattern.PANIC_PULSE) { starts++ }

        assertEquals(1, starts)
        assertSame(original, parent.children.single())
        assertTrue(original.isActive)
        assertEquals(0, outputCancellations)
    }

    @Test fun finishedJobCanRestartTheSamePatternWithoutCancellingDiscreteOutput() {
        var starts = 0
        repeat(2) {
            loop.start(WearHapticPattern.FRUSTRATION) { starts++ }
        }

        assertEquals(2, starts)
        assertFalse(parent.children.any())
        loop.stop()
        assertEquals(0, outputCancellations)
    }

    @Test fun normalIdleStopDoesNotCancelDiscretePhaseOutput() {
        loop.stop()
        loop.stop()

        assertEquals(0, outputCancellations)
        assertTrue(parent.isActive)
    }

    @Test fun forceStopCancelsOutputEvenWithoutALoop() {
        loop.stop(force = true)
        assertEquals(1, outputCancellations)
        loop.stop()
        assertEquals(1, outputCancellations)
        loop.stop(force = true)
        assertEquals(2, outputCancellations)
    }

    @Test fun stopCancelsOnlyItsJobRunsCleanupAndAllowsRestart() {
        var cleanedUp = false
        loop.start(WearHapticPattern.FRUSTRATION) {
            try {
                awaitCancellation()
            } finally {
                cleanedUp = true
            }
        }
        val original = parent.children.single()
        loop.stop()

        assertTrue(original.isCancelled)
        assertTrue(original.isCompleted)
        assertTrue(cleanedUp)
        assertTrue(parent.isActive)
        assertEquals(1, outputCancellations)
        loop.stop()
        assertEquals(1, outputCancellations)

        var restarted = false
        loop.start(WearHapticPattern.FRUSTRATION) {
            restarted = true
            awaitCancellation()
        }
        assertTrue(restarted)
        assertTrue(parent.children.single().isActive)
        loop.stop(force = true)
        assertEquals(2, outputCancellations)
    }

    private fun startRecording(pattern: WearHapticPattern, events: MutableList<String>) {
        loop.start(pattern) {
            events += "start:$pattern"
            try {
                awaitCancellation()
            } finally {
                events += "stop:$pattern"
            }
        }
    }
}
