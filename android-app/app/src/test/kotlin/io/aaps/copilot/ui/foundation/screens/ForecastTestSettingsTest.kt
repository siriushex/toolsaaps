package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.service.ForecastTestState
import io.aaps.copilot.service.ForecastTestStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class ForecastTestSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun enableRequiresConfirmationAndCancelDoesNotStart() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val changes = mutableListOf<Boolean>()
        try {
            activity.get().setContent { MaterialTheme { ForecastTestSettings(ForecastTestState()) { changes += it } } }
            compose.onNodeWithTag("forecast_test_toggle").assertIsOff().performClick()
            assertThat(changes).isEmpty()
            compose.onNodeWithText("Cancel").performClick()
            assertThat(changes).isEmpty()
            compose.onNodeWithTag("forecast_test_toggle").performClick()
            compose.onNodeWithText("Every minute, until stopped").assertExists()
            compose.onNodeWithText("Start test").performClick()
            assertThat(changes).containsExactly(true)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun stopIsImmediateAndCompletedCountIsVisible() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val changes = mutableListOf<Boolean>()
        try {
            activity.get().setContent { MaterialTheme {
                ForecastTestSettings(ForecastTestState(active = true, status = ForecastTestStatus.COMPLETED,
                    elapsedMinutes = 125, completedCycles = 8, lastDurationMs = 230)) { changes += it }
            } }
            compose.onNodeWithText("Completed: 8. Last attempt: 230 ms").assertExists()
            compose.onNodeWithText("Running for 125 min").assertExists()
            compose.onNodeWithTag("forecast_test_toggle").assertIsOn().performClick()
            assertThat(changes).containsExactly(false)
        } finally { activity.pause().stop().destroy() }
    }
}
