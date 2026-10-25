package com.example.wear

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.view.ContextThemeWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearHapticsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: SharedPreferences
    private lateinit var sink: RecordingSink
    private lateinit var haptics: WearHaptics
    private var now = 0L
    private var foreground = true

    @Before fun setUp() {
        prefs = context.getSharedPreferences("wear_haptics_test_${UUID.randomUUID()}", Context.MODE_PRIVATE)
        sink = RecordingSink()
        haptics = dispatcher()
    }

    @Test fun namedPatternsHaveDistinctRhythms() {
        val patterns = listOf(WearHapticPattern.FOCUS_COMPLETE, WearHapticPattern.SHORT_BREAK,
            WearHapticPattern.LONG_BREAK, WearHapticPattern.RECOVERY)
        patterns.forEach { haptics.play(it) }
        assertEquals(4, sink.waveforms.map { it.timings }.toSet().size)
        sink.waveforms.forEach { waveform ->
            assertEquals(waveform.timings.size, waveform.amplitudes.size)
            assertEquals(0, waveform.amplitudes.first())
            assertTrue(waveform.amplitudes.all { it in 0..80 })
        }
    }

    @Test fun normalPausedAndAutomaticTransitionsUseDistinctCues() {
        assertEquals(WearHapticPattern.FOCUS_COMPLETE, WearHaptics.transitionPattern("FOCUS", "SHORT_BREAK", false))
        assertEquals(WearHapticPattern.SHORT_BREAK, WearHaptics.transitionPattern("FOCUS", "SHORT_BREAK", true))
        assertEquals(WearHapticPattern.LONG_BREAK, WearHaptics.transitionPattern("FOCUS", "LONG_BREAK", false))
        assertEquals(WearHapticPattern.LONG_BREAK, WearHaptics.transitionPattern("FOCUS", "LONG_BREAK", true))
        assertEquals(WearHapticPattern.SHORT_BREAK, WearHaptics.transitionPattern("PANIC_MODE", "SHORT_BREAK", false))
        assertEquals(WearHapticPattern.RECOVERY, WearHaptics.transitionPattern("PANIC_MODE", "RECOVERY", true))
        assertEquals(WearHapticPattern.SUCCESS, WearHaptics.transitionPattern("SHORT_BREAK", "FOCUS", true))
        assertEquals(WearHapticPattern.TRANSITION, WearHaptics.transitionPattern("FOCUS", "FOCUS", true))
    }

    @Test fun disabledOrZeroIntensitySuppressesEveryPatternIncludingRecoveryAndOverrides() {
        assertTrue(haptics.setEnabled(false))
        WearHapticPattern.entries.forEach { haptics.play(it, 255, "disabled") }
        assertTrue(sink.waveforms.isEmpty())
        assertFalse(prefs.contains("played_events"))
        assertTrue(haptics.setEnabled(true))
        assertTrue(haptics.setIntensity(0))
        WearHapticPattern.entries.forEach { haptics.play(it, 255) }
        assertTrue(sink.waveforms.isEmpty())
        assertTrue(sink.lowTicks.isEmpty())
        assertEquals(0, sink.ticks)
    }

    @Test fun overridesAreBoundedByCurrentIntensityAndCannotTurnZeroIntoAPulse() {
        assertTrue(haptics.setIntensity(64))
        haptics.play(WearHapticPattern.SUCCESS, Int.MAX_VALUE)
        assertEquals(64, sink.waveforms.last().amplitudes.max())
        now += 600
        haptics.play(WearHapticPattern.SUCCESS, Int.MIN_VALUE)
        assertEquals(1, sink.waveforms.size)
        haptics.play(WearHapticPattern.SUCCESS, 16)
        assertEquals(16, sink.waveforms.last().amplitudes.max())
        assertTrue(haptics.setIntensity(Int.MAX_VALUE))
        assertEquals(255, haptics.intensity)
        assertTrue(haptics.setIntensity(Int.MIN_VALUE))
        assertEquals(0, haptics.intensity)
        now += 600
        haptics.play(WearHapticPattern.SUCCESS, 255)
        assertEquals(2, sink.waveforms.size)
    }

    @Test fun defaultIsSubtleButExistingValuesArePreservedAndCorruptBoundsAreSanitized() {
        assertEquals(80, haptics.intensity)
        prefs.edit().putInt("intensity", 160).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(160, haptics.intensity)
        assertEquals(160, dispatcher().intensity)
        assertEquals(160, dispatcher(object : SharedPreferences by prefs {}).intensity)
        prefs.edit().putInt("intensity", 999).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(255, haptics.intensity)
        prefs.edit().putInt("intensity", -12).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, haptics.intensity)
    }

    @Test fun settingsFlowTracksOtherInstancesExternalPreferencesAndRecreation() {
        val other = dispatcher()
        val observed = mutableListOf<WearHapticSettings>()
        val collection = CoroutineScope(Dispatchers.Unconfined).launch {
            other.settings.collect { observed += it }
        }
        try {
            assertTrue(haptics.setIntensity(112))
            assertEquals(112, other.settings.value.intensity)
            prefs.edit().putBoolean("enabled", false).putInt("intensity", 144).commit()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WearHapticSettings(false, 144), observed.last())
            assertEquals(other.settings.value, dispatcher().settings.value)
            val reloaded = dispatcher(object : SharedPreferences by prefs {})
            assertEquals(other.settings.value, reloaded.settings.value)
        } finally {
            collection.cancel()
        }
    }

    @Test fun failedCommitRestoresRawMemoryAndKeepsConfirmedSettingsAcrossInstances() {
        val faulty = FaultyPreferences(prefs).also { it.failCommits = true }
        val first = dispatcher(faulty)
        val other = dispatcher(faulty)
        assertFalse(first.setIntensity(200))
        assertEquals(80, prefs.getInt("intensity", -1))
        assertTrue(prefs.getBoolean("enabled", false))
        assertFalse(first.setEnabled(false))
        assertTrue(prefs.getBoolean("enabled", false))
        assertEquals(80, prefs.getInt("intensity", -1))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(other.isEnabled)
        assertEquals(80, dispatcher(faulty).intensity)
        assertNotNull(other.settings.value.error)
        // An unrelated successful edit must not flush the failed candidate later.
        prefs.edit().putString("unrelated", "saved").commit()
        assertTrue(prefs.getBoolean("enabled", false))
        assertEquals(80, prefs.getInt("intensity", -1))
        faulty.failCommits = false
        assertTrue(first.setIntensity(96))
        assertEquals(WearHapticSettings(true, 96), other.settings.value)
    }

    @Test fun failedRollbackAndLateCallbacksCannotPublishUncommittedSettings() {
        val faulty = FaultyPreferences(prefs).also {
            it.failCommits = true
            it.throwAfterFirstCommit = true
        }
        val first = dispatcher(faulty)
        assertFalse(first.setEnabled(false))
        assertFalse(prefs.getBoolean("enabled", true))
        prefs.edit().putInt("intensity", 120).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(first.isEnabled)
        assertEquals(120, first.intensity)
        assertNotNull(first.settings.value.error)
        assertTrue(dispatcher(faulty).isEnabled)
    }

    @Test fun readAndWriteFailuresAreReportedWithoutCrashing() {
        val faulty = FaultyPreferences(prefs)
        val first = dispatcher(faulty)
        faulty.writeFailure = SecurityException("preferences unavailable")
        assertFalse(first.setEnabled(false))
        assertTrue(first.isEnabled)
        assertNotNull(first.settings.value.error)
        val unreadable = FaultyPreferences(prefs).also { it.readFailure = ClassCastException("bad setting") }
        val failed = dispatcher(unreadable)
        assertFalse(failed.isEnabled)
        assertNotNull(failed.settings.value.error)
        failed.play(WearHapticPattern.RECOVERY)
        assertTrue(sink.waveforms.isEmpty())
    }

    @Test fun debounceIsSharedAndTheFirstTickAtZeroIsNotLost() {
        val other = dispatcher()
        haptics.play(WearHapticPattern.NAVIGATION_TICK)
        other.play(WearHapticPattern.NAVIGATION_TICK)
        now = 99
        dispatcher().play(WearHapticPattern.NAVIGATION_TICK)
        assertEquals(1, sink.waveforms.size)
        now = 100
        other.play(WearHapticPattern.NAVIGATION_TICK)
        haptics.play(WearHapticPattern.SUCCESS)
        other.play(WearHapticPattern.SUCCESS)
        assertEquals(3, sink.waveforms.size)
        now = 700
        other.play(WearHapticPattern.SUCCESS)
        assertEquals(4, sink.waveforms.size)
    }

    @Test fun discreteEventIdsAndTimesPreventReplayAfterReload() {
        haptics.play(WearHapticPattern.FOCUS_COMPLETE, eventId = "session-1")
        val stored = JSONObject(prefs.getString("played_events", "{}")!!)
        assertEquals(1_000_000L, stored.getLong("FOCUS_COMPLETE:session-1"))
        now = 5_000
        dispatcher().play(WearHapticPattern.FOCUS_COMPLETE, eventId = "session-1")
        val reloaded = dispatcher(object : SharedPreferences by prefs {})
        reloaded.play(WearHapticPattern.FOCUS_COMPLETE, eventId = "session-1")
        assertEquals(1, sink.waveforms.size)
        reloaded.play(WearHapticPattern.FOCUS_COMPLETE, eventId = "session-2")
        assertEquals(2, sink.waveforms.size)
    }

    @Test fun navigationPanicAndUnidentifiedLoopsNeverWriteAnEventLedger() {
        val tracked = FaultyPreferences(prefs)
        val first = dispatcher(tracked)
        repeat(5) {
            now += 1_000
            first.play(WearHapticPattern.NAVIGATION_TICK, eventId = "tick-$it")
            first.play(WearHapticPattern.PANIC_PULSE, eventId = "pulse-$it")
            first.play(WearHapticPattern.RECOVERY)
            first.play(WearHapticPattern.FRUSTRATION)
        }
        assertEquals(20, sink.waveforms.size)
        assertEquals(0, tracked.commits)
        assertFalse(prefs.contains("played_events"))
    }

    @Test fun failedEventPersistenceFailsClosedAndCanBeRetried() {
        val faulty = FaultyPreferences(prefs).also { it.failCommits = true }
        val first = dispatcher(faulty)
        first.play(WearHapticPattern.SUCCESS, eventId = "event")
        assertTrue(sink.waveforms.isEmpty())
        assertNotNull(first.settings.value.error)
        assertEquals(0, JSONObject(prefs.getString("played_events", "{}")!!).length())
        faulty.failCommits = false
        dispatcher(object : SharedPreferences by prefs {}).play(WearHapticPattern.SUCCESS, eventId = "event")
        assertEquals(1, sink.waveforms.size)
    }

    @Test fun eventLedgerIsBounded() {
        repeat(70) {
            now += 600
            haptics.play(WearHapticPattern.SUCCESS, eventId = "event-$it")
        }
        assertEquals(70, sink.waveforms.size)
        assertEquals(64, JSONObject(prefs.getString("played_events", "{}")!!).length())
    }

    @Test fun backgroundSuppressionDoesNotConsumeAnAlert() {
        foreground = false
        haptics.play(WearHapticPattern.RECOVERY)
        haptics.play(WearHapticPattern.FOCUS_COMPLETE, eventId = "background-event")
        assertTrue(sink.waveforms.isEmpty())
        assertFalse(prefs.contains("played_events"))
        foreground = true
        haptics.play(WearHapticPattern.FOCUS_COMPLETE, eventId = "background-event")
        assertEquals(1, sink.waveforms.size)
    }

    @Test fun foregroundRequiresAnUnwrappedResumedOwnerAndInteractiveDisplay() {
        val owner = OwnerContext(context)
        val wrapped = ContextThemeWrapper(ContextWrapper(owner), android.R.style.Theme_DeviceDefault)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(true)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(WearHaptics.isForeground(wrapped))
        assertFalse(WearHaptics.isForeground(context))
        val guarded = WearHaptics(prefs, sink, { WearHaptics.isForeground(wrapped) }, { now })
        guarded.play(WearHapticPattern.RECOVERY)
        assertEquals(1, sink.waveforms.size)
        now += 600
        owner.registry.currentState = Lifecycle.State.STARTED
        guarded.play(WearHapticPattern.RECOVERY)
        assertEquals(1, sink.waveforms.size)
        owner.registry.currentState = Lifecycle.State.RESUMED
        shadowOf(power).setIsInteractive(false)
        guarded.play(WearHapticPattern.RECOVERY)
        assertEquals(1, sink.waveforms.size)
    }

    @Test fun missingOrThrowingVibratorAndCancellationNeverCrash() {
        sink.available = false
        haptics.play(WearHapticPattern.RECOVERY, eventId = "missing")
        assertFalse(prefs.contains("played_events"))
        sink.available = true
        listOf(SecurityException("permission revoked"), IllegalStateException("vendor failure")).forEach {
            sink.failure = it
            haptics.play(WearHapticPattern.RECOVERY)
            haptics.cancel()
        }
        assertTrue(sink.waveforms.isEmpty())
        sink.failure = null
        haptics.play(WearHapticPattern.RECOVERY)
        haptics.cancel()
        assertEquals(1, sink.waveforms.size)
        assertEquals(1, sink.cancellations)
    }

    @Test fun supportedLowTickUsesBoundedScaleAndRejectedPrimitiveFallsBack() {
        sink.lowTickSupported = true
        haptics.play(WearHapticPattern.NAVIGATION_TICK, 16)
        assertEquals(16 / 255f, sink.lowTicks.single(), 0.0001f)
        assertTrue(sink.waveforms.isEmpty())
        now += 100
        sink.lowTickFailure = IllegalArgumentException("unsupported primitive")
        haptics.play(WearHapticPattern.NAVIGATION_TICK, 255)
        assertEquals(listOf(0L, 8L), sink.waveforms.single().timings)
        assertEquals(48, sink.waveforms.single().amplitudes.max())
    }

    @Test fun nonScalableMotorUsesNativeTickOrShortExplicitOnOffFallback() {
        sink.amplitudeControl = false
        sink.tickSupported = true
        haptics.play(WearHapticPattern.NAVIGATION_TICK)
        assertEquals(1, sink.ticks)
        assertTrue(sink.waveforms.isEmpty())
        now += 100
        sink.tickFailure = IllegalStateException("native tick failed")
        haptics.play(WearHapticPattern.NAVIGATION_TICK)
        haptics.play(WearHapticPattern.LONG_BREAK)
        sink.waveforms.forEach { waveform ->
            assertTrue(waveform.amplitudes.all { it == 0 || it == VibrationEffect.DEFAULT_AMPLITUDE })
            assertTrue(waveform.timings.filterIndexed { index, _ -> index % 2 == 1 }.all { it <= 8 })
        }
        assertEquals(2, sink.waveforms.size)
    }

    private fun dispatcher(preferences: SharedPreferences = prefs): WearHaptics =
        WearHaptics(preferences, sink, { foreground }, { now }, { 1_000_000L + now })

    private class OwnerContext(base: Context) : ContextWrapper(base), LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private data class Waveform(val timings: List<Long>, val amplitudes: List<Int>)

    private class RecordingSink : WearHapticSink {
        override var available = true
        override var amplitudeControl = true
        override var lowTickSupported = false
        override var tickSupported = false
        val waveforms = mutableListOf<Waveform>()
        val lowTicks = mutableListOf<Float>()
        var ticks = 0
        var cancellations = 0
        var failure: RuntimeException? = null
        var lowTickFailure: RuntimeException? = null
        var tickFailure: RuntimeException? = null

        override fun lowTick(scale: Float) {
            lowTickFailure?.let { throw it }
            failure?.let { throw it }
            lowTicks += scale
        }
        override fun tick() {
            tickFailure?.let { throw it }
            failure?.let { throw it }
            ticks++
        }
        override fun waveform(timings: LongArray, amplitudes: IntArray) {
            failure?.let { throw it }
            waveforms += Waveform(timings.toList(), amplitudes.toList())
        }
        override fun cancel() {
            failure?.let { throw it }
            cancellations++
        }
    }

    private class FaultyPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failCommits = false
        var throwAfterFirstCommit = false
        var writeFailure: RuntimeException? = null
        var readFailure: RuntimeException? = null
        var commits = 0

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            readFailure?.let { throw it }
            return delegate.getBoolean(key, defValue)
        }
        override fun getInt(key: String?, defValue: Int): Int {
            readFailure?.let { throw it }
            return delegate.getInt(key, defValue)
        }
        override fun edit(): SharedPreferences.Editor {
            writeFailure?.let { throw it }
            if (throwAfterFirstCommit && commits > 0) throw IllegalStateException("rollback failed")
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                    editor.putBoolean(key, value)
                    return this
                }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                    editor.putInt(key, value)
                    return this
                }
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun commit(): Boolean {
                    commits++
                    // Model memory being updated before commit reports a disk failure.
                    val committed = editor.commit()
                    return committed && !failCommits
                }
            }
        }
    }
}
