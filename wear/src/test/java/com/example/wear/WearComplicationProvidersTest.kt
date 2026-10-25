package com.example.wear

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearComplicationProvidersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sync get() = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
    private val config get() = context.getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
    private val goal get() = context.getSharedPreferences("daily_focus_goal", Context.MODE_PRIVATE)
    private val providers = listOf(
        CustomTextComplicationService::class.java to setOf(ComplicationType.SHORT_TEXT, ComplicationType.LONG_TEXT),
        BottomRightComplicationService::class.java to setOf(ComplicationType.SHORT_TEXT),
        PomodoroProgressComplicationService::class.java to setOf(ComplicationType.RANGED_VALUE),
        DailyTargetComplicationService::class.java to setOf(ComplicationType.SHORT_TEXT),
        LogoComplicationService::class.java to setOf(ComplicationType.SMALL_IMAGE)
    )

    @Before
    fun reset() {
        sync.edit().clear().commit()
        config.edit().clear().putString("custom_text", "HELLO").commit()
        goal.edit().clear().commit()
        File(context.filesDir, WearSyncService.LOGO_FILENAME).delete()
        seedGoal()
    }

    @Test
    fun everyDeclaredTypeHasMatchingRuntimeAndPreviewDataAndNavigation() {
        for ((providerClass, types) in providers) {
            val provider = create(providerClass)
            val tap = when (providerClass) {
                DailyTargetComplicationService::class.java -> ComplicationActions.openDailyTarget(context)
                LogoComplicationService::class.java -> ComplicationActions.openPanic(context)
                else -> ComplicationActions.openTimer(context)
            }
            for (type in types) {
                val preview = provider.getPreviewData(type)!!
                val runtime = request(provider, type)!!
                assertEquals(providerClass.simpleName, type, preview.type)
                assertEquals(providerClass.simpleName, type, runtime.type)
                assertTap(preview, tap)
                assertTap(runtime, tap)
            }
        }
    }

    @Test
    fun unsupportedTypesAreRejectedEvenWhenContentIsHidden() {
        config.edit().putString("left_slot_mode", "hidden").putString("bottom_right_slot_mode", "hidden")
            .putBoolean("show_panic_logo", false).commit()
        goal.edit().putBoolean("enabled", false).commit()
        for ((providerClass, types) in providers) {
            val provider = create(providerClass)
            for (type in ComplicationType.values().filterNot { it in types }) {
                assertNull("${providerClass.simpleName} preview $type", provider.getPreviewData(type))
                assertNull("${providerClass.simpleName} runtime $type", request(provider, type))
            }
        }
    }

    @Test
    fun everyDailyTargetModeExpiresAtResetAndOpensTarget() {
        config.edit().putString("left_slot_mode", "daily_target").putString("bottom_right_slot_mode", "daily_target").commit()
        val now = System.currentTimeMillis()
        val start = now - 60_000
        val end = now + 60_000
        seedGoal(start = start, end = end)
        val targets = listOf(
            create(CustomTextComplicationService::class.java) to ComplicationType.SHORT_TEXT,
            create(CustomTextComplicationService::class.java) to ComplicationType.LONG_TEXT,
            create(BottomRightComplicationService::class.java) to ComplicationType.SHORT_TEXT,
            create(DailyTargetComplicationService::class.java) to ComplicationType.SHORT_TEXT
        )
        for ((provider, type) in targets) {
            val data = request(provider, type)!!
            assertEquals("45/120m", text(data))
            assertEquals(Instant.ofEpochMilli(start), data.validTimeRange.startDateTimeMillis)
            assertEquals(Instant.ofEpochMilli(end - 1), data.validTimeRange.endDateTimeMillis)
            assertFalse(Instant.ofEpochMilli(end) in data.validTimeRange)
            assertTap(data, ComplicationActions.openDailyTarget(context))
        }
        for ((enabled, expired) in listOf(false to false, true to true)) {
            seedGoal(enabled, start, if (expired) now - 1 else end)
            for ((provider, type) in targets) assertTrue(request(provider, type) is NoDataComplicationData)
        }
    }

    @Test
    fun missingHeartRateAndStreakNeverInventMeasurements() {
        val provider = create(CustomTextComplicationService::class.java)
        for (type in listOf(ComplicationType.SHORT_TEXT, ComplicationType.LONG_TEXT)) {
            for (mode in listOf("heart_rate", "streak")) {
                config.edit().putString("left_slot_mode", mode).commit()
                assertTrue(request(provider, type) is NoDataComplicationData)
            }
            config.edit().putString("left_slot_mode", "heart_rate").commit()
            sync.edit().putInt("current_hr", -1).commit()
            assertTrue(request(provider, type) is NoDataComplicationData)
            sync.edit().putInt("current_hr", 72).commit()
            sync.edit().remove("current_hr_at").commit()
            assertTrue(request(provider, type) is NoDataComplicationData)
            sync.edit().putLong("current_hr_at", System.currentTimeMillis()).commit()
            assertEquals("72 BPM", text(request(provider, type)!!))
            sync.edit().remove("current_hr").commit()
        }
    }

    @Test
    fun heartRateExpiresWithoutAnotherRequestAndRejectsStaleOrFutureSamples() {
        config.edit().putString("left_slot_mode", "heart_rate").commit()
        val provider = create(CustomTextComplicationService::class.java)
        val sampledAt = System.currentTimeMillis() - 1000L
        sync.edit().putInt("current_hr", 72).putLong("current_hr_at", sampledAt).commit()
        for (type in listOf(ComplicationType.SHORT_TEXT, ComplicationType.LONG_TEXT)) {
            val data = request(provider, type)!!
            assertEquals(type, data.type)
            assertEquals(Instant.ofEpochMilli(sampledAt), data.validTimeRange.startDateTimeMillis)
            assertEquals(Instant.ofEpochMilli(sampledAt + 299_999L), data.validTimeRange.endDateTimeMillis)
            assertFalse(Instant.ofEpochMilli(sampledAt + 300_000L) in data.validTimeRange)
            assertTap(data, ComplicationActions.openTimer(context))
        }
        for (invalid in listOf(0L, System.currentTimeMillis() - 300_000L, System.currentTimeMillis() + 60_000L)) {
            sync.edit().putLong("current_hr_at", invalid).commit()
            assertTrue(request(provider) is NoDataComplicationData)
        }
    }

    @Test
    fun batteryUsesOnlyAValidLocalMeasurement() {
        config.edit().putString("bottom_right_slot_mode", "battery").commit()
        val provider = create(BottomRightComplicationService::class.java)
        val battery = shadowOf(context.getSystemService(BatteryManager::class.java))
        for (invalid in listOf(Int.MIN_VALUE, -1, 101)) {
            battery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, invalid)
            assertTrue(request(provider) is NoDataComplicationData)
        }
        for (valid in listOf(0, 64, 100)) {
            battery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, valid)
            assertEquals("$valid%", text(request(provider)!!))
        }
    }

    @Test
    fun countdownModesUsePersistedOfflineAnchorAndDoNotCountUpAfterExpiry() {
        val custom = create(CustomTextComplicationService::class.java)
        val bottom = create(BottomRightComplicationService::class.java)
        val now = System.currentTimeMillis()
        sync.edit().putString("state", "FOCUS").putInt("seconds_remaining", 600)
            .putLong("timer_updated_at", now).putBoolean("is_running", true).commit()
        config.edit().putString("left_slot_mode", "next_break").putString("bottom_right_slot_mode", "timer").commit()
        val original = sync.all
        for ((provider, type) in listOf(
            custom to ComplicationType.SHORT_TEXT, custom to ComplicationType.LONG_TEXT,
            bottom to ComplicationType.SHORT_TEXT
        )) {
            val data = request(provider, type)!!
            val prefix = if (provider === custom) {
                if (type == ComplicationType.LONG_TEXT) "BREAK IN " else "B "
            } else ""
            assertEquals("${prefix}10:00", text(data, Instant.ofEpochMilli(now)))
            assertEquals("${prefix}09:00", text(data, Instant.ofEpochMilli(now + 60_000)))
            assertEquals("${prefix}00:00", text(data, Instant.ofEpochMilli(now + 900_000)))
            assertTrue(data.getNextChangeInstant(Instant.ofEpochMilli(now)) < Instant.ofEpochMilli(now + 60_000))
            assertTap(data, ComplicationActions.openTimer(context))
        }
        assertEquals(original, sync.all)
        assertEquals("BREAK", (request(custom) as ShortTextComplicationData).title!!.getTextAt(context.resources, Instant.now()).toString())
        sync.edit().putString("state", "SHORT_BREAK").putInt("seconds_remaining", 240).commit()
        assertEquals("FOCUS", (request(custom) as ShortTextComplicationData).title!!.getTextAt(context.resources, Instant.now()).toString())
        sync.edit().putBoolean("is_running", false).commit()
        assertEquals("F 04:00", text(request(custom)!!))
        assertEquals("PAUSED", (request(custom) as ShortTextComplicationData).title!!.getTextAt(context.resources, Instant.now()).toString())
    }

    @Test
    fun dateTextUpdatesWithoutAnotherProviderRequest() {
        val data = request(create(BottomRightComplicationService::class.java))!!
        val now = Instant.now()
        assertNotEquals(text(data, now), text(data, now.plusSeconds(86_400)))
        assertTrue(data.getNextChangeInstant(now) < now.plusSeconds(86_401))
    }

    @Test
    fun progressPreservesIdleSentinelPhaseMaximumAndDynamicCountdown() {
        val provider = create(PomodoroProgressComplicationService::class.java)
        for ((state, key, duration) in listOf(
            Triple("FOCUS", "focus_duration", 1800),
            Triple("SHORT_BREAK", "short_break_duration", 420),
            Triple("LONG_BREAK", "long_break_duration", 1200)
        )) {
            sync.edit().putString("state", state).putInt(key, duration).putInt("seconds_remaining", Int.MAX_VALUE)
                .putBoolean("is_running", false).commit()
            val idle = request(provider, ComplicationType.RANGED_VALUE) as RangedValueComplicationData
            assertEquals(-1f, idle.value, 0f)
            assertEquals(-1f, idle.min, 0f)
            assertEquals(duration.toFloat(), idle.max, 0f)
            assertNull(idle.dynamicValue)
            sync.edit().putBoolean("is_running", true).putLong("timer_updated_at", System.currentTimeMillis()).commit()
            val running = request(provider, ComplicationType.RANGED_VALUE) as RangedValueComplicationData
            assertTrue(running.value in 0f..duration.toFloat())
            assertEquals(duration.toFloat(), running.max, 0f)
            assertNotNull(running.dynamicValue)
            assertTap(running, ComplicationActions.openTimer(context))
        }
        sync.edit().putLong("timer_updated_at", 1L).commit()
        val expired = request(provider, ComplicationType.RANGED_VALUE) as RangedValueComplicationData
        assertEquals(-1f, expired.value, 0f)
        assertNull(expired.dynamicValue)
    }

    @Test
    @Config(sdk = [28])
    fun oldSystemsKeepBoundedStaticProgressFallback() {
        sync.edit().putBoolean("is_running", true).putInt("seconds_remaining", 600)
            .putLong("timer_updated_at", System.currentTimeMillis()).commit()
        val data = request(create(PomodoroProgressComplicationService::class.java), ComplicationType.RANGED_VALUE) as RangedValueComplicationData
        assertTrue(data.value in 0f..600f)
        assertNull(data.dynamicValue)
    }

    @Test
    fun panicContentNavigatesWithoutIssuingATimerCommand() {
        sync.edit().putString("state", "PANIC_MODE").commit()
        config.edit().putString("left_slot_mode", "phase").putString("bottom_right_slot_mode", "timer").commit()
        val original = sync.all
        for (data in listOf(
            request(create(CustomTextComplicationService::class.java))!!,
            request(create(BottomRightComplicationService::class.java))!!,
            request(create(PomodoroProgressComplicationService::class.java), ComplicationType.RANGED_VALUE)!!,
            request(create(LogoComplicationService::class.java), ComplicationType.SMALL_IMAGE)!!
        )) assertTap(data, ComplicationActions.openPanic(context))
        assertEquals(original, sync.all)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun corruptLogoFallsBackAndHiddenLogoReturnsTransparentSmallImage() {
        val provider = create(LogoComplicationService::class.java)
        File(context.filesDir, WearSyncService.LOGO_FILENAME).writeText("not an image")
        for (data in listOf(provider.getPreviewData(ComplicationType.SMALL_IMAGE)!!, request(provider, ComplicationType.SMALL_IMAGE)!!)) {
            val icon = (data as SmallImageComplicationData).smallImage.image
            assertEquals(Icon.TYPE_RESOURCE, icon.type)
            assertEquals(R.drawable.ws_logo, icon.resId)
            assertTap(data, ComplicationActions.openPanic(context))
        }
        config.edit().putBoolean("show_panic_logo", false).commit()
        val hidden = request(provider, ComplicationType.SMALL_IMAGE) as SmallImageComplicationData
        assertEquals(ComplicationType.SMALL_IMAGE, hidden.type)
        assertNull(hidden.tapAction)
        val icon = hidden.smallImage.image
        assertEquals(Icon.TYPE_BITMAP, icon.type)
        val bitmap = (icon.loadDrawable(provider) as android.graphics.drawable.BitmapDrawable).bitmap
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
        assertEquals(android.graphics.Bitmap.Config.ARGB_8888, bitmap.config)
        assertEquals(android.graphics.Color.TRANSPARENT, bitmap.getPixel(0, 0))
    }

    @Test
    fun emptyAndUnknownModesDoNotSelectAnotherProvider() {
        val original = config.all
        val custom = create(CustomTextComplicationService::class.java)
        val bottom = create(BottomRightComplicationService::class.java)
        for (mode in listOf("hidden", "third_party", "unknown")) {
            config.edit().putString("left_slot_mode", mode).putString("bottom_right_slot_mode", mode).commit()
            val configured = config.all
            assertTrue(request(custom) is NoDataComplicationData)
            assertTrue(request(bottom) is NoDataComplicationData)
            assertEquals(configured, config.all)
        }
        config.edit().putString("left_slot_mode", "custom_text").putString("custom_text", "").commit()
        assertTrue(request(custom) is NoDataComplicationData)
        assertEquals(original["show_panic_logo"], config.all["show_panic_logo"])
    }

    private fun seedGoal(enabled: Boolean = true, start: Long = System.currentTimeMillis() - 60_000, end: Long = System.currentTimeMillis() + 86_400_000) {
        goal.edit().putBoolean("enabled", enabled).putString("type", "MINUTES")
            .putInt("target_minutes", 120).putLong("focused_seconds", 2700)
            .putLong("day_start", start).putLong("day_end", end).commit()
    }

    private fun <T : SuspendingComplicationDataSourceService> create(type: Class<T>): T =
        Robolectric.buildService(type).create().get()

    private fun request(provider: SuspendingComplicationDataSourceService, type: ComplicationType = ComplicationType.SHORT_TEXT): ComplicationData? =
        runBlocking { provider.onComplicationRequest(ComplicationRequest(103, type, false)) }

    private fun text(data: ComplicationData, at: Instant = Instant.now()): String = when (data) {
        is ShortTextComplicationData -> data.text.getTextAt(context.resources, at).toString()
        is LongTextComplicationData -> data.text.getTextAt(context.resources, at).toString()
        else -> error("Not text: ${data.type}")
    }

    private fun assertTap(data: ComplicationData, expected: PendingIntent) {
        assertEquals(expected, data.tapAction)
        val intent = shadowOf(data.tapAction!!).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertFalse(intent.getBooleanExtra("FORCE_PANIC_UI", false))
        assertFalse(intent.getBooleanExtra("TRIGGER_FRUSTRATION", false))
    }
}
