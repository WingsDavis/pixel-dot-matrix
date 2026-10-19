package com.example.core.goal

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DailyFocusGoalStoreTest {
    @Test
    fun persistsBothTargetsZoneResetAndDisabledState() = runTest {
        val file = File.createTempFile("daily-focus-goal-", ".preferences_pb").apply { delete() }
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val store = DailyFocusGoalStore(dataStore)
        assertFalse(store.load().enabled)
        val settings = DailyFocusGoalSettings(true, FocusTargetType.SESSIONS, 90, 6, "Africa/Accra", 4)
        store.save(settings)
        assertEquals(settings, DailyFocusGoalStore(dataStore).load())
        store.save(settings.copy(enabled = false))
        assertEquals(settings.copy(enabled = false), store.settings.first())
    }

    @Test
    fun boundsInvalidSettingsBeforeWriting() = runTest {
        val file = File.createTempFile("daily-focus-invalid-", ".preferences_pb").apply { delete() }
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val store = DailyFocusGoalStore(dataStore)
        store.save(DailyFocusGoalSettings(targetMinutes = 0, targetSessions = 1000, resetHour = -1, timeZoneId = "invalid/zone"))
        val restored = store.load()
        assertEquals(1, restored.targetMinutes)
        assertEquals(100, restored.targetSessions)
        assertEquals(0, restored.resetHour)
        assertNotEquals("invalid/zone", restored.timeZoneId)
    }
}
