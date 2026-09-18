package com.example.wear.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
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
    val view = LocalView.current

    this
        .scale(scale)
        .pointerInput(isPressed) {
            awaitPointerEventScope {
                isPressed = if (awaitFirstDown(requireUnconsumed = false).also { it.consume() } != null) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    true
                } else {
                    false
                }
                waitForUpOrCancellation()
                isPressed = false
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
