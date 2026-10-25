package com.example.wear

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w192dp-h192dp-round-xhdpi", sdk = [35])
class WearQuickActionsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun smallRoundWatchCanReachAllActionsFailuresAndSettings() {
        show(192, 1.3f, "Current task with a deliberately long recorded name", "Next task",
            WatchActionFeedback("FAILED", "Phone state changed", "failed-1"))
        compose.onRoot().captureRoboImage(filePath = "build/reports/quick-actions/round192-initial.png")
        scrollTo("DISTRACTION")
        compose.onNodeWithText("DISTRACTION").assertIsDisplayed().performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("DISTRACTION").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        assertFalse(layouts.single().isLineEllipsized(0))
        assertEquals(1, distractions)
        scrollTo("RETRY")
        compose.onNodeWithText("RETRY").performClick()
        assertEquals("failed-1", retried)
        scrollTo("TEST HAPTIC")
        compose.onNodeWithText("TEST HAPTIC").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/quick-actions/round192-controls.png")
    }

    @Test @Config(qualifiers = "w228dp-h228dp-round-xhdpi")
    fun largeRoundWatchAndIncreasedTextKeepActionsAccessible() {
        show(228, 1.3f, "", "", WatchActionFeedback("QUEUED", "Waiting for phone"))
        scrollTo("FOCUS")
        compose.onNodeWithText("FOCUS").performClick()
        scrollTo("BREAK")
        compose.onNodeWithText("BREAK").performClick()
        scrollTo("DISTRACTION")
        compose.onNodeWithText("DISTRACTION").assertIsDisplayed()
        assertEquals(1, focus)
        assertEquals(1, breaks)
        compose.onRoot().captureRoboImage(filePath = "build/reports/quick-actions/round228-large-text.png")
    }

    @Test fun receiptLabelsNeverConfuseSentWithAppliedAndExposeFailedRetry() {
        fun record(status: WearCommandStatus) = WearQueuedCommand("id", "/pomodoro/control", "START", status,
            if (status == WearCommandStatus.FAILED) "Rejected" else null, 1)
        assertEquals("QUEUED", record(WearCommandStatus.QUEUED).toFeedback().title)
        assertEquals("SENT", record(WearCommandStatus.DELIVERED).toFeedback().title)
        assertEquals("APPLIED", record(WearCommandStatus.APPLIED).toFeedback().title)
        assertNull(record(WearCommandStatus.APPLIED).toFeedback().retryId)
        assertEquals("id", record(WearCommandStatus.FAILED).toFeedback().retryId)
        assertEquals("Rejected", record(WearCommandStatus.FAILED).toFeedback().error)
    }

    @Test fun crownScrollsQuickActionsBeforeChangingPages() {
        assertTrue(shouldScrollQuickActions(4, 20f, true, false))
        assertTrue(shouldScrollQuickActions(4, -20f, false, true))
        assertFalse(shouldScrollQuickActions(4, 20f, false, true))
        assertFalse(shouldScrollQuickActions(4, -20f, true, false))
        assertFalse(shouldScrollQuickActions(3, 20f, true, true))
    }

    @Test fun recreationKeepsAnOlderRetriedActionSelectedWithoutReplayingAlerts() {
        val records = listOf(
            WearQueuedCommand("older", "/pomodoro/control", "START", WearCommandStatus.QUEUED, null, 1),
            WearQueuedCommand("newer", "/pomodoro/control", "PAUSE", WearCommandStatus.APPLIED, null, 2)
        )
        val selection = WatchActionSelection()
        selection.begin("older")
        assertTrue(selection.notifyResult)
        val state = Bundle()
        selection.save(state)
        val recreated = WatchActionSelection().also { it.restore(state) }
        assertEquals("older", recreated.selected(records, false)?.id)
        assertEquals(WearCommandStatus.QUEUED, recreated.selected(records, false)?.status)
        assertFalse(recreated.notifyResult)
        assertNull(recreated.selected(records.filterNot { it.id == "older" }, false))
    }

    private var distractions = 0
    private var focus = 0
    private var breaks = 0
    private var retried: String? = null

    private fun show(size: Int, scale: Float, task: String, next: String, feedback: WatchActionFeedback?) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("wear_haptics", Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent {
            WearAppTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    Box(Modifier.size(size.dp).clip(CircleShape).background(Color.Black)) {
                        WearQuickActions(WearHaptics(context), task, next, feedback,
                            onRetry = { retried = it }, onFocus = { focus++ }, onBreak = { breaks++ },
                            onDistraction = { distractions++ })
                    }
                }
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }
}
