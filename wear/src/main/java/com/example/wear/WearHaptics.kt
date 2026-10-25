package com.example.wear

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class WearHapticPattern(internal val timings: LongArray) {
    NAVIGATION_TICK(longArrayOf(0, 8)),
    TRANSITION(longArrayOf(0, 45, 80, 65)),
    FIVE_MINUTE_WARNING(longArrayOf(0, 35, 110, 35)),
    FRUSTRATION(longArrayOf(0, 70, 130, 70)),
    PANIC_PULSE(longArrayOf(0, 90)),
    SUCCESS(longArrayOf(0, 25, 65, 55)),
    FAILURE(longArrayOf(0, 95, 90, 45)),
    FOCUS_COMPLETE(longArrayOf(0, 35, 80, 35, 200, 100)),
    SHORT_BREAK(longArrayOf(0, 35, 130, 70)),
    LONG_BREAK(longArrayOf(0, 70, 130, 70, 130, 70)),
    RECOVERY(longArrayOf(0, 20, 240, 20))
}

data class WearHapticSettings(val enabled: Boolean, val intensity: Int, val error: String? = null)

class WearHaptics internal constructor(
    preferences: SharedPreferences?,
    private val sink: WearHapticSink,
    private val foreground: () -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        try {
            (context.applicationContext ?: context).getSharedPreferences("wear_haptics", Context.MODE_PRIVATE)
        } catch (_: RuntimeException) {
            null
        },
        AndroidWearHapticSink(context),
        { isForeground(context) }
    )

    private val shared = synchronized(stores) { stores.getOrPut(preferences) { HapticStore(preferences) } }
    val settings: StateFlow<WearHapticSettings> = shared.settings.asStateFlow()
    val isEnabled: Boolean get() = settings.value.enabled
    val intensity: Int get() = settings.value.intensity

    fun setEnabled(enabled: Boolean): Boolean = synchronized(shared) {
        shared.save(settings.value.copy(enabled = enabled, error = null))
    }

    fun setIntensity(value: Int): Boolean = synchronized(shared) {
        shared.save(settings.value.copy(intensity = value.coerceIn(0, 255), error = null))
    }

    fun play(pattern: WearHapticPattern, intensityOverride: Int? = null, eventId: String? = null) {
        synchronized(shared) {
            try {
                val current = settings.value
                if (!current.enabled || !foreground() || !sink.available) return
                // An override can soften feedback, never exceed the user's selected ceiling.
                val amplitude = (intensityOverride ?: current.intensity).coerceIn(0, current.intensity)
                if (amplitude == 0) return
                val now = clock()
                val debounce = if (pattern == WearHapticPattern.NAVIGATION_TICK) 100L else 600L
                val last = shared.lastPlayed[pattern]
                if (last != null && now - last in 0L until debounce) return
                if (eventId != null && pattern != WearHapticPattern.NAVIGATION_TICK &&
                    pattern != WearHapticPattern.PANIC_PULSE && !shared.claim(pattern, eventId, wallClock())
                ) return

                val amplitudeControl = sink.amplitudeControl
                val boundedAmplitude = when (pattern) {
                    WearHapticPattern.NAVIGATION_TICK -> amplitude.coerceAtMost(48)
                    WearHapticPattern.RECOVERY -> amplitude.coerceAtMost(64)
                    else -> amplitude
                }
                if (pattern != WearHapticPattern.NAVIGATION_TICK || !nativeTick(boundedAmplitude, amplitudeControl)) {
                    // Non-scalable motors need short ON/OFF pulses, not nominally "low" amplitudes.
                    val timings = pattern.timings.mapIndexed { index, duration ->
                        if (!amplitudeControl && index % 2 == 1) duration.coerceAtMost(8L) else duration
                    }.toLongArray()
                    val amplitudes = IntArray(timings.size) { index ->
                        if (index % 2 == 0) 0 else if (amplitudeControl) boundedAmplitude else VibrationEffect.DEFAULT_AMPLITUDE
                    }
                    sink.waveform(timings, amplitudes)
                }
                shared.lastPlayed[pattern] = now
            } catch (_: RuntimeException) {
                // Permission changes and vendor vibrator failures must not interrupt the UI.
            }
        }
    }

    private fun nativeTick(amplitude: Int, amplitudeControl: Boolean): Boolean {
        try {
            if (sink.lowTickSupported) {
                sink.lowTick(amplitude / 255f)
                return true
            }
        } catch (_: RuntimeException) {
            // Some vendors reject a primitive despite advertising support.
        }
        if (!amplitudeControl) {
            try {
                if (sink.tickSupported) {
                    sink.tick()
                    return true
                }
            } catch (_: RuntimeException) {
                // Use the short waveform fallback if the native effect fails too.
            }
        }
        return false
    }

    fun cancel() {
        try {
            sink.cancel()
        } catch (_: RuntimeException) {
            // Cancellation is best effort when the service or permission is unavailable.
        }
    }

    companion object {
        private val stores = mutableMapOf<SharedPreferences?, HapticStore>()

        fun transitionPattern(from: String, to: String, running: Boolean): WearHapticPattern = when {
            from == to -> WearHapticPattern.TRANSITION
            to == "LONG_BREAK" -> WearHapticPattern.LONG_BREAK
            from == "FOCUS" && to == "SHORT_BREAK" && !running -> WearHapticPattern.FOCUS_COMPLETE
            to == "SHORT_BREAK" -> WearHapticPattern.SHORT_BREAK
            to == "RECOVERY" -> WearHapticPattern.RECOVERY
            to == "FOCUS" && running -> WearHapticPattern.SUCCESS
            else -> WearHapticPattern.TRANSITION
        }

        internal fun isForeground(context: Context): Boolean {
            var unwrapped = context
            while (unwrapped !is LifecycleOwner && unwrapped is ContextWrapper) {
                val base = unwrapped.baseContext
                if (base === unwrapped) return false
                unwrapped = base
            }
            val owner = unwrapped as? LifecycleOwner ?: return false
            return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
        }
    }
}

