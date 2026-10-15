package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.util.Collections

internal data class MealAbsorptionPoint(
    val offsetMinutes: Int,
    val absorbedStepGrams: Double,
    val cumulativeFutureGrams: Double,
    val remainingGrams: Double
)

/** Carbohydrate timing component only, not a glucose forecast or safety verdict. */
internal class MealAbsorptionProjection private constructor(
    val predictionAtMs: Long,
    val alreadyAbsorbedGrams: Double,
    points: List<MealAbsorptionPoint>,
    val completeByHorizon: Boolean
) {
    val points: List<MealAbsorptionPoint> = Collections.unmodifiableList(points.toList())
    val remainingAtHorizonGrams: Double get() = points.last().remainingGrams

    companion object {
        fun project(
            nowMs: Long,
            hypotheticalStartMs: Long,
            grams: Double,
            profile: MealAbsorptionProfile,
            durationMinutes: Int,
            horizonMinutes: Int
        ): MealAbsorptionProjection {
            require(nowMs > 0 && hypotheticalStartMs > 0)
            require(grams.isFinite() && grams >= 0.0)
            require(horizonMinutes in 5..720 && horizonMinutes % 5 == 0)
            val curve = MealAbsorptionCurve(profile, durationMinutes)
            require(curve.durationMinutes == durationMinutes) { "Invalid scenario absorption duration" }
            return fromCumulative(nowMs, hypotheticalStartMs, grams, horizonMinutes, curve::absorbedFraction)
        }

        internal fun fromCumulative(
            nowMs: Long,
            hypotheticalStartMs: Long,
            grams: Double,
            horizonMinutes: Int,
            cumulative: (Double) -> Double
        ): MealAbsorptionProjection {
            require(nowMs > 0 && hypotheticalStartMs > 0 && grams.isFinite() && grams >= 0.0)
            require(horizonMinutes in 5..720 && horizonMinutes % 5 == 0)
            val initialAge = (nowMs - hypotheticalStartMs) / 60_000.0
            val initialFraction = cumulative(initialAge)
            require(initialFraction.isFinite() && initialFraction in 0.0..1.0)
            val alreadyAbsorbed = grams * initialFraction
            var previousFuture = 0.0
            var previousFraction = initialFraction
            val points = (0..horizonMinutes / 5).map { step ->
                val age = initialAge + step * 5.0
                val fraction = cumulative(age)
                require(fraction.isFinite() && fraction in previousFraction..1.0)
                val future = (grams * fraction - alreadyAbsorbed).coerceAtLeast(0.0)
                val point = MealAbsorptionPoint(step * 5, (future - previousFuture).coerceAtLeast(0.0),
                    future, grams * (1.0 - fraction))
                previousFuture = future
                previousFraction = fraction
                point
            }
            return MealAbsorptionProjection(nowMs, alreadyAbsorbed, points,
                grams == 0.0 || previousFraction >= 1.0)
        }
    }
}
