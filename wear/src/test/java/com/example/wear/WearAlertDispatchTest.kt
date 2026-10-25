package com.example.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearAlertDispatchTest {
    @Before fun prepare() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("wear_alert_dispatch", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun envelopeIsClaimedOnceAcrossListenerRecreationButNewEventsAreNotLost() {
        var alerts = 0
        val first = Robolectric.buildService(WearSyncService::class.java).create()
        assertTrue(first.get().dispatchAlert("panic", "event-a", 100, 1) { alerts++; WearAlertAcceptance.accept(it) })
        first.destroy()
        val recreated = Robolectric.buildService(WearSyncService::class.java).create()
        try {
            assertFalse(recreated.get().dispatchAlert("panic", "event-a", 200, 1) { alerts++ })
            assertTrue(recreated.get().dispatchAlert("panic", "event-b", 201, 1) { alerts++; WearAlertAcceptance.accept(it) })
            assertEquals(2, alerts)
        } finally { recreated.destroy() }
    }

    @Test fun rawLegacyAlertsDebounceWithoutDroppingTheFirstBootOrDifferentKinds() {
        var alerts = 0
        val service = Robolectric.buildService(WearSyncService::class.java).create()
        try {
            assertTrue(service.get().dispatchAlert("panic", null, 0, 1) { alerts++; WearAlertAcceptance.accept(it) })
            assertFalse(service.get().dispatchAlert("panic", null, 100, 1) { alerts++ })
            assertTrue(service.get().dispatchAlert("frustration", null, 100, 1) { alerts++; WearAlertAcceptance.accept(it) })
            assertTrue(service.get().dispatchAlert("panic", null, 1500, 1) { alerts++; WearAlertAcceptance.accept(it) })
            assertTrue(service.get().dispatchAlert("panic", null, 0, 2) { alerts++; WearAlertAcceptance.accept(it) })
            assertEquals(4, alerts)
        } finally { service.destroy() }
    }

    @Test fun deniedActivityLaunchDoesNotClaimTheEventOrDiscardItsRetry() {
        val service = Robolectric.buildService(WearSyncService::class.java).create()
        try {
            assertThrows(IllegalStateException::class.java) {
                service.get().dispatchAlert("panic", "event-a", 100, 1) { error("Launch denied") }
            }
            assertTrue(service.get().dispatchAlert("panic", "event-a", 101, 1) { WearAlertAcceptance.accept(it) })
        } finally { service.destroy() }
    }

    @Test fun silentBackgroundLaunchFailureRemainsRetryableAndLateAcceptanceIsIgnored() {
        val service = Robolectric.buildService(WearSyncService::class.java).create()
        var expiredToken = ""
        try {
            assertThrows(IllegalStateException::class.java) {
                service.get().dispatchAlert("panic", "event-a", 100, 1, 0) { expiredToken = it }
            }
            var lateChanges = 0
            assertFalse(WearAlertAcceptance.accept(expiredToken) { lateChanges++ })
            assertEquals(0, lateChanges)
            assertTrue(service.get().dispatchAlert("panic", "event-a", 101, 1) {
                assertNotEquals(expiredToken, it)
                WearAlertAcceptance.accept(it)
            })
        } finally { service.destroy() }
    }

    @Test fun acknowledgementFollowsStateAcceptanceAndCannotBeReusedAfterRelease() {
        val token = WearAlertAcceptance.prepare()
        var applied = false
        try {
            assertFalse(WearAlertAcceptance.await(token, 0))
            assertTrue(WearAlertAcceptance.accept(token) { applied = true })
            assertTrue(applied)
            assertTrue(WearAlertAcceptance.await(token, 0))
            assertFalse(WearAlertAcceptance.accept(token) { fail("Token applied twice") })
        } finally { WearAlertAcceptance.release(token) }
        assertFalse(WearAlertAcceptance.accept(token) { fail("Expired intent applied") })
        val expired = WearAlertAcceptance.prepare(0)
        try { assertFalse(WearAlertAcceptance.accept(expired) { fail("Timed out intent applied") }) }
        finally { WearAlertAcceptance.release(expired) }
    }

    @Test fun foregroundActivityDoesNotRegisterASecondMessageIngress() {
        assertFalse(com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener::class.java
            .isAssignableFrom(MainActivity::class.java))
    }
}
