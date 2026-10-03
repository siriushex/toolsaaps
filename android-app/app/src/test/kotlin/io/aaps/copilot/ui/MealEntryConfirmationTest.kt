package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.nutrition.MealPortion
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.foundation.components.confirmedMealFromInput
import org.junit.Test

class MealEntryConfirmationTest {
    @Test fun distinguishesAcceptedSuggestionFromNumericCorrection() {
        fun confirm(value: String) = confirmedMealFromInput(
            value, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED,
            "", false, false, "meal-origin", 60.0, proposedGrams = 25.0
        )!!
        assertThat(confirm("25,00").provenance).isEqualTo(
            io.aaps.copilot.domain.nutrition.MealPortionProvenance.ACCEPTED_SUGGESTION)
        assertThat(confirm("26.25").provenance).isEqualTo(
            io.aaps.copilot.domain.nutrition.MealPortionProvenance.USER_CORRECTED)
        assertThat(meal("25")!!.provenance).isEqualTo(
            io.aaps.copilot.domain.nutrition.MealPortionProvenance.UNKNOWN)
    }
    private fun meal(grams: String, energy: String = "", visible: Boolean = false) =
        confirmedMealFromInput(grams, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED,
            energy, visible, true, "meal-test-1", 60.0)

    @Test fun parsesExactAmountAndIgnoresHiddenEnergy() {
        val value = meal("25,25", "old-invalid-value")!!
        assertThat(value.grams).isEqualTo(25.25)
        assertThat(value.energyKcal).isNull()
        assertThat(value.eatingSoon).isTrue()
        assertThat(value.submissionId).isEqualTo("meal-test-1")
        assertThat(value.portion).isEqualTo(MealPortion.MEDIUM)
        assertThat(meal("25", "540", true)!!.energyKcal).isEqualTo(540.0)
    }

    @Test fun rejectsInvalidOrOverCapAmountsWithoutClamping() {
        for (grams in listOf("", "NaN", "Infinity", "-1", "0", "80")) assertThat(meal(grams)).isNull()
        for (energy in listOf("NaN", "Infinity", "0", "-1", "10001")) {
            assertThat(meal("25", energy, true)).isNull()
        }
        assertThat(meal("60")).isNotNull()
        assertThat(meal("25", "", true)!!.energyKcal).isNull()
    }
}
