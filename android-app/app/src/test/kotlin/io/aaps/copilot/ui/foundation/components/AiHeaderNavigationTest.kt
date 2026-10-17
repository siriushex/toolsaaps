package io.aaps.copilot.ui.foundation.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.aaps.copilot.ui.motion.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AiHeaderNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun oneAccessibleHeaderOwlOpensAllThreeDestinationsAndDiagnosticsHasBack() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var diagnostics by mutableStateOf(false)
        var main = 0; var settings = 0
        try {
            controller.get().setContent {
                MaterialTheme { ProvideCopilotMotion(false) {
                    AiHeaderNavigation(OwlColorBand.GREEN, "ai_analysis", OwlGesture.LISTEN,
                        false, true, diagnostics, { diagnostics = true }, { diagnostics = false },
                        { main++ }, { settings++ })
                } }
            }
            compose.onNodeWithTag("owlMenuAction").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            val size = compose.onNodeWithTag("owlMenuAction").fetchSemanticsNode().boundsInRoot
            assertTrue(size.width >= 48 && size.height >= 48)
            compose.onNodeWithTag("owlMenuAction").performClick()
            compose.onNodeWithTag("ai_header_diagnostics").performClick()
            compose.onNodeWithTag("owlMenuAction").assertDoesNotExist()
            compose.onNodeWithTag("ai_header_back").performClick()
            compose.onNodeWithTag("owlMenuAction").performClick()
            compose.onNodeWithTag("ai_header_main_menu").performClick()
            compose.onNodeWithTag("owlMenuAction").performClick()
            compose.onNodeWithTag("ai_header_settings").performClick()
            assertEquals(1, main); assertEquals(1, settings)
        } finally { controller.pause().stop().destroy() }
    }
}
