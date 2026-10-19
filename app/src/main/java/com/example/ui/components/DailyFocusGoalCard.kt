package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.goal.DailyFocusProgress
import com.example.core.goal.FocusTargetType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyFocusGoalCard(
    progress: DailyFocusProgress,
    onEnabledChange: (Boolean) -> Unit,
    onTypeChange: (FocusTargetType) -> Unit,
    onTargetChange: (Int) -> Unit,
    onResetHourChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    saveError: String? = null
) {
    val settings = progress.settings
    var expanded by remember { mutableStateOf(false) }
    var targetText by remember(settings.target, settings.type) { mutableStateOf(settings.target.toString()) }
    var resetMenu by remember { mutableStateOf(false) }
    val targetRange = if (settings.type == FocusTargetType.MINUTES) 1..1440 else 1..100
    val targetIsValid = targetText.toIntOrNull()?.let { it in targetRange } == true

    RetroTile(modifier = modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daily focus", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = when {
                            !settings.enabled -> "Target off"
                            settings.type == FocusTargetType.MINUTES -> "${progress.value} / ${settings.target} min"
                            else -> "${progress.value} / ${settings.target} sessions"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.Edit,
                        contentDescription = if (expanded) "Collapse daily target settings" else "Edit daily target"
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = {
                        onEnabledChange(it)
                        if (it) expanded = true
                    },
                    modifier = Modifier.semantics { contentDescription = "Enable daily focus target" }
                )
            }
            if (settings.enabled) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    modifier = Modifier.fillMaxWidth()
                )
                if (progress.complete) Text("Target reached", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    FocusTargetType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = settings.type == type,
                            onClick = { onTypeChange(type) },
                            shape = SegmentedButtonDefaults.itemShape(index, FocusTargetType.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) { Text(if (type == FocusTargetType.MINUTES) "Minutes" else "Sessions") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { onTargetChange((settings.target - 1).coerceIn(targetRange)) }, enabled = settings.target > targetRange.first) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease daily target")
                    }
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { value ->
                            if (value.length <= 4 && value.all(Char::isDigit)) {
                                targetText = value
                                value.toIntOrNull()?.takeIf { it in targetRange }?.let(onTargetChange)
                            }
                        },
                        label = { Text("Daily target") },
                        isError = !targetIsValid,
                        supportingText = if (!targetIsValid) ({ Text("Enter ${targetRange.first}-${targetRange.last}") }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onTargetChange((settings.target + 1).coerceIn(targetRange)) }, enabled = settings.target < targetRange.last) {
                        Icon(Icons.Default.Add, contentDescription = "Increase daily target")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Daily reset", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Box {
                        TextButton(onClick = { resetMenu = true }) {
                            Text(String.format(Locale.getDefault(), "%02d:00", settings.resetHour))
                        }
                        DropdownMenu(expanded = resetMenu, onDismissRequest = { resetMenu = false }, modifier = Modifier.heightIn(max = 240.dp)) {
                            (0..23).forEach { hour ->
                                DropdownMenuItem(
                                    text = { Text(String.format(Locale.getDefault(), "%02d:00", hour)) },
                                    onClick = {
                                        onResetHourChange(hour)
                                        resetMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Text(settings.timeZoneId, style = MaterialTheme.typography.bodySmall)
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
