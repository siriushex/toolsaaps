package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.profile.MealGlycemicIndex
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlin.math.pow

internal data class MealFoodDisplayInput(
    val grams: Double,
    val cumulativeAtOffset: (Int) -> Double,
    val glycemicIndex: MealGlycemicIndex? = null
)

/** Bounded food-only display estimate. Never an input to clinical pressure or target control. */
class MealFoodDisplayProjection private constructor(
    val predictionAtMs: Long,
    stepsMmol: List<Double>,
    val completeByHorizon: Boolean,
    val remainingAtHorizonGrams: Double,
    val giAdjustedMeals: Int
) {
    val stepsMmol: List<Double> = Collections.unmodifiableList(stepsMmol.toList())
    val modelVersion: String get() = MODEL_VERSION

    override fun equals(other: Any?): Boolean = this === other ||
        other is MealFoodDisplayProjection && predictionAtMs == other.predictionAtMs &&
        stepsMmol == other.stepsMmol && completeByHorizon == other.completeByHorizon &&
        remainingAtHorizonGrams.compareTo(other.remainingAtHorizonGrams) == 0 &&
        giAdjustedMeals == other.giAdjustedMeals

    override fun hashCode(): Int {
        var result = predictionAtMs.hashCode()
        result = 31 * result + stepsMmol.hashCode()
        result = 31 * result + completeByHorizon.hashCode()
        result = 31 * result + remainingAtHorizonGrams.hashCode()
        return 31 * result + giAdjustedMeals
    }

    companion object {
        const val MODEL_VERSION = "food_gi_shape_v1"
        const val MAX_HORIZON_MINUTES = 720

        internal fun build(
            predictionAtMs: Long,
            csfMmolPerGram: Double,
            meals: List<MealFoodDisplayInput>,
            horizonMinutes: Int = MAX_HORIZON_MINUTES
        ): MealFoodDisplayProjection? = try {
            require(horizonMinutes in 5..MAX_HORIZON_MINUTES && horizonMinutes % 5 == 0)
            require(predictionAtMs > 0 && predictionAtMs <= Long.MAX_VALUE - horizonMinutes * 60_000L)
            require(csfMmolPerGram.isFinite() && csfMmolPerGram > 0.0)
            val count = horizonMinutes / 5 + 1
            require(meals.size <= 5000 && meals.size.toLong() * count <= 1_000_000L)
            val steps = DoubleArray(count)
            var complete = true
            var residual = 0.0
            var lastRequiredStep = 0
            var adjusted = 0
            for (meal in meals) {
                require(meal.grams.isFinite() && meal.grams >= 0.0)
                if (meal.grams == 0.0) continue
                val initial = meal.cumulativeAtOffset(0)
                require(initial.isFinite() && initial in 0.0..1.0)
                val remainder = meal.grams * (1.0 - initial)
                if (remainder == 0.0) continue
                // Versioned display heuristic, not a calibrated physiological parameter.
                val exponent = meal.glycemicIndex?.let {
                    1.0 + 0.25 * ((60.0 - it.value) / 60.0).coerceIn(-1.0, 1.0)
                } ?: 1.0
                if (exponent != 1.0) adjusted += 1
                var previousFraction = initial
                var previousFuture = 0.0
                var finish = count - 1
                var finished = false
                for (index in 1 until count) {
                    val fraction = meal.cumulativeAtOffset(index * 5)
                    require(fraction.isFinite() && fraction in previousFraction..1.0)
                    val future = when {
                        exponent == 1.0 -> meal.grams * (fraction - initial)
                        fraction == initial -> 0.0
                        fraction == 1.0 -> remainder
                        else -> remainder * ((fraction - initial) / (1.0 - initial)).coerceIn(0.0, 1.0).pow(exponent)
                    }
                    require(future.isFinite() && future in previousFuture..remainder)
                    steps[index] += (future - previousFuture) * csfMmolPerGram
                    if (fraction == 1.0 && !finished) {
                        finish = index
                        finished = true
                    }
                    previousFraction = fraction
                    previousFuture = future
                }
                complete = complete && finished
                residual += remainder - previousFuture
                lastRequiredStep = maxOf(lastRequiredStep, finish)
            }
            require(steps.all { it.isFinite() && it >= 0.0 } && steps.sum().isFinite())
            require(residual.isFinite() && residual >= 0.0)
            val last = if (lastRequiredStep == 0) minOf(6, count - 1) else lastRequiredStep
            MealFoodDisplayProjection(predictionAtMs, steps.take(last + 1), complete, residual, adjusted)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            null
        }
    }
}
