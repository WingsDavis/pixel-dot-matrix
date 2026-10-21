package com.example.core.goal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import java.io.IOException

private val Context.dailyFocusGoalDataStore by preferencesDataStore(name = "daily_focus_goal")

class DailyFocusGoalStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dailyFocusGoalDataStore)

    val settings = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map(::fromPreferences)

    private fun fromPreferences(preferences: Preferences) = DailyFocusGoalSettings(
            enabled = preferences[ENABLED] ?: false,
            type = runCatching { FocusTargetType.valueOf(preferences[TYPE].orEmpty()) }.getOrDefault(FocusTargetType.MINUTES),
            targetMinutes = preferences[MINUTES] ?: 120,
            targetSessions = preferences[SESSIONS] ?: 4,
            timeZoneId = preferences[ZONE] ?: java.util.TimeZone.getDefault().id,
            resetHour = preferences[RESET_HOUR] ?: 0
        ).sanitized()

    suspend fun load(): DailyFocusGoalSettings = settings.first()
    suspend fun loadForBackup(): DailyFocusGoalSettings = fromPreferences(dataStore.data.first())

    suspend fun save(settings: DailyFocusGoalSettings) {
        val safe = settings.sanitized()
        dataStore.edit { preferences ->
            preferences[ENABLED] = safe.enabled
            preferences[TYPE] = safe.type.name
            preferences[MINUTES] = safe.targetMinutes
            preferences[SESSIONS] = safe.targetSessions
            preferences[ZONE] = safe.timeZoneId
            preferences[RESET_HOUR] = safe.resetHour
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val TYPE = stringPreferencesKey("type")
        val MINUTES = intPreferencesKey("target_minutes")
        val SESSIONS = intPreferencesKey("target_sessions")
        val ZONE = stringPreferencesKey("time_zone")
        val RESET_HOUR = intPreferencesKey("reset_hour")
    }
}
