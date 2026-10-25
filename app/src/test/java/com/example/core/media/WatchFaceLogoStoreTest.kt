package com.example.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 35])
class WatchFaceLogoStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.filesDir, "watch_face_logo")
    private val manifest = File(directory, "manifest.json")
    private val store = WatchFaceLogoStore(context)

    @After
    fun importTemporaryFilesAreAlwaysRemoved() {
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("watch-face-logo-") })
    }

    @Test
    fun emptyStoreLoadsNullAndCannotBeReframedBeforeImport() {
        assertNull(store.load())
        assertThrows(IllegalStateException::class.java) { store.update(LogoTransform()) }
        store.clear()
        store.clear()
        assertNull(WatchFaceLogoStore(context).load())
    }

    @Test
    fun importReframeAndRecreationKeepTheNormalizedSourceAndExactRenderedPixels() {
        val imported = store.importImage(Uri.fromFile(image(320, 160)))
        assertEquals(320, imported.source.width)
        assertEquals(160, imported.source.height)
        assertEquals(LogoTransform(), imported.transform)
        assertSame(imported, store.load())
        val source = sourceFile()
        val sourceBytes = source.readBytes()
        val transform = LogoTransform(LogoFit.FIT, 1.75f, -0.5f, 0.25f, false)
        val updated = store.update(transform)
        assertSame(imported.source, updated.source)
        assertSame(updated, store.load())
        assertEquals(source, sourceFile())
        assertArrayEquals(sourceBytes, source.readBytes())
        assertFalse(imported.source.isRecycled)
        assertFalse(imported.rendered.isRecycled)

        val restored = requireNotNull(WatchFaceLogoStore(context).load())
        assertEquals(transform, restored.transform)
        assertArrayEquals(pixels(updated.source), pixels(restored.source))
        assertArrayEquals(pixels(updated.rendered), pixels(restored.rendered))
        val png = WatchFaceLogoRenderer.png(updated.rendered)
        val exported = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        assertArrayEquals(pixels(updated.rendered), pixels(exported))
        assertTrue(manifest.length() < 1024)
        val metadata = JSONObject(manifest.readText())
        assertEquals(1, metadata.getInt("schema"))
        assertEquals(source.name, metadata.getString("source"))
        assertFalse(metadata.has("bitmap"))
        assertEquals(1, ownedSources().size)
    }

    @Test
    fun repeatedSliderUpdatesReuseTheSourceBitmapAndSourceFile() {
        val imported = store.importImage(Uri.fromFile(image(512, 256)))
        val source = sourceFile()
        val bytes = source.readBytes()
        for (step in 0..8) {
            val updated = store.update(LogoTransform(zoom = 1f + step / 4f, panX = -1f + step / 4f))
            assertSame(imported.source, updated.source)
            assertEquals(source, sourceFile())
        }
        assertArrayEquals(bytes, source.readBytes())
        assertFalse(imported.source.isRecycled)
        assertFalse(imported.rendered.isRecycled)
    }

    @Test
    fun sourceAlphaSurvivesImportPersistenceAndOpaqueReframing() {
        val imported = store.importImage(Uri.fromFile(image(16, 32, Color.argb(128, 255, 0, 0))))
        assertEquals(128, Color.alpha(imported.source.getPixel(8, 16)))
        val transparent = store.update(LogoTransform(fit = LogoFit.FIT))
        assertEquals(Color.TRANSPARENT, transparent.rendered.getPixel(0, 64))
        assertEquals(128, Color.alpha(transparent.rendered.getPixel(64, 64)))
        val opaque = store.update(transparent.transform.copy(transparent = false))
        assertSame(imported.source, opaque.source)
        assertEquals(128, Color.alpha(opaque.source.getPixel(8, 16)))
        assertEquals(Color.BLACK, opaque.rendered.getPixel(0, 64))
        assertEquals(Color.rgb(128, 0, 0), opaque.rendered.getPixel(64, 64))
        val restored = requireNotNull(WatchFaceLogoStore(context).load())
        assertArrayEquals(pixels(imported.source), pixels(restored.source))
        assertArrayEquals(pixels(opaque.rendered), pixels(restored.rendered))
    }

    @Test
    @Config(shadows = [UnsupportedExifInterface::class])
    fun validPngAndWebpImportAndRecreateWhenPlatformExifThrows() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
            val input = image(32, 64, Color.BLUE, format)
            val expected = requireNotNull(BitmapFactory.decodeFile(input.absolutePath))
            val imported = store.importImage(Uri.fromFile(input))
            assertEquals(32, imported.source.width)
            assertEquals(64, imported.source.height)
            assertArrayEquals(pixels(expected), pixels(imported.source))
            val restored = requireNotNull(WatchFaceLogoStore(context).load())
            assertArrayEquals(pixels(expected), pixels(restored.source))
            assertArrayEquals(pixels(imported.rendered), pixels(restored.rendered))
        }
        val metadata = manifest.readBytes()
        val corrupt = temporary.newFile().apply { writeText("not an image") }
        assertThrows(IOException::class.java) { store.importImage(Uri.fromFile(corrupt)) }
        assertArrayEquals(metadata, manifest.readBytes())
    }

    @Test
    fun successfulReplacementRetiresOnlyOldOwnedSourcesWithoutRecyclingUiBitmaps() {
        val first = store.importImage(Uri.fromFile(image(64, 128)))
        val oldFile = sourceFile()
        val before = pixels(first.source)
        val unrelated = File(directory, "source-not-a-uuid.png").apply { writeText("keep") }
        val replacement = store.importImage(Uri.fromFile(image(128, 64, Color.BLUE)))
        assertNotEquals(oldFile.name, sourceFile().name)
        assertFalse(oldFile.exists())
        assertEquals(1, ownedSources().size)
        assertTrue(unrelated.exists())
        assertFalse(first.source.isRecycled)
        assertFalse(first.rendered.isRecycled)
        assertArrayEquals(before, pixels(first.source))
        assertEquals(Color.BLUE, replacement.rendered.getPixel(64, 64))
        assertEquals(Color.BLUE, requireNotNull(WatchFaceLogoStore(context).load()).rendered.getPixel(64, 64))
    }

    @Test
    fun clearRemovesManifestVariantsAndOwnedMediaButLeavesUnrelatedFiles() {
        val imported = store.importImage(Uri.fromFile(image(64, 64)))
        val owned = sourceFile()
        val orphan = File(directory, "source-${UUID.randomUUID()}.png").apply { writeText("orphan") }
        val backup = File(directory, "manifest.json.bak").apply { writeBytes(manifest.readBytes()) }
        val pending = File(directory, "manifest.json.new").apply { writeText("pending") }
        val unrelated = File(directory, "notes.txt").apply { writeText("keep") }
        val sibling = File(context.filesDir, "unrelated-logo.png").apply { writeText("keep") }
        val nested = File(directory, "other").apply { mkdirs() }
        val nestedFile = File(nested, "source-${UUID.randomUUID()}.png").apply { writeText("keep") }
        store.clear()
        for (file in listOf(owned, orphan, manifest, backup, pending)) assertFalse(file.exists())
        for (file in listOf(unrelated, sibling, nestedFile)) assertEquals("keep", file.readText())
        assertNull(store.load())
        assertNull(WatchFaceLogoStore(context).load())
        assertFalse(imported.source.isRecycled)
        assertFalse(imported.rendered.isRecycled)
    }

    @Test
    fun corruptEmptyTruncatedAndMissingImportsNeverReplaceTheLastValidLogo() {
        val imported = store.importImage(Uri.fromFile(image(128, 64)))
        val source = sourceFile()
        val sourceBytes = source.readBytes()
        val metadata = manifest.readBytes()
        val corrupt = temporary.newFile().apply { writeText("not an image") }
        val empty = temporary.newFile()
        val truncated = temporary.newFile().apply { writeBytes(sourceBytes.copyOf(33)) }
        val missing = File(temporary.root, "missing.png")
        for (file in listOf(corrupt, empty, truncated, missing)) {
            val error = assertThrows(IOException::class.java) { store.importImage(Uri.fromFile(file)) }
            assertTrue(error.message.orEmpty().contains("watch-face logo"))
            assertArrayEquals(metadata, manifest.readBytes())
            assertArrayEquals(sourceBytes, source.readBytes())
            assertSame(imported, store.load())
            assertEquals(1, ownedSources().size)
            importTemporaryFilesAreAlwaysRemoved()
        }
    }

    @Test fun incompletePixelDataWithIntactBoundsCannotReplaceValidSource() {
        val imported = store.importImage(Uri.fromFile(image(128, 64)))
        val complete = image(256, 256).readBytes()
        val truncated = temporary.newFile().apply { writeBytes(complete.copyOf(complete.size * 3 / 4)) }
        assertThrows(IOException::class.java) { store.importImage(Uri.fromFile(truncated)) }
        assertSame(imported, store.load())
    }

    @Test
    fun inputLimitIsEnforcedFromTheStreamAndAnExactly32MiBImageIsAccepted() {
        val input = image(32, 64)
        RandomAccessFile(input, "rw").use { it.setLength(32L * 1024 * 1024) }
        val imported = store.importImage(Uri.fromFile(input))
        assertEquals(32, imported.source.width)
        assertEquals(64, imported.source.height)
        assertTrue(sourceFile().length() < input.length())
        val metadata = manifest.readBytes()
        val source = sourceFile()
        RandomAccessFile(input, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        val error = assertThrows(IOException::class.java) { store.importImage(Uri.fromFile(input)) }
        assertTrue(error.message.orEmpty().contains("limit"))
        assertArrayEquals(metadata, manifest.readBytes())
        assertTrue(source.exists())
        assertSame(imported, store.load())
    }

    @Test
    fun rawImageHeaderLimitsAcceptLargePhotosAndRejectOversizedOrOverflowingDimensions() {
        for ((width, height) in listOf(1 to 1, 8000 to 6000, 16000 to 16000, 100000 to 2560, 2560 to 100000)) {
            validateLogoImageBounds(width, height)
        }
        for ((width, height) in listOf(
            0 to 1, 1 to 0, -1 to 1024, 1024 to -1,
            100001 to 1, 1 to 100001, 16001 to 16000, 16000 to 16001,
            65536 to 65536, 100000 to 100000, Int.MAX_VALUE to Int.MAX_VALUE
        )) {
            assertThrows("Raw header ${width}x$height", IOException::class.java) { validateLogoImageBounds(width, height) }
        }
    }

    @Test
    fun hugeCompressedSourcesAreSampledBeforePixelDecodeAndStayWithinBothBudgets() {
        for ((width, height) in listOf(4096 to 4096, 4097 to 2051, 2051 to 4097)) {
            val document = store.importImage(Uri.fromFile(image(width, height, Color.BLUE)))
            assertTrue(document.source.width in 1..2048)
            assertTrue(document.source.height in 1..2048)
            assertTrue(document.source.allocationByteCount <= 16 * 1024 * 1024)
            assertEquals(width.toDouble() / height, document.source.width.toDouble() / document.source.height, 0.01)
            assertEquals(Color.BLUE, document.rendered.getPixel(64, 64))
            val restored = requireNotNull(WatchFaceLogoStore(context).load())
            assertArrayEquals(pixels(document.source), pixels(restored.source))
        }
    }

    @Test
    fun corruptMetadataRejectsUnsafePathsWrongTypesUnknownSchemasAndInvalidPoses() {
        store.importImage(Uri.fromFile(image(64, 64)))
        val source = sourceFile()
        val valid = manifest.readText()
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("schema", 2) },
            { it.put("schema", "1") },
            { it.put("source", "../outside.png") },
            { it.put("source", source.absolutePath) },
            { it.put("source", "source-not-a-uuid.png") },
            { it.remove("pose") },
            { it.getJSONObject("pose").put("fit", "STRETCH") },
            { it.getJSONObject("pose").put("zoom", "NaN") },
            { it.getJSONObject("pose").put("zoom", "2") },
            { it.getJSONObject("pose").put("zoom", 0) },
            { it.getJSONObject("pose").put("zoom", 4.00000001) },
            { it.getJSONObject("pose").put("panX", -1.1) },
            { it.getJSONObject("pose").put("panY", 1.1) },
            { it.getJSONObject("pose").put("transparent", "true") },
            { it.getJSONObject("pose").remove("panY") }
        )
        for (change in changes) {
            manifest.writeText(JSONObject(valid).also(change).toString())
            val error = assertThrows(IOException::class.java) { WatchFaceLogoStore(context).load() }
            assertTrue(error.message.orEmpty().contains("metadata"))
            assertTrue(source.exists())
        }
        for (raw in listOf("{broken", "x".repeat(4097))) {
            manifest.writeText(raw)
            assertThrows(IOException::class.java) { WatchFaceLogoStore(context).load() }
            assertTrue(source.exists())
        }
        manifest.writeText(valid)
        assertEquals(LogoTransform(), requireNotNull(WatchFaceLogoStore(context).load()).transform)
    }

    @Test
    fun missingOrCorruptPersistedDataThrowsInsteadOfSilentlyReturningNoLogo() {
        store.importImage(Uri.fromFile(image(64, 64)))
        val source = sourceFile()
        val sourceBytes = source.readBytes()
        source.delete()
        assertTrue(assertThrows(IOException::class.java) { store.load() }.message.orEmpty().contains("source"))
        source.writeText("broken PNG")
        assertTrue(assertThrows(IOException::class.java) { WatchFaceLogoStore(context).load() }.message.orEmpty().contains("source"))
        source.writeBytes(sourceBytes)
        source.writeBytes(WatchFaceLogoRenderer.png(Bitmap.createBitmap(2049, 1, Bitmap.Config.ARGB_8888)))
        assertThrows(IOException::class.java) { WatchFaceLogoStore(context).load() }
        source.writeBytes(sourceBytes)
        manifest.delete()
        assertTrue(assertThrows(IOException::class.java) { store.load() }.message.orEmpty().contains("metadata"))
        assertTrue(source.exists())
    }

    @Test
    @Config(sdk = [35])
    fun failedManifestWritesKeepThePreviousSourcePoseAndCacheRecoverable() {
        val original = store.importImage(Uri.fromFile(image(128, 64)))
        val source = sourceFile()
        val sourceBytes = source.readBytes()
        val metadata = manifest.readBytes()
        val blocked = File(directory, "manifest.json.new").apply { mkdirs() }
        File(blocked, "block-write").writeText("blocked")
        assertThrows(IOException::class.java) { store.update(LogoTransform(zoom = 2f)) }
        assertThrows(IOException::class.java) { store.importImage(Uri.fromFile(image(64, 128, Color.BLUE))) }
        assertArrayEquals(metadata, manifest.readBytes())
        assertArrayEquals(sourceBytes, source.readBytes())
        assertSame(original, store.load())
        assertArrayEquals(pixels(original.rendered), pixels(requireNotNull(WatchFaceLogoStore(context).load()).rendered))
        blocked.deleteRecursively()
        val updated = store.update(LogoTransform(zoom = 2f))
        assertSame(original.source, updated.source)
        assertEquals(1, ownedSources().size)
        assertEquals(source, sourceFile())
    }

    @Test
    @Config(sdk = [35])
    fun atomicManifestBackupRecoversAnInterruptedWriteAndDiscardsPendingMetadata() {
        val original = store.importImage(Uri.fromFile(image(64, 128)))
        val metadata = manifest.readBytes()
        File(directory, "manifest.json.bak").writeBytes(metadata)
        manifest.writeText("interrupted write")
        val pending = File(directory, "manifest.json.new").apply { writeText("uncommitted") }
        val restored = requireNotNull(WatchFaceLogoStore(context).load())
        assertArrayEquals(metadata, manifest.readBytes())
        assertArrayEquals(pixels(original.rendered), pixels(restored.rendered))
        assertFalse(pending.exists())
    }

    @Test
    fun allEightExifOrientationsAreNormalizedBeforePersistenceAndRendering() {
        for (orientation in 1..8) {
            val input = temporary.newFile()
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until height) for (x in 0 until width) setPixel(x, y, when {
                    x < width / 2 && y < height / 2 -> Color.RED
                    y < height / 2 -> Color.GREEN
                    x < width / 2 -> Color.BLUE
                    else -> Color.YELLOW
                })
            }
            input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            bitmap.recycle()
            ExifInterface(input.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val raw = requireNotNull(BitmapFactory.decodeFile(input.absolutePath))
            val imported = store.importImage(Uri.fromFile(input))
            assertEquals(if (orientation >= 5) raw.height else raw.width, imported.source.width)
            assertEquals(if (orientation >= 5) raw.width else raw.height, imported.source.height)
            val expected = IntArray(raw.width * raw.height)
            for (y in 0 until imported.source.height) for (x in 0 until imported.source.width) {
                val (sourceX, sourceY) = when (orientation) {
                    2 -> raw.width - 1 - x to y
                    3 -> raw.width - 1 - x to raw.height - 1 - y
                    4 -> x to raw.height - 1 - y
                    5 -> y to x
                    6 -> y to raw.height - 1 - x
                    7 -> raw.width - 1 - y to raw.height - 1 - x
                    8 -> raw.width - 1 - y to x
                    else -> x to y
                }
                expected[y * imported.source.width + x] = raw.getPixel(sourceX, sourceY)
            }
            assertArrayEquals("EXIF orientation $orientation", expected, pixels(imported.source))
            val restored = requireNotNull(WatchFaceLogoStore(context).load())
            assertArrayEquals(expected, pixels(restored.source))
            assertArrayEquals(pixels(imported.rendered), pixels(restored.rendered))
        }
    }

    private fun image(width: Int, height: Int, color: Int = Color.RED, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return temporary.newFile().also { file ->
            file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
            bitmap.recycle()
        }
    }

    private fun sourceFile() = File(directory, JSONObject(manifest.readText()).getString("source"))

    private fun ownedSources() = directory.listFiles().orEmpty().filter { it.name.startsWith("source-") && it.name != "source-not-a-uuid.png" }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    @Implements(ExifInterface::class)
    class UnsupportedExifInterface {
        @Implementation
        fun __constructor__(filename: String) {
            throw IOException("EXIF container unsupported: $filename")
        }
    }
}
