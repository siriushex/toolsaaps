package io.aaps.copilot.ui.foundation.screens

import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import io.aaps.copilot.R
import io.aaps.copilot.ui.MainUiState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAiCredentialTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun clearWindowProtection() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @After
    fun restoreWindowProtection() {
        composeRule.activityRule.scenario.onActivity { activity ->
            WindowSecureFlagController.onActivityDestroyed(activity.window)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Test
    fun credentialDraftUsesPasswordEditorWithoutSuggestionFlags() {
        setCredentialContent(
            state = baseState().copy(
                aiCredential = AiCredentialUiState(configured = false, busy = false)
            )
        )

        composeRule.onNodeWithTag("ai_credential_replace")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("ai_credential_draft")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performClick()
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.onActivity { activity ->
            val editorInfo = EditorInfo()
            val inputConnection = activity.currentFocus?.onCreateInputConnection(editorInfo)
            assertNotNull(inputConnection)
            assertEquals(
                InputType.TYPE_CLASS_TEXT,
                editorInfo.inputType and InputType.TYPE_MASK_CLASS
            )
            assertEquals(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                editorInfo.inputType and InputType.TYPE_MASK_VARIATION
            )
            assertEquals(0, editorInfo.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            assertEquals(0, editorInfo.inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
        }
    }

    @Test
    fun credentialDraftClearsOnPauseAndOwnedSecureFlagIsReleased() {
        setCredentialContent(
            state = baseState().copy(
                aiCredential = AiCredentialUiState(configured = false, busy = false)
            )
        )

        openCredentialDialogAndType("synthetic-pause-draft")
        assertSecureFlag(expected = true)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_credential_draft").assertDoesNotExist()
        assertSecureFlag(expected = true)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_credential_draft").assertDoesNotExist()
        waitForSecureFlag(expected = false)
    }

    @Test
    fun credentialDraftClearsOnStop() {
        setCredentialContent(
            state = baseState().copy(
                aiCredential = AiCredentialUiState(configured = false, busy = false)
            )
        )

        openCredentialDialogAndType("synthetic-stop-draft")
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_credential_draft").assertDoesNotExist()
        assertSecureFlag(expected = true)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_credential_draft").assertDoesNotExist()
        waitForSecureFlag(expected = false)
    }

    @Test
    fun credentialDialogReleasesOnlyTheSecureFlagItAdded() {
        setCredentialContent(
            state = baseState().copy(
                aiCredential = AiCredentialUiState(configured = false, busy = false)
            )
        )

        openCredentialDialogAndType("synthetic-owned-flag-draft")
        assertSecureFlag(expected = true)
        composeRule.onNodeWithTag("ai_credential_cancel").performClick()
        composeRule.waitForIdle()
        waitForSecureFlag(expected = false)

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        composeRule.onNodeWithTag("ai_credential_replace").performClick()
        assertSecureFlag(expected = true)
        composeRule.onNodeWithTag("ai_credential_cancel").performClick()
        composeRule.waitForIdle()
        assertSecureFlag(expected = true)
    }

    @Test
    fun secondTrackedOwnerKeepsWindowProtectedAfterDialogRelease() {
        setCredentialContent(
            state = baseState().copy(
                aiCredential = AiCredentialUiState(configured = false, busy = false)
            )
        )
        openCredentialDialogAndType("synthetic-second-owner-draft")

        var secondOwner: WindowSecureFlagLease? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            secondOwner = WindowSecureFlagController.acquire(
                window = activity.window,
                lifecycleOwner = activity,
                frameView = activity.window.decorView
            )
        }
        composeRule.onNodeWithTag("ai_credential_cancel").performClick()
        composeRule.waitForIdle()

        assertSecureFlag(expected = true)
        composeRule.activityRule.scenario.onActivity {
            WindowSecureFlagController.release(requireNotNull(secondOwner))
        }
        waitForSecureFlag(expected = false)
    }

    @Test
    fun credentialDialogPreservesPreExistingSecureFlagWhenActivityBackgrounds() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setCredentialContent(
            state = baseState().copy(
                aiCredential = AiCredentialUiState(configured = false, busy = false)
            )
        )

        openCredentialDialogAndType("synthetic-pre-existing-flag-draft")
        assertSecureFlag(expected = true)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_credential_draft").assertDoesNotExist()
        assertSecureFlag(expected = true)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_credential_draft").assertDoesNotExist()
        assertSecureFlag(expected = true)
    }

    @Test
    fun configuredCleanupPendingUsesOnlyConfiguredStableStatus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        setCredentialContent(
            state = baseState().copy(
                aiCredential = AiCredentialUiState(
                    configured = true,
                    busy = false,
                    legacyCleanupPending = true
                )
            )
        )

        composeRule.onNodeWithTag("ai_credential_status")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.settings_ai_credential_configured))
    }

    @Test
    fun unconfiguredCredential_addCancelClearsDraftAndSaveIsOneShot() {
        val saved = mutableListOf<String>()
        val state = baseState().copy(
            aiCredential = AiCredentialUiState(configured = false, busy = false)
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onAiCredentialReplace = { saved += it }
                )
            }
        }

        composeRule.onNodeWithTag("ai_credential_status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_credential_replace").performClick()
        composeRule.onNodeWithTag("ai_credential_draft").performTextInput("sk-transient")
        composeRule.onNodeWithTag("ai_credential_cancel").performClick()
        composeRule.onNodeWithText("sk-transient", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("ai_credential_replace").performClick()
        composeRule.onNodeWithTag("ai_credential_draft").assertTextEquals("")
        composeRule.onNodeWithTag("ai_credential_draft").performTextInput("sk-new")
        composeRule.onNodeWithTag("ai_credential_save").performClick()

        composeRule.runOnIdle { assertEquals(listOf("sk-new"), saved) }
        composeRule.onNodeWithText("sk-new", substring = true).assertDoesNotExist()
    }

    @Test
    fun configuredCredential_deleteRequiresExplicitConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var deletes = 0
        val state = baseState().copy(
            aiCredential = AiCredentialUiState(configured = true, busy = false)
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onAiCredentialDelete = { deletes += 1 }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_ai_credential_configured))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("ai_credential_delete").performClick()
        composeRule.onNodeWithTag("ai_credential_delete_cancel").performClick()
        composeRule.runOnIdle { assertEquals(0, deletes) }

        composeRule.onNodeWithTag("ai_credential_delete").performClick()
        composeRule.onNodeWithTag("ai_credential_delete_confirm").performClick()
        composeRule.runOnIdle { assertEquals(1, deletes) }
    }

    @Test
    fun migrationFailureShowsSanitizedResetWithConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var resets = 0
        val state = baseState().copy(
            aiCredential = AiCredentialUiState(
                configured = false,
                busy = false,
                migrationError = true
            )
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onAiCredentialDelete = { resets += 1 }
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.settings_ai_credential_migration_error)
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("sk-", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("ai_credential_replace").assertDoesNotExist()

        composeRule.onNodeWithTag("ai_credential_reset")
            .assertTextEquals(context.getString(R.string.settings_ai_credential_reset))
            .performClick()
        composeRule.onNodeWithTag("ai_credential_reset_cancel").performClick()
        composeRule.runOnIdle { assertEquals(0, resets) }

        composeRule.onNodeWithTag("ai_credential_reset").performClick()
        composeRule.onNodeWithTag("ai_credential_reset_confirm").performClick()
        composeRule.runOnIdle { assertEquals(1, resets) }
    }

    private fun baseState(): SettingsUiState =
        MainUiState().toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        )

    private fun setCredentialContent(state: SettingsUiState) {
        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {}
                )
            }
        }
    }

    private fun openCredentialDialogAndType(value: String) {
        composeRule.onNodeWithTag("ai_credential_replace")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("ai_credential_draft").performTextInput(value)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_credential_draft")
            .assertIsDisplayed()
            .assertTextEquals(value)
    }

    private fun assertSecureFlag(expected: Boolean) {
        composeRule.runOnIdle {
            val flags = composeRule.activity.window.attributes.flags
            assertEquals(
                expected,
                flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
        }
    }

    private fun waitForSecureFlag(expected: Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val flags = composeRule.activity.window.attributes.flags
            (flags and WindowManager.LayoutParams.FLAG_SECURE != 0) == expected
        }
    }

}
