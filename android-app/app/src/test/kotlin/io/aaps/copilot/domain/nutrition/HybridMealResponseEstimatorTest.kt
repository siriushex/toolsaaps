package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.profile.*
import java.time.Instant
import java.time.LocalDate
import kotlin.math.pow
import org.junit.Assert.assertThrows
import org.junit.Test

class HybridMealResponseEstimatorTest {
    private val cutoff = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()
    private val request = HybridMealResponseRequest(cutoff, MealAbsorptionProfile.MIXED,
        MealPortion.MEDIUM, "UTC", 9 * 60)
    private val estimator = HybridMealResponseEstimator()

    private fun input(day: Int, duration: Int = 150, exponent: Double = 1.15,
        scale: Double = 1.0, isf: Double = 3.0): PersonalAbsorptionInput {
        val mealTs = Instant.parse("2026-10-12T09:00:00Z").minusSeconds(day * 86400L).toEpochMilli()
        val meal = MealPortionObservation("history-meal-$day", Instant.ofEpochMilli(mealTs), 35.0,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_ENTERED, MealPortion.MEDIUM,
            availableAt = Instant.ofEpochMilli(mealTs + 1000), therapyRevision = "r1")
        val curve = MealAbsorptionCurve(meal.profile, duration)
        val glucose = (-45..180 step 5).map { minute ->
            val value = 6.0 + scale * 35.0 * isf / 10.0 *
                curve.absorbedFraction(minute.toDouble()).pow(exponent) - minute / 5.0 * isf * 0.005
            PersonalAbsorptionGlucose(mealTs + minute * 60000L, value, DataQuality.OK,
                mealTs + minute * 60000L + 1000)
        }
        val insulin = (-45 until 180 step 5).map { minute ->
            PersonalAbsorptionInsulinInterval(mealTs + minute * 60000L,
                mealTs + (minute + 5) * 60000L, 0.005,
                PersonalAbsorptionInsulinSource.INDEPENDENT_COMPONENTS,
                mealTs + (minute + 5) * 60000L + 1000)
        }
        return PersonalAbsorptionInput(meal, "UTC", glucose, insulin,
            PersonalAbsorptionContext(isf, 10.0, "rapid-model", 360, mealTs - 1000, true),
            PersonalAbsorptionContextCheck.entries.toSet(), emptySet())
    }

    @Test fun noHistoryAlwaysReturnsCompleteTypeCurveAndPresetQuantity() {
        for (type in MealAbsorptionProfile.entries) {
            val result = estimator.preview(request.copy(selectedProfile = type))
            assertThat(result.curveSource).isEqualTo(HybridMealCurveSource.TYPE_DEFAULT)
            assertThat(result.quantitySource).isEqualTo(HybridMealQuantitySource.PORTION_PRESET)
            assertThat(result.modeledGrams).isEqualTo(25.0)
            assertThat(result.shape.durationMinutes).isEqualTo(MealAbsorptionCurve(type, null).durationMinutes)
            assertThat(result.curve.first().cumulativeFraction).isEqualTo(0.0)
            assertThat(result.curve.last().cumulativeFraction).isEqualTo(1.0)
            assertThat(result.curve.last().modeledAbsorbedGrams).isEqualTo(25.0)
            assertThat(result.curve.last().modeledRemainingGrams).isEqualTo(0.0)
            assertThat(result.curve.size).isEqualTo(result.shape.durationMinutes / 5 + 1)
            assertThat(result.curve.zipWithNext().all { it.first.cumulativeFraction <= it.second.cumulativeFraction }).isTrue()
            assertThat(result.response).isEmpty()
            assertThat(result.supportMeals).isEqualTo(0)
        }
    }

    @Test fun oneQualifiedMealProducesSignedObservationsWithoutInventedSpreadOrFit() {
        val result = estimator.preview(request, listOf(input(1)))
        assertThat(result.supportMeals).isEqualTo(1)
        assertThat(result.supportDays).isEqualTo(1)
        assertThat(result.response).hasSize(36)
        assertThat(result.response.all { it.supportMeals == 1 && it.p25 == null && it.p75 == null }).isTrue()
        assertThat(result.curveSource).isEqualTo(HybridMealCurveSource.TYPE_DEFAULT)
        assertThat(result.personalEvaluation.status).isEqualTo(PersonalAbsorptionStatus.INSUFFICIENT_DATA)
        val negative = estimator.preview(request, listOf(input(1, scale = -0.1)))
        assertThat(negative.response.first().medianNormalizedDelta).isLessThan(0.0)
        assertThat(negative.supportMeals).isEqualTo(1)
    }

