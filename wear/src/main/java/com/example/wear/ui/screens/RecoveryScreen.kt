package com.example.wear.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import com.example.wear.WearHapticPattern
import com.example.wear.WearHaptics
import com.example.wear.ui.theme.CardWhite
import com.example.wear.ui.theme.TealAccent
import com.example.wear.ui.theme.WearTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun RecoveryScreen(onRecoveryComplete: () -> Unit) {
    val context = LocalContext.current
    val haptics = remember(context) { WearHaptics(context) }
    var instruction by remember { mutableStateOf("Inhale") }

    val scale = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // Timer for 2 minutes (120 seconds)
        val endTime = System.currentTimeMillis() + 120_000

        while (isActive && System.currentTimeMillis() < endTime) {
            // Inhale (4s)
            instruction = "Inhale"
            haptics.play(WearHapticPattern.RECOVERY)
            scale.animateTo(1.8f, animationSpec = tween(4000, easing = LinearEasing))

            // Hold (7s)
            instruction = "Hold"
            haptics.play(WearHapticPattern.RECOVERY)
            delay(7000)

            // Exhale (8s)
            instruction = "Exhale"
            haptics.play(WearHapticPattern.RECOVERY)
            scale.animateTo(1f, animationSpec = tween(8000, easing = LinearEasing))
        }

        onRecoveryComplete()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(scale.value)
                .background(TealAccent.copy(alpha = 0.5f), CircleShape)
        )

        Text(
            text = instruction,
            style = WearTypography.title2,
            color = CardWhite
        )
    }
}