private class HapticStore(private val prefs: SharedPreferences?) : SharedPreferences.OnSharedPreferenceChangeListener {
    val settings = MutableStateFlow(WearHapticSettings(enabled = true, intensity = 80))
    val lastPlayed = mutableMapOf<WearHapticPattern, Long>()
    private var writing = false
    private val rejectedValues = mutableMapOf<String, Any>()
    private var events: Map<String, Long>? = null

    init {
        refresh(initial = true)
        try {
            prefs?.registerOnSharedPreferenceChangeListener(this)
        } catch (_: RuntimeException) {
            settings.value = settings.value.copy(enabled = false, error = "Could not observe haptic settings")
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        synchronized(this) {
            if (!writing && (key == null || key == "enabled" || key == "intensity")) refresh()
        }
    }

    private fun refresh(initial: Boolean = false) {
        try {
            checkNotNull(prefs)
            val old = settings.value
            val enabled = prefs.getBoolean("enabled", true)
            val intensity = prefs.getInt("intensity", 80)
            // commit() updates SharedPreferences memory even when the disk write fails.
            val acceptedEnabled = if (rejectedValues["enabled"] == enabled) old.enabled else {
                rejectedValues.remove("enabled")
                enabled
            }
            val acceptedIntensity = if (rejectedValues["intensity"] == intensity) old.intensity else {
                rejectedValues.remove("intensity")
                intensity.coerceIn(0, 255)
            }
            settings.value = WearHapticSettings(acceptedEnabled, acceptedIntensity,
                if (rejectedValues.isEmpty()) null else old.error)
        } catch (_: RuntimeException) {
            settings.value = settings.value.copy(
                enabled = if (initial) false else settings.value.enabled,
                error = "Could not read haptic settings"
            )
        }
    }

    fun save(next: WearHapticSettings): Boolean {
        val old = settings.value
        writing = true
        var saved = false
        try {
            saved = prefs?.edit()?.putBoolean("enabled", next.enabled)?.putInt("intensity", next.intensity)?.commit() == true
        } catch (_: RuntimeException) {
            // Keep the confirmed settings and try to undo any in-memory preference mutation.
        } finally {
            if (!saved) {
                try {
                    prefs?.edit()?.putBoolean("enabled", old.enabled)?.putInt("intensity", old.intensity)?.commit()
                } catch (_: RuntimeException) {
                    // rejectedValues also protects against late callbacks if rollback fails.
                }
            }
            writing = false
        }
        if (saved) {
            rejectedValues.clear()
            settings.value = next
        } else {
            rejectedValues["enabled"] = next.enabled
            rejectedValues["intensity"] = next.intensity
            settings.value = old.copy(error = "Could not save haptic settings")
        }
        return saved
    }

    fun claim(pattern: WearHapticPattern, eventId: String, time: Long): Boolean {
        if (eventId.isBlank() || eventId.length > 256) return false
        return try {
            val previous = events ?: JSONObject(prefs?.getString("played_events", "{}") ?: "{}").let { json ->
                json.keys().asSequence().associateWith { json.getLong(it) }.also { events = it }
            }
            val key = "${pattern.name}:$eventId"
            if (key in previous) return false
            // Bound retained IDs while always keeping the new claim, even after wall-clock changes.
            val next = previous.entries.sortedByDescending { it.value }.take(63).associate { it.toPair() } +
                (key to time.coerceAtLeast(0L))
            val saved = try {
                prefs?.edit()?.putString("played_events", JSONObject(next).toString())?.commit() == true
            } catch (_: RuntimeException) {
                false
            }
            if (!saved) {
                try {
                    prefs?.edit()?.putString("played_events", JSONObject(previous).toString())?.commit()
                } catch (_: RuntimeException) {
                    // Keep only the confirmed ledger in this process if rollback also fails.
                }
                settings.value = settings.value.copy(error = "Could not save haptic event")
                false
            } else {
                events = next
                true
            }
        } catch (_: Exception) {
            settings.value = settings.value.copy(error = "Could not read or save haptic event")
            false
        }
    }
}

internal interface WearHapticSink {
    val available: Boolean
    val amplitudeControl: Boolean
    val lowTickSupported: Boolean
    val tickSupported: Boolean
    fun lowTick(scale: Float)
    fun tick()
    fun waveform(timings: LongArray, amplitudes: IntArray)
    fun cancel()
}

private class AndroidWearHapticSink(context: Context) : WearHapticSink {
    private val vibrator = try {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } catch (_: RuntimeException) {
        null
    }

    override val available: Boolean get() = vibrator?.hasVibrator() == true
    override val amplitudeControl: Boolean get() = vibrator?.hasAmplitudeControl() == true
    override val lowTickSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        vibrator?.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)?.firstOrNull() == true
    override val tickSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        vibrator?.areEffectsSupported(VibrationEffect.EFFECT_TICK)?.firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES

    override fun lowTick(scale: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibrator?.vibrate(VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale).compose())
        }
    }

    override fun tick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    override fun waveform(timings: LongArray, amplitudes: IntArray) {
        vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    override fun cancel() {
        vibrator?.cancel()
    }
}
