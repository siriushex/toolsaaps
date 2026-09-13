package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.alerts.AlertCauseAnalyzer
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.EffectiveBaseTarget
import io.aaps.copilot.domain.target.HorizonReliability
import io.aaps.copilot.domain.target.HorizonReliabilityState
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetManager
import io.aaps.copilot.domain.target.TargetManagerInput
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetManagerSafetyContext
import io.aaps.copilot.domain.target.CircadianAutoState
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntegratedClinicalRuntimeRevisionTest {

    @Test
    fun normalFanOutRejectsUamWithoutAcceptedForecastAuthorityBeforeWriter() = runTest {
        val accepted = acceptedSnapshot()
        val uam = AutomationRepository.projectUnifiedUamRuntimeStatic(
            diagnostics = null,
            effectiveCobGrams = 0.0,
            sensorBlocked = false,
            sensitivityRuntime = io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM,
                snapshot = accepted
            )
        )
        var carbWrites = 0
        val authority = authority(forecasts())

        val failure = runCatching {
            AutomationRepository.runAcceptedClinicalCalculationFanOutStatic<Unit>(
                intent = AutomationRepository.AutomationCycleIntent.NORMAL,
                policy = AutomationRepository.resolveCyclePolicyStatic(
                    intent = AutomationRepository.AutomationCycleIntent.NORMAL,
                    therapyActionsArmed = true,
                    killSwitch = false,
                    powerSaveActive = false
                ),
                acceptedSnapshot = accepted,
                acceptedClinicalForecastAuthority = authority,
                unifiedUam = uam,
                writeUamCarbs = { carbWrites += 1 },
                evaluateRulesAndTargetManager = { _, _ -> emptyList() },
                assessAlertCause = { _, _ -> },
                publishAlerts = { _, _ -> },
                publishAcceptedForecast = {}
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("UAM accepted forecast authority is missing")
        assertThat(carbWrites).isEqualTo(0)
    }

    @Test
    fun normalFanOutRejectsMismatchedUamAcceptedRowsBeforeWriter() = runTest {
        val accepted = acceptedSnapshot()
        val authority = authority(forecasts())
        val bound = AutomationRepository.bindUnifiedUamToAcceptedForecastsStatic(
            unified = AutomationRepository.projectUnifiedUamRuntimeStatic(
                diagnostics = null,
                effectiveCobGrams = 0.0,
                sensorBlocked = false,
                sensitivityRuntime = io.aaps.copilot.testSensitivityRuntimeContext(
                    consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM,
                    snapshot = accepted
                )
            ),
            authority = authority
        )
        val mismatched = bound.copy(
            acceptedForecasts = bound.acceptedForecasts.map { forecast ->
                if (forecast.horizonMinutes == 60) forecast.copy(ciHigh = forecast.ciHigh + 0.1)
                else forecast
            }
        )
        var carbWrites = 0

        val failure = runCatching {
            AutomationRepository.runAcceptedClinicalCalculationFanOutStatic<Unit>(
                intent = AutomationRepository.AutomationCycleIntent.NORMAL,
                policy = AutomationRepository.resolveCyclePolicyStatic(
                    intent = AutomationRepository.AutomationCycleIntent.NORMAL,
                    therapyActionsArmed = true,
                    killSwitch = false,
                    powerSaveActive = false
                ),
                acceptedSnapshot = accepted,
                acceptedClinicalForecastAuthority = authority,
                unifiedUam = mismatched,
                writeUamCarbs = { carbWrites += 1 },
                evaluateRulesAndTargetManager = { _, _ -> emptyList() },
                assessAlertCause = { _, _ -> },
                publishAlerts = { _, _ -> },
                publishAcceptedForecast = {}
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("UAM accepted forecast rows mismatch")
        assertThat(carbWrites).isEqualTo(0)
    }

    @Test
    fun sourceChangeRejectsWriterCapablePolicyBeforeAnyConsumer() = runTest {
        val accepted = acceptedSnapshot()
        val uam = AutomationRepository.projectUnifiedUamRuntimeStatic(
            diagnostics = null,
            effectiveCobGrams = 0.0,
            sensorBlocked = false,
            sensitivityRuntime = io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM,
                snapshot = accepted
            )
        )
        var effects = 0

        val failure = runCatching {
            AutomationRepository.runAcceptedClinicalCalculationFanOutStatic<Unit>(
                intent = AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
                policy = AutomationRepository.resolveCyclePolicyStatic(
                    intent = AutomationRepository.AutomationCycleIntent.NORMAL,
                    therapyActionsArmed = true,
                    killSwitch = false,
                    powerSaveActive = false
                ),
                acceptedSnapshot = accepted,
                acceptedClinicalForecastAuthority = authority(forecasts()),
                unifiedUam = uam,
                writeUamCarbs = { effects += 1 },
                evaluateRulesAndTargetManager = { _, _ -> effects += 1; emptyList() },
                assessAlertCause = { _, _ -> effects += 1 },
                publishAlerts = { _, _ -> effects += 1 },
                publishAcceptedForecast = { effects += 1 }
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("source-change policy must disable writers and alerts")
        assertThat(effects).isEqualTo(0)
    }

    @Test
    fun oneAcceptedSourceChangeFansExactRevisionAcrossCalculationConsumersWithoutWriters() = runTest {
        val accepted = acceptedSnapshot()
        val forecasts = forecasts()
        val unboundUam = AutomationRepository.projectUnifiedUamRuntimeStatic(
            diagnostics = null,
            effectiveCobGrams = 0.0,
            sensorBlocked = false,
            sensitivityRuntime = io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM,
                snapshot = accepted
            )
        ).copy(timestamp = NOW - 60_000L, state = "INACTIVE")
        val authority = authority(forecasts)
        val uam = AutomationRepository.bindUnifiedUamToAcceptedForecastsStatic(
            unified = unboundUam,
            authority = authority
        )
        val policy = AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
            therapyActionsArmed = true,
            killSwitch = false,
            powerSaveActive = false
        )
        val order = mutableListOf<String>()
        var overview: SensitivityRuntimeSnapshot? = null
        var targetInput: TargetManagerInput? = null
        var alertInput: io.aaps.copilot.domain.alerts.AlertCauseInput? = null
        var carbWrites = 0
        var targetWrites = 0
        var legacyActionWrites = 0
        var notificationWrites = 0
        var episodeWrites = 0
        var aiCallbacks = 0
        var maintenanceRuns = 0
        var remoteRefreshes = 0
        var sensitivityMaintenance = 0
        var widgetCallbacks = 0

        AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
            persistPendingRoomTuple = { order += "pending" },
            reserveAccepted = { order += "reserved"; "reservation" },
            commitAcceptedRoomTuple = { order += "committed" },
            reconcileAcceptedRoomTuple = { order += "read-back"; true },
            finalizeAccepted = { order += "published" },
            clinicalSideEffects = {
                AutomationRepository.publishAcceptedCycleStateStatic(
                    intent = AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
                    acceptedSnapshot = accepted,
                    publishUiTelemetry = {
                        overview = it
                        order += "overview"
                    },
                    runLocalMaintenance = { maintenanceRuns += 1 }
                )
                AutomationRepository.runRemoteRefreshStatic(policy) { remoteRefreshes += 1 }
                AutomationRepository.runSensitivityMaintenanceStatic(
                    policy = policy,
                    refreshRealtimeCandidate = { sensitivityMaintenance += 1 },
                    evaluateShadowAutoActivation = { sensitivityMaintenance += 1 },
                    runRetentionMaintenance = { sensitivityMaintenance += 1 }
                )
                AutomationRepository.runAcceptedClinicalCalculationFanOutStatic(
                    intent = AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
                    policy = policy,
                    acceptedSnapshot = accepted,
                    acceptedClinicalForecastAuthority = authority,
                    unifiedUam = uam,
                    writeUamCarbs = { carbWrites += 1 },
                    evaluateRulesAndTargetManager = { sensitivity, externalWritesAllowed ->
                        if (externalWritesAllowed) {
                            targetWrites += 1
                            legacyActionWrites += 1
                        }
                        targetInput = targetInput(sensitivity)
                        order += "target-manager"
                        listOf(TargetManager().decide(requireNotNull(targetInput)))
                    },
                    assessAlertCause = { sensitivity, exactUam ->
                        alertInput = alertInput(sensitivity.snapshot, exactUam)
                        order += "alert-cause"
                    },
                    publishAlerts = { _, _ ->
                        notificationWrites += 1
                        episodeWrites += 1
                        aiCallbacks += 1
                    },
                    publishAcceptedForecast = {
                        widgetCallbacks += 1
                        order += "widget"
                    }
                )
            }
        )

        val target = requireNotNull(targetInput)
        val alert = requireNotNull(alertInput)
        assertThat(overview).isSameInstanceAs(accepted)
        assertThat(forecasts.map(Forecast::horizonMinutes)).containsExactly(5, 30, 60).inOrder()
        assertThat(uam.sensitivityCycleId).isEqualTo(accepted.forecastCycleId)
        assertThat(uam.sensitivitySettingsRevision).isEqualTo(accepted.settingsRevision)
        assertThat(uam.acceptedForecastGenerationTimestamp).isEqualTo(authority.generationTimestamp)
        assertThat(uam.acceptedForecastDigest).isEqualTo(authority.digest)
        assertThat(uam.acceptedForecasts.map { forecast ->
            listOf(
                forecast.horizonMinutes,
                forecast.ts,
                forecast.valueMmol,
                forecast.ciLow,
                forecast.ciHigh,
                forecast.modelVersion
            )
        }).containsExactlyElementsIn(authority.forecasts.map { forecast ->
            listOf(
                forecast.horizonMinutes,
                forecast.ts,
                forecast.valueMmol,
                forecast.ciLow,
                forecast.ciHigh,
                forecast.modelVersion
            )
        }).inOrder()
        assertThat(target.sensitivityRuntime.snapshot).isSameInstanceAs(accepted)
        assertThat(target.sensitivityRuntime.snapshot.forecastCycleId).isEqualTo(accepted.forecastCycleId)
        assertThat(target.sensitivityRuntime.snapshot.settingsRevision).isEqualTo(accepted.settingsRevision)
        assertThat(TargetManager().decide(target).outcome).isEqualTo(TargetDecisionOutcome.NO_PROPOSAL)
        assertThat(alert.sensitivity?.cycleId).isEqualTo(accepted.forecastCycleId)
        assertThat(alert.sensitivity?.settingsRevision).isEqualTo(accepted.settingsRevision)
        assertThat(alert.uam?.sensitivityCycleId).isEqualTo(accepted.forecastCycleId)
        assertThat(alert.uam?.sensitivitySettingsRevision).isEqualTo(accepted.settingsRevision)
        assertThat(AlertCauseAnalyzer.analyze(alert).primary).isNotEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(carbWrites).isEqualTo(0)
        assertThat(targetWrites).isEqualTo(0)
        assertThat(legacyActionWrites).isEqualTo(0)
        assertThat(notificationWrites).isEqualTo(0)
        assertThat(episodeWrites).isEqualTo(0)
        assertThat(aiCallbacks).isEqualTo(0)
        assertThat(maintenanceRuns).isEqualTo(0)
        assertThat(remoteRefreshes).isEqualTo(0)
        assertThat(sensitivityMaintenance).isEqualTo(0)
        assertThat(policy.allowActionRepositoryAccess).isFalse()
        assertThat(widgetCallbacks).isEqualTo(1)
        assertThat(order).containsExactly(
            "pending",
            "reserved",
            "committed",
            "read-back",
            "published",
            "overview",
            "target-manager",
            "alert-cause",
            "widget"
        ).inOrder()
    }

    private fun targetInput(
        sensitivity: io.aaps.copilot.domain.predict.SensitivityRuntimeConsumerContext
    ) = TargetManagerInput(
        nowTs = NOW,
        glucoseTimestamp = NOW - 60_000L,
        therapyWatermark = NOW - 120_000L,
        mode = TargetManagerMode.SHADOW,
        proposals = emptyList(),
        runtimeState = TargetManagerRuntimeState(TargetManagerMode.SHADOW),
        activeAapsTarget = null,
        safety = TargetManagerSafetyContext(
            killSwitch = false,
            dataFresh = true,
            sensorTrust = SensorTrustState.TRUSTED,
            deliveryTrust = DeliveryTrustState.NORMAL,
            currentGlucoseMmol = 10.5,
            minimumPredictedOrCiMmol = 10.0,
            lowRiskThresholdMmol = 4.4,
            minTargetMmol = 4.4,
            maxTargetMmol = 8.0,
            minDurationMinutes = 15,
            maxDurationMinutes = 120,
            baseTargetMmol = 6.0,
            safetyIobUnits = 1.0
        ),
        reliability = listOf(5, 30, 60).associateWith { horizon ->
            HorizonReliability(
                horizonMinutes = horizon,
                state = HorizonReliabilityState.RELIABLE,
                sampleCount = 100,
                maeMmol = 0.3,
                biasMmol = 0.0,
                ciCoverage = 0.9,
                weightMultiplier = 1.0,
                evaluatedAt = NOW
            )
        },
        lastAutomaticSent = null,
        baseProvenance = TargetBaseProvenance(1L, "interval", null),
        sensitivityRuntime = sensitivity
    )

    private fun alertInput(
        sensitivity: SensitivityRuntimeSnapshot,
        uam: AutomationRepository.UnifiedUamRuntimeSnapshot
    ): io.aaps.copilot.domain.alerts.AlertCauseInput {
        val insulin = InsulinRuntimeSnapshot(
            timestamp = NOW - 60_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 1.0,
            bolusIobUnits = 1.0,
            basalIobUnits = 0.0,
            insulinActivity = 0.02,
            effectivePositiveIobUnits = 1.0,
            confidence = 1.0,
            fallbackReason = null,
            evidenceTimestamp = NOW - 60_000L,
            therapyCoverage = 1.0
        )
        val insulinContext = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = NOW,
            causalReferenceTimestamp = insulin.timestamp,
            insulinSnapshot = insulin,
            modeledActiveInsulinUnits = 1.0,
            therapyModelAvailable = true,
            freshnessMs = 10 * 60_000L
        )
        return AutomationRepository.buildAlertCauseInputStatic(
            nowTs = NOW,
            currentGlucoseTimestamp = NOW - 60_000L,
            decision = activeDecision(),
            forecasts = forecasts(),
            dataFresh = true,
            sensorQuality = AutomationRepository.SensorQualityAssessment(
                score = 0.95,
                blocked = false,
                reason = "ok",
                suspectFalseLow = false,
                delta5Mmol = 0.3,
                noiseStd5Mmol = 0.05,
                gapMinutes = 5.0
            ),
            sensorBlocked = false,
            insulinCycleContext = insulinContext,
            sensitivitySnapshot = sensitivity,
            unifiedUam = uam,
            deliveryTrust = DeliveryTrustState.NORMAL,
            effectiveBaseTarget = EffectiveBaseTarget(
                manualTargetMmol = 6.0,
                autoDeltaMmol = 0.0,
                effectiveTargetMmol = 6.0,
                state = CircadianAutoState.ACTIVE,
                scheduleRevision = 1L,
                intervalId = "interval",
                adjustmentRunId = "run",
                reasonCodes = listOf("eligible")
            ),
            circadianBiasApplied = false,
            circadianDelta30Mmol = null,
            circadianConfidence = null,
            activeEventTypes = emptySet(),
            eventContextAvailable = true
        )
    }

    private fun activeDecision() = GlucoseAlertDecision(
        state = GlucoseAlertState.SOFT_HIGH_RISK,
        direction = GlucoseAlertDirection.HIGH,
        notifyKind = GlucoseAlertNotifyKind.SOFT_HIGH,
        nextState = GlucoseAlertRuntimeState(activeAlertState = GlucoseAlertState.SOFT_HIGH_RISK),
        lowThreshold = 4.4,
        highThreshold = 10.0,
        urgentLowThreshold = 4.0,
        pred5 = 10.8,
        pred30 = 11.2,
        pred60 = 11.5,
        ciLow30 = 10.4,
        ciHigh30 = 12.0,
        currentGlucoseMmol = 10.5,
        currentGlucoseFresh = true,
        predictedMinutesToLow = null,
        trendDelta5Mmol = 0.3,
        softActive = true,
        strongActive = false,
        repeatSuppressedByTrend = false,
        disableReason = null
    )

    private fun acceptedSnapshot() = SensitivityRuntimeSnapshot(
        settingsRevision = 73L,
        forecastCycleId = "source-change-cycle-73",
        timestamp = NOW - 120_000L,
        isf = sensitivityDecision(3.0),
        cr = sensitivityDecision(10.0)
    )

    private fun sensitivityDecision(value: Double) = SensitivityMetricDecision(
        requested = SensitivitySourcePreference.COPILOT,
        resolved = SensitivityResolvedSource.COPILOT_NATIVE,
        rawAaps = null,
        rawEvidence = null,
        rawCopilot = value,
        blended = null,
        effective = value,
        confidence = 1.0,
        fallbackReason = null
    )

    private fun forecasts() = listOf(5, 30, 60).map { horizon ->
        Forecast(
            ts = NOW + horizon * 60_000L,
            horizonMinutes = horizon,
            valueMmol = when (horizon) {
                5 -> 10.8
                30 -> 11.2
                else -> 11.5
            },
            ciLow = 10.0,
            ciHigh = 12.0,
            modelVersion = "integration"
        )
    }

    private fun authority(forecasts: List<Forecast>) =
        AutomationRepository.AcceptedClinicalForecasts(
            forecasts = forecasts,
            generationTimestamp = NOW,
            digest = "a".repeat(64)
        )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
