package com.example.core.sync

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class WatchFaceConfigTransactions(
    private val scope: CoroutineScope,
    private val currentConfig: () -> WatchFaceConfig,
    private val saveConfig: (WatchFaceConfig) -> Unit,
    private val markApplied: (String) -> Unit,
    private val publish: suspend (WatchFaceConfig) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val newRevision: () -> String = { UUID.randomUUID().toString() }
) {
    private val lock = Any()
    private var generation = 0L
    private var lastJob: Job? = null
    private var pendingRevision: String? = null
    private val _status = MutableStateFlow(WatchFaceSyncStatus())
    val status = _status.asStateFlow()
    private val _editorConfig = MutableStateFlow(currentConfig())
    val editorConfig: StateFlow<WatchFaceConfig> = _editorConfig.asStateFlow()

    fun applyPreset(id: String, findPreset: (String) -> WatchFacePreset?): Job = synchronized(lock) {
        val selected = try {
            requireNotNull(findPreset(id)) { "Watch face preset no longer exists" }.config.copy(preset = id)
        } catch (error: Exception) {
            return@synchronized apply { throw error }
        }
        apply(previewConfig = selected) { selected }
    }

    fun apply(
        previewConfig: WatchFaceConfig? = null,
        beforePublish: suspend () -> Unit = {},
        selectConfig: () -> WatchFaceConfig
    ): Job = synchronized(lock) {
        val version = ++generation
        pendingRevision = null
        if (previewConfig != null) _editorConfig.value = previewConfig
        _status.value = WatchFaceSyncStatus(
            phase = WatchFaceSyncPhase.SYNCING,
            message = "Preparing watch configuration"
        )
        enqueue {
            var revision: String? = null
            try {
                val snapshot = synchronized(lock) {
                    val previousTimestamp = currentConfig().updatedAtEpochMs
                    val selected = selectConfig()
                    val current = currentConfig()
                    // Selection may refresh restored preferences, including their persisted clock.
                    val storedTimestamp = maxOf(previousTimestamp, current.updatedAtEpochMs)
                    revision = newRevision()
                    selected.copy(
                        localRevision = revision,
                        appliedRevision = current.appliedRevision,
                        updatedAtEpochMs = maxOf(now(), storedTimestamp + 1)
                    ).also {
                        saveConfig(it)
                        if (version == generation) {
                            _editorConfig.value = it
                            pendingRevision = revision
                            _status.value = WatchFaceSyncStatus(
                                phase = WatchFaceSyncPhase.SYNCING,
                                revision = revision,
                                message = "Waiting for watch acknowledgement"
                            )
                        }
                    }
                }
                val sent = withTimeoutOrNull(15_000L) {
                    beforePublish()
                    publish(snapshot)
                    true
                }
                check(sent == true) { "Watch configuration sync timed out after 15 seconds" }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(version, revision, error)
            }
        }
    }

    fun editDraft(update: (WatchFaceConfig) -> WatchFaceConfig): Job = synchronized(lock) {
        saveDraft(update(_editorConfig.value))
    }

    fun saveDraft(config: WatchFaceConfig): Job = synchronized(lock) {
        val version = ++generation
        pendingRevision = null
        _editorConfig.value = config
        _status.value = WatchFaceSyncStatus(message = "Changes not synced")
        enqueue {
            var revision: String? = null
            try {
                synchronized(lock) draft@ {
                    // Check and save under the same lock that invalidates drafts on apply.
                    if (version != generation) return@draft
                    val current = currentConfig()
                    revision = "draft-${newRevision()}"
                    val draft = config.copy(
                        localRevision = revision,
                        appliedRevision = current.appliedRevision,
                        updatedAtEpochMs = maxOf(now(), current.updatedAtEpochMs + 1)
                    )
                    saveConfig(draft)
                    if (version == generation) {
                        _editorConfig.value = draft
                        _status.value = WatchFaceSyncStatus(revision = revision, message = "Changes not synced")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(version, revision, error)
            }
        }
    }

    fun restoreLocal(block: () -> Unit): Deferred<Unit> = synchronized(lock) {
        val version = ++generation
        pendingRevision = null
        _status.value = WatchFaceSyncStatus(message = "Restoring watch preferences")
        val result = CompletableDeferred<Unit>()
        val job = enqueue {
            try {
                synchronized(lock) {
                    block()
                    if (version == generation) {
                        val restored = currentConfig()
                        _editorConfig.value = restored
                        _status.value = WatchFaceSyncStatus(revision = restored.localRevision, message = "Changes not synced")
                    }
                }
                result.complete(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    result.cancel(error)
                    throw error
                }
                result.completeExceptionally(error)
                fail(version, null, error)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) result.cancel(cause)
            else if (cause != null) result.completeExceptionally(cause)
        }
        result
    }

    fun acknowledge(revision: String, success: Boolean, message: String?, appliedAt: Long?) {
        synchronized(lock) {
            if (pendingRevision != revision || currentConfig().localRevision != revision) return
            val version = generation
            try {
                if (success) {
                    markApplied(revision)
                    val current = currentConfig()
                    if (version == generation && current.localRevision == revision && _editorConfig.value.localRevision == revision) {
                        _editorConfig.value = _editorConfig.value.copy(appliedRevision = current.appliedRevision)
                    }
                }
                if (version != generation) return
                _status.value = WatchFaceSyncStatus(
                    phase = if (success) WatchFaceSyncPhase.APPLIED else WatchFaceSyncPhase.FAILED,
                    revision = revision,
                    message = message ?: if (success) "Complications synced" else "Watch rejected configuration",
                    appliedAt = appliedAt
                )
                if (success) pendingRevision = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(version, revision, error)
            }
        }
    }

    private fun enqueue(block: suspend () -> Unit): Job {
        val previous = lastJob
        return scope.launch(dispatcher) {
            previous?.join()
            ensureActive()
            block()
        }.also { lastJob = it }
    }

    private fun fail(version: Long, revision: String?, error: Exception) {
        synchronized(lock) {
            if (version == generation && _status.value.phase != WatchFaceSyncPhase.APPLIED) {
                _status.value = WatchFaceSyncStatus(
                    phase = WatchFaceSyncPhase.FAILED,
                    revision = revision,
                    message = error.message ?: "Unable to save or send configuration"
                )
            }
        }
        onFailure(error)
    }
}
