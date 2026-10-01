package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Test
import java.time.Instant

class MealPortionEvidenceTest {
    private val event = TherapyEvent(
        ts = 1000L, type = "carbs",
        payload = mapOf(
            "aapsCarbAmount" to "25.0", "aapsCarbIsValid" to "true",
            "aapsCarbClassification" to "AAPS_REAL", "aapsCarbSynthetic" to "false",
            "aapsCarbSuperseded" to "false"
        ),
        componentTrust = TherapyEventComponentTrust(canonicalCarbId = 41L, canonicalCarbRevision = "r1")
    )
    private val evidence = MealPortionEvidence(
        "41", "r1", 25.0, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED,
        MealPortionProvenance.USER_CORRECTED, Instant.ofEpochMilli(1000)
    )

    @Test fun matchedIndependentEvidenceCreatesObservation() {
        val result = event.toPortionObservation(evidence)
        assertThat(result?.grams).isEqualTo(25.0)
        assertThat(result?.canonicalId).isEqualTo("41")
        assertThat(result?.portion).isEqualTo(MealPortion.MEDIUM)
    }

    @Test fun absentStaleOrChangedEvidenceIsRejected() {
        assertThat(event.toPortionObservation(null)).isNull()
        assertThat(event.toPortionObservation(evidence.copy(therapyRevision = "r0"))).isNull()
        assertThat(event.toPortionObservation(evidence.copy(canonicalId = "42"))).isNull()
        assertThat(event.toPortionObservation(evidence.copy(confirmedGrams = 25.1))).isNull()
    }

    @Test fun acceptedSuggestionsAndUnknownOriginCannotBecomeLabels() {
        MealPortionProvenance.entries.filter {
            it !in setOf(MealPortionProvenance.USER_ENTERED, MealPortionProvenance.USER_CORRECTED)
        }.forEach { origin ->
            assertThat(event.toPortionObservation(evidence.copy(provenance = origin))).isNull()
        }
    }

    @Test fun invalidSyntheticConflictingAndUntrustedTherapyIsRejected() {
        assertThat(event.copy(componentTrust = TherapyEventComponentTrust.NONE).toPortionObservation(evidence)).isNull()
        listOf("aapsCarbIsValid" to "false", "aapsCarbSynthetic" to "true", "aapsCarbSuperseded" to "true").forEach {
            assertThat(event.copy(payload = event.payload + it).toPortionObservation(evidence)).isNull()
        }
        assertThat(event.copy(componentTrust = TherapyEventComponentTrust(
            canonicalCarbId = 41L, canonicalCarbRevision = "r1", canonicalSemanticConflict = true
        )).toPortionObservation(evidence)).isNull()
    }

    @Test fun observationKeepsActualConfirmationTimeAndTherapyRevision() {
        val result = event.toPortionObservation(evidence.copy(confirmedAt = Instant.ofEpochMilli(3000)))
        assertThat(result?.availableAt).isEqualTo(Instant.ofEpochMilli(3000))
        assertThat(result?.therapyRevision).isEqualTo("r1")
        assertThat(event.toPortionObservation(evidence.copy(confirmedAt = Instant.ofEpochMilli(999)))).isNull()
    }

    @Test fun unknownConfirmationTimeCannotBeInventedFromMealTimestamp() {
        assertThat(event.toPortionObservation(evidence.copy(confirmedAt = null))).isNull()
    }

    @Test fun explicitLearningSuppressionExcludesOtherwiseRealIndependentMeal() {
        val suppressed = event.copy(payload = event.payload + ("copilotLearningCarbsSuppressed" to "true"))
        assertThat(suppressed.toPortionObservation(evidence)).isNull()
    }
}
