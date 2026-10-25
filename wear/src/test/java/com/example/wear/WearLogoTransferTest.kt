package com.example.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.example.core.sync.WatchFaceLogoProtocol
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WearLogoTransferTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var controller: ServiceController<WearSyncService>
    private val service get() = controller.get()

    @Before fun prepare() {
        clean()
        controller = Robolectric.buildService(WearSyncService::class.java).create()
    }
    @After fun finish() { controller.destroy(); clean() }
    private fun clean() {
        AtomicFile(File(context.filesDir, WearLogoStore.RECORD_FILENAME)).delete()
        File(context.filesDir, WearSyncService.LOGO_FILENAME).delete()
    }

    @Test fun appliedReceiptFollowsCommitAndContainsExactChecksum() {
        val png = png(128)
        val receipts = mutableListOf<DataMap>()
        assertTrue(service.applyWatchFaceLogo(data("image-a", 100, png), { png }) { receipt ->
            assertEquals("image-a", WearLogoStore(context).revision())
            assertEquals(Color.CYAN, WearLogoStore(context).bitmapOrNull()!!.getPixel(60, 60))
            receipts += receipt
        })
        assertTrue(receipts.single().getBoolean("success"))
        assertEquals(WatchFaceLogoProtocol.sha256(png), receipts.single().getString("sha256"))
    }

    @Test fun resetAndLateUploadCannotRestoreDeletedImage() {
        val png = png(128)
        val receipts = mutableListOf<DataMap>()
        service.applyWatchFaceLogo(data("image-a", 100, png), { png }, receipts::add)
        assertTrue(service.applyWatchFaceLogo(data("reset-b", 101, null), { error("Reset read an image") }, receipts::add))
        assertNull(WearLogoStore(context).bitmapOrNull())
        assertFalse(service.applyWatchFaceLogo(data("image-a", 100, png), { png }, receipts::add))
        assertFalse(receipts.last().getBoolean("success"))
        assertNull(WearLogoStore(context).bitmapOrNull())
        assertEquals("reset-b", WearLogoStore(context).revision())
    }

    @Test fun checksumReadAndSchemaFailuresNeverReplaceExistingImage() {
        val png = png(128)
        service.applyWatchFaceLogo(data("good", 100, png), { png }, {})
        val receipts = mutableListOf<DataMap>()
        assertFalse(service.applyWatchFaceLogo(data("bad-checksum", 101, png).apply { putString("sha256", "bad") }, { png }, receipts::add))
        assertFalse(service.applyWatchFaceLogo(data("read-failed", 102, png), { throw IOException("Asset unavailable") }, receipts::add))
        assertFalse(service.applyWatchFaceLogo(data("future", 103, png).apply { putInt("schema_version", 2) }, { png }, receipts::add))
        assertTrue(receipts.all { !it.getBoolean("success") })
        assertEquals("good", WearLogoStore(context).revision())
        assertEquals(Color.CYAN, WearLogoStore(context).bitmapOrNull()!!.getPixel(60, 60))
    }

    @Test fun old256PixelAssetIsSafelyNormalizedWithoutChangingItsColor() {
        val png = png(256)
        val legacy = DataMap().apply {
            putString("revision", "legacy"); putLong("timestamp", 100)
            putAsset("logo_asset", Asset.createFromBytes(png))
        }
        assertTrue(service.applyWatchFaceLogo(legacy, { png }, {}))
        val stored = WearLogoStore(context).bitmapOrNull()!!
        assertEquals(128, stored.width)
        assertEquals(128, stored.height)
        assertEquals(Color.CYAN, stored.getPixel(60, 60))
    }

    @Test fun legacyRectangleKeepsItsAspectRatioWithin128Pixels() {
        val png = png(256, 128)
        val legacy = DataMap().apply {
            putString("revision", "legacy-rectangle"); putLong("timestamp", 100)
            putAsset("logo_asset", Asset.createFromBytes(png))
        }
        assertTrue(service.applyWatchFaceLogo(legacy, { png }, {}))
        val stored = WearLogoStore(context).bitmapOrNull()!!
        assertEquals(128, stored.width)
        assertEquals(64, stored.height)
        assertEquals(Color.CYAN, stored.getPixel(60, 30))
    }

    @Test fun truncated256PixelLegacyAssetWithIntactBoundsFailsWithoutReplacingPreviousLogo() {
        val truncated = truncateImageData(png(256))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(truncated, 0, truncated.size, bounds)
        assertEquals("image/png", bounds.outMimeType)
        assertEquals(256, bounds.outWidth)
        assertEquals(256, bounds.outHeight)
        val partial = BitmapFactory.decodeByteArray(truncated, 0, truncated.size)
        assertNotNull("Fixture must exercise BitmapFactory's partial-image acceptance", partial)
        partial?.recycle()

        val previous = png(128)
        assertTrue(service.applyWatchFaceLogo(data("good", 100, previous), { previous }, {}))
        val record = File(context.filesDir, WearLogoStore.RECORD_FILENAME)
        val saved = record.readBytes()
        val legacy = DataMap().apply {
            putString("revision", "truncated-legacy"); putLong("timestamp", 101)
            putAsset("logo_asset", Asset.createFromBytes(truncated))
        }
        val receipts = mutableListOf<DataMap>()
        assertFalse(service.applyWatchFaceLogo(legacy, { truncated }, receipts::add))
        assertFalse(receipts.single().getBoolean("success"))
        assertEquals("truncated-legacy", receipts.single().getString("revision"))
        assertEquals("good", WearLogoStore(context).revision())
        assertArrayEquals(saved, record.readBytes())
        assertEquals(Color.CYAN, WearLogoStore(context).bitmapOrNull()!!.getPixel(60, 60))
    }

    private fun truncateImageData(bytes: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(bytes).apply { position(8) }
        while (input.remaining() >= 12) {
            val length = input.int
            val type = ByteArray(4).also { input.get(it) }.toString(Charsets.US_ASCII)
            if (type == "IDAT") {
                check(length > 1)
                return bytes.copyOf(input.position() + length / 2)
            }
            input.position(input.position() + length + 4)
        }
        error("PNG fixture has no image data")
    }

    private fun data(id: String, timestamp: Long, png: ByteArray?) = DataMap().apply {
        putInt("schema_version", WatchFaceLogoProtocol.SCHEMA_VERSION)
        putString("revision", id); putLong("timestamp", timestamp); putBoolean("reset_logo", png == null)
        putString("sha256", png?.let(WatchFaceLogoProtocol::sha256).orEmpty())
        png?.let { putAsset("logo_asset", Asset.createFromBytes(it)) }
    }
    private fun png(side: Int, height: Int = side): ByteArray = Bitmap.createBitmap(side, height, Bitmap.Config.ARGB_8888).run {
        eraseColor(Color.CYAN)
        try { ByteArrayOutputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { recycle() }
    }
}
