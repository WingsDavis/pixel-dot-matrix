package com.example.wear

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComplicationUpdatesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
    private val requests get() = shadowOf(context).broadcastIntents.filter {
        it.action == ComplicationDataSourceUpdateRequester.ACTION_REQUEST_UPDATE_ALL
    }

    @Before fun prepare() {
        ComplicationUpdates.clearForTest()
        listOf("pomodoro_sync_prefs", "watchface_config_prefs", "daily_focus_goal").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, WearSyncService.LOGO_FILENAME).delete()
    }

    @Test fun timerUpdatesEveryTimerSlotButNotDuplicateOrElapsedTicks() {
        prefs.edit().putString("state", "FOCUS").putBoolean("is_running", true)
            .putInt("seconds_remaining", 600).putLong("timer_updated_at", 100_000).commit()
        val before = requests.size
        ComplicationUpdates.timer(context)
        assertEquals(3, requests.size - before)
        assertEquals(setOf(PomodoroProgressComplicationService::class.java.name,
            CustomTextComplicationService::class.java.name, BottomRightComplicationService::class.java.name),
            providerNames(requests.drop(before)))
        ComplicationUpdates.timer(context)
        prefs.edit().putInt("seconds_remaining", 599).putLong("timer_updated_at", 101_000)
            .putString("current_task", "Unrelated task").commit()
        ComplicationUpdates.timer(context)
        assertEquals(3, requests.size - before)
        prefs.edit().putBoolean("is_running", false).commit()
        ComplicationUpdates.timer(context)
        assertEquals(6, requests.size - before)
    }

    @Test fun goalChangesUpdateDedicatedAndBothConfigurableSlots() {
        val before = requests.size
        ComplicationUpdates.goal(context)
        assertEquals(setOf(DailyTargetComplicationService::class.java.name,
            CustomTextComplicationService::class.java.name, BottomRightComplicationService::class.java.name),
            providerNames(requests.drop(before)))
        ComplicationUpdates.goal(context)
        assertEquals(3, requests.size - before)
        context.getSharedPreferences("daily_focus_goal", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putInt("completed_sessions", 1).commit()
        ComplicationUpdates.goal(context)
        assertEquals(6, requests.size - before)
    }

    @Test fun configurationRefreshIncludesEveryProviderWithoutRevisionOnlyRepeats() {
        val config = context.getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
        config.edit().putString("left_slot_mode", "daily_target").commit()
        val before = requests.size
        ComplicationUpdates.config(context)
        assertEquals(5, requests.size - before)
        config.edit().putString("revision", "different-envelope").putLong("timestamp", 123).commit()
        ComplicationUpdates.config(context)
        assertEquals(5, requests.size - before)
        config.edit().putBoolean("show_panic_logo", false).commit()
        ComplicationUpdates.config(context)
        assertEquals(10, requests.size - before)
    }

    @Test fun logoResetAndCustomTextRefreshOnlyTheirProviders() {
        val before = requests.size
        ComplicationUpdates.logo(context)
        File(context.filesDir, WearSyncService.LOGO_FILENAME).writeText("test-image")
        ComplicationUpdates.logo(context)
        File(context.filesDir, WearSyncService.LOGO_FILENAME).delete()
        ComplicationUpdates.logo(context)
        assertEquals(3, requests.size - before)
        assertEquals(setOf(LogoComplicationService::class.java.name), providerNames(requests.drop(before)))
        prefs.edit().putString("custom_text", "Changed").commit()
        ComplicationUpdates.text(context)
        ComplicationUpdates.text(context)
        assertEquals(4, requests.size - before)
    }

    @Test fun heartRateRefreshIsBoundedToFiveMinutes() {
        val before = requests.size
        prefs.edit().putInt("current_hr", 80).putLong("current_hr_at", 1L).commit()
        ComplicationUpdates.heartRate(context, 0)
        prefs.edit().putInt("current_hr", 81).putLong("current_hr_at", 1001L).commit()
        ComplicationUpdates.heartRate(context, 1000)
        assertEquals(1, requests.size - before)
        ComplicationUpdates.heartRate(context, 300_000)
        assertEquals(2, requests.size - before)
    }

    @Test fun sameBpmWithNewTimestampRefreshesAfterFiveMinutes() {
        val before = requests.size
        val sampledAt = System.currentTimeMillis()
        prefs.edit().putInt("current_hr", 80).putLong("current_hr_at", sampledAt).commit()
        ComplicationUpdates.heartRate(context, 0)
        prefs.edit().putLong("current_hr_at", sampledAt + 299_999L).commit()
        ComplicationUpdates.heartRate(context, 299_999)
        assertEquals(1, requests.size - before)
        prefs.edit().putLong("current_hr_at", sampledAt + 300_000L).commit()
        ComplicationUpdates.heartRate(context, 300_000)
        assertEquals(2, requests.size - before)
        assertEquals(setOf(CustomTextComplicationService::class.java.name), providerNames(requests.drop(before)))
    }

    @Test fun repeatedExpiredSampleDoesNotDelayTheNextFreshSample() {
        val before = requests.size
        prefs.edit().putInt("current_hr", 80)
            .putLong("current_hr_at", System.currentTimeMillis() - 1_000_000L).commit()
        ComplicationUpdates.heartRate(context, 0)
        for (elapsed in listOf(300_000L, 450_000L, 600_000L)) {
            ComplicationUpdates.heartRate(context, elapsed)
        }
        assertEquals(1, requests.size - before)
        prefs.edit().putLong("current_hr_at", System.currentTimeMillis()).commit()
        ComplicationUpdates.heartRate(context, 600_001)
        assertEquals(2, requests.size - before)
    }

    @Test fun heartRateRequiresAPositiveSampleTimestampWithoutConsumingThrottle() {
        val before = requests.size
        prefs.edit().putInt("current_hr", 80).commit()
        ComplicationUpdates.heartRate(context, 0)
        for (timestamp in listOf(0L, -1L)) {
            prefs.edit().putLong("current_hr_at", timestamp).commit()
            ComplicationUpdates.heartRate(context, 1000)
        }
        assertEquals(0, requests.size - before)
        prefs.edit().putLong("current_hr_at", System.currentTimeMillis()).commit()
        ComplicationUpdates.heartRate(context, 1001)
        assertEquals(1, requests.size - before)
    }

    @Suppress("DEPRECATION")
    private fun providerNames(intents: List<android.content.Intent>) = intents.map {
        it.getParcelableExtra<ComponentName>(ComplicationDataSourceUpdateRequester.EXTRA_PROVIDER_COMPONENT)!!.className
    }.toSet()
}
