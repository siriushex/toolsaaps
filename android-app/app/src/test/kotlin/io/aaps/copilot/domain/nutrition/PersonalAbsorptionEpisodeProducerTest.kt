package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonalAbsorptionEpisodeProducerTest {
    private val cutoff = PersonalAbsorptionEpisodeFixtures.cutoff
    private val meal = PersonalAbsorptionEpisodeFixtures.meal()
    private val evidence = PersonalAbsorptionEpisodeFixtures.evidence(meal)

    private fun produce(meals: List<MealPortionObservation> = listOf(meal),
        frames: List<PersonalAbsorptionEpisodeEvidence> = listOf(evidence),
        status: PersonalAbsorptionSourceStatus = PersonalAbsorptionSourceStatus.READY) =
        PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", meals, frames, status)

    @Test fun completeCanonicallyBoundEvidenceProducesAResearchEpisode() {
        val result = produce()
        assertThat(result.episodes).hasSize(1)
        assertThat(result.outcomes.single().sourceProblems).isEmpty()
        assertThat(result.outcomes.single().preparationRejections).isEmpty()
        assertThat(result.episodes.single().meal).isEqualTo(meal)
        assertThat(result.episodes.single().sourceDigest).hasLength(64)
        assertThat(result.episodes.single().latestEvidenceAtMs).isEqualTo(evidence.preparedAtMs)
        assertThat(result.clinicalInfluenceAllowed).isFalse()
        assertThat(result.actualMassAccuracyValidated).isFalse()
    }

    @Test fun legacyLabelsExposeEveryMissingSourceRatherThanFakePreparedEpisodes() {
        val result = produce(frames = emptyList())
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes.single().sourceProblems).containsAtLeast(
            PersonalAbsorptionSourceProblem.HISTORICAL_TIMEZONE,
            PersonalAbsorptionSourceProblem.GLUCOSE_AVAILABILITY,
            PersonalAbsorptionSourceProblem.INDEPENDENT_INSULIN_INTERVALS,
            PersonalAbsorptionSourceProblem.HISTORICAL_MODEL_CONTEXT,
            PersonalAbsorptionSourceProblem.CONTEXT_REVIEW,
            PersonalAbsorptionSourceProblem.SENSOR_IDENTITY,
            PersonalAbsorptionSourceProblem.CALIBRATION_IDENTITY)
    }

    @Test fun eachCanonicalLabelFieldMustMatchExactly() {
        val changes = listOf(meal.copy(therapyRevision = "r2"), meal.copy(grams = 36.0),
            meal.copy(timestamp = meal.timestamp.plusMillis(1)), meal.copy(portion = MealPortion.LARGE),
            meal.copy(profile = io.aaps.copilot.domain.profile.MealAbsorptionProfile.FAST),
            meal.copy(provenance = MealPortionProvenance.USER_CORRECTED),
            meal.copy(availableAt = meal.availableAt.plusMillis(1)))
        changes.forEach { changed ->
            val result = produce(frames = listOf(evidence.copy(input = evidence.input.copy(meal = changed))))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.MEAL_MISMATCH)
        }
    }

    @Test fun unboundEvidenceCannotCreateItsOwnGroundTruth() {
        val result = produce(meals = emptyList())
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.UNBOUND_MEAL)
    }

    @Test fun conflictingCanonicalRevisionsAndTombstonesDoNotSelectAWinner() {
        listOf(meal.copy(therapyRevision = "r2"), meal.copy(deleted = true)).forEach {
            val result = produce(meals = listOf(meal, it))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.CONFLICTING_MEAL)
        }
    }

    @Test fun duplicateEvidenceIsInvariantButConflictingPreparedValuesReject() {
        val duplicate = produce(meals = listOf(meal, meal), frames = listOf(evidence, evidence))
        assertThat(duplicate.episodes.single().sourceDigest).isEqualTo(produce().episodes.single().sourceDigest)
        val changed = evidence.copy(input = evidence.input.copy(context = evidence.input.context!!.copy(isfMmolPerUnit = 3.1)))
        val result = produce(frames = listOf(evidence, changed))
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.CONFLICTING_EVIDENCE)
    }

    @Test fun unknownOrInvalidHistoricalTimezoneIsNotReconstructedFromRequestZone() {
        listOf("", "missing-zone", "Europe/Berlin").forEach { zone ->
            val result = produce(frames = listOf(evidence.copy(input = evidence.input.copy(recordedZoneId = zone))))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.HISTORICAL_TIMEZONE)
        }
    }

    @Test fun sensorCalibrationAndReviewProvenanceAreRequired() {
        val cases = listOf(evidence.copy(sensorSessionId = null) to PersonalAbsorptionSourceProblem.SENSOR_IDENTITY,
            evidence.copy(calibrationIdentity = " ") to PersonalAbsorptionSourceProblem.CALIBRATION_IDENTITY,
            evidence.copy(contextReviewReference = null) to PersonalAbsorptionSourceProblem.CONTEXT_REVIEW,
            evidence.copy(sensorSessionId = "x".repeat(257)) to PersonalAbsorptionSourceProblem.SENSOR_IDENTITY)
        cases.forEach { (frame, reason) ->
            val result = produce(frames = listOf(frame))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().sourceProblems).contains(reason)
        }
    }

    @Test fun preparationMustFollowItsInputsAndBeKnownByCutoff() {
        listOf(0L, cutoff + 1, evidence.input.meal.availableAt.toEpochMilli()).forEach { preparedAt ->
            val result = produce(frames = listOf(evidence.copy(preparedAtMs = preparedAt)))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.PREPARATION_AVAILABILITY)
        }
    }

    @Test fun provenanceAndReviewAvailabilityArePartOfEpisodeIdentity() {
        val original = produce().episodes.single()
        val changed = produce(frames = listOf(evidence.copy(contextReviewReference = "review-2",
            preparedAtMs = evidence.preparedAtMs + 1))).episodes.single()
        assertThat(changed.sourceDigest).isNotEqualTo(original.sourceDigest)
        assertThat(changed.latestEvidenceAtMs).isEqualTo(evidence.preparedAtMs + 1)
    }

    @Test fun eachMissingInputIsReportedWithoutSilentlySettingZeroOrReviewed() {
        val input = evidence.input
        val cases = listOf(input.copy(glucose = emptyList()) to PersonalAbsorptionSourceProblem.GLUCOSE_AVAILABILITY,
            input.copy(glucose = input.glucose.map { it.copy(availableAtMs = null) }) to PersonalAbsorptionSourceProblem.GLUCOSE_AVAILABILITY,
            input.copy(insulin = emptyList()) to PersonalAbsorptionSourceProblem.INDEPENDENT_INSULIN_INTERVALS,
            input.copy(context = null) to PersonalAbsorptionSourceProblem.HISTORICAL_MODEL_CONTEXT,
            input.copy(reviewed = emptySet()) to PersonalAbsorptionSourceProblem.CONTEXT_REVIEW)
        cases.forEach { (changed, reason) ->
            val result = produce(frames = listOf(evidence.copy(input = changed)))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().sourceProblems).contains(reason)
        }
    }

    @Test fun retrospectiveOrUnknownGlucoseBasisCannotMasqueradeAsOriginalCgm() {
        for (basis in listOf(PersonalAbsorptionGlucoseBasis.UNKNOWN,
            PersonalAbsorptionGlucoseBasis.RETROSPECTIVELY_RECALCULATED)) {
            val result = produce(frames = listOf(evidence.copy(glucoseBasis = basis)))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.GLUCOSE_BASIS)
        }
    }

    @Test fun adapterStillRejectsBadInsulinAndCgmRatherThanWeakeningThresholds() {
        val input = evidence.input
        val cases = listOf(input.copy(insulin = input.insulin.drop(1)) to PersonalAbsorptionRejection.INSUFFICIENT_INSULIN,
            input.copy(insulin = input.insulin.map { it.copy(source = PersonalAbsorptionInsulinSource.UNKNOWN) }) to PersonalAbsorptionRejection.INVALID_INSULIN,
            input.copy(glucose = input.glucose.map { it.copy(quality = DataQuality.SENSOR_ERROR) }) to PersonalAbsorptionRejection.SENSOR_ERROR,
            input.copy(context = input.context!!.copy(isfMmolPerUnit = Double.NaN)) to PersonalAbsorptionRejection.INVALID_CONTEXT)
        cases.forEach { (changed, reason) ->
            val result = produce(frames = listOf(evidence.copy(input = changed)))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().preparationRejections).contains(reason)
        }
    }

    @Test fun everyExistingConfoundRemainsARejection() {
        PersonalAbsorptionConfound.entries.forEach { reason ->
            val result = produce(frames = listOf(evidence.copy(input = evidence.input.copy(confounds = setOf(reason)))))
            assertThat(result.episodes).isEmpty()
            assertThat(result.outcomes.single().preparationRejections).contains(PersonalAbsorptionRejection.valueOf(reason.name))
        }
    }

    @Test fun knownOverlappingMealCannotBeWaivedByPreparedReview() {
        val other = PersonalAbsorptionEpisodeFixtures.meal("meal-2", meal.timestamp.toEpochMilli() + 60_000)
        val result = produce(meals = listOf(meal, other))
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes.first().preparationRejections).contains(PersonalAbsorptionRejection.OVERLAPPING_MEAL)
    }

    @Test fun unfinishedDayAndEpisodeTailAreNotCompletedHistory() {
        val end = Instant.parse("2026-10-13T00:00:00Z").toEpochMilli()
        val today = PersonalAbsorptionEpisodeFixtures.meal(timestampMs = end)
        val late = PersonalAbsorptionEpisodeFixtures.meal(timestampMs = end - 60 * 60_000L)
        assertThat(produce(listOf(today), listOf(PersonalAbsorptionEpisodeFixtures.evidence(today)))
            .outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.OUTSIDE_WINDOW)
        val result = produce(listOf(late), listOf(PersonalAbsorptionEpisodeFixtures.evidence(late)))
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes.single().sourceProblems).contains(PersonalAbsorptionSourceProblem.INCOMPLETE_EPISODE)
    }

    @Test fun futureMealConfirmationIsNotKnownHistory() {
        val future = meal.copy(availableAt = Instant.ofEpochMilli(cutoff + 1))
        val result = produce(listOf(future), listOf(PersonalAbsorptionEpisodeFixtures.evidence(future)))
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes.single().preparationRejections).contains(PersonalAbsorptionRejection.FUTURE_EVIDENCE)
    }

    @Test fun quantitiesFromModelAndDeletedLabelsCannotTrain() {
        for (origin in MealPortionProvenance.entries.filter {
            it !in setOf(MealPortionProvenance.USER_ENTERED, MealPortionProvenance.USER_CORRECTED)
        }) {
            val changed = meal.copy(provenance = origin)
            assertThat(produce(listOf(changed), listOf(PersonalAbsorptionEpisodeFixtures.evidence(changed))).episodes).isEmpty()
        }
        assertThat(produce(listOf(meal.copy(deleted = true))).episodes).isEmpty()
    }

    @Test fun unavailableOrOverflowSourceNeverLooksLikeACompletePartialResult() {
        for (status in listOf(PersonalAbsorptionSourceStatus.UNAVAILABLE, PersonalAbsorptionSourceStatus.OVERFLOW)) {
            val result = produce(status = status)
            assertThat(result.status).isEqualTo(status)
            assertThat(result.episodes).isEmpty()
        }
        val frames = List(241) { evidence }
        assertThat(produce(frames = frames).status).isEqualTo(PersonalAbsorptionSourceStatus.OVERFLOW)
        assertThat(produce(meals = List(5001) { meal }).status).isEqualTo(PersonalAbsorptionSourceStatus.OVERFLOW)
    }

    @Test fun aggregateRawRowsAreBoundedBeforePreparation() {
        val manyGlucose = evidence.copy(input = evidence.input.copy(glucose = List(20_001) { evidence.input.glucose.first() }))
        val manyInsulin = evidence.copy(input = evidence.input.copy(insulin = List(60_001) { evidence.input.insulin.first() }))
        assertThat(produce(frames = listOf(manyGlucose)).status).isEqualTo(PersonalAbsorptionSourceStatus.OVERFLOW)
        assertThat(produce(frames = listOf(manyInsulin)).status).isEqualTo(PersonalAbsorptionSourceStatus.OVERFLOW)
    }

    @Test fun snapshotDoesNotRetainMutableCollectionsOrRawPayloads() {
        val points = evidence.input.glucose.toMutableList()
        val labels = mutableListOf(meal)
        val frames = mutableListOf(evidence.copy(input = evidence.input.copy(glucose = points)))
        val result = produce(labels, frames)
        val digest = result.episodes.single().sourceDigest
        points.clear(); labels.clear(); frames.clear()
        assertThat(result.episodes.single().sourceDigest).isEqualTo(digest)
        assertThat(result.episodes.single().steps).isNotEmpty()
        assertThrows(UnsupportedOperationException::class.java) { (result.outcomes as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.episodes as MutableList).clear() }
        val gaps = produce(frames = emptyList()).outcomes.single().sourceProblems
        assertThrows(UnsupportedOperationException::class.java) { (gaps as MutableSet).clear() }
    }

    @Test fun evidencePermutationDoesNotChangeEpisodeDigestsOrOrder() {
        val other = PersonalAbsorptionEpisodeFixtures.meal("meal-2", meal.timestamp.toEpochMilli() - 86_400_000L)
        val otherFrame = PersonalAbsorptionEpisodeFixtures.evidence(other)
        val first = produce(listOf(meal, other), listOf(evidence, otherFrame))
        val second = produce(listOf(other, meal), listOf(otherFrame, evidence))
        assertThat(first.episodes.map { it.sourceDigest }).containsExactlyElementsIn(second.episodes.map { it.sourceDigest }).inOrder()
        assertThat(first.outcomes.map { it.canonicalId }).containsExactly("meal-2", "meal-1").inOrder()
    }
}
