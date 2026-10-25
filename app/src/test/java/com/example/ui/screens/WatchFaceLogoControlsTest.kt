package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.core.media.LogoFit
import com.example.core.media.LogoTransform
import com.example.core.media.WatchFaceLogoDocument
import com.example.core.media.WatchFaceLogoRenderer
import com.example.core.sync.LogoSyncPhase
import com.example.core.sync.LogoUiState
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-xhdpi")
class WatchFaceLogoControlsTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(LogoUiState())
    private var applied = 0
    private var reset = 0
    private var chosen = 0
    private var retried = 0

    @Test fun fitZoomPanAndTransparencyUseTheRenderedDocumentWithoutClaimingApplication() {
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val pose = LogoTransform()
        state = LogoUiState(WatchFaceLogoDocument(source, pose, WatchFaceLogoRenderer.render(source, pose)), LogoSyncPhase.LOCAL,
            message = "Local logo changes not applied")
        show()
        compose.onNodeWithText("Fit").performClick()
        assertEquals(LogoFit.FIT, state.document!!.transform.fit)
        assertEquals(0, Color.alpha(state.document!!.rendered.getPixel(0, 0)))
        compose.onNodeWithContentDescription("Logo zoom").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        assertEquals(2f, state.document!!.transform.zoom, 0.0001f)
        compose.onNodeWithContentDescription("Logo horizontal position").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(0.5f, state.document!!.transform.panX, 0.0001f)
        compose.onNode(isToggleable()).performScrollTo().performClick()
        assertFalse(state.document!!.transform.transparent)
        compose.onNodeWithText("Apply Logo").performScrollTo().performClick()
        assertEquals(1, applied)
        compose.onNodeWithText("Logo stored on watch").assertDoesNotExist()
    }

    @Test fun importBusyAndTransferFailureStatesRemainActionable() {
        state = LogoUiState(busy = true, message = "Opening image")
        show()
        compose.onAllNodesWithText("Opening image", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Choose Logo").assertDoesNotExist()
        compose.onNodeWithText("Reset Logo").assertIsNotEnabled()
        compose.runOnIdle { state = LogoUiState(phase = LogoSyncPhase.FAILED, message = "No connection", canRetry = true) }
        compose.onNodeWithText("Choose Logo").performClick()
        compose.onNodeWithText("Retry transfer").performClick()
        compose.onNodeWithText("Reset Logo").performClick()
        assertEquals(1, chosen)
        assertEquals(1, retried)
        assertEquals(1, reset)
    }

    private fun show() {
        compose.setContent {
            MyApplicationTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WatchFaceLogoControls(state, { chosen++ }, { update ->
                        val document = state.document!!
                        val pose = update(document.transform)
                        state = state.copy(document = document.copy(transform = pose, rendered = WatchFaceLogoRenderer.render(document.source, pose)))
                    }, { applied++ }, { retried++ }, { reset++ })
                }
            }
        }
    }
}
