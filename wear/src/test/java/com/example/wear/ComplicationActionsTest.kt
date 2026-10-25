package com.example.wear

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComplicationActionsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun destinationsHaveIndependentImmutableNavigationOnlyIntents() {
        val actions = listOf(ComplicationActions.openTimer(context), ComplicationActions.openDailyTarget(context),
            ComplicationActions.openPanic(context))
        assertEquals(3, actions.toSet().size)
        assertEquals(listOf(ComplicationActions.ACTION_OPEN_TIMER, ComplicationActions.ACTION_OPEN_DAILY_TARGET,
            ComplicationActions.ACTION_OPEN_PANIC), actions.map { shadowOf(it).savedIntent.action })
        actions.forEach {
            assertTrue(it.isImmutable)
            val intent = shadowOf(it).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertFalse(intent.hasExtra("TRIGGER_PANIC"))
            assertFalse(intent.hasExtra("FORCE_PANIC_UI"))
        }
        assertEquals(actions[1], ComplicationActions.openDailyTarget(context))
    }

    @Test fun repeatedTapsRouteToTheRightScreenWithoutTimerCommands() {
        assertEquals(0, ComplicationActions.pageForAction(ComplicationActions.ACTION_OPEN_TIMER, "FOCUS"))
        assertEquals(1, ComplicationActions.pageForAction(ComplicationActions.ACTION_OPEN_TIMER, "SHORT_BREAK"))
        assertEquals(2, ComplicationActions.pageForAction(ComplicationActions.ACTION_OPEN_TIMER, "LONG_BREAK"))
        assertEquals(3, ComplicationActions.pageForAction(ComplicationActions.ACTION_OPEN_TIMER, "PANIC_MODE"))
        repeat(2) { assertEquals(5, ComplicationActions.pageForAction(ComplicationActions.ACTION_OPEN_DAILY_TARGET, "FOCUS")) }
        assertEquals(3, ComplicationActions.pageForAction(ComplicationActions.ACTION_OPEN_PANIC, "FOCUS"))
        assertNull(ComplicationActions.pageForAction("unrecognized", "FOCUS"))
    }

    @Test fun legacyLogoLinkOnlyOpensPanicControls() {
        val controller = Robolectric.buildActivity(PanicTriggerActivity::class.java).create()
        val intent = shadowOf(controller.get()).nextStartedActivity
        assertEquals(ComplicationActions.ACTION_OPEN_PANIC, intent.action)
        assertFalse(intent.hasExtra("TRIGGER_PANIC"))
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun canceledNavigationKeepsDestinationUntilSuccessfulCompletion() {
        val navigation = ComplicationNavigation()
        navigation.request(ComplicationActions.ACTION_OPEN_DAILY_TARGET, "FOCUS")
        val first = navigation.version
        assertEquals(5, navigation.destination("FOCUS"))
        assertEquals(5, navigation.destination("LONG_BREAK"))
        navigation.request(ComplicationActions.ACTION_OPEN_PANIC, "LONG_BREAK")
        navigation.completed(first)
        assertEquals(3, navigation.destination("LONG_BREAK"))
        navigation.completed(navigation.version)
        assertEquals(2, navigation.destination("LONG_BREAK"))
        navigation.request(ComplicationActions.ACTION_OPEN_DAILY_TARGET, "LONG_BREAK")
        assertTrue(navigation.version > first)
        assertEquals(5, navigation.destination("LONG_BREAK"))
    }

    @Test fun manifestRegistersProtectedProvidersWithBoundedFallbackUpdates() {
        val providers = listOf(CustomTextComplicationService::class.java, BottomRightComplicationService::class.java,
            PomodoroProgressComplicationService::class.java, DailyTargetComplicationService::class.java, LogoComplicationService::class.java)
        providers.forEach { provider ->
            val info = context.packageManager.getServiceInfo(android.content.ComponentName(context, provider), PackageManager.GET_META_DATA)
            assertTrue(info.exported)
            assertEquals("com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER", info.permission)
            assertNotEquals(0, info.icon)
            val period = info.metaData.getInt("android.support.wearable.complications.UPDATE_PERIOD_SECONDS")
            assertEquals(if (provider == LogoComplicationService::class.java) 0 else 300, period)
        }
    }
}
