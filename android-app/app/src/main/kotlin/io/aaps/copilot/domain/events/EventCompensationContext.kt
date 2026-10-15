package io.aaps.copilot.domain.events

import io.aaps.copilot.domain.profile.MealGlycemicIndex

enum class EventContextStatus { READY, UNAVAILABLE, SOURCE_CHANGED, OVERFLOW }

data class EventGlucoseObservation(
    val baseline: Double,
    val minimum: Double,
    val peak: Double,
    val peakAfterMinutes: Int,
    val throughTs: Long,
    val sampleCount: Int,
    val source: String
)

data class EventIsfObservation(
    val before: Double,
    val atEvent: Double,
    val beforeTs: Long,
    val atEventTs: Long,
    val source: String
) {
    val changePercent: Double get() = (atEvent / before - 1.0) * 100.0
}

data class EventSensorAge(val hours: Double, val observedAt: Long, val source: String)

/** Retrospective display context only; never a dose, timing or controller input. */
data class EventCompensationContext(
    val status: EventContextStatus = EventContextStatus.UNAVAILABLE,
    val glycemicIndex: MealGlycemicIndex? = null,
    val glucose: EventGlucoseObservation? = null,
    val isf: EventIsfObservation? = null,
    val sensorAge: EventSensorAge? = null,
    val sensorConcern: Boolean = false,
    val overlappingMeal: Boolean = false
)

typealias EventContextLoader = suspend (CompensationEvent) -> EventCompensationContext

data class EventContextSample(val ts: Long, val value: Double, val source: String)

object EventCompensationReview {
    const val MINUTE = 60_000L
    const val WINDOW = 4 * 60 * MINUTE

    fun glucose(startTs: Long, throughTs: Long, samples: List<EventContextSample>): EventGlucoseObservation? {
        if (throughTs - startTs < 30 * MINUTE || samples.size > 600) return null
        val rows = samples.filter { it.ts in (startTs - 10 * MINUTE)..throughTs }
        if (rows.any { !it.value.isFinite() || it.value !in 1.0..40.0 || it.source.isBlank() }) return null
        // Do not join different sensor streams or pick a convenient conflicting duplicate.
        if (rows.map { it.source }.distinct().size != 1) return null
        val grouped = rows.groupBy { it.ts }
        if (grouped.values.any { group -> group.map { it.value }.distinct().size > 1 }) return null
        val points = grouped.values.map { it.first() }.sortedBy { it.ts }
        val baseline = points.lastOrNull { it.ts <= startTs } ?: return null
        val after = points.filter { it.ts > startTs }
        if (after.size < 6 || throughTs - after.last().ts > 10 * MINUTE) return null
        if ((listOf(baseline) + after).zipWithNext().any { (a, b) -> b.ts - a.ts > 10 * MINUTE }) return null
        val peak = after.maxBy { it.value }
        return EventGlucoseObservation(baseline.value, after.minOf { it.value }, peak.value,
            ((peak.ts - startTs) / MINUTE).toInt(), after.last().ts, after.size, baseline.source)
    }

    fun isf(startTs: Long, samples: List<EventContextSample>): EventIsfObservation? {
        val rows = samples.filter { it.ts in (startTs - 90 * MINUTE)..startTs }
        if (rows.any { !it.value.isFinite() || it.value !in 0.2..18.0 || it.source.isBlank() }) return null
        if (rows.map { it.source }.distinct().size != 1) return null
        val grouped = rows.groupBy { it.ts }
        if (grouped.values.any { it.map { row -> row.value }.distinct().size > 1 }) return null
        val points = grouped.values.map { it.first() }.sortedBy { it.ts }
        val atEvent = points.lastOrNull { startTs - it.ts <= 10 * MINUTE } ?: return null
        val before = points.lastOrNull { it.ts <= startTs - 45 * MINUTE } ?: return null
        return EventIsfObservation(before.value, atEvent.value, before.ts, atEvent.ts, atEvent.source)
    }
}
