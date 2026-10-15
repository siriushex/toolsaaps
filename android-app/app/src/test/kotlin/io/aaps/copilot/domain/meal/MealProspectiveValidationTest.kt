package io.aaps.copilot.domain.meal

import com.google.gson.Gson
import io.aaps.copilot.domain.model.DataQuality
import org.junit.Assert.*
import org.junit.Test

class MealProspectiveValidationTest {
    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val model = MealProspectiveModel("personal-v1", "evidence-a", now - 2 * minute, now - minute)

    private fun identity(anchor: Long = now) = MealForecastIdentity(anchor, "cycle-$anchor", 7,
        "accepted-$anchor", "sensor", "session-a", "calibrated-basis-a", "calibration-a",
        MealForecastBasis.ACCEPTED_CONTROL, "Asia/Tbilisi")

    private fun values(anchor: Long = now, mean: Double = 6.0, intervals: Boolean = true) =
        listOf(5, 30, 60).map { MealProspectiveValue(it, anchor + it * minute, mean,
            if (intervals) mean - 0.5 else null, if (intervals) mean + 0.5 else null) }

    private fun candidate(id: MealForecastIdentity = identity(), mean: Double = 5.5,
        intervals: Boolean = true, stamp: MealProspectiveModel = model,
        through: Long = id.predictionAtMs, available: Long = id.predictionAtMs + 10,
        started: Long = id.predictionAtMs + 20, completed: Long = id.predictionAtMs + 30,
        points: List<MealProspectiveValue> = values(id.predictionAtMs, mean, intervals)) =
        MealProspectiveCandidate(id, stamp, through, available, started, completed, points)

    private fun registration(id: MealForecastIdentity = identity(), base: Double = 6.0,
        cand: MealProspectiveCandidate? = candidate(id), at: Long = id.predictionAtMs + 40,
        baseline: List<MealProspectiveValue> = values(id.predictionAtMs, base)) =
        MealProspectiveFrame.register(id, id.predictionAtMs + 10, baseline, cand, at)

    private fun frame(id: MealForecastIdentity = identity(), base: Double = 6.0,
        cand: MealProspectiveCandidate? = candidate(id)) = requireNotNull(registration(id, base, cand).frame)

    private fun outcomes(id: MealForecastIdentity = identity(), actual: Double = 5.3) =
        listOf(5, 30, 60).map { MealProspectiveOutcome(id.predictionAtMs + it * minute,
            id.predictionAtMs + it * minute + 100, actual, DataQuality.OK, id.source,
            id.sensorSessionKey, id.glucoseBasisId, id.calibrationModelId) }

    private fun evaluate(frames: List<MealProspectiveFrame> = listOf(frame()),
        samples: List<MealProspectiveOutcome> = outcomes(), cutoff: Long = now + 61 * minute) =
        MealProspectiveEvaluator().evaluate(frames, samples, cutoff, 3.9)

    @Test fun baselineOnlyScoresAreSeparateAndNeverPretendToBeCandidatePairs() {
        val result = evaluate(frames = listOf(frame(cand = null)), samples = outcomes(actual = 3.5))
        assertTrue(result.horizons.isEmpty())
        assertEquals(3, result.baselineOnlyHorizons.size)
        result.baselineOnlyHorizons.forEach {
            assertEquals(1, it.observedSamples)
            assertEquals(0, it.pairedSamples)
            assertEquals(2.5, it.baseline!!.maeMmol, 1e-9)
            assertEquals(1, it.baselineMissedLows)
            assertEquals(1, it.actualLowSamples)
            assertEquals(1, it.baselineObservedIntervalSamples)
            assertEquals(0.0, it.baselineIntervalCoverage!!, 0.0)
            assertNull(it.candidate)
            assertNull(it.cohort.model)
        }
        assertFalse(result.clinicalInfluenceAllowed)
    }

    @Test fun baselineOnlyUsesTheSameStrictOutcomeAndConflictRules() {
        val baseline = frame(cand = null)
        val samples = outcomes().map { it.copy(availableAtMs = null) }
        assertTrue(evaluate(listOf(baseline), samples).baselineOnlyHorizons.all { it.rejectedOutcome == 1 && it.baseline == null })
        val conflict = evaluate(listOf(baseline, frame(base = 7.0, cand = null)))
        assertEquals(2, conflict.conflictingFrames)
        assertTrue(conflict.baselineOnlyHorizons.all { it.observedSamples == 0 && it.baseline == null })
    }

