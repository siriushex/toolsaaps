package io.aaps.copilot.config

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.AutomationRepository
import java.io.File
import org.junit.Test

class SensitivitySettingsProductionWiringTest {
    @Test
    fun evidenceCrProductionCandidatesIncludeIndependentAapsFallback() {
        val container = File(
            sourceRoot(),
            "io/aaps/copilot/service/AppContainer.kt"
        ).readText()

        assertThat(container).contains("val aapsCr = telemetryCandidate(")
        assertThat(container).contains(
            "crCandidates = SensitivityCandidates(\n" +
                "                    aaps = aapsCr,"
        )
    }

    @Test
    fun productionCallsitesCannotChangeSensitivityFingerprintThroughGenericUpdate() {
        val root = sourceRoot()
        val fingerprintFields = listOf(
            "isfSourcePreference",
            "crSourcePreference",
            "isfCrShadowMode",
            "isfCrConfidenceThreshold",
            "isfCrUseActivity",
            "isfCrUseManualTags",
            "isfCrMinIsfEvidencePerHour",
            "isfCrMinCrEvidencePerHour",
            "isfCrCrMaxGapMinutes",
            "isfCrCrMaxSensorBlockedRatePct",
            "isfCrCrMaxUamAmbiguityRatePct",
            "analyticsLookbackDays",
            "physiologicalSex"
        )
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                genericUpdateBlocks(file.readText()).mapNotNull { block ->
                    val changed = fingerprintFields.filter { field ->
                        Regex("\\b${Regex.escape(field)}\\s*=").containsMatchIn(block)
                    }
                    changed.takeIf(List<String>::isNotEmpty)?.let {
                        "${file.relativeTo(root).invariantSeparatorsPath}:$it"
                    }
                }
            }
            .toList()

