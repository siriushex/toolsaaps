package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.LocalEventImpactAnalyzer
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class ChartEventAttributeCapTest {
    @Test fun removingReservedDisplayKeysCannotRevealPreviouslyClippedClinicalFields() {
        val payload = (0..21).associate { "a%02d".format(it) to "value" } + mapOf(
            "displayCarbsG" to "500", "grams" to "24", "profile" to "FAST")
        val event = requireNotNull(EventTimelineRepository().prepareTherapyEvent(TherapyEvent(10_000, "meal", payload)))
        assertThat(event.attributes).doesNotContainKey("profile")
        assertThat(event.attributes["displayCarbsG"]).isEqualTo("24.0")
        assertThat(LocalEventImpactAnalyzer().impact(event).absorptionMinutes).isEqualTo(60)
    }
}
