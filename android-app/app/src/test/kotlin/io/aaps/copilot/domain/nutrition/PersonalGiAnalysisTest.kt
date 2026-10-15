package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.pow

class PersonalGiAnalysisTest {
    private val cutoff = PersonalAbsorptionEpisodeFixtures.cutoff
    private val request = PersonalAbsorptionRequest(cutoff, MealAbsorptionProfile.MIXED, MealPortion.MEDIUM, "UTC", 720)
    private val analyzer = PersonalGiAnalyzer()
    private fun giValue(day: Int) = if (day % 2 == 0) 20.0 else 80.0
    private fun frames(giEffect: Double = -0.2) = (1..18).map { day ->
        val meal = PersonalAbsorptionEpisodeFixtures.meal("meal-$day", cutoff - day * 86_400_000L)
        val frame = PersonalAbsorptionEpisodeFixtures.evidence(meal)
        val curve = MealAbsorptionCurve(meal.profile, null)
        val exponent = 1.0 + giEffect * (giValue(day) - 50.0) / 100
        frame.copy(input = frame.input.copy(glucose = frame.input.glucose.map { sample ->
            val minute = (sample.timestampMs - meal.timestamp.toEpochMilli()) / 60_000.0
            sample.copy(mmol = 6.0 + meal.grams * 0.3 * curve.absorbedFraction(minute).pow(exponent) - minute / 5 * 0.03)
        }))
    }
    private fun source(frames: List<PersonalAbsorptionEpisodeEvidence> = frames()) =
        PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", frames.map { it.input.meal }, frames)
    private fun indexes() = (1..18).map { day -> PersonalGiEvidence("meal-$day", "r1", giValue(day),
        "USER", null, cutoff - day * 86_400_000L + 1000) }

    @Test fun learnsIncrementalGiContributionWithoutCreatingTherapyAuthority() {
        val result = analyzer.analyze(request, source(), indexes())
        assertThat(result.status).isEqualTo(PersonalGiStatus.ANALYZED)
        assertThat(result.trainingMeals).isEqualTo(12)
        assertThat(result.validationMeals).isEqualTo(6)
        assertThat(result.giImprovesEveryHorizon).isTrue()
        assertThat(result.model!!.slopePer100Gi).isLessThan(0.0)
        assertThat(result.horizons.map { it.minutes }).containsExactly(5, 30, 60).inOrder()
        assertThat(result.horizons.all { it.meals == 6 && it.days == 6 && it.windows > 0 }).isTrue()
        assertThat(result.evidenceDigest).hasLength(64)
        assertThat(result.clinicalInfluenceAllowed).isFalse()
        assertThat(result.prospectiveForecastValidated).isFalse()
        assertThat(result.laboratoryGi).isNull()
        assertThat(result.targetPercentage).isNull()
        assertThat(result.targetManagerBlockers).hasSize(3)
    }

    @Test fun zeroEffectDoesNotInventAGiCorrection() {
        val result = analyzer.analyze(request, source(frames(0.0)), indexes())
        assertThat(result.status).isEqualTo(PersonalGiStatus.ANALYZED)
        assertThat(result.model!!.slopePer100Gi).isEqualTo(0.0)
        assertThat(result.giImprovesEveryHorizon).isFalse()
        assertThat(result.horizons.all { it.personalModel == it.personalWithGi }).isTrue()
    }

    @Test fun holdoutOutcomeCannotChangeFittedParameters() {
        val original = frames()
        val changed = original.mapIndexed { index, frame ->
            if (index >= 6) frame else frame.copy(input = frame.input.copy(glucose = frame.input.glucose.map { point ->
                val age = (point.timestampMs - frame.input.meal.timestamp.toEpochMilli()) / 60_000.0
                point.copy(mmol = point.mmol + if (age > 0) age / 300.0 else 0.0)
            }))
        }
        val before = analyzer.analyze(request, source(original), indexes())
        val after = analyzer.analyze(request, source(changed), indexes())
        assertThat(after.model).isEqualTo(before.model)
        assertThat(after.horizons).isNotEqualTo(before.horizons)
        assertThat(after.evidenceDigest).isNotEqualTo(before.evidenceDigest)
    }

    @Test fun missingGiNeverBecomesAPresetOrACgmDerivedIndex() {
        val result = analyzer.analyze(request, source(), emptyList())
        assertThat(result.status).isEqualTo(PersonalGiStatus.INSUFFICIENT_DATA)
        assertThat(result.rejections["MISSING_GI"]).isEqualTo(18)
        assertThat(result.model).isNull()
        assertThat(result.horizons).isEmpty()
        assertThat(result.laboratoryGi).isNull()
    }

    @Test fun sparseHistoryAndConstantGiCannotIdentifyAnEffect() {
        assertThat(analyzer.analyze(request, source(frames().take(5)), indexes()).status)
            .isEqualTo(PersonalGiStatus.INSUFFICIENT_DATA)
        val result = analyzer.analyze(request, source(), indexes().map { it.copy(value = 55.0) })
        assertThat(result.status).isEqualTo(PersonalGiStatus.INSUFFICIENT_GI_VARIATION)
        assertThat(result.model).isNull()
    }

