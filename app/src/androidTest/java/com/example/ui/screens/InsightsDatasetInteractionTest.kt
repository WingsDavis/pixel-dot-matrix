package com.example.ui.screens

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import com.example.domain.InsightsCalculator
import com.example.domain.InsightsFilter
import com.example.domain.InsightsPeriod
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InsightsDatasetInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tenThousandRecordsRemainNavigableAndSearchable() {
        val now = System.currentTimeMillis()
        val records = List(10_000) { index ->
            SessionLogEntity(id = index + 1, sessionType = "FOCUS", startTimeMillis = now - (index + 1) * 3_600_000L,
                durationSeconds = 1500, status = "COMPLETED", taskName = "Task $index",
                timestamp = now - (index + 1) * 3_600_000L + 1_500_000L)
        }
        var filter by mutableStateOf(InsightsFilter(InsightsPeriod.ALL_TIME))
        compose.setContent {
            MyApplicationTheme {
                Surface(Modifier.fillMaxSize()) {
                    InsightsContent(InsightsCalculator.calculate(records, emptyList(), filter, now), filter, { filter = it })
                }
            }
        }
        compose.onNodeWithText("250000").assertIsDisplayed()
        val start = SystemClock.elapsedRealtime()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(10_004)
        compose.onNodeWithText("Task 9999").assertIsDisplayed()
        val elapsed = SystemClock.elapsedRealtime() - start
        Log.i("InsightsDeviceTest", "10,000-row navigation: ${elapsed}ms")
        assertTrue("10,000-row navigation took ${elapsed}ms", elapsed < 2000)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithContentDescription("Task").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Task 9999")
        compose.onNode(hasText("Task 9999") and isSelectable()).performClick()
        compose.onNodeWithText("25").assertIsDisplayed()
    }

    @Test
    fun emptyAndIncidentOnlyDataHaveUsableStates() {
        val now = System.currentTimeMillis()
        var incidents by mutableStateOf(emptyList<PanicLogEntity>())
        val filter = InsightsFilter()
        compose.setContent {
            MyApplicationTheme {
                Surface(Modifier.fillMaxSize()) {
                    InsightsContent(InsightsCalculator.calculate(emptyList(), incidents, filter, now), filter, {})
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.onNodeWithText("No focus history in this selection.").assertIsDisplayed()
        compose.runOnIdle { incidents = listOf(PanicLogEntity(timestamp = now - 120_000, resolvedAt = now - 30_000)) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1m 30s"))
        compose.onNodeWithText("1m 30s").assertIsDisplayed()
        compose.onNodeWithText("No focus history in this selection.").assertExists()
    }
}
