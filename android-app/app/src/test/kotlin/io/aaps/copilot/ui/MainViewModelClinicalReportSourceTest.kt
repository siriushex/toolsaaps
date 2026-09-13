package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class MainViewModelClinicalReportSourceTest {

    @Test
    fun productionUiCannotExposeClinicalPdfLauncherOverridesOrTestHooks() {
        val uiRoot = File("src/main/kotlin/io/aaps/copilot/ui")
        val forbidden = Regex(
            "(?i)(pdf\\w*(launcher|picker)\\w*(override|hook|test)|" +
                "(override|hook|test)\\w*pdf\\w*(launcher|picker))"
        )
        val violations = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                forbidden.findAll(file.readText()).map { match ->
                    "${file.relativeTo(uiRoot).path}:${match.value}"
                }
            }
            .toList()

        assertThat(violations).isEmpty()
    }

    @Test
    fun viewModelObservesRepositoryAndRouteOpeningPreparesWithoutSending() {
        val source = mainViewModelSource()
        val routeBlock = source
            .substringAfter("fun setActiveRoute(route: String?)")
            .substringBefore("fun prepareClinicalReport()")
        val prepareBlock = source
            .substringAfter("fun prepareClinicalReport()")
            .substringBefore("fun sendClinicalReport(")

        assertThat(source).contains("state = container.clinicalReportRepository.state")
        assertThat(routeBlock).contains("prepareClinicalReport()")
        assertThat(routeBlock).doesNotContain("clinicalReportCommands.send(")
        assertThat(routeBlock).doesNotContain("retryUnknownClinicalReport()")
        assertThat(prepareBlock).contains("clinicalReportCommands.prepare()")
        assertThat(prepareBlock).doesNotContain("clinicalReportCommands.send(")
    }

    @Test
    fun remoteDispatchIsLimitedToExplicitSendAndConfirmedUnknownRetry() {
        val source = mainViewModelSource()
        val sendBlock = source
            .substringAfter(
                "fun sendClinicalReport(confirmedDisclosure: ClinicalReportDisclosureUi)"
            )
            .substringBefore("fun cancelClinicalReport()")
        val retryBlock = source
            .substringAfter("fun retryUnknownClinicalReport()")
            .substringBefore("private fun refreshCircadianReplaySummaryAsync")

        assertThat(sendBlock).contains(
            "clinicalReportCommands.send(confirmedDisclosure.configIdentity)"
        )
        assertThat(retryBlock).contains("clinicalReportCommands.retryUnknown(confirmed)")
        assertThat(
            source.split(
                "clinicalReportCommands.send(confirmedDisclosure.configIdentity)"
            )
        ).hasSize(2)
        assertThat(source.split("clinicalReportCommands.retryUnknown(confirmed)")).hasSize(2)
    }

    @Test
    fun normalSendAndConfirmedRetryUseSharedPreflightBeforeRemoteDispatch() {
        val source = mainViewModelSource()
        assertThat(source).contains(
            "fun sendClinicalReport(confirmedDisclosure: ClinicalReportDisclosureUi)"
        )
        assertThat(source).doesNotContain("fun sendClinicalReport()")
        val sendBlock = source
            .substringAfter(
                "fun sendClinicalReport(confirmedDisclosure: ClinicalReportDisclosureUi)"
            )
            .substringBefore("fun cancelClinicalReport()")
        val retryBlock = source
            .substringAfter("fun retryUnknownClinicalReport()")
            .substringBefore("private fun refreshCircadianReplaySummaryAsync")

        assertThat(sendBlock).contains("clinicalAiRemoteSendPreflight()")
        assertThat(sendBlock).contains(
            "clinicalReportDisclosureSendGuard.sendIfMatches(confirmedDisclosure)"
        )
        assertThat(retryBlock).contains("clinicalAiRemoteSendPreflight()")
        assertThat(sendBlock.indexOf("clinicalAiRemoteSendPreflight()"))
            .isLessThan(
                sendBlock.indexOf(
                    "clinicalReportDisclosureSendGuard.sendIfMatches(confirmedDisclosure)"
                )
            )
        assertThat(
            sendBlock.indexOf(
                "clinicalReportDisclosureSendGuard.sendIfMatches(confirmedDisclosure)"
            )
        ).isLessThan(
            sendBlock.indexOf(
                "clinicalReportCommands.send(confirmedDisclosure.configIdentity)"
            )
        )
        assertThat(sendBlock).contains(
            "clinicalReportCommands.send(confirmedDisclosure.configIdentity)"
        )
        assertThat(retryBlock.indexOf("clinicalAiRemoteSendPreflight()"))
            .isLessThan(retryBlock.indexOf("clinicalReportCommands.retryUnknown(confirmed)"))
        assertThat(retryBlock).doesNotContain("clinicalReportDisclosureSendGuard")
    }

    @Test
    fun initialSendGuardUsesPersistedStateAndSanitizedLocalizedMismatchFeedback() {
        val source = mainViewModelSource()
        val guardWiring = source
            .substringAfter(
                "private val clinicalReportDisclosureSendGuard = " +
                    "ClinicalReportDisclosureSendGuard("
            )
            .substringBefore("private val dryRunState")
        val screen = File(
            "src/main/kotlin/io/aaps/copilot/ui/foundation/screens/AiAnalysisScreen.kt"
        ).readText()
        val english = File("src/main/res/values/strings.xml").readText()
        val russian = File("src/main/res/values-ru/strings.xml").readText()

        assertThat(guardWiring).contains(
            "container.settingsStore.settings.first().clinicalAiConfigState"
        )
        assertThat(guardWiring).doesNotContain("clinicalAiSettingsUiState")
        assertThat(guardWiring).contains(
            "R.string.settings_clinical_ai_report_settings_changed"
        )
        assertThat(screen).contains(
            "onSendClinicalReport: (ClinicalReportDisclosureUi) -> Unit"
        )
        assertThat(english).contains(
            "AI provider settings changed. Review the disclosure and send again."
        )
        assertThat(russian).contains(
            "Настройки ИИ-провайдера изменились. " +
                "Проверьте раскрытие данных и отправьте отчёт снова."
        )
    }

    @Test
    fun rootWiresLegacyAndClinicalEndpointsToIndependentCallbacks() {
        val root = File(
            "src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt"
        ).readText()

        assertThat(root).contains(
            "onAiApiSettingsSave = viewModel::setAiApiSettings"
        )
        assertThat(root).contains(
            "onClinicalAiEndpointChange =\n" +
                "                                        viewModel::setClinicalAiEndpoint"
        )
    }

    @Test
    fun clinicalCredentialAndConnectionActionsUseProviderManager() {
        val source = mainViewModelSource()
        val actions = source
            .substringAfter("fun replaceClinicalAiCredential(")
            .substringBefore("private fun updateClinicalAiDraft")

        assertThat(actions).contains(
            "clinicalAiProviderManager.replace(providerId, value)"
        )
        assertThat(actions).contains("clinicalAiProviderManager.delete(providerId)")
        assertThat(actions).contains("clinicalAiProviderManager.replace")
        assertThat(actions).contains("clinicalAiProviderManager.delete")
        assertThat(actions).contains("clinicalAiProviderManager.testConnection")
        assertThat(actions).doesNotContain("openAiCredentialProvider.replace")
        assertThat(actions).doesNotContain("openAiCredentialProvider.delete")
    }

    @Test
    fun persistenceAndConnectionGuardsAreWiredIntoViewModelMutations() {
        val source = mainViewModelSource()
        val connection = source
            .substringAfter("fun testClinicalAiConnection()")
            .substringBefore("private fun updateClinicalAiDraft")
        val persistence = source
            .substringAfter("private fun updateClinicalAiDraft")
            .substringBefore("private fun clinicalAiProviderOptions")

        assertThat(connection).contains("clinicalAiConnectionTestGuard.begin(config)")
        assertThat(connection).contains("clinicalAiConnectionTestJob = viewModelScope.launch")
        assertThat(connection).contains("publishIfCurrent(")
        assertThat(connection).contains("currentClinicalAiConnectionConfig()")
        assertThat(persistence).contains("invalidateClinicalAiConnectionTest()")
        assertThat(persistence).contains("clinicalAiPersistenceCoordinator.persist(")
        assertThat(persistence).contains(".first { persisted ->")
        assertThat(persistence).contains("current.complete(")
        assertThat(persistence).doesNotContain("finally")
    }

    @Test
    fun unusableSettingsObserverClearsBackingRetryStateOutsideCombine() {
        val source = mainViewModelSource()
        val observer = source
            .substringAfter("private val clinicalAiRetryStateObserver")
            .substringBefore("val aiAnalysisUiState")

        assertThat(observer).contains("ClinicalAiRetryStateResetGate")
        assertThat(observer).contains("clearClinicalReportRetryState()")
        assertThat(observer).doesNotContain("combine(")
    }

    @Test
    fun unknownRetryConfirmationClearsOnlyAfterRemoteStartedDisposition() {
        val retryBlock = mainViewModelSource()
            .substringAfter("fun retryUnknownClinicalReport()")
            .substringBefore("private fun refreshCircadianReplaySummaryAsync")

        assertThat(retryBlock).contains(
            "if (disposition == ClinicalReportRunDisposition.REMOTE_STARTED)"
        )
        assertThat(retryBlock.substringBefore("viewModelScope.launch"))
            .doesNotContain("clinicalReportRetryConfirmationState.value = false")
        assertThat(retryBlock).contains("ClinicalReportRetryFeedbackUi.PENDING")
        assertThat(retryBlock).contains("ClinicalReportRetryFeedbackUi.IN_FLIGHT")
        assertThat(retryBlock).contains("ClinicalReportRetryFeedbackUi.FAILED")
    }

    @Test
    fun localRetryIsSeparatelyWiredAndNeverDispatchesRemote() {
        val viewModel = mainViewModelSource()
        val retryBlock = viewModel
            .substringAfter("fun retryLocalClinicalReportPreparation()")
            .substringBefore("fun sendClinicalReport(")
        val root = File(
            "src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt"
        ).readText()

        assertThat(retryBlock).contains("clinicalReportCommands.prepare()")
        assertThat(retryBlock).doesNotContain("clinicalReportCommands.send(")
        assertThat(retryBlock).doesNotContain("retryUnknown")
        assertThat(root).contains(
            "onRetryLocalClinicalReportPreparation =\n" +
                "                                        viewModel::retryLocalClinicalReportPreparation"
        )
    }

    @Test
    fun pdfReprepareAndShareStayInsideTheirDedicatedLifecycleCoordinators() {
        val viewModel = mainViewModelSource()
        val reprepare = viewModel
            .substringAfter("fun reprepareClinicalReportPdf()")
            .substringBefore("fun beginClinicalReportPdfExport()")
        val share = viewModel
            .substringAfter("fun shareClinicalReportPdf()")
            .substringBefore("fun resolveClinicalReportPdfExport(")
        val root = File(
            "src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt"
        ).readText()

        assertThat(reprepare).contains("clinicalReportPdfReprepareCoordinator.reprepare()")
        assertThat(reprepare).doesNotContain("claimClinicalPdfReprepare")
        assertThat(share).contains("clinicalReportPdfExportCoordinator.beginShare")
        assertThat(share).doesNotContain("viewModelScope.launch")
        assertThat(share).doesNotContain("createShare(")
        assertThat(root).contains(
            "onReprepareClinicalReportPdf =\n" +
                "                                        viewModel::reprepareClinicalReportPdf"
        )
    }

    private fun mainViewModelSource(): String = File(
        "src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt"
    ).readText()
}
