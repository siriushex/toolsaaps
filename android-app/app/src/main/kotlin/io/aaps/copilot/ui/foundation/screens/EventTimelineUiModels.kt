package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.CompensationEventLifecycle
import io.aaps.copilot.domain.events.FemaleCyclePhase
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.events.CompensationEventProfilePolicy
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.ui.ManualCompensationEventRoute
import io.aaps.copilot.ui.manualCompensationEventRoute

data class ChartEventMarkerUi(
    val id: String,
    val type: CompensationEventType,
    val startTs: Long,
    val endTs: Long?,
    val severity: EventSeverity,
    val source: EventSource,
    val active: Boolean,
    val overflowCount: Int = 0,
    val representedEventIds: List<String> = listOf(id),
    val containsPointEvent: Boolean = endTs == null || endTs <= startTs
)

enum class EventSourceFilter { ALL, MANUAL, AAPS, AUTO }

data class EventTimelineSections(
    val active: List<CompensationEvent>,
    val recent24h: List<CompensationEvent>
)

internal data class EventActionItem(
    val type: CompensationEventType,
    val route: ManualCompensationEventRoute,
    val available: Boolean = true
)

fun eventTimelineSections(
    events: List<CompensationEvent>,
    nowTs: Long,
    filter: EventSourceFilter
): EventTimelineSections {
    val earliestRecentTs = (nowTs - RECENT_EVENT_WINDOW_MS).takeIf { nowTs >= RECENT_EVENT_WINDOW_MS } ?: 0L
    val accepted = events.filter { event ->
        when (filter) {
            EventSourceFilter.ALL -> true
            EventSourceFilter.MANUAL -> event.source == EventSource.USER
            EventSourceFilter.AAPS -> event.source == EventSource.AAPS
            EventSourceFilter.AUTO -> event.source == EventSource.AUTOMATIC
        }
    }
    val order = compareByDescending<CompensationEvent> { it.startTs }.thenBy { it.localId }
    return EventTimelineSections(
        active = accepted.filter { it.isActiveAt(nowTs) }.sortedWith(order),
        recent24h = accepted.filter {
            !it.isActiveAt(nowTs) && it.startTs <= nowTs && it.endTs >= earliestRecentTs
        }.sortedWith(order)
    )
}

val ChartEventMarkerUi.isMore: Boolean get() = overflowCount > 0

fun CompensationEvent.toChartMarkerUi(nowTs: Long = System.currentTimeMillis()): ChartEventMarkerUi = ChartEventMarkerUi(
    id = localId,
    type = type,
    startTs = startTs,
    endTs = endTs.takeIf { it > startTs },
    severity = severity,
    source = source,
    active = isActiveAt(nowTs)
)

fun CompensationEvent.lifecycleLabelAt(nowTs: Long): String = when (lifecycleAt(nowTs)) {
    CompensationEventLifecycle.UPCOMING -> "Upcoming"
    CompensationEventLifecycle.ACTIVE -> "Active"
    CompensationEventLifecycle.ELAPSED -> "Ended"
    CompensationEventLifecycle.CLOSED -> "Closed"
}

fun menstrualCyclePhaseOptions(): List<FemaleCyclePhase> = FemaleCyclePhase.entries

fun creatableContextEventTypes(sex: PhysiologicalSex): List<CompensationEventType> =
    CompensationEventType.entries.filter { type ->
        CompensationEventManualPolicy.isContextOnly(type) &&
            (type != CompensationEventType.MENSTRUAL_CYCLE || sex == PhysiologicalSex.FEMALE)
    }

internal fun eventActionTypes(sex: PhysiologicalSex): List<CompensationEventType> =
    eventActionItems(sex).map(EventActionItem::type)

internal fun eventActionItems(sex: PhysiologicalSex): List<EventActionItem> =
    CompensationEventType.entries
        .filter { type ->
            type != CompensationEventType.MENSTRUAL_CYCLE || sex == PhysiologicalSex.FEMALE
        }
        .map { type ->
            EventActionItem(type = type, route = manualCompensationEventRoute(type))
        }

fun canManageExistingContextEvent(event: CompensationEvent): Boolean =
    event.source == EventSource.USER && CompensationEventManualPolicy.isContextOnly(event.type)

fun canEditExistingContextEvent(
    event: CompensationEvent,
    sex: PhysiologicalSex
): Boolean = canManageExistingContextEvent(event) &&
    CompensationEventProfilePolicy.canCreate(event.type, sex)

fun clusterEventMarkers(markers: List<ChartEventMarkerUi>, maxMarkers: Int = 48): List<List<ChartEventMarkerUi>> {
    if (markers.isEmpty()) return emptyList()
    val sorted = markers.sortedWith(compareBy<ChartEventMarkerUi> { it.startTs }.thenBy { it.id })
    val retained = if (sorted.size > maxMarkers) {
        sorted.take((maxMarkers - 1).coerceAtLeast(0)) + sorted.drop(maxMarkers - 1).let { overflow ->
            overflow.last().copy(
                id = "events-more",
                overflowCount = overflow.size,
                representedEventIds = overflow.flatMap(ChartEventMarkerUi::representedEventIds),
                containsPointEvent = overflow.any(ChartEventMarkerUi::containsPointEvent)
            )
        }
    } else sorted
    return retained.fold(mutableListOf<MutableList<ChartEventMarkerUi>>()) { clusters, marker ->
        val last = clusters.lastOrNull()
        if (last != null && marker.startTs - last.last().startTs < 15L * 60_000L) last += marker else clusters += mutableListOf(marker)
        clusters
    }
}

fun clusterSelectionMarker(cluster: List<ChartEventMarkerUi>): ChartEventMarkerUi {
    require(cluster.isNotEmpty())
    val representedIds = cluster
        .sortedWith(compareBy<ChartEventMarkerUi> { it.startTs }.thenBy { it.id })
        .flatMap(ChartEventMarkerUi::representedEventIds)
        .distinct()
    return cluster.minWith(compareBy<ChartEventMarkerUi> { it.startTs }.thenBy { it.id }).copy(
        overflowCount = (representedIds.size - 1).coerceAtLeast(0),
        representedEventIds = representedIds,
        containsPointEvent = cluster.any(ChartEventMarkerUi::containsPointEvent)
    )
}

private const val RECENT_EVENT_WINDOW_MS = 24L * 60L * 60L * 1_000L
