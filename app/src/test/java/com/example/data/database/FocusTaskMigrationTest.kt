package com.example.data.database

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusTaskMigrationTest {
    @Test
    fun taskMigrationPreservesSessionRowsAndAddsStableTaskId() {
        val database = SQLiteDatabase.create(null)
        database.execSQL("CREATE TABLE session_logs (id INTEGER PRIMARY KEY, taskName TEXT)")
        database.execSQL("INSERT INTO session_logs(id, taskName) VALUES (1, 'Legacy task')")

        database.execSQL(AppDatabase.ADD_SESSION_TASK_ID)
        database.execSQL(AppDatabase.CREATE_FOCUS_TASKS_TABLE)
        database.execSQL("CREATE INDEX IF NOT EXISTS index_focus_tasks_status ON focus_tasks(status)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_focus_tasks_sortOrder ON focus_tasks(sortOrder)")

        database.rawQuery("SELECT taskName, taskId FROM session_logs WHERE id = 1", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Legacy task", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        database.rawQuery("PRAGMA table_info(focus_tasks)", null).use { cursor ->
            assertEquals(8, cursor.count)
        }
        database.close()
    }
}
