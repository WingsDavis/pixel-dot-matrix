package com.example.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CardBlack
import com.example.ui.theme.CardWhite
import com.example.ui.theme.TealAccent

fun Modifier.bounceClick(
    scaleDown: Float = 0.95f
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
fun BentoCard(
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
fun BentoPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = CardBlack,
    contentColor: Color = CardWhite,
    icon: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.bounceClick(),
        shape = RoundedCornerShape(50),
        color = color,
        contentColor = contentColor,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun BentoActionCard(
    title: String,
    subtitle: String,
    amount: String,
    modifier: Modifier = Modifier,
    color: Color = TealAccent,
    onClick: () -> Unit
) {
    BentoCard(
        modifier = modifier.fillMaxWidth(),
        color = color,
        shape = RoundedCornerShape(24.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(CardBlack, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(10.dp).background(CardWhite, CircleShape))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BentoCard(
                        color = CardBlack,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = amount,
                            color = CardWhite,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowOutward,
                        contentDescription = "Action",
                        tint = CardBlack,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = title,
                color = CardBlack,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                color = CardBlack.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
