@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.ui.screens

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.core.sync.WatchFaceSyncPhase
import com.example.core.sync.WatchFaceConfig
import com.example.data.entity.SyncOutboxEntity
import com.example.ui.viewmodel.PomodoroViewModel

private data class StudioColor(
    val id: String,
    val label: String,
    val hex: String,
    val color: Color
)

private val studioColors = listOf(
    StudioColor("silver", "Silver", "#ffc7ccd1", Color(0xFFC7CCD1)),
    StudioColor("graphite", "Graphite", "#ff73787c", Color(0xFF73787C)),
    StudioColor("white", "White", "#ffffffff", Color.White),
    StudioColor("amber", "Amber", "#ffffb300", Color(0xFFFFB300)),
    StudioColor("cyan", "Cyan", "#ff00e5ff", Color(0xFF00E5FF)),
    StudioColor("orange", "Orange", "#ffff9800", Color(0xFFFF9800)),
    StudioColor("coral", "Coral", "#ffff6f61", Color(0xFFFF6F61)),
    StudioColor("blue", "Blue", "#ff42a5f5", Color(0xFF42A5F5)),
    StudioColor("sky", "Sky", "#ff29b6f6", Color(0xFF29B6F6)),
    StudioColor("indigo", "Indigo", "#ff5c6bc0", Color(0xFF5C6BC0)),
    StudioColor("violet", "Violet", "#ffab47bc", Color(0xFFAB47BC)),
    StudioColor("green", "Green", "#ff81c784", Color(0xFF81C784)),
    StudioColor("lime", "Lime", "#ffc0ca33", Color(0xFFC0CA33)),
    StudioColor("mint", "Mint", "#ff66d9b8", Color(0xFF66D9B8)),
    StudioColor("teal", "Teal", "#ff26a69a", Color(0xFF26A69A)),
    StudioColor("yellow", "Yellow", "#ffffca28", Color(0xFFFFCA28)),
    StudioColor("red", "Red", "#ffef5350", Color(0xFFEF5350)),
    StudioColor("magenta", "Magenta", "#ffec407a", Color(0xFFEC407A)),
    StudioColor("terminal", "Terminal", "#ff39ff14", Color(0xFF39FF14)),
    StudioColor("ice", "Ice", "#ff80deea", Color(0xFF80DEEA))
)

