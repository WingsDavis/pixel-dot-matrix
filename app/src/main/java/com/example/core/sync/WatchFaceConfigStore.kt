package com.example.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

data class WatchFacePreset(val id: String, val name: String, val config: WatchFaceConfig)

data class WatchFaceConfig(
    val hourColor: String = "#ffff9800",
    val minuteColor: String = "#ffffffff",
    val secondColor: String = "#ffff9800",
    val customText: String = "FOCUS",
    val showLogo: Boolean = true,
    val preset: String = "original",
    val leftSlotMode: String = "custom_text",
    val bottomRightSlotMode: String = "date",
    val ambientStyle: String = "dim",
    val localRevision: String? = null,
    val appliedRevision: String? = null
)

private val Context.watchFaceConfigDataStore by preferencesDataStore(
    name = "watch_face_config",
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context, LEGACY_PREFS)) }
)

class WatchFaceConfigStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.watchFaceConfigDataStore)

    private val initialPreferences = runBlocking(Dispatchers.IO) { safePreferences().first() }
    private val _config = MutableStateFlow(read(initialPreferences))
    val config: StateFlow<WatchFaceConfig> = _config.asStateFlow()
    private val _presets = MutableStateFlow(readPresets(initialPreferences))
    val presets: StateFlow<List<WatchFacePreset>> = _presets.asStateFlow()
    private val _recentColors = MutableStateFlow(readRecentColors(initialPreferences))
    val recentColors: StateFlow<List<String>> = _recentColors.asStateFlow()

    fun save(config: WatchFaceConfig) {
        runBlocking(Dispatchers.IO) {
            dataStore.edit { preferences ->
                preferences[HOUR] = config.hourColor
                preferences[MINUTE] = config.minuteColor
                preferences[SECOND] = config.secondColor
                preferences[TEXT] = config.customText
                preferences[SHOW_LOGO] = config.showLogo
                preferences[PRESET] = config.preset
                preferences[LEFT_SLOT_MODE] = config.leftSlotMode
                preferences[BOTTOM_RIGHT_SLOT_MODE] = config.bottomRightSlotMode
                preferences[AMBIENT_STYLE] = config.ambientStyle
                config.localRevision?.let { preferences[LOCAL_REVISION] = it } ?: preferences.remove(LOCAL_REVISION)
                config.appliedRevision?.let { preferences[APPLIED_REVISION] = it } ?: preferences.remove(APPLIED_REVISION)
            }
        }
        _config.value = config
    }

    fun markApplied(revision: String) = save(_config.value.copy(appliedRevision = revision))

    fun hasUnsyncedChanges(): Boolean = _config.value.localRevision != null &&
        _config.value.localRevision != _config.value.appliedRevision

    fun reset(): WatchFaceConfig = WatchFaceConfig().also(::save)

    fun preset(id: String): WatchFacePreset? = BUILT_IN_PRESETS[id]?.let { WatchFacePreset(id, id, it) }
        ?: _presets.value.firstOrNull { it.id == id }

    fun savePreset(name: String, config: WatchFaceConfig): WatchFacePreset {
        val preset = WatchFacePreset(java.util.UUID.randomUUID().toString(), name.trim(), config.withoutRevisions())
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
        runBlocking(Dispatchers.IO) { dataStore.edit { it[RECENT_COLORS] = colors.toSet() } }
        _recentColors.value = colors
    }

    private fun safePreferences() = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    private fun read(preferences: Preferences) = WatchFaceConfig(
        hourColor = preferences[HOUR] ?: "#ffff9800",
        minuteColor = preferences[MINUTE] ?: "#ffffffff",
        secondColor = preferences[SECOND] ?: "#ffff9800",
        customText = preferences[TEXT] ?: "FOCUS",
        showLogo = preferences[SHOW_LOGO] ?: true,
        preset = preferences[PRESET] ?: "original",
        leftSlotMode = preferences[LEFT_SLOT_MODE] ?: "custom_text",
        bottomRightSlotMode = preferences[BOTTOM_RIGHT_SLOT_MODE] ?: "date",
        ambientStyle = preferences[AMBIENT_STYLE] ?: "dim",
        localRevision = preferences[LOCAL_REVISION],
        appliedRevision = preferences[APPLIED_REVISION]
    )

    private fun readPresets(preferences: Preferences): List<WatchFacePreset> =
        preferences[USER_PRESETS].orEmpty().mapNotNull(::decodePreset)

    private fun readRecentColors(preferences: Preferences): List<String> = preferences[RECENT_COLORS].orEmpty().toList()

    private fun writePresets(presets: List<WatchFacePreset>) {
        runBlocking(Dispatchers.IO) {
            dataStore.edit { preferences -> preferences[USER_PRESETS] = presets.map(::encodePreset).toSet() }
        }
        _presets.value = presets
    }

    private fun encodePreset(preset: WatchFacePreset) = JSONObject().apply {
        put("id", preset.id)
        put("name", preset.name)
        put("hour", preset.config.hourColor)
        put("minute", preset.config.minuteColor)
        put("second", preset.config.secondColor)
        put("text", preset.config.customText)
        put("logo", preset.config.showLogo)
        put("preset", preset.config.preset)
        put("leftSlotMode", preset.config.leftSlotMode)
        put("bottomRightSlotMode", preset.config.bottomRightSlotMode)
        put("ambientStyle", preset.config.ambientStyle)
    }.toString()

    private fun decodePreset(raw: String): WatchFacePreset? = runCatching {
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
                preset = json.getString("preset"),
                leftSlotMode = json.optString("leftSlotMode", "custom_text"),
                bottomRightSlotMode = json.optString("bottomRightSlotMode", "date"),
                ambientStyle = json.optString("ambientStyle", "dim")
            )
        )
    }.getOrNull()

    private fun WatchFaceConfig.withoutRevisions() = copy(localRevision = null, appliedRevision = null)

    companion object {
        val BUILT_IN_PRESETS = mapOf(
            "original" to WatchFaceConfig(),
            "terminal" to WatchFaceConfig("#ff39ff14", "#ffffffff", "#ff39ff14", preset = "terminal"),
            "high_contrast" to WatchFaceConfig("#ffffffff", "#ffffffff", "#ffff9800", preset = "high_contrast"),
            "focus" to WatchFaceConfig("#ff42a5f5", "#ffffffff", "#ff42a5f5", preset = "focus")
        )

        private val HOUR = stringPreferencesKey("hour")
        private val MINUTE = stringPreferencesKey("minute")
        private val SECOND = stringPreferencesKey("second")
        private val TEXT = stringPreferencesKey("text")
        private val SHOW_LOGO = booleanPreferencesKey("show_logo")
        private val PRESET = stringPreferencesKey("preset")
        private val LEFT_SLOT_MODE = stringPreferencesKey("left_slot_mode")
        private val BOTTOM_RIGHT_SLOT_MODE = stringPreferencesKey("bottom_right_slot_mode")
        private val AMBIENT_STYLE = stringPreferencesKey("ambient_style")
        private val LOCAL_REVISION = stringPreferencesKey("local_revision")
        private val APPLIED_REVISION = stringPreferencesKey("applied_revision")
        private val USER_PRESETS = stringSetPreferencesKey("user_presets")
        private val RECENT_COLORS = stringSetPreferencesKey("recent_colors")
    }
}

private const val LEGACY_PREFS = "phone_watchface_config"
