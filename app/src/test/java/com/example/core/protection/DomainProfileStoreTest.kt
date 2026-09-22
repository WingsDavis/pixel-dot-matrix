package com.example.core.protection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DomainProfileStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() = context.getSharedPreferences("domain_profiles", Context.MODE_PRIVATE).edit().clear().commit().let { Unit }

    @Test
    fun createsSelectsAndDeletesNamedProfiles() {
        val store = DomainProfileStore(context)
        val profile = store.saveProfile("Work", listOf("Example.com", "reddit.com"))
        store.selectProfile(profile.id)

        assertEquals(setOf("example.com", "reddit.com"), store.activeDomains())
        assertEquals("Work", store.profiles().first { it.id == profile.id }.name)

        store.deleteProfile(profile.id)
        assertEquals(DomainProfileStore.DEFAULT_PROFILE_ID, store.activeProfileId())
    }

    @Test
    fun persistsFocusAutomationPreference() {
        val store = DomainProfileStore(context)
        assertFalse(store.autoActivateDuringFocus())
        store.setAutoActivateDuringFocus(true)
        assertTrue(store.autoActivateDuringFocus())
    }
}
