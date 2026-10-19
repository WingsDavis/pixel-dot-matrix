package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.entity.SessionLogEntity
import com.example.domain.FocusInsights
import com.example.domain.InsightsFilter
import com.example.domain.InsightsPeriod
import com.example.ui.components.RetroTile
import com.example.ui.theme.DotMatrixFont
import com.example.ui.viewmodel.PomodoroViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun InsightsScreen(viewModel: PomodoroViewModel, modifier: Modifier = Modifier) {
    val metrics by viewModel.insights.collectAsStateWithLifecycle()
    val filter by viewModel.insightsFilter.collectAsStateWithLifecycle()
    InsightsContent(metrics, filter, viewModel::updateInsightsFilter, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsContent(
    metrics: FocusInsights?,
    filter: InsightsFilter,
    onFilterChange: (InsightsFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    var customDates by rememberSaveable { mutableStateOf(false) }
    val locale = Locale.getDefault()
    val zoneId = metrics?.timeZoneId ?: TimeZone.getDefault().id
    val dateFormat = remember(locale, zoneId) {
        SimpleDateFormat("MMM d, yyyy", locale).apply { timeZone = TimeZone.getTimeZone(zoneId) }
    }
    val rowDateFormat = remember(locale, zoneId) {
        SimpleDateFormat("MMM d, HH:mm", locale).apply { timeZone = TimeZone.getTimeZone(zoneId) }
    }
    val bucketFormat = remember(locale, zoneId) {
        SimpleDateFormat("MMM d", locale).apply { timeZone = TimeZone.getTimeZone(zoneId) }
    }

    if (customDates) {
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = filter.customStartUtcMs,
            initialSelectedEndDateMillis = filter.customEndUtcMs
        )
        DatePickerDialog(
            onDismissRequest = { customDates = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null,
                    onClick = {
                        onFilterChange(filter.copy(
                            period = InsightsPeriod.CUSTOM,
                            customStartUtcMs = picker.selectedStartDateMillis,
                            customEndUtcMs = picker.selectedEndDateMillis
                        ))
                        customDates = false
                    }
                ) { Text("Apply dates") }
            },
            dismissButton = { TextButton(onClick = { customDates = false }) { Text("Cancel") } }
        ) { DateRangePicker(picker, modifier = Modifier.heightIn(max = 500.dp)) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightsChoice("Date range", filter.period.label,
                    InsightsPeriod.entries.map { it.name to it.label }, selectedKey = filter.period.name, searchable = false
                ) { value ->
                    val period = InsightsPeriod.valueOf(requireNotNull(value))
                    if (period == InsightsPeriod.CUSTOM) customDates = true
                    else onFilterChange(filter.copy(period = period))
                }
                if (metrics != null) {
                    val dates = if (filter.period == InsightsPeriod.ALL_TIME) "All recorded dates"
                    else "${dateFormat.format(Date(metrics.fromEpochMs))} - ${dateFormat.format(Date(metrics.toEpochMs - 1))}"
                    Text(dates, style = MaterialTheme.typography.bodySmall, color = muted())
                }
                InsightsChoice("Task", filter.taskName?.ifEmpty { "No task" } ?: "All tasks",
                    listOf(null to "All tasks") + metrics?.taskNames.orEmpty().map { it to it.ifEmpty { "No task" } },
                    selectedKey = filter.taskName
                ) { onFilterChange(filter.copy(taskName = it)) }
                if (filter.taskName != null || filter.incidentCategory != null || filter.period != InsightsPeriod.LAST_7_DAYS) {
                    TextButton(onClick = { onFilterChange(InsightsFilter(weekly = filter.weekly)) }) { Text("Reset filters") }
                }
            }
        }
        if (metrics == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            item(key = "focus-metrics") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Focus", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    MetricPair(
                        first = { InsightMetric("Focus minutes", metrics.focusMinutes.toString(), "Saved focus time", it) },
                        second = { InsightMetric("Completion", metrics.completionRatePercent?.let { value -> "$value%" } ?: "--",
                            "${metrics.completedFocusSessions} / ${metrics.endedFocusIntervals} intervals", it) }
                    )
                    InsightMetric("Uninterrupted average", duration(metrics.averageUninterruptedSeconds),
                        "Recorded focus interval", Modifier.fillMaxWidth())
                }
            }
            item(key = "trend") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Daily focus", "Weekly focus").forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = filter.weekly == (index == 1),
                                onClick = { onFilterChange(filter.copy(weekly = index == 1)) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary)
                            ) { Text(label) }
                        }
                    }
                    val max = metrics.buckets.maxOfOrNull { it.focusSeconds }?.coerceAtLeast(1) ?: 1
                    metrics.buckets.forEach { bucket ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val label = if (filter.weekly) "${bucketFormat.format(Date(bucket.startEpochMs))} - ${bucketFormat.format(Date(bucket.endEpochMs - 1))}"
                            else bucketFormat.format(Date(bucket.startEpochMs))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted())
                                Text("${bucket.focusSeconds / 60} min", style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { bucket.focusSeconds.toFloat() / max },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                drawStopIndicator = {}
                            )
                        }
                    }
                }
            }
            item(key = "incident-metrics") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Incidents (${metrics.incidentCount})", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    InsightsChoice("Incident category", filter.incidentCategory ?: "All categories",
                        listOf(null to "All categories") + metrics.categoryNames.map { it to it },
                        selectedKey = filter.incidentCategory
                    ) { onFilterChange(filter.copy(incidentCategory = it)) }
                    MetricPair(
                        first = { InsightMetric("Panic recovery", duration(metrics.averageRecoverySeconds), "Resolved panics", it) },
                        second = { InsightMetric("Fast recoveries", metrics.rapidRecoveries.toString(), "Under 3 minutes", it) }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Resilience", color = muted())
                        Text(metrics.resilienceScore.toString(), fontFamily = DotMatrixFont, letterSpacing = 0.sp)
                    }
                    if (metrics.categories.isEmpty()) Text("No incidents in this selection.", color = muted())
                    else {
                        Text("Top incident categories", style = MaterialTheme.typography.bodySmall, color = muted())
                        metrics.categories.take(5).forEach { category ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(category.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(category.count.toString(), fontFamily = DotMatrixFont, letterSpacing = 0.sp)
                            }
                        }
                    }
                }
            }
            item(key = "history-header") {
                Text("Focus history (${metrics.history.size})", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            }
            if (metrics.history.isEmpty()) {
                item { Text("No focus history in this selection.", color = muted()) }
            }
            items(metrics.history, key = { it.id }, contentType = { "focus-interval" }) { log ->
                FocusHistoryRow(log, rowDateFormat)
            }
        }
    }
}

