package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.Forecast
import java.util.Collections

/** Research output only. Engine intervals are not calibrated for this candidate. */
internal class PersonalMealForecast(forecasts: List<Forecast>, val diagnostics: HybridPredictionEngine.V3Diagnostics) {
    val forecasts: List<Forecast> = Collections.unmodifiableList(forecasts.toList())
    val clinicalInfluenceAllowed: Boolean get() = false
}

internal data class MealInsulinKernelIdentity(val id: String, val durationMinutes: Int)
