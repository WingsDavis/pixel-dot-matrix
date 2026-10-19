package com.example.ui.screens

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
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class InsightsInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nativeDialogsApplyDatesTasksCategoriesAndReset() {
        val now = 1_792_411_200_000L
        val sessions = listOf("Build" to 1500, "Read" to 600).mapIndexed { index, (name, duration) ->
            SessionLogEntity(id = index + 1, sessionType = "FOCUS", startTimeMillis = now - 3_600_000,
                durationSeconds = duration, status = "COMPLETED", timestamp = now - 3_600_000 + duration * 1000L, taskName = name)
        }
        val incidents = listOf(PanicLogEntity(timestamp = now - 300_000, resolvedAt = now - 240_000,
            taskName = "Build", triggerReason = "Notification"))
        var filter by mutableStateOf(InsightsFilter(customStartUtcMs = now - 43_200_000, customEndUtcMs = now - 43_200_000))
        compose.setContent {
            MyApplicationTheme {
                Surface(Modifier.fillMaxSize()) {
                    InsightsContent(InsightsCalculator.calculate(sessions, incidents, filter, now, TimeZone.getTimeZone("UTC")),
                        filter, { filter = it })
                }
            }
        }
        compose.onNodeWithContentDescription("Task").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Bui")
        compose.onNodeWithText("Read").assertDoesNotExist()
        compose.onNode(hasText("Build") and hasClickAction()).performClick()
        compose.onNodeWithText("25").assertIsDisplayed()
        compose.onNodeWithContentDescription("Date range").performClick()
        compose.onNodeWithText("Custom dates").performClick()
        compose.onNodeWithText("Apply dates").assertIsEnabled().performClick()
        assertEquals(InsightsPeriod.CUSTOM, filter.period)
        compose.onNodeWithContentDescription("Date range").performClick()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("Date range: Today").assertIsDisplayed()
        compose.onNodeWithText("Reset filters").performClick()
        compose.onNodeWithText("35").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Weekly focus"))
        compose.onNodeWithText("Weekly focus").performClick()
        assertTrue(filter.weekly)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Incident category"))
        compose.onNodeWithContentDescription("Incident category").performClick()
        compose.onNode(hasText("Notification") and hasClickAction()).performClick()
        assertEquals("Notification", filter.incidentCategory)
        compose.onNodeWithText("1m 0s").assertIsDisplayed()
    }
}
