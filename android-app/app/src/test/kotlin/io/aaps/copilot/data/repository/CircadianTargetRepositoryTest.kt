package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.TelemetrySampleLite
import io.aaps.copilot.data.local.entity.CircadianTargetAdjustmentEntity
import io.aaps.copilot.data.local.entity.CircadianTargetRunEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.target.BaseTargetInterval
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.domain.target.CircadianPreviousAdjustment
import io.aaps.copilot.domain.target.CircadianPriorStepComparison
import io.aaps.copilot.domain.target.CircadianStepEvidence
import io.aaps.copilot.domain.target.CircadianTargetAdjustment
import io.aaps.copilot.domain.target.CircadianTargetCohorts
import io.aaps.copilot.domain.target.CircadianTargetEvaluationInput
import io.aaps.copilot.domain.target.CircadianTargetEvaluationResult
import io.aaps.copilot.domain.target.CircadianTargetSlot
import io.aaps.copilot.domain.target.EffectiveTargetRuntimeGates
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetManagerMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class CircadianTargetRepositoryTest {

    @Test
    fun evaluateReadsBoundedInputsOncePagesWhitelistAndPublishesOneRun() = runTest {
        val telemetry = telemetryRows(4_001)
        val previousRun = runEntity(
            runId = "previous-run",
            scheduleRevision = 42L,
            localRunDate = "2026-07-19"
        )
        val previousAdjustment = previousAdjustmentEntity(previousRun)
        val source = FakeSource(telemetryRows = telemetry).apply {
            latestRuns[42L] = previousRun
            visibleAdjustments += previousAdjustment
        }
        val canonical = listOf(
            GlucosePoint(
                ts = NOW,
                valueMmol = 6.7,
                source = "calibrated",
                quality = DataQuality.OK
            )
        )
        val calibration = RecordingCalibration(canonical)
        val cohorts = CircadianTargetCohorts(
            clean = emptyList(),
            allContext = emptyList(),
            validDates = emptySet(),
            reasonCounts = emptyMap()
        )
        val cohortAssembler = RecordingCohortAssembler(cohorts)
        val result = evaluationResult(
            adjustments = listOf(
                adjustment(CircadianDayType.WEEKDAY, hour = 10, delta = -0.1),
                adjustment(CircadianDayType.ALL, hour = 10, delta = -0.1)
            )
        )
        val evaluator = RecordingEvaluator(result)
        val repository = repository(
            source = source,
            calibration = calibration,
            cohortAssembler = cohortAssembler,
            evaluator = evaluator
        )

        val stored = repository.evaluateAndPublish(
            now = NOW,
            schedule = schedule(),
            zoneId = ZONE
        )

        assertThat(source.glucoseReads).containsExactly(Window(LOOKBACK_START, NOW))
        assertThat(source.therapyReads).containsExactly(Window(LOOKBACK_START, NOW))
        assertThat(source.forecastReads).containsExactly(Window(LOOKBACK_START, NOW))
        assertThat(source.telemetryReads).hasSize(3)
        assertThat(source.telemetryReads.map { it.limit }).containsExactly(2_000, 2_000, 2_000)
        source.telemetryReads.forEach { read ->
            assertThat(read.since).isEqualTo(LOOKBACK_START)
            assertThat(read.through).isEqualTo(NOW)
            assertThat(read.keys).containsExactlyElementsIn(EXPECTED_TELEMETRY_KEYS)
        }
        assertThat(calibration.calls).containsExactly(CalibrationCall(source.glucoseRows, NOW))
        assertThat(cohortAssembler.calls).hasSize(1)
        assertThat(cohortAssembler.calls.single().zoneId).isEqualTo(ZONE)
        assertThat(cohortAssembler.calls.single().canonicalGlucose).isEqualTo(canonical)
        assertThat(cohortAssembler.calls.single().telemetry).hasSize(4_001)
        assertThat(cohortAssembler.calls.single().therapyEvents.single().payload)
            .containsEntry("grams", "15")
        assertThat(evaluator.inputs).hasSize(1)
        assertThat(evaluator.inputs.single().cohorts).isSameInstanceAs(cohorts)
        assertThat(evaluator.inputs.single().previousAdjustments)
            .containsEntry(
                CircadianTargetSlot(CircadianDayType.ALL, 10),
                CircadianPreviousAdjustment(
                    appliedDeltaMmol = -0.2,
                    unchangedSuccessfulRuns = 2,
                    priorStepComparison = null
                )
            )
        assertThat(source.latestRunReads).containsExactly(42L)
        assertThat(source.adjustmentReads).containsExactly("previous-run")
        assertThat(source.publishCalls).hasSize(1)
        assertThat(source.publishCalls.single().adjustments).hasSize(2)
        assertThat(source.publishCalls.single().adjustments.first().manualTargetMmol).isEqualTo(5.8)
        assertThat(source.cleanupCutoffs).containsExactly(RETENTION_CUTOFF)
        assertThat(stored).isEqualTo(source.publishCalls.single().run)
        assertThat(stored.scheduleRevision).isEqualTo(42L)
        assertThat(stored.lookbackStart).isEqualTo(LOOKBACK_START)
        assertThat(stored.lookbackEnd).isEqualTo(NOW)
        assertThat(stored.localRunDate).isEqualTo("2026-07-20")
    }

    @Test
    fun evaluatorOutputAboveSeventyTwoRowsFailsClosedWithoutPublishingAdjustments() = runTest {
        val source = FakeSource()
        val tooMany = buildList {
            repeat(73) { index ->
                add(
                    adjustment(
                        dayType = CircadianDayType.entries[index % CircadianDayType.entries.size],
                        hour = index % 24,
                        delta = 0.0
                    )
                )
            }
        }
        val repository = repository(
            source = source,
            evaluator = RecordingEvaluator(evaluationResult(adjustments = tooMany))
        )

        val stored = repository.evaluateAndPublish(NOW, schedule(), ZONE)

        assertThat(source.publishCalls).isEmpty()
        assertThat(source.failedRuns).containsExactly(stored)
        assertThat(stored.status).isEqualTo("FAILED")
        assertThat(decodeReasons(stored.reasonCodesJson))
            .contains("adjustment_count_exceeds_72")
    }

    @Test
    fun publicationFailureStoresOnlyFailureSummaryAndExposesNoAdjustments() = runTest {
        val source = FakeSource().apply {
            publishFailure = IllegalStateException("injected publication failure")
        }
        val repository = repository(
            source = source,
            evaluator = RecordingEvaluator(
                evaluationResult(
                    adjustments = listOf(adjustment(CircadianDayType.ALL, 10, -0.1))
                )
            )
        )

        val stored = repository.evaluateAndPublish(NOW, schedule(), ZONE)

        assertThat(source.visibleAdjustments).isEmpty()
        assertThat(source.failedRuns).containsExactly(stored)
        assertThat(stored.status).isEqualTo("FAILED")
        assertThat(decodeReasons(stored.reasonCodesJson)).contains("publication_failed")
    }

    @Test
    fun reasonJsonIsValidAndBoundedForRunsAndAdjustments() = runTest {
        val source = FakeSource()
        val oversizedReasons = List(1_000) { index ->
            "reason_${index}_" + "x".repeat(300)
        }
        val repository = repository(
            source = source,
            evaluator = RecordingEvaluator(
                evaluationResult(
                    adjustments = listOf(
                        adjustment(CircadianDayType.ALL, 10, 0.0, oversizedReasons)
                    ),
                    reasons = oversizedReasons
                )
            )
        )

        repository.evaluateAndPublish(NOW, schedule(), ZONE)

        val publication = source.publishCalls.single()
        assertThat(publication.run.reasonCodesJson.toByteArray().size).isAtMost(64 * 1_024)
        assertThat(publication.adjustments.single().reasonCodesJson.toByteArray().size)
            .isAtMost(64 * 1_024)
        assertThat(decodeReasons(publication.run.reasonCodesJson)).isNotEmpty()
        assertThat(decodeReasons(publication.adjustments.single().reasonCodesJson)).isNotEmpty()
    }

    @Test
    fun cancellationIsRethrownWithoutFailureRowOrCleanup() = runTest {
        val cancellation = CancellationException("stop")
        val source = FakeSource().apply { glucoseFailure = cancellation }
        val repository = repository(source = source)

        val thrown = try {
            repository.evaluateAndPublish(NOW, schedule(), ZONE)
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
        assertThat(source.failedRuns).isEmpty()
        assertThat(source.publishCalls).isEmpty()
        assertThat(source.cleanupCutoffs).isEmpty()
    }

    @Test
    fun cancellationDuringPublicationRaceLookupIsNeverConvertedToFailedRun() = runTest {
        val cancellation = CancellationException("cancel race lookup")
        val source = FakeSource().apply {
            publishFailure = IllegalStateException("injected publication failure")
            runForDateFailureCall = 2
            runForDateFailure = cancellation
        }
        val repository = repository(
            source = source,
            evaluator = RecordingEvaluator(
                evaluationResult(
                    adjustments = listOf(adjustment(CircadianDayType.ALL, 10, -0.1))
                )
            )
        )

        val thrown = try {
            repository.evaluateAndPublish(NOW, schedule(), ZONE)
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
        assertThat(source.failedRuns).isEmpty()
        assertThat(source.cleanupCutoffs).isEmpty()
    }

    @Test
    fun latestCompletedRunUsesOnlyRequestedScheduleRevision() = runTest {
        val source = FakeSource()
        val current = runEntity(runId = "current", scheduleRevision = 42L)
        source.latestRuns[42L] = current
        source.latestRuns[41L] = runEntity(runId = "stale", scheduleRevision = 41L)
        val repository = repository(source = source)

        val loaded = repository.latestCompletedRun(scheduleRevision = 42L)

        assertThat(loaded).isEqualTo(current)
        assertThat(source.latestRunReads).containsExactly(42L)
    }

    @Test
    fun latestCompletedLocalDateUsesRequestedRevisionAndRejectsMalformedValue() = runTest {
        val source = FakeSource().apply {
            latestDates[42L] = "2026-07-20"
            latestDates[41L] = "malformed"
        }
        val repository = repository(source = source)

        assertThat(repository.latestCompletedLocalRunDate(42L))
            .isEqualTo(java.time.LocalDate.of(2026, 7, 20))
        assertThat(repository.latestCompletedLocalRunDate(41L)).isNull()
        assertThat(source.latestDateReads).containsExactly(42L, 41L).inOrder()
    }

    @Test
    fun effectiveResolutionLoadsOneLatestRunSnapshotWithoutHistoricalScans() = runTest {
        val latest = runEntity(runId = "effective-run", scheduleRevision = 42L)
        val source = FakeSource().apply {
            latestRuns[42L] = latest
            visibleAdjustments += previousAdjustmentEntity(latest).copy(
                id = "effective-run:WEEKDAY:9",
                dayType = CircadianDayType.WEEKDAY.name,
                hour = 9,
                generatedAt = NOW - 60L * 60L * 1_000L,
                validUntil = NOW + 60L * 60L * 1_000L
            )
        }
        val repository = repository(source = source)

        val result = repository.resolveEffectiveTarget(
            now = NOW,
            schedule = schedule(),
            zoneId = ZONE,
            targetManagerMode = TargetManagerMode.ACTIVE,
            hardMinTargetMmol = 4.0,
            hardMaxTargetMmol = 10.0,
            gates = trustedRuntimeGates()
        )
        val repeated = repository.resolveEffectiveTarget(
            now = NOW,
            schedule = schedule(),
            zoneId = ZONE,
            targetManagerMode = TargetManagerMode.ACTIVE,
            hardMinTargetMmol = 4.0,
            hardMaxTargetMmol = 10.0,
            gates = trustedRuntimeGates()
        )

        val next = latest.copy(runId = "effective-run-next", completedAt = NOW + 1L)
        source.latestRuns[42L] = next
        source.visibleAdjustments += previousAdjustmentEntity(next).copy(
            id = "effective-run-next:WEEKDAY:9",
            dayType = CircadianDayType.WEEKDAY.name,
            hour = 9,
            appliedDeltaMmol = 0.2,
            generatedAt = NOW - 60L * 60L * 1_000L,
            validUntil = NOW + 60L * 60L * 1_000L
        )
        val afterRunChange = repository.resolveEffectiveTarget(
            now = NOW,
            schedule = schedule(),
            zoneId = ZONE,
            targetManagerMode = TargetManagerMode.ACTIVE,
            hardMinTargetMmol = 4.0,
            hardMaxTargetMmol = 10.0,
            gates = trustedRuntimeGates()
        )

        assertThat(result.manualTargetMmol).isWithin(1e-9).of(5.8)
        assertThat(result.autoDeltaMmol).isWithin(1e-9).of(-0.2)
        assertThat(result.effectiveTargetMmol).isWithin(1e-9).of(5.6)
        assertThat(result.adjustmentRunId).isEqualTo("effective-run")
        assertThat(repeated).isEqualTo(result)
        assertThat(afterRunChange.effectiveTargetMmol).isWithin(1e-9).of(6.0)
        assertThat(afterRunChange.adjustmentRunId).isEqualTo("effective-run-next")
        assertThat(source.latestRunReads).containsExactly(42L, 42L, 42L).inOrder()
        assertThat(source.adjustmentReads)
            .containsExactly("effective-run", "effective-run-next")
            .inOrder()
        assertThat(source.glucoseReads).isEmpty()
        assertThat(source.therapyReads).isEmpty()
        assertThat(source.telemetryReads).isEmpty()
    }

    @Test
    fun effectiveResolutionSourceFailureReturnsManualWaitingState() = runTest {
        val source = FakeSource().apply {
            latestRunFailure = IllegalStateException("database temporarily unavailable")
        }
        val repository = repository(source = source)

        val result = repository.resolveEffectiveTarget(
            now = NOW,
            schedule = schedule(),
            zoneId = ZONE,
            targetManagerMode = TargetManagerMode.ACTIVE,
            hardMinTargetMmol = 4.0,
            hardMaxTargetMmol = 10.0,
            gates = trustedRuntimeGates()
        )

        assertThat(result.manualTargetMmol).isWithin(1e-9).of(5.8)
        assertThat(result.autoDeltaMmol).isWithin(1e-9).of(0.0)
        assertThat(result.effectiveTargetMmol).isWithin(1e-9).of(5.8)
        assertThat(result.state).isEqualTo(CircadianAutoState.WAITING_DATA)
        assertThat(result.reasonCodes).containsExactly("adjustment_source_failed")
    }

    @Test
    fun malformedRequestedRowCannotFallThroughToValidAllRow() = runTest {
        val latest = runEntity(runId = "malformed-run", scheduleRevision = 42L)
        val source = FakeSource().apply {
            latestRuns[42L] = latest
            visibleAdjustments += previousAdjustmentEntity(latest).copy(
                id = "malformed-run:WEEKDAY:9",
                dayType = CircadianDayType.WEEKDAY.name,
                hour = 9,
                generatedAt = NOW - 60L * 60L * 1_000L,
                validUntil = NOW + 60L * 60L * 1_000L,
                reasonCodesJson = "not-json"
            )
            visibleAdjustments += previousAdjustmentEntity(latest).copy(
                id = "malformed-run:ALL:9",
                dayType = CircadianDayType.ALL.name,
                hour = 9,
                generatedAt = NOW - 60L * 60L * 1_000L,
                validUntil = NOW + 60L * 60L * 1_000L
            )
        }
        val repository = repository(source = source)

        val result = repository.resolveEffectiveTarget(
            now = NOW,
            schedule = schedule(),
            zoneId = ZONE,
            targetManagerMode = TargetManagerMode.ACTIVE,
            hardMinTargetMmol = 4.0,
            hardMaxTargetMmol = 10.0,
            gates = trustedRuntimeGates()
        )

        assertThat(result.autoDeltaMmol).isWithin(1e-9).of(0.0)
        assertThat(result.effectiveTargetMmol).isWithin(1e-9).of(5.8)
        assertThat(result.state).isEqualTo(CircadianAutoState.WAITING_DATA)
    }

    @Test
    fun repeatedRunForSameRevisionAndLocalDateReturnsStoredRunWithoutReloadingInputs() = runTest {
        val source = FakeSource()
        val existing = runEntity(runId = "existing", scheduleRevision = 42L)
        source.runsByDate[42L to "2026-07-20"] = existing
        val repository = repository(source = source)

        val returned = repository.evaluateAndPublish(NOW, schedule(), ZONE)

        assertThat(returned).isEqualTo(existing)
        assertThat(source.glucoseReads).isEmpty()
        assertThat(source.therapyReads).isEmpty()
        assertThat(source.telemetryReads).isEmpty()
        assertThat(source.publishCalls).isEmpty()
        assertThat(source.failedRuns).isEmpty()
    }

    @Test
    fun failedRunForSameRevisionAndDateIsRetriedInsteadOfTreatedAsFinal() = runTest {
        val failed = runEntity(runId = "failed", scheduleRevision = 42L).copy(
            status = "FAILED",
            lowRiskPassed = false
        )
        val source = FakeSource().apply {
            runsByDate[42L to "2026-07-20"] = failed
            latestRuns[42L] = failed
        }
        val repository = repository(source = source)

        val returned = repository.evaluateAndPublish(NOW, schedule(), ZONE)

        assertThat(returned.status).isEqualTo(CircadianAutoState.ACTIVE.name)
        assertThat(returned.runId).isNotEqualTo(failed.runId)
        assertThat(source.glucoseReads).containsExactly(Window(LOOKBACK_START, NOW))
        assertThat(source.publishCalls).hasSize(1)
    }

    @Test
    fun malformedPreviousReasonJsonCannotReachEvaluatorAsDeepStepEvidence() = runTest {
        val previousRun = runEntity(
            runId = "previous-malformed",
            scheduleRevision = 42L,
            localRunDate = "2026-07-19"
        )
        val source = FakeSource().apply {
            latestRuns[42L] = previousRun
            visibleAdjustments += previousAdjustmentEntity(previousRun).copy(
                reasonCodesJson = "not-json-prior_low_exposure_not_worse"
            )
        }
        val evaluator = RecordingEvaluator(evaluationResult())
        val repository = repository(source = source, evaluator = evaluator)

        repository.evaluateAndPublish(NOW, schedule(), ZONE)

        assertThat(evaluator.inputs.single().previousAdjustments).isEmpty()
    }

    @Test
    fun duplicatePreviousSlotRowsAreIgnoredFailClosed() = runTest {
        val previousRun = runEntity(
            runId = "previous-duplicate",
            scheduleRevision = 42L,
            localRunDate = "2026-07-19"
        )
        val source = FakeSource().apply {
            latestRuns[42L] = previousRun
            visibleAdjustments += previousAdjustmentEntity(previousRun)
            visibleAdjustments += previousAdjustmentEntity(previousRun).copy(id = "duplicate-id")
        }
        val evaluator = RecordingEvaluator(evaluationResult())
        val repository = repository(source = source, evaluator = evaluator)

        repository.evaluateAndPublish(NOW, schedule(), ZONE)

        assertThat(evaluator.inputs.single().previousAdjustments).isEmpty()
    }

    @Test
    fun persistedBaselineAndCurrentEvidenceProduceRealPriorStepComparison() = runTest {
        val previousRun = runEntity(
            runId = "previous-evidence",
            scheduleRevision = 42L,
            localRunDate = "2026-07-19"
        )
        val source = FakeSource().apply {
            latestRuns[42L] = previousRun
            visibleAdjustments += previousAdjustmentEntity(previousRun).copy(
                appliedDeltaMmol = -0.3,
                p25 = 6.0,
                p75 = 7.0,
                trustedLowCount = 0,
                lowerTailReplayErrorMmol = 0.2,
                stepBaselineTrustedLowCount = 0,
                stepBaselineVariabilityIqrMmol = 1.1,
                stepBaselineLowerTailReplayErrorMmol = 0.3,
                reasonCodesJson = "[\"unchanged_successful_runs=3\"]"
            )
        }
        val evaluator = RecordingEvaluator(evaluationResult())
        val repository = repository(source = source, evaluator = evaluator)

        repository.evaluateAndPublish(NOW, schedule(), ZONE)

        assertThat(evaluator.inputs.single().previousAdjustments)
            .containsEntry(
                CircadianTargetSlot(CircadianDayType.ALL, 10),
                CircadianPreviousAdjustment(
                    appliedDeltaMmol = -0.3,
                    unchangedSuccessfulRuns = 3,
                    priorStepComparison = CircadianPriorStepComparison(
                        lowExposureNotWorse = true,
                        variabilityNotWorse = true,
                        lowerTailReplayErrorNotWorse = true
                    ),
                    stepBaselineEvidence = CircadianStepEvidence(
                        trustedLowCount = 0,
                        variabilityIqrMmol = 1.1,
                        lowerTailReplayErrorMmol = 0.3
                    )
                )
            )
    }

    @Test
    fun worsenedPersistedLowerTailErrorCannotProducePassingComparison() = runTest {
        val previousRun = runEntity(
            runId = "previous-worse-tail",
            scheduleRevision = 42L,
            localRunDate = "2026-07-19"
        )
        val source = FakeSource().apply {
            latestRuns[42L] = previousRun
            visibleAdjustments += previousAdjustmentEntity(previousRun).copy(
                appliedDeltaMmol = -0.4,
                p25 = 6.0,
                p75 = 7.0,
                trustedLowCount = 0,
                lowerTailReplayErrorMmol = 0.4,
                stepBaselineTrustedLowCount = 0,
                stepBaselineVariabilityIqrMmol = 1.0,
                stepBaselineLowerTailReplayErrorMmol = 0.3,
                reasonCodesJson = "[\"unchanged_successful_runs=3\"]"
            )
        }
        val evaluator = RecordingEvaluator(evaluationResult())

        repository(source = source, evaluator = evaluator)
            .evaluateAndPublish(NOW, schedule(), ZONE)

        val previous = evaluator.inputs.single().previousAdjustments
            .getValue(CircadianTargetSlot(CircadianDayType.ALL, 10))
        assertThat(previous.priorStepComparison?.lowExposureNotWorse).isTrue()
        assertThat(previous.priorStepComparison?.variabilityNotWorse).isTrue()
        assertThat(previous.priorStepComparison?.lowerTailReplayErrorNotWorse).isFalse()
    }

    @Test
    fun missingLegacyBaselineIsCapturedAndResetsDeepStepDwell() = runTest {
        val previousRun = runEntity(
            runId = "previous-legacy",
            scheduleRevision = 42L,
            localRunDate = "2026-07-19"
        )
        val source = FakeSource().apply {
            latestRuns[42L] = previousRun
            visibleAdjustments += previousAdjustmentEntity(previousRun).copy(
                appliedDeltaMmol = -0.3,
                reasonCodesJson = "[\"unchanged_successful_runs=20\"]"
            )
        }
        val repository = repository(
            source = source,
            evaluator = RecordingEvaluator(
                evaluationResult(
                    adjustments = listOf(
                        adjustment(
                            dayType = CircadianDayType.ALL,
                            hour = 10,
                            delta = -0.3,
                            lowerTailReplayErrorMmol = 0.25
                        )
                    )
                )
            )
        )

        repository.evaluateAndPublish(NOW, schedule(), ZONE)

        val stored = source.publishCalls.single().adjustments.single()
        assertThat(stored.stepBaselineTrustedLowCount).isEqualTo(0)
        assertThat(stored.stepBaselineVariabilityIqrMmol).isWithin(1e-9).of(1.2)
        assertThat(stored.stepBaselineLowerTailReplayErrorMmol).isWithin(1e-9).of(0.25)
        assertThat(decodeReasons(stored.reasonCodesJson))
            .contains("unchanged_successful_runs=1")
    }

    private fun repository(
        source: FakeSource,
        calibration: CircadianGlucoseHistoryResolver = RecordingCalibration(emptyList()),
        cohortAssembler: CircadianCohortAssembler = RecordingCohortAssembler(
            CircadianTargetCohorts(emptyList(), emptyList(), emptySet(), emptyMap())
        ),
        evaluator: CircadianEvaluationEngine = RecordingEvaluator(evaluationResult())
    ): CircadianTargetRepository = CircadianTargetRepository(
        source = source,
        glucoseResolver = calibration,
        cohortAssembler = cohortAssembler,
        evaluationEngine = evaluator,
        gson = Gson(),
        runIdFactory = { "run-fixed" }
    )

    private class FakeSource(
        val glucoseRows: List<GlucoseSampleEntity> = listOf(
            GlucoseSampleEntity(
                id = 1L,
                timestamp = NOW,
                mmol = 6.5,
                source = "raw",
                quality = "OK"
            )
        ),
        private val therapyRows: List<TherapyEventEntity> = listOf(
            TherapyEventEntity(
                id = "carbs-1",
                timestamp = NOW - 60_000L,
                type = "carbs",
                payloadJson = "{\"grams\":\"15\"}"
            )
        ),
        private val forecastRows: List<ForecastEntity> = emptyList(),
        private val telemetryRows: List<TelemetrySampleLite> = emptyList()
    ) : CircadianTargetRepositorySource {

        val glucoseReads = mutableListOf<Window>()
        val therapyReads = mutableListOf<Window>()
        val forecastReads = mutableListOf<Window>()
        val telemetryReads = mutableListOf<TelemetryRead>()
        val publishCalls = mutableListOf<Publication>()
        val failedRuns = mutableListOf<CircadianTargetRunEntity>()
        val visibleAdjustments = mutableListOf<CircadianTargetAdjustmentEntity>()
        val cleanupCutoffs = mutableListOf<Long>()
        val latestRuns = mutableMapOf<Long, CircadianTargetRunEntity>()
        val latestRunReads = mutableListOf<Long>()
        val runsByDate = mutableMapOf<Pair<Long, String>, CircadianTargetRunEntity>()
        val runForDateReads = mutableListOf<Pair<Long, String>>()
        val adjustmentReads = mutableListOf<String>()
        val latestDates = mutableMapOf<Long, String>()
        val latestDateReads = mutableListOf<Long>()
        var glucoseFailure: Throwable? = null
        var latestRunFailure: Throwable? = null
        var publishFailure: Throwable? = null
        var runForDateFailureCall: Int? = null
        var runForDateFailure: Throwable? = null

        override suspend fun glucoseBetween(
            since: Long,
            through: Long
        ): List<GlucoseSampleEntity> {
            glucoseReads += Window(since, through)
            glucoseFailure?.let { throw it }
            return glucoseRows
        }

        override suspend fun therapyBetween(
            since: Long,
            through: Long
        ): List<TherapyEventEntity> {
            therapyReads += Window(since, through)
            return therapyRows
        }

        override suspend fun forecastsBetween(
            since: Long,
            through: Long
        ): List<ForecastEntity> {
            forecastReads += Window(since, through)
            return forecastRows
        }

        override suspend fun telemetryBetweenByKeysPage(
            since: Long,
            through: Long,
            keys: List<String>,
            afterTimestamp: Long,
            afterId: String,
            limit: Int
        ): List<TelemetrySampleLite> {
            telemetryReads += TelemetryRead(since, through, keys, afterTimestamp, afterId, limit)
            return telemetryRows.asSequence()
                .filter { row ->
                    row.timestamp > afterTimestamp ||
                        (row.timestamp == afterTimestamp && row.id > afterId)
                }
                .take(limit)
                .toList()
        }

        override suspend fun publishRun(
            run: CircadianTargetRunEntity,
            adjustments: List<CircadianTargetAdjustmentEntity>
        ) {
            publishFailure?.let { throw it }
            publishCalls += Publication(run, adjustments)
            visibleAdjustments += adjustments
            latestRuns[run.scheduleRevision] = run
            runsByDate[run.scheduleRevision to run.localRunDate] = run
        }

        override suspend fun insertFailedRun(run: CircadianTargetRunEntity) {
            failedRuns += run
            runsByDate[run.scheduleRevision to run.localRunDate] = run
        }

        override suspend fun latestCompletedRun(scheduleRevision: Long): CircadianTargetRunEntity? {
            latestRunReads += scheduleRevision
            latestRunFailure?.let { throw it }
            return latestRuns[scheduleRevision]
        }

        override suspend fun runForLocalDate(
            scheduleRevision: Long,
            localRunDate: String
        ): CircadianTargetRunEntity? {
            val key = scheduleRevision to localRunDate
            runForDateReads += key
            if (runForDateReads.size == runForDateFailureCall) {
                runForDateFailure?.let { throw it }
            }
            return runsByDate[key]
        }

        override suspend fun adjustmentsForRun(runId: String): List<CircadianTargetAdjustmentEntity> {
            adjustmentReads += runId
            return visibleAdjustments.filter { it.runId == runId }
        }

        override suspend fun latestCompletedLocalRunDate(scheduleRevision: Long): String? {
            latestDateReads += scheduleRevision
            return latestDates[scheduleRevision]
        }

        override suspend fun deleteRunsCompletedBefore(cutoff: Long) {
            cleanupCutoffs += cutoff
        }
    }

    private class RecordingCalibration(
        private val result: List<GlucosePoint>
    ) : CircadianGlucoseHistoryResolver {
        val calls = mutableListOf<CalibrationCall>()

        override suspend fun resolve(
            rawGlucose: List<GlucoseSampleEntity>,
            now: Long
        ): List<GlucosePoint> {
            calls += CalibrationCall(rawGlucose, now)
            return result
        }
    }

    private class RecordingCohortAssembler(
        private val result: CircadianTargetCohorts
    ) : CircadianCohortAssembler {
        val calls = mutableListOf<CohortCall>()

        override fun build(
            zoneId: ZoneId,
            canonicalGlucose: List<GlucosePoint>,
            telemetry: List<io.aaps.copilot.domain.target.CircadianTelemetrySignal>,
            therapyEvents: List<TherapyEvent>
        ): CircadianTargetCohorts {
            calls += CohortCall(zoneId, canonicalGlucose, telemetry, therapyEvents)
            return result
        }
    }

    private class RecordingEvaluator(
        private val result: CircadianTargetEvaluationResult
    ) : CircadianEvaluationEngine {
        val inputs = mutableListOf<CircadianTargetEvaluationInput>()

        override fun evaluate(input: CircadianTargetEvaluationInput): CircadianTargetEvaluationResult {
            inputs += input
            return result
        }
    }

    private data class Window(val since: Long, val through: Long)

    private data class TelemetryRead(
        val since: Long,
        val through: Long,
        val keys: List<String>,
        val afterTimestamp: Long,
        val afterId: String,
        val limit: Int
    )

    private data class Publication(
        val run: CircadianTargetRunEntity,
        val adjustments: List<CircadianTargetAdjustmentEntity>
    )

    private data class CalibrationCall(
        val rawGlucose: List<GlucoseSampleEntity>,
        val now: Long
    )

    private data class CohortCall(
        val zoneId: ZoneId,
        val canonicalGlucose: List<GlucosePoint>,
        val telemetry: List<io.aaps.copilot.domain.target.CircadianTelemetrySignal>,
        val therapyEvents: List<TherapyEvent>
    )

    private fun schedule(): BaseTargetSchedule = BaseTargetSchedule(
        revision = 42L,
        defaultTargetMmol = 6.1,
        autoEnabled = true,
        intervals = listOf(
            BaseTargetInterval(
                id = "morning",
                startMinuteOfDay = 9 * 60,
                endMinuteOfDay = 11 * 60,
                targetMmol = 5.8
            )
        )
    )

    private fun evaluationResult(
        adjustments: List<CircadianTargetAdjustment> = emptyList(),
        reasons: List<String> = emptyList()
    ): CircadianTargetEvaluationResult = CircadianTargetEvaluationResult(
        state = CircadianAutoState.ACTIVE,
        adjustments = adjustments,
        validDays = 10,
        sensorTrustedShare = 0.96,
        lowRiskPassed = true,
        reasonCodes = reasons
    )

    private fun adjustment(
        dayType: CircadianDayType,
        hour: Int,
        delta: Double,
        reasons: List<String> = emptyList(),
        lowerTailReplayErrorMmol: Double? = null
    ): CircadianTargetAdjustment = CircadianTargetAdjustment(
        requestedDayType = dayType,
        sourceDayType = dayType,
        hour = hour,
        desiredDeltaMmol = delta,
        appliedDeltaMmol = delta,
        medianMmol = 6.7,
        p25 = 5.9,
        p75 = 7.1,
        trustedLowCount = 0,
        sampleCount = 48,
        activeDays = 10,
        qualityScore = 0.91,
        sensorTrustedShare = 0.96,
        status = CircadianAutoState.ACTIVE,
        reasonCodes = reasons,
        lowerTailReplayErrorMmol = lowerTailReplayErrorMmol
    )

    private fun telemetryRows(count: Int): List<TelemetrySampleLite> = List(count) { index ->
        TelemetrySampleLite(
            id = "row-${index.toString().padStart(5, '0')}",
            timestamp = NOW - 1_000L,
            source = "test",
            key = "sensor_quality_score",
            valueDouble = 0.95,
            valueText = null,
            unit = null,
            quality = "OK"
        )
    }

    private fun runEntity(
        runId: String,
        scheduleRevision: Long,
        localRunDate: String = "2026-07-20"
    ): CircadianTargetRunEntity = CircadianTargetRunEntity(
        runId = runId,
        scheduleRevision = scheduleRevision,
        localRunDate = localRunDate,
        startedAt = NOW,
        completedAt = NOW,
        lookbackStart = LOOKBACK_START,
        lookbackEnd = NOW,
        status = CircadianAutoState.ACTIVE.name,
        validDays = 10,
        trustedShare = 0.96,
        lowRiskPassed = true,
        reasonCodesJson = "[]"
    )

    private fun previousAdjustmentEntity(
        run: CircadianTargetRunEntity
    ): CircadianTargetAdjustmentEntity = CircadianTargetAdjustmentEntity(
        id = "${run.runId}:ALL:10",
        runId = run.runId,
        scheduleRevision = run.scheduleRevision,
        dayType = CircadianDayType.ALL.name,
        hour = 10,
        manualTargetMmol = 5.8,
        desiredDeltaMmol = -0.2,
        appliedDeltaMmol = -0.2,
        medianMmol = 7.4,
        p25 = 6.4,
        p75 = 8.0,
        trustedLowCount = 0,
        sampleCount = 48,
        activeDays = 10,
        qualityScore = 0.9,
        sensorTrustedShare = 0.95,
        generatedAt = run.completedAt,
        validUntil = run.completedAt + DAY_MS,
        status = CircadianAutoState.ACTIVE.name,
        reasonCodesJson = "[\"unchanged_successful_runs=2\"]"
    )

    private fun decodeReasons(json: String): List<String> =
        Gson().fromJson(json, Array<String>::class.java).toList()

    private fun trustedRuntimeGates(): EffectiveTargetRuntimeGates = EffectiveTargetRuntimeGates(
        sensorTrust = SensorTrustState.TRUSTED,
        sensorAgeHours = 120.0,
        lowRiskLatched = false,
        forecast5CiLowMmol = 4.8,
        forecast30CiLowMmol = 4.7,
        safetyIobUnits = 1.0,
        effectiveCobGrams = 0.0,
        uamActive = false
    )

    private companion object {
        val ZONE: ZoneId = ZoneOffset.UTC
        val NOW: Long = Instant.parse("2026-07-20T09:00:00Z").toEpochMilli()
        const val DAY_MS = 24L * 60L * 60L * 1_000L
        val LOOKBACK_START: Long = NOW - 14L * DAY_MS
        val RETENTION_CUTOFF: Long = NOW - 90L * DAY_MS

        val EXPECTED_TELEMETRY_KEYS = setOf(
            "cob_effective_grams",
            "cob_grams",
            "iob_units",
            "uam_runtime_control_flag",
            "uam_runtime_flag",
            "sensor_quality_score",
            "sensor_quality_blocked",
            "sensor_quality_suspect_false_low",
            "sensor_age_hours",
            "sensor_age_days",
            "target_low_risk_active",
            "target_low_risk_latched",
            "cage_days",
            "isf_factor_set_age_hours"
        )
    }
}
