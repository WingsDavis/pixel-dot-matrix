package com.example.wear.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.example.wear.WearHapticPattern
import com.example.wear.WearHaptics
import com.example.wear.ui.theme.CardBlack
import com.example.wear.ui.theme.CardWhite

fun Modifier.bounceClick(
    scaleDown: Float = 0.90f
) = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "BounceClick"
    )
    val context = LocalContext.current
    val haptics = remember(context) { WearHaptics(context) }

    this
        .scale(scale)
        .pointerInput(haptics) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                isPressed = true
                try {
                    if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) {
                        haptics.play(WearHapticPattern.NAVIGATION_TICK)
                    }
                } finally {
                    isPressed = false
                }
            }
        }
}

@Composable
fun WearBentoCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    color: Color = CardWhite,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val baseModifier = modifier
        .clip(shape)
        .background(color)

    val finalModifier = if (onClick != null) {
        baseModifier
            .bounceClick()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    } else {
        baseModifier
    }

    Box(modifier = finalModifier, content = content)
}

@Composable
fun WearBentoIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = CardWhite,
    contentColor: Color = CardBlack,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .bounceClick()
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(androidx.wear.compose.material.LocalContentColor provides contentColor) {
            icon()
        }
    }
}
