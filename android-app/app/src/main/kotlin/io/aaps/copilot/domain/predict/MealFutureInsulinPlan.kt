package io.aaps.copilot.domain.predict

import java.util.Collections

/** Hypothetical delivered impulse, not a pump command or a basal rate. */
internal data class MealFutureInsulinDelivery(val offsetMinutes: Int, val units: Double) {
    init {
        require(offsetMinutes in 0..720)
        require(units.isFinite() && units > 0.0)
    }
}

/** Explicit research scenario; an empty plan does not prove the pump will stop. */
internal class MealFutureInsulinPlan(val predictionAtMs: Long, deliveries: List<MealFutureInsulinDelivery>) {
    init { require(predictionAtMs > 0 && deliveries.size <= 145) }
    val deliveries: List<MealFutureInsulinDelivery> = Collections.unmodifiableList(deliveries.sortedBy { it.offsetMinutes })
    val totalUnits: Double = this.deliveries.sumOf { it.units }
    init {
        require(this.deliveries.map { it.offsetMinutes }.distinct().size == this.deliveries.size)
        require(totalUnits.isFinite())
    }
}

internal class MealFutureInsulinProjection(
    stepEffectsMmol: List<Double>,
    val remainingUnitsAtHorizon: Double
) {
    val stepEffectsMmol: List<Double> = Collections.unmodifiableList(stepEffectsMmol.toList())
}
