package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.isfcr.PhysioContextTag
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.data.repository.EventTimelineSources
import org.junit.Test

class PhysioContextTagEventTimelineMapperTest {
    @Test
    fun reconstructionPreservesEventMetadataAndClosedStatus() {
        val entity = PhysioContextTag(
            id = "closed-stress",
            tsStart = 1_000L,
            tsEnd = 2_000L,
            tagType = "stress",
            severity = 0.9,
            source = "user",
            note = "context note",
            subtype = "acute",
            title = "Closed stress",
            attributesJson = "{\"confidence\":\"high\"}",
            revision = 7L,
            status = "CLOSED"
        ).toEventTimelineEntity()

        val event = EventTimelineRepository().aggregate(
            EventTimelineSources(contextTags = listOf(entity)),
            nowTs = 0L
        ).single()

        assertThat(event.status).isEqualTo(CompensationEventStatus.CLOSED)
        assertThat(event.revision).isEqualTo(7L)
        assertThat(event.title).isEqualTo("Closed stress")
        assertThat(event.subtype).isEqualTo("acute")
        assertThat(event.note).isEqualTo("context note")
        assertThat(event.attributes).containsExactly("confidence", "high")
    }

    @Test
    fun steroidsQuickTagMapsToMedicationSteroid() {
        val entity = PhysioContextTag(
            id = "steroids-tag",
            tsStart = 1_000L,
            tsEnd = 2_000L,
            tagType = "steroids",
            severity = 0.6,
            source = "user",
            note = "",
            subtype = "",
            title = "Steroids",
            attributesJson = "{}",
            revision = 1L,
            status = "ACTIVE"
        ).toEventTimelineEntity()

        val event = EventTimelineRepository().aggregate(
            EventTimelineSources(contextTags = listOf(entity)),
            nowTs = 1_500L
        ).single()

        assertThat(event.type).isEqualTo(CompensationEventType.MEDICATION_STEROID)
    }
}
