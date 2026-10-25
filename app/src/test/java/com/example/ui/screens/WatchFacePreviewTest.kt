package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import java.io.File
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi", sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class WatchFacePreviewTest {
    @get:Rule val compose = createComposeRule()

    private var preview by mutableStateOf(PreviewConfig())
    private val hourRegion = Rect(0.55f, 0.25f, 0.86f, 0.46f)
    private val minuteRegion = Rect(0.55f, 0.51f, 0.86f, 0.73f)
    private val arcRegion = Rect(0.90f, 0.15f, 1f, 0.65f)
    private val leftRegion = Rect(0.09f, 0.30f, 0.49f, 0.76f)
    private val bottomRegion = Rect(0.15f, 0.79f, 0.90f, 0.96f)
    private val logoRegion = Rect(0.15f, 0.04f, 0.85f, 0.25f)

    @Test
    fun idleHourMinuteAndSecondColorsIndependentlyAffectPixels() {
        showPreview()
        val initial = PreviewConfig()
        val idle = capture("idle-orange-white-cyan", initial)
        assertColor("idle hour", idle, initial.hourColor, hourRegion)
        assertColor("idle minute", idle, initial.minuteColor, minuteRegion)
        assertColor("idle seconds arc", idle, initial.secondColor, arcRegion)

        val whiteHour = capture("idle-white-hour", initial.copy(hourColor = Color.White))
        assertChanged("hour color", idle, whiteHour, hourRegion)
        assertColor("white hour", whiteHour, Color.White, hourRegion)

        val orangeMinute = capture("idle-orange-minute", initial.copy(minuteColor = initial.hourColor))
        assertChanged("minute color", idle, orangeMinute, minuteRegion)
        assertColor("orange minute", orangeMinute, initial.hourColor, minuteRegion)

        val whiteArc = capture("idle-white-seconds", initial.copy(secondColor = Color.White))
        assertChanged("seconds color", idle, whiteArc, arcRegion)
        assertColor("white seconds arc", whiteArc, Color.White, arcRegion)
    }

    @Test
    fun activePhasesUseSessionColorsAndDifferFromIdle() {
        showPreview()
        val initial = PreviewConfig()
        val idle = capture("phases-idle", initial)
        val phases = listOf(
            "Focus" to Color(0xFF42A5F5),
            "Short Break" to Color(0xFFFFCA28),
            "Long Break" to Color(0xFF81C784),
            "Panic" to Color(0xFFEF5350)
        )
        for ((phase, color) in phases) {
            val active = capture("phase-${phase.lowercase().replace(' ', '-')}", initial.copy(previewState = phase))
            assertChanged("$phase hour digits", idle, active, hourRegion)
            assertChanged("$phase minute digits", idle, active, minuteRegion)
            assertColor("$phase hour", active, color, hourRegion)
            assertColor("$phase minute", active, color, minuteRegion)
        }
    }

    @Test
    fun customTextAndVisibleSlotModesChangeTheirOwnRegions() {
        showPreview()
        val initial = PreviewConfig(customText = "SHIP IT", dailyTargetText = "30 / 120 min")
        val hidden = capture("slots-hidden", initial)
        for (mode in listOf("custom_text", "phase", "heart_rate", "next_break", "daily_target")) {
            val visible = capture("left-$mode", initial.copy(leftSlotMode = mode))
            assertChanged("left slot $mode", hidden, visible, leftRegion)
        }
        for (mode in listOf("date", "phase", "timer", "battery", "daily_target")) {
            val visible = capture("bottom-$mode", initial.copy(bottomRightSlotMode = mode))
            assertChanged("bottom slot $mode", hidden, visible, bottomRegion)
        }
        val textConfig = initial.copy(leftSlotMode = "custom_text")
        val before = capture("custom-text-before", textConfig)
        val after = capture("custom-text-after", textConfig.copy(customText = "TAKE A BREAK"))
        assertChanged("custom text", before, after, leftRegion)
    }

    @Test
    fun dailyTargetTextUpdatesBothOwnedSlotsAndEmptyTextHidesIt() {
        showPreview()
        val hidden = capture("goal-slots-hidden", PreviewConfig())
        val goalConfig = PreviewConfig(leftSlotMode = "daily_target", bottomRightSlotMode = "daily_target")
        val empty = capture("goal-empty", goalConfig)
        assertArrayEquals("Empty left target must stay hidden", hidden.pixels(leftRegion), empty.pixels(leftRegion))
        assertArrayEquals("Empty bottom target must stay hidden", hidden.pixels(bottomRegion), empty.pixels(bottomRegion))

        val minutes = capture("goal-minutes", goalConfig.copy(dailyTargetText = "30 / 120 min"))
        val progress = capture("goal-progress", goalConfig.copy(dailyTargetText = "90 / 120 min"))
        val sessions = capture("goal-sessions", goalConfig.copy(dailyTargetText = "1 / 4 sessions"))
        for ((name, region) in listOf("left" to leftRegion, "bottom" to bottomRegion)) {
            assertChanged("$name target enabled", empty, minutes, region)
            assertChanged("$name target progress", minutes, progress, region)
            assertChanged("$name target type", progress, sessions, region)
        }
    }

    @Test
    fun logoVisibilityBitmapAndEachTransformAffectPixels() {
        showPreview()
        val initial = PreviewConfig()
        val hidden = capture("logo-hidden", initial)
        val builtIn = capture("logo-built-in", initial.copy(showPanicLogo = true))
        assertChanged("built-in logo visibility", hidden, builtIn, logoRegion)

        val logoConfig = initial.copy(showPanicLogo = true, logoBitmap = patternedLogo())
        val custom = capture("logo-custom", logoConfig)
        assertChanged("custom logo bitmap", builtIn, custom, logoRegion)
        val replaced = capture("logo-replaced", logoConfig.copy(logoBitmap = solidLogo(Color.Magenta)))
        assertChanged("replacement logo pixels", custom, replaced, logoRegion)
        val scaled = capture("logo-scaled", logoConfig.copy(logoScale = 1.4f))
        assertChanged("logo scale", custom, scaled, logoRegion)
        val movedX = capture("logo-offset-x", logoConfig.copy(logoOffsetX = 0.5f))
        assertChanged("logo horizontal offset", custom, movedX, logoRegion)
        val movedY = capture("logo-offset-y", logoConfig.copy(logoOffsetY = 0.5f))
        assertChanged("logo vertical offset", custom, movedY, logoRegion)
        val customHidden = capture("logo-custom-hidden", logoConfig.copy(showPanicLogo = false))
        assertArrayEquals("Hidden bitmap must not leak into preview", hidden.pixels(logoRegion), customHidden.pixels(logoRegion))
    }

    @Test
    fun ambientContainsOnlyDimClockRegardlessOfInteractiveConfiguration() {
        showPreview()
        val quiet = PreviewConfig(ambient = true)
        val loud = quiet.copy(
            previewState = "Focus",
            hourColor = Color.Magenta,
            minuteColor = Color.Green,
            secondColor = Color.Yellow,
            customText = "MUST NOT APPEAR",
            leftSlotMode = "custom_text",
            bottomRightSlotMode = "daily_target",
            dailyTargetText = "999 / 999 min",
            showPanicLogo = true,
            logoBitmap = solidLogo(Color.Cyan),
            logoScale = 1.5f,
            logoOffsetX = 0.5f,
            logoOffsetY = -0.5f
        )
        val variants = listOf(
            "ambient-custom-logo-text-goal" to loud,
            "ambient-built-in-logo-left-goal" to loud.copy(
                previewState = "Panic", logoBitmap = null, leftSlotMode = "daily_target",
                bottomRightSlotMode = "timer", dailyTargetText = "1 / 100 sessions"
            ),
            "ambient-phase-date" to loud.copy(
                previewState = "Short Break", leftSlotMode = "phase", bottomRightSlotMode = "date"
            )
        )

        // LocalTime.now() is real wall time; retry a minute rollover without changing production API.
        repeat(3) {
            val minute = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
            val clock = capture("ambient-clock-only", quiet)
            val changed = variants.map { (name, config) -> name to capture(name, config) }
            if (LocalTime.now().truncatedTo(ChronoUnit.MINUTES) != minute) return@repeat

            assertDimClockOnly(clock)
            for ((name, image) in changed) {
                assertArrayEquals("$name must not affect the ambient clock", clock.pixels(), image.pixels())
            }
            return
        }
        throw AssertionError("Could not capture ambient configurations within one clock minute")
    }

    private fun showPreview() {
        compose.setContent {
            MyApplicationTheme {
                val config = preview
                WatchFacePreview(
                    previewState = config.previewState,
                    hourColor = config.hourColor,
                    minuteColor = config.minuteColor,
                    secondColor = config.secondColor,
                    customText = config.customText,
                    leftSlotMode = config.leftSlotMode,
                    bottomRightSlotMode = config.bottomRightSlotMode,
                    showPanicLogo = config.showPanicLogo,
                    logoBitmap = config.logoBitmap,
                    logoScale = config.logoScale,
                    logoOffsetX = config.logoOffsetX,
                    logoOffsetY = config.logoOffsetY,
                    ambient = config.ambient,
                    dailyTargetText = config.dailyTargetText,
                    modifier = Modifier.size(286.dp).background(Color.Black).testTag("watch-face-preview")
                )
            }
        }
    }

    private fun capture(name: String, config: PreviewConfig): Bitmap {
        compose.runOnIdle { preview = config }
        val node = compose.onNodeWithTag("watch-face-preview").assertIsDisplayed()
        val file = File("build/reports/watchface-preview/$name.png").absoluteFile
        node.captureRoboImage(file = file, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return requireNotNull(BitmapFactory.decodeFile(file.absolutePath)).also { image ->
            assertEquals("$name must remain square", image.width, image.height)
            assertTrue("$name must render visible content, not just a dark face", image.pixels().count {
                (it ushr 16 and 255) > 32 || (it ushr 8 and 255) > 32 || (it and 255) > 32
            } > 100)
        }
    }

    private fun assertChanged(name: String, before: Bitmap, after: Bitmap, region: Rect) {
        assertEquals("$name must not resize the preview", before.width, after.width)
        assertEquals("$name must not resize the preview", before.height, after.height)
        assertFalse("$name must affect its preview pixels", before.pixels(region).contentEquals(after.pixels(region)))
    }

    private fun assertColor(name: String, image: Bitmap, color: Color, region: Rect) {
        assertTrue("$name must use $color", image.pixels(region).count { it == color.toArgb() } > 30)
    }

    private fun assertDimClockOnly(image: Bitmap) {
        val pixels = image.pixels()
        assertTrue("Ambient must contain only dim grayscale pixels", pixels.all {
            val red = it ushr 16 and 255
            red <= 128 && red == (it ushr 8 and 255) && red == (it and 255)
        })
        assertTrue("Ambient must be black outside the central clock", pixels.indices.all { index ->
            pixels[index] and 0x00FFFFFF == 0 ||
                (index % image.width in (image.width * 0.20f).toInt()..(image.width * 0.80f).toInt() &&
                    index / image.width in (image.height * 0.35f).toInt()..(image.height * 0.65f).toInt())
        })
        assertTrue("Ambient clock must leave most of the face black", pixels.count { it and 0x00FFFFFF != 0 } < pixels.size / 6)
    }

    private fun Bitmap.pixels(region: Rect = Rect(0f, 0f, 1f, 1f)): IntArray {
        val left = (width * region.left).toInt()
        val top = (height * region.top).toInt()
        val cropWidth = (width * region.right).toInt() - left
        val cropHeight = (height * region.bottom).toInt() - top
        return IntArray(cropWidth * cropHeight).also {
            getPixels(it, 0, cropWidth, left, top, cropWidth, cropHeight)
        }
    }

    private fun solidLogo(color: Color): ImageBitmap =
        Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color.toArgb()) }.asImageBitmap()

    private fun patternedLogo(): ImageBitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) {
            setPixel(x, y, if ((x / 3 + y / 3) % 2 == 0) Color.Cyan.toArgb() else Color.Black.toArgb())
        }
    }.asImageBitmap()

    private data class PreviewConfig(
        val previewState: String = "Idle",
        val hourColor: Color = Color(0xFFFF9800),
        val minuteColor: Color = Color.White,
        val secondColor: Color = Color.Cyan,
        val customText: String = "",
        val leftSlotMode: String = "hidden",
        val bottomRightSlotMode: String = "hidden",
        val showPanicLogo: Boolean = false,
        val logoBitmap: ImageBitmap? = null,
        val logoScale: Float = 1f,
        val logoOffsetX: Float = 0f,
        val logoOffsetY: Float = 0f,
        val ambient: Boolean = false,
        val dailyTargetText: String = ""
    )
}
