package com.example.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.protection.DomainProfile
import com.example.core.protection.DomainProfileStore
import com.example.core.sync.WatchFaceConfig
import com.example.core.timer.TimerSettings
import com.example.data.database.AppDatabase
import com.example.data.database.RestoreSettingsEntity
import com.example.data.entity.FocusTaskEntity
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRepositoryTest {
    @Test
    fun mergesWithoutDuplicatesRecoversSettingsAndRollsBackFailedTransactions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = BackupSettings(TimerSettings(focusDurationSeconds = 1800),
            DailyFocusGoalSettings(enabled = true, targetMinutes = 90), WatchFaceConfig(customText = "Restored"),
            emptyList(), listOf("#ffff9800"),
            listOf(DomainProfile(DomainProfileStore.DEFAULT_PROFILE_ID, "Default", setOf("example.com"))),
            DomainProfileStore.DEFAULT_PROFILE_ID, false)
        val legacy = SessionLogEntity(id = 7, sessionType = "FOCUS", startTimeMillis = 1000,
            durationSeconds = 60, status = "COMPLETED", timestamp = 61_000, taskId = "task-a", taskName = "Build")
        val document = BackupDocument(createdAtEpochMs = 100_000, sessions = listOf(legacy),
            incidents = listOf(PanicLogEntity(id = 4, timestamp = 2000, resolvedAt = 3000, incidentId = "incident-a")),
            tasks = listOf(FocusTaskEntity(id = "task-a", title = "Build", sortOrder = 0, createdAt = 1000, updatedAt = 1000)),
            settings = settings)
        withDatabase(context) { database ->
            val repository = BackupRepository(context, database)
            database.sessionDao().insertSession(legacy)
            val first = repository.restore(document)
            assertEquals(0, first.sessionsAdded)
            assertEquals(1, first.incidentsAdded)
            assertEquals(1, first.tasksAdded)
            assertFalse(first.settingsPending)
            val repeated = repository.restore(document)
            assertEquals(BackupRestoreResult(0, 0, 0, false), repeated)
            assertEquals(1, database.sessionDao().snapshot().size)
            assertEquals(settings, repository.snapshot().settings)

            database.restoreSettingsDao().insert(RestoreSettingsEntity(payload = BackupCodec.encode(document.copy(
                sessions = emptyList(), incidents = emptyList(), tasks = emptyList()))))
            assertTrue(BackupRepository(context, database).finishPendingSettings())
            assertFalse(repository.finishPendingSettings())
            assertNull(database.restoreSettingsDao().pending())
            assertEquals(1, database.sessionDao().snapshot().size)

            try { repository.restore(document.copy(schemaVersion = 99)); fail("Newer schema accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, database.sessionDao().snapshot().size)
            assertNull(database.restoreSettingsDao().pending())
        }
        withDatabase(context) { database ->
            database.panicDao().insertPanicLog(document.incidents.single().copy(incidentId = "legacy-1", timestamp = 1000, id = 1))
            val fromAnotherInstallation = document.copy(incidents = listOf(document.incidents.single().copy(
                incidentId = "legacy-1", timestamp = 2000, id = 1)))
            val repository = BackupRepository(context, database)
            assertEquals(1, repository.restore(fromAnotherInstallation).incidentsAdded)
            assertEquals(0, repository.restore(fromAnotherInstallation).incidentsAdded)
            assertEquals(2, database.panicDao().snapshot().size)
        }
        withDatabase(context) { database ->
            val repository = BackupRepository(context, database)
            assertEquals(1, repository.restore(document).sessionsAdded)
            assertEquals(0, repository.restore(document).sessionsAdded)
            assertEquals(BackupCodec.sessionRecordId(legacy), database.sessionDao().snapshot().single().recordId)
        }
        withDatabase(context) { database ->
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_task BEFORE INSERT ON focus_tasks BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            try { BackupRepository(context, database).restore(document); fail("Transaction should fail") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertTrue(database.sessionDao().snapshot().isEmpty())
            assertTrue(database.panicDao().snapshot().isEmpty())
            assertTrue(database.focusTaskDao().snapshot().isEmpty())
            assertNull(database.restoreSettingsDao().pending())
        }
    }

    @Test
    fun additiveJournalMigrationPreservesExistingData() {
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("CREATE TABLE existing_data (value TEXT NOT NULL)")
            database.execSQL("INSERT INTO existing_data VALUES ('preserved')")
            database.execSQL(AppDatabase.CREATE_RESTORE_SETTINGS_TABLE)
            database.rawQuery("SELECT value FROM existing_data", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("preserved", cursor.getString(0))
            }
            database.execSQL("INSERT INTO restore_settings (id, payload) VALUES (1, X'0102')")
            database.rawQuery("SELECT payload FROM restore_settings", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertArrayEquals(byteArrayOf(1, 2), cursor.getBlob(0))
            }
        }
    }

    private fun newDatabase(context: Context) = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries().build()

    private inline fun withDatabase(context: Context, block: (AppDatabase) -> Unit) {
        val database = newDatabase(context)
        try { block(database) } finally { database.close() }
    }
}
