package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertThrows
import org.junit.Test

class MealQuantityPriorTest {
    private val now = Instant.parse("2026-07-20T12:00:00Z")
    private val estimator = MealPortionEstimator(Clock.fixed(now, ZoneOffset.UTC))
    private val settings = MealPortionSettings()
    private fun rows() = listOf(20.0, 25.0, 30.0, 35.0, 40.0).mapIndexed { index, grams ->
        MealPortionObservation("declared-$index", now.minusSeconds((index + 1) * 86400L), grams,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_ENTERED, MealPortion.MEDIUM)
    }
    private fun prior(rows: List<MealPortionObservation> = rows()) = estimator.describePrior(
        rows, settings, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED, ZoneOffset.UTC)

    @Test fun empiricalDeclaredRangeIsNotActualMassPrecision() {
        val result = prior()
        assertThat(result.estimate).isEqualTo(estimator.estimate(rows(), settings, MealPortion.MEDIUM,
            MealAbsorptionProfile.MIXED, ZoneOffset.UTC))
        assertThat(result.declaredP10Grams).isEqualTo(22.0)
        assertThat(result.declaredP90Grams).isEqualTo(38.0)
        assertThat(result.actualMassAccuracyValidated).isFalse()
        assertThat(result.clinicalInfluenceAllowed).isFalse()
        assertThat(result).isEqualTo(prior(rows().reversed()))
    }

    @Test fun insufficientSupportDoesNotInventAnEmpiricalRange() {
        val result = prior(rows().take(4))
        assertThat(result.estimate.fromHistory).isFalse()
        assertThat(result.declaredP10Grams).isNull()
        assertThat(result.declaredP90Grams).isNull()
    }

    @Test fun syntheticAndAcceptedSuggestionsCannotNarrowUncertainty() {
        for (origin in listOf(MealPortionProvenance.SYNTHETIC_UAM,
            MealPortionProvenance.ACCEPTED_SUGGESTION, MealPortionProvenance.RESCUE)) {
            val extra = (1..100).map { rows().first().copy(canonicalId = "synthetic-$it",
                grams = 25.0, provenance = origin) }
            assertThat(prior(rows() + extra)).isEqualTo(prior())
        }
    }

    @Test fun overflowIsNotSilentSelectionOfAConvenientSubset() {
        val result = prior(List(5001) { rows().first() })
        assertThat(result.inputOverflow).isTrue()
        assertThat(result.estimate.fromHistory).isFalse()
        assertThat(result.declaredP10Grams).isNull()
    }

    @Test fun selectedMealHourDoesNotShiftCutoffOrChangeDefaultCallBehavior() {
        val breakfast = rows().map { it.copy(timestamp = it.timestamp.minusSeconds(3 * 3600),
            availableAt = it.availableAt.minusSeconds(3 * 3600)) }
        assertThat(prior(breakfast).estimate.fromHistory).isFalse()
        val selected = estimator.describePrior(breakfast, settings, MealPortion.MEDIUM,
            MealAbsorptionProfile.MIXED, ZoneOffset.UTC, 9 * 60)
        assertThat(selected.estimate.fromHistory).isTrue()
        val notYetKnown = breakfast.first().copy(availableAt = now.plusSeconds(1))
        assertThat(estimator.describePrior(breakfast.drop(1) + notYetKnown, settings, MealPortion.MEDIUM,
            MealAbsorptionProfile.MIXED, ZoneOffset.UTC, 9 * 60).estimate.fromHistory).isFalse()
        assertThrows(IllegalArgumentException::class.java) {
            estimator.describePrior(breakfast, settings, MealPortion.MEDIUM,
                MealAbsorptionProfile.MIXED, ZoneOffset.UTC, 1440)
        }
    }
}
