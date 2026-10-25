package com.example.core.sync

import android.util.Base64

data class SyncAppliedAck(val id: String, val applied: Boolean, val message: String? = null) {
    fun encode(): ByteArray {
        val reason = Base64.encodeToString(message.orEmpty().take(2000).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "$id|${if (applied) "APPLIED" else "FAILED"}|$reason".toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun decode(bytes: ByteArray): SyncAppliedAck? {
            if (bytes.isEmpty() || bytes.size > 16_384) return null
            val raw = bytes.toString(Charsets.UTF_8)
            val parts = raw.split('|', limit = 3)
            if (parts.size == 1 && WearCommandProtocol.validId(raw)) return SyncAppliedAck(raw, true)
            if (parts.size != 3 || !WearCommandProtocol.validId(parts[0]) || parts[1] !in setOf("APPLIED", "FAILED")) return null
            if (parts[2].length % 4 != 0 || !parts[2].matches(Regex("[A-Za-z0-9+/]*={0,2}"))) return null
            val reason = runCatching { String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return null
            return SyncAppliedAck(parts[0], parts[1] == "APPLIED", reason.ifBlank { null })
        }
    }
}
