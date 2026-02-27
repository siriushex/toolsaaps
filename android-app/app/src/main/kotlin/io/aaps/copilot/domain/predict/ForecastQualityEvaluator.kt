package io.aaps.copilot.domain.predict

import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import kotlin.math.abs
import kotlin.math.sqrt

data class ForecastQualityMetrics(
    val horizonMinutes: Int,
    val sampleCount: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double
)

class ForecastQualityEvaluator {

    fun evaluate(
        forecasts: List<ForecastEntity>,
        glucose: List<GlucoseSampleEntity>
    ): List<ForecastQualityMetrics> {
        if (forecasts.isEmpty() || glucose.isEmpty()) return emptyList()

        val byHorizon = forecasts.groupBy { it.horizonMinutes }
        return byHorizon.mapNotNull { (horizon, rows) ->
            val toleranceMs = if (horizon <= 10) 5 * 60 * 1000L else 15 * 60 * 1000L

            val errors = rows.mapNotNull { forecast ->
                val actual = closestGlucose(glucose, forecast.timestamp, toleranceMs) ?: return@mapNotNull null
                val absError = abs(actual.mmol - forecast.valueMmol)
                val sqError = absError * absError
                val ard = if (actual.mmol > 0.0) absError / actual.mmol else 0.0
                Triple(absError, sqError, ard)
            }

            if (errors.isEmpty()) return@mapNotNull null
            val n = errors.size.toDouble()
            val mae = errors.sumOf { it.first } / n
            val rmse = sqrt(errors.sumOf { it.second } / n)
            val mard = errors.sumOf { it.third } / n * 100.0

            ForecastQualityMetrics(
                horizonMinutes = horizon,
                sampleCount = errors.size,
                mae = mae,
                rmse = rmse,
                mardPct = mard
            )
        }.sortedBy { it.horizonMinutes }
    }

    private fun closestGlucose(
        samples: List<GlucoseSampleEntity>,
        targetTs: Long,
        toleranceMs: Long
    ): GlucoseSampleEntity? {
        val candidate = samples.minByOrNull { abs(it.timestamp - targetTs) } ?: return null
        return candidate.takeIf { abs(candidate.timestamp - targetTs) <= toleranceMs }
    }
}
