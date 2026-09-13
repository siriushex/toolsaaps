package io.aaps.copilot.domain.events

enum class MealAbsorptionProfile { FAST, MIXED, FATTY_PROTEIN }

data class EventImpact(
    val absorptionMinutes: Int? = null,
    val activityCurrentFactor: Double = 0.0,
    val activityTailFactor: Double = 0.0,
    val delayedLowFactor: Double = 0.0,
    val contextHours: Int = 0,
    val sensorTrustPenalty: Double = 0.0,
    val infusionGateBlocked: Boolean = false,
    val hormoneFactor: Double = 0.0,
    val isContextOnly: Boolean = false
)

class LocalEventImpactAnalyzer {
    fun impact(event: CompensationEvent, atTs: Long = event.startTs): EventImpact = when (event.type) {
        CompensationEventType.MEAL -> EventImpact(absorptionMinutes = mealDuration(event))
        CompensationEventType.ACTIVITY -> activityImpact(event, atTs)
        CompensationEventType.STRESS -> EventImpact(contextHours = 4, isContextOnly = true)
        CompensationEventType.ILLNESS -> EventImpact(contextHours = 12, isContextOnly = true)
        CompensationEventType.SLEEP -> EventImpact(contextHours = 8, isContextOnly = true)
        CompensationEventType.HORMONAL -> EventImpact(
            contextHours = 24,
            hormoneFactor = CompensationEventHormonePolicy.normalizedFactor(event),
            isContextOnly = true
        )
        CompensationEventType.MENSTRUAL_CYCLE -> EventImpact(
            contextHours = 24,
            hormoneFactor = CompensationEventHormonePolicy.normalizedFactor(event),
            isContextOnly = true
        )
        CompensationEventType.MEDICATION_STEROID -> EventImpact(contextHours = 12, isContextOnly = true)
        CompensationEventType.ALCOHOL -> EventImpact(contextHours = 12, isContextOnly = true)
        CompensationEventType.SENSOR_CALIBRATION -> EventImpact(contextHours = 6, sensorTrustPenalty = event.attributes.decimal("trustPenalty").coerceIn(0.0, 1.0), isContextOnly = true)
        CompensationEventType.INFUSION_PUMP_INSULIN -> EventImpact(contextHours = 4, infusionGateBlocked = true, isContextOnly = true)
        CompensationEventType.CUSTOM -> EventImpact(isContextOnly = true)
    }

    private fun activityImpact(event: CompensationEvent, atTs: Long): EventImpact {
        if (atTs < event.startTs) return EventImpact()
        val durationMs = (event.endTs - event.startTs).coerceAtLeast(0L)
        val elapsedMs = (atTs - event.startTs).coerceIn(0L, durationMs)
        val phase = if (durationMs == 0L) 0.0 else elapsedMs.toDouble() / durationMs
        val intensity = when (event.attributes["intensity"]?.uppercase()) {
            "HIGH" -> 1.0
            "MEDIUM" -> 0.65
            else -> 0.35
        }
        val base = intensity * (0.45 + 0.55 * (1.0 - phase))
        val current = if (atTs < event.endTs) base else 0.0
        val tailMinutes = event.attributes.decimal("tailMinutes").takeIf { it > 0.0 } ?: 120.0
        val tailProgress = if (atTs <= event.endTs) 0.0 else
            ((atTs - event.endTs).toDouble() / (tailMinutes * 60_000.0)).coerceIn(0.0, 1.0)
        val tail = intensity * 0.7 * (1.0 - tailProgress)
        val delayedLow = intensity * (if (atTs >= event.endTs) 0.65 else 0.15) * (1.0 - tailProgress)
        return EventImpact(
            activityCurrentFactor = current.coerceIn(0.0, 1.0),
            activityTailFactor = tail.coerceIn(0.0, 1.0),
            delayedLowFactor = delayedLow.coerceIn(0.0, 1.0)
        )
    }

    fun maximumHormonalFactor(events: Iterable<CompensationEvent>): Double = events
        .filter { it.type == CompensationEventType.HORMONAL || it.type == CompensationEventType.MENSTRUAL_CYCLE }
        .maxOfOrNull { impact(it).hormoneFactor } ?: 0.0

    private fun mealDuration(event: CompensationEvent): Int {
        val profile = event.attributes["profile"]?.uppercase()
        val explicit = event.attributes["durationMinutes"]?.toIntOrNull()?.takeIf { it > 0 }
        val range = when (profile) {
            MealAbsorptionProfile.FAST.name -> 30..60
            MealAbsorptionProfile.FATTY_PROTEIN.name -> 180..360
            else -> 60..180
        }
        return (explicit ?: range.first).coerceIn(range.first, range.last)
    }

    private fun Map<String, String>.decimal(key: String): Double = this[key]?.toDoubleOrNull() ?: 0.0
}
