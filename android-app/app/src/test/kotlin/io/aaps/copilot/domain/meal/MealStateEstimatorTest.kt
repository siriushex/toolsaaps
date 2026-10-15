package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.*
import org.junit.Test

class MealStateEstimatorTest {
    private val estimator = MealStateEstimator()
    private val enteredAt = 10_000_000L
    private fun scenarios() = MealHypothesisKind.entries.map { kind ->
        MealScenario(
            kind.name, kind,
            if (kind == MealHypothesisKind.NOT_HAPPENING || kind == MealHypothesisKind.NO_NEW_MEAL) null
            else MealStartInterval(enteredAt - 600_000L, enteredAt + 600_000L),
            MealCarbRange(15.0, 40.0),
            listOf(MealAbsorptionAlternative(MealAbsorptionProfile.MIXED, 120, 1.0)),
            1.0
        )
    }
    private fun belief() = estimator.initialize(MealInput("meal-1", enteredAt, MealCarbRange(15.0, 40.0)), scenarios())
    private fun evidence(b: MealBelief, ts: Long = enteredAt + 300_000L) = MealObservation(
        "sample-$ts", ts, 7.0, enteredAt, "runtime-1", b.revision,
        b.scenarios.associate { it.id to MealExpectedObservation(7.0, 0.4) }, true
    )

    @Test fun allSixHypothesesRemainNormalized() {
        val b = belief()
        assertEquals(MealHypothesisKind.entries.toSet(), b.stageProbabilities.keys)
        assertEquals(1.0, b.stageProbabilities.values.sum(), 1e-12)
        assertEquals(6, b.scenarios.size)
    }
    @Test fun predictionSupportsHypothesisWithoutChangingOriginalTreatmentTime() {
        val b = belief()
        val e = evidence(b)
        val likelihoods = e.expected.toMutableMap()
        likelihoods[MealHypothesisKind.UPCOMING.name] = MealExpectedObservation(10.0, 0.4)
        val result = estimator.observe(b, e.copy(expected = likelihoods))
        assertEquals(MealUpdateReason.UPDATED, result.reason)
        assertTrue(result.belief.stageProbabilities.getValue(MealHypothesisKind.UPCOMING) < 1.0 / 6)
        assertEquals(b.input, result.belief.input)
        assertEquals(enteredAt, result.belief.input.recordedAtMs)
    }
    @Test fun repeatedAndCorrelatedSamplesCannotMultiplyConfidence() {
        val b = belief()
        val e = evidence(b)
        val updated = estimator.observe(b, e).belief
        assertEquals(MealUpdateReason.DUPLICATE_OR_OLD, estimator.observe(updated, e).reason)
        val tooSoon = e.copy(sampleId = "another", sampleAtMs = e.sampleAtMs + 60_000L,
            basedOnRevision = updated.revision, predictedAtMs = e.sampleAtMs)
        assertEquals(MealUpdateReason.CORRELATED_SAMPLE, estimator.observe(updated, tooSoon).reason)
    }
    @Test fun futureOrSameSamplePredictionIsNotIndependentEvidence() {
        val b = belief()
        val e = evidence(b)
        for (asOf in listOf(e.sampleAtMs, e.sampleAtMs + 1)) {
            val result = estimator.observe(b, e.copy(predictedAtMs = asOf))
            assertEquals(MealUpdateReason.NON_CAUSAL, result.reason)
            assertSame(b, result.belief)
        }
    }
    @Test fun incompleteInvalidOrUntrustedEvidenceDoesNotCreateConfidence() {
        val b = belief()
        val e = evidence(b)
        for (invalid in listOf(e.copy(glucoseMmol = Double.NaN), e.copy(qualityTrusted = false),
            e.copy(expected = emptyMap()), e.copy(basedOnRevision = 99))) {
            assertSame(b, estimator.observe(b, invalid).belief)
        }
    }
    @Test fun sourceSwitchNeedsFreshPredictionInsteadOfMultiplyingDifferentModels() {
        val first = estimator.observe(belief(), evidence(belief())).belief
        val e = evidence(first, enteredAt + 600_000L).copy(predictedAtMs = enteredAt + 300_000L,
            runtimeIdentity = "runtime-2")
        assertEquals(MealUpdateReason.MODEL_CHANGED, estimator.observe(first, e).reason)
    }
    @Test fun extremeFiniteResidualDoesNotUnderflowPosterior() {
        val b = belief()
        val result = estimator.observe(b, evidence(b).copy(glucoseMmol = Double.MAX_VALUE))
        assertTrue(result.belief.scenarios.all { it.probability.isFinite() && it.probability > 0.0 })
        assertEquals(1.0, result.belief.scenarios.sumOf { it.probability }, 1e-12)
    }
    @Test fun snapshotsDoNotRetainMutableInputLists() {
        val supplied = scenarios().toMutableList()
        val b = estimator.initialize(MealInput("meal-1", enteredAt, MealCarbRange(15.0, 40.0)), supplied)
        supplied.clear()
        assertEquals(6, b.scenarios.size)
    }
    @Test(expected = IllegalArgumentException::class)
    fun missingHypothesesCannotBeSilentlyDropped() {
        estimator.initialize(MealInput("meal-1", enteredAt, MealCarbRange(15.0, 40.0)), scenarios().take(1))
    }
}
