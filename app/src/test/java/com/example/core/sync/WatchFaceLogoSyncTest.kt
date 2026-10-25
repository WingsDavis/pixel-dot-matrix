package com.example.core.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.example.core.media.LogoTransform
import com.example.core.media.WatchFaceLogoRenderer
import com.example.core.media.WatchFaceLogoStore
import com.google.android.gms.wearable.DataMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WatchFaceLogoSyncTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val record get() = AtomicFile(File(context.filesDir, "watch_face_logo_sync.json"))
    private val input get() = File(context.cacheDir, "logo-sync-input.png")

    @Before fun prepare() {
        WatchFaceLogoStore(context).clear()
        record.delete()
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 80) bitmap.setPixel(x, y, Color.rgb(x * 3, y * 6, (x + y) * 2 % 255))
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @After fun cleanup() { WatchFaceLogoStore(context).clear(); record.delete(); input.delete() }

    @Test fun exactPreviewBytesAreSentAndOldReceiptCannotApplyANewerLocalCrop() = runTest {
        val sent = mutableListOf<LogoOperation>()
        val sync = WatchFaceLogoSync(context, this, { sent += it }, StandardTestDispatcher(testScheduler))
        sync.select(Uri.fromFile(input)).join()
        assertEquals(LogoSyncPhase.LOCAL, sync.state.value.phase)
        val previewBytes = WatchFaceLogoRenderer.png(sync.state.value.document!!.rendered)
        sync.apply().join()
        val first = sent.single()
        assertArrayEquals(previewBytes, first.png)
        assertEquals(LogoSyncPhase.QUEUED, sync.state.value.phase)
        sync.edit { it.copy(zoom = 1.7f, panX = 1f) }.join()
        assertNotEquals(first.digest, WatchFaceLogoProtocol.sha256(WatchFaceLogoRenderer.png(sync.state.value.document!!.rendered)))
        sync.acknowledge(ack(first)).join()
        assertEquals(LogoSyncPhase.LOCAL, sync.state.value.phase)
        sync.apply().join()
        val latest = sent.last()
        assertNotEquals(first.id, latest.id)
        assertTrue(latest.timestamp > first.timestamp)
        sync.acknowledge(ack(latest)).join()
        assertEquals(LogoSyncPhase.APPLIED, sync.state.value.phase)
        sync.acknowledge(ack(latest, success = false)).join()
        assertEquals(LogoSyncPhase.APPLIED, sync.state.value.phase)
    }

    @Test fun pendingUploadSurvivesRecreationAndRetriesItsExactIdentityAndBytes() = runTest {
        val sent = mutableListOf<LogoOperation>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        first.select(Uri.fromFile(input)).join(); first.apply().join()
        val queued = sent.single()
        record.baseFile.writeText(journal().apply { remove("cleanup_pending") }.toString())
        val restored = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        runCurrent()
        assertEquals(LogoSyncPhase.QUEUED, restored.state.value.phase)
        restored.retryPending()
        assertEquals(queued.id, sent.last().id)
        assertEquals(queued.timestamp, sent.last().timestamp)
        assertArrayEquals(queued.png, sent.last().png)
        assertEquals(2, sent.last().attempt)
        restored.acknowledge(ack(sent.last())).join()
        assertEquals(LogoSyncPhase.APPLIED, restored.state.value.phase)
    }

    @Test fun resetErasesLocalMediaAndPendingImageCopyAndRejectsLateUploadReceipt() = runTest {
        val sent = mutableListOf<LogoOperation>()
        val sync = WatchFaceLogoSync(context, this, { sent += it }, StandardTestDispatcher(testScheduler))
        sync.select(Uri.fromFile(input)).join(); sync.apply().join()
        val old = sent.single()
        sync.reset().join()
        val reset = sent.last()
        assertTrue(reset.reset)
        assertNull(reset.png)
        assertNull(WatchFaceLogoStore(context).load())
        assertNull(sync.state.value.document)
        assertFalse(record.baseFile.readText().contains(android.util.Base64.encodeToString(old.png!!, android.util.Base64.NO_WRAP)))
        sync.acknowledge(ack(old)).join()
        assertEquals(LogoSyncPhase.QUEUED, sync.state.value.phase)
        sync.acknowledge(ack(reset)).join()
        assertEquals(LogoSyncPhase.APPLIED, sync.state.value.phase)
    }

    @Test fun failedSendIsVisibleAndRetryCannotBeAppliedByWrongChecksum() = runTest {
        var fail = true
        val sent = mutableListOf<LogoOperation>()
        val sync = WatchFaceLogoSync(context, this, { sent += it; if (fail) throw IOException("No connection") }, StandardTestDispatcher(testScheduler))
        sync.select(Uri.fromFile(input)).join(); sync.apply().join()
        assertEquals(LogoSyncPhase.FAILED, sync.state.value.phase)
        assertTrue(sync.state.value.canRetry)
        fail = false
        sync.retry().join()
        assertEquals(sent.first().id, sent.last().id)
        sync.acknowledge(ack(sent.last()).apply { putString("sha256", "wrong") }).join()
        assertEquals(LogoSyncPhase.QUEUED, sync.state.value.phase)
        sync.acknowledge(ack(sent.last())).join()
        assertEquals(LogoSyncPhase.APPLIED, sync.state.value.phase)
    }

    @Test fun stalledUploadCannotBlockResetOrDowngradeItsReceipt() = runTest {
        val sent = mutableListOf<LogoOperation>()
        val sync = WatchFaceLogoSync(context, this, {
            sent += it
            if (!it.reset) awaitCancellation()
        }, StandardTestDispatcher(testScheduler))
        sync.select(Uri.fromFile(input)).join()
        val upload = sync.apply()
        runCurrent()
        val reset = sync.reset()
        runCurrent()
        assertNull(sync.state.value.document)
        reset.join(); upload.join()
        assertTrue(testScheduler.currentTime >= 10_000)
        sync.acknowledge(ack(sent.last())).join()
        sync.acknowledge(ack(sent.first(), success = false)).join()
        assertEquals(LogoSyncPhase.APPLIED, sync.state.value.phase)
    }

    @Test fun corruptImportAndFailedJournalWriteDoNotPublishOrDiscardValidPreview() = runTest {
        val sent = mutableListOf<LogoOperation>()
        val broken = object : AtomicFile(record.baseFile) {
            override fun startWrite(): FileOutputStream = throw IOException("Disk full")
        }
        val sync = WatchFaceLogoSync(context, this, { sent += it }, StandardTestDispatcher(testScheduler), operationFile = broken)
        sync.select(Uri.fromFile(input)).join()
        val valid = sync.state.value.document
        input.writeText("not an image")
        sync.select(Uri.fromFile(input)).join()
        assertSame(valid, sync.state.value.document)
        assertEquals(LogoSyncPhase.FAILED, sync.state.value.phase)
        sync.apply().join()
        assertTrue(sent.isEmpty())
        assertEquals(LogoSyncPhase.FAILED, sync.state.value.phase)
    }

    @Test fun reversedIoSchedulingKeepsEditInvocationOrderAndApplyUsesTheFinalPose() = runTest {
        val dispatcher = ReversedDispatcher()
        val sent = mutableListOf<LogoOperation>()
        val sync = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        sync.select(Uri.fromFile(input))
        dispatcher.runAll()
        val visited = mutableListOf<Int>()
        sync.edit { visited += 1; it.copy(zoom = 1.5f) }
        sync.edit { visited += 2; it.copy(zoom = 3f) }
        sync.edit { visited += 3; it.copy(panX = 1f, panY = -0.5f) }
        val apply = sync.apply()
        dispatcher.runAll()
        val document = requireNotNull(sync.state.value.document)
        val expected = LogoTransform(zoom = 3f, panX = 1f, panY = -0.5f)
        assertEquals(listOf(1, 2, 3), visited)
        assertEquals(expected, document.transform)
        assertEquals(expected, requireNotNull(WatchFaceLogoStore(context).load()).transform)
        assertArrayEquals(WatchFaceLogoRenderer.png(WatchFaceLogoRenderer.render(document.source, expected)), sent.single().png)
        assertTrue(apply.isCompleted)
    }

    @Test fun cancellingAWaitingEditCannotLetApplyOvertakeTheEarlierImport() = runTest {
        val dispatcher = ReversedDispatcher()
        val sent = mutableListOf<LogoOperation>()
        val sync = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        sync.select(Uri.fromFile(input))
        val cancelled = sync.edit { error("Cancelled edit must not run") }
        cancelled.cancel()
        val apply = sync.apply()
        dispatcher.runAll()
        assertTrue(cancelled.isCancelled)
        assertTrue(apply.isCompleted)
        val document = requireNotNull(sync.state.value.document)
        assertEquals(LogoTransform(), document.transform)
        assertArrayEquals(WatchFaceLogoRenderer.png(document.rendered), sent.single().png)
    }

    @Test fun stalledPublicationDoesNotBlockLocalEditsOrResetCleanup() = runTest {
        val sent = mutableListOf<LogoOperation>()
        val sync = WatchFaceLogoSync(context, this, {
            sent += it
            if (!it.reset) awaitCancellation()
        }, StandardTestDispatcher(testScheduler))
        sync.select(Uri.fromFile(input)).join()
        val upload = sync.apply()
        runCurrent()
        val edit = sync.edit { it.copy(zoom = 2f) }
        runCurrent()
        assertTrue(edit.isCompleted)
        assertFalse(upload.isCompleted)
        assertEquals(2f, requireNotNull(sync.state.value.document).transform.zoom, 0f)
        val reset = sync.reset()
        runCurrent()
        assertFalse(upload.isCompleted)
        assertNull(sync.state.value.document)
        assertNull(WatchFaceLogoStore(context).load())
        assertTrue(journal().getBoolean("reset"))
        assertFalse(journal().getBoolean("cleanup_pending"))
        reset.await()
        upload.join()
        assertTrue(sent.last().reset)
    }

    @Test fun crashAfterResetIntentRecoversCleanupBeforePreviewOrReplayAndCannotResendOldUpload() = runTest {
        val sent = mutableListOf<LogoOperation>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        first.select(Uri.fromFile(input)).join(); first.apply().join()
        val old = sent.single()
        val source = File(File(context.filesDir, "watch_face_logo"), JSONObject(
            File(context.filesDir, "watch_face_logo/manifest.json").readText()).getString("source"))
        record.baseFile.writeText(journal().apply {
            put("id", "crash-reset"); put("timestamp", old.timestamp + 1)
            put("reset", true); put("png", ""); put("sha256", "")
            put("status", "QUEUED"); put("attempt", 0); put("cleanup_pending", true)
        }.toString())
        assertTrue(source.exists())
        sent.clear()
        val restored = WatchFaceLogoSync(context, this, {
            assertNull(WatchFaceLogoStore(context).load())
            assertFalse(journal().getBoolean("cleanup_pending"))
            sent += it
        }, dispatcher)
        assertNull(restored.state.value.document)
        runCurrent()
        assertFalse(source.exists())
        assertNull(WatchFaceLogoStore(context).load())
        assertEquals(LogoSyncPhase.QUEUED, restored.state.value.phase)
        restored.retryPending()
        assertEquals("crash-reset", sent.single().id)
        assertTrue(sent.single().reset)
        assertNull(sent.single().png)
        restored.acknowledge(ack(old)).join()
        assertEquals(LogoSyncPhase.QUEUED, restored.state.value.phase)
        assertEquals("crash-reset", journal().getString("id"))
    }

    @Test fun failedResetIntentWriteKeepsTheSourceAndIgnoredDeferredFailureDoesNotEscape() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sent = mutableListOf<LogoOperation>()
        val first = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        first.select(Uri.fromFile(input)).join(); first.apply().join()
        val preview = WatchFaceLogoRenderer.png(first.state.value.document!!.rendered)
        val before = record.baseFile.readBytes()
        val broken = object : AtomicFile(record.baseFile) {
            override fun startWrite(): FileOutputStream = throw IOException("Disk full")
        }
        val sync = WatchFaceLogoSync(context, this, { sent += it }, dispatcher, operationFile = broken)
        val reset = sync.reset()
        assertTrue(runCatching { reset.await() }.exceptionOrNull() is IOException)
        assertArrayEquals(before, record.baseFile.readBytes())
        assertArrayEquals(preview, WatchFaceLogoRenderer.png(requireNotNull(WatchFaceLogoStore(context).load()).rendered))
        assertNotNull(sync.state.value.document)
        assertEquals(LogoSyncPhase.FAILED, sync.state.value.phase)
        sync.reset()
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(LogoSyncPhase.FAILED, sync.state.value.phase)
    }

    @Test fun failureAfterLocalClearRetainsTheResetIntentForRecreation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sent = mutableListOf<LogoOperation>()
        val first = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        first.select(Uri.fromFile(input)).join(); first.apply().join()
        val old = sent.single()
        var writes = 0
        val interrupted = object : AtomicFile(record.baseFile) {
            override fun startWrite(): FileOutputStream {
                if (++writes == 2) throw IOException("Cleanup completion could not be saved")
                return super.startWrite()
            }
        }
        val sync = WatchFaceLogoSync(context, this, { sent += it }, dispatcher, operationFile = interrupted)
        assertTrue(runCatching { sync.reset().await() }.exceptionOrNull() is IOException)
        assertNull(WatchFaceLogoStore(context).load())
        assertTrue(journal().getBoolean("cleanup_pending"))
        assertTrue(journal().getBoolean("reset"))
        assertEquals("", journal().getString("png"))
        assertNotEquals(old.id, journal().getString("id"))
        assertEquals(1, sent.size)
        sent.clear()
        val restored = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        runCurrent()
        assertFalse(journal().getBoolean("cleanup_pending"))
        restored.retryPending()
        assertTrue(sent.single().reset)
        assertNull(sent.single().png)
    }

    @Test fun selectingAfterFailedCleanupRetiresOnlyThatCleanupBeforeImportAndRetryPreservesNewSource() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sent = mutableListOf<LogoOperation>()
        val sync = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
        sync.select(Uri.fromFile(input)).join(); sync.apply().join()
        val old = sent.single()
        val blocked = File(context.filesDir, "watch_face_logo/manifest.json.new").apply { mkdirs() }
        File(blocked, "block-cleanup").writeText("blocked")
        try {
            assertTrue(runCatching { sync.reset().await() }.exceptionOrNull() is IOException)
            val resetId = journal().getString("id")
            assertTrue(journal().getBoolean("cleanup_pending"))
            assertEquals("", journal().getString("png"))
            assertNull(sync.state.value.document)
            assertEquals(LogoSyncPhase.FAILED, sync.state.value.phase)
            assertTrue(sync.state.value.canRetry)
            sync.acknowledge(ack(old)).join()
            assertEquals(resetId, journal().getString("id"))
            sync.retry().join()
            assertEquals(1, sent.size)
            assertTrue(journal().getBoolean("cleanup_pending"))

            input.writeText("not an image")
            sync.select(Uri.fromFile(input)).join()
            assertFalse(journal().getBoolean("cleanup_pending"))
            assertEquals(resetId, journal().getString("id"))
            assertEquals(LogoSyncPhase.FAILED, sync.state.value.phase)
            blocked.deleteRecursively()
            val bitmap = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            sync.select(Uri.fromFile(input)).join()
            val selected = requireNotNull(sync.state.value.document)
            val preview = WatchFaceLogoRenderer.png(selected.rendered)
            sync.retry().join()
            assertEquals(resetId, sent.last().id)
            assertTrue(sent.last().reset)
            assertFalse(sent.last().cleanupPending)
            assertSame(selected, sync.state.value.document)
            assertArrayEquals(preview, WatchFaceLogoRenderer.png(requireNotNull(WatchFaceLogoStore(context).load()).rendered))
            sync.acknowledge(ack(sent.last())).join()
            assertEquals(LogoSyncPhase.LOCAL, sync.state.value.phase)
            val restored = WatchFaceLogoSync(context, this, { sent += it }, dispatcher)
            runCurrent()
            assertEquals(LogoSyncPhase.LOCAL, restored.state.value.phase)
            assertArrayEquals(preview, WatchFaceLogoRenderer.png(requireNotNull(restored.state.value.document).rendered))
        } finally {
            blocked.deleteRecursively()
        }
    }

    private fun journal() = JSONObject(record.baseFile.readText())

    private class ReversedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeLast().run() }
    }

    private fun ack(operation: LogoOperation, success: Boolean = true) = DataMap().apply {
        putInt("schema_version", WatchFaceLogoProtocol.SCHEMA_VERSION)
        putString("revision", operation.id); putBoolean("reset_logo", operation.reset)
        putString("sha256", operation.digest); putBoolean("success", success)
        putString("status_message", if (success) "Stored" else "Could not store")
    }
}
