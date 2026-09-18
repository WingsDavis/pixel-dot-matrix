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

class ClassicWatchFaceService : WatchFaceService() {
    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = ClassicRenderer(
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

class ClassicRenderer(
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

    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textSize = 80f
        textAlign = Paint.Align.CENTER
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

        drawClassicTickMark(canvas, bounds, hour, minute, isAmbient)
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {}

    private fun drawClassicTickMark(canvas: Canvas, bounds: Rect, hour: Int, minute: Int, isAmbient: Boolean) {
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val radius = bounds.width() / 2f - 20f

        // Draw WS Logo at the bottom
        wsLogoPaint.alpha = if (isAmbient) 128 else 255
        val logoX = centerX - (scaledLogoBitmap.width / 2f)
        val logoY = bounds.bottom - 70f
        canvas.drawBitmap(scaledLogoBitmap, logoX, logoY, wsLogoPaint)

        // Draw 60 tick marks
        val tickPaint = Paint().apply {
            color = if (isAmbient) Color.DKGRAY else Color.WHITE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        for (i in 0 until 60) {
            val angle = Math.toRadians((i * 6).toDouble() - 90)
            val innerRadius = radius - 15f
            val startX = centerX + innerRadius * Math.cos(angle).toFloat()
            val startY = centerY + innerRadius * Math.sin(angle).toFloat()
            val endX = centerX + radius * Math.cos(angle).toFloat()
            val endY = centerY + radius * Math.sin(angle).toFloat()

            // Highlight ticks up to current minute
            if (!isAmbient && i <= minute) {
                tickPaint.color = Color.parseColor("#E53935") // Red
            } else {
                tickPaint.color = Color.DKGRAY
            }

            canvas.drawLine(startX, startY, endX, endY, tickPaint)
        }

        // Draw Time Text
        textPaint.color = if (isAmbient) Color.GRAY else Color.WHITE
        val timeString = String.format("%02d:%02d", hour, minute)
        canvas.drawText(timeString, centerX, centerY + (textPaint.textSize / 3), textPaint)
    }
}