    @Test fun baselineModelVersionsDoNotPoolAccuracy() {
        val other = identity().copy(baselineModelVersion = "new-model")
        val result = evaluate(listOf(frame(cand = null), frame(other, cand = null)))
        assertEquals(6, result.baselineOnlyHorizons.size)
        assertTrue(result.baselineOnlyHorizons.all { it.observedSamples == 1 })
    }

    @Test fun registrationMustFinishBeforeFirstOutcomeAndAfterBothCalculations() {
        assertEquals(MealProspectiveRegistrationStatus.PAIRED, registration(at = now + 5 * minute - 1).status)
        assertEquals(MealProspectiveRegistrationStatus.LATE_REGISTRATION, registration(at = now + 5 * minute).status)
        assertEquals(MealProspectiveRegistrationStatus.LATE_REGISTRATION, registration(at = now + 9).status)
        assertEquals(MealProspectiveRegistrationStatus.INVALID_CANDIDATE,
            registration(cand = candidate(completed = now + 41)).status)
        assertEquals(MealProspectiveRegistrationStatus.INVALID_CANDIDATE,
            registration(cand = candidate(started = now + 9)).status)
    }

    @Test fun futureTrainingOrInputsCannotBePresentedAsProspective() {
        assertEquals(MealProspectiveRegistrationStatus.FUTURE_MODEL,
            registration(cand = candidate(stamp = model.copy(trainingThroughMs = now + 1, availableAtMs = now + 2))).status)
        assertEquals(MealProspectiveRegistrationStatus.FUTURE_MODEL,
            registration(cand = candidate(stamp = model.copy(availableAtMs = now + 21))).status)
        assertEquals(MealProspectiveRegistrationStatus.FUTURE_INPUT,
            registration(cand = candidate(through = now + 1)).status)
        assertEquals(MealProspectiveRegistrationStatus.FUTURE_INPUT,
            registration(cand = candidate(available = now + 21)).status)
    }

    @Test fun candidateMustUseExactCycleCalibrationRevisionAndComparisonBasis() {
        val id = identity()
        for (changed in listOf(id.copy(forecastCycleId = "other"), id.copy(settingsRevision = 8),
            id.copy(acceptedForecastDigest = "other"), id.copy(calibrationModelId = "other"),
            id.copy(sensorSessionKey = "other"), id.copy(glucoseBasisId = "other"),
            id.copy(basis = MealForecastBasis.LOCAL_ENGINE), id.copy(source = "other"))) {
            assertEquals(MealProspectiveRegistrationStatus.CONTEXT_MISMATCH,
                registration(cand = candidate(changed, points = values())).status)
        }
    }

    @Test fun incompleteInvalidAndMislabeledPredictionsAreRejected() {
        for (points in listOf(values().dropLast(1), values().map { it.copy(timestampMs = it.timestampMs + 1) },
            values().map { it.copy(mmol = Double.NaN) }, values().map { it.copy(lowerMmol = 7.0) },
            values().map { it.copy(upperMmol = null) })) {
            assertEquals(MealProspectiveRegistrationStatus.INVALID_BASELINE, registration(baseline = points).status)
            assertEquals(MealProspectiveRegistrationStatus.INVALID_CANDIDATE, registration(cand = candidate(points = points)).status)
        }
        assertEquals(MealProspectiveRegistrationStatus.INVALID_BASELINE,
            registration(id = identity().copy(sensorSessionKey = "")).status)
        assertEquals(MealProspectiveRegistrationStatus.INVALID_BASELINE,
            registration(id = identity().copy(recordedZoneId = "not-a-zone")).status)
    }

    @Test fun predictionCollectionsAreFrozenAgainstLaterMutation() {
        val base = values().toMutableList()
        val proposed = values(mean = 5.5).toMutableList()
        val result = registration(baseline = base, cand = candidate(points = proposed)).frame!!
        base.clear(); proposed.clear()
        assertEquals(3, result.baseline.size)
        assertEquals(3, result.candidate!!.values.size)
        assertThrows(UnsupportedOperationException::class.java) { (result.baseline as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.candidate.values as MutableList).clear() }
    }

