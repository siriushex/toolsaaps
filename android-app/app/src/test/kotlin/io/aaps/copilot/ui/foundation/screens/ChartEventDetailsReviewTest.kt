package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.eventDisplayQuantities
import org.junit.Test

class ChartEventDetailsReviewTest {
    @Test fun multilineJsonCannotLeakIntoHumanNote() {
        val event = CompensationEvent("note-1", 1, 2, CompensationEventType.CUSTOM,
            note = "{\n  \"internalValue\": \"neutral\"\n}")
        assertThat(event.toChartEventDetails(2).note).isNull()
    }
    @Test fun conflictingValidityCannotDisplayRecordedInsulin() {
        val values = eventDisplayQuantities("meal_bolus", mapOf("grams" to "24", "units" to "1.4",
            "isValid" to "false", "is_valid" to "true"))
        assertThat(values.insulinUnits).isNull()
    }
}
