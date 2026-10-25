package com.example.core.sync

import android.util.Base64

data class SyncMessageEnvelope(val id: String, val payload: ByteArray) {
    fun encode(): ByteArray = (PREFIX + id + SEPARATOR + Base64.encodeToString(payload, Base64.NO_WRAP)).toByteArray(Charsets.UTF_8)

    companion object {
        private const val PREFIX = "sync1|"
        private const val SEPARATOR = "|"
        fun decode(bytes: ByteArray): SyncMessageEnvelope? {
            val body = bytes.toString(Charsets.UTF_8).removePrefix(PREFIX)
            if (body == bytes.toString(Charsets.UTF_8) || !body.contains(SEPARATOR)) return null
            val split = body.indexOf(SEPARATOR)
            return runCatching { SyncMessageEnvelope(body.substring(0, split), Base64.decode(body.substring(split + 1), Base64.DEFAULT)) }.getOrNull()
        }
    }
}