    @Test fun sparseMedianResistsOutlierAndCannotChangeTypeShape() {
        val single = estimator.preview(request, listOf(input(1)))
        val sparse = estimator.preview(request, listOf(input(1), input(2), input(3, scale = 0.1)))
        assertThat(sparse.supportMeals).isEqualTo(3)
        assertThat(sparse.response.first().medianNormalizedDelta)
            .isWithin(1e-10).of(single.response.first().medianNormalizedDelta)
        assertThat(sparse.response.first().p25).isNotNull()
        assertThat(sparse.shape).isEqualTo(single.shape)
    }

    @Test fun explicitDeclaredGramsAreNeverReplacedByPortionSuggestion() {
        val result = estimator.preview(request.copy(declaredGrams = 42.0), (1..18).map { input(it) })
        assertThat(result.modeledGrams).isEqualTo(42.0)
        assertThat(result.quantitySource).isEqualTo(HybridMealQuantitySource.DECLARED)
        assertThat(result.quantityPrior.estimate.fromHistory).isTrue()
        assertThat(result.curve.last().modeledAbsorbedGrams).isEqualTo(42.0)
    }

    @Test fun qualifiedTimingUsesExistingHeldoutEvaluationNotSparseRule() {
        val history = (1..18).map { input(it) }
        val result = estimator.preview(request, history)
        val old = PersonalAbsorptionEstimator().evaluate(
            PersonalAbsorptionRequest(cutoff, MealAbsorptionProfile.MIXED, MealPortion.MEDIUM, "UTC", 540), history)
        assertThat(result.personalEvaluation).isEqualTo(old)
        assertThat(result.curveSource).isEqualTo(HybridMealCurveSource.PERSONAL_DIAGNOSTIC)
        assertThat(result.shape).isEqualTo(old.candidate)
        assertThat(result.quantitySource).isEqualTo(HybridMealQuantitySource.HISTORY_SUGGESTION)
        assertThat(result).isEqualTo(estimator.preview(request, history.reversed()))
    }

    @Test fun unknownFoodTypeUsesExplicitMixedFallbackWithoutPersonalShape() {
        val result = estimator.preview(request.copy(selectedProfile = null), (1..18).map { input(it) })
        assertThat(result.profileWasDefaulted).isTrue()
        assertThat(result.shape.profile).isEqualTo(MealAbsorptionProfile.MIXED)
        assertThat(result.shape.durationMinutes).isEqualTo(120)
        assertThat(result.curveSource).isEqualTo(HybridMealCurveSource.TYPE_DEFAULT)
    }

    @Test fun validIncompleteAndInvalidBodyProfilesDoNotManufactureLearning() {
        val complete = EnergyProfileSettings(birthDateEpochDay = LocalDate.of(1985, 1, 1).toEpochDay(),
            heightCm = 175.0, weightKg = 70.0)
        val a = estimator.preview(request.copy(personalProfile = complete))
        val b = estimator.preview(request.copy(personalProfile = complete.copy(weightKg = Double.NaN)))
        val c = estimator.preview(request)
        assertThat(a.profileResolution).isInstanceOf(ProfileResolution.Complete::class.java)
        assertThat(b.profileResolution).isInstanceOf(ProfileResolution.Invalid::class.java)
        assertThat(c.profileResolution).isInstanceOf(ProfileResolution.Incomplete::class.java)
        assertThat(a.curve).isEqualTo(b.curve)
        assertThat(a.curve).isEqualTo(c.curve)
        assertThat(a.supportMeals).isEqualTo(0)
    }

    @Test fun allRejectedOrOverflowHistoryStillHasAnHonestDefaultPreview() {
        val missing = estimator.preview(request, listOf(input(1).copy(context = null)))
        assertThat(missing.supportMeals).isEqualTo(0)
        assertThat(missing.personalEvaluation.rejections).containsKey(PersonalAbsorptionRejection.MISSING_CONTEXT)
        assertThat(missing.curve).isNotEmpty()
        val overflow = estimator.preview(request, List(241) { input(1) })
        assertThat(overflow.personalEvaluation.status).isEqualTo(PersonalAbsorptionStatus.OVERFLOW)
        assertThat(overflow.quantitySource).isEqualTo(HybridMealQuantitySource.PORTION_PRESET)
        assertThat(overflow.supportMeals).isEqualTo(0)
        assertThat(overflow.curve.last().modeledRemainingGrams).isEqualTo(0.0)
    }