    @Test fun eachInvalidGiAndUnknownProvenanceIsExcluded() {
        val first = indexes().first()
        for ((changed, reason) in listOf(
            first.copy(value = Double.NaN) to "INVALID_GI",
            first.copy(value = -1.0) to "INVALID_GI",
            first.copy(source = "MODEL") to "INVALID_GI",
            first.copy(source = "CATALOG", reference = null) to "INVALID_GI",
            first.copy(therapyRevision = "r2") to "GI_REVISION_MISMATCH",
            first.copy(availableAtMs = cutoff + 1) to "GI_AVAILABILITY",
            first.copy(availableAtMs = 0) to "GI_AVAILABILITY"
        )) {
            val result = analyzer.analyze(request, source(), listOf(changed) + indexes().drop(1))
            assertThat(result.rejections[reason]).isEqualTo(1)
            assertThat(result.validationMeals).isEqualTo(5)
        }
    }

    @Test fun conflictingGiCannotReviveAnotherVersionAndIdenticalDuplicatesAreNeutral() {
        val data = indexes()
        val before = analyzer.analyze(request, source(), data)
        val duplicate = analyzer.analyze(request, source(), data + data.first())
        assertThat(duplicate.model).isEqualTo(before.model)
        assertThat(duplicate.evidenceDigest).isEqualTo(before.evidenceDigest)
        val conflict = analyzer.analyze(request, source(), data + data.first().copy(value = 25.0))
        assertThat(conflict.rejections["CONFLICTING_GI"]).isEqualTo(1)
        assertThat(conflict.validationMeals).isEqualTo(5)
    }

    @Test fun missingLabelsCannotMoveTheOriginalHeldoutBoundary() {
        val result = analyzer.analyze(request, source(), indexes().filter { it.canonicalId !in setOf("meal-1", "meal-2") })
        assertThat(result.trainingMeals).isEqualTo(12)
        assertThat(result.validationMeals).isEqualTo(4)
    }

    @Test fun lateTrainingGiAndLateReviewCannotLeakAcrossDays() {
        val frames = frames().map { frame -> if (frame.input.meal.canonicalId == "meal-9")
            frame.copy(preparedAtMs = cutoff - 5 * 86_400_000L) else frame }
        val gi = indexes().map { if (it.canonicalId == "meal-10") it.copy(availableAtMs = cutoff - 5 * 86_400_000L) else it }
        val result = analyzer.analyze(request, source(frames), gi)
        assertThat(result.trainingMeals).isEqualTo(10)
        assertThat(result.validationMeals).isEqualTo(6)
        assertThat(result.rejections["LATE_TRAINING_EVIDENCE"]).isEqualTo(2)
    }

    @Test fun giOutsideTrainingRangeIsNotExtrapolated() {
        val gi = indexes().map { if (it.canonicalId in setOf("meal-1", "meal-2", "meal-3", "meal-4")) it.copy(value = 120.0) else it }
        val result = analyzer.analyze(request, source(), gi)
        assertThat(result.rejections["GI_OUTSIDE_TRAINING_SUPPORT"]).isEqualTo(4)
        assertThat(result.status).isEqualTo(PersonalGiStatus.INSUFFICIENT_DATA)
        assertThat(result.horizons).isEmpty()
    }

    @Test fun requestTypePortionAndCircularHourSelectComparableMealsOnly() {
        val s = source()
        for (changed in listOf(request.copy(profile = MealAbsorptionProfile.FAST),
            request.copy(portion = MealPortion.SMALL), request.copy(minuteOfDay = 300))) {
            val result = analyzer.analyze(changed, s, indexes())
            assertThat(result.rejections["NOT_COMPARABLE"]).isEqualTo(18)
            assertThat(result.model).isNull()
        }
        assertThat(analyzer.analyze(request.copy(minuteOfDay = 600), s, indexes()).status)
            .isEqualTo(PersonalGiStatus.ANALYZED)
    }

    @Test fun sensorCalibrationAndInsulinModelChangesCannotBePooled() {
        for (change in listOf<(PersonalAbsorptionEpisodeEvidence) -> PersonalAbsorptionEpisodeEvidence>(
            { it.copy(sensorSessionId = "sensor-session-2") },
            { it.copy(calibrationIdentity = "calibration-2") },
            { it.copy(input = it.input.copy(context = it.input.context!!.copy(insulinModelId = "other-model"))) },
            { it.copy(input = it.input.copy(context = it.input.context!!.copy(insulinDurationMinutes = 300))) }
        )) {
            val rows = frames().mapIndexed { index, frame -> if (index == 0) change(frame) else frame }
            val s = source(rows)
            val mixed = analyzer.analyze(request, s, indexes())
            assertThat(mixed.status).isEqualTo(PersonalGiStatus.MIXED_CONTEXT)
            assertThat(mixed.availableCohorts).hasSize(2)
            assertThat(mixed.model).isNull()
            val selected = mixed.availableCohorts.maxBy { it.value }.key
            val result = analyzer.analyze(request, s, indexes(), selected)
            assertThat(result.status).isEqualTo(PersonalGiStatus.ANALYZED)
            assertThat(result.rejections["UNQUALIFIED_OR_DIFFERENT_COHORT"]).isEqualTo(1)
        }
    }