    @Test fun bothModelsAreScoredAgainstIdenticalExactFutureSamples() {
        val report = evaluate()
        assertEquals(3, report.horizons.size)
        for (result in report.horizons) {
            assertEquals(1, result.pairedSamples)
            assertEquals(1, result.observedDays)
            assertEquals(0.7, result.baseline!!.maeMmol, 1e-9)
            assertEquals(0.2, result.candidate!!.maeMmol, 1e-9)
            assertEquals(0.7, result.baseline.biasMmol, 1e-9)
            assertEquals(0.7, result.baseline.maximumOverpredictionMmol, 1e-9)
            assertEquals(0.0, result.baselineIntervalCoverage!!, 1e-9)
            assertEquals(1.0, result.candidateIntervalCoverage!!, 1e-9)
        }
        assertFalse(report.clinicalInfluenceAllowed)
        assertFalse(report.controlSafetyValidated)
    }

    @Test fun futureOutcomeAndDelayedAvailabilityAreNotScoredEarly() {
        val pending = evaluate(cutoff = now + 4 * minute)
        assertTrue(pending.horizons.all { it.pending == 1 && it.baseline == null })
        val waiting = evaluate(cutoff = now + 5 * minute)
        assertEquals(1, waiting.horizons.first().missingOutcome)
        assertNull(waiting.horizons.first().baseline)
        assertEquals(1, evaluate(cutoff = now + 5 * minute + 100).horizons.first().pairedSamples)
    }

    @Test fun nearbySamplesDoNotInventAnExactOutcome() {
        val result = evaluate(samples = outcomes().map { it.copy(timestampMs = it.timestampMs - 1) })
        assertTrue(result.horizons.all { it.missingOutcome == 1 && it.baseline == null })
    }

    @Test fun unknownAvailabilityAndInvalidSamplesAreExplicitlyRejected() {
        for (samples in listOf(outcomes().map { it.copy(availableAtMs = null) },
            outcomes().map { it.copy(availableAtMs = it.timestampMs - 1) },
            outcomes().map { it.copy(quality = DataQuality.SENSOR_ERROR) },
            outcomes().map { it.copy(mmol = Double.NaN) }, outcomes().map { it.copy(mmol = 0.0) })) {
            assertTrue(evaluate(samples = samples).horizons.all { it.rejectedOutcome == 1 && it.baseline == null })
        }
    }

    @Test fun sensorCalibrationAndGlucoseBasisChangesCannotBeMixed() {
        for (samples in listOf(outcomes().map { it.copy(sensorSessionKey = "other") },
            outcomes().map { it.copy(calibrationModelId = "other") },
            outcomes().map { it.copy(glucoseBasisId = "other") }, outcomes().map { it.copy(source = "other") })) {
            assertTrue(evaluate(samples = samples).horizons.all { it.contextMismatch == 1 && it.baseline == null })
        }
    }

    @Test fun conflictingValueOrQualityDoesNotSelectAFavorableOutcome() {
        for (extra in listOf(outcomes(actual = 7.0), outcomes().map { it.copy(quality = DataQuality.SENSOR_ERROR) })) {
            assertTrue(evaluate(samples = outcomes() + extra).horizons.all { it.conflictingOutcome == 1 && it.baseline == null })
        }
        assertTrue(evaluate(samples = outcomes() + outcomes()).horizons.all { it.pairedSamples == 1 })
    }

    @Test fun lowOutcomesAreRetainedAndMissedLowsAreCounted() {
        val result = evaluate(frames = listOf(frame(cand = candidate(mean = 3.5))), samples = outcomes(actual = 3.5))
        assertTrue(result.horizons.all { it.actualLowSamples == 1 && it.baselineMissedLows == 1 &&
            it.candidateMissedLows == 0 && it.candidate!!.maeMmol == 0.0 &&
            it.pairedLowerBoundLowSamples == 1 && it.baselineLowerBoundMissedLows == 1 && it.candidateLowerBoundMissedLows == 0 })
    }

