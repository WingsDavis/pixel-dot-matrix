package com.example.wear

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

enum class WearHapticPattern { NAVIGATION_TICK, TRANSITION, FIVE_MINUTE_WARNING, FRUSTRATION, PANIC_PULSE, SUCCESS, FAILURE }

class WearHaptics(context: Context) {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val prefs = context.getSharedPreferences("wear_haptics", Context.MODE_PRIVATE)
    private val lastPlayed = mutableMapOf<WearHapticPattern, Long>()

    fun play(pattern: WearHapticPattern, intensityOverride: Int? = null) {
        if (!prefs.getBoolean("enabled", true) || !vibrator.hasVibrator()) return
        val now = SystemClock.elapsedRealtime()
        val debounceMs = if (pattern == WearHapticPattern.NAVIGATION_TICK) 100L else 600L
        if (now - (lastPlayed[pattern] ?: 0L) < debounceMs) return
        lastPlayed[pattern] = now
        val intensity = (intensityOverride ?: prefs.getInt("intensity", 160)).coerceIn(1, 255)
        val timings = when (pattern) {
            WearHapticPattern.NAVIGATION_TICK -> longArrayOf(0, 18)
            WearHapticPattern.TRANSITION -> longArrayOf(0, 80, 50, 120)
            WearHapticPattern.FIVE_MINUTE_WARNING -> longArrayOf(0, 55, 90, 55)
            WearHapticPattern.FRUSTRATION -> longArrayOf(0, 150, 100, 150)
            WearHapticPattern.PANIC_PULSE -> longArrayOf(0, 220)
            WearHapticPattern.SUCCESS -> longArrayOf(0, 45, 45, 90)
            WearHapticPattern.FAILURE -> longArrayOf(0, 180, 70, 180)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, IntArray(timings.size) { if (it % 2 == 0) 0 else intensity }, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    fun cancel() = vibrator.cancel()
}
