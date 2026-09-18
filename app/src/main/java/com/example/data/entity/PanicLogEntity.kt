package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "panic_logs")
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
