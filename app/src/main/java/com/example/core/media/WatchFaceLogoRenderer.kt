package com.example.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs

enum class LogoFit { FIT, FILL }

data class LogoTransform(
    val fit: LogoFit = LogoFit.FILL,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val transparent: Boolean = true
) {
    init {
        require(zoom.isFinite() && zoom in 1f..4f) { "Logo zoom must be finite and between 1 and 4" }
        require(panX.isFinite() && panX in -1f..1f) { "Logo horizontal pan must be finite and between -1 and 1" }
        require(panY.isFinite() && panY in -1f..1f) { "Logo vertical pan must be finite and between -1 and 1" }
    }
}

data class WatchFaceLogoDocument(val source: Bitmap, val transform: LogoTransform, val rendered: Bitmap)

object WatchFaceLogoRenderer {
    const val SIZE = 128

    fun render(source: Bitmap, transform: LogoTransform): Bitmap {
        require(!source.isRecycled) { "Logo source bitmap is no longer available" }
        val scaleX = SIZE.toFloat() / source.width
        val scaleY = SIZE.toFloat() / source.height
        val scale = (if (transform.fit == LogoFit.FIT) minOf(scaleX, scaleY) else maxOf(scaleX, scaleY)) * transform.zoom
        val width = source.width * scale
        val height = source.height * scale
        val left = (SIZE - width) / 2f + transform.panX * abs(width - SIZE) / 2f
        val top = (SIZE - height) / 2f + transform.panY * abs(height - SIZE) / 2f
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { rendered ->
            val canvas = Canvas(rendered)
            if (!transform.transparent) canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    fun png(bitmap: Bitmap): ByteArray {
        require(!bitmap.isRecycled) { "Logo bitmap is no longer available" }
        return ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Could not encode logo PNG")
            output.toByteArray()
        }
    }
}
