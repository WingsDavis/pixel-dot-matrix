package com.example.core.timer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import com.example.service.PomodoroTileService
import com.example.service.TimerForegroundService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerTileManifestTest {
    @Test
    fun tileBindingIsSystemProtectedAndTimerHostIsPrivate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val tile = manager.getServiceInfo(ComponentName(context, PomodoroTileService::class.java), PackageManager.GET_META_DATA)
        assertTrue(tile.exported)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE, tile.permission)
        assertTrue(tile.metaData.getBoolean(TileService.META_DATA_ACTIVE_TILE))
        assertTrue(tile.icon != 0)
        assertTrue(tile.loadLabel(manager).isNotBlank())
        @Suppress("DEPRECATION")
        val timer = manager.getServiceInfo(ComponentName(context, TimerForegroundService::class.java), 0)
        assertFalse(timer.exported)
        assertTrue(timer.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
    }
}