@Composable
private fun MetricPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

@Composable
private fun InsightMetric(label: String, value: String, supporting: String, modifier: Modifier = Modifier) {
    RetroTile(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 12.sp, color = muted())
            Text(value, modifier = Modifier.fillMaxWidth(), fontSize = 24.sp, lineHeight = 32.sp, fontFamily = DotMatrixFont,
                color = MaterialTheme.colorScheme.primary, letterSpacing = 0.sp)
            Text(supporting, fontSize = 12.sp, color = muted())
        }
    }
}

@Composable
private fun FocusHistoryRow(log: SessionLogEntity, dateFormat: SimpleDateFormat) {
    RetroTile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(log.taskName?.takeIf { it.isNotBlank() } ?: "No task", Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(duration(log.durationSeconds.coerceAtLeast(0).toLong()), fontFamily = DotMatrixFont, letterSpacing = 0.sp)
            }
            Text("${dateFormat.format(Date(log.timestamp))} / ${when (log.status) {
                "COMPLETED" -> "Completed"
                "PANIC" -> "Panic"
                else -> "Interrupted"
            }}", style = MaterialTheme.typography.bodySmall, color = muted())
        }
    }
}

@Composable
private fun InsightsChoice(
    label: String,
    value: String,
    options: List<Pair<String?, String>>,
    selectedKey: String?,
    searchable: Boolean = true,
    onSelect: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { contentDescription = label }, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
        Text("$label: $value", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                InsightsOptions(options, selectedKey, searchable) {
                    onSelect(it)
                    open = false
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }
        )
    }
}

@Composable
internal fun InsightsOptions(
    options: List<Pair<String?, String>>,
    selectedKey: String?,
    searchable: Boolean,
    onSelect: (String?) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(options, query) { options.filter { it.second.contains(query, ignoreCase = true) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (searchable) OutlinedTextField(query, { query = it }, label = { Text("Search") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
            items(visible, key = { it.first?.let { key -> "option:$key" } ?: "all" }) { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = option.first == selectedKey, role = Role.RadioButton
                ) { onSelect(option.first) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(option.second, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    RadioButton(selected = option.first == selectedKey, onClick = null)
                }
            }
            if (visible.isEmpty()) item { Text("No matches", color = muted()) }
        }
    }
}

private fun duration(seconds: Long?): String = when {
    seconds == null -> "--"
    seconds < 60 -> "${seconds}s"
    else -> "${seconds / 60}m ${seconds % 60}s"
}

@Composable
private fun muted() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
