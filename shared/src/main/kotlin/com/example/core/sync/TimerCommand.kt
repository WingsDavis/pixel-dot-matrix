package com.example.core.sync

import java.util.UUID

data class TimerCommand(
    val id: String = UUID.randomUUID().toString(),
    val action: String,
    val baseRevision: Long,
    val sourceDevice: String = "wear"
) {
    fun encode(): ByteArray = listOf("v2", id, baseRevision.toString(), sourceDevice, action)
        .joinToString("|").toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): TimerCommand? {
            if (bytes.isEmpty() || bytes.size > 16_384) return null
            val raw = bytes.toString(Charsets.UTF_8)
            val parts = raw.split('|', limit = 5)
            if (parts.firstOrNull() == "v2") {
                if (parts.size != 5 || !WearCommandProtocol.validId(parts[1]) || parts[3] !in setOf("phone", "wear")) return null
                return TimerCommand(parts[1], parts[4], parts[2].toLongOrNull() ?: return null, parts[3])
            }
            return raw.takeIf { it.isNotBlank() }?.let {
                TimerCommand(id = "legacy-${UUID.randomUUID()}", action = it, baseRevision = -1L)
            }
        }
    }
}
