package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.entity.SessionLogEntity
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SyncOutboxEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SessionLogEntity::class, PanicLogEntity::class, SyncOutboxEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun panicDao(): PanicDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "panic_pomodoro_db"
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE session_logs ADD COLUMN taskName TEXT")
                database.execSQL("ALTER TABLE panic_logs ADD COLUMN taskName TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS sync_outbox (
                        id TEXT NOT NULL PRIMARY KEY,
                        path TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        coalesceKey TEXT,
                        status TEXT NOT NULL,
                        retryCount INTEGER NOT NULL,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_coalesceKey ON sync_outbox(coalesceKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_status ON sync_outbox(status)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE panic_logs ADD COLUMN incidentId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE panic_logs ADD COLUMN incidentType TEXT NOT NULL DEFAULT 'PANIC'")
                database.execSQL("ALTER TABLE panic_logs ADD COLUMN sourceDevice TEXT NOT NULL DEFAULT 'PHONE'")
                database.execSQL("ALTER TABLE panic_logs ADD COLUMN incidentStatus TEXT NOT NULL DEFAULT 'OPEN'")
                database.execSQL("UPDATE panic_logs SET incidentId = 'legacy-' || id")
                database.execSQL("UPDATE panic_logs SET incidentStatus = CASE WHEN resolvedAt IS NULL THEN 'OPEN' ELSE 'PENDING_DETAIL' END")
            }
        }

        const val CREATE_INCIDENT_ID_INDEX = "CREATE UNIQUE INDEX IF NOT EXISTS index_panic_logs_incidentId ON panic_logs(incidentId)"

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(CREATE_INCIDENT_ID_INDEX)
            }
        }
    }
}
