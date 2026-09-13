package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.testSensitivityRuntimeSnapshot
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.domain.alerts.AlertCauseAnalyzer
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertContextEventType
import io.aaps.copilot.domain.alerts.AlertUamState
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.DeliveryTrustStateWireCodec
import io.aaps.copilot.domain.target.EffectiveBaseTarget
import io.aaps.copilot.domain.target.TargetManagerMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AutomationAlertCauseIntegrationTest {

    @Test
    fun pureCauseDiagnosticRowsCarryExactSensitivityAndUamIdentityOnly() {
        val input = completeCauseInput(eventContextAvailable = true)
        val analysis = AlertCauseAnalyzer.analyze(input)
        val prepared = AutomationRepository.PreparedAlertCause(
            analysis = analysis,
            snapshot = io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec.encode(input, analysis),
            input = input
        )

        val rows = AutomationRepository.buildAlertCauseDiagnosticTelemetryRowsStatic(
            nowTs = NOW,
            prepared = prepared
        ).associateBy { it.key }

        assertThat(rows.getValue("alert_cause_sensitivity_cycle_id").valueText)
            .isEqualTo(input.sensitivity?.cycleId)
        assertThat(rows.getValue("alert_cause_sensitivity_settings_revision").valueDouble)
            .isEqualTo(input.sensitivity?.settingsRevision?.toDouble())
        assertThat(rows.getValue("alert_cause_uam_sensitivity_cycle_id").valueText)
            .isEqualTo(input.uam?.sensitivityCycleId)
        assertThat(rows.getValue("alert_cause_uam_sensitivity_settings_revision").valueDouble)
            .isEqualTo(input.uam?.sensitivitySettingsRevision?.toDouble())
        assertThat(rows.getValue("alert_cause_identity_status").valueText).isEqualTo("MATCHED")
        assertThat(rows.getValue("alert_cause_primary").valueText).isEqualTo(analysis.primary.name)
        assertThat(rows.values.map { it.source }.distinct())
            .containsExactly("copilot_alert_cause_diagnostic")
        assertThat(rows.keys).containsNoneOf(
            "glucose_alert_notification_posted",
            "glucose_alert_audio_played",
            "glucose_alert_vibration_attempted"
        )
    }

    @Test
    fun inactiveDecisionSkipsCauseAndEventContextWork() = runBlocking<Unit> {
        var builds = 0

        val result = AutomationRepository.prepareAlertCauseForDecisionStatic(safeDecision()) {
            builds++
            error("inactive decision must not query event context")
        }

        assertThat(result).isNull()
        assertThat(builds).isEqualTo(0)
    }

    @Test
    fun ordinaryAnalyzerFailureReturnsBoundedIncompleteCause() = runBlocking<Unit> {
        val first = AutomationRepository.prepareAlertCauseForDecisionStatic(activeDecision()) {
            throw IllegalStateException("private event query detail")
        }
        val second = AutomationRepository.prepareAlertCauseForDecisionStatic(activeDecision()) {
            throw IllegalArgumentException("different private detail")
        }

        assertThat(first?.analysis?.primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(first?.snapshot).isNotNull()
        assertThat(first?.snapshot?.canonicalJson).isEqualTo(second?.snapshot?.canonicalJson)
        assertThat(first?.snapshot?.canonicalJson).contains("\"cause\":\"DATA_INCOMPLETE\"")
        assertThat(first?.snapshot?.canonicalJson).contains("CURRENT_EVIDENCE_MISSING")
        assertThat(first?.snapshot?.canonicalJson).doesNotContain("private")
        assertThat(first?.snapshot?.canonicalJson?.length).isAtMost(4_096)
    }

    @Test
    fun outerPreparationConvertsOptionalContextAssertionAndLinkageErrors() = runBlocking<Unit> {
        val assertion = AutomationRepository.prepareAlertCauseForDecisionStatic(activeDecision()) {
            AutomationRepository.loadOptionalAlertCauseContextStatic<Unit> {
                throw AssertionError("private assertion detail")
            }
            error("unreachable")
        }
        val linkage = AutomationRepository.prepareAlertCauseForDecisionStatic(activeDecision()) {
            AutomationRepository.loadOptionalAlertCauseContextStatic<Unit> {
                throw LinkageError("private linkage detail")
            }
            error("unreachable")
        }

        assertThat(assertion?.analysis?.primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(linkage?.analysis?.primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(assertion?.snapshot?.canonicalJson).isEqualTo(linkage?.snapshot?.canonicalJson)
        assertThat(assertion?.snapshot?.canonicalJson).doesNotContain("private")
    }

    @Test
    fun cancellationEscapesCauseFailureContainment() = runBlocking<Unit> {
        var escaped = false

        try {
            AutomationRepository.prepareAlertCauseForDecisionStatic(activeDecision()) {
                throw kotlinx.coroutines.CancellationException("cancel alert analysis")
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            escaped = true
        }

        assertThat(escaped).isTrue()
    }

    @Test
    fun vmAndThreadTerminationErrorsEscapeCauseFailureContainment() = runBlocking<Unit> {
        var vmEscaped = false
        var threadDeathEscaped = false

        try {
            AutomationRepository.prepareAlertCauseForDecisionStatic(activeDecision()) {
                throw SimulatedFatalVmError()
            }
        } catch (_: SimulatedFatalVmError) {
            vmEscaped = true
        }
        try {
            AutomationRepository.prepareAlertCauseForDecisionStatic(activeDecision()) {
                throw ThreadDeath()
            }
        } catch (_: ThreadDeath) {
            threadDeathEscaped = true
        }

        assertThat(vmEscaped).isTrue()
        assertThat(threadDeathEscaped).isTrue()
    }

    @Test
    fun malformedUamStateUsesBlockedFallback() {
        assertThat(AutomationRepository.parseAlertUamStateStatic("not-a-state"))
            .isEqualTo(AlertUamState.BLOCKED)
    }

    @Test
    fun uamStateConversionLetsNonFatalErrorsReachPreparationBoundary() {
        listOf(
            AssertionError("assert UAM parser"),
            LinkageError("link UAM parser")
        ).forEach { failure ->
            assertSameFailureEscapes(failure) {
                AutomationRepository.parseAlertUamStateStatic("ACTIVE") { throw failure }
            }
        }
    }

    @Test
    fun uamStateConversionRethrowsCancellationAndFatalErrors() {
        listOf(
            kotlinx.coroutines.CancellationException("cancel UAM parser"),
            SimulatedFatalVmError(),
            ThreadDeath()
        ).forEach { failure ->
            assertSameFailureEscapes(failure) {
                AutomationRepository.parseAlertUamStateStatic("ACTIVE") { throw failure }
            }
        }
    }

    @Test
    fun optionalContextTimeoutReturnsToAlertPublicationContinuation() = runTest {
        var loaderCancelled = false
        var publicationReached = false
        val startedAt = testScheduler.currentTime

        val result: AlertCauseContextLoadResult<String> = AutomationRepository.loadOptionalAlertCauseContextStatic(
            timeoutMs = 125L
        ) {
            try {
                awaitCancellation()
            } finally {
                loaderCancelled = true
            }
        }
        publicationReached = true

        assertThat(result).isEqualTo(AlertCauseContextLoadResult.Timeout)
        assertThat(loaderCancelled).isTrue()
        assertThat(publicationReached).isTrue()
        assertThat(testScheduler.currentTime - startedAt).isEqualTo(125L)
    }

    @Test
    fun optionalContextTimeoutPreservesParentCancellation() = runBlocking<Unit> {
        val cancellation = CancellationException("parent cancelled")
        var escaped: CancellationException? = null

        try {
            AutomationRepository.loadOptionalAlertCauseContextStatic(timeoutMs = 125L) {
                throw cancellation
            }
        } catch (caught: CancellationException) {
            escaped = caught
        }

        assertThat(escaped).isNotNull()
        assertThat(escaped?.message).isEqualTo(cancellation.message)
    }

    @Test
    fun optionalContextFailureProducesOneAccurateAuditOutcome() = runBlocking<Unit> {
        val result: AlertCauseContextLoadResult<String> =
            AutomationRepository.loadOptionalAlertCauseContextStatic(timeoutMs = 125L) {
                throw IllegalStateException("private DAO detail")
            }
        val warnings = mutableListOf<Pair<String, Map<String, Any?>>>()

        AutomationRepository.reportAlertCauseContextOutcomeStatic(
            result = result,
            stage = GlucoseAlertState.LOW_NOW,
            warn = { message, metadata -> warnings += message to metadata }
        )

        assertThat(result).isEqualTo(AlertCauseContextLoadResult.Failure("IllegalStateException"))
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single().first).isEqualTo("glucose_alert_cause_context_unavailable")
        assertThat(warnings.single().second["reason"]).isEqualTo("failure")
        assertThat(warnings.single().second["errorType"]).isEqualTo("IllegalStateException")
        assertThat(warnings.single().second).doesNotContainKey("timeoutMs")
        assertThat(warnings.single().second.toString()).doesNotContain("private DAO detail")
    }

    @Test
    fun timeoutAndOverflowEachProduceOneAccurateAuditOutcome() = runBlocking<Unit> {
        val timeoutWarnings = mutableListOf<Map<String, Any?>>()
        val overflowWarnings = mutableListOf<Map<String, Any?>>()

        AutomationRepository.reportAlertCauseContextOutcomeStatic(
            result = AlertCauseContextLoadResult.Timeout,
            stage = GlucoseAlertState.WARNING_30,
            warn = { _, metadata -> timeoutWarnings += metadata }
        )
        AutomationRepository.reportAlertCauseContextOutcomeStatic(
            result = AlertCauseContextLoadResult.Overflow(AlertCauseContextSource.CONTEXT_TAGS),
            stage = GlucoseAlertState.WARNING_30,
            warn = { _, metadata -> overflowWarnings += metadata }
        )

        assertThat(timeoutWarnings).hasSize(1)
        assertThat(timeoutWarnings.single()["reason"]).isEqualTo("timeout")
        assertThat(timeoutWarnings.single()["timeoutMs"])
            .isEqualTo(AutomationRepository.ALERT_CAUSE_CONTEXT_TIMEOUT_MS)
        assertThat(timeoutWarnings.single()).doesNotContainKey("errorType")
        assertThat(overflowWarnings).hasSize(1)
        assertThat(overflowWarnings.single()["reason"]).isEqualTo("overflow")
        assertThat(overflowWarnings.single()["source"]).isEqualTo("CONTEXT_TAGS")
        assertThat(overflowWarnings.single()).doesNotContainKey("timeoutMs")
    }

    @Test
    fun optionalContextLoaderDoesNotCatchErrors() = runBlocking<Unit> {
        listOf(
            AssertionError("assertion"),
            LinkageError("linkage"),
            SimulatedFatalVmError(),
            ThreadDeath()
        ).forEach { failure ->
            var escaped: Throwable? = null
            try {
                AutomationRepository.loadOptionalAlertCauseContextStatic<String> {
                    throw failure
                }
            } catch (caught: Throwable) {
                escaped = caught
            }
            assertThat(escaped).isInstanceOf(failure::class.java)
        }
    }

    @Test
    fun irrelevantTherapyVolumeCannotEvictOlderRelevantContext() {
        val stress = TherapyEvent(
            ts = 1L,
            type = "stress",
            payload = emptyMap()
        )
        val history = listOf(stress) + (0 until 10_000).map { index ->
            TherapyEvent(
                ts = index + 2L,
                type = "bolus",
                payload = mapOf("insulin" to "1.0")
            )
        }

        val bounded = AutomationRepository.boundAlertCauseTherapyStatic(history)

        assertThat(bounded.overflow).isFalse()
        assertThat(bounded.rows).containsExactly(stress)
    }

    @Test
    fun relevantTherapyOverflowIsExplicitAndForcesIncompleteCause() {
        val history = (0..AutomationRepository.ALERT_CAUSE_MAX_THERAPY_EVENTS).map { index ->
            TherapyEvent(
                ts = index.toLong(),
                type = "stress",
                payload = emptyMap()
            )
        }

        val bounded = AutomationRepository.boundAlertCauseTherapyStatic(history)
        val completeInput = completeCauseInput(eventContextAvailable = true)
        val input = completeCauseInput(eventContextAvailable = !bounded.overflow)

        assertThat(bounded.rows).hasSize(AutomationRepository.ALERT_CAUSE_MAX_THERAPY_EVENTS)
        assertThat(bounded.overflow).isTrue()
        assertThat(AlertCauseAnalyzer.analyze(completeInput).primary)
            .isNotEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(AlertCauseAnalyzer.analyze(input).primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
    }

    @Test
    fun plannedOverflowAndBoundedScanExhaustionBothFailClosed() = runBlocking<Unit> {
        var nextId = 0
        var pageCalls = 0
        val scanResult = AutomationRepository.loadRelevantPlannedAlertEventsStatic(NOW) { _, _, limit ->
            pageCalls += 1
            List(limit) {
                val id = nextId++
                PlannedActivityEventEntity(
                    eventId = "irrelevant-${id.toString().padStart(4, '0')}",
                    enabled = true,
                    title = "ignored",
                    activityType = "WALKING",
                    intensity = "MEDIUM",
                    localStartIso = "2000-01-01T10:00:00",
                    durationMinutes = 30,
                    timezoneId = "UTC",
                    recurrenceDaysMask = 0,
                    recurrenceEndEpochDay = null,
                    revision = 1L,
                    createdAtMs = 1L,
                    updatedAtMs = 1L
                )
            }
        }

        assertThat(scanResult).isEqualTo(AlertCausePlannedLoadResult.ScanLimitReached)
        assertThat(pageCalls).isEqualTo(AutomationRepository.ALERT_CAUSE_MAX_PLANNED_PAGES)
        listOf(AlertCausePlannedLoadResult.RelevantOverflow, scanResult).forEach { unavailable ->
            val input = completeCauseInput(
                eventContextAvailable = unavailable is AlertCausePlannedLoadResult.Complete
            )
            assertThat(AlertCauseAnalyzer.analyze(input).primary)
                .isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        }
    }

    @Test
    fun alertEventContextEnumConversionFallsBackOnlyForOrdinaryExceptions() {
        val ordinary = parseAlertContextEnumOrDefault(
            raw = "malformed",
            fallback = CompensationEventStatus.ACTIVE,
            parse = CompensationEventStatus::valueOf
        )
        assertThat(ordinary).isEqualTo(CompensationEventStatus.ACTIVE)

        listOf(
            kotlinx.coroutines.CancellationException("cancel context parser"),
            SimulatedFatalVmError(),
            ThreadDeath()
        ).forEach { failure ->
            assertSameFailureEscapes(failure) {
                parseAlertContextEnumOrDefault(
                    raw = "ACTIVE",
                    fallback = CompensationEventStatus.ACTIVE
                ) { throw failure }
            }
        }
    }

    @Test
    fun exactFanoutAndUnifiedUamIdentitiesEnterCauseInputWithoutAnotherRead() {
        val sensitivity = testSensitivityRuntimeSnapshot(
            cycleId = "accepted-cycle-77",
            settingsRevision = 77L,
            isf = 2.7,
            cr = 8.5,
            timestamp = NOW - 2_000L
        )
        val insulinSnapshot = InsulinRuntimeSnapshot(
            timestamp = NOW - 60_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 1.2,
            bolusIobUnits = 0.8,
            basalIobUnits = 0.4,
            insulinActivity = 0.03,
            effectivePositiveIobUnits = 1.2,
            confidence = 1.0,
            fallbackReason = null,
            evidenceTimestamp = NOW - 60_000L,
            therapyCoverage = 1.0
        )
        val insulinContext = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = NOW,
            causalReferenceTimestamp = insulinSnapshot.timestamp,
            insulinSnapshot = insulinSnapshot,
            modeledActiveInsulinUnits = 1.2,
            therapyModelAvailable = true,
            freshnessMs = 10 * 60_000L
        )
        val canonicalSampleTimestamp = NOW - 5 * 60_000L
        val uam = AutomationRepository.UnifiedUamRuntimeSnapshot(
            timestamp = canonicalSampleTimestamp,
            state = "ACTIVE",
            flag = 1.0,
            controlFlag = 1.0,
            confidence = 0.85,
            impactMmol5 = 0.4,
            signedResidualMmol5 = 0.35,
            shortAverageDeltaMmol5 = 0.3,
            forecastComponent60Mmol = 2.0,
            equivalentCarbsGrams = 16.0,
            supportedLowerBoundGrams = 8.0,
            onsetTs = NOW - 10 * 60_000L,
            firstDetectionTs = NOW - 10 * 60_000L,
            activeSinceTs = NOW - 5 * 60_000L,
            supportStableBuckets = 3,
            lowerBoundStableBuckets = 2,
            sensorTrust = 0.9,
            therapyCoverage = 1.0,
            source = "local",
            reasons = setOf("qualified"),
            algorithmVersion = "test",
            episodeId = "private-episode-id",
            effectiveCobGrams = 0.0,
            sensorBlocked = false,
            sensitivityCycleId = sensitivity.forecastCycleId,
            sensitivitySettingsRevision = sensitivity.settingsRevision,
            sensitivityIsfMmolPerUnit = sensitivity.isf.effective,
            sensitivityCrGramPerUnit = sensitivity.cr.effective
        )

        fun buildInput(
            unifiedUam: AutomationRepository.UnifiedUamRuntimeSnapshot
        ) = AutomationRepository.buildAlertCauseInputStatic(
            nowTs = NOW,
            currentGlucoseTimestamp = canonicalSampleTimestamp,
            decision = activeDecision(),
            forecasts = forecasts(),
            dataFresh = true,
            sensorQuality = AutomationRepository.SensorQualityAssessment(
                score = 0.9,
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
            unifiedUam = unifiedUam,
            deliveryTrust = DeliveryTrustState.NORMAL,
            effectiveBaseTarget = effectiveTarget(),
            circadianBiasApplied = false,
            circadianDelta30Mmol = null,
            circadianConfidence = null,
            activeEventTypes = emptySet(),
            eventContextAvailable = true
        )

        val input = buildInput(uam)

        assertThat(input.cycleTimestamp).isEqualTo(NOW)
        assertThat(input.insulin?.snapshotTimestamp).isEqualTo(insulinSnapshot.timestamp)
        assertThat(input.insulin?.source?.name).isEqualTo(insulinSnapshot.source.name)
        assertThat(input.sensitivity?.cycleId).isEqualTo(sensitivity.forecastCycleId)
        assertThat(input.sensitivity?.settingsRevision).isEqualTo(sensitivity.settingsRevision)
        assertThat(input.uam?.timestamp).isEqualTo(uam.timestamp)
        assertThat(input.uam?.sensitivityCycleId).isEqualTo(sensitivity.forecastCycleId)
        assertThat(input.uam?.equivalentCarbsGrams).isEqualTo(16.0)
        assertThat(input.direction).isEqualTo(AlertCauseDirection.HIGH)
        assertThat(AlertCauseAnalyzer.analyze(input).primary).isEqualTo(AlertCauseCode.MEAL_UAM)

        val invalidStateInput = buildInput(uam.copy(state = "INVALID", flag = 1.0))
        assertThat(invalidStateInput.uam?.state).isEqualTo(AlertUamState.BLOCKED)
        assertThat(invalidStateInput.uam?.active).isTrue()
        assertThat(AlertCauseAnalyzer.analyze(invalidStateInput).primary)
            .isNotEqualTo(AlertCauseCode.MEAL_UAM)
    }

    @Test
    fun deliveryTrustUsesPreservedProvenanceAndDeterministicStablePriority() {
        val stable = AutomationRepository.decodeAlertDeliveryTrustStatic(
            telemetry = listOf(
                deliveryTelemetry(
                    timestamp = 100L,
                    source = AutomationRepository.TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    value = DeliveryTrustStateWireCodec.encode(DeliveryTrustState.SUSPECTED_NONRESPONSE)
                )
            )
        )
        val unrelated = AutomationRepository.decodeAlertDeliveryTrustStatic(
            telemetry = emptyList()
        )
        val validLegacy = AutomationRepository.decodeAlertDeliveryTrustStatic(
            telemetry = listOf(
                deliveryTelemetry(
                    timestamp = 200L,
                    source = DeliveryTrustStateWireCodec.LEGACY_SOURCE,
                    key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                    value = 2.0
                )
            )
        )
        val stableWinsLegacy = AutomationRepository.decodeAlertDeliveryTrustStatic(
            telemetry = listOf(
                deliveryTelemetry(
                    timestamp = 100L,
                    source = AutomationRepository.TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    value = DeliveryTrustStateWireCodec.encode(DeliveryTrustState.WATCH)
                ),
                deliveryTelemetry(
                    timestamp = 300L,
                    source = DeliveryTrustStateWireCodec.LEGACY_SOURCE,
                    key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                    value = 2.0
                )
            )
        )
        val invalidStableFallsBackToTrustedLegacy = AutomationRepository.decodeAlertDeliveryTrustStatic(
            telemetry = listOf(
                deliveryTelemetry(
                    timestamp = 400L,
                    source = "foreign",
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    value = 2.0
                ),
                deliveryTelemetry(
                    timestamp = 300L,
                    source = DeliveryTrustStateWireCodec.LEGACY_SOURCE,
                    key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                    value = 2.0
                )
            )
        )
        val equalTimestampStableConflict = AutomationRepository.decodeAlertDeliveryTrustStatic(
            telemetry = listOf(
                deliveryTelemetry(
                    timestamp = 500L,
                    source = "a",
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    value = DeliveryTrustStateWireCodec.encode(DeliveryTrustState.WATCH)
                ),
                deliveryTelemetry(
                    timestamp = 500L,
                    source = "b",
                    key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    value = DeliveryTrustStateWireCodec.encode(DeliveryTrustState.SUSPECTED_NONRESPONSE)
                )
            )
        )

        assertThat(stable).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(unrelated).isNull()
        assertThat(validLegacy).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(stableWinsLegacy).isEqualTo(DeliveryTrustState.WATCH)
        assertThat(invalidStableFallsBackToTrustedLegacy)
            .isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(equalTimestampStableConflict).isNull()
    }

    @Test
    fun targetManagerOffCannotPromoteForeignLegacyTelemetry() {
        val routing = AutomationRepository.automaticTargetWriterRoutingStatic(TargetManagerMode.OFF)
        val selected = AutomationRepository.selectAlertDeliveryTrustTelemetryStatic(
            rows = listOf(
                io.aaps.copilot.data.local.entity.TelemetrySampleEntity(
                    id = "foreign-target-manager-off",
                    timestamp = 700L,
                    source = "target_manager_off",
                    key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                    valueDouble = 2.0,
                    valueText = null,
                    unit = null,
                    quality = "OK"
                )
            )
        )

        assertThat(routing.evaluateManager).isFalse()
        assertThat(selected.single().source).isEqualTo("target_manager_off")
        assertThat(selected.single().timestamp).isEqualTo(700L)
        assertThat(AutomationRepository.decodeAlertDeliveryTrustStatic(selected)).isNull()
    }

    @Test
    fun sameCycleStableDeliveryTrustUsesTargetManagerProvenance() {
        val row = AutomationRepository.buildSameCycleAlertDeliveryTrustTelemetryStatic(
            nowTs = 800L,
            state = DeliveryTrustState.WATCH
        )

        assertThat(row.timestamp).isEqualTo(800L)
        assertThat(row.source).isEqualTo("copilot_target_manager")
        assertThat(row.source).isNotEqualTo(DeliveryTrustStateWireCodec.LEGACY_SOURCE)
        assertThat(AutomationRepository.decodeAlertDeliveryTrustStatic(listOf(row)))
            .isEqualTo(DeliveryTrustState.WATCH)
    }

    @Test
    fun activeEventMappingReturnsOnlyTypesAndDeterministicOrder() {
        val events = listOf(
            event("secret-id-1", CompensationEventType.ILLNESS, "private title", "private note"),
            event("secret-id-2", CompensationEventType.ACTIVITY, "activity title", null),
            event("ended", CompensationEventType.STRESS, "ended", null, endTs = NOW)
        )

        val types = AutomationRepository.activeAlertContextTypesStatic(events, NOW)

        assertThat(types).containsExactly(
            AlertContextEventType.ACTIVITY,
            AlertContextEventType.ILLNESS
        ).inOrder()
        assertThat(types.joinToString()).doesNotContain("secret")
        assertThat(types.joinToString()).doesNotContain("private")
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
        ciLow30 = 10.5,
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

    private fun safeDecision() = activeDecision().copy(
        state = GlucoseAlertState.NONE,
        direction = null,
        notifyKind = GlucoseAlertNotifyKind.NONE,
        softActive = false,
        episodeStage = GlucoseAlertState.NONE,
        episodeDirection = null
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
            modelVersion = "test"
        )
    }

    private fun effectiveTarget() = EffectiveBaseTarget(
        manualTargetMmol = 6.0,
        autoDeltaMmol = 0.0,
        effectiveTargetMmol = 6.0,
        state = CircadianAutoState.ACTIVE,
        scheduleRevision = 4L,
        intervalId = "private-interval",
        adjustmentRunId = "private-run",
        reasonCodes = listOf("eligible")
    )

    private fun completeCauseInput(eventContextAvailable: Boolean) =
        io.aaps.copilot.domain.alerts.AlertCauseInput(
            nowTs = NOW,
            cycleTimestamp = NOW,
            direction = AlertCauseDirection.HIGH,
            glucose = io.aaps.copilot.domain.alerts.AlertGlucoseEvidence(
                currentMmol = 10.5,
                currentTimestamp = NOW - 60_000L,
                forecast5Mmol = 10.8,
                forecast30Mmol = 11.2,
                forecast60Mmol = 11.5,
                lowerCi5Mmol = 10.0,
                lowerCi30Mmol = 10.4,
                lowerCi60Mmol = 10.6,
                trendDelta5Mmol = 0.3,
                dataFresh = true
            ),
            sensor = io.aaps.copilot.domain.alerts.AlertSensorEvidence(
                score = 0.95,
                blocked = false,
                suspectFalseLow = false
            ),
            insulin = io.aaps.copilot.domain.alerts.AlertInsulinEvidence(
                cycleTimestamp = NOW,
                snapshotTimestamp = NOW - 60_000L,
                evidenceTimestamp = NOW - 60_000L,
                source = io.aaps.copilot.domain.alerts.AlertInsulinSource.AAPS_COMPONENTS,
                netIobUnits = 1.0,
                effectivePositiveIobUnits = 1.0,
                insulinActivity = 0.03,
                confidence = 1.0
            ),
            sensitivity = io.aaps.copilot.domain.alerts.AlertSensitivityEvidence(
                cycleId = "accepted-cycle",
                settingsRevision = 1L,
                timestamp = NOW - 30_000L,
                isfMmolPerUnit = 2.5,
                crGramPerUnit = 9.0,
                isfSource = io.aaps.copilot.domain.alerts.AlertSensitivitySource.AAPS,
                crSource = io.aaps.copilot.domain.alerts.AlertSensitivitySource.AAPS,
                confidence = 1.0
            ),
            uam = io.aaps.copilot.domain.alerts.AlertUamEvidence(
                timestamp = NOW - 60_000L,
                state = AlertUamState.INACTIVE,
                active = false,
                controlActive = false,
                confidence = 0.0,
                signedResidualMmol5 = 0.0,
                equivalentCarbsGrams = null,
                sensitivityCycleId = "accepted-cycle",
                sensitivitySettingsRevision = 1L
            ),
            deliveryTrust = DeliveryTrustState.NORMAL,
            target = null,
            circadian = null,
            activeEventTypes = emptySet(),
            eventContextAvailable = eventContextAvailable
        )

    private fun event(
        id: String,
        type: CompensationEventType,
        title: String,
        note: String?,
        endTs: Long = NOW + 60_000L
    ) = CompensationEvent(
        localId = id,
        startTs = NOW - 60_000L,
        endTs = endTs,
        type = type,
        title = title,
        note = note
    )

    private fun deliveryTelemetry(
        timestamp: Long,
        source: String,
        key: String,
        value: Double?
    ) = DeliveryTrustTelemetryValue(timestamp, source, key, value)

    private fun assertSameFailureEscapes(failure: Throwable, block: () -> Unit) {
        var escaped: Throwable? = null
        try {
            block()
        } catch (caught: Throwable) {
            escaped = caught
        }
        assertThat(escaped).isSameInstanceAs(failure)
    }

    companion object {
        private const val NOW = 1_900_000_000_000L
    }
}

private class SimulatedFatalVmError : VirtualMachineError()
