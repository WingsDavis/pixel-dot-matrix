package com.example.core.backup

import android.content.Context
import androidx.room.withTransaction
import com.example.core.goal.DailyFocusGoalStore
import com.example.core.protection.DomainProfileStore
import com.example.core.sync.WatchFaceConfigStore
import com.example.core.timer.TimerSettingsStore
import com.example.data.database.AppDatabase
import com.example.data.database.RestoreSettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BackupRestoreResult(val sessionsAdded: Int, val incidentsAdded: Int, val tasksAdded: Int, val settingsPending: Boolean)

class BackupRepository(private val context: Context, private val database: AppDatabase) {
    suspend fun snapshot(): BackupDocument = withContext(Dispatchers.IO) { restoreMutex.withLock {
        check(database.restoreSettingsDao().pending() == null) { "Finish the pending settings restore before exporting" }
        val faceStore = WatchFaceConfigStore(context)
        val face = faceStore.snapshotForBackup()
        val domains = DomainProfileStore(context)
        val settings = BackupSettings(
            timer = TimerSettingsStore(context).loadAndMigrate(),
            dailyGoal = DailyFocusGoalStore(context).loadForBackup(),
            watchFace = face.config.copy(localRevision = null, appliedRevision = null),
            presets = face.presets.map { it.copy(config = it.config.copy(localRevision = null, appliedRevision = null)) },
            recentColors = face.recentColors,
            domainProfiles = domains.profiles(),
            activeProfileId = domains.activeProfileId(),
            autoFocusProtection = domains.autoActivateDuringFocus()
        )
        database.withTransaction {
            BackupDocument(createdAtEpochMs = System.currentTimeMillis(), sessions = database.sessionDao().snapshot(),
                incidents = database.panicDao().snapshot(), tasks = database.focusTaskDao().snapshot(), settings = settings)
        }
    } }

    suspend fun restore(document: BackupDocument): BackupRestoreResult = withContext(Dispatchers.IO) { restoreMutex.withLock {
        val validated = BackupCodec.decode(BackupCodec.encode(document))
        val settingsPayload = BackupCodec.encode(validated.copy(sessions = emptyList(), incidents = emptyList(), tasks = emptyList()))
        var sessionsAdded = 0
        var incidentsAdded = 0
        var tasksAdded = 0
        database.withTransaction {
            check(database.restoreSettingsDao().pending() == null) { "Finish the pending settings restore first" }
            val existing = database.sessionDao().snapshot().map(BackupCodec::sessionRecordId).toHashSet()
            validated.sessions.forEach { row ->
                val recordId = BackupCodec.sessionRecordId(row)
                if (existing.add(recordId) && database.sessionDao().insertSessionIfAbsent(row.copy(id = 0, recordId = recordId)) != -1L) sessionsAdded++
            }
            val existingIncidents = database.panicDao().snapshot().map(BackupCodec::incidentRecordId).toHashSet()
            validated.incidents.forEach { row ->
                val incidentId = BackupCodec.incidentRecordId(row)
                if (existingIncidents.add(incidentId) && database.panicDao().insertIncidentIfAbsent(row.copy(id = 0, incidentId = incidentId)) != -1L) incidentsAdded++
            }
            validated.tasks.forEach { if (database.focusTaskDao().insertIfAbsent(it) != -1L) tasksAdded++ }
            database.restoreSettingsDao().insert(RestoreSettingsEntity(payload = settingsPayload))
        }
        val pending = try { applyPendingSettings(); false } catch (error: Exception) {
            if (error is CancellationException) throw error
            true
        }
        BackupRestoreResult(sessionsAdded, incidentsAdded, tasksAdded, pending)
    } }

    suspend fun finishPendingSettings(): Boolean = withContext(Dispatchers.IO) {
        restoreMutex.withLock { applyPendingSettings() }
    }

    private suspend fun applyPendingSettings(): Boolean {
        val pending = database.restoreSettingsDao().pending() ?: return false
        val settings = BackupCodec.decode(pending.payload).settings
        TimerSettingsStore(context).write(settings.timer)
        DailyFocusGoalStore(context).save(settings.dailyGoal)
        WatchFaceConfigStore(context).restore(settings.watchFace, settings.presets, settings.recentColors)
        DomainProfileStore(context).restore(settings.domainProfiles, settings.activeProfileId, settings.autoFocusProtection)
        database.restoreSettingsDao().clear()
        return true
    }

    private companion object { val restoreMutex = Mutex() }
}
