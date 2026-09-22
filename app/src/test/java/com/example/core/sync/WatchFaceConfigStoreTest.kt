package com.example.core.sync

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchFaceConfigStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

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
        File.createTempFile("watchface-$name-", ".preferences_pb").apply { delete() }
}
