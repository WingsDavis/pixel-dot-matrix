package com.example.wear

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.io.File

internal object ComplicationUpdates {
    private val previous = mutableMapOf<Pair<String, String>, Any>()
    private var lastHeartRateUpdate = Long.MIN_VALUE
    private val timerProviders = listOf(PomodoroProgressComplicationService::class.java,
        CustomTextComplicationService::class.java, BottomRightComplicationService::class.java)
    private val goalProviders = listOf(DailyTargetComplicationService::class.java,
        CustomTextComplicationService::class.java, BottomRightComplicationService::class.java)

    fun timer(context: Context) {
        val values = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE).all
        val running = values["is_running"] == true
        val seconds = (values["seconds_remaining"] as? Int ?: 0).coerceIn(0, 86_400)
        val anchor = (values["timer_updated_at"] as? Long ?: 0L).coerceIn(0L, Long.MAX_VALUE - 86_400_000L)
        val time = if (running) anchor / 1000 + seconds else seconds.toLong()
        val key = listOf(values["state"], running, time, values["focus_duration"],
            values["short_break_duration"], values["long_break_duration"],
            values["long_break_cadence"], values["completed_focus_sessions"])
        requestChanged(context, "timer", key, timerProviders)
    }

    fun goal(context: Context) = requestChanged(context, "goal",
        context.getSharedPreferences("daily_focus_goal", Context.MODE_PRIVATE).all.toMap(), goalProviders)

    fun config(context: Context) = requestChanged(context, "config",
        context.getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE).all
            .filterKeys { it !in setOf("revision", "timestamp", "schema_version") },
        (timerProviders + goalProviders + LogoComplicationService::class.java).distinct())

    fun text(context: Context) = requestChanged(context, "text",
        context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE).getString("custom_text", "").orEmpty(),
        listOf(CustomTextComplicationService::class.java))

    fun logo(context: Context) {
        val file = File(context.filesDir, WearSyncService.LOGO_FILENAME)
        val visible = context.getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE).getBoolean("show_panic_logo", true)
        requestChanged(context, "logo", listOf(visible, WearLogoStore(context).revision(), file.exists(), file.length(), file.lastModified()),
            listOf(LogoComplicationService::class.java))
    }

    @Synchronized
    fun heartRate(context: Context, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (lastHeartRateUpdate != Long.MIN_VALUE && nowElapsedMs - lastHeartRateUpdate < 300_000L) return
        val prefs = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        val value = prefs.getInt("current_hr", 0)
        val sampledAt = prefs.getLong("current_hr_at", 0L)
        if (value <= 0 || sampledAt <= 0L) return
        val fingerprint = value to sampledAt
        if (previous[context.packageName to "heart_rate"] == fingerprint) return
        requestChanged(context, "heart_rate", fingerprint, listOf(CustomTextComplicationService::class.java))
        lastHeartRateUpdate = nowElapsedMs
    }

    @Synchronized
    private fun requestChanged(context: Context, kind: String, fingerprint: Any, providers: List<Class<*>>) {
        val key = context.packageName to kind
        if (previous[key] == fingerprint) return
        providers.forEach { provider ->
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, provider)).requestUpdateAll()
        }
        previous[key] = fingerprint
    }

    internal fun clearForTest() { previous.clear(); lastHeartRateUpdate = Long.MIN_VALUE }
}
