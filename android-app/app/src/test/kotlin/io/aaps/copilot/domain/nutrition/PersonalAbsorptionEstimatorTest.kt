package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Instant
import kotlin.math.pow
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonalAbsorptionEstimatorTest {
    private val cutoff = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()
    private val request = PersonalAbsorptionRequest(cutoff, MealAbsorptionProfile.MIXED,
        MealPortion.MEDIUM, "UTC", 9 * 60)
    private val estimator = PersonalAbsorptionEstimator()

    private fun input(day: Int, duration: Int = 150, exponent: Double = 1.15,
        isf: Double = 3.0): PersonalAbsorptionInput {
        val mealTs = Instant.parse("2026-10-12T09:00:00Z").minusSeconds(day * 86400L).toEpochMilli()
        val meal = MealPortionObservation("meal-$day", Instant.ofEpochMilli(mealTs), 35.0,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_ENTERED, MealPortion.MEDIUM,
            availableAt = Instant.ofEpochMilli(mealTs + 1000), therapyRevision = "r1")
        val curve = MealAbsorptionCurve(meal.profile, duration)
        val glucose = (-45..180 step 5).map { minute ->
            val value = 6.0 + 35.0 * isf / 10.0 * curve.absorbedFraction(minute.toDouble()).pow(exponent) -
                minute / 5.0 * isf * 0.03
            PersonalAbsorptionGlucose(mealTs + minute * 60000L, value, DataQuality.OK,
                mealTs + minute * 60000L + 1000)
        }
        val insulin = (-45 until 180 step 5).map { minute ->
            PersonalAbsorptionInsulinInterval(mealTs + minute * 60000L,
                mealTs + (minute + 5) * 60000L, 0.03,
                PersonalAbsorptionInsulinSource.INDEPENDENT_COMPONENTS,
                mealTs + (minute + 5) * 60000L + 1000)
        }
        return PersonalAbsorptionInput(meal, "UTC", glucose, insulin,
            PersonalAbsorptionContext(isf, 10.0, "NOVORAPID", 360, mealTs - 1000, true),
            PersonalAbsorptionContextCheck.entries.toSet(), emptySet())
    }
    private fun history(duration: Int = 150, exponent: Double = 1.15, isf: Double = 3.0) =
        (1..18).map { input(it, duration, exponent, isf) }

    @Test fun learnsTimingFromCgmAndUsesLaterWholeDaysForValidation() {
        val result = estimator.evaluate(request, history())
        assertThat(result.status).isEqualTo(PersonalAbsorptionStatus.EVALUATED)
        assertThat(result.trainingMeals).isAtLeast(8)
        assertThat(result.trainingDays).isAtLeast(5)
        assertThat(result.validationDays).isAtLeast(2)
        assertThat(result.candidateMaeMmol5!!).isLessThan(result.baselineMaeMmol5!!)
        assertThat(result.candidate!!.durationMinutes).isGreaterThan(120)
        assertThat(result.candidate!!.exponent).isGreaterThan(1.0)
        assertThat(result.clinicalInfluenceAllowed).isFalse()
        assertThat(result.prospectiveForecastMae).isNull()
        assertThat(result.evidenceDigest).hasLength(64)
        assertThat(result).isEqualTo(estimator.evaluate(request, history().reversed()))
    }

    @Test fun candidatePreservesMassAndMonotonicity() {
        for (type in MealAbsorptionProfile.entries) {
            val duration = MealAbsorptionCurve(type, null).durationMinutes
            for (p in listOf(0.75, 1.0, 1.25)) {
                val shape = PersonalAbsorptionShape(type, duration, p)
                val fractions = (0..duration).map { shape.cumulative(it.toDouble()) }
                assertThat(fractions.first()).isEqualTo(0.0)
                assertThat(fractions.last()).isEqualTo(1.0)
                assertThat(fractions.zipWithNext().all { it.first <= it.second }).isTrue()
                assertThat(shape.absorbedGrams(35.0, duration.toDouble())).isEqualTo(35.0)
            }
        }
    }

    @Test fun absentCriticalContextAndEachConfoundRejectRatherThanGuess() {
        val adapter = PersonalAbsorptionEvidenceAdapter()
        assertThat(adapter.prepare(input(1).copy(context = null), cutoff).rejections)
            .contains(PersonalAbsorptionRejection.MISSING_CONTEXT)
        for (check in PersonalAbsorptionContextCheck.entries) {
            assertThat(adapter.prepare(input(1).let { it.copy(reviewed = it.reviewed - check) }, cutoff)
                .rejections).contains(PersonalAbsorptionRejection.UNKNOWN_CONTEXT)
        }
        for (fault in PersonalAbsorptionConfound.entries) {
            assertThat(adapter.prepare(input(1).copy(confounds = setOf(fault)), cutoff).episode).isNull()
        }
    }

    @Test fun onlyConfirmedUserCarbsCanBeLabels() {
        for (origin in MealPortionProvenance.entries.filter {
            it != MealPortionProvenance.USER_ENTERED && it != MealPortionProvenance.USER_CORRECTED
        }) {
            val i = input(1)
            assertThat(PersonalAbsorptionEvidenceAdapter().prepare(
                i.copy(meal = i.meal.copy(provenance = origin)), cutoff).episode).isNull()
        }
        for (changed in listOf(input(1).meal.copy(deleted = true), input(1).meal.copy(therapyRevision = null),
            input(1).meal.copy(grams = Double.NaN), input(1).meal.copy(canonicalId = ""))) {
            assertThat(PersonalAbsorptionEvidenceAdapter().prepare(input(1).copy(meal = changed), cutoff)
                .episode).isNull()
        }
    }

    @Test fun sparseCgmUnknownQualityAndInsulinGapsReject() {
        val i = input(1)
        val adapter = PersonalAbsorptionEvidenceAdapter()
        assertThat(adapter.prepare(i.copy(glucose = i.glucose.filterIndexed { n, _ -> n % 3 == 0 }), cutoff)
            .rejections).contains(PersonalAbsorptionRejection.INSUFFICIENT_CGM)
        assertThat(adapter.prepare(i.copy(glucose = i.glucose.map { it.copy(quality = DataQuality.SENSOR_ERROR) }), cutoff)
            .episode).isNull()
        assertThat(adapter.prepare(i.copy(insulin = i.insulin.drop(5)), cutoff).rejections)
            .contains(PersonalAbsorptionRejection.INSUFFICIENT_INSULIN)
        assertThat(adapter.prepare(i.copy(insulin = i.insulin.map { it.copy(source = PersonalAbsorptionInsulinSource.UNKNOWN) }), cutoff)
            .episode).isNull()
        assertThat(adapter.prepare(i.copy(insulin = i.insulin + i.insulin.first().copy(activityUnits = 0.3)), cutoff)
            .rejections).contains(PersonalAbsorptionRejection.INVALID_INSULIN)
    }

    @Test fun nonfiniteAndFutureInputsNeverBecomeZeroOrFreshEvidence() {
        val i = input(1)
        val adapter = PersonalAbsorptionEvidenceAdapter()
        assertThat(adapter.prepare(i.copy(context = i.context!!.copy(isfMmolPerUnit = Double.NaN)), cutoff).episode).isNull()
        assertThat(adapter.prepare(i.copy(context = i.context!!.copy(knownAtMs = i.meal.timestamp.toEpochMilli() + 1)), cutoff)
            .rejections).contains(PersonalAbsorptionRejection.INVALID_CONTEXT)
        assertThat(adapter.prepare(i.copy(glucose = i.glucose.map { it.copy(availableAtMs = cutoff + 1) }), cutoff)
            .rejections).contains(PersonalAbsorptionRejection.FUTURE_EVIDENCE)
        assertThat(adapter.prepare(i.copy(insulin = i.insulin.map { it.copy(activityUnits = Double.NaN) }), cutoff).episode).isNull()
    }

    @Test fun conflictingRevisionsAndTombstonesCannotReviveMeal() {
        val original = history()
        val first = original.first()
        for (changed in listOf(first.copy(meal = first.meal.copy(therapyRevision = "r2")),
            first.copy(meal = first.meal.copy(deleted = true)))) {
            val result = estimator.evaluate(request, original + changed)
            assertThat(result.trainingMeals + result.validationMeals).isEqualTo(17)
            assertThat(result.rejections[PersonalAbsorptionRejection.CONFLICTING_IDENTITY]).isEqualTo(1)
        }
        assertThat(estimator.evaluate(request, original + first)).isEqualTo(estimator.evaluate(request, original))
    }

    @Test fun trainingAvailabilityCannotCrossHeldoutBoundary() {
        val original = history()
        val holdoutStart = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
        val changed = original.map { i -> if (i.meal.canonicalId == "meal-10") i.copy(
            glucose = i.glucose.map { it.copy(availableAtMs = holdoutStart + 1) }) else i }
        val before = estimator.evaluate(request, original)
        val after = estimator.evaluate(request, changed)
        assertThat(after.trainingMeals).isEqualTo(before.trainingMeals - 1)
        assertThat(after.rejections[PersonalAbsorptionRejection.LATE_TRAINING_EVIDENCE]).isEqualTo(1)
    }

    @Test fun insufficientSupportAndInputOverflowRemainDistinct() {
        assertThat(estimator.evaluate(request, history().take(5)).status)
            .isEqualTo(PersonalAbsorptionStatus.INSUFFICIENT_DATA)
        assertThat(estimator.evaluate(request, List(241) { input(1) }).status)
            .isEqualTo(PersonalAbsorptionStatus.OVERFLOW)
        val i = input(1)
        assertThat(PersonalAbsorptionEvidenceAdapter().prepare(i.copy(glucose = List(10001) { i.glucose.first() }), cutoff)
            .rejections).contains(PersonalAbsorptionRejection.OVERFLOW)
    }

    @Test fun baselineMatchingDataDoesNotReportImprovement() {
        val result = estimator.evaluate(request, history(120, 1.0))
        assertThat(result.status).isEqualTo(PersonalAbsorptionStatus.NOT_IMPROVED)
    }

    @Test fun tinyUnidentifiableResponseReportsAmbiguity() {
        assertThat(estimator.evaluate(request, history(isf = 0.03)).status)
            .isEqualTo(PersonalAbsorptionStatus.AMBIGUOUS)
    }

    @Test fun nearbyDifferentMealIsNotCleanEvenWithClaimedContextReview() {
        val i = input(1)
        val neighbor = i.copy(meal = i.meal.copy(canonicalId = "other-meal",
            timestamp = i.meal.timestamp.plusSeconds(1800)))
        val result = estimator.evaluate(request, listOf(i, neighbor))
        assertThat(result.rejections[PersonalAbsorptionRejection.OVERLAPPING_MEAL]).isEqualTo(2)
    }

    @Test fun preparedResidualsCannotBeChangedByAConsumer() {
        val episode = PersonalAbsorptionEvidenceAdapter().prepare(input(1), cutoff).episode!!
        assertThrows(UnsupportedOperationException::class.java) {
            (episode.steps as MutableList<PersonalAbsorptionStep>).clear()
        }
    }

    @Test fun allDiagnosticStatusesRemainUnqualifiedForTherapyAndLaboratoryGi() {
        for (result in listOf(estimator.evaluate(request, history()),
            estimator.evaluate(request, emptyList()), estimator.evaluate(request, history(isf = 0.03)))) {
            assertThat(result.actualMassAccuracyValidated).isFalse()
            assertThat(result.clinicalInfluenceAllowed).isFalse()
            assertThat(result.laboratoryGi).isNull()
            assertThat(result.prospectiveForecastMae).isNull()
        }
    }

    @Test fun recordedTimeNeighborhoodWrapsAtMidnight() {
        val shift = 520 * 60000L
        val changed = history().map { i -> i.copy(
            meal = i.meal.copy(timestamp = i.meal.timestamp.minusMillis(shift), availableAt = i.meal.availableAt.minusMillis(shift)),
            glucose = i.glucose.map { it.copy(timestampMs = it.timestampMs - shift, availableAtMs = it.availableAtMs!! - shift) },
            insulin = i.insulin.map { it.copy(startMs = it.startMs - shift, endMs = it.endMs - shift, availableAtMs = it.availableAtMs!! - shift) },
            context = i.context!!.copy(knownAtMs = i.context.knownAtMs - shift)) }
        assertThat(estimator.evaluate(request.copy(minuteOfDay = 23 * 60 + 40), changed).status)
            .isEqualTo(PersonalAbsorptionStatus.EVALUATED)
        assertThat(estimator.evaluate(request.copy(recordedZoneId = "Europe/Berlin"), history()).candidate).isNull()
    }

    @Test fun aDurationLimitIsNotConfirmedPhysiologicalCompletion() {
        val result = estimator.evaluate(request, history(duration = 180))
        assertThat(result.status).isEqualTo(PersonalAbsorptionStatus.BOUNDED_CANDIDATE)
    }

    @Test fun extremeFiniteValuesCannotOverflowTheLossIntoAnEvaluatedResult() {
        val i = input(1)
        assertThat(PersonalAbsorptionEvidenceAdapter().prepare(i.copy(
            context = i.context!!.copy(isfMmolPerUnit = 1e308)), cutoff).episode).isNull()
        assertThat(PersonalAbsorptionEvidenceAdapter().prepare(i.copy(
            glucose = i.glucose.map { it.copy(mmol = 1e308) }), cutoff).episode).isNull()
    }

    @Test fun evidenceDigestIncludesHistoricalModelProvenanceNotOnlyEqualResiduals() {
        val original = history()
        val changed = original.map { it.copy(context = it.context!!.copy(insulinModelId = "OTHER_MODEL")) }
        assertThat(estimator.evaluate(request, changed).evidenceDigest)
            .isNotEqualTo(estimator.evaluate(request, original).evidenceDigest)
    }
}