    @Test fun invalidRequestCannotPretendZeroFood() {
        for (grams in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY, 500.1)) {
            assertThrows(IllegalArgumentException::class.java) { estimator.preview(request.copy(declaredGrams = grams)) }
        }
        assertThrows(IllegalArgumentException::class.java) { estimator.preview(request.copy(minuteOfDay = 1440)) }
        assertThrows(IllegalArgumentException::class.java) { estimator.preview(request.copy(cutoffMs = 0)) }
        assertThrows(Exception::class.java) { estimator.preview(request.copy(recordedZoneId = "absent-zone")) }
        assertThrows(IllegalArgumentException::class.java) { estimator.preview(request.copy(
            portionSettings = MealPortionSettings(medium = MealPortionRange(40.0, 15.0, 25.0)))) }
    }

    @Test fun everyResultRemainsAdvisoryAndListsCannotBeMutated() {
        for (result in listOf(estimator.preview(request), estimator.preview(request, listOf(input(1))),
            estimator.preview(request, (1..18).map { input(it) }))) {
            assertThat(result.clinicalInfluenceAllowed).isFalse()
            assertThat(result.actualMassAccuracyValidated).isFalse()
            assertThat(result.laboratoryGi).isNull()
            assertThat(result.targetAdjustmentPercent).isNull()
            assertThat(result.prospectiveForecastMae).isNull()
            assertThat(result.evidenceCutoffMs).isEqualTo(cutoff)
            assertThat(result.evidenceDigest).hasLength(64)
            assertThrows(UnsupportedOperationException::class.java) {
                (result.curve as MutableList<HybridMealCurvePoint>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (result.response as MutableList<HybridMealResponsePoint>).clear()
            }
        }
    }

    @Test fun unfinishedDayCannotBiasHistoricalPortionPrior() {
        val past = (1..5).map { input(it) }
        val today = input(0).let { it.copy(meal = it.meal.copy(grams = 39.0), context = null) }
        val before = estimator.preview(request, past)
        val after = estimator.preview(request, past + today)
        assertThat(after.quantityPrior).isEqualTo(before.quantityPrior)
        assertThat(after.personalEvaluation.rejections).containsKey(PersonalAbsorptionRejection.OUTSIDE_WINDOW)
    }

    @Test fun rejectionSetOrderingDoesNotChangePreviewIdentity() {
        val original = input(1)
        val a = original.copy(confounds = linkedSetOf(PersonalAbsorptionConfound.SENSOR_ERROR,
            PersonalAbsorptionConfound.ACTIVITY))
        val b = original.copy(confounds = linkedSetOf(PersonalAbsorptionConfound.ACTIVITY,
            PersonalAbsorptionConfound.SENSOR_ERROR))
        assertThat(estimator.preview(request, listOf(a))).isEqualTo(estimator.preview(request, listOf(b)))
    }

    @Test fun missingEndPointIsNotAZeroResponseSample() {
        val first = input(1)
        val shortened = input(2).let { it.copy(glucose = it.glucose.dropLast(1)) }
        val result = estimator.preview(request, listOf(first, shortened))
        assertThat(result.supportMeals).isEqualTo(2)
        assertThat(result.response.first().supportMeals).isEqualTo(2)
        assertThat(result.response.last().ageMinutes).isEqualTo(180)
        assertThat(result.response.last().supportMeals).isEqualTo(1)
    }

    @Test fun nonfiniteNormalizationExcludesWholeSummaryEpisodeNotOneConvenientPoint() {
        val tiny = input(1, isf = Double.MIN_VALUE).let { i -> i.copy(
            context = i.context!!.copy(carbRatioGramsPerUnit = 1.0),
            glucose = i.glucose.map { point ->
                val age = (point.timestampMs - i.meal.timestamp.toEpochMilli()) / 60000.0
                point.copy(mmol = point.mmol + maxOf(0.0, age) * 0.001)
            }) }
        val result = estimator.preview(request, listOf(tiny))
        assertThat(result.unusableSummaryMeals).isEqualTo(1)
        assertThat(result.response).isEmpty()
        assertThat(result.supportMeals).isEqualTo(0)
        assertThat(result.curveSource).isEqualTo(HybridMealCurveSource.TYPE_DEFAULT)
    }

    @Test fun conflictsTombstonesAndNonindependentOriginsDoNotBecomeObservations() {
        val original = input(1)
        for (other in listOf(original.copy(meal = original.meal.copy(therapyRevision = "r2")),
            original.copy(meal = original.meal.copy(deleted = true)))) {
            val result = estimator.preview(request, listOf(original, other))
            assertThat(result.supportMeals).isEqualTo(0)
            assertThat(result.quantitySource).isEqualTo(HybridMealQuantitySource.PORTION_PRESET)
        }
        for (origin in MealPortionProvenance.entries.filter {
            it != MealPortionProvenance.USER_ENTERED && it != MealPortionProvenance.USER_CORRECTED
        }) {
            assertThat(estimator.preview(request, listOf(original.copy(meal = original.meal.copy(provenance = origin))))
                .supportMeals).isEqualTo(0)
        }
        assertThat(estimator.preview(request, listOf(original, original))).isEqualTo(
            estimator.preview(request, listOf(original)))
    }

    @Test fun sensorInsulinUnknownAndFutureEvidenceFallBackWithoutDisablingCurve() {
        val i = input(1)
        for (changed in listOf(i.copy(reviewed = emptySet()), i.copy(insulin = i.insulin.drop(5)),
            i.copy(glucose = i.glucose.filterIndexed { n, _ -> n % 3 == 0 }),
            i.copy(glucose = i.glucose.map { it.copy(quality = DataQuality.SENSOR_ERROR) }),
            i.copy(glucose = i.glucose.map { it.copy(availableAtMs = cutoff + 1) }),
            i.copy(insulin = i.insulin.map { it.copy(source = PersonalAbsorptionInsulinSource.UNKNOWN) }))) {
            val result = estimator.preview(request, listOf(changed))
            assertThat(result.supportMeals).isEqualTo(0)
            assertThat(result.curve.last().modeledRemainingGrams).isEqualTo(0.0)
            assertThat(result.personalEvaluation.rejections).isNotEmpty()
        }
    }

    @Test fun timeZoneTypePortionAndSelectedTimeMustBeComparable() {
        val i = input(1)
        for (query in listOf(request.copy(recordedZoneId = "Europe/Berlin"),
            request.copy(selectedProfile = MealAbsorptionProfile.FAST),
            request.copy(portion = MealPortion.LARGE), request.copy(minuteOfDay = 3 * 60))) {
            assertThat(estimator.preview(query, listOf(i)).supportMeals).isEqualTo(0)
        }
        val shift = 520 * 60000L
        val midnight = i.copy(
            meal = i.meal.copy(timestamp = i.meal.timestamp.minusMillis(shift), availableAt = i.meal.availableAt.minusMillis(shift)),
            glucose = i.glucose.map { it.copy(timestampMs = it.timestampMs - shift, availableAtMs = it.availableAtMs!! - shift) },
            insulin = i.insulin.map { it.copy(startMs = it.startMs - shift, endMs = it.endMs - shift, availableAtMs = it.availableAtMs!! - shift) },
            context = i.context!!.copy(knownAtMs = i.context.knownAtMs - shift))
        assertThat(estimator.preview(request.copy(minuteOfDay = 23 * 60 + 40), listOf(midnight)).supportMeals).isEqualTo(1)
    }

    @Test fun unsuccessfulAmbiguousAndBoundedCandidatesNeverReplaceDefault() {
        val histories = listOf((1..18).map { input(it, duration = 120, exponent = 1.0) },
            (1..18).map { input(it, isf = 0.03) }, (1..18).map { input(it, duration = 180) })
        val statuses = listOf(PersonalAbsorptionStatus.NOT_IMPROVED, PersonalAbsorptionStatus.AMBIGUOUS,
            PersonalAbsorptionStatus.BOUNDED_CANDIDATE)
        histories.zip(statuses).forEach { (history, status) ->
            val result = estimator.preview(request, history)
            assertThat(result.personalEvaluation.status).isEqualTo(status)
            assertThat(result.curveSource).isEqualTo(HybridMealCurveSource.TYPE_DEFAULT)
            assertThat(result.shape.durationMinutes).isEqualTo(120)
        }
    }

    @Test fun modelMassIsConservedAtEveryPointAndQualityCannotImplyTherapy() {
        val result = estimator.preview(request.copy(declaredGrams = 43.7), (1..18).map { input(it) })
        result.curve.forEach { point ->
            assertThat(point.modeledAbsorbedGrams + point.modeledRemainingGrams).isWithin(1e-10).of(43.7)
            assertThat(point.modeledRemainingGrams).isAtLeast(0.0)
        }
        assertThat(result.clinicalInfluenceAllowed).isFalse()
    }
}
