package io.aaps.copilot.domain.profile

import io.aaps.copilot.domain.model.Forecast
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

enum class ActivityCoverage { AVAILABLE, UNKNOWN }

enum class ActivityContextSource { DISABLED, UNKNOWN, SCHEDULED, MEASURED }

enum class ActivityContextBlocker {
    MODULE_DISABLED,
    BEFORE_PLANNED_START,
    MISSING_HEALTH_CONNECT_COVERAGE,
    MEASUREMENT_GRACE_EXPIRED,
    DIRECTION_UNPROVEN
}

/**
 * Reasons a numeric activity shadow cannot be safely composed with the existing
 * control forecast. These are diagnostic only and never alter control output.
 */
enum class ActivityShadowSuppressionReason {
    UNPROVEN_PREEXISTING_CONTROL_ACTIVITY_FACTOR
}

data class ActivityMeasurement(
    val observedAt: Instant?,
    val activityRatio: Double?,
    val coverage: ActivityCoverage
)

data class ActivityReplayEvidence(
    val factor30: Double,
    val factor60: Double,
    val confidence: Double
) {
    fun usable(): Boolean =
        factor30.isFinite() && factor60.isFinite() && confidence >= REPLAY_CONFIDENCE_MINIMUM

    private companion object {
        const val REPLAY_CONFIDENCE_MINIMUM = 0.70
    }
}

data class ActivityEffectContext(
    val source: ActivityContextSource,
    val factor5: Double,
    val factor30: Double,
    val factor60: Double,
    val ciWidthMultiplier30: Double,
    val ciWidthMultiplier60: Double,
    val confidence: Double,
    val blockers: Set<ActivityContextBlocker>
) {
    companion object {
        val DISABLED = ActivityEffectContext(
            source = ActivityContextSource.DISABLED,
            factor5 = 1.0,
            factor30 = 1.0,
            factor60 = 1.0,
            ciWidthMultiplier30 = 1.0,
            ciWidthMultiplier60 = 1.0,
            confidence = 0.0,
            blockers = setOf(ActivityContextBlocker.MODULE_DISABLED)
        )
    }
}

/**
 * Conservative, pure arbitration for activity forecast context. It never issues a therapy action.
 */
