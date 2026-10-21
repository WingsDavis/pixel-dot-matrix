package com.example.ui.viewmodel

enum class BackupExportKind { JSON, SESSIONS, INCIDENTS }
data class BackupPreview(val sessions: Int, val incidents: Int, val tasks: Int)
data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val preview: BackupPreview? = null,
    val settingsPending: Boolean = false
)
