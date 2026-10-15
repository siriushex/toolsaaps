package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.Forecast
import java.util.Collections

/** Conditional forward path only: observed history is fixed, future AAPS control is not simulated. */
internal class MealForwardForecast(
    val predictionAtMs: Long,
    forecasts: List<Forecast>,
    glucoseMmol: List<Double>,
    trendSteps: List<Double>,
    val remainingFoodGrams: Double,
    val remainingInsulinUnits: Double,
    val numericLimitsReached: Boolean,
    val hypotheticalFutureInsulinUnits: Double = 0.0
) {
    val forecasts: List<Forecast> = Collections.unmodifiableList(forecasts.toList())
    /** Five-minute grid including the engine's current filtered glucose at index zero. */
    val glucoseMmol: List<Double> = Collections.unmodifiableList(glucoseMmol.toList())
    val trendSteps: List<Double> = Collections.unmodifiableList(trendSteps.toList())
    val horizonMinutes: Int = (this.glucoseMmol.size - 1) * 5
    /** Completion of known component curves is not proof of physiological or controller coverage. */
    val modeledTailsComplete: Boolean = remainingFoodGrams == 0.0 && remainingInsulinUnits == 0.0
    val futureControlSimulated: Boolean = false
    val trajectoryUncertaintyValidated: Boolean = false

    init {
        require(horizonMinutes in 60..720 && this.trendSteps.size == this.glucoseMmol.size)
        require(this.glucoseMmol.all { it.isFinite() } && this.trendSteps.all { it.isFinite() })
        require(remainingFoodGrams.isFinite() && remainingFoodGrams >= 0.0)
        require(remainingInsulinUnits.isFinite() && remainingInsulinUnits >= 0.0)
        require(hypotheticalFutureInsulinUnits.isFinite() && hypotheticalFutureInsulinUnits >= 0.0)
    }
}
