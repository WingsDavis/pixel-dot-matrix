package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.core.goal.*
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi", sdk = [35])
class DailyFocusGoalCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun goalControlsEnableSwitchTypeValidateAndRenderAtLargeText() {
        var settings by mutableStateOf(DailyFocusGoalSettings(timeZoneId = "Africa/Accra"))
        compose.setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Column(
                        Modifier.width(360.dp).height(780.dp).background(Color.Black)
                            .verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        DailyFocusGoalCard(
                            DailyFocusProgress(settings, FocusDay(0, Long.MAX_VALUE), 2700, 3),
                            onEnabledChange = { settings = settings.copy(enabled = it) },
                            onTypeChange = { settings = settings.copy(type = it) },
                            onTargetChange = { settings = if (settings.type == FocusTargetType.MINUTES) settings.copy(targetMinutes = it) else settings.copy(targetSessions = it) },
                            onResetHourChange = { settings = settings.copy(resetHour = it) }
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Target off").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enable daily focus target").performClick()
        compose.onNodeWithText("45 / 120 min").assertIsDisplayed()
        compose.onNodeWithText("Sessions").performClick()
        compose.onNodeWithText("3 / 4 sessions").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("0")
        compose.onNodeWithText("Enter 1-100").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("6")
        compose.onNodeWithText("3 / 6 sessions").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/daily-focus-goal/phone-large-text.png")
    }
}
