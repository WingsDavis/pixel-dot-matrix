package com.example.core.timer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class TimerSettings(
    val focusDurationSeconds: Int = DEFAULT_FOCUS_SECONDS,
    val shortBreakDurationSeconds: Int = DEFAULT_SHORT_BREAK_SECONDS,
    val longBreakDurationSeconds: Int = DEFAULT_LONG_BREAK_SECONDS,
    val longBreakCadence: Int = DEFAULT_LONG_BREAK_CADENCE,
    val autoStartBreaks: Boolean = false,
    val autoStartFocus: Boolean = false,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    fun sanitized() = copy(
        focusDurationSeconds = focusDurationSeconds.takeIf { it in FOCUS_RANGE } ?: DEFAULT_FOCUS_SECONDS,
        shortBreakDurationSeconds = shortBreakDurationSeconds.takeIf { it in SHORT_BREAK_RANGE } ?: DEFAULT_SHORT_BREAK_SECONDS,
        longBreakDurationSeconds = longBreakDurationSeconds.takeIf { it in LONG_BREAK_RANGE } ?: DEFAULT_LONG_BREAK_SECONDS,
        longBreakCadence = longBreakCadence.takeIf { it in CADENCE_RANGE } ?: DEFAULT_LONG_BREAK_CADENCE,
        schemaVersion = CURRENT_SCHEMA_VERSION
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_FOCUS_SECONDS = 25 * 60
        const val DEFAULT_SHORT_BREAK_SECONDS = 5 * 60
        const val DEFAULT_LONG_BREAK_SECONDS = 15 * 60
        const val DEFAULT_LONG_BREAK_CADENCE = 4

        val FOCUS_RANGE = 60..(60 * 60)
        val SHORT_BREAK_RANGE = 60..(25 * 60)
        val LONG_BREAK_RANGE = (5 * 60)..(45 * 60)
        val CADENCE_RANGE = 2..8
    }
}

private val Context.timerSettingsDataStore by preferencesDataStore(name = "timer_settings")

class TimerSettingsStore(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.timerSettingsDataStore)

    val settings: Flow<TimerSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map(::fromPreferences)

    suspend fun loadAndMigrate(): TimerSettings {
        val preferences = dataStore.data.first()
        val settings = fromPreferences(preferences)
        if (preferences[SCHEMA_VERSION] != TimerSettings.CURRENT_SCHEMA_VERSION || hasInvalidValues(preferences)) {
            write(settings)
        }
        return settings
    }

    suspend fun setFocusDuration(seconds: Int) = update(FOCUS_SECONDS, seconds)
    suspend fun setShortBreakDuration(seconds: Int) = update(SHORT_BREAK_SECONDS, seconds)
    suspend fun setLongBreakDuration(seconds: Int) = update(LONG_BREAK_SECONDS, seconds)
    suspend fun setLongBreakCadence(cadence: Int) = update(LONG_BREAK_CADENCE, cadence)
    suspend fun setAutoStartBreaks(enabled: Boolean) = update(AUTO_START_BREAKS, enabled)
    suspend fun setAutoStartFocus(enabled: Boolean) = update(AUTO_START_FOCUS, enabled)

    private suspend fun update(key: Preferences.Key<Int>, value: Int) {
        dataStore.edit { preferences ->
            preferences[key] = value
            preferences[SCHEMA_VERSION] = TimerSettings.CURRENT_SCHEMA_VERSION
        }
    }

    private suspend fun update(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { preferences ->
            preferences[key] = value
            preferences[SCHEMA_VERSION] = TimerSettings.CURRENT_SCHEMA_VERSION
        }
    }

    suspend fun write(settings: TimerSettings) {
        dataStore.edit { preferences ->
            preferences[FOCUS_SECONDS] = settings.focusDurationSeconds
            preferences[SHORT_BREAK_SECONDS] = settings.shortBreakDurationSeconds
            preferences[LONG_BREAK_SECONDS] = settings.longBreakDurationSeconds
            preferences[LONG_BREAK_CADENCE] = settings.longBreakCadence
            preferences[AUTO_START_BREAKS] = settings.autoStartBreaks
            preferences[AUTO_START_FOCUS] = settings.autoStartFocus
            preferences[SCHEMA_VERSION] = TimerSettings.CURRENT_SCHEMA_VERSION
        }
    }

    private fun fromPreferences(preferences: Preferences) = TimerSettings(
        focusDurationSeconds = preferences[FOCUS_SECONDS] ?: TimerSettings.DEFAULT_FOCUS_SECONDS,
        shortBreakDurationSeconds = preferences[SHORT_BREAK_SECONDS] ?: TimerSettings.DEFAULT_SHORT_BREAK_SECONDS,
        longBreakDurationSeconds = preferences[LONG_BREAK_SECONDS] ?: TimerSettings.DEFAULT_LONG_BREAK_SECONDS,
        longBreakCadence = preferences[LONG_BREAK_CADENCE] ?: TimerSettings.DEFAULT_LONG_BREAK_CADENCE,
        autoStartBreaks = preferences[AUTO_START_BREAKS] ?: false,
        autoStartFocus = preferences[AUTO_START_FOCUS] ?: false,
        schemaVersion = preferences[SCHEMA_VERSION] ?: 0
    ).sanitized()

    private fun hasInvalidValues(preferences: Preferences): Boolean =
        preferences[FOCUS_SECONDS]?.let { it !in TimerSettings.FOCUS_RANGE } == true ||
            preferences[SHORT_BREAK_SECONDS]?.let { it !in TimerSettings.SHORT_BREAK_RANGE } == true ||
            preferences[LONG_BREAK_SECONDS]?.let { it !in TimerSettings.LONG_BREAK_RANGE } == true ||
            preferences[LONG_BREAK_CADENCE]?.let { it !in TimerSettings.CADENCE_RANGE } == true

    private companion object {
        val SCHEMA_VERSION = intPreferencesKey("schema_version")
        val FOCUS_SECONDS = intPreferencesKey("focus_seconds")
        val SHORT_BREAK_SECONDS = intPreferencesKey("short_break_seconds")
        val LONG_BREAK_SECONDS = intPreferencesKey("long_break_seconds")
        val LONG_BREAK_CADENCE = intPreferencesKey("long_break_cadence")
        val AUTO_START_BREAKS = booleanPreferencesKey("auto_start_breaks")
        val AUTO_START_FOCUS = booleanPreferencesKey("auto_start_focus")
    }
}
