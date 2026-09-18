package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TealBentoColorScheme =
    darkColorScheme(
        primary = TealAccent,
        onPrimary = DeepBackground,
        primaryContainer = TealAccentDark,
        onPrimaryContainer = DeepBackground,
        secondary = CardWhite,
        onSecondary = CardBlack,
        background = DeepBackground,
        onBackground = CardWhite,
        surface = DeepBackground,
        onSurface = CardWhite,
        surfaceVariant = CardWhite,
        onSurfaceVariant = SoftTextGray,
        outline = SoftGray,
        error = Color(0xFFE57373),
        errorContainer = Color(0xFF4A1C1C),
        onErrorContainer = Color(0xFFFFB4AB)
    )

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = TealBentoColorScheme // Always use the teal bento theme

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
