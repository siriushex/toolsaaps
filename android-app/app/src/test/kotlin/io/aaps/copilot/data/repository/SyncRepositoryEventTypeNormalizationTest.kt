package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatment
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
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

    @Test
    fun carbCorrectionWithInsulinAndCarbsBecomesMealBolus() {
        val type = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Carb Correction",
            payload = mapOf(
                "carbs" to "24",
                "insulin" to "2.4"
            )
        )

        assertThat(type).isEqualTo("meal_bolus")
    }

    @Test
    fun plainBolusUsesPayloadToBecomeInsulinLike() {
        val correction = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Bolus",
            payload = mapOf("insulin" to "1.1")
        )
        val meal = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Bolus",
            payload = mapOf("insulin" to "2.0", "carbs" to "20")
        )

        assertThat(correction).isEqualTo("correction_bolus")
        assertThat(meal).isEqualTo("meal_bolus")
    }

    @Test
    fun bolusWizardFallsBackToPayloadClassification() {
        val type = SyncRepository.normalizeTreatmentTypeStatic(
            eventType = "Bolus Wizard",
            payload = mapOf(
                "enteredCarbs" to "18",
                "enteredInsulin" to "1.8"
            )
        )

        assertThat(type).isEqualTo("meal_bolus")
    }

    @Test
    fun treatmentPayloadBuilderPreservesEnteredBolusWizardFields() {
        val payload = SyncRepository.buildNightscoutTreatmentPayloadStatic(
            treatment = NightscoutTreatment(
                eventType = "Bolus Wizard",
                enteredCarbs = 18.0,
                enteredInsulin = 1.8
            ),
            source = "nightscout_treatment"
        )

        assertThat(payload["enteredCarbs"]).isEqualTo("18.0")
        assertThat(payload["mealCarbs"]).isEqualTo("18.0")
        assertThat(payload["enteredInsulin"]).isEqualTo("1.8")
        assertThat(payload["bolusUnits"]).isEqualTo("1.8")
        assertThat(SyncRepository.normalizeTreatmentTypeStatic("Bolus Wizard", payload))
            .isEqualTo("meal_bolus")
    }

    @Test
    fun treatmentRequestPayloadBuilderPreservesEnteredInsulinForBolus() {
        val payload = SyncRepository.buildNightscoutTreatmentPayloadStatic(
            request = NightscoutTreatmentRequest(
                eventType = "Bolus",
                enteredInsulin = 2.3
            ),
            source = "local_nightscout_treatment"
        )

        assertThat(payload["enteredInsulin"]).isEqualTo("2.3")
        assertThat(payload["bolusUnits"]).isEqualTo("2.3")
        assertThat(SyncRepository.normalizeTreatmentTypeStatic("Bolus", payload))
            .isEqualTo("correction_bolus")
    }
}
