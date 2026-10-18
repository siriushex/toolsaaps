package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.aaps.copilot.ui.motion.ProvideCopilotMotion
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.config.ClinicalAiProviderId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AiFocusScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var root: View

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun chatRolesAreHiddenButRemainAccessible() = screen(ready().copy(
        chatMessages = listOf(
            AiChatMessageUi("question", "user", "Проверить свежесть данных", 1000),
            AiChatMessageUi("reply", "assistant", "Нет свежих данных. Проверьте связь с сенсором.", 2000)
        )
    )) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        assertNoVisibleRoleCaptions()
        compose.onNodeWithContentDescription("Вы").assertExists()
        compose.onNodeWithContentDescription("AI").assertExists()
        compose.onNodeWithText("Проверить свежесть данных").assertIsDisplayed()
        compose.onNodeWithText("Нет свежих данных. Проверьте связь с сенсором.").assertIsDisplayed()
        saveRaster("chat-no-role-captions-ru-small")
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun chatRolesStayHiddenWithLargeFontAndVoiceMetadata() = screenContent({ ready().copy(
        chatMessages = listOf(AiChatMessageUi("spoken", "user", "Проверить данные", 1000, voiceTranscript = true))
    ) }, fontScale = 1.8f) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        assertNoVisibleRoleCaptions()
        compose.onNodeWithText(root.context.getString(io.aaps.copilot.R.string.ai_analysis_chat_voice_transcript))
            .assertIsDisplayed()
        val text = compose.onNodeWithText("Проверить данные").fetchSemanticsNode().boundsInRoot
        assertTrue(text.left >= 0 && text.right <= root.width)
        compose.onNodeWithTag("ai_focus_send").assertIsDisplayed()
        saveRaster("chat-no-role-captions-ru-large")
    }

    private fun assertNoVisibleRoleCaptions() {
        compose.onAllNodesWithText("Вы", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("AI", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun focusPlusHasNoInnerOwlDuplicatingTheAppHeader() = screen(ready()) {
        compose.onNodeWithTag("ai_focus_owl").assertDoesNotExist()
        compose.onNodeWithTag("ai_focus_diagnostics").assertDoesNotExist()
    }

    @Config(qualifiers = "w320dp-h640dp-port")
    @Test fun voiceRepliesShareTheSixToolComposerRow() = screen(ready().copy(chatVoiceRepliesEnabled = true)) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        val voice = compose.onNodeWithTag("ai_focus_voice_replies")
        voice.assertIsOn()
        val controls = listOf("ai_focus_attach_photo", "ai_focus_attach_file", "ai_focus_record",
            "ai_focus_questions", "ai_focus_voice_replies", "ai_focus_send").map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        controls.forEach {
            assertEquals(controls.last().top, it.top, 0.1f)
            assertTrue(it.width >= 48f && it.height >= 48f && it.left >= 0f && it.right <= root.width)
        }
    }

    @Config(qualifiers = "ru-w320dp-h640dp-port")
    @Test fun savedDisplayFitsSmallScreenAndDoesNotRestoreLiveReportActions() = screen(ready().copy(
        clinicalDisplay = savedDisplay()
    )) {
        compose.onNodeWithTag("ai_focus_saved_date").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_observation_2").assertIsDisplayed()
        compose.onNodeWithTag("clinical_report_prepare").assertDoesNotExist()
        compose.onNodeWithTag("clinical_report_send").assertDoesNotExist()
        saveRaster("focus-plus-saved-ru-small")
        compose.onNodeWithTag("ai_focus_report_actions").performClick()
        compose.onNodeWithTag("clinical_report_prepare").performScrollTo().assertIsDisplayed()
    }

    @Test fun retainedDisplaySurvivesFailureAndRefreshAndDiscussionOnlyAddsDraft() {
        var state by mutableStateOf(ready().copy(clinicalDisplay = savedDisplay(), chatDraft = "Existing draft"))
        var sends = 0
        screenContent({ state }, onDraft = { state = state.copy(chatDraft = it) }, onSend = { sends++ }) {
            compose.onNodeWithTag("ai_focus_saved_period_menu").performClick()
            compose.onNodeWithTag("ai_focus_saved_period_30").performClick()
            compose.runOnIdle { state = state.copy(clinicalDisplayRefreshing = true) }
            compose.onNodeWithTag("ai_focus_saved_date").assertExists()
            compose.onNodeWithTag("ai_focus_display_refreshing").assertExists()
            compose.runOnIdle { state = state.copy(clinicalDisplayRefreshing = false, clinicalDisplayFailed = true) }
            compose.onNodeWithTag("ai_focus_saved_date").assertExists()
            compose.onNodeWithTag("ai_focus_display_retry").assertExists()
            compose.onNodeWithTag("ai_focus_observation_0").performClick()
            compose.onNodeWithTag("ai_focus_observation_discuss").performClick()
            compose.onNodeWithTag("ai_focus_composer").assertTextContains("Existing draft", substring = true)
            assertTrue(state.chatDraft.contains("Asia/Tbilisi"))
            assertTrue(state.chatDraft.contains(root.context.getString(io.aaps.copilot.R.string.ai_focus_period_days, 30)))
            assertEquals(0, sends)
        }
    }

    @Config(qualifiers = "ru-w320dp-h640dp-port")
    @Test fun retainedDisplayLargeFontAndShortHeightKeepDetailsReachable() = screenContent(
        { ready().copy(clinicalDisplay = savedDisplay()) }, fontScale = 1.8f
    ) {
        compose.onNodeWithTag("ai_focus_large_metrics", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ai_focus_report_actions").performScrollTo().assertIsDisplayed()
        saveRaster("focus-plus-saved-ru-large")
    }

    @Config(qualifiers = "ru-w320dp-h480dp-port")
    @Test fun compactSavedDisplayKeepsAllThreeObservationsAndActionsWithinShortHeight() = screen(ready().copy(
        clinicalDisplay = savedDisplay()
    )) {
        compose.onNodeWithTag("ai_focus_observation_2").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_report_actions").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_saved_period_menu").performClick()
        compose.onNodeWithTag("ai_focus_saved_period_30").performClick()
        compose.onNodeWithTag("clinical_report_summary_30").assertIsDisplayed()
        compose.onNodeWithText("30 дн. · Локальная сводка").assertIsDisplayed()
        saveRaster("focus-plus-saved-ru-short")
    }

    @Test fun cachedReportKeepsUnknownRemoteOutcomeVisibleWithoutReplacingItsRetryGuard() = screen(ready().copy(
        clinicalDisplay = savedDisplay(), clinicalReport = ClinicalReportUiState(
            phase = ClinicalReportPhaseUi.UNKNOWN_OUTCOME, failure = ClinicalReportFailureUi.UNKNOWN_REMOTE_OUTCOME,
            canRequestGuardedRetry = true)
    )) {
        compose.onNodeWithTag("ai_focus_saved_date").assertExists()
        compose.onNodeWithTag("ai_focus_display_retry").assertDoesNotExist()
        compose.onNodeWithTag("ai_focus_last_request_failure").performScrollTo().performClick()
        compose.onNodeWithTag("clinical_report_section").assertExists()
    }

    @Config(qualifiers = "ru-w320dp-h640dp-port")
    @Test fun nightlyAdviceHasNativePixelsOnASmallRussianScreen() = screen(ready().copy(nightlyAdvice = advice())) {
        compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
        compose.onNodeWithTag("ai_advice_window_3").assertIsDisplayed()
        val metrics = (0..2).map { compose.onNodeWithTag("ai_advice_metric_$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(metrics[0].top, metrics[1].top, 0.1f)
        assertEquals(metrics[1].top, metrics[2].top, 0.1f)
        saveRaster("nightly-advice-ru-small")
        compose.onNodeWithTag("ai_advice_discuss_low").performScrollTo()
        val button = compose.onNodeWithTag("ai_advice_discuss_low").fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= 0 && button.right <= root.width && button.bottom <= root.height)
        saveRaster("nightly-advice-ru-small-detail")
    }

    @Test fun nightlyAdviceShowsItsPeriodAndWindowSelector() = screen(ready().copy(nightlyAdvice = advice())) {
        compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
        compose.onNodeWithTag("ai_advice_window_14").assertIsSelected()
        compose.onNodeWithTag("ai_advice_period").assertExists()
        compose.onNodeWithTag("ai_advice_window_3").performClick().assertIsSelected()
        compose.onNodeWithTag("ai_advice_metrics").assertExists()
    }

    @Config(qualifiers = "ru-w320dp-h640dp-port")
    @Test fun nightlyAdviceLargeFontKeepsItsControlsReachable() = screenContent(
        { ready().copy(nightlyAdvice = advice()) }, fontScale = 1.8f
    ) {
        compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
        compose.onNodeWithTag("ai_advice_window_7").performClick().assertIsSelected()
        compose.onNodeWithTag("ai_advice_discuss_low").performScrollTo().assertIsDisplayed()
        saveRaster("nightly-advice-ru-large-font")
    }

    @Test fun nightlyDiscussionRetainsEvidenceAndNeverSends() {
        var draft = "Unsent draft"
        var sends = 0
        screenContent({ ready().copy(nightlyAdvice = advice(), chatDraft = draft) },
            onDraft = { draft = it }, onSend = { sends++ }) {
            compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
            compose.onNodeWithTag("ai_advice_discuss_low").performScrollTo().performClick()
            assertTrue(draft.startsWith("Unsent draft"))
            assertTrue(draft.contains("2026"))
            assertTrue(draft.contains("4"))
            assertEquals(0, sends)
            compose.onNodeWithTag("ai_focus_tab_chat").assertIsSelected()
        }
    }

    @Test fun insufficientHistoryCannotSupplyConfidentPatterns() = screen(ready().copy(
        nightlyAdvice = advice().let { report -> report.copy(windows = report.windows.map {
            it.copy(coveragePct = 2.0, qualityDays = 0, lowDays = 0, highDays = 0)
        }) }
    )) {
        compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
        compose.onNodeWithTag("ai_advice_insufficient").assertExists()
        compose.onNodeWithTag("ai_advice_discuss_low").assertDoesNotExist()
    }

    @Test fun olderReportsAreSeparateFromNightlyAdvice() = screen(ready().copy(
        nightlyAdvice = advice(), localRecommendations = listOf("Older forecast diagnostics")
    )) {
        compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
        compose.onNodeWithText("Older forecast diagnostics").assertDoesNotExist()
        compose.onNodeWithTag("ai_advice_other_reports").performScrollTo().performClick()
        compose.onNodeWithText("Older forecast diagnostics").assertExists()
    }

    @Test fun missingRecentDaysAreNotHiddenByAGoodLongerWindow() = screen(ready().copy(
        nightlyAdvice = advice().let { report -> report.copy(windows = report.windows.map {
            if (it.days == 3) it.copy(coveragePct = 0.0, qualityDays = 0, lowDays = 0) else it
        }) }
    )) {
        compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
        compose.onNodeWithTag("ai_advice_recent_incomplete").assertExists()
    }

    @Test fun aNewSavedNightAppearsWithoutARefreshTap() {
        var state by mutableStateOf(ready().copy(nightlyAdvice = advice()))
        screenContent({ state }) {
            compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
            compose.onNodeWithTag("ai_advice_discuss_low").assertExists()
            state = state.copy(nightlyAdvice = advice().let { report -> report.copy(windows = report.windows.map {
                it.copy(lowDays = 0, highDays = 0, inRangePct = 100.0, belowPct = 0.0, abovePct = 0.0)
            }) })
            compose.onNodeWithTag("ai_advice_discuss_low").assertDoesNotExist()
            compose.onNodeWithTag("ai_advice_metrics").assertExists()
        }
    }

    private fun advice(): io.aaps.copilot.data.repository.NightlyAdviceSnapshot {
        val end = java.time.ZonedDateTime.parse("2026-10-17T05:00:00+04:00[Asia/Tbilisi]").toInstant().toEpochMilli()
        return io.aaps.copilot.data.repository.NightlyAdviceSnapshot(generatedAt = end + 60_000,
            throughTs = end, zoneId = "Asia/Tbilisi", windows = listOf(3, 7, 14).map {
                io.aaps.copilot.data.repository.NightlyAdviceWindow(it, end - it * 86_400_000L,
                    92.0, it, 80.0, 5.0, 15.0, 3, 0, null)
            })
    }

    @Test fun reportIsFirstAndOtherPanelsAreNotMixedIntoIt() = screen(ready()) {
        compose.onNodeWithTag("ai_focus_tab_report").assertIsSelected()
        compose.onNodeWithTag("clinical_report_section").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_composer").assertDoesNotExist()
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
        compose.onNodeWithTag("clinical_report_section").assertDoesNotExist()
    }

    @Test fun dailyLoadingCannotHideChatOrLocalReport() = screen(
        ready().copy(loadState = ScreenLoadState.LOADING, analysisReady = false)
    ) {
        compose.onNodeWithTag("clinical_report_section").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
    }

    @Test fun draftSurvivesTabChangesAndSendRequiresContent() {
        var state by mutableStateOf(ready())
        var sent = ""
        screenContent({ state }, onDraft = { state = state.copy(chatDraft = it) }, onSend = { sent = it }) {
            compose.onNodeWithTag("ai_focus_tab_chat").performClick()
            compose.onNodeWithTag("ai_focus_send").assertIsNotEnabled()
            compose.onNodeWithTag("ai_focus_composer").performTextInput("Sample question")
            compose.onNodeWithTag("ai_focus_tab_archive").performClick()
            compose.onNodeWithTag("ai_focus_tab_chat").performClick()
            compose.onNodeWithTag("ai_focus_composer").assertTextContains("Sample question")
            compose.onNodeWithTag("ai_focus_send").performClick()
            assertEquals("Sample question", sent)
        }
    }

    @Test fun attachmentOnlyDraftCannotSendWithoutAQuestion() = screen(
        ready().copy(chatDraft = "  ", chatPendingAttachments = listOf(
            AiChatAttachmentUi("sample-file", "notes.txt", "FILE")
        ))
    ) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_send").assertIsNotEnabled()
    }

    @Test fun serverTextChatOffersTextFilesButNotUnsupportedImagesOrSpeech() = screen(
        ready().copy(chatTextOnly = true)
    ) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_attach_photo").assertIsNotEnabled()
        compose.onNodeWithTag("ai_focus_attach_file").assertIsEnabled()
        compose.onNodeWithTag("ai_focus_record").assertIsNotEnabled()
        compose.onNodeWithTag("ai_focus_voice_replies").assertIsNotEnabled()
    }

    @Test fun recordingMustRemainStoppableIfTheConnectionChangesToTextOnly() = screen(
        ready().copy(chatTextOnly = true, chatRecording = true, chatInProgress = true)
    ) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_record").assertIsEnabled()
    }

    @Test fun textAttachmentCanBeSentToTextOnlyServer() = screen(
        ready().copy(chatTextOnly = true, chatDraft = "Review the supplied context",
            chatPendingAttachments = listOf(AiChatAttachmentUi("sample", "notes.txt", "FILE")))
    ) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_send").assertIsEnabled()
    }

    @Test fun waitingIndicatorExistsOnlyDuringTheActualRequest() {
        var state by mutableStateOf(ready().copy(chatInProgress = true))
        screenContent({ state }) {
            compose.onNodeWithTag("ai_focus_tab_chat").performClick()
            compose.onNodeWithTag("ai_chat_waiting").assertIsDisplayed()
            compose.onNodeWithTag("ai_focus_send").assertIsNotEnabled()
            val waiting = compose.onNodeWithTag("ai_chat_waiting").fetchSemanticsNode().boundsInRoot
            val composer = compose.onNodeWithTag("ai_focus_composer").fetchSemanticsNode().boundsInRoot
            assertTrue(waiting.bottom <= composer.top && composer.top - waiting.bottom < 32f)
            saveRaster("chat-waiting")
            compose.runOnIdle { state = state.copy(chatInProgress = false) }
            compose.onNodeWithTag("ai_chat_waiting").assertDoesNotExist()
        }
    }

    @Test fun importingAFileBlocksSendAndAnotherPickerUntilComplete() = screen(
        ready().copy(chatDraft = "Review it", chatAttachmentBusy = true)
    ) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_chat_attachment_importing").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_send").assertIsNotEnabled()
        compose.onNodeWithTag("ai_focus_attach_file").assertIsNotEnabled()
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun fourAttachmentsStayInOneStripAndEachCanBeRemoved() {
        var state by mutableStateOf(ready().copy(chatDraft = "Проверь файлы", chatTextOnly = true,
            chatPendingAttachments = (1..4).map { AiChatAttachmentUi("sample-$it", "Длинное_имя_наблюдения_$it.txt", "file", "text/plain", "200 B") }))
        screenContent({ state }, onRemove = { id -> state = state.copy(
            chatPendingAttachments = state.chatPendingAttachments.filterNot { it.id == id }) }) {
            compose.onNodeWithTag("ai_focus_tab_chat").performClick()
            compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
            compose.onNodeWithTag("ai_focus_send").assertIsDisplayed().assertIsEnabled()
            saveRaster("chat-attachments-ru-small")
            compose.onNodeWithTag("ai_chat_attachments").performScrollToIndex(3)
            compose.onNodeWithTag("ai_chat_remove_sample-4").performClick()
            assertEquals(3, state.chatPendingAttachments.size)
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun waitingAtSmallWidthLeavesComposerAndFileActionUsable() = screen(
        ready().copy(chatInProgress = true, chatTextOnly = true)
    ) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_chat_waiting").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_attach_file").assertIsDisplayed()
        saveRaster("chat-waiting-ru-small")
    }

    @Test fun recordingCannotSubmitASecondTextQuestion() = screen(
        ready().copy(chatDraft = "Another question", chatRecording = true)
    ) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_send").assertIsNotEnabled()
    }

    @Test fun technicalFailureHasItsOwnPresentationAndDoesNotExposeDiagnostics() = screen(
        ready().copy(chatMessages = listOf(AiChatMessageUi("failed", "assistant",
            "Private network diagnostics", 1, failure = AiChatFailureUi.UNKNOWN_OUTCOME)))
    ) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_chat_failure").assertIsDisplayed()
        compose.onNodeWithText("Private network diagnostics").assertDoesNotExist()
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun russianTextOnlyFailureRemainsUsableAtSmallWidth() = screen(ready().copy(
        chatTextOnly = true, chatDraft = "Проверить доступные данные",
        chatMessages = listOf(AiChatMessageUi("failed", "assistant", "Private diagnostics", 1,
            failure = AiChatFailureUi.UNKNOWN_OUTCOME))
    )) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_voice_replies").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Текстовый чат").assertExists()
        compose.onNodeWithText("Ответ не получен").assertIsDisplayed()
        compose.onNodeWithText("Private diagnostics").assertDoesNotExist()
        compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_send").assertIsEnabled()
        saveRaster("focus-ru-text-failure")
    }

    @Test fun archiveSearchOpensFullRecordWithoutStartingAnalysis() {
        val row = AiAnalysisHistoryItemUi(1_000, "2026-10-01", "LOCAL", "SUCCESS",
            "Synthetic full report", listOf("Sample observation"), listOf("Sample review"), null)
        screen(ready().copy(historyItems = listOf(row))) {
            compose.onNodeWithTag("ai_focus_tab_archive").performClick()
            compose.onNodeWithTag("ai_focus_archive_search").performTextInput("missing")
            compose.onNodeWithTag("ai_focus_archive_item_0").assertDoesNotExist()
            compose.onNodeWithTag("ai_focus_archive_search").performTextClearance()
            compose.onNodeWithTag("ai_focus_archive_search").performTextInput("observation")
            compose.onNodeWithTag("ai_focus_archive_item_0").performClick()
            compose.onNodeWithTag("ai_focus_archive_detail").assertIsDisplayed()
            compose.onNodeWithText("Sample observation").assertExists()
        }
    }

    @Test fun recommendationsDiscussDoesNotSendOrEraseDraft() {
        var state by mutableStateOf(ready().copy(chatDraft = "Unsent draft",
            localRecommendations = listOf("Review the supplied context")))
        var sends = 0
        screenContent({ state }, onDraft = { state = state.copy(chatDraft = it) }, onSend = { sends++ }) {
            compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
            compose.onNodeWithTag("ai_focus_discuss_0").performClick()
            compose.onNodeWithTag("ai_focus_tab_chat").assertIsSelected()
            compose.onNodeWithTag("ai_focus_composer").assertTextContains("Unsent draft", substring = true)
                .assertTextContains("Review the supplied context", substring = true)
            assertEquals(0, sends)
        }
    }

    @Test fun reportsShowNewestSuccessfulSavedReviewWithoutOpeningADialog() {
        val older = AiAnalysisHistoryItemUi(1_000, "2026-10-01", "scheduler", "SUCCESS",
            "Older saved review", emptyList(), emptyList(), null)
        val latest = older.copy(runTs = 3_000, date = "2026-10-03", summary = "Most recent completed observations")
        val failed = older.copy(runTs = 4_000, date = "2026-10-04", status = "FAILED",
            summary = "Unusable answer", errorMessage = "Synthetic failure")
        screen(ready().copy(historyItems = listOf(older, failed, latest))) {
            compose.onNodeWithTag("ai_focus_tab_archive").performClick()
            compose.onNodeWithTag("ai_focus_latest_report").assertIsDisplayed()
            compose.onNodeWithText("Most recent completed observations").assertIsDisplayed()
            compose.onNodeWithTag("ai_focus_latest_open").performClick()
            compose.onNodeWithTag("ai_focus_archive_detail").assertIsDisplayed()
            compose.onNodeWithText("2026-10-03").assertExists()
        }
    }

    @Test fun failedHistoryCannotBecomeClinicalAdvice() {
        val failed = AiAnalysisHistoryItemUi(2_000, "2026-10-02", "scheduler", "FAILED",
            "Failed review", emptyList(), listOf("Unvalidated failed advice"), "Synthetic failure")
        val valid = failed.copy(runTs = 1_000, date = "2026-10-01", status = "SUCCESS",
            summary = "Saved review", recommendations = listOf("Review the dated evidence"), errorMessage = null)
        screen(ready().copy(historyItems = listOf(valid, failed))) {
            compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
            compose.onNodeWithText("Unvalidated failed advice").assertDoesNotExist()
            compose.onNodeWithText("Review the dated evidence").assertExists()
        }
    }

    @Test fun discussingSavedAdviceRetainsDateAndSourceWithoutSending() {
        val record = AiAnalysisHistoryItemUi(1_000, "2026-10-01", "scheduler", "SUCCESS",
            "Saved review", emptyList(), listOf("Review the dated evidence"), null)
        var draft = ""
        var sends = 0
        screenContent({ ready().copy(historyItems = listOf(record), chatDraft = draft) },
            onDraft = { draft = it }, onSend = { sends++ }) {
            compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
            compose.onNodeWithTag("ai_focus_history_discuss_0").performClick()
            assertTrue(draft.contains("2026-10-01"))
            assertTrue(draft.contains("scheduler"))
            assertTrue(draft.contains("Review the dated evidence"))
            assertEquals(0, sends)
        }
    }

    @Test fun onlyFailedReportsKeepTheirErrorAndNeverShowASuccessPreview() {
        val failed = AiAnalysisHistoryItemUi(2_000, "2026-10-02", "scheduler", "FAILED",
            "Failed review", emptyList(), emptyList(), "Synthetic failure")
        screen(ready().copy(historyItems = listOf(failed))) {
            compose.onNodeWithTag("ai_focus_tab_archive").performClick()
            compose.onNodeWithTag("ai_focus_latest_report").assertDoesNotExist()
            compose.onNodeWithTag("ai_focus_archive_item_0").performClick()
            compose.onNodeWithText("Synthetic failure").assertExists()
        }
    }

    @Test fun inconsistentSuccessWithAnErrorCannotSupplyAdviceOrPreview() {
        val inconsistent = AiAnalysisHistoryItemUi(2_000, "2026-10-02", "scheduler", "SUCCESS",
            "Incomplete review", emptyList(), listOf("Unusable advice"), "Synthetic failure")
        screen(ready().copy(historyItems = listOf(inconsistent))) {
            compose.onNodeWithTag("ai_focus_tab_archive").performClick()
            compose.onNodeWithTag("ai_focus_latest_report").assertDoesNotExist()
            compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
            compose.onNodeWithText("Unusable advice").assertDoesNotExist()
        }
    }

    @Test fun discussingAFailedReportDoesNotCopyPrivateDiagnosticsIntoChat() {
        val record = AiAnalysisHistoryItemUi(2_000, "2026-10-02", "scheduler", "FAILED",
            "Saved diagnostic summary", emptyList(), emptyList(), "Private diagnostic details")
        var draft = ""
        var sends = 0
        screenContent({ ready().copy(historyItems = listOf(record), chatDraft = draft) },
            onDraft = { draft = it }, onSend = { sends++ }) {
            compose.onNodeWithTag("ai_focus_tab_archive").performClick()
            compose.onNodeWithTag("ai_focus_archive_item_0").performClick()
            compose.onNodeWithText("Private diagnostic details").assertExists()
            compose.onNodeWithText("Discuss in chat").performClick()
            assertTrue(draft.contains("2026-10-02 / scheduler / FAILED"))
            assertTrue(draft.contains("Saved diagnostic summary"))
            assertFalse(draft.contains("Private diagnostic details"))
            assertEquals(0, sends)
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h480dp-port")
    @Test fun latestReportHasNativePixelsAndAnAccessibleActionAtSmallWidth() {
        val record = AiAnalysisHistoryItemUi(2_000, "2026-10-02", "scheduler", "SUCCESS",
            "За выбранный период сохранены наблюдения для обсуждения.",
            listOf("Контекст измерений требует проверки"), emptyList(), null)
        screen(ready().copy(historyItems = listOf(record))) {
            compose.onNodeWithTag("ai_focus_tab_archive").performClick()
            compose.onNodeWithTag("ai_focus_latest_report").assertIsDisplayed()
            val action = compose.onNodeWithTag("ai_focus_latest_open").fetchSemanticsNode().boundsInRoot
            assertTrue(action.width >= 48f && action.height >= 48f)
            assertTrue(action.right <= root.width && action.bottom <= root.height)
            saveRaster("focus-ru-latest-report")
        }
    }

    @Test fun externallyOpenedDiagnosticsDoNotSendOrPrepareAndTabsReturnToWorkspace() = screenContent(
        { ready() }, initialDiagnostics = true
    ) {
        compose.onNodeWithTag("ai_focus_diagnostics_content").assertExists()
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_diagnostics_content").assertDoesNotExist()
        compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
    }

    @Test fun confirmedClinicalSendCannotBeReopenedBySwitchingTabs() {
        val state = ready().copy(clinicalReport = ClinicalReportUiState(
            phase = ClinicalReportPhaseUi.LOCAL_READY, canSend = true,
            disclosure = ClinicalReportDisclosureUi(ClinicalAiProviderId.OPENAI, "sample-model", null, "a".repeat(64))
        ))
        var sends = 0
        screenContent({ state }, onClinicalSend = { sends++ }) {
            compose.onNodeWithTag("clinical_report_send").performClick()
            assertEquals(0, sends)
            compose.onNodeWithTag("clinical_report_disclosure_confirm").performClick()
            assertEquals(1, sends)
            compose.onNodeWithTag("ai_focus_tab_chat").performClick()
            compose.onNodeWithTag("ai_focus_tab_report").performClick()
            compose.onNodeWithTag("clinical_report_send").assertIsNotEnabled()
            assertEquals(1, sends)
        }
    }

    @Test fun busyChatAllowsDraftButNeverAnotherSend() {
        var state by mutableStateOf(ready().copy(chatInProgress = true, chatDraft = "Draft"))
        screenContent({ state }, onDraft = { state = state.copy(chatDraft = it) }) {
            compose.onNodeWithTag("ai_focus_tab_chat").performClick()
            compose.onNodeWithTag("ai_focus_send").assertIsNotEnabled()
            compose.onNodeWithTag("ai_focus_composer").performTextInput(" next")
            assertTrue(state.chatDraft.contains("next"))
        }
    }

    @Test fun reportBusyStateRemainsIndeterminateWhenMotionIsDisabled() = screen(ready().copy(
        clinicalReport = ClinicalReportUiState(phase = ClinicalReportPhaseUi.BUILDING)
    )) {
        compose.onNodeWithTag("ai_focus_owl").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
    }

    @Test fun reportPeriodAndDiagnosticValuesRemainAvailable() = screen(ready().copy(
        clinicalReport = ClinicalReportUiState(summary24h = summary(1), summary7d = summary(7), summary30d = summary(30))
    )) {
        compose.onNodeWithTag("clinical_report_summary_1").assertIsDisplayed()
        compose.onNodeWithTag("clinical_report_period_30").performClick()
        compose.onNodeWithTag("clinical_report_summary_30").assertIsDisplayed().performClick()
        compose.onNodeWithTag("clinical_report_detail_30").assertIsDisplayed()
        compose.onNodeWithTag("clinical_report_aaps_carbs_30").assertExists()
    }

    @Test fun clinicalRecommendationsKeepTheirPeriodSourceAndDiscussAction() {
        val complete = ClinicalCompleteReportUi(
            ClinicalReportTextKey.SUMMARY_STABLE, ClinicalReportTextKey.SUMMARY_STABLE,
            emptyList(), emptyList(), emptyList(),
            listOf(ClinicalRecommendationUi(ClinicalReportTextKey.DISCUSSION_ISF_CR_REVIEW,
                ClinicalReportTextKey.PRIORITY_MEDIUM, ClinicalReportTextKey.PERIOD_LAST_7_DAYS, emptyList())),
            emptyList(), ClinicalReportMetadataUi(1_000, ClinicalAiProviderId.OPENAI, "sample-model", null, null)
        )
        var draft = ""
        screenContent({ ready().copy(clinicalReport = ClinicalReportUiState(
            phase = ClinicalReportPhaseUi.COMPLETE, complete = complete)) }, onDraft = { draft = it }) {
            compose.onNodeWithTag("ai_focus_tab_recommendations").performClick()
            compose.onNodeWithText("sample-model", substring = true).assertExists()
            compose.onNodeWithTag("ai_focus_clinical_discuss_0").performClick()
            assertTrue(draft.isNotBlank())
        }
    }

    @Test fun keyboardAndImeSendOnlyOnceAndNeverSendBlank() {
        var state by mutableStateOf(ready())
        var sends = 0
        screenContent({ state }, onDraft = { state = state.copy(chatDraft = it) }, onSend = { sends++ }) {
            compose.onNodeWithTag("ai_focus_tab_chat").performClick()
            val input = compose.onNodeWithTag("ai_focus_composer")
            input.performImeAction()
            assertEquals(0, sends)
            input.performTextInput("Question")
            input.performKeyInput { pressKey(Key.Enter) }
            assertEquals(1, sends)
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun russianSummaryAndChatRenderAtSmallWidth() = screen(ready().copy(
        clinicalReport = ClinicalReportUiState(summary24h = summary(1))
    )) {
        saveRaster("focus-ru-report")
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
        saveRaster("focus-ru-chat")
    }

    @Config(qualifiers = "w320dp-h480dp-port")
    @Test fun composerFitsTheSpaceRemainingBelowAppNavigation() = screen(ready()) {
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_send").assertIsDisplayed()
        saveRaster("focus-available-height-chat")
    }

    @Test fun missingDailyDataAndErrorsDoNotBlockChat() {
        var state by mutableStateOf(ready().copy(loadState = ScreenLoadState.ERROR, analysisReady = false,
            errorText = "Synthetic daily error"))
        screenContent({ state }) {
            compose.onNodeWithTag("ai_focus_tab_chat").performClick()
            compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
            compose.runOnIdle { state = state.copy(loadState = ScreenLoadState.EMPTY) }
            compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
        }
    }

    @Config(qualifiers = "w320dp-h640dp-port")
    @Test fun allPanelsHaveNativePixelsAndComposerFitsSmallScreen() = screen(ready().copy(
        clinicalReport = ClinicalReportUiState(summary24h = summary(1)),
        localRecommendations = listOf("Review the supplied observations"),
        chatMessages = listOf(AiChatMessageUi("sample-message", "assistant", "Synthetic reply", 1000))
    )) {
        listOf("report", "chat", "recommendations", "archive").forEach { tab ->
            compose.onNodeWithTag("ai_focus_tab_$tab").performClick()
            compose.waitForIdle()
            saveRaster("focus-small-$tab")
        }
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        val composer = compose.onNodeWithTag("ai_focus_composer").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("ai_focus_send").fetchSemanticsNode().boundsInRoot
        assertTrue(composer.bottom <= send.top)
        assertTrue(send.right <= root.width && send.bottom <= root.height)
        assertTrue(send.width >= 48f && send.height >= 48f)
    }

    @Config(qualifiers = "w320dp-h640dp-port")
    @Test fun largeFontDoesNotClipTabsOrFixedComposer() = screenContent({ ready().copy(
        clinicalReport = ClinicalReportUiState(summary24h = summary(1))) }, fontScale = 1.6f) {
        saveRaster("focus-large-font-report")
        compose.onNodeWithTag("ai_focus_tab_chat").performClick()
        compose.onNodeWithTag("ai_focus_composer").assertIsDisplayed()
        compose.onNodeWithTag("ai_focus_send").assertIsDisplayed()
        saveRaster("focus-large-font-chat")
        listOf("report", "chat", "recommendations", "archive").forEach {
            val bounds = compose.onNodeWithTag("ai_focus_tab_$it").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= 0 && bounds.right <= root.width)
        }
    }

    private fun savedDisplay() = ClinicalDisplayUi(1_792_198_800_000, "Asia/Tbilisi",
        listOf(summary(1), summary(7), summary(30)))

    private fun summary(days: Int) = ClinicalPeriodSummaryUi(days, 96.5, .965f,
        7.2, 6.8, 31.4, 2.1, 78.6, 19.3, 42.5, 315.0, 15,
        ClinicalLocalDataQualityUi.GOOD, aapsCarbs = 99.0)

    private fun saveRaster(name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("native raster must contain rendered UI", pixels.toSet().size > 32)
        val directory = File("build/reports/focus-raster").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ready() = AiAnalysisUiState(ScreenLoadState.READY, false, analysisReady = true)

    private fun screen(state: AiAnalysisUiState, test: () -> Unit) = screenContent({ state }, test = test)

    private fun screenContent(
        state: () -> AiAnalysisUiState,
        onDraft: (String) -> Unit = {}, onSend: (String) -> Unit = {},
        onRemove: (String) -> Unit = {},
        onClinicalSend: (ClinicalReportDisclosureUi) -> Unit = {}, fontScale: Float = 1f,
        initialDiagnostics: Boolean = false,
        test: () -> Unit
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        root = controller.get().window.decorView
        try {
            controller.get().setContent {
                var diagnostics by androidx.compose.runtime.remember { mutableStateOf(initialDiagnostics) }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AapsCopilotTheme(darkTheme = false, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                        ProvideCopilotMotion(false) {
                            AiAnalysisScreen(state(), onChatDraftChange = onDraft, onSendChatPrompt = onSend,
                                diagnosticsVisible = diagnostics, onDismissDiagnostics = { diagnostics = false },
                                onRemoveChatAttachment = onRemove,
                                onSendClinicalReport = onClinicalSend,
                                onPrepareClinicalReport = { error("unexpected preparation") },
                                modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            compose.waitForIdle()
            test()
            compose.waitForIdle()
        } finally { controller.pause().stop().destroy() }
    }
}
