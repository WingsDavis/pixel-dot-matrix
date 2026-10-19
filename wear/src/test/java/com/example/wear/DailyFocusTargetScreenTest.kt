package com.example.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.core.goal.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w228dp-h228dp-round-xhdpi", sdk = [35])
class DailyFocusTargetScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun maximumMinuteTargetRendersOnRoundWatch() {
        compose.setContent {
            WearAppTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                    Box(Modifier.size(228.dp).background(Color.Black)) {
                        DailyFocusTargetScreen(
                            DailyFocusProgress(
                                DailyFocusGoalSettings(enabled = true, targetMinutes = 1440),
                                FocusDay(0, Long.MAX_VALUE),
                                focusedSeconds = 1440 * 60L
                            )
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("1440/1440m").assertIsDisplayed()
        val textLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("1440/1440m").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        assertFalse(textLayouts.single().hasVisualOverflow)
        compose.onNodeWithText("TARGET REACHED").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/daily-focus-goal/watch-max-target.png")
    }
}
