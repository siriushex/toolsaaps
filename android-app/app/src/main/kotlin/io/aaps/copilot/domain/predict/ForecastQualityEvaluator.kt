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
        glucose: List<GlucoseSampleEntity>,
        evaluatedAt: Long
    ): List<ForecastQualityMetrics> {
        if (forecasts.isEmpty() || glucose.isEmpty()) return emptyList()

        val sortedGlucose = glucose.filter { it.timestamp <= evaluatedAt }.sortedBy { it.timestamp }
        if (sortedGlucose.isEmpty()) return emptyList()
        val byHorizon = forecasts.groupBy { it.horizonMinutes }
        return byHorizon.mapNotNull { (horizon, rows) ->
            val toleranceMs = if (horizon <= 10) 5 * 60 * 1000L else 15 * 60 * 1000L

            val errors = rows.mapNotNull { forecast ->
                val actualMmol = actualAt(sortedGlucose, forecast.timestamp, toleranceMs) ?: return@mapNotNull null
                val absError = abs(actualMmol - forecast.valueMmol)
                val sqError = absError * absError
                val ard = if (actualMmol > 0.0) absError / actualMmol else 0.0
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

    private fun actualAt(
        samples: List<GlucoseSampleEntity>,
        targetTs: Long,
        toleranceMs: Long
    ): Double? {
        // Nearest-neighbour tolerance must not turn an unobserved future into an outcome.
        if (targetTs < samples.first().timestamp || targetTs > samples.last().timestamp) return null
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi).ushr(1)
            if (samples[mid].timestamp < targetTs) lo = mid + 1 else hi = mid
        }
        val after = samples.getOrNull(lo)
        if (after?.timestamp == targetTs) return after.mmol
        val before = samples.getOrNull(lo - 1)
        if (before != null && after != null) {
            val beforeDelta = targetTs - before.timestamp
            val afterDelta = after.timestamp - targetTs
            val totalGap = after.timestamp - before.timestamp
            if (beforeDelta <= toleranceMs && afterDelta <= toleranceMs && totalGap in 1..(2 * toleranceMs)) {
                val ratio = beforeDelta.toDouble() / totalGap.toDouble()
                return before.mmol + (after.mmol - before.mmol) * ratio
            }
        }

        val candidate = when {
            before == null -> after
            after == null -> before
            targetTs - before.timestamp <= after.timestamp - targetTs -> before
            else -> after
        } ?: return null
        return candidate.mmol.takeIf { abs(candidate.timestamp - targetTs) <= toleranceMs }
    }
}
