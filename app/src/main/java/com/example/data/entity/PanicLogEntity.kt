package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "panic_logs", indices = [Index(value = ["incidentId"], unique = true)])
data class PanicLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val heartRateSpike: Int? = null,
    val stepsTaken: Int = 0,
    val resolvedAt: Long? = null,
    // Distraction Audit Forensic Fields
    val targetApp: String? = null,
    val triggerReason: String? = null, // "Boredom", "Stuck on a bug", "Notification", etc.
    val forensicNotes: String? = null,
    val taskName: String? = null,
    val incidentId: String = java.util.UUID.randomUUID().toString(),
    val incidentType: String = "PANIC",
    val sourceDevice: String = "PHONE",
    val incidentStatus: String = "OPEN"
)

object IncidentStatus {
    const val OPEN = "OPEN"
    const val PENDING_DETAIL = "PENDING_DETAIL"
    const val CLOSED = "CLOSED"
    const val DISMISSED = "DISMISSED"

    fun canTransition(from: String, to: String): Boolean = when (from) {
        OPEN -> to == PENDING_DETAIL || to == CLOSED || to == DISMISSED
        PENDING_DETAIL -> to == CLOSED || to == DISMISSED
        else -> false
    }
}
