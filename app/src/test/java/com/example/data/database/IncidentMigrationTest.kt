package com.example.data.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.example.data.entity.IncidentStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncidentMigrationTest {
    @Test
    fun uniqueIncidentIndexPreservesRowsAndRejectsDuplicates() {
        val database = SQLiteDatabase.create(null)
        database.execSQL("CREATE TABLE panic_logs (id INTEGER PRIMARY KEY, incidentId TEXT NOT NULL)")
        database.execSQL("INSERT INTO panic_logs(id, incidentId) VALUES (1, 'legacy-1'), (2, 'legacy-2')")
        database.execSQL(AppDatabase.CREATE_INCIDENT_ID_INDEX)

        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL("INSERT INTO panic_logs(id, incidentId) VALUES (3, 'legacy-1')")
        }
        assertTrue(database.rawQuery("SELECT id FROM panic_logs", null).use { it.count == 2 })
    }

    @Test
    fun incidentTerminalStatesCannotReopen() {
        assertTrue(IncidentStatus.canTransition(IncidentStatus.OPEN, IncidentStatus.PENDING_DETAIL))
        assertTrue(IncidentStatus.canTransition(IncidentStatus.PENDING_DETAIL, IncidentStatus.CLOSED))
        assertFalse(IncidentStatus.canTransition(IncidentStatus.CLOSED, IncidentStatus.OPEN))
        assertFalse(IncidentStatus.canTransition(IncidentStatus.DISMISSED, IncidentStatus.PENDING_DETAIL))
    }
}