class ActivityEffectModel(
    private val measurementFreshness: Duration = Duration.ofMinutes(10),
    private val scheduledGrace: Duration = Duration.ofMinutes(15),
    private val scheduledDecay: Duration = Duration.ofMinutes(30)
) {

    fun isWithinPlannedEffectWindow(now: Instant, planned: PlannedActivityOccurrence): Boolean =
        !now.isBefore(planned.start) &&
            now.isBefore(planned.end.plus(scheduledGrace).plus(scheduledDecay))

    /** Target keepalive is narrower than forecast decay and begins at the lead window. */
    fun isWithinPlannedTargetWindow(now: Instant, planned: PlannedActivityOccurrence): Boolean =
        !now.isBefore(planned.evaluationStarts) && now.isBefore(planned.end.plus(scheduledGrace))

    fun evaluate(
        enabled: Boolean,
        now: Instant,
        planned: PlannedActivityOccurrence?,
        measured: ActivityMeasurement?,
        replayEvidence: ActivityReplayEvidence? = null
    ): ActivityEffectContext {
        if (!enabled) return ActivityEffectContext.DISABLED
        measured?.usableAt(now, measurementFreshness)?.let { ratio ->
            return measuredContext(ratio)
        }
        if (planned == null) {
            return ActivityEffectContext(
                source = ActivityContextSource.UNKNOWN,
                factor5 = 1.0,
                factor30 = 1.0,
                factor60 = 1.0,
                ciWidthMultiplier30 = 1.0,
                ciWidthMultiplier60 = 1.0,
                confidence = 0.0,
                blockers = setOf(ActivityContextBlocker.MISSING_HEALTH_CONNECT_COVERAGE)
            )
        }
        if (now.isBefore(planned.start)) {
            return ActivityEffectContext(
                source = ActivityContextSource.SCHEDULED,
                factor5 = 1.0,
                factor30 = 1.0,
                factor60 = 1.0,
                ciWidthMultiplier30 = 1.0,
                ciWidthMultiplier60 = 1.0,
                confidence = 0.0,
                blockers = setOf(ActivityContextBlocker.BEFORE_PLANNED_START)
            )
        }
        val replay = replayEvidence?.takeIf(ActivityReplayEvidence::usable)
        val directionUnproven = (planned.type == PlannedActivityType.STRENGTH ||
            planned.intensity == PlannedActivityIntensity.HIGH) && replay == null
        val elapsed = Duration.between(planned.end, now).coerceAtLeast(Duration.ZERO)
        val decay = scheduledPriorWeight(elapsed)
        val scheduledFactors = replay?.let { it.factor30.coerceIn(0.80, 1.25) to it.factor60.coerceIn(0.80, 1.30) }
            ?: scheduledFactors(planned, directionUnproven)
        val blockers = linkedSetOf<ActivityContextBlocker>()
        if (directionUnproven) blockers += ActivityContextBlocker.DIRECTION_UNPROVEN
        if (decay < 1.0) blockers += ActivityContextBlocker.MEASUREMENT_GRACE_EXPIRED
        return ActivityEffectContext(
            source = ActivityContextSource.SCHEDULED,
            factor5 = 1.0,
            factor30 = blendToIdentity(scheduledFactors.first, decay),
            factor60 = blendToIdentity(scheduledFactors.second, decay),
            ciWidthMultiplier30 = (1.0 + if (directionUnproven) 0.20 else 0.08 * (1.0 - decay)).coerceAtLeast(1.0),
            ciWidthMultiplier60 = (1.0 + if (directionUnproven) 0.32 else 0.12 * (1.0 - decay)).coerceAtLeast(1.0),
            confidence = (if (replay != null) replay.confidence else if (directionUnproven) 0.28 else 0.50) * decay,
            blockers = blockers
        )
    }

    /**
     * Applies an already-resolved context at the prediction-domain boundary.
     * The incoming bounded interval is always contained after the adjustment.
     */
    fun applyForecastContext(
        forecasts: List<Forecast>,
        context: ActivityEffectContext
    ): List<Forecast> {
        if (forecasts.isEmpty() || context == ActivityEffectContext.DISABLED) return forecasts
        return forecasts.map { forecast ->
            val factor = when (forecast.horizonMinutes) {
                5 -> context.factor5
                30 -> context.factor30
                60 -> context.factor60
                else -> 1.0
            }
            val ciMultiplier = when (forecast.horizonMinutes) {
                30 -> context.ciWidthMultiplier30
                60 -> context.ciWidthMultiplier60
                else -> 1.0
            }
            if (abs(factor - 1.0) < EPSILON && abs(ciMultiplier - 1.0) < EPSILON) return@map forecast

            val horizonGain = when (forecast.horizonMinutes) {
                5 -> 0.22
                30 -> 0.50
                60 -> 0.75
                else -> 0.0
            }
            val shift = (-(factor - 1.0) * horizonGain).coerceIn(-0.45, 0.45)
            val value = (forecast.valueMmol + shift).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            val incomingLow = minOf(forecast.ciLow, forecast.ciHigh).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            val incomingHigh = maxOf(forecast.ciLow, forecast.ciHigh).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            val widenedHalfWidth = ((incomingHigh - incomingLow) / 2.0)
                .coerceAtLeast(0.0) * ciMultiplier.coerceAtLeast(1.0)
            val low = minOf(incomingLow, value - widenedHalfWidth, value)
                .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            val high = maxOf(incomingHigh, value + widenedHalfWidth, value)
                .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            forecast.copy(
                valueMmol = value,
                ciLow = low,
                ciHigh = high,
                modelVersion = forecast.modelVersion.withActivityContextVersion()
            )
        }
    }

    private fun measuredContext(ratio: Double): ActivityEffectContext {
        val bounded = ratio.coerceIn(MEASURED_FACTOR_MIN, MEASURED_FACTOR_MAX)
        return ActivityEffectContext(
            source = ActivityContextSource.MEASURED,
            factor5 = bounded,
            factor30 = bounded,
            factor60 = bounded,
            ciWidthMultiplier30 = 1.0,
            ciWidthMultiplier60 = 1.0,
            confidence = 0.85,
            blockers = emptySet()
        )
    }

    private fun ActivityMeasurement.usableAt(now: Instant, freshness: Duration): Double? {
        val value = activityRatio?.takeIf { it.isFinite() } ?: return null
        val observed = observedAt ?: return null
        if (coverage != ActivityCoverage.AVAILABLE || observed.isAfter(now) || Duration.between(observed, now) > freshness) {
            return null
        }
        return value
    }

    private fun scheduledPriorWeight(elapsed: Duration): Double = when {
        elapsed <= scheduledGrace -> 1.0
        elapsed >= scheduledGrace.plus(scheduledDecay) -> 0.0
        else -> 1.0 - (elapsed.minus(scheduledGrace).toMillis().toDouble() / scheduledDecay.toMillis())
    }

    private fun scheduledFactors(
        planned: PlannedActivityOccurrence,
        directionUnproven: Boolean
    ): Pair<Double, Double> {
        if (directionUnproven) return 1.0 to 1.0
        return when (planned.intensity) {
            PlannedActivityIntensity.LIGHT -> 1.04 to 1.07
            PlannedActivityIntensity.MEDIUM -> 1.07 to 1.12
            PlannedActivityIntensity.HIGH -> 1.0 to 1.0
        }
    }

    private fun blendToIdentity(factor: Double, weight: Double): Double =
        1.0 + (factor - 1.0) * weight.coerceIn(0.0, 1.0)

    private fun String.withActivityContextVersion(): String =
        if (contains("|activity_context_v2")) this else "$this|activity_context_v2"

    private companion object {
        const val MEASURED_FACTOR_MIN = 0.70
        const val MEASURED_FACTOR_MAX = 1.50
        const val MIN_GLUCOSE_MMOL = 2.2
        const val MAX_GLUCOSE_MMOL = 22.0
        const val EPSILON = 1e-6
    }
}
