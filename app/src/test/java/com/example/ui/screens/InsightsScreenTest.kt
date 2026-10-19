package com.example.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import com.example.domain.InsightsCalculator
import com.example.domain.InsightsFilter
import com.example.domain.InsightsPeriod
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi", sdk = [35])
class InsightsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_792_411_200_000L // 2026-10-19 12:00 UTC
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun focusMetricsRenderAtLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        val sessions = listOf(row(1, "Build", 1500), row(2, "Read", 600))
        val incidents = listOf(PanicLogEntity(timestamp = now - 300_000, resolvedAt = now - 240_000,
            taskName = "Build", triggerReason = "Notification"))
        val filter = InsightsFilter()
        compose.setContent {
            MyApplicationTheme {
                Surface(Modifier.fillMaxSize()) {
                    InsightsContent(InsightsCalculator.calculate(sessions, incidents, filter, now, utc), filter, {})
                }
            }
        }
        compose.onNodeWithText("35").assertIsDisplayed().assertHasNoClickAction()
        compose.onRoot().captureRoboImage(filePath = "build/reports/insights/phone-large-text.png")
        assertTextFits("35")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Incident category"))
        compose.onNodeWithText("1m 0s").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/insights/phone-incidents.png")
    }

    @Test
    fun filterContentSearchesAndSelectsKeysRatherThanDuplicateLabels() {
        var selected: String? by mutableStateOf(null)
        compose.setContent {
            MyApplicationTheme {
                Surface(Modifier.fillMaxSize().padding(24.dp)) {
                    InsightsOptions(listOf(null to "All tasks", "All tasks" to "All tasks", "Build" to "Build", "Read" to "Read"),
                        selected, searchable = true, onSelect = { selected = it })
                }
            }
        }
        compose.onNode(hasText("All tasks") and isNotSelected()).performClick()
        assertEquals("All tasks", selected)
        compose.onNode(hasSetTextAction()).performTextInput("Bui")
        compose.onNodeWithText("Read").assertDoesNotExist()
        compose.onNodeWithText("Build").performClick()
        assertEquals("Build", selected)
        compose.onNode(hasSetTextAction()).performTextReplacement("No match")
        compose.onNodeWithText("No matches").assertIsDisplayed()
    }

    @Test
    fun tenThousandHistoryRowsAreLazyAndLastRecordIsReachable() {
        val rows = List(10_000) { index -> row(index + 1, "Task $index", 1500) }
        val filter = InsightsFilter(InsightsPeriod.ALL_TIME)
        val metrics = InsightsCalculator.calculate(rows, emptyList(), filter, now, utc)
        compose.setContent {
            MyApplicationTheme {
                Surface(Modifier.fillMaxSize()) { InsightsContent(metrics, filter, {}) }
            }
        }
        compose.onNodeWithText("250000").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/insights/phone-10k-metrics.png")
        assertTextFits("250000")
        compose.onNodeWithText("Task 9999").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(10_004)
        compose.onNodeWithText("Task 9999").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/insights/phone-10k-history.png")
    }

    @Test
    fun emptyDataRendersAndTwoTimesFontScaleDoesNotClipMetrics() {
        val filter = InsightsFilter()
        val metrics = InsightsCalculator.calculate(emptyList(), emptyList(), filter, now, utc)
        compose.setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Surface(Modifier.fillMaxSize()) { InsightsContent(metrics, filter, {}) }
                }
            }
        }
        compose.onNodeWithText("0").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/insights/phone-empty-2x-text.png")
        assertTextFits("0")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.onNodeWithText("No focus history in this selection.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-land-xhdpi", sdk = [35])
    fun landscapeHistoryWithLongTaskNameRemainsReadable() {
        val filter = InsightsFilter()
        val name = "A long recorded task name that should wrap within its own history row without moving the duration"
        val metrics = InsightsCalculator.calculate(listOf(row(1, name, 1500)), emptyList(), filter, now, utc)
        compose.setContent {
            MyApplicationTheme {
                Surface(Modifier.fillMaxSize()) { InsightsContent(metrics, filter, {}) }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("25m 0s").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/insights/phone-landscape.png")
    }

    private fun row(id: Int, taskName: String, seconds: Int) = SessionLogEntity(
        id = id, sessionType = "FOCUS", startTimeMillis = now - id * 3_600_000L,
        durationSeconds = seconds, status = "COMPLETED", taskName = taskName,
        timestamp = now - id * 3_600_000L + seconds * 1000L)

    private fun assertTextFits(value: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(value).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val result = results.single()
        assertFalse("Metric text overflows: $value size=${result.size} constraints=${result.layoutInput.constraints} " +
            "width=${result.didOverflowWidth} height=${result.didOverflowHeight} lines=${result.lineCount}", result.hasVisualOverflow)
    }
}
