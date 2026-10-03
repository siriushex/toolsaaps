package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

class MealPortionValidationTest {
    private val targetTime = Instant.parse("2026-10-01T12:00:00Z")
    private val validator = MealPortionValidation(Clock.fixed(targetTime.plusSeconds(3600), ZoneOffset.UTC))
    private val settings = MealPortionSettings()
    private fun meal(id: String, at: Instant, grams: Double = 35.0) = MealPortionObservation(
        id, at, grams, MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_CORRECTED,
        MealPortion.MEDIUM, therapyRevision = "r1"
    )
    private fun training() = (1..5).map { meal("past-$it", targetTime.minusSeconds(it * 86400L)) }
    private fun evaluate(rows: List<MealPortionObservation>) = validator.evaluate(
        rows, settings, ZoneOffset.UTC, targetTime
    )

    @Test fun walkForwardUsesOnlyPastLabelsAndComparesFixedPreset() {
        val result = evaluate(training() + meal("target", targetTime))
        assertThat(result.total?.meals).isEqualTo(1)
        assertThat(result.total?.historyPredictions).isEqualTo(1)
        assertThat(result.total?.candidateMaeGrams).isEqualTo(5.0)
        assertThat(result.total?.presetMaeGrams).isEqualTo(10.0)
        assertThat(result.byPortion[MealPortion.MEDIUM]).isEqualTo(result.total)
        assertThat(result.byLocalHourBlock[2]).isEqualTo(result.total)
    }

    @Test fun delayedConfirmationCannotLeakIntoEarlierPrediction() {
        val delayed = training().map { it.copy(availableAt = targetTime.plusSeconds(1)) }
        val result = evaluate(delayed + meal("target", targetTime))
        assertThat(result.total?.historyPredictions).isEqualTo(0)
        assertThat(result.total?.candidateMaeGrams).isEqualTo(result.total?.presetMaeGrams)
    }

    @Test fun positiveOverestimationIsReportedEvenWhenCandidateIsWorse() {
        val result = evaluate(training() + meal("target", targetTime, 20.0))
        assertThat(result.total?.candidateMaeGrams).isEqualTo(10.0)
        assertThat(result.total?.presetMaeGrams).isEqualTo(5.0)
        assertThat(result.total?.candidateMeanOverestimateGrams).isEqualTo(10.0)
        assertThat(result.total?.presetMeanOverestimateGrams).isEqualTo(5.0)
    }

    @Test fun unknownCategoryAndAcceptedOrFutureLabelsAreNotValidationTargets() {
        val target = meal("target", targetTime)
        val rows = training() + listOf(target.copy(portion = null),
            target.copy(canonicalId = "accepted", provenance = MealPortionProvenance.ACCEPTED_SUGGESTION),
            target.copy(canonicalId = "future", availableAt = targetTime.plusSeconds(7200)))
        assertThat(evaluate(rows).total).isNull()
    }

    @Test fun duplicateOrDeletedIdentityDoesNotInflateValidationCount() {
        val target = meal("target", targetTime)
        assertThat(evaluate(training() + target + target).total?.meals).isEqualTo(1)
        assertThat(evaluate(training() + target + target.copy(deleted = true)).total).isNull()
    }

    @Test fun noRealLabelsProducesNoInventedScores() {
        val result = evaluate(emptyList())
        assertThat(result.total).isNull()
        assertThat(result.byPortion).isEmpty()
        assertThat(result.byLocalHourBlock).isEmpty()
    }
}