@Composable
fun WearScreen(
    viewModel: PomodoroViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val syncStatus by viewModel.watchFaceSyncStatus.collectAsState()
    val outboxItems by viewModel.syncOutboxItems.collectAsState(initial = emptyList())
    val savedConfig by viewModel.watchFaceConfig.collectAsState()
    val userPresets by viewModel.watchFacePresets.collectAsState()
    val recentColors by viewModel.watchFaceRecentColors.collectAsState()
    var previewState by remember { mutableStateOf("Idle") }
    var hourColor by remember(savedConfig.hourColor) {
        mutableStateOf(studioColors.find { it.hex.equals(savedConfig.hourColor, true) } ?: studioColors.first { it.id == "orange" })
    }
    var minuteColor by remember(savedConfig.minuteColor) {
        mutableStateOf(studioColors.find { it.hex.equals(savedConfig.minuteColor, true) } ?: studioColors.first { it.id == "white" })
    }
    var secondColor by remember(savedConfig.secondColor) {
        mutableStateOf(studioColors.find { it.hex.equals(savedConfig.secondColor, true) } ?: studioColors.first { it.id == "orange" })
    }
    var themePreset by remember(savedConfig.preset) { mutableStateOf(savedConfig.preset) }
    var customText by remember(savedConfig.customText) { mutableStateOf(savedConfig.customText) }
    var showPanicLogo by remember(savedConfig.showLogo) { mutableStateOf(savedConfig.showLogo) }
    var customLogo by remember { mutableStateOf<Bitmap?>(null) }
    var logoZoom by remember { mutableStateOf(1f) }
    var logoOffsetX by remember { mutableStateOf(0f) }
    var logoOffsetY by remember { mutableStateOf(0f) }
    var customHex by remember { mutableStateOf("") }
    var customPreviewColor by remember { mutableStateOf<Color?>(null) }
    var presetDialog by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var selectedUserPreset by remember { mutableStateOf<String?>(null) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            customLogo = decodeLogoPreview(context, uri)
            if (customLogo == null) {
                Toast.makeText(context, "Unsupported or corrupt image", Toast.LENGTH_LONG).show()
            } else {
                logoZoom = 1f
                logoOffsetX = 0f
                logoOffsetY = 0f
                Toast.makeText(context, "Logo ready to apply", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (presetDialog != null) {
        AlertDialog(
            onDismissRequest = { presetDialog = null },
            title = { Text(if (presetDialog == "save") "Save preset" else if (presetDialog == "rename") "Rename preset" else "Duplicate preset") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Preset name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = presetName.trim()
                    if (name.isNotEmpty()) {
                        when (presetDialog) {
                            "save" -> viewModel.saveWatchFacePreset(name, WatchFaceConfig(hourColor.hex, minuteColor.hex, secondColor.hex, customText, showPanicLogo, name))
                            "rename" -> selectedUserPreset?.let { viewModel.renameWatchFacePreset(it, name) }
                            "duplicate" -> selectedUserPreset?.let { viewModel.duplicateWatchFacePreset(it, name) }
                        }
                        presetDialog = null
                        presetName = ""
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { presetDialog = null }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        StudioHeader()

        WatchFacePreview(
            previewState = previewState,
            hourColor = customPreviewColor ?: hourColor.color,
            minuteColor = minuteColor.color,
            secondColor = secondColor.color,
            customText = customText,
            leftSlotMode = "custom_text",
            bottomRightSlotMode = "date",
            showPanicLogo = showPanicLogo,
            logoBitmap = customLogo?.asImageBitmap(),
            logoScale = logoZoom,
            logoOffsetX = logoOffsetX,
            logoOffsetY = logoOffsetY,
            ambient = previewState == "Ambient",
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp)
        )

        StudioSection(title = "Preview State", icon = Icons.Default.Watch) {
            StudioChipRow(
                values = listOf("Idle", "Focus", "Short Break", "Long Break", "Panic", "Ambient"),
                selectedValue = previewState,
                onSelected = { previewState = it }
            )
            if (userPresets.isNotEmpty()) {
                Text("Your presets", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                StudioChipRow(
                    values = userPresets.map { it.id },
                    selectedValue = selectedUserPreset ?: "",
                    onSelected = {
                        selectedUserPreset = it
                        viewModel.applyWatchFacePreset(it)
                    },
                    labels = userPresets.associate { it.id to it.name }
                )
            }
            OutlinedButton(
                onClick = { presetDialog = "save" },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) { Text("Save Current Preset") }
            if (selectedUserPreset != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { presetName = userPresets.firstOrNull { it.id == selectedUserPreset }?.name.orEmpty(); presetDialog = "rename" }, modifier = Modifier.weight(1f)) { Text("Rename") }
                    OutlinedButton(onClick = { presetName = "Copy"; presetDialog = "duplicate" }, modifier = Modifier.weight(1f)) { Text("Duplicate") }
                    OutlinedButton(onClick = { viewModel.deleteWatchFacePreset(selectedUserPreset!!); selectedUserPreset = null }, modifier = Modifier.weight(1f)) { Text("Delete") }
                }
            }
        }

        StudioSection(title = "Watch Face Editor", icon = Icons.Default.Settings) {
            Text("Preset", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            StudioChipRow(
                values = listOf("original", "terminal", "high_contrast", "focus"),
                selectedValue = themePreset,
                onSelected = {
                    themePreset = it
                    viewModel.applyWatchFacePreset(it)
                }
            )
            ToggleRow(
                label = "Panic logo",
                checked = showPanicLogo,
                onCheckedChange = { showPanicLogo = it }
            )
            OutlinedButton(
                onClick = { logoPicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Choose Logo")
            }
            if (customLogo != null) {
                Text("Logo framing", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Zoom", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Slider(value = logoZoom, onValueChange = { logoZoom = it }, valueRange = 1f..2f)
                Text("Horizontal", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Slider(value = logoOffsetX, onValueChange = { logoOffsetX = it }, valueRange = -0.3f..0.3f)
                Text("Vertical", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Slider(value = logoOffsetY, onValueChange = { logoOffsetY = it }, valueRange = -0.3f..0.3f)
                OutlinedButton(onClick = { viewModel.syncWatchFaceLogo(customLogo!!, logoZoom, logoOffsetX, logoOffsetY) }, modifier = Modifier.fillMaxWidth()) { Text("Apply Logo Framing") }
            }
            OutlinedButton(
                onClick = {
                    customLogo = null
                    viewModel.resetWatchFace()
                    Toast.makeText(context, "Watch face reset to defaults", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Reset Face to Default")
            }
        }

        StudioSection(title = "Watch Face Preview Colors", icon = Icons.Default.Palette) {
            Text("Hour", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            ColorSwatches(studioColors, hourColor) {
                hourColor = it
                themePreset = it.id
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text("Minute", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            ColorSwatches(studioColors, minuteColor) { minuteColor = it }
            Spacer(modifier = Modifier.height(10.dp))
            Text("Second", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            ColorSwatches(studioColors, secondColor) { secondColor = it }
            Text("Custom preview color", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = customHex,
                onValueChange = { customHex = it },
                label = { Text("Hex, e.g. #FF4DD2") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = {
                normalizeHexColor(customHex)?.let { hex ->
                    customPreviewColor = Color(hex.toColorInt())
                    viewModel.rememberWatchFaceColor(hex)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Use in Preview") }
            customPreviewColor?.let { color ->
                Text("Custom color is preview-only; WFF uses its declared palette.", color = Color(0xFFFFCA28), fontSize = 11.sp)
                Box(Modifier.size(28.dp).background(color, CircleShape))
                if (color.luminance() < 0.18f) Text("Low contrast on black background", color = Color(0xFFFF8A80), fontSize = 11.sp)
            }
            if (recentColors.isNotEmpty()) {
                Text("Recent", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                ColorSwatches(recentColors.mapIndexed { index, hex -> StudioColor("recent_$index", hex, hex, Color(hex.toColorInt())) }, StudioColor("none", "", "", Color.Transparent)) { option ->
                    customHex = option.hex
                    customPreviewColor = Color(option.hex.toColorInt())
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { openWatchFaceEditor(context) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Edit Face in Pixel Watch")
            }
        }

        StudioSection(title = "Live Sync Complications", icon = Icons.Default.Watch) {
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                label = { Text("Custom left text") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(
            onClick = {
                viewModel.syncWatchFaceConfig(
                    timerColor = hourColor.hex,
                    secondsColor = secondColor.hex,
                    idleTimeColor = minuteColor.hex,
                    customText = customText,
                    leftSlotMode = "custom_text",
                    bottomRightSlotMode = "date",
                    showPanicLogo = showPanicLogo,
                    ambientStyle = "dim",
                    themePreset = themePreset
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00E5FF),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(
                text = when (syncStatus.phase) {
                    WatchFaceSyncPhase.SYNCING -> "Waiting for Watch"
                    WatchFaceSyncPhase.APPLIED -> "Sync Complications Again"
                    WatchFaceSyncPhase.FAILED -> "Retry Complication Sync"
                    WatchFaceSyncPhase.IDLE -> "Sync Complications"
                },
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = syncStatus.message,
            color = when (syncStatus.phase) {
                WatchFaceSyncPhase.APPLIED -> Color(0xFF81C784)
                WatchFaceSyncPhase.FAILED -> Color(0xFFEF5350)
                WatchFaceSyncPhase.SYNCING -> Color(0xFFFFCA28)
                WatchFaceSyncPhase.IDLE -> Color.White.copy(alpha = 0.55f)
            },
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        val pendingCount = outboxItems.count { it.status == SyncOutboxEntity.STATUS_PENDING }
        val deliveredCount = outboxItems.count {
            it.status == SyncOutboxEntity.STATUS_DELIVERED || it.status == SyncOutboxEntity.STATUS_SENT
        }
        val failedCount = outboxItems.count { it.status == SyncOutboxEntity.STATUS_FAILED }
        val appliedCount = outboxItems.count { it.status == SyncOutboxEntity.STATUS_APPLIED }
        val latestFailure = outboxItems.lastOrNull { it.status == SyncOutboxEntity.STATUS_FAILED }
        if (outboxItems.isNotEmpty()) {
            StudioSection(title = "Sync Queue", icon = Icons.Default.Watch) {
                Text(
                    text = "$pendingCount pending  ·  $deliveredCount delivered  ·  $appliedCount applied  ·  $failedCount failed",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                latestFailure?.let { failed ->
                    Text(
                        text = "${failed.path.substringAfterLast('/')}: ${failed.lastError ?: "Watch did not apply this change"}",
                        color = Color(0xFFEF5350),
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                if (failedCount > 0 || pendingCount > 0) {
                    OutlinedButton(
                        onClick = viewModel::retryPendingSync,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Retry pending sync")
                    }
                }
            }
        }

        if (savedConfig.localRevision != savedConfig.appliedRevision) {
            Text(
                text = "Local changes are not yet applied on the watch",
                color = Color(0xFFFFCA28),
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
private fun StudioHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Watch Face Studio",
            color = Color.White,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black)
        )
        Text(
            text = "Customize Dot Matrix colors, owned complications, and watch face behavior.",
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun StudioSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF151515))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        content()
    }
}

@Composable
private fun StudioChipRow(
    values: List<String>,
    selectedValue: String,
    onSelected: (String) -> Unit,
    labels: Map<String, String> = emptyMap()
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selectedValue,
                onClick = { onSelected(value) },
                label = { Text(labels[value] ?: value.toStudioLabel()) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    labelColor = Color.White,
                    selectedContainerColor = Color(0xFF00E5FF),
                    selectedLabelColor = Color.Black
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = value == selectedValue,
                    borderColor = Color.White.copy(alpha = 0.12f),
                    selectedBorderColor = Color(0xFF00E5FF)
                )
            )
        }
    }
}

@Composable
private fun ColorSwatches(
    colors: List<StudioColor>,
    selected: StudioColor,
    onSelected: (StudioColor) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { option ->
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(option.color)
                    .border(
                        width = if (option.id == selected.id) 3.dp else 1.dp,
                        color = if (option.id == selected.id) Color.White else Color.White.copy(alpha = 0.24f),
                        shape = CircleShape
                    )
                    .clickable { onSelected(option) }
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WatchFacePreview(
    previewState: String,
    hourColor: Color,
    minuteColor: Color,
    secondColor: Color,
    customText: String,
    leftSlotMode: String,
    bottomRightSlotMode: String,
    showPanicLogo: Boolean,
    logoBitmap: ImageBitmap?,
    logoScale: Float = 1f,
    logoOffsetX: Float = 0f,
    logoOffsetY: Float = 0f,
    ambient: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val yuyuTypeface = remember(context) {
        runCatching { Typeface.createFromAsset(context.assets, "twinkle_star.ttf") }
            .getOrDefault(Typeface.create("cursive", Typeface.NORMAL))
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(286.dp)) {
            val diameter = size.minDimension
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawCircle(color = Color(0xFF050505), radius = diameter / 2f)
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = diameter / 2f - 2.dp.toPx(),
                style = Stroke(width = 1.dp.toPx())
            )
            drawArc(
                color = Color.White.copy(alpha = if (ambient) 0.08f else 0.2f),
                startAngle = -55f,
                sweepAngle = 115f,
                useCenter = false,
                topLeft = topLeft + Offset(10.dp.toPx(), 10.dp.toPx()),
                size = Size(diameter - 20.dp.toPx(), diameter - 20.dp.toPx()),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = if (ambient) secondColor.copy(alpha = 0.35f) else secondColor,
                startAngle = -55f,
                sweepAngle = if (previewState == "Idle") 70f else 92f,
                useCenter = false,
                topLeft = topLeft + Offset(10.dp.toPx(), 10.dp.toPx()),
                size = Size(diameter - 20.dp.toPx(), diameter - 20.dp.toPx()),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            val (topDigits, bottomDigits) = previewDigits(previewState)
            val digitX = center.x + 24.dp.toPx()
            val topDigitY = center.y - 65.dp.toPx()
            val bottomDigitY = center.y + 10.dp.toPx()
            val digitSpacing = 7.5.dp.toPx()
            val digitGap = 41.dp.toPx()
            val dotRadius = 3.4.dp.toPx()
            val isIdleFace = previewState == "Idle" || previewState == "Ambient"
            val sessionColor = previewSessionColor(previewState)
            val topDigitColor = when {
                ambient -> hourColor.copy(alpha = 0.55f)
                isIdleFace -> hourColor
                else -> sessionColor
            }
            val bottomDigitColor = when {
                ambient -> minuteColor.copy(alpha = 0.55f)
                isIdleFace -> minuteColor
                else -> sessionColor
            }

            drawPreviewDigit(topDigits[0], digitX, topDigitY, digitSpacing, dotRadius, topDigitColor)
            drawPreviewDigit(topDigits[1], digitX + digitGap, topDigitY, digitSpacing, dotRadius, topDigitColor)
            drawPreviewDigit(bottomDigits[0], digitX, bottomDigitY, digitSpacing, dotRadius, bottomDigitColor)
            drawPreviewDigit(bottomDigits[1], digitX + digitGap, bottomDigitY, digitSpacing, dotRadius, bottomDigitColor)

            val leftText = leftSlotPreview(leftSlotMode, customText, previewState)
            if (leftText.isNotBlank()) {
                val textPaint = TextPaint().apply {
                    isAntiAlias = true
                    color = Color.White.copy(alpha = if (ambient) 0.28f else 0.92f).toArgbInt()
                    textSize = 18.sp.toPx()
                    typeface = yuyuTypeface
                }
                val textWidth = 108.dp.roundToPx()
                val layout = StaticLayout.Builder.obtain(leftText, 0, leftText.length, textPaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(1.dp.toPx(), 1f)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setMaxLines(5)
                    .build()
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.translate(center.x - 112.dp.toPx(), center.y - 49.dp.toPx())
                layout.draw(drawContext.canvas.nativeCanvas)
                drawContext.canvas.nativeCanvas.restore()
            }

            val datePaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textSize = 17.sp.toPx()
                color = Color.White.copy(alpha = if (ambient) 0.4f else 0.9f).toArgbInt()
            }
            drawContext.canvas.nativeCanvas.drawText(
                bottomRightPreview(bottomRightSlotMode, previewState),
                center.x + 16.dp.toPx(),
                center.y + 112.dp.toPx(),
                datePaint
            )
        }
        if (showPanicLogo && !ambient) {
            if (logoBitmap != null) {
                Image(
                    bitmap = logoBitmap,
                    contentDescription = "Selected watch face logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 30.dp)
                        .size(52.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .graphicsLayer {
                            scaleX = logoScale
                            scaleY = logoScale
                            translationX = logoOffsetX * 52.dp.toPx()
                            translationY = logoOffsetY * 52.dp.toPx()
                        }
                )
            } else {
                Text(
                    text = "W$",
                    color = Color(0xFF50C94D),
                    fontSize = 28.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 30.dp)
                        .background(Color(0xFF070A08), RoundedCornerShape(2.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun decodeLogoPreview(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sampleSize = 1
    while (bounds.outWidth > 0 && bounds.outHeight > 0 &&
        (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 512)
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
    }
}.getOrNull()

private fun normalizeHexColor(raw: String): String? {
    val value = raw.trim().removePrefix("#")
    if (!value.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) return null
    return if (value.length == 6) "#ff${value.lowercase()}" else "#${value.lowercase()}"
}

private fun previewDigits(state: String): Pair<String, String> {
    val now = java.time.LocalTime.now()
    return when (state) {
        "Focus" -> "25" to "00"
        "Short Break" -> "05" to "00"
        "Long Break" -> "15" to "00"
        "Panic" -> "00" to "00"
        else -> "%02d".format(if (now.hour % 12 == 0) 12 else now.hour % 12) to "%02d".format(now.minute)
    }
}

private fun previewSessionColor(state: String): Color {
    return when (state) {
        "Focus" -> Color(0xFF42A5F5)
        "Short Break" -> Color(0xFFFFCA28)
        "Long Break" -> Color(0xFF81C784)
        "Panic" -> Color(0xFFEF5350)
        else -> Color.White
    }
}

private fun DrawScope.drawPreviewDigit(
    digit: Char,
    x: Float,
    y: Float,
    spacing: Float,
    radius: Float,
    color: Color
) {
    val rows = previewDigitPatterns[digit] ?: return
    rows.forEachIndexed { row, pattern ->
        pattern.forEachIndexed { column, cell ->
            if (cell == '*') {
                drawCircle(color = color, radius = radius, center = Offset(x + column * spacing, y + row * spacing))
            }
        }
    }
}

private val previewDigitPatterns = mapOf(
    '0' to listOf(" *** ", "*   *", "*   *", "*   *", "*   *", "*   *", " *** "),
    '1' to listOf("  *  ", " **  ", "  *  ", "  *  ", "  *  ", "  *  ", " *** "),
    '2' to listOf(" *** ", "*   *", "    *", "  ** ", " *   ", "*    ", "*****"),
    '3' to listOf(" *** ", "*   *", "    *", "  ** ", "    *", "*   *", " *** "),
    '4' to listOf("   * ", "  ** ", " * * ", "*  * ", "*****", "   * ", "   * "),
    '5' to listOf("*****", "*    ", "**** ", "    *", "    *", "*   *", " *** "),
    '6' to listOf(" *** ", "*    ", "*    ", "**** ", "*   *", "*   *", " *** "),
    '7' to listOf("*****", "    *", "   * ", "  *  ", " *   ", "*    ", "*    "),
    '8' to listOf(" *** ", "*   *", "*   *", " *** ", "*   *", "*   *", " *** "),
    '9' to listOf(" *** ", "*   *", "*   *", " ****", "    *", "    *", " *** ")
)

private fun leftSlotPreview(mode: String, customText: String, state: String): String {
    return when (mode) {
        "hidden" -> ""
        "custom_text" -> customText.ifBlank { "FOCUS" }
        "phase" -> state.uppercase()
        "heart_rate" -> "74 BPM"
        "next_break" -> if (state == "Focus") "NEXT BREAK" else "NEXT FOCUS"
        "streak" -> "STREAK 0"
        else -> customText
    }
}

private fun bottomRightPreview(mode: String, state: String): String {
    return when (mode) {
        "hidden" -> ""
        "date" -> java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
            .uppercase()
        "phase" -> state.uppercase().take(8)
        "timer" -> "25:00"
        "battery" -> "WATCH"
        else -> "THU 09"
    }
}

private fun String.toStudioLabel(): String {
    return split("_", " ").joinToString(" ") { part ->
        part.lowercase().replaceFirstChar { it.uppercase() }
    }
}

private fun Color.toArgbInt(): Int {
    return "#%02x%02x%02x%02x".format(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    ).toColorInt()
}

private fun openWatchFaceEditor(context: Context) {
    val pixelWatchIntent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(
            "com.google.android.apps.wear.companion",
            "com.google.android.apps.wear.companion.core.application.RootActivity"
        )
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val legacyWearIntent = context.packageManager
        .getLaunchIntentForPackage("com.google.android.wearable.app")
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(pixelWatchIntent)
    } catch (_: android.content.ActivityNotFoundException) {
        if (legacyWearIntent != null) {
            context.startActivity(legacyWearIntent)
            return
        }
        Toast.makeText(context, "Wear OS companion app is not installed", Toast.LENGTH_LONG).show()
    }
}
