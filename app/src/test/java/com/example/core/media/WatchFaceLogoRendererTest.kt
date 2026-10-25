package com.example.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 35])
class WatchFaceLogoRendererTest {
    @Test
    fun portraitLandscapeAndSquareRenderAt128WithFitPaddingAndFillCoverage() {
        for ((width, height) in listOf(256 to 128, 128 to 256, 128 to 128)) {
            val source = solid(width, height, Color.RED)
            val before = pixels(source)
            val fit = WatchFaceLogoRenderer.render(source, LogoTransform(fit = LogoFit.FIT))
            assertEquals(128, fit.width)
            assertEquals(128, fit.height)
            assertEquals(Color.RED, fit.getPixel(64, 64))
            assertEquals(if (width == height) Color.RED else Color.TRANSPARENT, fit.getPixel(0, 0))
            for (zoom in listOf(1f, 2f, 4f)) for (panX in listOf(-1f, 0f, 1f)) for (panY in listOf(-1f, 0f, 1f)) {
                val fill = WatchFaceLogoRenderer.render(source, LogoTransform(zoom = zoom, panX = panX, panY = panY))
                assertEquals(128, fill.width)
                assertEquals(128, fill.height)
                assertTrue("FILL must not expose padding: ${width}x$height, $zoom, $panX, $panY", pixels(fill).all { it == Color.RED })
            }
            assertArrayEquals(before, pixels(source))
            assertFalse(source.isRecycled)
        }
    }

    @Test
    fun panSpansTheEntireCropOrLetterboxAndZoomReframesTheUncroppedSource() {
        val landscape = solid(256, 128, Color.RED).apply {
            for (y in 0 until height) for (x in width / 2 until width) setPixel(x, y, Color.BLUE)
        }
        assertEquals(Color.BLUE, WatchFaceLogoRenderer.render(landscape, LogoTransform(panX = -1f)).getPixel(64, 64))
        assertEquals(Color.RED, WatchFaceLogoRenderer.render(landscape, LogoTransform(panX = 1f)).getPixel(64, 64))
        val portrait = solid(128, 256, Color.RED).apply {
            for (y in height / 2 until height) for (x in 0 until width) setPixel(x, y, Color.BLUE)
        }
        assertEquals(Color.BLUE, WatchFaceLogoRenderer.render(portrait, LogoTransform(panY = -1f)).getPixel(64, 64))
        assertEquals(Color.RED, WatchFaceLogoRenderer.render(portrait, LogoTransform(panY = 1f)).getPixel(64, 64))

        val top = WatchFaceLogoRenderer.render(landscape, LogoTransform(fit = LogoFit.FIT, panY = -1f))
        val bottom = WatchFaceLogoRenderer.render(landscape, LogoTransform(fit = LogoFit.FIT, panY = 1f))
        assertEquals(Color.RED, top.getPixel(16, 16))
        assertEquals(Color.TRANSPARENT, top.getPixel(16, 112))
        assertEquals(Color.TRANSPARENT, bottom.getPixel(16, 16))
        assertEquals(Color.RED, bottom.getPixel(16, 112))

        val square = solid(128, 128, Color.RED).apply {
            for (y in 64 until height) for (x in 64 until width) setPixel(x, y, Color.BLUE)
        }
        val stationary = WatchFaceLogoRenderer.render(square, LogoTransform())
        assertArrayEquals(pixels(stationary), pixels(WatchFaceLogoRenderer.render(square, LogoTransform(panX = 1f, panY = -1f))))
        val bottomRight = WatchFaceLogoRenderer.render(square, LogoTransform(zoom = 4f, panX = -1f, panY = -1f))
        val topLeft = WatchFaceLogoRenderer.render(square, LogoTransform(zoom = 4f, panX = 1f, panY = 1f))
        assertTrue(pixels(bottomRight).all { it == Color.BLUE })
        assertTrue(pixels(topLeft).all { it == Color.RED })

        val zoomedFit = WatchFaceLogoRenderer.render(landscape, LogoTransform(fit = LogoFit.FIT, zoom = 2f))
        assertArrayEquals(pixels(WatchFaceLogoRenderer.render(landscape, LogoTransform())), pixels(zoomedFit))
    }

    @Test
    fun transparencyPreservesSourceAlphaAndOpaqueModeCompositesOntoBlack() {
        val source = solid(16, 32, Color.argb(128, 255, 0, 0))
        val transparent = WatchFaceLogoRenderer.render(source, LogoTransform(fit = LogoFit.FIT))
        assertEquals(Color.TRANSPARENT, transparent.getPixel(0, 64))
        assertEquals(128, Color.alpha(transparent.getPixel(64, 64)))
        assertEquals(255, Color.red(transparent.getPixel(64, 64)))

        val opaque = WatchFaceLogoRenderer.render(source, LogoTransform(fit = LogoFit.FIT, transparent = false))
        assertEquals(Color.BLACK, opaque.getPixel(0, 64))
        assertEquals(Color.rgb(128, 0, 0), opaque.getPixel(64, 64))
        assertTrue(pixels(opaque).all { Color.alpha(it) == 255 })
    }

    @Test
    fun pngExportHasExactlyThePreviewPixelsWithoutAnotherCropOrResize() {
        val source = solid(48, 96, Color.argb(128, 0, 255, 0)).apply {
            for (y in 32 until 64) for (x in 16 until 32) setPixel(x, y, Color.MAGENTA)
        }
        for (fit in LogoFit.entries) for (transparent in listOf(true, false)) {
            val preview = WatchFaceLogoRenderer.render(source, LogoTransform(fit, 1.5f, -0.75f, 0.5f, transparent))
            val bytes = WatchFaceLogoRenderer.png(preview)
            val exported = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertEquals(128, exported.width)
            assertEquals(128, exported.height)
            assertArrayEquals(pixels(preview), pixels(exported))
            assertFalse(preview.isRecycled)
        }
    }

    @Test
    fun invalidTransformsAreRejectedRatherThanClampedOrPersisted() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, 0.99f, 4.01f)) {
            assertThrows(IllegalArgumentException::class.java) { LogoTransform(zoom = value) }
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { LogoTransform(panX = value) }
            assertThrows(IllegalArgumentException::class.java) { LogoTransform(panY = value) }
        }
        assertEquals(LogoTransform(), LogoTransform(LogoFit.FILL, 1f, 0f, 0f, true))
        LogoTransform(zoom = 4f, panX = -1f, panY = 1f)
    }

    private fun solid(width: Int, height: Int, color: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
