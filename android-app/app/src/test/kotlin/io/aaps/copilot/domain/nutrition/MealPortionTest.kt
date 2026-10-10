package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MealPortionTest {
    @Test fun defaultsAndSharedBoundaries() {
        val settings = MealPortionSettings()
        assertThat(settings.isValid()).isTrue()
        assertThat(settings.showCalories).isFalse()
        assertThat(settings.small).isEqualTo(MealPortionRange(7.0, 15.0, 10.0))
        assertThat(settings.medium).isEqualTo(MealPortionRange(15.0, 40.0, 25.0))
        assertThat(settings.large).isEqualTo(MealPortionRange(40.0, 80.0, 60.0))
        assertThat(settings.classifyHistoricalGrams(14.9)).isEqualTo(MealPortion.SMALL)
        assertThat(settings.classifyHistoricalGrams(15.0)).isEqualTo(MealPortion.MEDIUM)
        assertThat(settings.classifyHistoricalGrams(40.0)).isEqualTo(MealPortion.LARGE)
        assertThat(settings.classifyHistoricalGrams(80.0)).isEqualTo(MealPortion.LARGE)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 81.0)) {
            assertThat(settings.classifyHistoricalGrams(value)).isNull()
        }
    }

    @Test fun rejectsNonFiniteReversedOverlappingAndOutOfRangeDefaults() {
        val settings = MealPortionSettings()
        val badRanges = listOf(
            MealPortionRange(Double.NaN, 15.0, 10.0),
            MealPortionRange(7.0, Double.POSITIVE_INFINITY, 10.0),
            MealPortionRange(7.0, 15.0, Double.NaN),
            MealPortionRange(0.0, 15.0, 10.0),
            MealPortionRange(15.0, 7.0, 10.0),
            MealPortionRange(7.0, 7.0, 7.0),
            MealPortionRange(7.0, 15.0, 16.0),
            MealPortionRange(7.0, 15.0, 6.0)
        )
        for (range in badRanges) assertThat(settings.copy(small = range).isValid()).isFalse()
        assertThat(settings.copy(small = MealPortionRange(7.0, 16.0, 10.0)).isValid()).isFalse()
        assertThat(settings.copy(medium = MealPortionRange(15.0, 41.0, 25.0)).isValid()).isFalse()
    }

    @Test fun configuredBoundariesDetermineClassificationWithoutClamping() {
        val settings = MealPortionSettings(
            small = MealPortionRange(5.0, 12.0, 8.0),
            medium = MealPortionRange(12.0, 30.0, 20.0),
            large = MealPortionRange(30.0, 70.0, 50.0)
        )
        assertThat(settings.isValid()).isTrue()
        assertThat(settings.range(MealPortion.LARGE).defaultGrams).isEqualTo(50.0)
        assertThat(settings.classifyHistoricalGrams(12.0)).isEqualTo(MealPortion.MEDIUM)
        assertThat(settings.classifyHistoricalGrams(30.0)).isEqualTo(MealPortion.LARGE)
        assertThat(settings.classifyHistoricalGrams(4.0)).isNull()
    }
}
