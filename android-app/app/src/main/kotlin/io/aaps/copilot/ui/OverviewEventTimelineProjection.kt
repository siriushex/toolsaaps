package io.aaps.copilot.ui

import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.TherapySanitizer
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.EventTimeline
import io.aaps.copilot.domain.model.TherapyEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal fun <T : Any> projectOverviewEventTimeline(
    inputsForWindow: (EventTimelineWindow) -> Flow<T>,
    pastWindowMs: Long,
    futureWindowMs: Long,
    clock: () -> Long,
    gson: Gson,
    projectionDispatcher: CoroutineDispatcher,
    therapyRows: (T) -> List<TherapyEventEntity>,
    maxRetainedRows: Int,
    prepareTherapyEvent: (TherapyEvent) -> CompensationEvent?,
    convertTherapyRow: (TherapyEventEntity) -> TherapyEvent? = { row ->
        TherapySanitizer.toDomainEvents(listOf(row), gson).singleOrNull()
    },
    project: suspend (T, EventTimelineWindow, List<CompensationEvent>) -> List<CompensationEvent>
): Flow<EventTimeline> = flow {
    require(maxRetainedRows > 0)
    // Full row equality includes timestamp, type and every payload/source/revision correction.
    // Null entries retain rejected/ignored rows too; only the current visible snapshot is kept.
    // Values are static source events, never the clock-dependent aggregate result.
    var retained = emptyMap<TherapyEventEntity, CompensationEvent?>()
    emitAll(boundaryDrivenEventTimeline(
        inputsForWindow = inputsForWindow,
        pastWindowMs = pastWindowMs,
        futureWindowMs = futureWindowMs,
        clock = clock,
        project = { input, window ->
            withContext(projectionDispatcher) {
                val context = currentCoroutineContext()
                val next = LinkedHashMap<TherapyEventEntity, CompensationEvent?>()
                val events = buildList {
                    for (row in therapyRows(input)) {
                        context.ensureActive()
                        // Mappers.toDomain assigns ts = timestamp, never a payload timestamp.
                        if (row.timestamp !in window.fromTs..window.throughTs) continue
                        val event = when {
                            next.containsKey(row) -> next[row]
                            retained.containsKey(row) -> retained[row]
                            else -> convertTherapyRow(row)?.let { therapy ->
                                context.ensureActive()
                                prepareTherapyEvent(therapy)
                            }
                        }
                        context.ensureActive()
                        if (next.size < maxRetainedRows) next[row] = event
                        if (event != null) add(event)
                    }
                }
                val result = project(input, window, events)
                context.ensureActive()
                retained = next
                result
            }
        }
    ))
}
