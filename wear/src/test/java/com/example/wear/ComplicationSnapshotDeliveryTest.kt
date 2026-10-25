package com.example.wear

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComplicationSnapshotDeliveryTest {
    @Test fun restoringAnOfflineCompletedFocusPersistsThePausedBreakAndRefreshesSlots() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().clear().putString("state", "FOCUS").putBoolean("is_running", true)
            .putInt("seconds_remaining", 1).putLong("timer_updated_at", now - 61_000)
            .putInt("focus_duration", 60).putInt("short_break_duration", 60)
            .putBoolean("wear_authority", true).putLong("wear_lease_expires_at", now + 300_000)
            .putLong("timer_revision", 10).commit()
        ComplicationUpdates.clearForTest()
        val before = shadowOf(context).broadcastIntents.size
        Robolectric.buildActivity(MainActivity::class.java).get().restoreTimerState()
        assertEquals("SHORT_BREAK", prefs.getString("state", null))
        assertEquals(60, prefs.getInt("seconds_remaining", 0))
        assertFalse(prefs.getBoolean("is_running", true))
        assertEquals(1, prefs.getInt("completed_focus_sessions", 0))
        assertTrue(prefs.getLong("timer_revision", 0) > 10)
        assertEquals(3, shadowOf(context).broadcastIntents.drop(before).count {
            it.action == ComplicationDataSourceUpdateRequester.ACTION_REQUEST_UPDATE_ALL
        })
    }

    @Test fun restoringExpiredPhoneTimerPersistsIdleWithoutClaimingAuthority() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("state", "FOCUS").putBoolean("is_running", true)
            .putInt("seconds_remaining", 30).putLong("timer_updated_at", System.currentTimeMillis() - 60_000)
            .putBoolean("wear_authority", false).putLong("timer_revision", 10).commit()
        ComplicationUpdates.clearForTest()
        Robolectric.buildActivity(MainActivity::class.java).get().restoreTimerState()
        assertFalse(prefs.getBoolean("is_running", true))
        assertEquals(0, prefs.getInt("seconds_remaining", -1))
        assertFalse(prefs.getBoolean("wear_authority", true))
        assertEquals(10L, prefs.getLong("timer_revision", -1))
    }

    @Test fun staleAndDuplicateSnapshotsCannotRegressProviderDataOrRepeatRefreshes() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ComplicationUpdates.clearForTest()
        val service = Robolectric.buildService(WearSyncService::class.java).create()
        try {
            val data = DataMap().apply {
                putString("state", "SHORT_BREAK"); putLong("timer_revision", 20)
                putInt("seconds_remaining", 240); putBoolean("is_running", true)
                putLong("timestamp", 1000)
            }
            assertTrue(service.get().applyTimerState(data))
            val saved = prefs.all
            val requests = shadowOf(context).broadcastIntents.count {
                it.action == ComplicationDataSourceUpdateRequester.ACTION_REQUEST_UPDATE_ALL
            }
            assertTrue(service.get().applyTimerState(data))
            data.putLong("timer_revision", 19)
            data.putString("state", "FOCUS")
            assertFalse(service.get().applyTimerState(data))
            data.putLong("timer_revision", 21)
            data.putString("state", "INVALID")
            assertFalse(service.get().applyTimerState(data))
            assertEquals(saved, prefs.all)
            assertEquals(requests, shadowOf(context).broadcastIntents.count {
                it.action == ComplicationDataSourceUpdateRequester.ACTION_REQUEST_UPDATE_ALL
            })
        } finally { service.destroy() }
    }
}
