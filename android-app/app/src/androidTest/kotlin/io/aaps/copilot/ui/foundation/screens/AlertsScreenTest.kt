package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.AlertEpisodeDetail
import io.aaps.copilot.data.repository.AlertEpisodeSummary
import io.aaps.copilot.data.repository.AlertAiPresentation
import io.aaps.copilot.data.repository.AlertEvidenceItem
import io.aaps.copilot.data.repository.AlertEvidenceKind
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.CauseConfidence
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.text.DateFormat
import java.util.Date
import org.junit.Rule
import org.junit.Test

class AlertsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun suppressedEpisodeShowsOffAndLocalCauseBeforeOptionalAi() {
        val episode = episode(notShownOff = true)
        composeRule.setContent {
            AapsCopilotTheme {
                AlertsScreen(
                    state = AlertsUiState(
                        loadState = ScreenLoadState.READY,
                        active = episode,
                        history = listOf(episode),
                        selected = AlertEpisodeDetail(
                            summary = episode,
                            localAdviceCode = "SENSOR_QUALITY",
                            evidence = listOf(
                                AlertEvidenceItem(
                                    kind = AlertEvidenceKind.CURRENT_GLUCOSE,
                                    glucoseMmol = 6.6,
                                    timestamp = 1_000L
                                ),
                                AlertEvidenceItem(AlertEvidenceKind.SENSOR_QUALITY, timestamp = 1_000L)
                            ),
                            aiPresentation = AlertAiPresentation(
                                primaryCause = AlertCauseCode.SENSOR_QUALITY,
                                confidence = CauseConfidence.HIGH,
                                evidence = listOf(AlertEvidenceKind.SENSOR_QUALITY),
                                advice = AlertCauseCode.DATA_INCOMPLETE
                            )
                        )
                    ),
                    onSelectEpisode = {}
                )
            }
        }

        composeRule.onAllNodesWithText(
            composeRule.activity.getString(R.string.alerts_not_shown_off),
            useUnmergedTree = true
        ).onFirst().assertIsDisplayed()
        val local = composeRule.onNodeWithText(composeRule.activity.getString(R.string.alerts_local_cause_title))
            .fetchSemanticsNode().boundsInRoot
        val advice = composeRule.onNodeWithText(composeRule.activity.getString(R.string.alerts_local_advice_title))
            .fetchSemanticsNode().boundsInRoot
        val evidence = composeRule.onNodeWithText(composeRule.activity.getString(R.string.alerts_evidence_title))
            .fetchSemanticsNode().boundsInRoot
        val ai = composeRule.onNodeWithText(composeRule.activity.getString(R.string.alerts_ai_title))
            .fetchSemanticsNode().boundsInRoot
        check(local.top < advice.top && advice.top < evidence.top && evidence.top < ai.top)
        composeRule.onNodeWithText("SENSOR_BLOCKED").assertDoesNotExist()
        composeRule.onNodeWithText("currentMmol").assertDoesNotExist()
        composeRule.onAllNodes(hasText("SENSOR_QUALITY", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onAllNodes(hasText("SENSOR_BLOCKED", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onAllNodes(hasText("Saved AI detail", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.alerts_ai_cause,
                composeRule.activity.getString(R.string.glucose_alert_cause_sensor_quality)
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.alerts_ai_advice,
                composeRule.activity.getString(R.string.alerts_advice_data_incomplete)
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.alerts_ai_confidence,
                composeRule.activity.getString(R.string.clinical_text_confidence_high)
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.alerts_ai_evidence))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.alerts_evidence_current_glucose),
            substring = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText("mmol/L", substring = true).assertIsDisplayed()
    }

    @Test
    fun emptyHistoryIsSafeAndDoesNotExposeEpisodeId() {
        composeRule.setContent {
            AapsCopilotTheme {
                AlertsScreen(
                    state = AlertsUiState(loadState = ScreenLoadState.EMPTY),
                    onSelectEpisode = {}
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.alerts_history_empty))
            .assertIsDisplayed()
        composeRule.onNodeWithText("glucose-alert-private").assertDoesNotExist()
    }

    @Test
    fun historicalRowUsesCreatedAtAndLocalizedStageStatusInAccessibility() {
        val createdAt = 1_704_067_200_000L
        val updatedAt = createdAt + 24L * 60L * 60L * 1_000L
        val episode = episode(
            notShownOff = false,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
        composeRule.setContent {
            AapsCopilotTheme {
                AlertsScreen(
                    state = AlertsUiState(
                        loadState = ScreenLoadState.READY,
                        history = listOf(episode)
                    ),
                    onSelectEpisode = {}
                )
            }
        }
        val createdText = formatTime(createdAt)
        val updatedText = formatTime(updatedAt)
        val expectedDescription = composeRule.activity.getString(
            R.string.alerts_episode_accessibility,
            composeRule.activity.getString(R.string.alerts_direction_low),
            composeRule.activity.getString(R.string.alerts_stage_warning_30),
            composeRule.activity.getString(R.string.alerts_status_open),
            createdText
        )

        composeRule.onNodeWithText(createdText).assertIsDisplayed()
        composeRule.onNodeWithText(updatedText).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
        composeRule.onAllNodes(hasContentDescription("WARNING_30", substring = true))
            .assertCountEquals(0)
        composeRule.onAllNodes(hasContentDescription("OPEN", substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun activeRowUsesUpdatedAtInsteadOfCreatedAt() {
        val createdAt = 1_704_067_200_000L
        val updatedAt = createdAt + 24L * 60L * 60L * 1_000L
        val episode = episode(
            notShownOff = false,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
        composeRule.setContent {
            AapsCopilotTheme {
                AlertsScreen(
                    state = AlertsUiState(
                        loadState = ScreenLoadState.READY,
                        active = episode
                    ),
                    onSelectEpisode = {}
                )
            }
        }
        val createdText = formatTime(createdAt)
        val updatedText = formatTime(updatedAt)
        val expectedDescription = composeRule.activity.getString(
            R.string.alerts_episode_accessibility,
            composeRule.activity.getString(R.string.alerts_direction_low),
            composeRule.activity.getString(R.string.alerts_stage_warning_30),
            composeRule.activity.getString(R.string.alerts_status_open),
            updatedText
        )

        composeRule.onNodeWithText(updatedText).assertIsDisplayed()
        composeRule.onNodeWithText(createdText).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun positiveMuteTimestampIsAuthoritativeUntilExpiryFlowEmitsZero() {
        composeRule.setContent {
            AapsCopilotTheme {
                AlertsScreen(
                    state = AlertsUiState(
                        loadState = ScreenLoadState.EMPTY,
                        mutedUntilTs = 1L
                    ),
                    onSelectEpisode = {}
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.alerts_not_shown_off),
            useUnmergedTree = true
        ).assertIsDisplayed()
    }

    private fun episode(
        notShownOff: Boolean,
        createdAt: Long = 1_000L,
        updatedAt: Long = 2_000L
    ) = AlertEpisodeSummary(
        episodeId = "glucose-alert-private",
        direction = "LOW",
        stage = "WARNING_30",
        status = "OPEN",
        severity = "WARNING_30",
        createdAt = createdAt,
        updatedAt = updatedAt,
        resolvedAt = null,
        suppressionUntil = 3_000L,
        notShownOff = notShownOff,
        localCauseCode = "SENSOR_QUALITY"
    )

    private fun formatTime(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}
