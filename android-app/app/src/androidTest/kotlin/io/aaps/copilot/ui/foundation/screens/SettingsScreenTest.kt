package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.R
import io.aaps.copilot.config.ClinicalAiModelCatalog
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.config.TargetManagerTimingSetting
import io.aaps.copilot.ui.MainUiState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun targetDiagnosticsAreReadOnlyAndTimingSelectionReflectsImmediately() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writes = mutableListOf<Pair<TargetManagerTimingSetting, Int>>()
        var state by mutableStateOf(baseState(ClinicalAiProviderId.OPENAI).copy(
            safetyMaxTargetMmol = 8.0,
            adaptiveControllerRetargetMinutes = 5
        ))
        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onTargetManagerTimingChange = { setting, minutes ->
                        writes += setting to minutes
                        state = state.copy(adaptiveControllerRetargetMinutes = minutes)
                    }
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.settings_tab_advanced)).performClick()
        composeRule.onNodeWithTag("target_manager_diagnostics_open").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_target_manager_diagnostics").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<Pair<TargetManagerTimingSetting, Int>>(), writes) }
        composeRule.onNodeWithText(context.getString(R.string.overview_uam_dialog_close)).performClick()
        composeRule.onNodeWithTag("target_manager_retarget_15").performScrollTo().performClick().assertIsSelected()
        composeRule.runOnIdle { assertEquals(listOf(TargetManagerTimingSetting.RETARGET to 15), writes) }
    }

    @Test
    fun targetManagerPriorityEnableConfirmsCancelDoesNotWriteAndDisableIsImmediate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writes = mutableListOf<Boolean>()
        var state by mutableStateOf(
            baseState(ClinicalAiProviderId.OPENAI).copy(
                targetManagerCopilotPriorityEnabled = false,
                targetManagerPolicyRevision = 7L
            )
        )
        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onTargetManagerCopilotPriorityChange = { enabled ->
                        writes += enabled
                        state = state.copy(
                            targetManagerCopilotPriorityEnabled = enabled,
                            targetManagerPolicyRevision = state.targetManagerPolicyRevision + 1L
                        )
                    }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_tab_advanced)).performClick()
        composeRule.onNodeWithTag("target_manager_priority_switch")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        composeRule.onNodeWithTag("target_manager_priority_confirm_dialog").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<Boolean>(), writes) }

        composeRule.onNodeWithTag("target_manager_priority_cancel").performClick()
        composeRule.onNodeWithTag("target_manager_priority_switch").assertIsOff()
        composeRule.runOnIdle { assertEquals(emptyList<Boolean>(), writes) }

        composeRule.onNodeWithTag("target_manager_priority_switch").performClick()
        composeRule.onNodeWithTag("target_manager_priority_confirm").performClick()
        composeRule.onNodeWithTag("target_manager_priority_switch").assertIsOn()
        composeRule.runOnIdle { assertEquals(listOf(true), writes) }

        composeRule.onNodeWithTag("target_manager_priority_switch").performClick()
        composeRule.onNodeWithTag("target_manager_priority_switch").assertIsOff()
        composeRule.runOnIdle { assertEquals(listOf(true, false), writes) }
    }

    @Test
    fun energyProfileIsOffByDefaultInAdvancedSettings() {
        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = baseState(ClinicalAiProviderId.OPENAI),
                    onVerboseLogsToggle = {},
                    onProModeToggle = {}
                )
            }
        }

        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.settings_tab_advanced)
        ).performClick()
        composeRule.onNodeWithTag("energy-profile-enabled")
            .performScrollTo()
            .assertIsOff()
    }

    @Test
    fun plannedActivityOverlapIsVisibleAndDisablesSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = plannedEvent(id = "first", start = "2026-08-10T10:00")
        val second = plannedEvent(id = "second", start = "2026-08-10T11:00")
        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = baseState(ClinicalAiProviderId.OPENAI).copy(
                        energyProfile = EnergyProfileSettingsUiState(
                            plannedEvents = listOf(first, second)
                        )
                    ),
                    onVerboseLogsToggle = {},
                    onProModeToggle = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_tab_advanced)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.energy_activity_and_schedule))
            .performScrollTo()
            .performClick()
        composeRule.onAllNodesWithContentDescription(
            context.getString(R.string.energy_activity_edit_event)
        )[1].performClick()
        composeRule.onNodeWithTag("planned_activity_start")
            .performTextReplacement("2026-08-10T10:30")

        composeRule.onNodeWithText(context.getString(R.string.energy_activity_events_overlap))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("planned_activity_save").assertIsNotEnabled()
    }

    @Test
    fun providerSelectionUpdatesImmediatelyAndNativeProvidersHideCompatibleControls() {
        var selected = ClinicalAiProviderId.OPENAI
        var state by mutableStateOf(baseState(ClinicalAiProviderId.OPENAI))

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onClinicalAiProviderChange = { provider ->
                        selected = provider
                        state = baseState(provider)
                    }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_ai_provider_ANTHROPIC")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(ClinicalAiProviderId.ANTHROPIC, selected)
        }
        composeRule.onNodeWithTag("clinical_ai_endpoint").assertDoesNotExist()
        composeRule.onNodeWithTag("clinical_ai_protocol_RESPONSES").assertDoesNotExist()
    }

    @Test
    fun automaticCauseAnalysisToggleUpdatesImmediatelyWithoutConnectionTest() {
        var toggledTo: Boolean? = null
        var connectionTests = 0
        var state by mutableStateOf(
            baseState(ClinicalAiProviderId.OPENAI).copy(
                clinicalAi = nativeUiState(ClinicalAiProviderId.OPENAI).copy(
                    automaticCauseAnalysisEnabled = true
                )
            )
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onAutomaticCauseAnalysisToggle = { enabled ->
                        toggledTo = enabled
                        state = state.copy(
                            clinicalAi = state.clinicalAi.copy(
                                automaticCauseAnalysisEnabled = enabled
                            )
                        )
                    },
                    onClinicalAiTestConnection = { connectionTests += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_ai_automatic_cause_analysis")
            .performScrollTo()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        composeRule.runOnIdle {
            assertEquals(false, toggledTo)
            assertEquals(0, connectionTests)
        }
    }

    @Test
    fun compatibleProviderShowsEndpointProtocolAndInvalidDraftDisablesActions() {
        val state = baseState(ClinicalAiProviderId.OPENAI_COMPATIBLE).copy(
            clinicalAi = compatibleUiState(
                localValidationError = ClinicalAiSettingsValidationErrorUi.INVALID_ENDPOINT,
                canTestConnection = false,
                canSendReport = false
            )
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {}
                )
            }
        }

        composeRule.onNodeWithTag("clinical_ai_endpoint")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_ai_protocol_RESPONSES")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_ai_endpoint_error").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_ai_test_connection").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertFalse(state.clinicalAi.canSendReport)
        }
    }

    @Test
    fun legacyAiApiUrlAndClinicalEndpointFireIndependentCallbacks() {
        var legacySaved: String? = null
        var clinicalEndpoint: String? = null
        val state = baseState(ClinicalAiProviderId.OPENAI_COMPATIBLE).copy(
            aiApiUrl = "https://legacy-chat.example/v1"
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onAiApiSettingsSave = { legacySaved = it },
                    onClinicalAiEndpointChange = { clinicalEndpoint = it }
                )
            }
        }

        composeRule.onNodeWithTag("legacy_ai_api_url")
            .performScrollTo()
            .assertTextContains("https://legacy-chat.example/v1")
            .performTextReplacement("https://legacy-chat-2.example/v1")
        composeRule.onNodeWithTag("legacy_ai_api_url_save").performClick()
        composeRule.onNodeWithTag("clinical_ai_endpoint")
            .performScrollTo()
            .performTextReplacement("https://clinical-2.example/v1")

        composeRule.runOnIdle {
            assertEquals("https://legacy-chat-2.example/v1", legacySaved)
            assertEquals("https://clinical-2.example/v1", clinicalEndpoint)
        }
    }

    @Test
    fun customModelFieldAppearsOnlyForCustomSelection() {
        var state by mutableStateOf(baseState(ClinicalAiProviderId.OPENAI))

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onClinicalAiModelChange = { model ->
                        state = state.copy(
                            clinicalAi = state.clinicalAi.copy(
                                effectiveConfig = ClinicalAiProviderConfig(
                                    ClinicalAiProviderId.OPENAI,
                                    model
                                ),
                                customModelSelected = false
                            )
                        )
                    },
                    onClinicalAiCustomModelChange = {
                        state = state.copy(
                            clinicalAi = state.clinicalAi.copy(
                                customModelSelected = true,
                                customModelDraft = it
                            )
                        )
                    }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_ai_custom_model").assertDoesNotExist()
        composeRule.onNodeWithTag("clinical_ai_model_custom")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("clinical_ai_custom_model").assertIsDisplayed()
    }

    @Test
    fun credentialAndConnectionResultExposeOnlySanitizedMetadata() {
        var connectionTests = 0
        val state = baseState(ClinicalAiProviderId.GEMINI).copy(
            clinicalAi = nativeUiState(ClinicalAiProviderId.GEMINI).copy(
                credential = AiCredentialUiState(configured = true, busy = false),
                connectionTest = ClinicalAiConnectionTestUiState(
                    phase = ClinicalAiConnectionTestPhaseUi.SUCCESS,
                    providerId = ClinicalAiProviderId.GEMINI,
                    modelId = ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.GEMINI).id
                ),
                canTestConnection = true,
                canSendReport = true
            )
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onClinicalAiTestConnection = { connectionTests += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("ai_credential_status")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_ai_test_connection")
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, connectionTests)
        }
        composeRule.onNodeWithTag("clinical_ai_connection_result")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("GEMINI", substring = true)
            .assertTextContains("gemini-", substring = true)
        composeRule.onNodeWithText("sk-secret", substring = true).assertDoesNotExist()
    }

    @Test
    fun credentialDialogsCaptureProviderAndDismissWhenProviderChanges() {
        var state by mutableStateOf(baseState(ClinicalAiProviderId.GEMINI))
        val replacements =
            mutableListOf<Pair<ClinicalAiProviderId, String>>()
        val deletions = mutableListOf<ClinicalAiProviderId>()

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onClinicalAiCredentialReplace = { providerId, value ->
                        replacements += providerId to value
                    },
                    onClinicalAiCredentialDelete = { deletions += it }
                )
            }
        }

        composeRule.onNodeWithTag("ai_credential_replace")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("ai_credential_draft")
            .performTextInput("discarded-secret")
        composeRule.runOnIdle {
            state = baseState(ClinicalAiProviderId.ANTHROPIC)
        }
        composeRule.onNodeWithTag("ai_credential_draft").assertDoesNotExist()

        composeRule.onNodeWithTag("ai_credential_replace").performClick()
        composeRule.onNodeWithTag("ai_credential_draft")
            .performTextInput("captured-secret")
        composeRule.onNodeWithTag("ai_credential_save").performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(ClinicalAiProviderId.ANTHROPIC to "captured-secret"),
                replacements
            )
        }

        composeRule.runOnIdle {
            val clinicalAi = nativeUiState(ClinicalAiProviderId.ANTHROPIC)
            state = baseState(ClinicalAiProviderId.ANTHROPIC).copy(
                clinicalAi = clinicalAi.copy(
                    credential = AiCredentialUiState(configured = true, busy = false)
                )
            )
        }
        composeRule.onNodeWithTag("ai_credential_delete")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            val clinicalAi = nativeUiState(ClinicalAiProviderId.GEMINI)
            state = baseState(ClinicalAiProviderId.GEMINI).copy(
                clinicalAi = clinicalAi.copy(
                    credential = AiCredentialUiState(configured = true, busy = false)
                )
            )
        }
        composeRule.onNodeWithTag("ai_credential_delete_confirm").assertDoesNotExist()

        composeRule.onNodeWithTag("ai_credential_delete")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("ai_credential_delete_confirm").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(ClinicalAiProviderId.GEMINI), deletions)
        }
    }

    @Test
    fun saveAndCredentialReadFailuresShowSanitizedResetUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseClinicalAi = nativeUiState(ClinicalAiProviderId.GEMINI)
        val state = baseState(ClinicalAiProviderId.GEMINI).copy(
            clinicalAi = baseClinicalAi.copy(
                credential = AiCredentialUiState(
                    configured = true,
                    busy = false,
                    readError = true
                ),
                saveError = true,
                labels = baseClinicalAi.labels.copy(
                    configSaveFailed = context.getString(
                        R.string.settings_clinical_ai_config_save_failed
                    )
                )
            )
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {}
                )
            }
        }

        composeRule.onNodeWithTag("clinical_ai_save_error")
            .performScrollTo()
            .assertTextContains(
                context.getString(R.string.settings_clinical_ai_config_save_failed)
            )
        composeRule.onNodeWithTag("ai_credential_replace").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_credential_reset").assertIsDisplayed()
    }

    @Test
    fun failedConnectionResultShowsOnlySanitizedProviderAndModel() {
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        val state = baseState(ClinicalAiProviderId.OPENAI).copy(
            clinicalAi = nativeUiState(ClinicalAiProviderId.OPENAI).copy(
                connectionTest = ClinicalAiConnectionTestUiState(
                    phase = ClinicalAiConnectionTestPhaseUi.FAILURE,
                    providerId = config.providerId,
                    modelId = config.modelId
                )
            )
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {}
                )
            }
        }

        composeRule.onNodeWithTag("clinical_ai_connection_result")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Connection failed", substring = true)
            .assertTextContains("OPENAI", substring = true)
            .assertTextContains(config.modelId, substring = true)
        composeRule.onNodeWithText("sk-secret", substring = true).assertDoesNotExist()
    }

    private fun baseState(providerId: ClinicalAiProviderId): SettingsUiState =
        MainUiState().toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        ).copy(
            clinicalAi = when (providerId) {
                ClinicalAiProviderId.OPENAI_COMPATIBLE -> compatibleUiState()
                else -> nativeUiState(providerId)
            }
        )

    private fun plannedEvent(id: String, start: String) = PlannedActivityEventUi(
        eventId = id,
        enabled = true,
        title = id,
        activityType = "AEROBIC",
        intensity = "MEDIUM",
        localStartIso = start,
        durationMinutes = 60,
        timezoneId = "UTC",
        recurrenceDaysMask = 0,
        recurrenceEndEpochDay = null,
        revision = 0L,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun nativeUiState(
        providerId: ClinicalAiProviderId
    ): ClinicalAiSettingsUiState {
        val config = ClinicalAiProviderConfig(
            providerId = providerId,
            modelId = ClinicalAiModelCatalog.defaultFor(providerId).id
        )
        return ClinicalAiSettingsUiState(
            effectiveConfig = config,
            selectedProvider = providerId,
            modelPresets = ClinicalAiModelCatalog.forProvider(providerId),
            credential = AiCredentialUiState(configured = false, busy = false),
            canTestConnection = true,
            canSendReport = true
        )
    }

    private fun compatibleUiState(
        localValidationError: ClinicalAiSettingsValidationErrorUi? = null,
        canTestConnection: Boolean = true,
        canSendReport: Boolean = true
    ) = ClinicalAiSettingsUiState(
        effectiveConfig = ClinicalAiProviderConfig.defaultOpenAi(),
        selectedProvider = ClinicalAiProviderId.OPENAI_COMPATIBLE,
        modelPresets = ClinicalAiModelCatalog.forProvider(
            ClinicalAiProviderId.OPENAI_COMPATIBLE
        ),
        customModelSelected = true,
        customModelDraft = "local-model",
        endpointDraft = "https://models.example/v1",
        selectedProtocol = OpenAiCompatibleProtocol.RESPONSES,
        credential = AiCredentialUiState(configured = false, busy = false),
        localValidationError = localValidationError,
        canTestConnection = canTestConnection,
        canSendReport = canSendReport
    )
}
