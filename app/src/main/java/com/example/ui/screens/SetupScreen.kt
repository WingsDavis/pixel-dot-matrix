package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.example.ui.viewmodel.PomodoroViewModel
import com.example.ui.components.BentoCard
import com.example.ui.components.BentoPillButton
import com.example.ui.theme.CardBlack
import com.example.ui.theme.CardWhite
import com.example.ui.theme.TealAccent

@Composable
fun SetupScreen(
    viewModel: PomodoroViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sessions by viewModel.sessionLogs.collectAsState()
    val incidents by viewModel.panicLogs.collectAsState()
    val syncItems by viewModel.syncOutboxItems.collectAsState(initial = emptyList())
    val connectedWatchCount by viewModel.connectedWatchCount.collectAsState()
    val isDnsActive by viewModel.isDnsSinkholeActive.collectAsState()
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // Timer durations config state
    var focusMin by remember { mutableStateOf(viewModel.engine.focusDuration / 60) }
    var breakMin by remember { mutableStateOf(viewModel.engine.shortBreakDuration / 60) }
    var longBreakMin by remember { mutableStateOf(viewModel.engine.longBreakDuration / 60) }

    // Overlay permission state
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Activity Recognition permission state
    var hasActivityRecognitionPermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    val activityPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasActivityRecognitionPermission = granted
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotificationPermission = granted }

    // Polling for overlay permission if the user goes to settings and back
    LaunchedEffect(Unit) {
        while (true) {
            hasOverlayPermission = Settings.canDrawOverlays(context)
            hasActivityRecognitionPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            } else true
            hasNotificationPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
            kotlinx.coroutines.delay(1500)
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete local data?") },
            text = { Text("This removes $target from this phone and cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    when (target) {
                        "session history" -> viewModel.clearSessionHistory()
                        "incident history" -> viewModel.clearIncidentHistory()
                        "pending sync operations" -> viewModel.clearSyncQueue()
                        "watch artwork and face settings" -> viewModel.resetWatchFace()
                        "protection profiles" -> viewModel.clearProtectionSettings()
                    }
                    Toast.makeText(context, "Deleted $target", Toast.LENGTH_SHORT).show()
                    deleteTarget = null
                }) { Text("Delete", color = Color(0xFFFF5252)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Huge Neo-Brutalist Header
        Text(
            text = "App Setup",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(vertical = 16.dp)
        )

        // Card for Timer Configuration
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            color = CardWhite,
            shape = RoundedCornerShape(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(CardBlack, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Config Icon",
                            tint = CardWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "Timer Preferences",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        color = CardBlack
                    )
                }

                // Focus Duration
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Focus Duration", fontSize = 13.sp, color = CardBlack.copy(alpha = 0.7f))
                        Text(text = "${focusMin} min", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CardBlack)
                    }
                    Slider(
                        value = focusMin.toFloat(),
                        onValueChange = {
                            focusMin = it.toInt()
                            viewModel.engine.focusDuration = focusMin * 60
                            viewModel.engine.reset() // reset to reflect new duration
                        },
                        valueRange = 1f..60f,
                        colors = SliderDefaults.colors(
                            thumbColor = CardBlack,
                            activeTrackColor = TealAccent,
                            inactiveTrackColor = CardBlack.copy(alpha = 0.1f)
                        )
                    )
                }

                // Short Break Duration
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Short Break Duration", fontSize = 13.sp, color = CardBlack.copy(alpha = 0.7f))
                        Text(text = "${breakMin} min", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CardBlack)
                    }
                    Slider(
                        value = breakMin.toFloat(),
                        onValueChange = {
                            breakMin = it.toInt()
                            viewModel.engine.shortBreakDuration = breakMin * 60
                            viewModel.engine.reset() // reset to reflect new duration
                        },
                        valueRange = 1f..25f,
                        colors = SliderDefaults.colors(
                            thumbColor = CardBlack,
                            activeTrackColor = TealAccent,
                            inactiveTrackColor = CardBlack.copy(alpha = 0.1f)
                        )
                    )
                }

                // Long Break Duration
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Long Break Duration", fontSize = 13.sp, color = CardBlack.copy(alpha = 0.7f))
                        Text(text = "$longBreakMin min", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CardBlack)
                    }
                    Slider(
                        value = longBreakMin.toFloat(),
                        onValueChange = {
                            longBreakMin = it.toInt()
                            viewModel.engine.longBreakDuration = longBreakMin * 60
                            viewModel.engine.reset()
                        },
                        valueRange = 5f..45f,
                        colors = SliderDefaults.colors(
                            thumbColor = CardBlack,
                            activeTrackColor = TealAccent,
                            inactiveTrackColor = CardBlack.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }

        // Card for Android Permissions
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            color = CardWhite,
            shape = RoundedCornerShape(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(CardBlack, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Permissions Icon",
                            tint = CardWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "Integrations",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        color = CardBlack
                    )
                }

                // Overlay Permission Status and trigger button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "System Overlay",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = CardBlack
                        )
                        Text(
                            text = if (hasOverlayPermission) "Enabled" else "Required to lock screen in Panic Mode",
                            fontSize = 11.sp,
                            color = CardBlack.copy(alpha = 0.7f)
                        )
                    }
                    BentoPillButton(
                        text = if (hasOverlayPermission) "Active" else "Enable",
                        onClick = {
                            if (!hasOverlayPermission) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                overlayPermissionLauncher.launch(intent)
                            }
                        },
                        color = if (hasOverlayPermission) TealAccent else CardBlack,
                        contentColor = if (hasOverlayPermission) CardBlack else CardWhite
                    )
                }

                // Activity recognition status and trigger button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Physical Activity",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = CardBlack
                        )
                        Text(
                            text = if (hasActivityRecognitionPermission) "Step count detection enabled" else "Required for the walk-away resolution",
                            fontSize = 11.sp,
                            color = CardBlack.copy(alpha = 0.7f)
                        )
                    }
                    BentoPillButton(
                        text = if (hasActivityRecognitionPermission) "Active" else "Grant",
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        },
                        color = if (hasActivityRecognitionPermission) TealAccent else CardBlack,
                        contentColor = if (hasActivityRecognitionPermission) CardBlack else CardWhite
                    )
                }
            }
        }

        // Clear Storage Card
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            color = CardBlack,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Privacy & local data", color = CardWhite, fontWeight = FontWeight.Bold)
                Text("Sessions, incidents, preferences, and watch artwork stay in local app storage unless synchronized to your paired watch.", color = CardWhite.copy(alpha = 0.7f), fontSize = 12.sp)
                Text("Overlay: ${if (hasOverlayPermission) "allowed" else "not allowed"}", color = CardWhite, fontSize = 12.sp)
                Text("Physical activity: ${if (hasActivityRecognitionPermission) "allowed" else "not allowed"}", color = CardWhite, fontSize = 12.sp)
                Text("Notifications: ${if (hasNotificationPermission) "allowed" else "not allowed"}", color = CardWhite, fontSize = 12.sp)
                Text("DNS protection: ${if (isDnsActive) "active" else "inactive"}", color = CardWhite, fontSize = 12.sp)
                Text("Wear connection: ${if (connectedWatchCount > 0) "$connectedWatchCount connected" else "disconnected"}", color = CardWhite, fontSize = 12.sp)
                Text("Stored locally: ${sessions.size} sessions · ${incidents.size} incidents · ${syncItems.size} sync records", color = CardWhite.copy(alpha = 0.7f), fontSize = 11.sp)
                Text("Task names are stored with session history and are removed with it.", color = CardWhite.copy(alpha = 0.55f), fontSize = 10.sp)
                if (!hasNotificationPermission && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow notifications")
                    }
                }
                OutlinedButton(onClick = { deleteTarget = "session history" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete session history (${sessions.size})")
                }
                OutlinedButton(onClick = { deleteTarget = "incident history" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete incident history (${incidents.size})")
                }
                OutlinedButton(onClick = { deleteTarget = "pending sync operations" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete sync queue (${syncItems.size})")
                }
                OutlinedButton(onClick = { deleteTarget = "watch artwork and face settings" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete watch artwork & face settings")
                }
                OutlinedButton(onClick = { deleteTarget = "protection profiles" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete protection profiles")
                }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WingsDavis/pixel-dot-matrix")))
                }, modifier = Modifier.fillMaxWidth()) { Text("View source repository") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WingsDavis/pixel-dot-matrix/blob/main/THIRD_PARTY_NOTICES.md")))
                }, modifier = Modifier.fillMaxWidth()) { Text("Open licenses & notices") }
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}