    @Test fun missingCandidateAndMissingIntervalsDoNotBecomeZeroErrorsOrCertainty() {
        val missing = evaluate(frames = listOf(frame(cand = null)))
        assertEquals(1, missing.baselineOnlyFrames)
        assertTrue(missing.horizons.isEmpty())
        val meanOnly = evaluate(frames = listOf(frame(cand = candidate(intervals = false))))
        assertTrue(meanOnly.horizons.all { it.pairedSamples == 1 && it.pairedIntervalSamples == 0 &&
            it.baselineIntervalCoverage == null && it.candidateIntervalCoverage == null })
    }

    @Test fun repeatedAndConflictingGenerationsCannotVoteOrChooseBestPrediction() {
        val first = frame()
        val repeated = evaluate(frames = listOf(first, first))
        assertEquals(1, repeated.duplicateFrames)
        assertTrue(repeated.horizons.all { it.pairedSamples == 1 })
        val secondId = identity().copy(forecastCycleId = "another-cycle")
        val conflict = evaluate(frames = listOf(first, frame(secondId)))
        assertEquals(2, conflict.conflictingFrames)
        assertTrue(conflict.horizons.all { it.pairedSamples == 0 && it.baseline == null })
    }

    @Test fun metadataOrderingDoesNotTurnIdenticalPredictionsIntoConflicts() {
        val first = frame()
        val shuffled = registration(baseline = values().reversed(), cand = candidate(points = values(mean = 5.5).reversed())).frame!!
        assertEquals(1, evaluate(frames = listOf(first, shuffled)).duplicateFrames)
    }

    @Test fun differentModelsAndRuntimeCohortsRemainSeparate() {
        val otherId = identity().copy(settingsRevision = 8)
        val otherModel = model.copy(evidenceDigest = "evidence-b")
        val result = evaluate(frames = listOf(frame(), frame(otherId), frame(cand = candidate(stamp = otherModel))))
        assertEquals(9, result.horizons.size)
        assertTrue(result.horizons.all { it.pairedSamples == 1 })
    }

    @Test fun dayBalancedErrorsDoNotLetFrequentCyclesDominate() {
        val later = now + 24 * 60 * minute
        val ids = listOf(identity(), identity(now + 5 * minute), identity(later))
        val frames = ids.map { frame(it, base = 6.0, cand = candidate(it, mean = 5.0)) }
        val samples = ids.flatMap { outcomes(it, actual = if (it.predictionAtMs < later) 5.0 else 3.0) }
        val result = evaluate(frames, samples, later + 61 * minute)
        for (horizon in result.horizons) {
            assertEquals(3, horizon.pairedSamples)
            assertEquals(2, horizon.observedDays)
            assertEquals(2.0, horizon.baseline!!.maeMmol, 1e-9)
            assertEquals(1.0, horizon.candidate!!.maeMmol, 1e-9)
            assertEquals(2.0, horizon.candidate.maximumOverpredictionMmol, 1e-9)
        }
    }

    @Test fun overflowDoesNotSilentlyScoreATruncatedDataset() {
        val first = frame()
        assertEquals(MealProspectiveEvaluationStatus.OVERFLOW, evaluate(frames = List(7_001) { first }).status)
        assertEquals(MealProspectiveEvaluationStatus.OVERFLOW, evaluate(samples = List(24_001) { outcomes().first() }).status)
        assertTrue(evaluate(frames = List(7_001) { first }).horizons.isEmpty())
    }

    @Test fun receiptsAfterEvaluationCutoffAreNotAvailableEvidence() {
        val result = evaluate(cutoff = now + 39)
        assertEquals(1, result.unregisteredFutureFrames)
        assertTrue(result.horizons.isEmpty())
    }

    @Test fun importedReceiptCannotOmitItsLocalOrControlComparisonBasis() {
        val gson = Gson()
        val objectValue = gson.toJsonTree(identity()).asJsonObject
        objectValue.remove("basis")
        val imported = gson.fromJson(objectValue, MealForecastIdentity::class.java)
        assertEquals(MealProspectiveRegistrationStatus.INVALID_BASELINE, registration(id = imported, cand = null).status)
    }
}
