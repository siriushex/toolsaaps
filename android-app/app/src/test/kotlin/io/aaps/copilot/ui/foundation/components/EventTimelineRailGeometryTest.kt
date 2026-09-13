package io.aaps.copilot.ui.foundation.components

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.ui.foundation.screens.clusterEventMarkers
import io.aaps.copilot.ui.foundation.screens.toChartMarkerUi
import org.junit.Test

class EventTimelineRailGeometryTest {
    @Test fun pointMarkerUsesSixteenDpIconAndShortStemBelowGlyph() {
        val geometry = eventMarkerGeometry()

        assertThat(geometry.iconSizeDp).isEqualTo(16f)
        assertThat(geometry.iconSizeDp).isAtLeast(14f)
        assertThat(geometry.iconSizeDp).isAtMost(16f)
        assertThat(geometry.pointStemStartDp).isGreaterThan(geometry.iconBottomDp)
        assertThat(geometry.pointStemEndDp).isGreaterThan(geometry.pointStemStartDp)
        assertThat(geometry.pointStemEndDp).isLessThan(geometry.durationRailYDp)
    }

    @Test fun clusterBadgeDoesNotChangeMarkerOrRailDimensions() {
        assertThat(eventMarkerTouchTargetSizeDp(overflowCount = 5))
            .isEqualTo(eventMarkerTouchTargetSizeDp(overflowCount = 0))
    }

    @Test fun onlyPointEventsReceiveStemWhileDurationsRetainRail() {
        val point = CompensationEvent("point", 100L, 100L, CompensationEventType.CUSTOM).toChartMarkerUi()
        val duration = CompensationEvent("duration", 100L, 200L, CompensationEventType.ACTIVITY).toChartMarkerUi()

        assertThat(point.hasPointEventStem()).isTrue()
        assertThat(duration.hasPointEventStem()).isFalse()
        assertThat(duration.endTs).isEqualTo(200L)
    }

    @Test fun durationFirstMixedClusterProjectsExactlyOnePointStemAndRetainsDurationAndMembers() {
        val duration = CompensationEvent("duration", 100L, 300L, CompensationEventType.ACTIVITY).toChartMarkerUi()
        val point = CompensationEvent("point", 200L, 200L, CompensationEventType.CUSTOM).toChartMarkerUi()
        val cluster = clusterEventMarkers(listOf(point, duration)).single()

        val stems = pointStemMarkers(listOf(cluster))

        assertThat(stems).hasSize(1)
        assertThat(stems.single().id).isEqualTo("duration")
        assertThat(stems.single().endTs).isEqualTo(300L)
        assertThat(stems.single().representedEventIds).containsExactly("duration", "point").inOrder()
        assertThat(stems.single().containsPointEvent).isTrue()
        assertThat(stems.single().overflowCount).isEqualTo(1)
    }
}
