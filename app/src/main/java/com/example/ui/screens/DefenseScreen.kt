package com.example.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.PanicLogEntity
import com.example.ui.viewmodel.PomodoroViewModel
import com.example.ui.components.BentoCard
import com.example.ui.components.BentoPillButton
import com.example.ui.theme.CardBlack
import com.example.ui.theme.CardWhite
import com.example.ui.theme.TealAccent
import java.text.SimpleDateFormat
import com.example.core.protection.DomainProfileStore

@Composable
fun DefenseScreen(viewModel: PomodoroViewModel) {
    val context = LocalContext.current
    val activeAudit by viewModel.activeIncidentAudit.collectAsState()
    val predictiveText by viewModel.predictiveVulnerabilityText.collectAsState()
    val isDnsActive by viewModel.isDnsSinkholeActive.collectAsState()
    val panicLogs by viewModel.panicLogs.collectAsState()
    val domainStore = remember { DomainProfileStore(context) }
    var domains by remember { mutableStateOf(domainStore.activeDomains().sorted()) }
    var newDomain by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf(domainStore.profiles()) }
    var activeProfileId by remember { mutableStateOf(domainStore.activeProfileId()) }
    var profileName by remember { mutableStateOf("") }
    var autoFocus by remember { mutableStateOf(domainStore.autoActivateDuringFocus()) }
    var unblockUntil by remember { mutableStateOf(domainStore.unblockUntil()) }
    var unblockRemainingMs by remember { mutableStateOf((unblockUntil - System.currentTimeMillis()).coerceAtLeast(0L)) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.setDnsSinkholeEnabled(context, true)
        }
    }

    LaunchedEffect(unblockUntil) {
        while (unblockRemainingMs > 0L) {
            unblockRemainingMs = (unblockUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            kotlinx.coroutines.delay(1_000L)
        }
    }

    if (activeAudit != null) {
        IncidentAuditDialog(
            audit = activeAudit!!,
            onSubmit = { app, reason, notes ->
                viewModel.submitIncidentAudit(app, reason, notes)
            },
            onDismiss = { viewModel.dismissIncidentAudit() }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = "Defense Center",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 2. On-Device Predictive Vulnerability
        item {
            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                color = CardWhite,
                shape = RoundedCornerShape(32.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(CardBlack, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Analytics, contentDescription = null, tint = CardWhite, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            "PREDICTIVE VULNERABILITY",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                            color = CardBlack
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = predictiveText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = CardBlack.copy(alpha = 0.8f)
                    )
                }
            }
        }

        item {
            BentoCard(modifier = Modifier.fillMaxWidth(), color = CardWhite, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Protection profiles", fontWeight = FontWeight.Bold, color = CardBlack)
                    profiles.forEach { profile ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, color = CardBlack, fontSize = 12.sp, fontWeight = if (profile.id == activeProfileId) FontWeight.Bold else FontWeight.Normal)
                            Row {
                                TextButton(onClick = {
                                    domainStore.selectProfile(profile.id)
                                    activeProfileId = profile.id
                                    domains = domainStore.activeDomains().sorted()
                                }) { Text(if (profile.id == activeProfileId) "Active" else "Use") }
                                if (profile.id != DomainProfileStore.DEFAULT_PROFILE_ID) {
                                    TextButton(onClick = {
                                        domainStore.deleteProfile(profile.id)
                                        profiles = domainStore.profiles()
                                        activeProfileId = domainStore.activeProfileId()
                                        domains = domainStore.activeDomains().sorted()
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("New profile name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = {
                        if (profileName.isNotBlank()) {
                            val profile = domainStore.saveProfile(profileName, domains)
                            domainStore.selectProfile(profile.id)
                            profiles = domainStore.profiles()
                            activeProfileId = profile.id
                            profileName = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save current domains as profile") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable during Focus", color = CardBlack, fontSize = 12.sp)
                        Switch(checked = autoFocus, onCheckedChange = {
                            autoFocus = it
                            domainStore.setAutoActivateDuringFocus(it)
                        })
                    }
                    Text("Blocked domains", fontWeight = FontWeight.Bold, color = CardBlack)
                    OutlinedTextField(
                        value = newDomain,
                        onValueChange = { newDomain = it },
                        label = { Text("Domain") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        if (domainStore.addDomain(newDomain)) {
                            domains = domainStore.activeDomains().sorted()
                            newDomain = ""
                        }
                    }) { Text("Add domain") }
                    domains.forEach { domain ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(domain, color = CardBlack, fontSize = 12.sp)
                            TextButton(onClick = { domainStore.removeDomain(domain); domains = domainStore.activeDomains().sorted() }) {
                                Text("Remove")
                            }
                        }
                    }
                    if (unblockRemainingMs > 0L) {
                        Text("Protection resumes in ${unblockRemainingMs / 60_000}:${"%02d".format((unblockRemainingMs / 1_000) % 60)}", color = Color(0xFFFF3B30), fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = {
                        unblockUntil = System.currentTimeMillis() + 10 * 60 * 1000L
                        unblockRemainingMs = 10 * 60 * 1000L
                        viewModel.temporarilyUnblockDomains(10 * 60 * 1000L)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Unblock for 10 minutes")
                    }
                }
            }
        }

        // 3. Local DNS Sinkhole
        item {
            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                color = CardWhite,
                shape = RoundedCornerShape(32.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(CardBlack, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = CardWhite, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Local DNS Sinkhole", style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = CardBlack)
                            Text(
                                "Air-gap distraction domains natively.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CardBlack.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Switch(
                        checked = isDnsActive,
                        onCheckedChange = { checked ->
                            if (checked) {
                                val prepareIntent = VpnService.prepare(context)
                                if (prepareIntent != null) {
                                    vpnPermissionLauncher.launch(prepareIntent)
                                } else {
                                    viewModel.setDnsSinkholeEnabled(context, true)
                                }
                            } else {
                                viewModel.setDnsSinkholeEnabled(context, false)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CardWhite,
                            checkedTrackColor = TealAccent,
                            uncheckedThumbColor = CardBlack,
                            uncheckedTrackColor = CardBlack.copy(alpha = 0.3f),
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }
        }

        // 4. Biometric Frustration Interception
        item {
            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                color = CardWhite,
                shape = RoundedCornerShape(32.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(CardBlack, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Spa, contentDescription = null, tint = CardWhite, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Frustration Interception", style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = CardBlack)
                            Text(
                                "Uses Pixel Watch 3 HRV via Wear OS",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CardBlack.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    BentoPillButton(
                        text = "SIMULATE HRV SPIKE",
                        color = CardBlack,
                        contentColor = CardWhite,
                        onClick = { viewModel.triggerFrustrationAlert() }
                    )
                }
            }
        }

        // 1. Distraction Audit Logs
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "RECENT INCIDENTS",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (panicLogs.isEmpty()) {
            item {
                Text("No incidents recorded. Stay focused.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(panicLogs.sortedByDescending { it.timestamp }) { log ->
                IncidentLogItem(log)
            }
        }
    }
}

@Composable
fun IncidentLogItem(log: PanicLogEntity) {
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()) }
    val dateString = formatter.format(java.util.Date(log.timestamp))

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        color = CardWhite,
        shape = RoundedCornerShape(32.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(dateString, style = MaterialTheme.typography.titleMedium, color = CardBlack)
                if (log.targetApp != null) {
                    Text(log.targetApp, color = CardBlack.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("UNRESOLVED", color = CardBlack.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (log.triggerReason != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Reason: ${log.triggerReason}", style = MaterialTheme.typography.bodyLarge, color = CardBlack.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun IncidentAuditDialog(
    audit: PanicLogEntity,
    onSubmit: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var app by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Distraction Audit", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A breach was detected. Let's perform a forensic breakdown to improve your threat model.", fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = app,
                    onValueChange = { app = it },
                    label = { Text("Target App / Website") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (Bored, Bug, etc.)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            BentoPillButton(
                onClick = { onSubmit(app, reason, notes) },
                color = CardBlack,
                contentColor = CardWhite,
                text = "SUBMIT"
            )
        },
        dismissButton = {
            BentoPillButton(
                onClick = onDismiss,
                color = CardWhite,
                contentColor = CardBlack,
                text = "SKIP"
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(24.dp)
    )
}
