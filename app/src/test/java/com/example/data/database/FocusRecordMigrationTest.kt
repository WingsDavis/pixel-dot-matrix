package com.example.data.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusRecordMigrationTest {
    @Test
    fun migrationPreservesLegacyRowsAndPreventsRecoveredDuplicates() {
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("CREATE TABLE session_logs (id INTEGER PRIMARY KEY, taskName TEXT)")
            database.execSQL("INSERT INTO session_logs (id, taskName) VALUES (1, 'First'), (2, 'Second')")
            database.execSQL(AppDatabase.ADD_SESSION_RECORD_ID)
            database.execSQL(AppDatabase.CREATE_SESSION_RECORD_INDEX)
            database.execSQL("INSERT INTO session_logs (id, recordId) VALUES (3, 'focus-1')")
            assertThrows(SQLiteConstraintException::class.java) {
                database.execSQL("INSERT INTO session_logs (id, recordId) VALUES (4, 'focus-1')")
            }
            database.rawQuery("SELECT id FROM session_logs WHERE recordId IS NULL", null).use { assertEquals(2, it.count) }
            database.rawQuery("SELECT id FROM session_logs", null).use { assertEquals(3, it.count) }
        }
    }
}