        assertThat(offenders).isEmpty()
        val viewModel = File(root, "io/aaps/copilot/ui/MainViewModel.kt").readText()
        assertThat(viewModel).contains("automationRepository::applySensitivitySettings")
        val settingsStore = File(root, "io/aaps/copilot/config/AppSettingsStore.kt").readText()
        val energySetter = functionBody(settingsStore, "setEnergyProfileSettings")
        assertThat(energySetter).doesNotContain("dataStore.edit")
        assertThat(energySetter).contains("update")
        val energyUiSetter = functionBody(viewModel, "updateEnergyProfileSettings")
        assertThat(energyUiSetter).contains("automationRepository.applySensitivitySettings")
        val analyticsMigration = functionBody(
            settingsStore,
            "ensureAnalyticsRetentionDefault30Days"
        )
        assertThat(analyticsMigration).doesNotContain("KEY_ANALYTICS_LOOKBACK_DAYS] =")
        val container = File(root, "io/aaps/copilot/service/AppContainer.kt").readText()
        assertThat(container).contains(
            "automationRepository.ensureAnalyticsRetentionDefault30Days"
        )
    }

    @Test
    fun acceptedReservationAbortIsARequiredNonSuspendingOwnershipRelease() {
        val source = File(
            sourceRoot(),
            "io/aaps/copilot/data/repository/AutomationRepository.kt"
        ).readText()
        assertThat(source).contains("abortReservation: (Reservation) -> Unit")
        assertThat(source).doesNotContain("abortReservation: suspend")
    }

    @Test
    fun productionUsesOneForwardOnlyAuthoritativeSensitivityIdentity() {
        val root = sourceRoot()
        val production = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString(separator = "\n") { it.readText() }

        assertThat(production).doesNotContain("committedSensitivitySettingsRevision")
        assertThat(production).doesNotContain("committedIsfSourcePreference")
        assertThat(production).doesNotContain("committedCrSourcePreference")
        assertThat(production).doesNotContain("KEY_COMMITTED_ISF_RUNTIME_SOURCE")
        assertThat(production).doesNotContain("KEY_COMMITTED_CR_RUNTIME_SOURCE")
        assertThat(production).doesNotContain("KEY_COMMITTED_SENSITIVITY_SETTINGS_REVISION")
        assertThat(production).doesNotContain("requestedSensitivityRuntimeIdentity")
        assertThat(production).doesNotContain("uncommitted_sensitivity_settings")
        assertThat(production).doesNotContain("rollbackSensitivitySettingsMutation")
        assertThat(production).doesNotContain("invalidateSensitivitySettingsMutation")
    }

    @Test
    fun acceptedRuntimeStateHasNoDirectProductionPublicationBypass() {
        val repository = File(
            sourceRoot(),
            "io/aaps/copilot/data/repository/SensitivityRuntimeRepository.kt"
        ).readText()

        assertThat(repository).doesNotContain("fun publishAccepted(")
        assertThat(Regex("_current\\.value\\s*=(?!=)").findAll(repository).count()).isEqualTo(1)
        assertThat(functionBody(repository, "hydrateFromAcceptedTuple")).contains("reserveAccepted")
        assertThat(functionBody(repository, "hydrateFromAcceptedTuple")).contains("finalizeAccepted")
    }

    @Test
    fun preAcceptanceCalculationHelpersDoNotPublishUiOrMaintenanceState() {
        val source = File(
            sourceRoot(),
            "io/aaps/copilot/data/repository/AutomationRepository.kt"
        ).readText()
        val energyProfileSource = File(
            sourceRoot(),
            "io/aaps/copilot/data/repository/EnergyProfileRepository.kt"
        ).readText()
        val cycleBeforeFence = functionBody(source, "runAutomationCycleLocked")
            .substringBefore("val decisions = runAfterAcceptedSensitivityCycleStatic")
        val calibration = functionBody(source, "prepareCalibrationRuntimeContext")
        val preparation = functionBody(source, "preparePredictionPreparationContext")
        val forecast = functionBody(source, "buildForecastRuntimeContext")
        val forbidden = listOf(
            "persistRealtimeIsfCrTelemetry",
            "persistRuntimeDiaTelemetry",
            "persistRuntimeCobIobTelemetry",
            "persistSensorQualityTelemetry",
            "persistActivityForecastTelemetry",
            "persistGlucoseCalibrationTelemetry",
            "persistSensorLagTelemetry",
            "persistForecastDecompositionTelemetry",
            "persistUnifiedUamTelemetry",
            "persistForecastOutputsAndCoverage",
            "refreshPendingSelections",
            "refreshCalibrationModel",
            "maybeProcessIsfCrShadowAutoActivation",
            "scheduleRealtimeIsfCrRefresh"
        )

        forbidden.forEach { mutation ->
            assertThat(cycleBeforeFence).doesNotContain(mutation)
            assertThat(calibration).doesNotContain(mutation)
            assertThat(preparation).doesNotContain(mutation)
            assertThat(forecast).doesNotContain(mutation)
        }
        assertThat(cycleBeforeFence).doesNotContain("persistPendingSensitivityCycle")
        assertThat(cycleBeforeFence).contains("auditForecastFactorCoverage")
        assertThat(functionBody(source, "commitAcceptedSensitivityCycleStatic"))
            .contains("db.withTransaction")
        assertThat(functionBody(source, "commitAcceptedSensitivityCycleStatic"))
            .contains("forecastDao().insertAll")
        assertThat(functionBody(source, "commitAcceptedSensitivityCycleStatic"))
            .contains("commitAcceptedSensitivityTuple")
        assertThat(functionBody(energyProfileSource, "resolveMealOverride"))
            .doesNotContain("deleteMealOverride")
    }

    @Test
    fun acceptedRuntimeUsesPreparedCalibrationWithoutPostForecastRefresh() {
        val source = File(
            sourceRoot(),
            "io/aaps/copilot/data/repository/AutomationRepository.kt"
        ).readText()
        val cycle = functionBody(source, "runAutomationCycleLocked")
        val calibrationPreparation = functionBody(source, "prepareCalibrationRuntimeContext")
        val acceptedCommit = functionBody(source, "commitAcceptedSensitivityCycle")
        val uiTelemetry = functionBody(source, "persistAcceptedCycleRuntimeTelemetry")

        assertThat(calibrationPreparation).contains("prepareAcceptedCycleCalibration")
        assertThat(calibrationPreparation).contains("allowsCalibrationMaintenanceStatic(intent)")
        assertThat(acceptedCommit).contains("commitPreparedCalibrationAcceptance")
        assertThat(cycle).contains("preparedCalibration = calibrationRuntime.preparedCalibration")
        assertThat(cycle).contains("readPreparedCalibrationAcceptance")
        assertThat(cycle).contains("AcceptedSensitivityTupleRoomLoader(db).loadExact")
        assertThat(uiTelemetry).contains("requireExactCalibrationIdentityStatic")
        assertThat(uiTelemetry).contains("preparedCalibration.model")
        assertThat(uiTelemetry).doesNotContain("refreshCalibrationModel")
    }

    @Test
    fun overviewBuildersFenceForecastAndDisplayedCalibrationWithAcceptedIdentity() {
        val source = File(
            sourceRoot(),
            "io/aaps/copilot/ui/MainViewModel.kt"
        ).readText()

        listOf("buildMainUiState", "buildPrimaryUiState").forEach { functionName ->
            val body = functionBody(source, functionName)
            assertThat(body).contains("loadUiCalibrationAuthority(")
            assertThat(body).doesNotContain("resolveUiCalibrationAuthorityFromBoundedEvidence(")
            assertThat(body).contains("calibrationAuthority = AcceptedCalibrationAuthority")
            assertThat(body).contains("currentSessionKey = currentCalibrationAuthorityForUi.currentSessionKey")
            assertThat(body).contains("resolveOverviewCalibrationModelForUi")
        }
    }

    @Test
    fun overviewBuildersUseDedicatedDurableCalibrationAuthorityTokenFlow() {
        val source = File(
            sourceRoot(),
            "io/aaps/copilot/ui/MainViewModel.kt"
        ).readText()

        assertThat(Regex("observeCurrentBySourceAndKey").findAll(source).count()).isEqualTo(2)
        listOf("buildMainUiState", "buildPrimaryUiState").forEach { functionName ->
            val body = functionBody(source, functionName)
            assertThat(body).contains("calibrationAuthorityTokenRow")
            assertThat(body).doesNotContain("telemetryByKey[CALIBRATION_AUTHORITY_TOKEN_KEY]")
        }
    }

    @Test
    fun docsMatchAcceptedWidgetPublicationPolicy() {
        val normal = AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.NORMAL,
            therapyActionsArmed = false,
            killSwitch = false,
            powerSaveActive = false
        )
        val sourceChange = AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
            therapyActionsArmed = false,
            killSwitch = false,
            powerSaveActive = false
        )
        val localReadOnly = AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY,
            therapyActionsArmed = false,
            killSwitch = false,
            powerSaveActive = false
        )
        val requiredStatement =
            "Accepted NORMAL and SENSITIVITY_SOURCE_CHANGE cycles invoke the widget callback; " +
                "LOCAL_READ_ONLY does not."

        assertThat(normal.publishWidgetAfterAcceptance).isTrue()
        assertThat(sourceChange.publishWidgetAfterAcceptance).isTrue()
        assertThat(localReadOnly.publishWidgetAfterAcceptance).isFalse()
        listOf(
            File(repositoryRoot(), "docs/ARCHITECTURE.md"),
            File(repositoryRoot(), "docs/INVARIANTS.md"),
            File(repositoryRoot(), "AI_NOTES.md")
        ).forEach { document ->
            assertThat(document.readText()).contains(requiredStatement)
        }
    }

    private fun genericUpdateBlocks(source: String): Sequence<String> = sequence {
        var searchFrom = 0
        while (true) {
            val call = source.indexOf("settingsStore.update", searchFrom)
            if (call < 0) break
            val open = source.indexOf('{', call)
            if (open < 0) break
            var depth = 0
            var index = open
            while (index < source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            yield(source.substring(call, index + 1))
                            searchFrom = index + 1
                            break
                        }
                    }
                }
                index++
            }
            if (index >= source.length) break
        }
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name")
        check(start >= 0) { "Missing function $name" }
        val open = source.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, index + 1)
            }
        }
        error("Unclosed function $name")
    }

    private fun sourceRoot(): File = checkNotNull(
        generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .map { File(it, "src/main/kotlin") }
            .firstOrNull(File::isDirectory)
    ) { "Unable to locate src/main/kotlin" }

    private fun repositoryRoot(): File = checkNotNull(
        generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "AI_NOTES.md").isFile && File(it, "docs").isDirectory }
    ) { "Unable to locate repository root" }
}
