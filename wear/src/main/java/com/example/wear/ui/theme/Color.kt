package com.example.wear.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors

val TealAccent = Color(0xFF00E5FF)
val CardBlack = Color(0xFF151515)
val CardWhite = Color(0xFFFFFFFF)
val BackgroundBlack = Color.Black

val PanicRed = Color(0xFFFF3B30)

val WearTealBentoColors = Colors(
    primary = TealAccent,
    primaryVariant = TealAccent.copy(alpha = 0.8f),
    secondary = TealAccent,
    secondaryVariant = TealAccent.copy(alpha = 0.8f),
    background = BackgroundBlack,
    surface = CardBlack,
    error = PanicRed,
    onPrimary = CardBlack,
    onSecondary = CardBlack,
    onBackground = CardWhite,
    onSurface = CardWhite,
    onError = CardWhite
)
