package io.aaps.copilot.ui.foundation.screens

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.app.ActivityOptionsCompat
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiAnalysisClinicalReportTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun localReadySendFirstOpensDisclosureWithoutDispatching() {
        var sends = 0
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(
                        ClinicalReportUiState(
                            phase = ClinicalReportPhaseUi.LOCAL_READY,
                            summary24h = summary(days = 1),
                            summary7d = summary(days = 7),
                            summary30d = summary(days = 30),
                            canSend = true,
                            disclosure = disclosure()
                        )
                    ),
                    onSendClinicalReport = { sends += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_section").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_summary_1").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_summary_7").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_summary_30").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_send")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("clinical_report_send_disclosure").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_disclosure_cancel")
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains("Cancel")
        composeRule.onNodeWithTag("clinical_report_disclosure_confirm")
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains("Confirm and send")
        composeRule.onNodeWithTag("clinical_report_disclosure_data")
            .assertTextContains("Detailed data for the last 24 hours")
            .assertTextContains(
                "Complete canonical time series for glucose, insulin, real carbs, " +
                    "and targets for the last 7 and 30 days"
            )
            .assertTextContains(
                "Local summaries, coverage, and data quality, when applicable"
            )
        composeRule.onNodeWithTag("clinical_report_disclosure_provider")
            .assertTextContains("OpenAI")
        composeRule.onNodeWithTag("clinical_report_disclosure_model")
            .assertTextContains("gpt-5.6-terra")
        composeRule.onNodeWithTag("clinical_report_disclosure_endpoint")
            .assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, sends) }
    }

    @Test
    fun localSummaryRendersAapsCarbsAsDiagnosticReference() {
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(
                        ClinicalReportUiState(
                            phase = ClinicalReportPhaseUi.LOCAL_READY,
                            summary24h = summary(days = 1).copy(aapsCarbs = 99.0),
                            summary7d = summary(days = 7),
                            summary30d = summary(days = 30)
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_aaps_carbs_1").assertExists()
        composeRule.onNodeWithText("AAPS carbohydrate reference (diagnostic)").assertExists()
        composeRule.onNodeWithText("99.0 g").assertExists()
    }

    @Test
    fun disclosureCancelClosesDialogAndSendsNothing() {
        var sends = 0
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(
                        ClinicalReportUiState(
                            phase = ClinicalReportPhaseUi.LOCAL_READY,
                            summary7d = summary(days = 7),
                            summary30d = summary(days = 30),
                            canSend = true,
                            disclosure = disclosure()
                        )
                    ),
                    onSendClinicalReport = { sends += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_send").performClick()
        composeRule.onNodeWithTag("clinical_report_disclosure_cancel").performClick()
        composeRule.onNodeWithTag("clinical_report_send_disclosure").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, sends) }
    }

    @Test
    fun compatibleDisclosureShowsHostnameOnlyAndNoCredentialState() {
        val configIdentity = "c".repeat(64)
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(
                        ClinicalReportUiState(
                            phase = ClinicalReportPhaseUi.LOCAL_READY,
                            summary7d = summary(days = 7),
                            summary30d = summary(days = 30),
                            canSend = true,
                            disclosure = disclosure(
                                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                                model = "private-model",
                                endpointHost = "gateway.example.com",
                                configIdentity = configIdentity
                            )
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_send").performClick()
        composeRule.onNodeWithTag("clinical_report_disclosure_provider")
            .assertTextContains("OpenAI compatible")
        composeRule.onNodeWithTag("clinical_report_disclosure_model")
            .assertTextContains("private-model")
        composeRule.onNodeWithTag("clinical_report_disclosure_endpoint")
            .assertTextContains("gateway.example.com")
        composeRule.onNodeWithText(
            "https://gateway.example.com/private/path?token=secret",
            substring = true
        ).assertDoesNotExist()
        composeRule.onNodeWithText("credential", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("key", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText(configIdentity, substring = true).assertDoesNotExist()
    }

    @Test
    fun disclosureBodyScrollsAtLargeFontWhileTitleAndActionsRemainReachable() {
        val configuration = Configuration(
            composeRule.activity.resources.configuration
        ).apply {
            screenWidthDp = 320
            screenHeightDp = 320
        }
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(baseDensity.density, fontScale = 2f)
            ) {
                AapsCopilotTheme {
                    AiAnalysisScreen(
                        state = screenState(
                            ClinicalReportUiState(
                                phase = ClinicalReportPhaseUi.LOCAL_READY,
                                summary7d = summary(7),
                                summary30d = summary(30),
                                canSend = true,
                                disclosure = disclosure(
                                    providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                                    model = "model-" + "x".repeat(100),
                                    endpointHost =
                                        "long-subdomain.models.example.com",
                                    configIdentity = "d".repeat(64)
                                )
                            )
                        )
                    )
                }
            }
        }

        composeRule.onNodeWithTag("clinical_report_send").performClick()

        composeRule.onNodeWithTag("clinical_report_send_disclosure").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_disclosure_scroll")
            .assertIsDisplayed()
            .assert(hasScrollAction())
        composeRule.onNodeWithTag("clinical_report_disclosure_cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_disclosure_confirm").assertIsDisplayed()
    }

    @Test
    fun disclosureConfirmAfterRecompositionAndDoubleTapDispatchesExactlyOnce() {
        val sends = mutableListOf<ClinicalReportDisclosureUi>()
        val disclosed = disclosure()
        val clinical = mutableStateOf(
            ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.LOCAL_READY,
                summary7d = summary(days = 7),
                summary30d = summary(days = 30),
                canSend = true,
                disclosure = disclosed
            )
        )
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(clinical.value),
                    onSendClinicalReport = { sends += it }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_send").performClick()
        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(progress = 0.25f)
        }
        composeRule.onNodeWithTag("clinical_report_send_disclosure").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_disclosure_confirm")
            .performTouchInput {
                click()
                click()
            }
        composeRule.onNodeWithTag("clinical_report_send_disclosure").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf(disclosed), sends) }

        composeRule.onNodeWithTag("clinical_report_send")
            .assertIsNotEnabled()
            .performClick()
        composeRule.onNodeWithTag("clinical_report_send_disclosure").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf(disclosed), sends) }
    }

    @Test
    fun changedDisclosureIdentityDismissesAndRequiresReopen() {
        var sends = 0
        val clinical = mutableStateOf(
            ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.LOCAL_READY,
                summary7d = summary(days = 7),
                summary30d = summary(days = 30),
                canSend = true,
                disclosure = disclosure()
            )
        )
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(clinical.value),
                    onSendClinicalReport = { sends += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_send").performClick()
        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                disclosure = disclosure(
                    providerId = ClinicalAiProviderId.ANTHROPIC,
                    model = "claude-sonnet-5"
                )
            )
        }
        composeRule.onNodeWithTag("clinical_report_send_disclosure").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, sends) }

        composeRule.onNodeWithTag("clinical_report_send").performClick()
        composeRule.onNodeWithTag("clinical_report_disclosure_provider")
            .assertTextContains("Anthropic")
        composeRule.onNodeWithTag("clinical_report_disclosure_model")
            .assertTextContains("claude-sonnet-5")
        composeRule.onNodeWithTag("clinical_report_disclosure_confirm").performClick()
        composeRule.runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun completedMetadataShowsProviderWithModelAndSchema() {
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(
                        ClinicalReportUiState(
                            phase = ClinicalReportPhaseUi.COMPLETE,
                            summary7d = summary(7),
                            summary30d = summary(30),
                            complete = completeReport(
                                providerId = ClinicalAiProviderId.GEMINI
                            )
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_complete_provider")
            .assertTextContains("Provider: Gemini")
        composeRule.onNodeWithText("Model: gpt-5-mini").assertIsDisplayed()
        composeRule.onNodeWithText("Schema: clinical_advisory_report v4").assertIsDisplayed()
    }

    @Test
    fun savePdfIsVisibleForLocalReadyAndCompleteButHiddenWithoutLocalReport() {
        val clinical = mutableStateOf(
            ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.IDLE
            )
        )
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(state = screenState(clinical.value))
            }
        }

        composeRule.onNodeWithTag("clinical_report_save_pdf").assertDoesNotExist()

        composeRule.runOnIdle {
            clinical.value = ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.LOCAL_READY,
                summary7d = summary(7),
                summary30d = summary(30),
                canSavePdf = true
            )
        }
        composeRule.onNodeWithTag("clinical_report_save_pdf")
            .assertIsDisplayed()
            .assertTextContains("Save PDF")

        composeRule.runOnIdle {
            clinical.value = ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.COMPLETE,
                summary7d = summary(7),
                summary30d = summary(30),
                complete = completeReport(),
                canSavePdf = true
            )
        }
        composeRule.onNodeWithTag("clinical_report_save_pdf").assertIsDisplayed()

        composeRule.runOnIdle {
            clinical.value = ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.BUILDING
            )
        }
        composeRule.onNodeWithTag("clinical_report_save_pdf").assertDoesNotExist()
    }

    @Test
    fun savePdfLaunchesSanitizedFilenameAndResolvesReturnedUriOnce() {
        val registry = ClinicalPdfActivityResultRegistry()
        val registryOwner = ClinicalPdfActivityResultRegistryOwner(registry)
        val resolutions = mutableListOf<Pair<Long, android.net.Uri?>>()
        val ticket = ClinicalPdfExportTicketUi(
            id = 7L,
            suggestedFilename = "aaps-copilot-clinical-report-2027-01-15.pdf"
        )
        val clinical = mutableStateOf(
            ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.LOCAL_READY,
                summary7d = summary(7),
                summary30d = summary(30),
                canSavePdf = true
            )
        )
        var claims = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                AapsCopilotTheme {
                    AiAnalysisScreen(
                        state = screenState(clinical.value),
                        onBeginClinicalReportPdfExport = {
                            clinical.value = clinical.value.copy(
                                pdfExportState = ClinicalPdfExportUiState.READY,
                                pdfExportTicket = ticket
                            )
                        },
                        onClaimClinicalReportPdfExportTicket = {
                            claims += 1
                            claims == 1
                        },
                        onResolveClinicalReportPdfExport = { id, uri ->
                            resolutions += id to uri
                        }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("clinical_report_save_pdf").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(ticket.suggestedFilename), registry.launches)
            clinical.value = clinical.value.copy(canSharePdf = true)
        }
        composeRule.runOnIdle {
            assertEquals(1, registry.launches.size)
            assertEquals(1, claims)
            registry.deliver(android.net.Uri.parse("content://selected/report.pdf"))
            registry.deliver(android.net.Uri.parse("content://selected/report.pdf"))
        }

        composeRule.runOnIdle {
            assertEquals(1, resolutions.size)
            assertEquals(7L, resolutions.single().first)
        }
    }

    @Test
    fun pickerCancellationResolvesOnceAndLeavesClinicalReportVisible() {
        val registry = ClinicalPdfActivityResultRegistry()
        val registryOwner = ClinicalPdfActivityResultRegistryOwner(registry)
        val resolutions = mutableListOf<Pair<Long, android.net.Uri?>>()
        val ticket = ClinicalPdfExportTicketUi(8L, "report.pdf")
        val clinical = mutableStateOf(
            ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.LOCAL_READY,
                summary7d = summary(7),
                summary30d = summary(30),
                canSavePdf = true
            )
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                AapsCopilotTheme {
                    AiAnalysisScreen(
                        state = screenState(clinical.value),
                        onBeginClinicalReportPdfExport = {
                            clinical.value = clinical.value.copy(
                                pdfExportState = ClinicalPdfExportUiState.READY,
                                pdfExportTicket = ticket
                            )
                        },
                        onClaimClinicalReportPdfExportTicket = { true },
                        onResolveClinicalReportPdfExport = { id, uri ->
                            resolutions += id to uri
                        }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("clinical_report_save_pdf").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(ticket.suggestedFilename), registry.launches)
            registry.deliver(null)
            registry.deliver(null)
        }

        composeRule.onNodeWithTag("clinical_report_summary_7").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_summary_30").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(8L to null), resolutions)
        }
    }

    @Test
    fun pdfExportStatusIsVisibleAndSanitized() {
        val clinical = mutableStateOf(
            ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.LOCAL_READY,
                summary7d = summary(7),
                summary30d = summary(30),
                canSavePdf = true,
                pdfExportState = ClinicalPdfExportUiState.WRITING
            )
        )
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(state = screenState(clinical.value))
            }
        }

        composeRule.onNodeWithTag("clinical_report_pdf_status")
            .assertTextContains("Saving PDF")
        composeRule.onNodeWithText("content://", substring = true).assertDoesNotExist()

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                pdfExportState = ClinicalPdfExportUiState.PREPARING
            )
        }
        composeRule.onNodeWithTag("clinical_report_pdf_status")
            .assertTextContains("Preparing PDF")

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                pdfExportState = ClinicalPdfExportUiState.READY
            )
        }
        composeRule.onNodeWithTag("clinical_report_pdf_status")
            .assertTextContains("PDF ready")

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                pdfExportState = ClinicalPdfExportUiState.COMPLETE
            )
        }
        composeRule.onNodeWithTag("clinical_report_pdf_status")
            .assertTextContains("PDF saved")

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                pdfExportState = ClinicalPdfExportUiState.FAILED
            )
        }
        composeRule.onNodeWithTag("clinical_report_pdf_status")
            .assertTextContains("Could not save PDF")

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                pdfExportState = ClinicalPdfExportUiState.TOO_LARGE
            )
        }
        composeRule.onNodeWithTag("clinical_report_pdf_status")
            .assertTextContains("PDF is too large")

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                pdfExportState = ClinicalPdfExportUiState.CANCELLED
            )
        }
        composeRule.onNodeWithTag("clinical_report_pdf_status")
            .assertTextContains("PDF save cancelled")
    }

    @Test
    fun unknownOutcomeReviewDismissRecomposeAndConfirmDispatchesExactlyOnce() {
        var reviewRequests = 0
        var confirmations = 0
        val clinical = mutableStateOf(unknownState(showConfirmation = false))
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(clinical.value),
                    onRequestUnknownClinicalReportRetry = {
                        reviewRequests += 1
                        clinical.value = clinical.value.copy(showRetryConfirmation = true)
                    },
                    onDismissUnknownClinicalReportRetry = {
                        clinical.value = clinical.value.copy(showRetryConfirmation = false)
                    },
                    onConfirmUnknownClinicalReportRetry = {
                        confirmations += 1
                        clinical.value = clinical.value.copy(
                            retryFeedback = ClinicalReportRetryFeedbackUi.PENDING
                        )
                    }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_retry_confirm").assertDoesNotExist()
        composeRule.onNodeWithTag("clinical_report_review_retry")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, reviewRequests)
            assertEquals(0, confirmations)
        }
        composeRule.onNodeWithTag("clinical_report_retry_cancel")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("clinical_report_retry_confirm").assertDoesNotExist()

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(progress = 0.25f)
        }
        composeRule.onNodeWithTag("clinical_report_review_retry").performClick()
        composeRule.onNodeWithTag("clinical_report_retry_confirm")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("clinical_report_retry_confirm")
            .assertIsNotEnabled()
        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(progress = 0.5f)
        }

        composeRule.runOnIdle {
            assertEquals(2, reviewRequests)
            assertEquals(1, confirmations)
            clinical.value = clinical.value.copy(
                showRetryConfirmation = false,
                retryFeedback = null
            )
        }
        composeRule.onNodeWithTag("clinical_report_retry_confirm").assertDoesNotExist()
    }

    @Test
    fun missingLocalSummaryShowsOnlyLocalRetryAndNeverSend() {
        var localRetries = 0
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(
                        ClinicalReportUiState(
                            phase = ClinicalReportPhaseUi.FAILED,
                            failure = ClinicalReportFailureUi.LOCAL_BUILD,
                            canRetryLocalPreparation = true
                        )
                    ),
                    onRetryLocalClinicalReportPreparation = { localRetries += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("clinical_report_send").assertDoesNotExist()
        composeRule.onNodeWithTag("clinical_report_review_retry").assertDoesNotExist()
        composeRule.onNodeWithTag("clinical_report_retry_local")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, localRetries) }
    }

    @Test
    fun russianConfigurationUsesLocalizedTextDecimalCommaAndDate() {
        val configuration = Configuration(
            composeRule.activity.resources.configuration
        ).apply {
            setLocale(Locale.forLanguageTag("ru-RU"))
        }
        val localizedContext = composeRule.activity.createConfigurationContext(configuration)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides localizedContext
            ) {
                AapsCopilotTheme {
                    AiAnalysisScreen(
                        state = screenState(
                            ClinicalReportUiState(
                                phase = ClinicalReportPhaseUi.COMPLETE,
                                summary7d = summary(7),
                                summary30d = summary(30),
                                complete = completeReport()
                            )
                        )
                    )
                }
            }
        }

        composeRule.onNodeWithText("Средняя глюкоза").assertIsDisplayed()
        composeRule.onNodeWithText("7,2 mmol/L").assertIsDisplayed()
        composeRule.onNodeWithText("96,5\u00a0%").assertIsDisplayed()
        composeRule.onNodeWithText("Сформирован:", substring = true)
            .assertTextContains(".")
    }

    @Test
    fun headingCoverageProgressAndContainerSemanticsAreNotDuplicated() {
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(
                    state = screenState(
                        ClinicalReportUiState(
                            phase = ClinicalReportPhaseUi.LOCAL_READY,
                            summary7d = summary(7),
                            summary30d = summary(30),
                            canSend = true
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("Clinical compensation report").assert(
            SemanticsMatcher("is heading") {
                it.config.contains(SemanticsProperties.Heading)
            }
        )
        composeRule.onNodeWithContentDescription(
            "7 days, Coverage, 96.5%"
        ).assert(
            SemanticsMatcher("has progress semantics") {
                it.config.contains(SemanticsProperties.ProgressBarRangeInfo)
            }
        )
        composeRule.onNodeWithContentDescription("Clinical compensation report")
            .assertDoesNotExist()
    }

    @Test
    fun buildingAndUploadingExposeOneLocalizedProgressAnnouncementEach() {
        val clinical = mutableStateOf(
            ClinicalReportUiState(phase = ClinicalReportPhaseUi.BUILDING)
        )
        composeRule.setContent {
            AapsCopilotTheme {
                AiAnalysisScreen(state = screenState(clinical.value))
            }
        }

        composeRule.onAllNodesWithContentDescription(
            "Building local 7/30-day summary"
        ).assertCountEquals(1)

        composeRule.runOnIdle {
            clinical.value = ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.UPLOADING,
                summary7d = summary(7),
                summary30d = summary(30),
                completedChunks = 1,
                totalChunks = 3,
                progress = 1f / 3f,
                progressStage = ClinicalReportProgressStageUi.ANALYZING,
                canCancel = true
            )
        }
        composeRule.onAllNodesWithContentDescription(
            "Uploading chunk 1 of 3"
        ).assertCountEquals(1)

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                completedChunks = 0,
                totalChunks = 1,
                progress = 0f,
                progressStage = ClinicalReportProgressStageUi.SYNTHESIZING
            )
        }
        composeRule.onAllNodesWithContentDescription(
            "Synthesizing final report"
        ).assertCountEquals(1)

        composeRule.runOnIdle {
            clinical.value = clinical.value.copy(
                progressStage = ClinicalReportProgressStageUi.VALIDATING
            )
        }
        composeRule.onAllNodesWithContentDescription(
            "Validating final report"
        ).assertCountEquals(1)
    }

    @Test
    fun reducingStageUsesLocalizedTaggedSummaryAndCancellationKeepsOnlyLocalCards() {
        val configuration = Configuration(
            composeRule.activity.resources.configuration
        ).apply {
            setLocale(Locale.forLanguageTag("ru-RU"))
        }
        val localizedContext = composeRule.activity.createConfigurationContext(configuration)
        val clinical = mutableStateOf(
            ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.UPLOADING,
                summary7d = summary(7),
                summary30d = summary(30),
                completedChunks = 2,
                totalChunks = 4,
                progress = 0.5f,
                progressStage = ClinicalReportProgressStageUi.REDUCING,
                progressLevel = 2,
                canCancel = true
            )
        )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides localizedContext
            ) {
                AapsCopilotTheme {
                    AiAnalysisScreen(state = screenState(clinical.value))
                }
            }
        }

        composeRule.onNodeWithTag("clinical_report_progress_stage")
            .assertTextContains("Сводка 2/4")

        composeRule.runOnIdle {
            clinical.value = ClinicalReportUiState(
                phase = ClinicalReportPhaseUi.CANCELLED,
                summary7d = summary(7),
                summary30d = summary(30),
                failure = ClinicalReportFailureUi.CANCELLED,
                canSend = true
            )
        }

        composeRule.onNodeWithTag("clinical_report_summary_7").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_summary_30").assertIsDisplayed()
        composeRule.onNodeWithTag("clinical_report_progress_stage").assertDoesNotExist()
        composeRule.onNodeWithTag("clinical_report_complete_status").assertDoesNotExist()
        composeRule.onNodeWithTag("clinical_report_complete_metadata").assertDoesNotExist()
    }

    @Test
    fun constrainedLargeFontDenseCompleteReportKeepsSummaryRowsOrderedAndInBounds() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = 1.6f
                )
            ) {
                AapsCopilotTheme {
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .width(320.dp)
                            .testTag("clinical_report_320_container")
                    ) {
                        AiAnalysisScreen(
                            state = screenState(
                                ClinicalReportUiState(
                                    phase = ClinicalReportPhaseUi.COMPLETE,
                                    summary7d = summary(7),
                                    summary30d = summary(30),
                                    complete = completeReport()
                                )
                            )
                        )
                    }
                }
            }
        }

        val container = composeRule.onNodeWithTag("clinical_report_320_container")
            .fetchSemanticsNode().boundsInRoot
        val tags = listOf(
            "clinical_report_summary_7",
            "clinical_report_summary_30",
            "clinical_report_complete_status",
            "clinical_report_complete_quality",
            "clinical_report_complete_observations",
            "clinical_report_complete_safety",
            "clinical_report_complete_recommendations",
            "clinical_report_complete_questions",
            "clinical_report_complete_metadata"
        )
        val bounds = tags.map { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach { child ->
            assertTrue(child.width > 0f)
            assertTrue(child.left >= container.left)
            assertTrue(child.right <= container.right)
        }
        bounds.zipWithNext().forEach { (upper, lower) ->
            assertTrue(upper.bottom <= lower.top)
        }
    }

    @Test
    fun constrainedLargeFontActionStaysInside320DpContainer() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 1.6f)
            ) {
                AapsCopilotTheme {
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .width(320.dp)
                            .testTag("clinical_report_action_container")
                    ) {
                        AiAnalysisScreen(
                            state = screenState(
                                ClinicalReportUiState(
                                    phase = ClinicalReportPhaseUi.LOCAL_READY,
                                    summary7d = summary(7),
                                    summary30d = summary(30),
                                    canSend = true
                                )
                            )
                        )
                    }
                }
            }
        }

        val container = composeRule.onNodeWithTag("clinical_report_action_container")
            .fetchSemanticsNode().boundsInRoot
        val action = composeRule.onNodeWithTag("clinical_report_send")
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(action.left >= container.left)
        assertTrue(action.right <= container.right)
    }

    private fun screenState(clinical: ClinicalReportUiState) = AiAnalysisUiState(
        loadState = ScreenLoadState.READY,
        isStale = false,
        clinicalReport = clinical
    )

    private fun unknownState(showConfirmation: Boolean) = ClinicalReportUiState(
        phase = ClinicalReportPhaseUi.UNKNOWN_OUTCOME,
        summary7d = summary(days = 7),
        summary30d = summary(days = 30),
        failure = ClinicalReportFailureUi.UNKNOWN_REMOTE_OUTCOME,
        canRequestGuardedRetry = true,
        showRetryConfirmation = showConfirmation
    )

    private fun summary(days: Int) = ClinicalPeriodSummaryUi(
        days = days,
        coveragePct = 96.5,
        coverageProgress = 0.965f,
        meanGlucose = 7.2,
        medianGlucose = 6.8,
        variability = 31.4,
        belowRange = 2.1,
        inRange = 78.6,
        aboveRange = 19.3,
        recordedInsulin = 42.5,
        realCarbs = 315.0,
        maxGapMinutes = 15,
        quality = ClinicalLocalDataQualityUi.GOOD
    )

    private fun disclosure(
        providerId: ClinicalAiProviderId = ClinicalAiProviderId.OPENAI,
        model: String = "gpt-5.6-terra",
        endpointHost: String? = null,
        configIdentity: String = "a".repeat(64)
    ) = ClinicalReportDisclosureUi(
        providerId = providerId,
        model = model,
        endpointHost = endpointHost,
        configIdentity = configIdentity
    )

    private fun completeReport(
        providerId: ClinicalAiProviderId = ClinicalAiProviderId.OPENAI
    ): ClinicalCompleteReportUi {
        val finding = ClinicalFindingUi(
            topic = ClinicalReportTextKey.OBSERVATION_GLUCOSE_VARIABILITY,
            period = ClinicalReportTextKey.PERIOD_LAST_7_DAYS,
            direction = ClinicalReportTextKey.DIRECTION_MIXED,
            confidence = ClinicalReportTextKey.CONFIDENCE_HIGH,
            timeBand = ClinicalReportTextKey.TIME_ALL_DAY,
            evidence = ClinicalEvidenceUi(
                metric = ClinicalReportTextKey.EVIDENCE_VARIABILITY,
                value = 31.4,
                decimals = 1,
                unit = ClinicalNumericUnitUi.PERCENT
            )
        )
        return ClinicalCompleteReportUi(
            summary7dStatus = ClinicalReportTextKey.SUMMARY_STABLE,
            summary30dStatus = ClinicalReportTextKey.SUMMARY_HIGH_VARIABILITY,
            dataQuality = listOf(
                ClinicalReportTextKey.QUALITY_COMPLETE,
                ClinicalReportTextKey.QUALITY_PARTIAL_COVERAGE
            ),
            patterns = List(4) { finding },
            safetyObservations = listOf(
                ClinicalReportTextKey.SAFETY_HIGH_VARIABILITY_PATTERN
            ),
            recommendations = listOf(
                ClinicalRecommendationUi(
                    topic = ClinicalReportTextKey.DISCUSSION_ISF_CR_REVIEW,
                    priority = ClinicalReportTextKey.PRIORITY_MEDIUM,
                    period = ClinicalReportTextKey.PERIOD_LAST_7_DAYS,
                    linkedEvidence = listOf(finding)
                )
            ),
            careTeamQuestions = listOf(
                ClinicalReportTextKey.QUESTION_ISF_CR_CONTEXT
            ),
            metadata = ClinicalReportMetadataUi(
                generatedAtTs = 1_800_000_000_000L,
                providerId = providerId,
                model = "gpt-5-mini",
                schemaName = "clinical_advisory_report",
                schemaVersion = 4
            )
        )
    }
}

private class ClinicalPdfActivityResultRegistryOwner(
    override val activityResultRegistry: ActivityResultRegistry
) : ActivityResultRegistryOwner

private class ClinicalPdfActivityResultRegistry : ActivityResultRegistry() {
    val launches = mutableListOf<String>()
    private var requestCode: Int? = null

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?
    ) {
        launches += checkNotNull(input as? String)
        this.requestCode = requestCode
    }

    fun deliver(uri: Uri?) {
        dispatchResult(
            checkNotNull(requestCode),
            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            Intent().setData(uri)
        )
    }
}
