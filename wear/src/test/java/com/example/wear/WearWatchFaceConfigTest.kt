package com.example.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.DataMap
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearWatchFaceConfigTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("watchface_config_prefs", Context.MODE_PRIVATE)
    private lateinit var controller: ServiceController<WearSyncService>
    private val service get() = controller.get()
    @Before fun prepare() {
        prefs.edit().clear().commit()
        controller = Robolectric.buildService(WearSyncService::class.java).create()
    }
    @After fun finish() { controller.destroy() }

    @Test fun completeConfigurationCommitsOnceAndDuplicateIsIdempotent() {
        val data = config("a", 100)
        assertTrue(service.persistWatchFaceConfig(data))
        val saved = prefs.all.toMap()
        assertFalse(service.persistWatchFaceConfig(data))
        assertEquals(saved, prefs.all)
        assertEquals("ALL TEXT", prefs.getString("custom_text", null))
        assertEquals("daily_target", prefs.getString("left_slot_mode", null))
        assertEquals("timer", prefs.getString("bottom_right_slot_mode", null))
        assertFalse(prefs.getBoolean("show_panic_logo", true))
    }

    @Test fun staleLegacyUnsupportedAndInvalidConfigurationsDoNotReplaceThePreset() {
        service.persistWatchFaceConfig(config("new", 100))
        val saved = prefs.all.toMap()
        val invalid = listOf(config("old", 99), config("future", 101).apply { putInt("schema_version", 3) },
            config("legacy", 101).apply { putInt("schema_version", 1) },
            config("bad", 101).apply { putString("timer_color", "not-a-color") },
            config("bad-mode", 101).apply { putString("left_slot_mode", "unknown") },
            DataMap().apply { putInt("schema_version", 2) })
        invalid.forEach {
            assertThrows(Exception::class.java) { service.persistWatchFaceConfig(it) }
            assertEquals(saved, prefs.all)
        }
        assertTrue(service.persistWatchFaceConfig(config("next", 101)))
    }

    @Test fun failedCommitRestoresThePreviousInMemoryPreferences() {
        service.persistWatchFaceConfig(config("old", 100))
        val saved = prefs.all.toMap()
        val broken = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name != "edit") method.invoke(prefs, *(args ?: emptyArray())) else {
                val editor = prefs.edit()
                lateinit var proxy: SharedPreferences.Editor
                proxy = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                    val result = editMethod.invoke(editor, *(editArgs ?: emptyArray()))
                    when { editMethod.name == "commit" -> false; result is SharedPreferences.Editor -> proxy; else -> result }
                } as SharedPreferences.Editor
                proxy
            }
        } as SharedPreferences
        assertThrows(IllegalStateException::class.java) { service.persistWatchFaceConfig(config("new", 101), broken) }
        assertEquals(saved, prefs.all)
    }

    private fun config(id: String, timestamp: Long) = DataMap().apply {
        putInt("schema_version", 2); putString("revision", id); putLong("timestamp", timestamp)
        putString("timer_color", "#ff42a5f5"); putString("seconds_color", "#ffef5350")
        putString("idle_time_color", "#ffffffff"); putString("custom_text", "ALL TEXT")
        putString("left_slot_mode", "daily_target"); putString("bottom_right_slot_mode", "timer")
        putBoolean("show_panic_logo", false); putString("ambient_style", "minimal"); putString("theme_preset", "work")
    }
}