    @Test fun legacyInputsWithoutQualifiedProvenanceCannotProduceAModel() {
        val rows = frames()
        val source = PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", rows.map { it.input.meal }, emptyList())
        val result = analyzer.analyze(request, source, indexes())
        assertThat(result.status).isEqualTo(PersonalGiStatus.INSUFFICIENT_DATA)
        assertThat(result.rejections["SOURCE_INDEPENDENT_INSULIN_INTERVALS"]).isEqualTo(18)
        assertThat(result.model).isNull()
    }

    @Test fun unavailableAndOverflowNeverBecomePartialAnalysis() {
        val source = PersonalAbsorptionSourceSnapshot(cutoff, "UTC", PersonalAbsorptionSourceStatus.UNAVAILABLE, emptyList())
        assertThat(analyzer.analyze(request, source, indexes()).status).isEqualTo(PersonalGiStatus.SOURCE_UNAVAILABLE)
        assertThat(analyzer.analyze(request, source(), List(5001) { indexes().first() }).status).isEqualTo(PersonalGiStatus.OVERFLOW)
    }

    @Test fun cutoffAndZoneMustMatchTheFrozenSource() {
        assertThrows(IllegalArgumentException::class.java) { analyzer.analyze(request.copy(cutoffMs = cutoff + 1), source(), indexes()) }
        assertThrows(IllegalArgumentException::class.java) { analyzer.analyze(request.copy(recordedZoneId = "Asia/Tbilisi"), source(), indexes()) }
    }

    @Test fun missingContinuousHorizonRemainsUnknownRatherThanZeroError() {
        val original = source()
        val gaps = PersonalAbsorptionSourceSnapshot(cutoff, "UTC", original.status, original.outcomes.map { outcome ->
            val episode = requireNotNull(outcome.episode)
            PersonalAbsorptionEpisodeOutcome(outcome.canonicalId, outcome.therapyRevision, outcome.timestampMs,
                PersonalAbsorptionEpisode(episode.meal, episode.recordedZoneId,
                    episode.steps.filter { it.ageMinutes !in setOf(45, 90, 135) }, episode.csfMmolPerGram,
                    episode.latestEvidenceAtMs, episode.sourceDigest, episode.comparisonCohort), emptySet(), emptySet())
        })
        val result = analyzer.analyze(request, gaps, indexes())
        val long = result.horizons.single { it.minutes == 60 }
        assertThat(long.windows).isEqualTo(0)
        assertThat(long.personalWithGi).isNull()
        assertThat(result.giImprovesEveryHorizon).isFalse()
    }

    @Test fun oversizedGiMetadataRejectsBeforeFitting() {
        val gi = indexes().map { it.copy(reference = "x".repeat(257)) }
        assertThat(analyzer.analyze(request, source(), gi).status).isEqualTo(PersonalGiStatus.OVERFLOW)
    }

    @Test fun massStressIsSeparateAndNeverRewritesDeclaredFood() {
        val s = source()
        val before = s.episodes.map { it.meal }
        val result = analyzer.analyze(request, s, indexes())
        assertThat(result.massStress.map { it.declaredMassScale }).containsExactly(0.8, 1.2)
        assertThat(result.massStress.all { it.horizons.size == 3 }).isTrue()
        assertThat(result.massStress.all { stress -> stress.horizons.all { h -> h.personalWithGi!!.maeMmol.isFinite() } }).isTrue()
        assertThat(s.episodes.map { it.meal }).isEqualTo(before)
    }

    @Test fun allLearnedShapesKeepMassEndpointsAndMonotonicity() {
        val model = analyzer.analyze(request, source(), indexes()).model!!
        for (gi in listOf(20.0, 50.0, 80.0)) {
            val shape = model.shapeFor(gi)!!
            val fractions = (0..180).map { shape.cumulative(it.toDouble()) }
            assertThat(fractions.first()).isEqualTo(0.0)
            assertThat(fractions.last()).isEqualTo(1.0)
            assertThat(fractions.zipWithNext().all { it.first <= it.second }).isTrue()
            assertThat(shape.absorbedGrams(35.0, 180.0)).isEqualTo(35.0)
        }
        assertThat(model.shapeFor(19.0)).isNull()
        assertThat(model.shapeFor(Double.NaN)).isNull()
    }

    @Test fun resultCollectionsAreFrozenAndInputOrderingDoesNotChangeAnalysis() {
        val before = analyzer.analyze(request, source(), indexes())
        val after = analyzer.analyze(request, source(frames().reversed()), indexes().reversed())
        assertThat(after.evidenceDigest).isEqualTo(before.evidenceDigest)
        assertThat(after.model).isEqualTo(before.model)
        assertThat(after.horizons).isEqualTo(before.horizons)
        assertThrows(UnsupportedOperationException::class.java) { (after.horizons as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (after.rejections as MutableMap)["invented"] = 1 }
        assertThrows(UnsupportedOperationException::class.java) { (after.targetManagerBlockers as MutableList).clear() }
    }
}
