package com.example.core.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WatchFaceConfig(
    val hourColor: String = "#ffff9800",
    val minuteColor: String = "#ffffffff",
    val secondColor: String = "#ffff9800",
    val customText: String = "FOCUS",
    val showLogo: Boolean = true,
    val preset: String = "original",
    val localRevision: String? = null,
    val appliedRevision: String? = null
)

class WatchFaceConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<WatchFaceConfig> = _config.asStateFlow()

    fun save(config: WatchFaceConfig) {
        preferences.edit()
            .putString(HOUR, config.hourColor)
            .putString(MINUTE, config.minuteColor)
            .putString(SECOND, config.secondColor)
            .putString(TEXT, config.customText)
            .putBoolean(SHOW_LOGO, config.showLogo)
            .putString(PRESET, config.preset)
            .putString(LOCAL_REVISION, config.localRevision)
            .putString(APPLIED_REVISION, config.appliedRevision)
            .apply()
        _config.value = config
    }

    fun markApplied(revision: String) = save(_config.value.copy(appliedRevision = revision))

    fun reset(): WatchFaceConfig = WatchFaceConfig().also(::save)

    private fun read() = WatchFaceConfig(
        hourColor = preferences.getString(HOUR, "#ffff9800") ?: "#ffff9800",
        minuteColor = preferences.getString(MINUTE, "#ffffffff") ?: "#ffffffff",
        secondColor = preferences.getString(SECOND, "#ffff9800") ?: "#ffff9800",
        customText = preferences.getString(TEXT, "FOCUS") ?: "FOCUS",
        showLogo = preferences.getBoolean(SHOW_LOGO, true),
        preset = preferences.getString(PRESET, "original") ?: "original",
        localRevision = preferences.getString(LOCAL_REVISION, null),
        appliedRevision = preferences.getString(APPLIED_REVISION, null)
    )

    companion object {
        val BUILT_IN_PRESETS = mapOf(
            "original" to WatchFaceConfig(),
            "terminal" to WatchFaceConfig("#ff39ff14", "#ffffffff", "#ff39ff14", preset = "terminal"),
            "high_contrast" to WatchFaceConfig("#ffffffff", "#ffffffff", "#ffff9800", preset = "high_contrast"),
            "focus" to WatchFaceConfig("#ff42a5f5", "#ffffffff", "#ff42a5f5", preset = "focus")
        )

        private const val PREFS = "phone_watchface_config"
        private const val HOUR = "hour"
        private const val MINUTE = "minute"
        private const val SECOND = "second"
        private const val TEXT = "text"
        private const val SHOW_LOGO = "show_logo"
        private const val PRESET = "preset"
        private const val LOCAL_REVISION = "local_revision"
        private const val APPLIED_REVISION = "applied_revision"
    }
}
