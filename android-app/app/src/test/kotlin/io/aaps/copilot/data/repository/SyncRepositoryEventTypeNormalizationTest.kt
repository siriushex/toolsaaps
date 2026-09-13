package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatment
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import org.junit.Test
import java.time.Instant

class SyncRepositoryEventTypeNormalizationTest {

    @Test
    fun createdAtBackfillUsesItsOwnClientWindow() {
        val oldTreatment = NightscoutTreatment(
            id = "old-bolus",
            eventType = "Bolus Wizard",
            createdAt = "2026-03-10T12:00:00Z",
            enteredInsulin = 2.4,
            enteredCarbs = 24.0
        )

        val selection = SyncRepository.selectTreatmentsWithinClientWindowStatic(
            treatmentsByDate = emptyList(),
            treatmentsByCreatedAt = listOf(oldTreatment),
            treatmentQuerySince = Instant.parse("2026-03-11T10:00:00Z").toEpochMilli(),
            treatmentCreatedAtQuerySince = Instant.parse("2026-02-09T10:00:00Z").toEpochMilli()
        )

        assertThat(selection.skippedByClientWindow).isEqualTo(0)
        assertThat(selection.treatments).hasSize(1)
        assertThat(selection.treatments.single().id).isEqualTo("old-bolus")
    }

    @Test
    fun dateFetchStillUsesIncrementalClientWindow() {
        val oldTreatment = NightscoutTreatment(
            id = "old-date-bolus",
            eventType = "Bolus",
            createdAt = "2026-03-10T12:00:00Z",
            enteredInsulin = 1.2
        )

        val selection = SyncRepository.selectTreatmentsWithinClientWindowStatic(
            treatmentsByDate = listOf(oldTreatment),
            treatmentsByCreatedAt = emptyList(),
            treatmentQuerySince = Instant.parse("2026-03-11T10:00:00Z").toEpochMilli(),
            treatmentCreatedAtQuerySince = Instant.parse("2026-02-09T10:00:00Z").toEpochMilli()
        )

        assertThat(selection.skippedByClientWindow).isEqualTo(1)
        assertThat(selection.treatments).isEmpty()
    }

    @Test
    fun dateFetchCanSignalCreatedAtRecoveryForOlderRelevantTreatments() {
        val oldTreatment = NightscoutTreatment(
            id = "old-carb-correction",
            eventType = "Carb Correction",
            createdAt = "2026-03-10T12:00:00Z",
            enteredInsulin = 1.2,
            enteredCarbs = 12.0
        )

        val needsRecovery = SyncRepository.hasRecoveryRelevantTreatmentsOutsideClientWindowStatic(
            treatments = listOf(oldTreatment),
            clientWindowSince = Instant.parse("2026-03-11T10:00:00Z").toEpochMilli(),
            source = "nightscout_treatment"
        )

        assertThat(needsRecovery).isTrue()
    }

    @Test
    fun therapyHistorySourceModeBecomesSparseWhenRealHistoryIsUsableButStillThin() {
        val mode = SyncRepository.therapyHistorySourceModeStatic(
            rawCount = 45,
            inferredCount = 30,
            realFetchedCount = 4,
            recoveredCount = 0,
            usableCount = 4
        )

        assertThat(mode).isEqualTo("SPARSE_REAL_FETCHED")
    }

    @Test
    fun therapyHistorySourceModeStaysSparseWhenRecoveredRowsInflateUsableCount() {
        val mode = SyncRepository.therapyHistorySourceModeStatic(
            rawCount = 45,
            inferredCount = 30,
            realFetchedCount = 6,
            recoveredCount = 12,
            usableCount = 18
        )

        assertThat(mode).isEqualTo("SPARSE_REAL_FETCHED")
    }

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

    @Test
    fun legacyIobInferenceIsReclassifiedAwayFromNightscoutTreatment() {
        val payload = SyncRepository.normalizeNightscoutFetchedPayloadStatic(
            eventId = "iob-inf-123-42",
            payload = mapOf(
                "source" to "nightscout_treatment",
                "eventType" to "Correction Bolus",
                "inferred" to "true",
                "method" to "iob_jump",
                "insulin" to "1.2"
            ),
            defaultSource = "nightscout_treatment"
        )

        assertThat(payload["source"]).isEqualTo("aaps_ns_iob")
        assertThat(payload["classification"]).isEqualTo("INFERRED_IOB")
        assertThat(payload["inferred"]).isEqualTo("true")
        assertThat(payload["method"]).isEqualTo("iob_jump")
    }

    @Test
    fun uamTaggedPayloadIsClassifiedAsSynthetic() {
        val payload = SyncRepository.normalizeNightscoutFetchedPayloadStatic(
            eventId = "ns-uam-1",
            payload = mapOf(
                "source" to "nightscout_treatment",
                "eventType" to "Carb Correction",
                "notes" to "UAM_ENGINE|id=abc|seq=1|ver=1|mode=NORMAL|",
                "carbs" to "18"
            ),
            defaultSource = "nightscout_treatment"
        )

        assertThat(payload["source"]).isEqualTo("uam_engine")
        assertThat(payload["classification"]).isEqualTo("UAM_SYNTHETIC")
        assertThat(payload["synthetic"]).isEqualTo("true")
    }

    @Test
    fun normalFetchedBolusRemainsRealFetched() {
        val payload = SyncRepository.normalizeNightscoutFetchedPayloadStatic(
            eventId = "ns-123",
            payload = mapOf(
                "source" to "nightscout_treatment",
                "eventType" to "Bolus Wizard",
                "enteredInsulin" to "2.4",
                "enteredCarbs" to "24"
            ),
            defaultSource = "nightscout_treatment"
        )

        assertThat(payload["source"]).isEqualTo("nightscout_treatment")
        assertThat(payload["classification"]).isEqualTo("REAL_FETCHED")
    }

    @Test
    fun localNightscoutEchoIsClassifiedAsLocalAction() {
        val payload = SyncRepository.normalizeNightscoutFetchedPayloadStatic(
            eventId = "local-ns-123",
            payload = mapOf(
                "source" to "nightscout_treatment",
                "eventType" to "Temporary Target",
                "notes" to "copilot:adaptive-temp-target"
            ),
            defaultSource = "nightscout_treatment"
        )

        assertThat(payload["source"]).isEqualTo("local_nightscout_treatment")
        assertThat(payload["classification"]).isEqualTo("LOCAL_ACTION")
    }

    @Test
    fun treatmentHistoryRepairRunsOnlyWhenThereIsRelevantWork() {
        assertThat(
            SyncRepository.shouldRunNightscoutTreatmentRepairStatic(
                importedTreatmentCount = 0,
                historicalBootstrap = false,
                legacyCandidateCount = 0
            )
        ).isFalse()
        assertThat(
            SyncRepository.shouldRunNightscoutTreatmentRepairStatic(
                importedTreatmentCount = 1,
                historicalBootstrap = false,
                legacyCandidateCount = 0
            )
        ).isTrue()
        assertThat(
            SyncRepository.shouldRunNightscoutTreatmentRepairStatic(
                importedTreatmentCount = 0,
                historicalBootstrap = true,
                legacyCandidateCount = 0
            )
        ).isTrue()
        assertThat(
            SyncRepository.shouldRunNightscoutTreatmentRepairStatic(
                importedTreatmentCount = 0,
                historicalBootstrap = false,
                legacyCandidateCount = 1
            )
        ).isTrue()
    }
}
