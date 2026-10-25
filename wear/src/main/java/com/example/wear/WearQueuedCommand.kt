package com.example.wear

enum class WearCommandStatus { QUEUED, DELIVERED, APPLIED, FAILED }

data class WearQueuedCommand(
    val id: String,
    val path: String,
    val action: String,
    val status: WearCommandStatus,
    val error: String?,
    val createdAt: Long
)
