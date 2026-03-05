package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.model.DayType
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class IsfCrBaseFitter {

    data class FitOutput(
        val hourlyIsf: List<Double?>,
        val hourlyCr: List<Double?>,
        val weekdayHourlyIsf: List<Double?>,
        val weekendHourlyIsf: List<Double?>,
        val weekdayHourlyCr: List<Double?>,
        val weekendHourlyCr: List<Double?>,
        val metrics: Map<String, Double>
    )

    fun fit(
        evidence: List<IsfCrEvidenceSample>,
        defaults: Pair<Double, Double>
    ): FitOutput {
        val isfByHour = evidence.filter { it.sampleType == IsfCrSampleType.ISF }.groupBy { it.hourLocal }
        val crByHour = evidence.filter { it.sampleType == IsfCrSampleType.CR }.groupBy { it.hourLocal }
        val rawIsf = MutableList<Double?>(24) { null }
        val rawCr = MutableList<Double?>(24) { null }

        (0..23).forEach { hour ->
            rawIsf[hour] = robustWeightedLogMean(isfByHour[hour].orEmpty())
            rawCr[hour] = robustWeightedLogMean(crByHour[hour].orEmpty())
        }

        val filledIsf = fillMissing(rawIsf, defaults.first)
        val filledCr = fillMissing(rawCr, defaults.second)
        val constrainedIsf = constrainHourlyJumps(filledIsf)
        val constrainedCr = constrainHourlyJumps(filledCr)
        val smoothIsf = circularSmooth(constrainedIsf, alpha = 0.35)
        val smoothCr = circularSmooth(constrainedCr, alpha = 0.35)

        val weekdayIsf = fitDayTypeHourly(
            samples = evidence.filter {
                it.sampleType == IsfCrSampleType.ISF &&
                    it.dayType == DayType.WEEKDAY
            },
            fallback = defaults.first
        )
        val weekendIsf = fitDayTypeHourly(
            samples = evidence.filter {
                it.sampleType == IsfCrSampleType.ISF &&
                    it.dayType == DayType.WEEKEND
            },
            fallback = defaults.first
        )
        val weekdayCr = fitDayTypeHourly(
            samples = evidence.filter {
                it.sampleType == IsfCrSampleType.CR &&
                    it.dayType == DayType.WEEKDAY
            },
            fallback = defaults.second
        )
        val weekendCr = fitDayTypeHourly(
            samples = evidence.filter {
                it.sampleType == IsfCrSampleType.CR &&
                    it.dayType == DayType.WEEKEND
            },
            fallback = defaults.second
        )

        return FitOutput(
            hourlyIsf = smoothIsf,
            hourlyCr = smoothCr,
            weekdayHourlyIsf = weekdayIsf,
            weekendHourlyIsf = weekendIsf,
            weekdayHourlyCr = weekdayCr,
            weekendHourlyCr = weekendCr,
            metrics = mapOf(
                "isfEvidenceCount" to isfByHour.values.sumOf { it.size }.toDouble(),
                "crEvidenceCount" to crByHour.values.sumOf { it.size }.toDouble(),
                "isfCoverageHours" to rawIsf.count { it != null }.toDouble(),
                "crCoverageHours" to rawCr.count { it != null }.toDouble(),
                "weekdayIsfCoverageHours" to weekdayIsf.count { it != null }.toDouble(),
                "weekendIsfCoverageHours" to weekendIsf.count { it != null }.toDouble(),
                "weekdayCrCoverageHours" to weekdayCr.count { it != null }.toDouble(),
                "weekendCrCoverageHours" to weekendCr.count { it != null }.toDouble()
            )
        )
    }

    private fun fitDayTypeHourly(
        samples: List<IsfCrEvidenceSample>,
        fallback: Double
    ): List<Double?> {
        if (samples.isEmpty()) return List(24) { null }
        val grouped = samples.groupBy { it.hourLocal }
        val raw = MutableList<Double?>(24) { null }
        (0..23).forEach { hour ->
            raw[hour] = robustWeightedLogMean(grouped[hour].orEmpty())
        }
        val observedHours = raw.mapIndexedNotNull { hour, value ->
            if (value != null) hour else null
        }
        if (observedHours.size < MIN_DAYTYPE_COVERAGE_HOURS) {
            return List(24) { null }
        }

        val filled = fillMissing(raw, fallback)
        val constrained = constrainHourlyJumps(filled)
        val smooth = circularSmooth(constrained, alpha = 0.35)
        if (observedHours.size >= MIN_DAYTYPE_FULL_EXPANSION_COVERAGE_HOURS) {
            return smooth.map { it.coerceAtLeast(MIN_POSITIVE_VALUE) }
        }
        return smooth.mapIndexed { hour, value ->
            val localDistance = observedHours.minOfOrNull { observed ->
                circularHourDistance(hour, observed)
            } ?: 24
            if (localDistance <= DAYTYPE_LOCAL_EXPANSION_HOURS) {
                value.coerceAtLeast(MIN_POSITIVE_VALUE)
            } else {
                null
            }
        }
    }

    private fun robustWeightedLogMean(samples: List<IsfCrEvidenceSample>): Double? {
        if (samples.isEmpty()) return null
        val weighted = samples.mapNotNull { sample ->
            if (sample.value <= 0.0) return@mapNotNull null
            val w = (sample.weight * sample.qualityScore).coerceIn(0.01, 1.0)
            ln(sample.value) to w
        }
        if (weighted.isEmpty()) return null
        val center = weightedQuantile(weighted, 0.5) ?: return null
        val madSamples = weighted.map { (value, weight) ->
            kotlin.math.abs(value - center) to weight
        }
        val mad = weightedQuantile(madSamples, 0.5)?.coerceAtLeast(ROBUST_SCALE_EPSILON) ?: ROBUST_SCALE_EPSILON
        val scale = (mad * 1.4826).coerceAtLeast(ROBUST_SCALE_EPSILON)

        val reweighted = weighted.mapNotNull { (value, weight) ->
            val z = kotlin.math.abs(value - center) / scale
            val huber = if (z <= ROBUST_HUBER_CUTOFF) 1.0 else ROBUST_HUBER_CUTOFF / z
            val tukey = if (z <= ROBUST_TUKEY_CUTOFF) {
                val ratio = z / ROBUST_TUKEY_CUTOFF
                (1.0 - ratio * ratio).pow(2.0)
            } else {
                0.0
            }
            val combinedWeight = (weight * huber * tukey).coerceAtLeast(0.0)
            if (combinedWeight <= 0.0) {
                null
            } else {
                value to combinedWeight
            }
        }

        val active = if (reweighted.isNotEmpty()) reweighted else weighted
        val totalWeight = active.sumOf { it.second }.coerceAtLeast(1e-9)
        val mean = active.sumOf { it.first * it.second } / totalWeight
        return exp(mean)
    }

    private fun weightedQuantile(values: List<Pair<Double, Double>>, quantile: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values
            .filter { (_, weight) -> weight > 0.0 }
            .sortedBy { it.first }
        if (sorted.isEmpty()) return null
        val totalWeight = sorted.sumOf { it.second }.coerceAtLeast(1e-9)
        val target = totalWeight * quantile.coerceIn(0.0, 1.0)
        var acc = 0.0
        sorted.forEach { (value, weight) ->
            acc += weight
            if (acc >= target) return value
        }
        return sorted.last().first
    }

    private fun fillMissing(values: List<Double?>, fallback: Double): List<Double> {
        val nonNull = values.filterNotNull()
        val medianFallback = nonNull.sorted().let {
            if (it.isEmpty()) fallback else it[it.size / 2]
        }
        return values.map { it ?: medianFallback }
    }

    private fun constrainHourlyJumps(values: List<Double>): List<Double> {
        if (values.isEmpty()) return values
        val out = values.toMutableList()
        for (i in out.indices) {
            val prev = out[(i - 1 + out.size) % out.size]
            val low = prev * 0.75
            val high = prev * 1.25
            out[i] = out[i].coerceIn(low, high)
        }
        return out
    }

    private fun circularSmooth(values: List<Double>, alpha: Double): List<Double> {
        if (values.isEmpty()) return values
        val out = MutableList(values.size) { 0.0 }
        for (i in values.indices) {
            val prev = values[(i - 1 + values.size) % values.size]
            val next = values[(i + 1) % values.size]
            out[i] = (values[i] * (1.0 - alpha)) + ((prev + next) * 0.5 * alpha)
        }
        return out
    }

    private fun circularHourDistance(h1: Int, h2: Int): Int {
        val diff = kotlin.math.abs(h1 - h2)
        return minOf(diff, 24 - diff)
    }

    private companion object {
        private const val MIN_DAYTYPE_COVERAGE_HOURS = 4
        private const val MIN_DAYTYPE_FULL_EXPANSION_COVERAGE_HOURS = 8
        private const val DAYTYPE_LOCAL_EXPANSION_HOURS = 2
        private const val MIN_POSITIVE_VALUE = 1e-3
        private const val ROBUST_SCALE_EPSILON = 0.02
        private const val ROBUST_HUBER_CUTOFF = 2.5
        private const val ROBUST_TUKEY_CUTOFF = 4.5
    }
}
