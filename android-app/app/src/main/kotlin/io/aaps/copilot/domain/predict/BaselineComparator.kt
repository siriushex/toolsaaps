package io.aaps.copilot.domain.predict

import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.domain.model.Forecast
import kotlin.math.abs

data class BaselineDelta(
    val algorithm: String,
    val horizonMinutes: Int,
    val deltaMmol: Double
)

class BaselineComparator {

    fun compare(
        forecasts: List<Forecast>,
        baseline: List<BaselinePointEntity>
    ): List<BaselineDelta> {
        if (forecasts.isEmpty() || baseline.isEmpty()) return emptyList()
        return forecasts.mapNotNull { forecast ->
            val candidate = baseline
                .filter { it.horizonMinutes == forecast.horizonMinutes }
                .minByOrNull { abs(it.timestamp - forecast.ts) }
            candidate?.let {
                BaselineDelta(
                    algorithm = it.algorithm,
                    horizonMinutes = forecast.horizonMinutes,
                    deltaMmol = forecast.valueMmol - it.valueMmol
                )
            }
        }
    }
}
