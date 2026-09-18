package com.example.core.sync

import com.example.core.timer.PomodoroState
import java.util.UUID

enum class TimerAuthority { PHONE, WEAR }

data class TimerSnapshot(
    val revision: Long,
    val sessionId: String,
    val sourceDevice: String,
    val authority: TimerAuthority,
    val state: PomodoroState,
    val secondsRemaining: Int,
    val isRunning: Boolean,
    val updatedAtEpochMs: Long,
    val anchorElapsedRealtimeMs: Long
)

data class TimerCommand(
    val id: String = UUID.randomUUID().toString(),
    val action: String,
    val baseRevision: Long,
    val sourceDevice: String = "wear"
) {
    fun encode(): ByteArray = listOf(VERSION, id, baseRevision.toString(), sourceDevice, action)
        .joinToString(SEPARATOR)
        .toByteArray(Charsets.UTF_8)

    companion object {
        private const val VERSION = "v2"
        private const val SEPARATOR = "|"

        fun decode(bytes: ByteArray): TimerCommand? {
            val raw = bytes.toString(Charsets.UTF_8)
            val parts = raw.split(SEPARATOR, limit = 5)
            if (parts.size == 5 && parts[0] == VERSION) {
                return TimerCommand(
                    id = parts[1],
                    baseRevision = parts[2].toLongOrNull() ?: return null,
                    sourceDevice = parts[3],
                    action = parts[4]
                )
            }
            return raw.takeIf { it.isNotBlank() }?.let {
                TimerCommand(id = "legacy-${UUID.randomUUID()}", action = it, baseRevision = -1L)
            }
        }
    }
}

sealed interface SnapshotResolution {
    data class Accept(val snapshot: TimerSnapshot) : SnapshotResolution
    data class Reject(val reason: String) : SnapshotResolution
}

object TimerSnapshotResolver {
    fun resolve(
        local: TimerSnapshot,
        incoming: TimerSnapshot,
        wearLeaseExpiresAtEpochMs: Long,
        nowEpochMs: Long
    ): SnapshotResolution {
        if (incoming.sourceDevice == local.sourceDevice) {
            return SnapshotResolution.Reject("echo")
        }
        if (incoming.revision <= local.revision) {
            return SnapshotResolution.Reject("stale_revision")
        }
        val wearHasLease = incoming.authority == TimerAuthority.WEAR && nowEpochMs <= wearLeaseExpiresAtEpochMs
        if (local.authority == TimerAuthority.PHONE && !wearHasLease) {
            return SnapshotResolution.Reject("phone_authoritative")
        }
        return SnapshotResolution.Accept(incoming)
    }
}
