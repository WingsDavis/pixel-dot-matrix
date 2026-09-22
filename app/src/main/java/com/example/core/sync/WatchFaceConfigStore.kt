package com.example.core.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class WatchFacePreset(val id: String, val name: String, val config: WatchFaceConfig)

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
    private val _presets = MutableStateFlow(readPresets())
    val presets: StateFlow<List<WatchFacePreset>> = _presets.asStateFlow()
    private val _recentColors = MutableStateFlow(readRecentColors())
    val recentColors: StateFlow<List<String>> = _recentColors.asStateFlow()

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

    fun hasUnsyncedChanges(): Boolean = _config.value.localRevision != null &&
        _config.value.localRevision != _config.value.appliedRevision

    fun reset(): WatchFaceConfig = WatchFaceConfig().also(::save)

    fun preset(id: String): WatchFacePreset? = BUILT_IN_PRESETS[id]?.let { WatchFacePreset(id, id, it) }
        ?: _presets.value.firstOrNull { it.id == id }

    fun savePreset(name: String, config: WatchFaceConfig): WatchFacePreset {
        val preset = WatchFacePreset(java.util.UUID.randomUUID().toString(), name.trim(), config)
        writePresets(_presets.value + preset)
        return preset
    }

    fun duplicatePreset(id: String, name: String): WatchFacePreset? = preset(id)?.let { savePreset(name, it.config) }

    fun renamePreset(id: String, name: String) {
        writePresets(_presets.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun deletePreset(id: String) = writePresets(_presets.value.filterNot { it.id == id })

    fun rememberColor(hex: String) {
        val colors = (listOf(hex) + _recentColors.value).distinct().take(8)
        preferences.edit().putStringSet(RECENT_COLORS, colors.toSet()).apply()
        _recentColors.value = colors
    }

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

    private fun readPresets(): List<WatchFacePreset> = preferences.getStringSet(USER_PRESETS, emptySet()).orEmpty().mapNotNull { raw ->
        runCatching {
            val json = JSONObject(raw)
            WatchFacePreset(
                id = json.getString("id"),
                name = json.getString("name"),
                config = WatchFaceConfig(
                    hourColor = json.getString("hour"),
                    minuteColor = json.getString("minute"),
                    secondColor = json.getString("second"),
                    customText = json.getString("text"),
                    showLogo = json.getBoolean("logo"),
                    preset = json.getString("preset")
                )
            )
        }.getOrNull()
    }

    private fun readRecentColors(): List<String> = preferences.getStringSet(RECENT_COLORS, emptySet()).orEmpty().toList()

    private fun writePresets(presets: List<WatchFacePreset>) {
        preferences.edit().putStringSet(USER_PRESETS, presets.map { preset ->
            JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("hour", preset.config.hourColor)
                put("minute", preset.config.minuteColor)
                put("second", preset.config.secondColor)
                put("text", preset.config.customText)
                put("logo", preset.config.showLogo)
                put("preset", preset.config.preset)
            }.toString()
        }.toSet()).apply()
        _presets.value = presets
    }

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
        private const val USER_PRESETS = "user_presets"
        private const val RECENT_COLORS = "recent_colors"
    }
}
