package com.example.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Intent
import android.view.SurfaceHolder
import androidx.wear.watchface.*
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.time.ZonedDateTime

class DotMatrixWatchFaceService : WatchFaceService() {
    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = DotMatrixRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository,
            canvasType = CanvasType.HARDWARE
        )

        return WatchFace(
            watchFaceType = WatchFaceType.DIGITAL,
            renderer = renderer
        ).apply {
            setTapListener(
                object : WatchFace.TapListener {
                    override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                        if (tapType == TapType.UP) {
                            val tapY = tapEvent.yPos
                            if (tapY > 250) {
                                val intent = Intent(applicationContext, PanicTriggerActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                applicationContext.startActivity(intent)
                            }
                        }
                    }
                }
            )
        }
    }
}

class DotMatrixRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    private val watchState: WatchState,
    private val currentUserStyleRepository: CurrentUserStyleRepository,
    canvasType: Int
) : Renderer.CanvasRenderer2<Renderer.SharedAssets>(
    surfaceHolder = surfaceHolder,
    currentUserStyleRepository = currentUserStyleRepository,
    watchState = watchState,
    canvasType = canvasType,
    interactiveDrawModeUpdateDelayMillis = 16L,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = true
) {

    private val dotPaintRed = Paint().apply {
        color = Color.parseColor("#E53935")
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val dotPaintWhite = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val wsLogoPaint = Paint().apply {
        isAntiAlias = true
    }

    private val scaledLogoBitmap by lazy {
        val logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ws_logo)
        android.graphics.Bitmap.createScaledBitmap(logoBitmap, 66, 60, true)
    }

    override suspend fun createSharedAssets(): SharedAssets {
        return object : SharedAssets {
            override fun onDestroy() {}
        }
    }

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {
        val isAmbient = renderParameters.drawMode == DrawMode.AMBIENT
        canvas.drawColor(Color.BLACK)

        val hour = zonedDateTime.hour
        val minute = zonedDateTime.minute

        drawDotMatrix(canvas, bounds, hour, minute, isAmbient, zonedDateTime)
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {}

    private fun drawDotMatrix(canvas: Canvas, bounds: Rect, hour: Int, minute: Int, isAmbient: Boolean, zonedDateTime: ZonedDateTime) {
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()

        val hourStr = String.format("%02d", hour)
        val minStr = String.format("%02d", minute)
        val configPrefs = context.getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
        val timerColor = configPrefs.getString("timer_color", "#ffff9800").toAndroidColor(Color.parseColor("#FF9800"))
        val secondsColor = configPrefs.getString("seconds_color", "#ffffffff").toAndroidColor(Color.WHITE)
        val idleTimeColor = configPrefs.getString("idle_time_color", "#ffffffff").toAndroidColor(Color.WHITE)
        val showPanicLogo = configPrefs.getBoolean("show_panic_logo", true)

        // Draw WS Logo at the bottom
        if (showPanicLogo) {
            wsLogoPaint.alpha = if (isAmbient) 128 else 255
            val logoX = centerX - (scaledLogoBitmap.width / 2f)
            val logoY = bounds.bottom - 70f
            canvas.drawBitmap(scaledLogoBitmap, logoX, logoY, wsLogoPaint)
        }

        val dotRadius = 4.5f
        val dotSpacing = 13f
        val digitWidth = 5 * dotSpacing
        val digitHeight = 7 * dotSpacing

        // Draw more towards the right to match the image
        val startX = centerX + 10f
        val startYTop = centerY - digitHeight - 15f
        val startYBottom = centerY + 15f

        // Top Row (Hours) uses synced timer color.
        val hPaint = if (isAmbient) Paint(dotPaintWhite).apply { color = Color.GRAY } else Paint(dotPaintRed).apply { color = timerColor }
        drawDigit(canvas, hourStr[0] - '0', startX - digitWidth / 2 - 10f, startYTop, dotSpacing, dotRadius, hPaint)
        drawDigit(canvas, hourStr[1] - '0', startX + digitWidth / 2 + 10f, startYTop, dotSpacing, dotRadius, hPaint)

        // Bottom Row (Minutes) uses synced seconds/accent color.
        val mPaint = if (isAmbient) Paint(dotPaintWhite).apply { color = Color.GRAY } else Paint(dotPaintWhite).apply { color = secondsColor }
        drawDigit(canvas, minStr[0] - '0', startX - digitWidth / 2 - 10f, startYBottom, dotSpacing, dotRadius, mPaint)
        drawDigit(canvas, minStr[1] - '0', startX + digitWidth / 2 + 10f, startYBottom, dotSpacing, dotRadius, mPaint)

        if (!isAmbient) {
            val secondProgress = zonedDateTime.second / 60f
            val arcPaint = Paint().apply {
                color = idleTimeColor
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
                isAntiAlias = true
                alpha = 180
            }
            canvas.drawArc(
                bounds.left + 18f,
                bounds.top + 18f,
                bounds.right - 18f,
                bounds.bottom - 18f,
                45f,
                90f * secondProgress,
                false,
                arcPaint
            )
        }
    }

    private fun drawDigit(canvas: Canvas, digit: Int, x: Float, y: Float, spacing: Float, radius: Float, paint: Paint) {
        val pattern = DIGIT_PATTERNS[digit] ?: return
        for (row in 0 until 7) {
            for (col in 0 until 5) {
                if (pattern[row][col] == '*') {
                    canvas.drawCircle(x + col * spacing, y + row * spacing, radius, paint)
                }
            }
        }
    }

    companion object {
        val DIGIT_PATTERNS = mapOf(
            0 to listOf(" *** ", "*   *", "*   *", "*   *", "*   *", "*   *", " *** "),
            1 to listOf("  *  ", " **  ", "  *  ", "  *  ", "  *  ", "  *  ", " *** "),
            2 to listOf(" *** ", "*   *", "    *", "  ** ", " *   ", "*    ", "*****"),
            3 to listOf(" *** ", "*   *", "    *", "  ** ", "    *", "*   *", " *** "),
            4 to listOf("   * ", "  ** ", " * * ", "*  * ", "*****", "   * ", "   * "),
            5 to listOf("*****", "*    ", "**** ", "    *", "    *", "*   *", " *** "),
            6 to listOf(" *** ", "*    ", "*    ", "**** ", "*   *", "*   *", " *** "),
            7 to listOf("*****", "    *", "   * ", "  *  ", " *   ", "*    ", "*    "),
            8 to listOf(" *** ", "*   *", "*   *", " *** ", "*   *", "*   *", " *** "),
            9 to listOf(" *** ", "*   *", "*   *", " ****", "    *", "    *", " *** ")
        )
    }
}

private fun String?.toAndroidColor(fallback: Int): Int {
    if (this.isNullOrBlank()) return fallback
    return try {
        Color.parseColor(this)
    } catch (_: IllegalArgumentException) {
        fallback
    }
}
