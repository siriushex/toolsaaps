package io.aaps.copilot.domain.predict

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

internal class ResidualArModel {

    data class Params(
        val mu: Double,
        val phi: Double,
        val sigmaE: Double,
        val usedFallback: Boolean,
        val sampleCount: Int
    )

    private val historyByBucket = LinkedHashMap<Long, Double>()

    fun appendOrUpdate(nowTs: Long, residualRocPer5: Double) {
        val bucket = nowTs / BUCKET_MS
        historyByBucket[bucket] = residualRocPer5.coerceIn(-1.2, 1.2)
        trim(bucket)
    }

    fun fit(uamActive: Boolean, trendRocHalfLifeMin: Double): Params {
        val values = historyByBucket.values.toList()
        val m = values.size
        if (m < MIN_SAMPLES) {
            return Params(
                mu = 0.0,
                phi = fallbackPhi(trendRocHalfLifeMin),
                sigmaE = 0.10,
                usedFallback = true,
                sampleCount = m
            )
        }

        val weights = List(m) { i ->
            exp(-((m - 1 - i).toDouble()) / TAU)
        }

        val wSum = weights.sum().coerceAtLeast(EPS)
        var mu = values.indices.sumOf { i -> weights[i] * values[i] } / wSum
        mu = mu.coerceIn(-0.30, 0.30)
        if (uamActive) {
            mu = minOf(0.0, mu)
        }

        var num = 0.0
        var den = 0.0
        for (i in 1 until m) {
            val w = weights[i]
            val a = values[i] - mu
            val b = values[i - 1] - mu
            num += w * a * b
            den += w * b * b
        }
        val phi = (num / (den + EPS)).coerceIn(0.0, 0.97)

        var errNum = 0.0
        var errDen = 0.0
        for (i in 1 until m) {
            val w = weights[i]
            val e = (values[i] - mu) - phi * (values[i - 1] - mu)
            errNum += w * e * e
            errDen += w
        }
        val sigmaE = sqrt((errNum / (errDen + EPS)).coerceAtLeast(0.0)).coerceIn(0.05, 0.60)

        return Params(
            mu = mu,
            phi = phi,
            sigmaE = sigmaE,
            usedFallback = false,
            sampleCount = m
        )
    }

    fun forecastSteps(residualRoc0: Double, params: Params, steps: Int): DoubleArray {
        val out = DoubleArray(steps + 1)
        for (j in 1..steps) {
            val rec = params.phi.pow((j - 1).toDouble())
            out[j] = params.mu + rec * (residualRoc0 - params.mu)
        }
        return out
    }

    private fun trim(latestBucket: Long) {
        val minBucket = latestBucket - MAX_BUCKETS + 1
        val keysToDrop = historyByBucket.keys.filter { it < minBucket }
        keysToDrop.forEach { historyByBucket.remove(it) }
        while (historyByBucket.size > MAX_BUCKETS) {
            val oldest = historyByBucket.keys.firstOrNull() ?: break
            historyByBucket.remove(oldest)
        }
    }

    private fun fallbackPhi(trendRocHalfLifeMin: Double): Double {
        return exp(-ln(2.0) * 5.0 / trendRocHalfLifeMin)
    }

    private companion object {
        const val BUCKET_MS = 300_000L
        const val MAX_BUCKETS = 24L
        const val MIN_SAMPLES = 8
        const val TAU = 8.0
        const val EPS = 1e-9
    }
}
