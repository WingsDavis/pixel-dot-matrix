package com.example.core.protection

import android.content.Context
import org.json.JSONObject
import java.util.UUID

data class DomainProfile(val id: String, val name: String, val domains: Set<String>)

class DomainProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("domain_profiles", Context.MODE_PRIVATE)

    fun profiles(): List<DomainProfile> = readProfiles()
    fun activeProfileId(): String = prefs.getString(KEY_ACTIVE_PROFILE, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
    fun activeDomains(): Set<String> = readProfiles().firstOrNull { it.id == activeProfileId() }?.domains ?: DEFAULT_DOMAINS

    fun selectProfile(id: String) {
        if (readProfiles().any { it.id == id }) prefs.edit().putString(KEY_ACTIVE_PROFILE, id).apply()
    }

    fun saveProfile(name: String, domains: Collection<String> = activeDomains()): DomainProfile {
        val profile = DomainProfile(UUID.randomUUID().toString(), name.trim(), normalize(domains))
        writeProfiles(readProfiles() + profile)
        return profile
    }

    fun deleteProfile(id: String) {
        if (id == DEFAULT_PROFILE_ID) return
        writeProfiles(readProfiles().filterNot { it.id == id })
        if (activeProfileId() == id) prefs.edit().putString(KEY_ACTIVE_PROFILE, DEFAULT_PROFILE_ID).apply()
    }

    fun setDomains(values: Collection<String>) {
        val activeId = activeProfileId()
        writeProfiles(readProfiles().map { if (it.id == activeId) it.copy(domains = normalize(values)) else it })
    }

    fun addDomain(value: String): Boolean = DomainRules.normalize(value)?.let { setDomains(activeDomains() + it); true } ?: false
    fun removeDomain(value: String) { DomainRules.normalize(value)?.let { setDomains(activeDomains() - it) } }
    fun temporarilyUnblock(durationMs: Long) = prefs.edit().putLong(KEY_UNBLOCK_UNTIL, System.currentTimeMillis() + durationMs.coerceAtLeast(0)).apply()
    fun isTemporarilyUnblocked(now: Long = System.currentTimeMillis()) = now < prefs.getLong(KEY_UNBLOCK_UNTIL, 0L)
    fun autoActivateDuringFocus(): Boolean = prefs.getBoolean(KEY_AUTO_FOCUS, false)
    fun setAutoActivateDuringFocus(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_FOCUS, enabled).apply()

    private fun readProfiles(): List<DomainProfile> {
        val stored = prefs.getStringSet(KEY_PROFILES, emptySet()).orEmpty().mapNotNull(::decode)
        return if (stored.isEmpty()) {
            listOf(DomainProfile(DEFAULT_PROFILE_ID, "Default", normalize(prefs.getStringSet("domains", DEFAULT_DOMAINS).orEmpty())))
        } else stored
    }

    private fun writeProfiles(profiles: List<DomainProfile>) {
        prefs.edit().putStringSet(KEY_PROFILES, profiles.map(::encode).toSet()).apply()
    }

    private fun encode(profile: DomainProfile) = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("domains", profile.domains.joinToString(","))
    }.toString()

    private fun decode(raw: String): DomainProfile? = runCatching {
        val json = JSONObject(raw)
        DomainProfile(json.getString("id"), json.getString("name"), normalize(json.getString("domains").split(',')))
    }.getOrNull()

    private fun normalize(values: Collection<String>) = values.mapNotNull(DomainRules::normalize).toSet()

    companion object {
        const val DEFAULT_PROFILE_ID = "default"
        val DEFAULT_DOMAINS = setOf("facebook.com", "instagram.com", "tiktok.com", "twitter.com", "x.com", "reddit.com", "youtube.com", "news.google.com")
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_UNBLOCK_UNTIL = "unblock_until"
        private const val KEY_AUTO_FOCUS = "auto_focus"
    }
}
