package com.example.wear

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearLogoProviderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = WearLogoStore(context)
    private val recordFile get() = File(context.filesDir, WearLogoStore.RECORD_FILENAME)
    private val legacyFile get() = File(context.filesDir, WearSyncService.LOGO_FILENAME)
    private val config get() = context.getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
    private val requests get() = shadowOf(context).broadcastIntents.filter {
        it.action == ComplicationDataSourceUpdateRequester.ACTION_REQUEST_UPDATE_ALL
    }

    @Before fun prepare() {
        clearLogoFiles()
        config.edit().clear().commit()
        ComplicationUpdates.clearForTest()
    }

    @After fun cleanUp() {
        clearLogoFiles()
        config.edit().clear().commit()
        ComplicationUpdates.clearForTest()
    }

    @Test fun providerUsesPersistedPixelsAndImmutablePanicNavigation() {
        assertTrue(store.apply(png(), "custom", 100L, reset = false))
        val provider = provider()
        for (data in listOf(request(provider)!!, provider.getPreviewData(ComplicationType.SMALL_IMAGE)!!)) {
            val icon = (data as SmallImageComplicationData).smallImage.image
            assertEquals(Icon.TYPE_BITMAP, icon.type)
            val bitmap = (icon.loadDrawable(provider) as BitmapDrawable).bitmap
            assertEquals(128, bitmap.width)
            assertEquals(128, bitmap.height)
            assertEquals(Color.GREEN, bitmap.getPixel(20, 20))
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
            assertEquals(Color.BLUE, bitmap.getPixel(127, 127))
            assertPanicTap(data)
        }
    }

    @Test fun missingCorruptAndResetRecordsUseWsLogoWithoutRevivingLegacyImage() {
        val provider = provider()
        assertFallback(request(provider)!!)
        assertTrue(store.apply(png(), "custom", 100L, reset = false))
        assertTrue(store.apply(null, "reset", 200L, reset = true))
        legacyFile.writeBytes(png())
        assertFallback(request(provider)!!)
        assertFallback(provider.getPreviewData(ComplicationType.SMALL_IMAGE)!!)

        recordFile.writeText("{broken")
        assertFallback(request(provider)!!)
        assertTrue(legacyFile.exists())
        assertTrue(store.apply(null, "recovered-reset", 300L, reset = true))
        assertFalse(legacyFile.exists())
        assertFallback(request(provider)!!)
    }

    @Test fun intentionallyHiddenLogoReturnsTransparentSmallImageAndNoTapAction() {
        assertTrue(store.apply(png(), "custom", 100L, reset = false))
        config.edit().putBoolean("show_panic_logo", false).commit()
        val provider = provider()
        val data = request(provider) as SmallImageComplicationData
        data.validate()
        assertEquals(ComplicationType.SMALL_IMAGE, data.type)
        assertNull(data.tapAction)
        val icon = data.smallImage.image
        assertEquals(Icon.TYPE_BITMAP, icon.type)
        val bitmap = (icon.loadDrawable(provider) as BitmapDrawable).bitmap
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertEquals(ComplicationType.SMALL_IMAGE, provider.getPreviewData(ComplicationType.SMALL_IMAGE)!!.type)
        assertNull(request(provider, ComplicationType.SHORT_TEXT))
        assertNull(provider.getPreviewData(ComplicationType.SHORT_TEXT))
    }

    @Test fun changedRevisionRefreshesEvenWhenImageRecordSizeAndModificationTimeMatch() {
        val png = png()
        assertTrue(store.apply(png, "first", 100L, reset = false))
        assertTrue(recordFile.setLastModified(1_000L))
        val modified = recordFile.lastModified()
        val size = recordFile.length()
        val before = requests.size
        ComplicationUpdates.logo(context)
        ComplicationUpdates.logo(context)
        assertEquals(1, requests.size - before)

        assertTrue(store.apply(png, "other", 200L, reset = false))
        assertEquals(size, recordFile.length())
        assertTrue(recordFile.setLastModified(modified))
        assertEquals(modified, recordFile.lastModified())
        ComplicationUpdates.logo(context)
        ComplicationUpdates.logo(context)
        assertEquals(2, requests.size - before)
        assertFalse(store.apply(png, "other", 200L, reset = false))
        ComplicationUpdates.logo(context)
        assertEquals(2, requests.size - before)

        assertTrue(store.apply(null, "reset", 300L, reset = true))
        ComplicationUpdates.logo(context)
        ComplicationUpdates.logo(context)
        assertEquals(3, requests.size - before)
        config.edit().putBoolean("show_panic_logo", false).commit()
        ComplicationUpdates.logo(context)
        assertEquals(4, requests.size - before)
        assertEquals(setOf(LogoComplicationService::class.java.name), requests.drop(before).map {
            @Suppress("DEPRECATION")
            it.getParcelableExtra<ComponentName>(ComplicationDataSourceUpdateRequester.EXTRA_PROVIDER_COMPONENT)!!.className
        }.toSet())
    }

    private fun provider(): LogoComplicationService = Robolectric.buildService(LogoComplicationService::class.java).create().get()

    private fun request(provider: LogoComplicationService, type: ComplicationType = ComplicationType.SMALL_IMAGE): ComplicationData? =
        runBlocking { provider.onComplicationRequest(ComplicationRequest(106, type, false)) }

    private fun assertFallback(data: ComplicationData) {
        val icon = (data as SmallImageComplicationData).smallImage.image
        assertEquals(Icon.TYPE_RESOURCE, icon.type)
        assertEquals(R.drawable.ws_logo, icon.resId)
        assertPanicTap(data)
    }

    private fun assertPanicTap(data: ComplicationData) {
        val action = data.tapAction!!
        assertEquals(ComplicationActions.openPanic(context), action)
        assertTrue(action.isImmutable)
        assertEquals(ComplicationActions.ACTION_OPEN_PANIC, shadowOf(action).savedIntent.action)
        assertFalse(shadowOf(action).savedIntent.getBooleanExtra("FORCE_PANIC_UI", false))
    }

    private fun clearLogoFiles() {
        AtomicFile(recordFile).delete()
        legacyFile.delete()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.GREEN)
            bitmap.setPixel(0, 0, Color.RED)
            bitmap.setPixel(127, 127, Color.BLUE)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
