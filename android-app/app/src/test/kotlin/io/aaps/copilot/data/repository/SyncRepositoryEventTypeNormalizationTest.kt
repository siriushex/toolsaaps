package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncRepositoryEventTypeNormalizationTest {

    @Test
    fun correctionBolusWithoutDoseDowngradesToTreatment() {
        val type = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Correction Bolus",
            payload = emptyMap()
        )

        assertThat(type).isEqualTo("treatment")
    }

    @Test
    fun correctionBolusWithDoseRemainsCorrectionBolus() {
        val type = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Correction Bolus",
            payload = mapOf("insulin" to "1.2")
        )

        assertThat(type).isEqualTo("correction_bolus")
    }

    @Test
    fun inferredIobJumpWithoutDoseRemainsCorrectionBolus() {
        val type = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Correction Bolus",
            payload = mapOf(
                "inferred" to "true",
                "method" to "iob_jump"
            )
        )

        assertThat(type).isEqualTo("correction_bolus")
    }

    @Test
    fun mealBolusReroutesByPayload() {
        val full = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Meal Bolus",
            payload = mapOf("insulin" to "1.5", "carbs" to "20")
        )
        val insulinOnly = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Meal Bolus",
            payload = mapOf("insulin" to "1.5")
        )
        val carbsOnly = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Meal Bolus",
            payload = mapOf("carbs" to "20")
        )
        val empty = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Meal Bolus",
            payload = emptyMap()
        )

        assertThat(full).isEqualTo("meal_bolus")
        assertThat(insulinOnly).isEqualTo("correction_bolus")
        assertThat(carbsOnly).isEqualTo("carbs")
        assertThat(empty).isEqualTo("treatment")
    }

    @Test
    fun carbCorrectionWithoutCarbsDowngradesToTreatment() {
        val carbs = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Carb Correction",
            payload = mapOf("carbs" to "12")
        )
        val empty = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Carb Correction",
            payload = emptyMap()
        )

        assertThat(carbs).isEqualTo("carbs")
        assertThat(empty).isEqualTo("treatment")
    }
}
