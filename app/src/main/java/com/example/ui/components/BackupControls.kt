package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.BackupExportKind
import com.example.ui.viewmodel.BackupUiState
import com.example.ui.viewmodel.PomodoroViewModel

@Composable
fun BackupControls(viewModel: PomodoroViewModel) {
    val state by viewModel.backupState.collectAsStateWithLifecycle()
    var csvKind by rememberSaveable { mutableStateOf(BackupExportKind.SESSIONS.name) }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let { uri -> viewModel.exportBackup(uri, BackupExportKind.JSON) }
    }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        it?.let { uri -> viewModel.exportBackup(uri, BackupExportKind.valueOf(csvKind)) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::prepareBackupImport)
    }
    BackupActions(state,
        onExport = { kind ->
            if (kind == BackupExportKind.JSON) jsonExport.launch("pixel-dot-matrix-backup.json")
            else {
                csvKind = kind.name
                csvExport.launch("pixel-dot-matrix-${kind.name.lowercase()}.csv")
            }
        },
        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        onRetry = viewModel::retryBackupSettings
    )
    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) viewModel.cancelBackupImport() },
            title = { Text("Merge backup?") },
            text = { Text("${preview.sessions} sessions, ${preview.incidents} incidents, ${preview.tasks} tasks. " +
                "Existing records win on ID conflicts. Timer, target, face and protection preferences are replaced. " +
                "Logo images, live timers and permission grants are not restored.") },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { viewModel.confirmBackupImport() }) { Text("Merge and restore") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = viewModel::cancelBackupImport) { Text("Cancel") } }
        )
    }
}

@Composable
internal fun BackupActions(
    state: BackupUiState,
    onExport: (BackupExportKind) -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit
) {
    var exportMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Backup & export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Unencrypted files include task names and incident notes.", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(enabled = !state.busy && !state.settingsPending, onClick = { exportMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Export")
            }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                listOf(BackupExportKind.JSON to "Full backup (.json)", BackupExportKind.SESSIONS to "Sessions (.csv)",
                    BackupExportKind.INCIDENTS to "Incidents (.csv)").forEach { (kind, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { exportMenu = false; onExport(kind) })
                }
            }
        }
        OutlinedButton(enabled = !state.busy && !state.settingsPending, onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Import JSON backup")
        }
        if (state.settingsPending) OutlinedButton(enabled = !state.busy, onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry settings restore") }
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
