package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.CompensationEventLifecycle
import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.events.FemaleCyclePhase
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.ui.ManualCompensationEventRoute
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.data.repository.EventTimelineSources
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class EventTimelineUiModelsTest {
    @Test fun filtersProduceSeparateActiveAndRecentSectionsForEverySource() {
        val hour = 60L * 60L * 1_000L
        val now = 48L * hour
        val events = listOf(
            CompensationEvent("manual-active", now - hour, now + hour, CompensationEventType.STRESS),
            CompensationEvent(
                "aaps-recent",
                now - 3L * hour,
                now - 2L * hour,
                CompensationEventType.CUSTOM,
                source = EventSource.AAPS
            ),
            CompensationEvent(
                "auto-recent",
                now - 4L * hour,
                now - 3L * hour,
                CompensationEventType.ACTIVITY,
                source = EventSource.AUTOMATIC
            ),
            CompensationEvent("old", now - 30L * hour, now - 29L * hour, CompensationEventType.CUSTOM)
        )

        assertThat(eventTimelineSections(events, now, EventSourceFilter.ALL).active.map { it.localId })
            .containsExactly("manual-active")
        assertThat(eventTimelineSections(events, now, EventSourceFilter.ALL).recent24h.map { it.localId })
            .containsExactly("aaps-recent", "auto-recent").inOrder()
        assertThat(eventTimelineSections(events, now, EventSourceFilter.MANUAL).active.map { it.localId })
            .containsExactly("manual-active")
        assertThat(eventTimelineSections(events, now, EventSourceFilter.AAPS).recent24h.map { it.localId })
            .containsExactly("aaps-recent")
        assertThat(eventTimelineSections(events, now, EventSourceFilter.AUTO).recent24h.map { it.localId })
            .containsExactly("auto-recent")
    }

    @Test fun aggregatedTherapyAndContextEventsReachChartMarkerModel() {
        val events = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(TherapyEvent(1_000, "carbs", mapOf("eventId" to "meal", "grams" to "20"))),
                contextTags = listOf(PhysioContextTagEntity("stress", 2_000, 3_000, "stress", 0.5, "user", "context"))
            ),
            nowTs = 4_000
        )
        val markers = events.map(CompensationEvent::toChartMarkerUi)
        assertThat(markers.map { it.id }).containsExactly("meal", "stress").inOrder()
    }

    @Test fun menstrualCreationIsHiddenButHistoricalMarkersRemainVisible() {
        assertThat(creatableContextEventTypes(PhysiologicalSex.MALE)).doesNotContain(CompensationEventType.MENSTRUAL_CYCLE)
        assertThat(creatableContextEventTypes(PhysiologicalSex.FEMALE)).contains(CompensationEventType.MENSTRUAL_CYCLE)
        assertThat(
            CompensationEvent("cycle", 1L, 2L, CompensationEventType.MENSTRUAL_CYCLE)
                .visibleFor(PhysiologicalSex.MALE)
        ).isTrue()
    }

    @Test fun existingMenstrualHistoryRemainsManageableButNotEditableAfterProfileChange() {
        val historical = CompensationEvent(
            "historical-cycle",
            1L,
            2L,
            CompensationEventType.MENSTRUAL_CYCLE
        ).withFemaleCyclePhase(FemaleCyclePhase.LUTEAL)

        assertThat(creatableContextEventTypes(PhysiologicalSex.MALE))
            .doesNotContain(CompensationEventType.MENSTRUAL_CYCLE)
        assertThat(canManageExistingContextEvent(historical)).isTrue()
        assertThat(canEditExistingContextEvent(historical, PhysiologicalSex.MALE)).isFalse()
        assertThat(canEditExistingContextEvent(historical, PhysiologicalSex.FEMALE)).isTrue()
    }

    @Test fun genericEditorContainsOnlyContextOwnedTypes() {
        assertThat(creatableContextEventTypes(PhysiologicalSex.FEMALE)).containsExactly(
            CompensationEventType.STRESS,
            CompensationEventType.ILLNESS,
            CompensationEventType.SLEEP,
            CompensationEventType.HORMONAL,
            CompensationEventType.MEDICATION_STEROID,
            CompensationEventType.ALCOHOL,
            CompensationEventType.CUSTOM,
            CompensationEventType.MENSTRUAL_CYCLE
        ).inOrder()
    }

    @Test fun actionGridContainsEveryAuthoritativeRouteAndHidesOnlyIneligibleCycleAction() {
        val femaleActions = eventActionItems(PhysiologicalSex.FEMALE)
        assertThat(femaleActions.map { it.type })
            .containsExactlyElementsIn(CompensationEventType.entries)
            .inOrder()
        assertThat(femaleActions.all { it.available }).isTrue()
        assertThat(femaleActions.associate { it.type to it.route }).containsExactly(
            CompensationEventType.MEAL, ManualCompensationEventRoute.MEAL_ACTION,
            CompensationEventType.ACTIVITY, ManualCompensationEventRoute.PLANNED_ACTIVITY,
            CompensationEventType.STRESS, ManualCompensationEventRoute.CONTEXT,
            CompensationEventType.ILLNESS, ManualCompensationEventRoute.CONTEXT,
            CompensationEventType.SLEEP, ManualCompensationEventRoute.CONTEXT,
            CompensationEventType.HORMONAL, ManualCompensationEventRoute.CONTEXT,
            CompensationEventType.MEDICATION_STEROID, ManualCompensationEventRoute.CONTEXT,
            CompensationEventType.ALCOHOL, ManualCompensationEventRoute.CONTEXT,
            CompensationEventType.SENSOR_CALIBRATION, ManualCompensationEventRoute.BLOOD_CHECK,
            CompensationEventType.INFUSION_PUMP_INSULIN, ManualCompensationEventRoute.READ_ONLY_DIAGNOSTIC,
            CompensationEventType.CUSTOM, ManualCompensationEventRoute.CONTEXT,
            CompensationEventType.MENSTRUAL_CYCLE, ManualCompensationEventRoute.CONTEXT
        )
        assertThat(eventActionItems(PhysiologicalSex.MALE).map { it.type })
            .containsExactlyElementsIn(
                CompensationEventType.entries.filterNot {
                    it == CompensationEventType.MENSTRUAL_CYCLE
                }
            )
            .inOrder()
    }

    @Test fun importedAapsAndAutomaticRowsRemainReadOnlyForEveryContextType() {
        for (source in listOf(EventSource.AAPS, EventSource.AUTOMATIC)) {
            for (type in CompensationEventManualPolicy.contextOnlyTypes) {
                val event = CompensationEvent("$source:$type", 1, 2, type, source = source)
                assertThat(canManageExistingContextEvent(event)).isFalse()
                assertThat(canEditExistingContextEvent(event, PhysiologicalSex.FEMALE)).isFalse()
            }
        }
    }

    @Test fun chartMarkerPreservesDurationAndActiveState() {
        val marker = CompensationEvent("event-1", 1_000, 61_000, CompensationEventType.MEAL).toChartMarkerUi(nowTs = 30_000)
        assertThat(marker.endTs).isEqualTo(61_000)
        assertThat(marker.active).isTrue()
    }

    @Test fun lifecycleProjectionIsUpcomingDuringThenElapsedWithoutStoredMutation() {
        val planned = CompensationEvent("planned", 10_000, 20_000, CompensationEventType.ACTIVITY, subtype = "PLANNED")
        val context = CompensationEvent("context", 10_000, 20_000, CompensationEventType.STRESS)

        assertThat(planned.lifecycleAt(9_999)).isEqualTo(CompensationEventLifecycle.UPCOMING)
        assertThat(planned.lifecycleAt(15_000)).isEqualTo(CompensationEventLifecycle.ACTIVE)
        assertThat(planned.lifecycleAt(20_001)).isEqualTo(CompensationEventLifecycle.ELAPSED)
        assertThat(context.lifecycleAt(9_999)).isEqualTo(CompensationEventLifecycle.UPCOMING)
        assertThat(context.lifecycleAt(15_000)).isEqualTo(CompensationEventLifecycle.ACTIVE)
        assertThat(context.lifecycleAt(20_001)).isEqualTo(CompensationEventLifecycle.ELAPSED)
        assertThat(planned.isActiveAt(9_999)).isFalse()
        assertThat(planned.isActiveAt(15_000)).isTrue()
        assertThat(planned.status.name).isEqualTo("ACTIVE")
    }

    @Test fun threeDayContextRemainsActiveInListAndGraphAfterDayOne() {
        val day = 24L * 60L * 60L * 1_000L
        val start = 40L * day
        val now = start + 2L * day
        val event = EventTimelineRepository().aggregate(
            EventTimelineSources(
                contextTags = listOf(
                    PhysioContextTagEntity(
                        id = "three-day-cycle",
                        tsStart = start,
                        tsEnd = start + 3L * day,
                        tagType = "menstrual_cycle",
                        severity = 0.5,
                        source = "user",
                        note = "history"
                    )
                )
            ),
            nowTs = now
        ).single()

        val marker = event.toChartMarkerUi(now)

        assertThat(event.lifecycleLabelAt(now)).isEqualTo("Active")
        assertThat(event.isActiveAt(now)).isTrue()
        assertThat(marker.active).isTrue()
        assertThat(marker.endTs).isEqualTo(start + 3L * day)
    }

    @Test fun menstrualEditorMappingUsesSafePhaseAttribute() {
        val event = CompensationEvent("cycle", 1, 2, CompensationEventType.MENSTRUAL_CYCLE)
            .withFemaleCyclePhase(FemaleCyclePhase.OVULATION)
        assertThat(menstrualCyclePhaseOptions()).containsExactlyElementsIn(FemaleCyclePhase.entries).inOrder()
        assertThat(event.femaleCyclePhase()).isEqualTo(FemaleCyclePhase.OVULATION)
        assertThat(event.attributes["cyclePhase"]).isEqualTo("OVULATION")
    }

    @Test fun markersClusterWithinBoundedReadableWindow() {
        val markers = listOf(1_000L, 2_000L, 16L * 60_000L).mapIndexed { index, ts ->
            CompensationEvent("event-$index", ts, ts, CompensationEventType.CUSTOM).toChartMarkerUi()
        }
        assertThat(clusterEventMarkers(markers)).hasSize(2)
        assertThat(clusterEventMarkers(markers).first()).hasSize(2)
    }

    @Test fun markerOverflowIsExplicitAndAccessible() {
        val markers = (0..4).map { index ->
            CompensationEvent("event-$index", index.toLong(), index.toLong(), CompensationEventType.CUSTOM).toChartMarkerUi()
        }
        val result = clusterEventMarkers(markers, maxMarkers = 3).flatten()
        assertThat(result).hasSize(3)
        assertThat(result.last().isMore).isTrue()
        assertThat(result.last().overflowCount).isEqualTo(3)
        assertThat(result.last().representedEventIds)
            .containsExactly("event-2", "event-3", "event-4")
            .inOrder()
    }

    @Test fun markerClustersAreDeterministicForUnsortedOverlaps() {
        val markers = listOf(20L, 1L, 10L).map { ts ->
            CompensationEvent("event-$ts", ts, ts + 20L, CompensationEventType.CUSTOM).toChartMarkerUi()
        }

        val ids = clusterEventMarkers(markers).flatten().map { it.id }

        assertThat(ids).containsExactly("event-1", "event-10", "event-20").inOrder()
    }

    @Test fun clusterSelectionRetainsEveryRepresentedEventIdIncludingBoundedOverflow() {
        val markers = (0..5).map { index ->
            CompensationEvent(
                "event-$index",
                index.toLong(),
                index.toLong(),
                CompensationEventType.CUSTOM
            ).toChartMarkerUi()
        }
        val cluster = clusterEventMarkers(markers, maxMarkers = 3).single()

        val selected = clusterSelectionMarker(cluster)

        assertThat(selected.representedEventIds)
            .containsExactlyElementsIn(markers.map { it.id })
            .inOrder()
        assertThat(selected.overflowCount).isEqualTo(5)
    }

    @Test fun clusterSelectionOrdersEveryUniqueMemberByTimestampThenStableId() {
        val selected = clusterSelectionMarker(
            listOf(
                ChartEventMarkerUi(
                    id = "later",
                    type = CompensationEventType.CUSTOM,
                    startTs = 20,
                    endTs = null,
                    severity = EventSeverity.LOW,
                    source = EventSource.USER,
                    active = false,
                    representedEventIds = listOf("later", "shared")
                ),
                ChartEventMarkerUi(
                    id = "earlier-b",
                    type = CompensationEventType.STRESS,
                    startTs = 10,
                    endTs = null,
                    severity = EventSeverity.LOW,
                    source = EventSource.USER,
                    active = false,
                    representedEventIds = listOf("earlier-b", "shared")
                ),
                ChartEventMarkerUi(
                    id = "earlier-a",
                    type = CompensationEventType.STRESS,
                    startTs = 10,
                    endTs = null,
                    severity = EventSeverity.LOW,
                    source = EventSource.USER,
                    active = false
                )
            )
        )

        assertThat(selected.representedEventIds)
            .containsExactly("earlier-a", "earlier-b", "shared", "later")
            .inOrder()
        assertThat(selected.overflowCount).isEqualTo(3)
    }
}
