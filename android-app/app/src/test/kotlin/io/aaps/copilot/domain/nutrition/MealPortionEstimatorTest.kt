package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class MealPortionEstimatorTest {
    private val now = Instant.parse("2026-09-27T12:00:00Z")
    private val estimator = MealPortionEstimator(Clock.fixed(now, ZoneOffset.UTC))
    private val settings = MealPortionSettings()
    private fun history(days: Int = 5) = (1..days).map { day ->
        MealPortionObservation(
            canonicalId = "meal-$day",
            timestamp = now.minusSeconds(day * 86_400L),
            grams = 35.0,
            profile = MealAbsorptionProfile.MIXED,
            provenance = MealPortionProvenance.USER_ENTERED
        )
    }
    private fun estimate(rows: List<MealPortionObservation>) = estimator.estimate(
        rows, settings, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED, ZoneOffset.UTC
    )

    @Test fun insufficientDaysUsesConfiguredDefault() {
        val result = estimate(history(4))
        assertThat(result.grams).isEqualTo(25.0)
        assertThat(result.fromHistory).isFalse()
    }

    @Test fun comparableIndependentMealsBlendTowardHistoryWithoutChangingSettings() {
        val result = estimate(history())
        assertThat(result.grams).isEqualTo(30.0)
        assertThat(result.fromHistory).isTrue()
        assertThat(result.supportMeals).isEqualTo(5)
        assertThat(settings.medium.defaultGrams).isEqualTo(25.0)
        assertThat(estimate(history().reversed())).isEqualTo(result)
    }

    @Test fun excludedOriginsNeverTeachTheEstimator() {
        MealPortionProvenance.entries.filter {
            it != MealPortionProvenance.USER_ENTERED && it != MealPortionProvenance.USER_CORRECTED
        }.forEach { origin ->
            assertThat(estimate(history().map { it.copy(provenance = origin) }).fromHistory).isFalse()
        }
        assertThat(estimate(history().map { it.copy(deleted = true) }).fromHistory).isFalse()
        assertThat(estimate(history().map { it.copy(grams = Double.NaN) }).fromHistory).isFalse()
    }

    @Test fun duplicatesFutureAndExpiredRecordsDoNotAddSupport() {
        val base = history(4)
        val extra = listOf(
            base.first(),
            base.first().copy(canonicalId = "future", timestamp = now.plusSeconds(60)),
            base.first().copy(canonicalId = "expired", timestamp = now.minusSeconds(15 * 86_400L))
        )
        assertThat(estimate(base + extra).fromHistory).isFalse()
        assertThat(estimate(history() + history()).supportMeals).isEqualTo(5)
        assertThat(estimate(history() + history().first().copy(deleted = true)).fromHistory).isFalse()
    }

    @Test fun otherProfilesAndDistantTimesDoNotSupplyComparableSupport() {
        assertThat(estimate(history().map { it.copy(profile = MealAbsorptionProfile.FAST) }).fromHistory).isFalse()
        assertThat(estimate(history().map { it.copy(timestamp = it.timestamp.minusSeconds(5 * 3600)) }).fromHistory).isFalse()
    }

    @Test fun sharedBoundaryBelongsToLargerCategoryUnlessExplicitlyRecorded() {
        assertThat(estimate(history().map { it.copy(grams = 40.0) }).fromHistory).isFalse()
        val explicit = history().map { it.copy(grams = 40.0, portion = MealPortion.MEDIUM) }
        assertThat(estimate(explicit).fromHistory).isTrue()
    }

    @Test fun localTimeComparisonWrapsAtMidnight() {
        val midnight = Instant.parse("2026-09-27T00:20:00Z")
        val rows = history().mapIndexed { index, row ->
            row.copy(timestamp = midnight.minusSeconds((index + 1) * 86_400L + 40 * 60))
        }
        val result = MealPortionEstimator(Clock.fixed(midnight, ZoneOffset.UTC)).estimate(
            rows, settings, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED, ZoneId.of("UTC")
        )
        assertThat(result.fromHistory).isTrue()
    }

    @Test fun manyMealsOnOneDayCannotReplaceIndependentDaySupport() {
        val distant = history().map { it.copy(timestamp = it.timestamp.minusSeconds(5 * 3600)) }
        val sameDay = (1..8).map { index ->
            history().first().copy(canonicalId = "same-day-$index")
        }
        val result = estimate(distant + sameDay)
        assertThat(result.fromHistory).isFalse()
        assertThat(result.supportDays).isEqualTo(1)
    }

    @Test fun repeatedDstHourDoesNotCountOneMealOrDayTwice() {
        val autumn = Instant.parse("2026-11-02T00:30:00Z")
        val row = history().first().copy(timestamp = Instant.parse("2026-10-25T00:30:00Z"),
            availableAt = Instant.parse("2026-10-25T00:30:00Z"))
        val repeatedHour = row.copy(canonicalId = "second", timestamp = Instant.parse("2026-10-25T01:30:00Z"),
            availableAt = Instant.parse("2026-10-25T01:30:00Z"))
        val result = MealPortionEstimator(Clock.fixed(autumn, ZoneOffset.UTC)).estimate(
            listOf(row, row, repeatedHour), settings, MealPortion.MEDIUM,
            MealAbsorptionProfile.MIXED, ZoneId.of("Europe/Berlin")
        )
        assertThat(result.supportDays).isEqualTo(1)
        assertThat(result.supportMeals).isEqualTo(2)
        assertThat(result.fromHistory).isFalse()
    }

    @Test fun futureConfirmationCannotSupplyHistoricalSupport() {
        val rows = history().map { it.copy(availableAt = now.plusSeconds(60)) }
        assertThat(estimate(rows).fromHistory).isFalse()
        assertThat(estimate(rows).supportMeals).isEqualTo(0)
    }

    @Test fun confirmationBeforeMealIsNotUsableEvidence() {
        val rows = history().map { it.copy(availableAt = it.timestamp.minusSeconds(1)) }
        assertThat(estimate(rows).supportMeals).isEqualTo(0)
    }
}
