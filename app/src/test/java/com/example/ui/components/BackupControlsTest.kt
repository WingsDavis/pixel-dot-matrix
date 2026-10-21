package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.BackupUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi", sdk = [35])
class BackupControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun controlsReflectBusyPendingAndCompletedStatesAtLargeText() {
        var state by mutableStateOf(BackupUiState())
        var imports = 0
        var retries = 0
        compose.setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp)) {
                            BackupActions(state, onExport = {}, onImport = { imports++ }, onRetry = { retries++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Import JSON backup").performClick()
        assertEquals(1, imports)
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNodeWithText("Export").assertIsNotEnabled()
        compose.onNodeWithText("Import JSON backup").assertIsNotEnabled()
        compose.runOnIdle { state = BackupUiState(settingsPending = true, message = "Records merged. Settings restore is pending.") }
        compose.onNodeWithText("Retry settings restore").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Import JSON backup").assertIsNotEnabled()
        compose.onRoot().captureRoboImage(filePath = "build/reports/backup-export/phone-large-text.png")
    }
}
