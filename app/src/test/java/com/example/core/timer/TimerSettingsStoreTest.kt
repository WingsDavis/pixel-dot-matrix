package com.example.core.timer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TimerSettingsStoreTest {
    @Test
    fun defaultsAreSafeAndComplete() = runTest {
        val store = newStore("defaults")

        assertEquals(TimerSettings(), store.loadAndMigrate())
    }

    @Test
    fun individualWritesPreserveUnrelatedPreferences() = runTest {
        val store = newStore("writes")

        store.setFocusDuration(40 * 60)
        store.setLongBreakCadence(6)
        store.setAutoStartBreaks(true)
        store.setTransitionSoundEnabled(false)
        store.setTransitionNotificationEnabled(false)
        store.setTransitionHapticEnabled(false)

        val restored = store.loadAndMigrate()
        assertEquals(40 * 60, restored.focusDurationSeconds)
        assertEquals(6, restored.longBreakCadence)
        assertEquals(TimerSettings.DEFAULT_SHORT_BREAK_SECONDS, restored.shortBreakDurationSeconds)
        assertEquals(true, restored.autoStartBreaks)
        assertFalse(restored.autoStartFocus)
        assertFalse(restored.transitionSoundEnabled)
        assertFalse(restored.transitionNotificationEnabled)
        assertFalse(restored.transitionHapticEnabled)
    }

    @Test
    fun invalidLegacyValuesMigrateToBoundedDefaults() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFile("migration") }
        )
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[androidx.datastore.preferences.core.intPreferencesKey("focus_seconds")] = -1
                this[androidx.datastore.preferences.core.intPreferencesKey("short_break_seconds")] = 99_999
                this[androidx.datastore.preferences.core.intPreferencesKey("long_break_cadence")] = 99
                this[androidx.datastore.preferences.core.booleanPreferencesKey("auto_start_focus")] = true
            }
        }

        val migrated = TimerSettingsStore(dataStore).loadAndMigrate()
        assertEquals(TimerSettings.DEFAULT_FOCUS_SECONDS, migrated.focusDurationSeconds)
        assertEquals(TimerSettings.DEFAULT_SHORT_BREAK_SECONDS, migrated.shortBreakDurationSeconds)
        assertEquals(TimerSettings.DEFAULT_LONG_BREAK_CADENCE, migrated.longBreakCadence)
        assertEquals(true, migrated.autoStartFocus)
        assertEquals(TimerSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(migrated, TimerSettingsStore(dataStore).loadAndMigrate())
    }

    private fun newStore(name: String): TimerSettingsStore {
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile(name) })
        return TimerSettingsStore(dataStore)
    }

    private fun tempFile(name: String): File = File.createTempFile("timer-$name-", ".preferences_pb").apply { delete() }
}
