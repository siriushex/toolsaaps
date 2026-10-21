package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.activity.PhysicalActivityObservation
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import java.time.Instant
import java.time.ZoneId

/** Preserves acquisition identity before generic telemetry aliases and daily maxima. */
internal object PhysicalActivityObservationResolver {
    fun resolve(rows: List<TelemetrySampleEntity>, nowTs: Long, zoneId: ZoneId): PhysicalActivityObservation? =
        rows.filter { it.source in PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES && it.key in KEYS }
            .groupBy { it.source }
            .mapNotNull { (source, samples) ->
                val timestamp = samples.maxOf { it.timestamp }
                if (timestamp !in 1..nowTs || nowTs - timestamp > PhysicalActivityObservation.maxAgeMs(source)) {
                    return@mapNotNull null
                }
                val acquisition = samples.filter { it.timestamp == timestamp }
                if (acquisition.any { !PhysicalActivityTelemetryPolicy.isTrustedSourceAndQuality(it.source, it.quality) }) {
                    return@mapNotNull null
                }
                val byKey = acquisition.groupBy { it.key }
                if (byKey.values.any { values ->
                        values.distinctBy { Triple(it.valueDouble, it.valueText, it.unit) }.size != 1 ||
                            values.any {
                                val validUnit = if (it.key == PhysicalActivityTelemetryPolicy.STEPS_COUNT_KEY) {
                                    it.unit == "steps"
                                } else {
                                    it.unit == null || it.unit == "ratio"
                                }
                                it.valueDouble == null || it.valueText != null || !validUnit
                            }
                    }) return@mapNotNull null
                PhysicalActivityObservation(
                    timestamp = timestamp,
                    source = source,
                    counterDay = "$zoneId:${Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()}",
                    activityRatio = byKey[PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY]?.first()?.valueDouble,
                    stepsCount = byKey[PhysicalActivityTelemetryPolicy.STEPS_COUNT_KEY]?.first()?.valueDouble
                ).takeIf { it.usableAt(nowTs) }
            }
            // Stable tie-breaking keeps concurrent phone/watch totals separate.
            .maxWithOrNull(compareBy<PhysicalActivityObservation> { it.timestamp }.thenBy { it.source })

    private val KEYS = setOf(
        PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY,
        PhysicalActivityTelemetryPolicy.STEPS_COUNT_KEY
    )
}
