package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.target.DeliveryTrustStateWireCodec

internal const val DELIVERY_DIAGNOSTIC_MAX_LOOKBACK_MS = 4L * 60L * 60L * 1_000L

internal val DELIVERY_DIAGNOSTIC_TELEMETRY_KEYS = listOf(
    DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
    DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY
)

internal data class DeliveryTrustTelemetryValue(
    val timestamp: Long,
    val source: String,
    val key: String,
    val value: Double?
)

internal fun deliveryDiagnosticSamplesFromTelemetry(
    rows: Iterable<DeliveryTrustTelemetryValue>
): List<DeliveryDiagnosticTimelineSample> = rows
    .asSequence()
    .filter { row ->
        row.key == DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY ||
            row.key == DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY
    }
    .groupBy { row -> row.timestamp to row.source }
    .entries
    .sortedWith(compareBy({ it.key.first }, { it.key.second }))
    .mapNotNull { (identity, candidates) ->
        val stable = candidates.filter { it.key == DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY }
        val selected = stable.ifEmpty {
            candidates.filter { it.key == DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY }
        }
        val states = selected.map { row ->
            DeliveryTrustStateWireCodec.decodeTelemetry(row.key, row.source, row.value)
        }
        val state = states.takeIf { values ->
            values.isNotEmpty() && values.none { it == null } && values.distinct().size == 1
        }?.firstOrNull() ?: return@mapNotNull null
        DeliveryDiagnosticTimelineSample(identity.first, identity.second, state)
    }

internal fun deliveryDiagnosticLookbackFrom(fromTs: Long): Long =
    if (fromTs <= DELIVERY_DIAGNOSTIC_MAX_LOOKBACK_MS) {
        0L
    } else {
        fromTs - DELIVERY_DIAGNOSTIC_MAX_LOOKBACK_MS
    }

internal fun deliveryDiagnosticEventsForWindow(
    rows: Iterable<DeliveryTrustTelemetryValue>,
    fromTs: Long,
    throughTs: Long,
    reserveEvent: (Int) -> Unit = {}
): List<CompensationEvent> {
    if (fromTs < 0L || throughTs < fromTs) return emptyList()
    val sourceFromTs = deliveryDiagnosticLookbackFrom(fromTs)
    val boundedRows = rows.filter { row -> row.timestamp in sourceFromTs..throughTs }
    return EventTimelineRepository()
        .deliveryDiagnosticEvents(
            deliveryDiagnosticSamplesFromTelemetry(boundedRows),
            reserveEvent
        )
        .filter { event -> event.startTs <= throughTs && event.endTs >= fromTs }
}
