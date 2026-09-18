package com.example.core.protection

import java.net.IDN
import java.util.Locale

object DomainRules {
    fun normalize(input: String): String? = runCatching {
        val value = input.trim().lowercase(Locale.US)
            .removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.')
        IDN.toASCII(value).takeIf { domain ->
            domain.isNotBlank() && domain.length <= 253 && domain.split('.').all { label ->
                label.isNotBlank() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }
        }
    }.getOrNull()

    fun matches(query: String, rules: Set<String>): Boolean {
        val domain = normalize(query) ?: return false
        return rules.any { domain == it || domain.endsWith(".$it") }
    }
}
