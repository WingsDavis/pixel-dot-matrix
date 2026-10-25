package com.example.core.sync

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchFaceConfigStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun userPresetCrudPersistsAllFieldsAndCannotChangeBuiltIns() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile("preset-crud") })
        val store = WatchFaceConfigStore(dataStore)
        val config = WatchFaceConfig("#ff42a5f5", "#ff81c784", "#ffef5350", "CUSTOM", false,
            "focus", "daily_target", "timer", "minimal", "local", "applied", 100)
        val saved = store.savePreset("  Work  ", config)
        assertEquals("Work", saved.name)
        assertEquals(config.copy(localRevision = null, appliedRevision = null, updatedAtEpochMs = 0), saved.config)
        val copy = store.duplicatePreset(saved.id, "Copy")!!
        assertTrue(saved.id != copy.id)
        store.renamePreset(copy.id, "Renamed")
        store.deletePreset(saved.id)
        val recreated = WatchFaceConfigStore(dataStore)
        assertEquals(listOf(copy.copy(name = "Renamed")), recreated.presets.value)
        WatchFaceConfigStore.BUILT_IN_PRESETS.forEach { (id, expected) ->
            recreated.renamePreset(id, "Wrong")
            recreated.deletePreset(id)
            assertEquals(expected, recreated.preset(id)!!.config)
        }
        val duplicateBuiltIn = recreated.duplicatePreset("original", "Original copy")!!
        assertTrue(duplicateBuiltIn.id != "original")
        assertEquals(WatchFaceConfig(), duplicateBuiltIn.config)
    }

    @Test fun invalidNamesAndLateAcknowledgementsCannotMutateSavedSettings() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile("preset-invalid") })
        val store = WatchFaceConfigStore(dataStore)
        listOf("  ", "bad\nname", "a".repeat(65)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { store.savePreset(name, WatchFaceConfig()) }
        }
        assertTrue(store.presets.value.isEmpty())
        val latest = WatchFaceConfig(localRevision = "latest", appliedRevision = "older", updatedAtEpochMs = 123)
        store.save(latest)
        store.markApplied("older")
        assertEquals(latest, store.config.value)
        assertEquals(123L, WatchFaceConfigStore(dataStore).config.value.updatedAtEpochMs)
        assertEquals(123L, store.reset().updatedAtEpochMs)
        store.restore(WatchFaceConfig(), emptyList(), emptyList())
        assertEquals(123L, store.config.value.updatedAtEpochMs)
    }

    @Test
    fun fullEditorModelAndIndependentRevisionsSurviveRecreation() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFile("full-model") }
        )
        val expected = WatchFaceConfig(
            hourColor = "#ff42a5f5",
            minuteColor = "#ffffffff",
            secondColor = "#ffef5350",
            customText = "SHIP IT",
            showLogo = false,
            preset = "release",
            leftSlotMode = "phase",
            bottomRightSlotMode = "daily_target",
            ambientStyle = "minimal",
            localRevision = "local-2",
            appliedRevision = "watch-1"
        )

        WatchFaceConfigStore(dataStore).save(expected)
        val restored = WatchFaceConfigStore(dataStore)

        assertEquals(expected, restored.config.value)
        assertEquals(true, restored.hasUnsyncedChanges())
        restored.markApplied("local-2")
        assertEquals(false, restored.hasUnsyncedChanges())
    }

    @Test
    fun resetRestoresEveryBuiltInDefaultAndClearsRevisions() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFile("reset") }
        )
        val store = WatchFaceConfigStore(dataStore)
        store.save(WatchFaceConfig(customText = "CUSTOM", showLogo = false, localRevision = "1", appliedRevision = "0"))

        assertEquals(WatchFaceConfig(), store.reset())
        assertNull(store.config.value.localRevision)
        assertNull(store.config.value.appliedRevision)
    }

    @Test
    fun legacySharedPreferencesMigrateOnFirstRead() = runTest {
        val legacyName = "legacy-watchface-${System.nanoTime()}"
        context.getSharedPreferences(legacyName, Context.MODE_PRIVATE).edit()
            .putString("hour", "#ff39ff14")
            .putString("text", "MIGRATED")
            .putBoolean("show_logo", false)
            .putString("local_revision", "legacy-local")
            .putString("applied_revision", "legacy-watch")
            .commit()
        val dataStore = PreferenceDataStoreFactory.create(
            migrations = listOf(SharedPreferencesMigration(context, legacyName)),
            produceFile = { tempFile("migration") }
        )

        val migrated = WatchFaceConfigStore(dataStore).config.value

        assertEquals("#ff39ff14", migrated.hourColor)
        assertEquals("MIGRATED", migrated.customText)
        assertEquals(false, migrated.showLogo)
        assertEquals("legacy-local", migrated.localRevision)
        assertEquals("legacy-watch", migrated.appliedRevision)
    }

    private fun tempFile(name: String): File =
        File.createTempFile("watchface-$name-", ".preferences_pb").apply { delete(); deleteOnExit() }
}
