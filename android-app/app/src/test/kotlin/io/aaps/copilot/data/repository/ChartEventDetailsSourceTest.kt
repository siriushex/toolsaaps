package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.LocalEventImpactAnalyzer
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class ChartEventDetailsSourceTest {
    private val repository = EventTimelineRepository()
    private fun check() = BloodGlucoseCheck("check-1", 10_000L, 6.2, "mmol/L", "MANUAL",
        "meter check", 10_001L, "session-1", 9_000L, 5.8, BloodGlucoseCheckStatus.VALID, null)

    @Test fun bloodAndMatchedCgmRemainAvailableToEventDetails() {
        val event = repository.calibrationEvents(listOf(check())).single()
        assertThat(event.attributes["bloodMmol"]).isEqualTo("6.2")
        assertThat(event.attributes["matchedCgmMmol"]).isEqualTo("5.8")
        assertThat(event.attributes["matchedCgmTs"]).isEqualTo("9000")
        assertThat(event.attributes).doesNotContainKey("sensorSessionKey")
    }

    @Test fun displayEnrichmentPreservesClinicalImpactForEveryCheckStatus() {
        BloodGlucoseCheckStatus.entries.forEach { status ->
            val event = repository.calibrationEvents(listOf(check().copy(status = status))).single()
            val original = event.copy(attributes = event.attributes.filterKeys {
                it == "status" || it == "trustPenalty"
            })
            assertThat(LocalEventImpactAnalyzer().impact(event)).isEqualTo(LocalEventImpactAnalyzer().impact(original))
            assertThat(event.attributes["status"]).isEqualTo(status.name)
        }
    }

    @Test fun missingOrNonfiniteValuesAreNotInvented() {
        val event = repository.calibrationEvents(listOf(check().copy(mmol = Double.NaN,
            matchedRawGlucose = Double.POSITIVE_INFINITY, lagAlignedTs = null))).single()
        assertThat(event.attributes).doesNotContainKey("bloodMmol")
        assertThat(event.attributes).doesNotContainKey("matchedCgmMmol")
        assertThat(event.attributes).doesNotContainKey("matchedCgmTs")
    }

    @Test fun mealDetailsUseOriginalCanonicalSemanticsAndRecordedInsulin() {
        val payload = mapOf("grams" to "60", "aapsCarbAmount" to "32", "aapsCarbIsValid" to "true",
            "aapsCarbClassification" to "AAPS_REAL", "units" to "1.4", "profile" to "MIXED")
        val event = requireNotNull(repository.prepareTherapyEvent(TherapyEvent(10_000, "meal_bolus", payload)))
        assertThat(event.attributes["displayCarbsG"]).isEqualTo("32.0")
        assertThat(event.attributes["displayInsulinU"]).isEqualTo("1.4")
        assertThat(event.attributes).containsAtLeastEntriesIn(payload)
        assertThat(LocalEventImpactAnalyzer().impact(event)).isEqualTo(
            LocalEventImpactAnalyzer().impact(event.copy(attributes = payload)))
    }

    @Test fun fullAttributeBudgetNeverEvictsClinicalKeysForDisplay() {
        val payload = (1..22).associate { "extra$it" to "value" } + mapOf("grams" to "24", "profile" to "FAST")
        val event = requireNotNull(repository.prepareTherapyEvent(TherapyEvent(10_000, "meal", payload)))
        assertThat(event.attributes).containsExactlyEntriesIn(payload)
    }

    @Test fun ambiguousQuantitiesAndUntrustedDisplayKeysDoNotBecomeNumbers() {
        val payload = mapOf("grams" to "24", "carbs" to "48", "displayCarbsG" to "500",
            "displayInsulinU" to "50")
        val event = requireNotNull(repository.prepareTherapyEvent(TherapyEvent(10_000, "meal", payload)))
        assertThat(event.attributes).doesNotContainKey("displayCarbsG")
        assertThat(event.attributes).doesNotContainKey("displayInsulinU")
        assertThat(event.attributes["grams"]).isEqualTo("24")
    }
}
