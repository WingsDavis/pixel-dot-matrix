package com.example.wear

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearLogoStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var context: Context
    private val recordFile get() = File(context.filesDir, WearLogoStore.RECORD_FILENAME)
    private val legacyFile get() = File(context.filesDir, WearSyncService.LOGO_FILENAME)
    private val store get() = WearLogoStore(context)

    @Before fun prepare() {
        context = contextForFiles()
    }

    @Test fun uploadPersistsOneAtomicEnvelopeAndRetainsDecodedPixelsAfterRecreation() {
        val pngBytes = png(Color.GREEN)
        legacyFile.writeBytes(png(Color.YELLOW))
        assertTrue(store.apply(pngBytes, "first", 100L, reset = false))

        assertFalse(legacyFile.exists())
        assertEquals(listOf(WearLogoStore.RECORD_FILENAME), folder.root.list()!!.toList())
        val record = JSONObject(recordFile.readText())
        assertEquals(1, record.getInt("schema_version"))
        assertEquals("first", record.getString("revision"))
        assertEquals(100L, record.getLong("timestamp"))
        assertFalse(record.getBoolean("reset"))
        assertArrayEquals(pngBytes, Base64.decode(record.getString("png"), Base64.NO_WRAP))
        assertTrue(recordFile.length() <= WearLogoStore.MAX_RECORD_BYTES)
        assertEquals("first", store.revision())
        assertPixels(store.bitmapOrNull(), Color.GREEN)
    }

    @Test fun duplicateRevisionDoesNotRewriteAndOlderOrTiedRevisionsCannotReplaceIt() {
        val pngBytes = png(Color.GREEN)
        assertTrue(store.apply(pngBytes, "first", 100L, reset = false))
        val before = recordFile.readBytes()
        assertTrue(recordFile.setLastModified(1_000L))
        val modified = recordFile.lastModified()

        assertFalse(store.apply(pngBytes, "first", 100L, reset = false))
        assertFalse(store.apply(pngBytes, "first", 100L, reset = false))
        assertArrayEquals(before, recordFile.readBytes())
        assertEquals(modified, recordFile.lastModified())
        for (timestamp in listOf(99L, 100L)) {
            assertThrows(IllegalStateException::class.java) {
                store.apply(png(Color.YELLOW), "other", timestamp, reset = false)
            }
        }
        assertPixels(store.bitmapOrNull(), Color.GREEN)
        assertTrue(store.apply(png(Color.YELLOW), "other", 101L, reset = false))
        assertEquals("other", store.revision())
        assertPixels(store.bitmapOrNull(), Color.YELLOW)
    }

    @Test fun sameRevisionWithDifferentTimestampResetOrImageIsRejectedWithoutChangingSavedIdentity() {
        val pngBytes = png(Color.GREEN)
        assertTrue(store.apply(pngBytes, "upload", 100L, reset = false))
        val before = recordFile.readBytes()
        for (timestamp in listOf(1L, 101L)) {
            assertThrows(IllegalStateException::class.java) {
                store.apply(pngBytes, "upload", timestamp, reset = false)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            store.apply(png(Color.YELLOW), "upload", 100L, reset = false)
        }
        assertThrows(IllegalStateException::class.java) {
            store.apply(null, "upload", 100L, reset = true)
        }
        assertArrayEquals(before, recordFile.readBytes())
        assertPixels(store.bitmapOrNull(), Color.GREEN)

        assertTrue(store.apply(null, "reset", 200L, reset = true))
        val tombstone = recordFile.readBytes()
        assertThrows(IllegalStateException::class.java) {
            store.apply(pngBytes, "reset", 200L, reset = false)
        }
        assertThrows(IllegalStateException::class.java) {
            store.apply(null, "reset", 201L, reset = true)
        }
        assertArrayEquals(tombstone, recordFile.readBytes())
        assertNull(store.bitmapOrNull())
    }

    @Test fun resetPersistsTombstoneAndLateUploadsCannotRestoreLegacyOrPreviousImage() {
        assertTrue(store.apply(png(Color.GREEN), "upload", 100L, reset = false))
        legacyFile.writeBytes(png(Color.YELLOW))
        assertTrue(store.apply(null, "reset", 200L, reset = true))
        assertFalse(legacyFile.exists())
        assertNull(store.bitmapOrNull())
        assertEquals("reset", store.revision())
        val tombstone = recordFile.readBytes()
        val record = JSONObject(String(tombstone, Charsets.UTF_8))
        assertTrue(record.getBoolean("reset"))
        assertTrue(record.isNull("png"))

        legacyFile.writeBytes(png(Color.YELLOW))
        assertNull(store.bitmapOrNull())
        for (timestamp in listOf(100L, 199L, 200L)) {
            assertThrows(IllegalStateException::class.java) {
                store.apply(png(Color.GREEN), "late-$timestamp", timestamp, reset = false)
            }
        }
        assertFalse(store.apply(null, "reset", 200L, reset = true))
        assertFalse(legacyFile.exists())
        assertArrayEquals(tombstone, recordFile.readBytes())
        assertNull(store.bitmapOrNull())
        assertTrue(store.apply(png(Color.YELLOW), "new-upload", 201L, reset = false))
        assertPixels(store.bitmapOrNull(), Color.YELLOW)
    }

    @Test fun invalidInputsAreRejectedBeforeWritingAndKeepPreviousValidImage() {
        val valid = png(Color.GREEN)
        assertTrue(store.apply(valid, "valid", 100L, reset = false))
        val before = recordFile.readBytes()
        val invalidImages = listOf(
            null,
            byteArrayOf(),
            "not PNG".toByteArray(),
            valid.copyOf(33),
            png(Color.YELLOW, width = 129),
            png(Color.YELLOW, height = 129),
            valid.copyOf(WearLogoStore.MAX_IMAGE_BYTES + 1)
        )
        for (invalid in invalidImages) {
            assertThrows(Exception::class.java) { store.apply(invalid, "invalid", 200L, reset = false) }
            assertArrayEquals(before, recordFile.readBytes())
        }
        assertThrows(IllegalArgumentException::class.java) { store.apply(valid, "invalid", 200L, reset = true) }
        for (revision in listOf("", " ", "bad\nrevision", "x".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) { store.apply(valid, revision, 200L, reset = false) }
        }
        for (timestamp in listOf(-1L, 0L)) {
            assertThrows(IllegalArgumentException::class.java) { store.apply(valid, "invalid", timestamp, reset = false) }
        }
        assertArrayEquals(before, recordFile.readBytes())
        assertEquals("valid", store.revision())
        assertPixels(store.bitmapOrNull(), Color.GREEN)
    }

    @Test fun partialUploadAndResetWritesRollBackImageAndMetadataTogether() {
        assertTrue(store.apply(png(Color.GREEN), "valid", 100L, reset = false))
        val before = recordFile.readBytes()
        legacyFile.writeBytes(png(Color.YELLOW))
        val failing = WearLogoStore(context) { output, bytes ->
            output.write(bytes, 0, bytes.size / 2)
            output.flush()
            throw IOException("Disk failed after a partial write")
        }

        for (reset in listOf(false, true)) {
            assertThrows(IOException::class.java) {
                failing.apply(if (reset) null else png(Color.YELLOW), "failed-$reset", 200L, reset)
            }
            assertArrayEquals(before, recordFile.readBytes())
            assertEquals("valid", store.revision())
            assertPixels(store.bitmapOrNull(), Color.GREEN)
            assertTrue(legacyFile.exists())
            assertFalse(File(recordFile.path + ".new").exists())
            assertFalse(File(recordFile.path + ".bak").exists())
        }
    }

    @Test fun failedFirstWriteLeavesLegacyReadableWithoutCreatingARecord() {
        legacyFile.writeBytes(png(Color.GREEN))
        val before = legacyFile.readBytes()
        val failing = WearLogoStore(context) { output, bytes ->
            output.write(bytes, 0, bytes.size / 2)
            throw IOException("First write failed")
        }
        assertThrows(IOException::class.java) { failing.apply(null, "reset", 100L, reset = true) }
        assertFalse(recordFile.exists())
        assertArrayEquals(before, legacyFile.readBytes())
        assertNull(store.revision())
        assertPixels(store.bitmapOrNull(), Color.GREEN)
    }

    @Test fun cleanupFailureThrowsAndExactDuplicateRetriesItWithoutRewritingUploadOrReset() {
        val pngBytes = png(Color.GREEN)
        for ((reset, timestamp) in listOf(false to 100L, true to 200L)) {
            assertTrue(legacyFile.mkdir())
            val blocker = File(legacyFile, "cannot-delete-until-empty").apply { writeText("occupied") }
            val revision = "operation-$reset"
            val payload = if (reset) null else pngBytes
            assertThrows(IOException::class.java) { store.apply(payload, revision, timestamp, reset) }
            assertEquals(revision, store.revision())
            val committed = recordFile.readBytes()
            assertTrue(blocker.exists())
            if (reset) assertNull(store.bitmapOrNull()) else assertPixels(store.bitmapOrNull(), Color.GREEN)

            assertThrows(IOException::class.java) { store.apply(payload, revision, timestamp, reset) }
            assertArrayEquals(committed, recordFile.readBytes())
            assertTrue(blocker.delete())
            assertFalse(store.apply(payload, revision, timestamp, reset))
            assertFalse(legacyFile.exists())
            assertArrayEquals(committed, recordFile.readBytes())
        }
    }

    @Test fun largeLegacyImageIsSampledWithoutAutoMigrationOrPixelFabrication() {
        legacyFile.writeBytes(png(Color.GREEN, width = 512, height = 256))
        val before = legacyFile.readBytes()
        val bitmap = store.bitmapOrNull()!!
        try {
            assertEquals(128, bitmap.width)
            assertEquals(64, bitmap.height)
            assertEquals(Color.GREEN, bitmap.getPixel(20, 20))
        } finally {
            bitmap.recycle()
        }
        assertNull(store.revision())
        assertFalse(recordFile.exists())
        assertArrayEquals(before, legacyFile.readBytes())
    }

    @Test fun corruptAndOversizedLegacyFilesReturnNoBitmap() {
        for (bytes in listOf("not PNG".toByteArray(), png(Color.GREEN).copyOf(33))) {
            legacyFile.writeBytes(bytes)
            assertNull(store.bitmapOrNull())
        }
        RandomAccessFile(legacyFile, "rw").use { it.setLength(WearLogoStore.MAX_IMAGE_BYTES + 1L) }
        assertNull(store.bitmapOrNull())
        assertFalse(recordFile.exists())
    }

    @Test fun corruptAuthoritativeRecordNeverFallsBackToLegacyAndRejectsUploadsUntilReset() {
        legacyFile.writeBytes(png(Color.GREEN))
        recordFile.writeText("{broken")
        assertNull(store.bitmapOrNull())
        assertNull(store.revision())
        assertThrows(Exception::class.java) { store.apply(png(Color.YELLOW), "upload", 200L, reset = false) }
        assertEquals("{broken", recordFile.readText())
        assertTrue(legacyFile.exists())

        RandomAccessFile(recordFile, "rw").use { it.setLength(WearLogoStore.MAX_RECORD_BYTES + 1L) }
        assertNull(store.bitmapOrNull())
        assertNull(store.revision())
        assertThrows(IOException::class.java) { store.apply(png(Color.YELLOW), "upload", 200L, reset = false) }
    }

    @Test fun explicitResetRecoversCorruptRecordsAtomicallyAndRejectsLateUploads() {
        val corruptRecords = listOf(
            "{broken".toByteArray(),
            envelope(null, reset = true).put("timestamp", -1L).toString().toByteArray(),
            envelope(png(Color.GREEN).copyOf(33)).toString().toByteArray(),
            ByteArray(WearLogoStore.MAX_RECORD_BYTES + 1)
        )
        val failing = WearLogoStore(context) { output, bytes ->
            output.write(bytes, 0, bytes.size / 2)
            throw IOException("Recovery reset failed after a partial write")
        }
        for ((index, corrupt) in corruptRecords.withIndex()) {
            recordFile.writeBytes(corrupt)
            legacyFile.writeBytes(png(Color.GREEN))
            val revision = "recovery-$index"
            val timestamp = 200L + index
            assertThrows(Exception::class.java) {
                store.apply(png(Color.YELLOW), "upload", timestamp + 1L, reset = false)
            }
            assertThrows(IOException::class.java) { failing.apply(null, revision, timestamp, reset = true) }
            assertArrayEquals(corrupt, recordFile.readBytes())
            assertTrue(legacyFile.exists())
            assertNull(store.bitmapOrNull())

            assertTrue(store.apply(null, revision, timestamp, reset = true))
            assertEquals(revision, store.revision())
            assertNull(store.bitmapOrNull())
            assertFalse(legacyFile.exists())
            val tombstone = recordFile.readBytes()
            val json = JSONObject(String(tombstone, Charsets.UTF_8))
            assertEquals(1, json.getInt("schema_version"))
            assertTrue(json.getBoolean("reset"))
            assertTrue(json.isNull("png"))
            assertThrows(IllegalStateException::class.java) {
                store.apply(png(Color.YELLOW), "late-upload", 100L, reset = false)
            }
            assertFalse(store.apply(null, revision, timestamp, reset = true))
            assertArrayEquals(tombstone, recordFile.readBytes())
        }
    }

    @Test fun recordsWithoutSchemaDefaultToOneAndFutureSchemasCannotBeReadOrOverwritten() {
        val pngBytes = png(Color.GREEN)
        assertTrue(store.apply(pngBytes, "valid", 100L, reset = false))
        val legacySchema = JSONObject(recordFile.readText()).apply { remove("schema_version") }
        recordFile.writeText(legacySchema.toString())
        val before = recordFile.readBytes()
        assertEquals("valid", store.revision())
        assertPixels(store.bitmapOrNull(), Color.GREEN)
        assertFalse(store.apply(pngBytes, "valid", 100L, reset = false))
        assertArrayEquals(before, recordFile.readBytes())

        legacyFile.writeBytes(png(Color.YELLOW))
        for (schema in listOf(2L, 4_294_967_297L, Long.MAX_VALUE)) {
            val future = legacySchema.put("schema_version", schema).toString().toByteArray()
            recordFile.writeBytes(future)
            assertNull(store.revision())
            assertNull(store.bitmapOrNull())
            for (reset in listOf(false, true)) {
                assertThrows(IOException::class.java) {
                    store.apply(if (reset) null else pngBytes, "unsupported", 200L, reset)
                }
                assertArrayEquals(future, recordFile.readBytes())
                assertTrue(legacyFile.exists())
            }
        }
    }

    @Test fun invalidRecordPayloadsStayBoundedAndNeverUseLegacyPixels() {
        legacyFile.writeBytes(png(Color.GREEN))
        val valid = png(Color.YELLOW)
        val envelopes = listOf(
            envelope(null, reset = false),
            envelope(valid, reset = true),
            envelope(valid.copyOf(33)),
            envelope(png(Color.YELLOW, width = 129)),
            envelope(valid).put("png", "A".repeat(((WearLogoStore.MAX_IMAGE_BYTES + 2) / 3) * 4 + 1))
        )
        for (json in envelopes) {
            recordFile.writeText(json.toString())
            assertNull(store.bitmapOrNull())
            val before = recordFile.readBytes()
            assertThrows(Exception::class.java) { store.apply(valid, "repair-upload", 200L, reset = false) }
            assertArrayEquals(before, recordFile.readBytes())
            assertTrue(legacyFile.exists())
        }
    }

    @Test fun atomicBackupRecoveryKeepsImageAndRevisionPaired() {
        assertTrue(store.apply(png(Color.GREEN), "valid", 100L, reset = false))
        recordFile.copyTo(File(recordFile.path + ".bak"))
        recordFile.writeText("{partial")
        assertEquals("valid", store.revision())
        assertPixels(store.bitmapOrNull(), Color.GREEN)
        assertFalse(File(recordFile.path + ".bak").exists())
    }

    @Test fun sharedLockPreventsAnotherContextReadingOrWritingDuringPartialReset() {
        assertTrue(store.apply(png(Color.GREEN), "upload", 100L, reset = false))
        val writing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        val anotherContext = contextForFiles()
        val resetting = WearLogoStore(context) { output, bytes ->
            val half = bytes.size / 2
            output.write(bytes, 0, half)
            writing.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            output.write(bytes, half, bytes.size - half)
        }
        try {
            val reset = executor.submit<Boolean> { resetting.apply(null, "reset", 200L, reset = true) }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val read = executor.submit<String?> {
                readerStarted.countDown()
                WearLogoStore(anotherContext).revision()
            }
            val lateUpload = executor.submit<Boolean> {
                writerStarted.countDown()
                try {
                    WearLogoStore(anotherContext).apply(png(Color.YELLOW), "late", 150L, reset = false)
                    false
                } catch (_: IllegalStateException) {
                    true
                }
            }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { read.get(100, TimeUnit.MILLISECONDS) }
            assertThrows(TimeoutException::class.java) { lateUpload.get(100, TimeUnit.MILLISECONDS) }
            finish.countDown()
            assertTrue(reset.get(5, TimeUnit.SECONDS))
            assertEquals("reset", read.get(5, TimeUnit.SECONDS))
            assertTrue(lateUpload.get(5, TimeUnit.SECONDS))
            assertNull(WearLogoStore(anotherContext).bitmapOrNull())
        } finally {
            finish.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun contextForFiles(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        override fun getFilesDir(): File = folder.root
    }

    private fun envelope(png: ByteArray?, reset: Boolean = false): JSONObject = JSONObject()
        .put("revision", "stored")
        .put("timestamp", 100L)
        .put("reset", reset)
        .put("png", png?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)

    private fun png(color: Int, width: Int = 128, height: Int = 128): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            bitmap.setPixel(0, 0, Color.RED)
            bitmap.setPixel(width - 1, height - 1, Color.BLUE)
            bitmap.setPixel(width / 2, height / 2, Color.TRANSPARENT)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertPixels(bitmap: Bitmap?, color: Int) {
        assertNotNull(bitmap)
        val actual = bitmap!!
        try {
            assertEquals(128, actual.width)
            assertEquals(128, actual.height)
            assertEquals(Color.RED, actual.getPixel(0, 0))
            assertEquals(Color.BLUE, actual.getPixel(127, 127))
            assertEquals(Color.TRANSPARENT, actual.getPixel(64, 64))
            assertEquals(color, actual.getPixel(20, 20))
        } finally {
            actual.recycle()
        }
    }
}
