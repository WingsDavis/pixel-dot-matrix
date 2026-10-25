package com.example.core.sync

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchFaceConfigTransactionsTest {
    @Test
    fun pendingDraftThenSameTextPresetHydratesWholeEditorBeforeIo() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        assertSame(fixture.config, fixture.transactions.editorConfig.value)
        fixture.transactions.editDraft { it.copy(customText = "OLD EDIT", showLogo = false, leftSlotMode = "phase") }
        assertEquals("OLD EDIT", fixture.transactions.editorConfig.value.customText)
        assertEquals("FOCUS", fixture.config.customText)
        val preset = WatchFaceConfig(hourColor = "#ff39ff14", secondColor = "#ff39ff14")

        val apply = fixture.applyPreset("terminal", preset)

        assertEquals(preset.copy(preset = "terminal"), fixture.transactions.editorConfig.value)
        assertEquals("FOCUS", fixture.transactions.editorConfig.value.customText)
        assertTrue(fixture.saved.isEmpty())
        apply.join()
        assertEquals(1, fixture.saved.size)
        assertEquals(1, fixture.revisions)
        assertEquals(fixture.config, fixture.transactions.editorConfig.value)
        assertEquals("FOCUS", fixture.config.customText)
    }

    @Test
    fun editsAfterPresetBeforeIoKeepPresetFieldsAndSurviveItsStoreEchoAndAck() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseSend = CompletableDeferred<Unit>()
        fixture.send = { releaseSend.await() }
        val preset = WatchFaceConfig(hourColor = "#ff39ff14", secondColor = "#ff39ff14", showLogo = false)
        fixture.applyPreset("terminal", preset)
        fixture.transactions.editDraft { it.copy(customText = "NEW EDIT") }
        val edit = fixture.transactions.editDraft { it.copy(bottomRightSlotMode = "timer") }
        val optimistic = fixture.transactions.editorConfig.value

        assertEquals("terminal", optimistic.preset)
        assertEquals(preset.hourColor, optimistic.hourColor)
        assertEquals("NEW EDIT", optimistic.customText)
        assertEquals("timer", optimistic.bottomRightSlotMode)
        assertTrue(fixture.saved.isEmpty())
        runCurrent()
        assertEquals(optimistic, fixture.transactions.editorConfig.value)
        val revision = requireNotNull(fixture.config.localRevision)
        fixture.transactions.acknowledge(revision, true, null, 200)
        assertEquals(optimistic, fixture.transactions.editorConfig.value)
        assertTrue(fixture.applied.isEmpty())

        releaseSend.complete(Unit)
        edit.join()

        assertEquals(optimistic.copy(localRevision = "draft-revision-2", appliedRevision = "applied-0", updatedAtEpochMs = 102), fixture.config)
        assertEquals(fixture.config, fixture.transactions.editorConfig.value)
        assertEquals(1, fixture.published.size)
    }

    @Test
    fun queuedPresetsCannotReplaceNewerOptimisticEditWhenTheirSnapshotsCommit() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        fixture.send = { if (it.preset == "a") releaseA.await() else releaseB.await() }
        fixture.applyPreset("a", WatchFaceConfig(customText = "A"))
        fixture.applyPreset("b", WatchFaceConfig(hourColor = "#ff42a5f5", customText = "B"))
        val edit = fixture.transactions.editDraft { it.copy(customText = "LATEST EDIT", showLogo = false) }
        val optimistic = fixture.transactions.editorConfig.value

        runCurrent()
        assertEquals("a", fixture.config.preset)
        assertEquals(optimistic, fixture.transactions.editorConfig.value)
        releaseA.complete(Unit)
        runCurrent()
        assertEquals("b", fixture.config.preset)
        assertEquals("B", fixture.config.customText)
        assertEquals(optimistic, fixture.transactions.editorConfig.value)

        releaseB.complete(Unit)
        edit.join()

        assertEquals(listOf("a", "b", "b"), fixture.saved.map { it.preset })
        assertEquals(listOf("a", "b"), fixture.published.map { it.preset })
        assertEquals("LATEST EDIT", fixture.config.customText)
        assertEquals(optimistic.hourColor, fixture.config.hourColor)
        assertFalse(fixture.config.showLogo)
        assertEquals(fixture.config, fixture.transactions.editorConfig.value)
    }

    @Test
    fun localRestoreRunsAfterBlockedSendAndQueuedPresetWithoutWaitingForAck() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseSend = CompletableDeferred<Unit>()
        fixture.send = { if (it.preset == "old") releaseSend.await() }
        fixture.applyPreset("old")
        runCurrent()
        fixture.applyPreset("queued")
        val restored = WatchFaceConfig(customText = "RESTORED", preset = "restored", localRevision = "restore-r", updatedAtEpochMs = 5_000)
        var presetBeforeRestore: String? = null
        val restore = fixture.transactions.restoreLocal {
            presetBeforeRestore = fixture.config.preset
            fixture.config = restored
        }
        runCurrent()
        assertFalse(restore.isCompleted)
        assertEquals("old", fixture.config.preset)

        releaseSend.complete(Unit)
        restore.await()

        assertEquals("queued", presetBeforeRestore)
        assertEquals(restored, fixture.config)
        assertEquals(restored, fixture.transactions.editorConfig.value)
        assertEquals(listOf("old", "queued"), fixture.published.map { it.preset })
        assertTrue(fixture.applied.isEmpty())
        assertEquals(WatchFaceSyncPhase.IDLE, fixture.transactions.status.value.phase)
    }

    @Test
    fun editAfterRestoreInvocationWinsOverTheRestoredStoreEcho() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseSend = CompletableDeferred<Unit>()
        fixture.send = { if (it.preset == "old") releaseSend.await() }
        fixture.applyPreset("old")
        runCurrent()
        fixture.applyPreset("queued", WatchFaceConfig(hourColor = "#ff42a5f5"))
        val restored = WatchFaceConfig(customText = "RESTORED", preset = "restored", localRevision = "restore-r", updatedAtEpochMs = 5_000)
        val restore = fixture.transactions.restoreLocal { fixture.config = restored }
        val edit = fixture.transactions.editDraft { it.copy(customText = "EDIT AFTER RESTORE") }
        val optimistic = fixture.transactions.editorConfig.value
        var editorAtRestoreCompletion: WatchFaceConfig? = null
        var storedAtRestoreCompletion: WatchFaceConfig? = null
        restore.invokeOnCompletion {
            editorAtRestoreCompletion = fixture.transactions.editorConfig.value
            storedAtRestoreCompletion = fixture.config
        }

        releaseSend.complete(Unit)
        edit.join()
        restore.await()

        assertEquals(restored, storedAtRestoreCompletion)
        assertEquals(optimistic, editorAtRestoreCompletion)
        assertEquals(optimistic.copy(localRevision = "draft-revision-3", appliedRevision = null, updatedAtEpochMs = 5_001), fixture.config)
        assertEquals(fixture.config, fixture.transactions.editorConfig.value)
    }

    @Test
    fun followUpRefreshKeepsAnEditQueuedAfterRestoreInvocation() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val restored = WatchFaceConfig(customText = "RESTORED", localRevision = "restore-r", updatedAtEpochMs = 5_000)
        val restore = fixture.transactions.restoreLocal { fixture.config = restored }
        val edit = fixture.transactions.editDraft { it.copy(customText = "NEWER EDIT", bottomRightSlotMode = "timer") }
        val optimistic = fixture.transactions.editorConfig.value
        var refresh: Job? = null
        restore.invokeOnCompletion {
            val config = fixture.transactions.editorConfig.value
            refresh = fixture.transactions.apply(previewConfig = config) { config }
        }

        edit.join()
        requireNotNull(refresh).join()
        restore.await()

        assertEquals(optimistic.copy(localRevision = "revision-1", appliedRevision = null, updatedAtEpochMs = 5_001), fixture.config)
        assertEquals(fixture.config, fixture.transactions.editorConfig.value)
        assertEquals(fixture.saved, fixture.published)
        assertEquals(1, fixture.revisions)
    }

    @Test
    fun failedLocalRestoreReportsActualWriteErrorAndDoesNotBlockNextApply() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val initial = fixture.config
        val error = IOException("Restore write failed")
        val restore = fixture.transactions.restoreLocal { throw error }

        val thrown = runCatching { restore.await() }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(error.message, thrown?.message)
        assertTrue(generateSequence(thrown) { it.cause }.any { it === error })
        assertEquals(initial, fixture.config)
        assertEquals(initial, fixture.transactions.editorConfig.value)
        assertEquals(WatchFaceSyncPhase.FAILED, fixture.transactions.status.value.phase)
        assertEquals("Restore write failed", fixture.transactions.status.value.message)
        assertSame(error, fixture.failures.single())
        assertTrue(fixture.published.isEmpty())

        fixture.applyPreset("next").join()
        assertEquals("next", fixture.config.preset)
        assertEquals(WatchFaceSyncPhase.SYNCING, fixture.transactions.status.value.phase)
    }

    @Test
    fun scopeCancellationBeforeQueuedRestoreRunsCancelsItsDeferred() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.send = { awaitCancellation() }
        fixture.applyPreset("old")
        runCurrent()
        var wrote = false
        val restore = fixture.transactions.restoreLocal { wrote = true }
        runCurrent()

        backgroundScope.cancel()
        runCurrent()

        assertFalse(wrote)
        assertTrue(restore.isCancelled)
        assertTrue(runCatching { restore.await() }.exceptionOrNull() is CancellationException)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun scopeCancellationDuringCompletedLocalWriteKeepsItsActualSuccess() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val restored = WatchFaceConfig(customText = "RESTORED", localRevision = "restore-r")
        val restore = fixture.transactions.restoreLocal {
            fixture.config = restored
            backgroundScope.cancel()
        }
        runCurrent()

        restore.await()
        assertFalse(restore.isCancelled)
        assertEquals(restored, fixture.config)
        assertEquals(restored, fixture.transactions.editorConfig.value)
    }

    @Test
    fun queuedOldDraftIsInvalidatedAndPresetSavesAndPublishesOneSnapshot() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val preset = WatchFaceConfig(
            hourColor = "#ff123456", minuteColor = "#ff654321", secondColor = "#ffabcdef",
            customText = "PRESET", showLogo = false, preset = "inherited-preset",
            leftSlotMode = "phase", bottomRightSlotMode = "daily_target", ambientStyle = "minimal",
            localRevision = "inherited-local", appliedRevision = "inherited-applied", updatedAtEpochMs = 99_999
        )

        fixture.transactions.saveDraft(WatchFaceConfig(customText = "OLD DRAFT"))
        fixture.applyPreset("saved-preset", preset).join()

        val expected = preset.copy(
            preset = "saved-preset", localRevision = "revision-1", appliedRevision = "applied-0",
            updatedAtEpochMs = 101
        )
        assertEquals(listOf(expected), fixture.saved)
        assertEquals(listOf(expected), fixture.published)
        assertSame(fixture.saved.single(), fixture.published.single())
        assertEquals(1, fixture.revisions)
        assertEquals(WatchFaceSyncPhase.SYNCING, fixture.transactions.status.value.phase)
    }

    @Test
    fun userEditAfterPresetWaitsForSendAndRejectsOldAckEvenBeforeDraftSave() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseSend = CompletableDeferred<Unit>()
        fixture.send = { releaseSend.await() }
        val apply = fixture.applyPreset("terminal")
        runCurrent()
        val presetRevision = requireNotNull(fixture.config.localRevision)

        val edit = fixture.transactions.saveDraft(WatchFaceConfig(customText = "NEW EDIT", preset = "terminal"))
        fixture.transactions.acknowledge(presetRevision, true, null, 200)
        runCurrent()

        assertEquals(1, fixture.saved.size)
        assertTrue(fixture.applied.isEmpty())
        assertEquals(WatchFaceSyncPhase.IDLE, fixture.transactions.status.value.phase)

        releaseSend.complete(Unit)
        apply.join()
        edit.join()
        fixture.transactions.acknowledge(presetRevision, true, null, 200)

        assertEquals("NEW EDIT", fixture.config.customText)
        assertEquals("draft-revision-2", fixture.config.localRevision)
        assertEquals("applied-0", fixture.config.appliedRevision)
        assertEquals(102L, fixture.config.updatedAtEpochMs)
        assertEquals(1, fixture.published.size)
        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun presetAndNormalPushPersistAndPublishInInvocationOrder() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseSend = CompletableDeferred<Unit>()
        fixture.send = { if (it.preset == "terminal") releaseSend.await() }
        fixture.applyPreset("terminal")
        runCurrent()
        val firstRevision = requireNotNull(fixture.config.localRevision)
        val second = fixture.transactions.apply { WatchFaceConfig(customText = "PUSH", preset = "custom") }
        runCurrent()

        assertEquals(1, fixture.saved.size)
        assertEquals(1, fixture.published.size)
        fixture.transactions.acknowledge(firstRevision, true, null, 200)
        assertTrue(fixture.applied.isEmpty())

        releaseSend.complete(Unit)
        second.join()
        assertEquals(listOf("terminal", "custom"), fixture.saved.map { it.preset })
        assertEquals(fixture.saved, fixture.published)
        assertEquals(listOf(101L, 102L), fixture.published.map { it.updatedAtEpochMs })
        assertEquals(2, fixture.revisions)

        val secondRevision = requireNotNull(fixture.config.localRevision)
        fixture.transactions.acknowledge(secondRevision, true, null, 201)
        fixture.transactions.acknowledge(firstRevision, true, null, 200)
        fixture.transactions.acknowledge(secondRevision, false, "late rejection", null)
        assertEquals(listOf(secondRevision), fixture.applied)
        assertEquals(secondRevision, fixture.config.appliedRevision)
        assertEquals(fixture.config, fixture.transactions.editorConfig.value)
        assertEquals(WatchFaceSyncPhase.APPLIED, fixture.transactions.status.value.phase)
    }

    @Test
    fun replayKeepsTimestampMonotonicAcrossClockRollbackAndRecreation() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler), WatchFaceConfig(updatedAtEpochMs = 5_000))
        fixture.applyPreset("focus").join()
        fixture.now = 0
        fixture.transactions.apply { fixture.config }.join()
        val replayed = fixture.config
        val recreated = Fixture(this, StandardTestDispatcher(testScheduler), replayed)
        recreated.transactions.apply { recreated.config }.join()

        assertEquals(listOf(5_001L, 5_002L), fixture.published.map { it.updatedAtEpochMs })
        assertEquals(5_003L, recreated.published.single().updatedAtEpochMs)
        assertEquals(recreated.config, recreated.published.single())
    }

    @Test
    fun selectionRefreshUsesCurrentAppliedRevisionAndKeepsPersistedClockFloor() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        fixture.transactions.apply {
            fixture.config = fixture.config.copy(appliedRevision = "restored-applied", updatedAtEpochMs = 5_000)
            WatchFaceConfig(appliedRevision = "preset-applied", updatedAtEpochMs = 99_999)
        }.join()

        assertEquals("restored-applied", fixture.config.appliedRevision)
        assertEquals(5_001L, fixture.config.updatedAtEpochMs)
        assertSame(fixture.saved.single(), fixture.published.single())
    }

    @Test
    fun saveFailureIsVisibleWithoutPublishingAndNextApplyStillWorks() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        fixture.saveFailure = IOException("Storage unavailable")
        fixture.applyPreset("focus").join()

        assertTrue(fixture.saved.isEmpty())
        assertTrue(fixture.published.isEmpty())
        assertEquals(WatchFaceSyncPhase.FAILED, fixture.transactions.status.value.phase)
        assertEquals("Storage unavailable", fixture.transactions.status.value.message)
        assertEquals("applied-0", fixture.config.appliedRevision)

        fixture.saveFailure = null
        fixture.applyPreset("focus").join()
        assertEquals(WatchFaceSyncPhase.SYNCING, fixture.transactions.status.value.phase)
        assertEquals(1, fixture.published.size)
    }

    @Test
    fun sendFailureKeepsSavedRevisionAndReportsFailure() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        fixture.send = { throw IOException("Watch unavailable") }
        fixture.applyPreset("focus").join()

        assertSame(fixture.saved.single(), fixture.published.single())
        assertEquals(WatchFaceSyncPhase.FAILED, fixture.transactions.status.value.phase)
        assertEquals(fixture.config.localRevision, fixture.transactions.status.value.revision)
        assertEquals("Watch unavailable", fixture.transactions.status.value.message)
        assertNotEquals(fixture.config.localRevision, fixture.config.appliedRevision)
    }

    @Test
    fun lateSendFailureCannotOverwriteNewerQueuedDraftStatus() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseSend = CompletableDeferred<Unit>()
        fixture.send = { releaseSend.await(); throw IOException("Old send failed") }
        fixture.applyPreset("focus")
        runCurrent()
        val draft = fixture.transactions.saveDraft(WatchFaceConfig(customText = "NEW EDIT"))
        val latestStatus = fixture.transactions.status.value
        fixture.onFailure = { assertEquals(latestStatus, fixture.transactions.status.value) }

        releaseSend.complete(Unit)
        draft.join()

        assertEquals("NEW EDIT", fixture.config.customText)
        assertEquals(WatchFaceSyncPhase.IDLE, fixture.transactions.status.value.phase)
        assertEquals(1, fixture.failures.size)
    }

    @Test
    fun sendFailureAfterAppliedAckCannotDowngradeAppliedStatus() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        fixture.send = {
            fixture.transactions.acknowledge(requireNotNull(it.localRevision), true, "Applied", 200)
            throw IOException("Late transport failure")
        }
        fixture.applyPreset("focus").join()

        assertEquals(WatchFaceSyncPhase.APPLIED, fixture.transactions.status.value.phase)
        assertEquals(fixture.config.localRevision, fixture.config.appliedRevision)
        assertEquals(1, fixture.failures.size)
    }

    @Test
    fun newerAppliedPresetWinsOverBlockedOldSendFailureAndQueuedOldDraft() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseSend = CompletableDeferred<Unit>()
        fixture.send = {
            if (it.preset == "old") {
                releaseSend.await()
                throw IOException("Old send failed")
            }
            fixture.transactions.acknowledge(requireNotNull(it.localRevision), true, "Applied", 200)
        }
        fixture.applyPreset("old")
        runCurrent()
        fixture.transactions.saveDraft(WatchFaceConfig(customText = "OBSOLETE"))
        runCurrent()
        val latest = fixture.applyPreset("latest")
        val latestStatus = fixture.transactions.status.value
        fixture.onFailure = { assertEquals(latestStatus, fixture.transactions.status.value) }

        releaseSend.complete(Unit)
        latest.join()

        assertEquals(listOf("old", "latest"), fixture.saved.map { it.preset })
        assertEquals(fixture.saved, fixture.published)
        assertEquals(2, fixture.revisions)
        assertEquals("latest", fixture.config.preset)
        assertEquals(fixture.config.localRevision, fixture.config.appliedRevision)
        assertEquals(fixture.config.localRevision, fixture.transactions.status.value.revision)
        assertEquals(WatchFaceSyncPhase.APPLIED, fixture.transactions.status.value.phase)
        assertEquals(1, fixture.failures.size)
    }

    @Test
    fun stalledSendTimesOutAndQueuedApplyCanReachApplied() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        fixture.send = { if (it.preset == "stalled") awaitCancellation() }
        val first = fixture.applyPreset("stalled")
        runCurrent()
        val firstRevision = requireNotNull(fixture.config.localRevision)
        val latest = fixture.applyPreset("latest")

        advanceTimeBy(14_999)
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(latest.isCompleted)
        assertEquals(1, fixture.saved.size)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(first.isCompleted)
        assertFalse(first.isCancelled)
        assertTrue(latest.isCompleted)
        assertFalse(latest.isCancelled)
        assertEquals("Watch configuration sync timed out after 15 seconds", fixture.failures.single().message)
        assertFalse(fixture.failures.single() is CancellationException)
        assertEquals(listOf("stalled", "latest"), fixture.saved.map { it.preset })
        assertEquals(fixture.saved, fixture.published)

        val latestRevision = requireNotNull(fixture.config.localRevision)
        fixture.transactions.acknowledge(firstRevision, true, null, 200)
        assertTrue(fixture.applied.isEmpty())
        fixture.transactions.acknowledge(latestRevision, true, null, 201)
        assertEquals(latestRevision, fixture.config.appliedRevision)
        assertEquals(latestRevision, fixture.transactions.status.value.revision)
        assertEquals(WatchFaceSyncPhase.APPLIED, fixture.transactions.status.value.phase)
    }

    @Test
    fun logoPreparationAndPublishShareTimeoutAndSavedConfigRemainsReplayable() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        fixture.send = { awaitCancellation() }
        val apply = fixture.transactions.apply(beforePublish = { delay(10_000) }) { WatchFaceConfig() }
        runCurrent()
        val saved = fixture.config
        assertTrue(fixture.published.isEmpty())

        advanceTimeBy(15_000)
        runCurrent()

        assertTrue(apply.isCompleted)
        assertFalse(apply.isCancelled)
        assertEquals(saved, fixture.config)
        assertEquals(saved.localRevision, fixture.transactions.status.value.revision)
        assertEquals(WatchFaceSyncPhase.FAILED, fixture.transactions.status.value.phase)
        assertEquals("Watch configuration sync timed out after 15 seconds", fixture.transactions.status.value.message)
        assertSame(saved, fixture.published.single())

        fixture.send = {}
        fixture.transactions.apply { fixture.config }.join()
        assertEquals(saved.appliedRevision, fixture.config.appliedRevision)
        assertEquals(saved.updatedAtEpochMs + 1, fixture.config.updatedAtEpochMs)
        assertNotEquals(saved.localRevision, fixture.config.localRevision)
        assertEquals(WatchFaceSyncPhase.SYNCING, fixture.transactions.status.value.phase)
    }

    @Test
    fun externalScopeCancellationStillCancelsSendWithoutReportingTimeout() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.send = { awaitCancellation() }
        val apply = fixture.applyPreset("focus")
        runCurrent()
        val saved = fixture.config

        backgroundScope.cancel()
        runCurrent()

        assertTrue(apply.isCancelled)
        assertEquals(saved, fixture.config)
        assertTrue(fixture.failures.isEmpty())
        assertEquals(WatchFaceSyncPhase.SYNCING, fixture.transactions.status.value.phase)
    }

    @Test
    fun missingPresetAndDraftSaveFailureAreObservableWithoutPublishing() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        fixture.transactions.applyPreset("deleted") { null }.join()
        assertEquals(WatchFaceSyncPhase.FAILED, fixture.transactions.status.value.phase)
        assertEquals(0, fixture.revisions)

        fixture.saveFailure = IOException("Draft storage unavailable")
        fixture.transactions.saveDraft(WatchFaceConfig(customText = "EDIT")).join()
        assertEquals(WatchFaceSyncPhase.FAILED, fixture.transactions.status.value.phase)
        assertEquals("Draft storage unavailable", fixture.transactions.status.value.message)
        assertTrue(fixture.published.isEmpty())
    }

    @Test
    fun resetSavesOnceThenAwaitsLogoResetBeforePublishingConfig() = runTest {
        val fixture = Fixture(this, StandardTestDispatcher(testScheduler))
        val releaseLogoReset = CompletableDeferred<Unit>()
        val reset = fixture.transactions.apply(beforePublish = { releaseLogoReset.await() }) { WatchFaceConfig() }
        runCurrent()

        assertEquals(1, fixture.saved.size)
        assertTrue(fixture.published.isEmpty())
        releaseLogoReset.complete(Unit)
        reset.join()

        assertEquals(1, fixture.revisions)
        assertEquals("applied-0", fixture.config.appliedRevision)
        assertSame(fixture.saved.single(), fixture.published.single())
    }
}

private class Fixture(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    var config: WatchFaceConfig = WatchFaceConfig(appliedRevision = "applied-0", updatedAtEpochMs = 100)
) {
    var now = 100L
    var revisions = 0
    var saveFailure: IOException? = null
    var send: suspend (WatchFaceConfig) -> Unit = {}
    var onFailure: (Exception) -> Unit = {}
    val saved = mutableListOf<WatchFaceConfig>()
    val published = mutableListOf<WatchFaceConfig>()
    val applied = mutableListOf<String>()
    val failures = mutableListOf<Exception>()
    val transactions = WatchFaceConfigTransactions(
        scope = scope,
        currentConfig = { config },
        saveConfig = {
            saveFailure?.let { throw it }
            saved += it
            config = it
        },
        markApplied = {
            applied += it
            config = config.copy(appliedRevision = it)
        },
        publish = { published += it; send(it) },
        onFailure = { failures += it; onFailure(it) },
        dispatcher = dispatcher,
        now = { now },
        newRevision = { "revision-${++revisions}" }
    )

    fun applyPreset(id: String, config: WatchFaceConfig = WatchFaceConfig()) =
        transactions.applyPreset(id) { WatchFacePreset(it, "Preset", config) }
}
