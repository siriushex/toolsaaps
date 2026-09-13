package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.R
import io.aaps.copilot.security.ServerAiConnectionError
import io.aaps.copilot.security.ServerAiConnectionPhase
import io.aaps.copilot.security.ServerAiConnectionState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServerAiConnectionSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun disconnectedActivationSubmitsOnceAndClearsTransientCode() {
        val submitted = mutableListOf<String>()
        var state by mutableStateOf(disconnectedState())

        composeRule.setContent {
            AapsCopilotTheme {
                ServerAiConnectionSection(
                    state = state,
                    onActivate = { code ->
                        submitted += code
                        state = disconnectedState().copy(
                            phase = ServerAiConnectionPhase.CONNECTING
                        )
                    },
                    onResume = {},
                    onCheck = {}
                )
            }
        }

        captureSection("server-ai-code-empty.png")
        composeRule.onNodeWithTag("server_ai_access_code")
            .performTextInput("abcd efgh jkmn pqrs")
        composeRule.onNodeWithTag("server_ai_activate")
            .assertIsEnabled()
            .performClick()
            .assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(listOf("abcd efgh jkmn pqrs"), submitted)
            state = disconnectedState().copy(
                phase = ServerAiConnectionPhase.ERROR,
                error = ServerAiConnectionError.INVALID_CODE
            )
        }
        composeRule.onNodeWithTag("server_ai_activate").assertIsNotEnabled()
    }

    @Test
    fun resumableOperationHidesReplacementCodeAndInvokesResume() {
        var resumes = 0
        val state = disconnectedState().copy(
            phase = ServerAiConnectionPhase.ERROR,
            canResume = true,
            error = ServerAiConnectionError.NETWORK
        )

        composeRule.setContent {
            AapsCopilotTheme {
                ServerAiConnectionSection(
                    state = state,
                    onActivate = { error("activation must remain locked") },
                    onResume = { resumes += 1 },
                    onCheck = {}
                )
            }
        }

        composeRule.onNodeWithTag("server_ai_access_code").assertDoesNotExist()
        composeRule.onNodeWithTag("server_ai_activate").assertDoesNotExist()
        composeRule.onNodeWithTag("server_ai_resume")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, resumes) }
    }

    @Test
    fun sameErrorPhaseWithNewRevisionReleasesRetryControls() {
        val submitted = mutableListOf<String>()
        var state by mutableStateOf(
            disconnectedState().copy(
                phase = ServerAiConnectionPhase.ERROR,
                error = ServerAiConnectionError.INVALID_CODE,
                revision = 1L
            )
        )

        composeRule.setContent {
            AapsCopilotTheme {
                ServerAiConnectionSection(
                    state = state,
                    onActivate = { code ->
                        submitted += code
                        state = state.copy(revision = 2L)
                    },
                    onResume = {},
                    onCheck = {}
                )
            }
        }

        composeRule.onNodeWithTag("server_ai_access_code")
            .performTextInput("abcd efgh jkmn pqrs")
        composeRule.onNodeWithTag("server_ai_activate").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("abcd efgh jkmn pqrs"), submitted)
        }
        composeRule.onNodeWithTag("server_ai_access_code")
            .performTextInput("wxyz tvwx rstu pqrs")
        composeRule.onNodeWithTag("server_ai_activate").assertIsEnabled()
    }

    @Test
    fun activeSubscriptionDoesNotClaimAnalysisConnectionWhenInferenceIsUnavailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var checks = 0
        val state = disconnectedState().copy(
            phase = ServerAiConnectionPhase.ACTIVE,
            subscriptionExpiresMs = 1_900_000_000_000L,
            inferenceReady = false,
            hasStoredSession = true
        )

        composeRule.setContent {
            AapsCopilotTheme {
                ServerAiConnectionSection(
                    state = state,
                    onActivate = {},
                    onResume = {},
                    onCheck = { checks += 1 }
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.settings_server_ai_analysis_not_connected)
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("server_ai_subscription_expiry").assertIsDisplayed()
        composeRule.onNodeWithTag("server_ai_access_code").assertDoesNotExist()
        captureSection("server-ai-subscription-synthetic.png")
        composeRule.onNodeWithTag("server_ai_check")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, checks) }
    }

    private fun captureSection(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = composeRule.onNodeWithTag("server_ai_connection_section")
            .captureToImage().asAndroidBitmap()
        File(context.filesDir, name).outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun disconnectedState() = ServerAiConnectionState(
        phase = ServerAiConnectionPhase.DISCONNECTED,
        subscriptionExpiresMs = null,
        inferenceReady = false,
        canResume = false,
        hasStoredSession = false,
        error = null
    )
}
