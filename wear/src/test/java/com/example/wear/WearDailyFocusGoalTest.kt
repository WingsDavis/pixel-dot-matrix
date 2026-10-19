package com.example.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearDailyFocusGoalTest {
    @Test
    fun appliesPhoneSnapshotAndExpiresAtReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = DataMap().apply {
            putInt("schema_version", 1)
            putBoolean("enabled", true)
            putString("type", "SESSIONS")
            putInt("target_sessions", 6)
            putString("time_zone", "Africa/Accra")
            putInt("reset_hour", 4)
            putLong("day_start", 1000)
            putLong("day_end", 86_401_000)
            putLong("focused_seconds", 2700)
            putInt("completed_sessions", 3)
        }
        assertTrue(WearDailyFocusGoal.save(context, data))
        val applied = WearDailyFocusGoal.read(context, 2000)!!
        assertEquals("3/6x", applied.shortText)
        assertEquals(0.5f, applied.fraction)
        assertEquals("Africa/Accra", applied.settings.timeZoneId)
        assertNull(WearDailyFocusGoal.read(context, 86_401_000))

        data.putString("type", "MINUTES")
        data.putInt("target_minutes", 120)
        assertTrue(WearDailyFocusGoal.save(context, data))
        assertEquals("45/120m", WearDailyFocusGoal.read(context, 2000)?.shortText)

        data.putBoolean("enabled", false)
        assertTrue(WearDailyFocusGoal.save(context, data))
        assertNull(WearDailyFocusGoal.read(context, 2000))
    }

    @Test
    fun rejectsUnsupportedAndMalformedPayloads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(WearDailyFocusGoal.save(context, DataMap()))
        val invalid = DataMap().apply {
            putInt("schema_version", 1)
            putLong("day_start", 3000)
            putLong("day_end", 1000)
        }
        assertFalse(WearDailyFocusGoal.save(context, invalid))
    }
}
