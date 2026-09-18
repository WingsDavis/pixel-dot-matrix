package com.example.wear.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
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
import com.example.wear.ui.theme.CardWhite
import com.example.wear.ui.theme.TealAccent
import com.example.wear.ui.theme.WearTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun RecoveryScreen(onRecoveryComplete: () -> Unit) {
    val context = LocalContext.current
    var instruction by remember { mutableStateOf("Inhale") }

    val scale = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Timer for 2 minutes (120 seconds)
        val endTime = System.currentTimeMillis() + 120_000

        while (isActive && System.currentTimeMillis() < endTime) {
            // Inhale (4s)
            instruction = "Inhale"
            vibrateGentle(vibrator)
            scale.animateTo(1.8f, animationSpec = tween(4000, easing = LinearEasing))

            // Hold (7s)
            instruction = "Hold"
            vibrateGentle(vibrator)
            delay(7000)

            // Exhale (8s)
            instruction = "Exhale"
            vibrateGentle(vibrator)
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

private fun vibrateGentle(vibrator: Vibrator) {
    if (!vibrator.hasVibrator()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(50)
    }
}
