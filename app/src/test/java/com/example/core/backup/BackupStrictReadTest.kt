package com.example.core.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.core.goal.DailyFocusGoalStore
import com.example.core.sync.WatchFaceConfigStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupStrictReadTest {
    @Test
    fun backupReadsReportIoFailuresInsteadOfExportingFallbackDefaults() = runBlocking {
        val failing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("read failed") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("write failed")
        }
        val goalStore = DailyFocusGoalStore(failing)
        assertFalse(goalStore.load().enabled)
        try { goalStore.loadForBackup(); fail("Goal backup read should fail") } catch (_: IOException) { }
        val faceStore = WatchFaceConfigStore(failing)
        try { faceStore.snapshotForBackup(); fail("Face backup read should fail") } catch (_: IOException) { }
    }
}
