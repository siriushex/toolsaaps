package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.*
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import org.junit.Test

class ChartEventDetailsUiTest {
    private fun event(type: CompensationEventType = CompensationEventType.MEAL) =
        CompensationEvent("event-1", 10_000, 3_610_000, type, title = "meal_bolus")

    @Test fun mealShowsRecordedQuantityWithoutClaimingAbsorption() {
        val result = event().copy(attributes = mapOf("grams" to "24,5", "profile" to "MIXED"))
            .toChartEventDetails(4_000_000)
        assertThat(result.carbsGrams).isEqualTo(24.5)
        assertThat(result.mealProfile).isEqualTo(MealAbsorptionProfile.MIXED)
        assertThat(result.title).isNull()
        assertThat(result.contextEndTs).isEqualTo(3_610_000L)
        assertThat(result.lifecycle).isEqualTo(CompensationEventLifecycle.ELAPSED)
    }

    @Test fun ambiguousMissingAndNonfiniteCarbsStayUnknown() {
        listOf(emptyMap(), mapOf("carbs" to "NaN"), mapOf("grams" to "24", "carbs" to "48"),
            mapOf("carbs" to "24", "CARBS" to "48")).forEach { attributes ->
            assertThat(event().copy(attributes = attributes).toChartEventDetails(10_000).carbsGrams).isNull()
        }
    }

    @Test fun canonicalCarbsDoNotUseLegacyAmountOrInvalidRecord() {
        val attributes = mapOf("aapsCarbAmount" to "32", "aapsCarbIsValid" to "true",
            "aapsCarbClassification" to "AAPS_REAL", "grams" to "60")
        assertThat(event().copy(attributes = attributes).toChartEventDetails(10_000).carbsGrams).isEqualTo(32.0)
        assertThat(event().copy(attributes = attributes + ("aapsCarbIsValid" to "false"))
            .toChartEventDetails(10_000).carbsGrams).isNull()
    }

    @Test fun therapyDetailsUseSourceResolvedValuesNotTruncatedAliases() {
        val e = event().copy(provenance = "therapy_events", attributes = mapOf("grams" to "60",
            "displayCarbsG" to "32", "displayInsulinU" to "1.4"))
        assertThat(e.toChartEventDetails(10_000).carbsGrams).isEqualTo(32.0)
        assertThat(e.toChartEventDetails(10_000).recordedInsulinUnits).isEqualTo(1.4)
        assertThat(e.copy(attributes = mapOf("grams" to "60")).toChartEventDetails(10_000).carbsGrams).isNull()
    }

    @Test fun bloodCheckIsAPointAndAcceptedReferenceDoesNotMeanAppliedCalibration() {
        val result = event(CompensationEventType.SENSOR_CALIBRATION).copy(subtype = "MANUAL_BLOOD_CHECK",
            provenance = "blood_glucose_checks", attributes = mapOf("bloodMmol" to "6.2",
                "matchedCgmMmol" to "5.8", "status" to "VALID"), title = "Blood glucose check")
            .toChartEventDetails(20_000)
        assertThat(result.heading).isEqualTo(ChartEventHeading.BLOOD_CHECK)
        assertThat(result.contextEndTs).isNull()
        assertThat(result.lifecycle).isNull()
        assertThat(result.bloodMmol).isEqualTo(6.2)
        assertThat(result.bloodStatus).isEqualTo(BloodGlucoseCheckStatus.VALID)
        assertThat(result.matchedCgmMmol).isEqualTo(5.8)
    }

    @Test fun activityDoesNotTreatDefaultSeverityAsMeasuredIntensity() {
        val e = event(CompensationEventType.ACTIVITY).copy(subtype = "PLANNED")
        assertThat(e.toChartEventDetails(1).intensity).isNull()
        assertThat(e.toChartEventDetails(1).heading).isEqualTo(ChartEventHeading.PLANNED_ACTIVITY)
        assertThat(e.copy(attributes = mapOf("intensity" to "HIGH", "durationMinutes" to "45", "steps" to "900"))
            .toChartEventDetails(1).intensity).isEqualTo(EventSeverity.HIGH)
    }

    @Test fun modelContextIsNotConfirmedFood() {
        val result = event(CompensationEventType.CUSTOM).copy(subtype = "UAM", source = EventSource.AUTOMATIC,
            title = "UAM context", attributes = mapOf("carbs" to "12"), note = "UAM_ENGINE|value=12")
            .toChartEventDetails(20_000)
        assertThat(result.heading).isEqualTo(ChartEventHeading.UAM)
        assertThat(result.carbsGrams).isNull()
        assertThat(result.note).isNull()
    }

    @Test fun allTypesRetainOwnTimestampSourceAndHumanNotes() {
        CompensationEventType.entries.forEach { type ->
            val result = event(type).copy(title = "Evening context", note = "A personal note", source = EventSource.AAPS)
                .toChartEventDetails(20_000)
            assertThat(result.type).isEqualTo(type)
            assertThat(result.startTs).isEqualTo(10_000L)
            assertThat(result.source).isEqualTo(EventSource.AAPS)
            assertThat(result.note).isEqualTo("A personal note")
        }
    }

    @Test fun identicalTitleAndNoteAreNotRepeatedAndRawPayloadIsHidden() {
        assertThat(event().copy(title = "Lunch", note = "Lunch").toChartEventDetails(10_000).note).isNull()
        assertThat(event().copy(note = "{\"token\":\"neutral\"}").toChartEventDetails(10_000).note).isNull()
    }

    @Test fun commandCorrelationMarkersAreNotHumanNotesOrTitles() {
        val marker = "copilot:manual:meal:sample-meal"
        assertThat(event().copy(title = marker, note = marker).toChartEventDetails(10_000).title).isNull()
        assertThat(event().copy(note = marker).toChartEventDetails(10_000).note).isNull()
        assertThat(event().copy(note = "Lunch | $marker").toChartEventDetails(10_000).note).isEqualTo("Lunch")
        assertThat(event().copy(note = "$marker\nLunch").toChartEventDetails(10_000).note).isEqualTo("Lunch")
        assertThat(event().copy(note = "COPILOT:MANUAL:MEAL:sample-meal").toChartEventDetails(10_000).note).isNull()
        assertThat(event().copy(note = "Discussed Copilot at lunch").toChartEventDetails(10_000).note)
            .isEqualTo("Discussed Copilot at lunch")
    }
}
