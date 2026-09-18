package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun BreathingCircle(modifier: Modifier = Modifier) {
    // Box Breathing: 4s Inhale, 4s Hold, 4s Exhale, 4s Hold (16s cycle)
    var phase by remember { mutableStateOf("Inhale") }
    var secondsInPhase by remember { mutableStateOf(4) }

    // Custom scale animation reflecting the phases
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")

    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 16000
                0.4f at 0 with LinearOutSlowInEasing // Inhale starts (4s)
                1.0f at 4000 with LinearEasing // Inhale ends, start Hold (4s)
                1.0f at 8000 with FastOutLinearInEasing // Hold ends, start Exhale (4s)
                0.4f at 12000 with LinearEasing // Exhale ends, start Hold (4s)
                0.4f at 16000 // End cycle
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )

    // Dynamic label and timer based on animation progress
    LaunchedEffect(Unit) {
        while (true) {
            // Inhale (4s)
            phase = "Inhale"
            for (i in 4 downTo 1) {
                secondsInPhase = i
                delay(1000)
            }
            // Hold (4s)
            phase = "Hold"
            for (i in 4 downTo 1) {
                secondsInPhase = i
                delay(1000)
            }
            // Exhale (4s)
            phase = "Exhale"
            for (i in 4 downTo 1) {
                secondsInPhase = i
                delay(1000)
            }
            // Hold (4s)
            phase = "Hold"
            for (i in 4 downTo 1) {
                secondsInPhase = i
                delay(1000)
            }
        }
    }

    Box(
        modifier = modifier
            .size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring with custom glowing color
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(breathingScale)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = CircleShape
                )
        )

        // Inner solid pulsing circle
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(breathingScale * 0.85f + 0.15f)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = phase,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${secondsInPhase}s",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
