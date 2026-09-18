package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.PomodoroViewModel
import com.example.ui.components.BentoCard
import com.example.ui.components.BentoActionCard
import com.example.ui.components.BentoPillButton
import com.example.ui.theme.CardBlack
import com.example.ui.theme.CardWhite
import com.example.ui.theme.TealAccent

@Composable
fun TimerScreen(
    viewModel: PomodoroViewModel,
    modifier: Modifier = Modifier
) {
    val currentState by viewModel.currentState.collectAsState()
    val secondsRemaining by viewModel.secondsRemaining.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val currentTaskName by viewModel.currentTaskName.collectAsState()

    val totalDuration = when (currentState) {
        com.example.core.timer.PomodoroState.FOCUS -> viewModel.engine.focusDuration
        com.example.core.timer.PomodoroState.SHORT_BREAK -> viewModel.engine.shortBreakDuration
        com.example.core.timer.PomodoroState.LONG_BREAK -> viewModel.engine.longBreakDuration
        com.example.core.timer.PomodoroState.PANIC_MODE -> 60
    }

    val progress = if (totalDuration > 0) {
        secondsRemaining.toFloat() / totalDuration.toFloat()
    } else {
        1f
    }

    val minutes = secondsRemaining / 60
    val seconds = secondsRemaining % 60
    val timeString = String.format("%02d:%02d", minutes, seconds)

    val stateLabel = when (currentState) {
        com.example.core.timer.PomodoroState.FOCUS -> "Deep Focus"
        com.example.core.timer.PomodoroState.SHORT_BREAK -> "Short Break"
        com.example.core.timer.PomodoroState.LONG_BREAK -> "Long Break"
        com.example.core.timer.PomodoroState.PANIC_MODE -> "Panic Breathing"
    }

    val themeColor = if (currentState == com.example.core.timer.PomodoroState.PANIC_MODE) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onBackground
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

        OutlinedTextField(
            value = currentTaskName,
            onValueChange = { viewModel.updateTaskName(it) },
            placeholder = { Text("What are you focusing on?", color = CardWhite.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TealAccent,
                unfocusedBorderColor = CardWhite.copy(alpha = 0.3f),
                focusedTextColor = CardWhite,
                unfocusedTextColor = CardWhite,
                cursorColor = TealAccent
            ),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        // Main Timer Card (Teal Bento Style)
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            color = TealAccent,
            shape = RoundedCornerShape(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column {
                    var showStateMenu by remember { mutableStateOf(false) }

                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isRunning) { showStateMenu = true }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = stateLabel,
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
                                color = CardBlack
                            )
                            if (!isRunning) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Choose timer phase",
                                    tint = CardBlack,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showStateMenu,
                            onDismissRequest = { showStateMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Deep Focus") },
                                onClick = {
                                    viewModel.setTimerState(com.example.core.timer.PomodoroState.FOCUS)
                                    showStateMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Short Break") },
                                onClick = {
                                    viewModel.setTimerState(com.example.core.timer.PomodoroState.SHORT_BREAK)
                                    showStateMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Long Break") },
                                onClick = {
                                    viewModel.setTimerState(com.example.core.timer.PomodoroState.LONG_BREAK)
                                    showStateMenu = false
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Huge Time Display
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, letterSpacing = 0.sp),
                        color = CardBlack
                    )
                }

                // Progress Bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(CardBlack.copy(alpha = 0.1f), RoundedCornerShape(50))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = 1f - progress) // progress goes down, so 1-progress is elapsed
                                .height(24.dp)
                                .background(CardBlack, RoundedCornerShape(50))
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Elapsed", color = CardBlack.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Remaining", color = CardBlack.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Control Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BentoPillButton(
                text = if (isRunning) "Pause" else "Start",
                onClick = {
                    if (isRunning) viewModel.pauseTimer() else viewModel.startTimer()
                },
                modifier = Modifier.weight(1f),
                color = CardWhite,
                contentColor = CardBlack,
                icon = {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }
            )

            BentoCard(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = CardWhite,
                onClick = { viewModel.resetTimer() }
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Reset Timer",
                    tint = CardBlack,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            BentoCard(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = CardWhite,
                onClick = { viewModel.skipTimer() }
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Skip",
                    tint = CardBlack,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Panic Mode Activation Banner (Teal Bento Style)
        BentoActionCard(
            title = "Panic Mode",
            subtitle = "Biometric HR Monitoring",
            amount = "SOS",
            color = CardWhite,
            onClick = { viewModel.triggerPanic() }
        )

        Spacer(modifier = Modifier.height(100.dp))

    }
}
