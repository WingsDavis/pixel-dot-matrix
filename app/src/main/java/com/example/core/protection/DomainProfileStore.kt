package com.example.core.protection

import android.content.Context

class DomainProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("domain_profiles", Context.MODE_PRIVATE)
    fun activeDomains(): Set<String> = prefs.getStringSet("domains", DEFAULT_DOMAINS).orEmpty().mapNotNull(DomainRules::normalize).toSet()
    fun setDomains(values: Collection<String>) = prefs.edit().putStringSet("domains", values.mapNotNull(DomainRules::normalize).toSet()).apply()
    fun addDomain(value: String): Boolean = DomainRules.normalize(value)?.let { setDomains(activeDomains() + it); true } ?: false
    fun removeDomain(value: String) { DomainRules.normalize(value)?.let { setDomains(activeDomains() - it) } }
    fun temporarilyUnblock(durationMs: Long) = prefs.edit().putLong("unblock_until", System.currentTimeMillis() + durationMs.coerceAtLeast(0)).apply()
    fun isTemporarilyUnblocked(now: Long = System.currentTimeMillis()) = now < prefs.getLong("unblock_until", 0L)

    companion object {
        val DEFAULT_DOMAINS = setOf("facebook.com", "instagram.com", "tiktok.com", "twitter.com", "x.com", "reddit.com", "youtube.com", "news.google.com")
    }
}
