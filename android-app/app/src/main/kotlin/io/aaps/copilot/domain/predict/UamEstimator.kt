package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent

internal class UamEstimator {

    data class Context(
        val glucose: List<GlucosePoint>,
        val therapyEvents: List<TherapyEvent>,
        val nowTs: Long,
        val isfMmolPerUnit: Double,
        val csfMmolPerGram: Double,
        val enableVirtualMealFit: Boolean,
        val carbCumulativeForEvent: (TherapyEvent, Double) -> Double,
        val insulinCumulative: (Double) -> Double,
        val extractCarbsGrams: (TherapyEvent) -> Double?,
        val extractInsulinUnits: (TherapyEvent) -> Double?,
        val eventCanCarryInsulin: (TherapyEvent) -> Boolean
    )

    data class Result(
        val uci0: Double,
        val uciMax: Double,
        val k: Double,
        val steps: DoubleArray,
        val active: Boolean,
        val virtualMealCarbs: Double?,
        val virtualMealConfidence: Double?,
        val usingVirtualMeal: Boolean
    ) {
        companion object {
            fun zero(csf: Double): Result {
                val uciMax = MAX_UAM_ABSORB_RATE_GPH * csf * (5.0 / 60.0)
                return Result(
                    uci0 = 0.0,
                    uciMax = uciMax,
                    k = 0.0,
                    steps = DoubleArray(STEPS_MAX + 1),
                    active = false,
                    virtualMealCarbs = null,
                    virtualMealConfidence = null,
                    usingVirtualMeal = false
                )
            }
        }
    }

    private val historyByBucket = LinkedHashMap<Long, Double>()

    fun estimate(context: Context): Result {
        val glucose = context.glucose
        if (glucose.size < 2) return Result.zero(context.csfMmolPerGram)

        val nowBucket = context.nowTs / BUCKET_MS
        val prev = findPrev(glucose, context.nowTs)
        if (prev == null) {
            appendOrUpdateBucket(nowBucket, 0.0)
            return Result.zero(context.csfMmolPerGram)
        }

        val nowPoint = glucose.last()
        val dtMin = (nowPoint.ts - prev.ts) / 60_000.0
        if (dtMin !in 2.0..15.0) {
            appendOrUpdateBucket(nowBucket, 0.0)
            return Result.zero(context.csfMmolPerGram)
        }

        val sObsPer5 = (nowPoint.valueMmol - prev.valueMmol) / (dtMin / 5.0)
        val therapyPastDelta = intervalTherapyDelta(
            context = context,
            startTs = prev.ts,
            endTs = context.nowTs
        )
        val therapySlopePastPer5 = therapyPastDelta / (dtMin / 5.0)

        val dev0 = sObsPer5 - therapySlopePastPer5
        val uciMax = MAX_UAM_ABSORB_RATE_GPH * context.csfMmolPerGram * (5.0 / 60.0)
        var uci0 = maxOf(0.0, dev0)
        uci0 = uci0.coerceIn(0.0, uciMax)

        appendOrUpdateBucket(nowBucket, uci0)
        val k = estimateK(nowBucket = nowBucket, uci0 = uci0)

        val levelASteps = DoubleArray(STEPS_MAX + 1)
        for (j in 1..STEPS_MAX) {
            val step = j.toDouble()
            val uciSlope = maxOf(0.0, uci0 + step * k)
            val uciCap = maxOf(0.0, uci0 * (1.0 - step / 36.0))
            levelASteps[j] = minOf(uciSlope, uciCap)
        }

        var finalSteps = levelASteps
        var virtualCarbs: Double? = null
        var virtualConfidence: Double? = null
        var usingVirtual = false

        if (context.enableVirtualMealFit) {
            val fit = fitVirtualMeal(context, nowBucket)
            if (fit != null) {
                virtualCarbs = fit.carbsGrams
                virtualConfidence = fit.confidence
                if (fit.confidence >= VIRTUAL_MEAL_MIN_CONFIDENCE) {
                    finalSteps = buildVirtualMealSteps(context, fit)
                    usingVirtual = true
                }
            }
        }

        return Result(
            uci0 = uci0,
            uciMax = uciMax,
            k = k,
            steps = finalSteps,
            active = uci0 >= UAM_ACTIVE_THRESHOLD,
            virtualMealCarbs = virtualCarbs,
            virtualMealConfidence = virtualConfidence,
            usingVirtualMeal = usingVirtual
        )
    }

    private fun findPrev(glucose: List<GlucosePoint>, nowTs: Long): GlucosePoint? {
        for (i in glucose.lastIndex - 1 downTo 0) {
            val p = glucose[i]
            val dt = (nowTs - p.ts) / 60_000.0
            if (dt in 2.0..15.0) return p
            if (dt > 15.0) break
        }
        return null
    }

    private fun intervalTherapyDelta(context: Context, startTs: Long, endTs: Long): Double {
        var total = 0.0
        context.therapyEvents.asSequence()
            .filter { it.ts <= endTs }
            .filter { endTs - it.ts <= EVENT_LOOKBACK_MS }
            .forEach { event ->
                val age0 = maxOf(0.0, (startTs - event.ts) / 60_000.0)
                val age1 = maxOf(0.0, (endTs - event.ts) / 60_000.0)

                val carbs = context.extractCarbsGrams(event)
                if (carbs != null && carbs > 0.0) {
                    total += carbs * context.csfMmolPerGram *
                        maxOf(0.0, context.carbCumulativeForEvent(event, age1) - context.carbCumulativeForEvent(event, age0))
                }

                val insulin = context.extractInsulinUnits(event)
                if (insulin != null && insulin > 0.0 && context.eventCanCarryInsulin(event)) {
                    total += -insulin * context.isfMmolPerUnit *
                        maxOf(0.0, context.insulinCumulative(age1) - context.insulinCumulative(age0))
                }
            }
        return total
    }

    private fun appendOrUpdateBucket(bucket: Long, value: Double) {
        historyByBucket[bucket] = value
        val minBucket = bucket - 11
        val toDrop = historyByBucket.keys.filter { it < minBucket }
        toDrop.forEach { historyByBucket.remove(it) }
        while (historyByBucket.size > 12) {
            val oldest = historyByBucket.keys.firstOrNull() ?: break
            historyByBucket.remove(oldest)
        }
    }

    private fun estimateK(nowBucket: Long, uci0: Double): Double {
        if (historyByBucket.size < 3) return -uci0 / 12.0
        val maxEntry = historyByBucket.maxByOrNull { it.value } ?: return -uci0 / 12.0
        val minEntry = historyByBucket.minByOrNull { it.value } ?: return -uci0 / 12.0

        val stepsMax = maxOf(1.0, (nowBucket - maxEntry.key).toDouble())
        val stepsMin = maxOf(1.0, (nowBucket - minEntry.key).toDouble())
        val kMax = (uci0 - maxEntry.value) / stepsMax
        val kMin = (uci0 - minEntry.value) / stepsMin
        val k = minOf(kMax, -kMin / 3.0)
        return if (k.isFinite()) k else -uci0 / 12.0
    }

    private data class Interval(
        val startTs: Long,
        val endTs: Long,
        val residualPlus: Double,
        val weight: Double
    )

    private data class VirtualMealFit(
        val tStarTs: Long,
        val carbsGrams: Double,
        val confidence: Double,
        val sse: Double
    )

    private fun fitVirtualMeal(context: Context, nowBucket: Long): VirtualMealFit? {
        val nowTs = context.nowTs
        val intervals = context.glucose.zipWithNext().mapNotNull { (prev, curr) ->
            if (curr.ts > nowTs || curr.ts < nowTs - VIRTUAL_MEAL_LOOKBACK_MS) return@mapNotNull null
            val dt = (curr.ts - prev.ts) / 60_000.0
            if (dt !in 2.0..15.0) return@mapNotNull null

            val dGObs = curr.valueMmol - prev.valueMmol
            val dTherapy = intervalTherapyDelta(
                context = context,
                startTs = prev.ts,
                endTs = curr.ts
            )
            val residualPlus = maxOf(0.0, dGObs - dTherapy)
            Interval(
                startTs = prev.ts,
                endTs = curr.ts,
                residualPlus = residualPlus,
                weight = 1.0
            )
        }

        if (intervals.size < 3) return null
        val denomResidual = intervals.sumOf { it.weight * it.residualPlus * it.residualPlus }
        if (denomResidual <= EPS) return null

        var best: VirtualMealFit? = null
        for (bucket in (nowBucket - 12) until nowBucket) {
            val tStarTs = bucket * BUCKET_MS
            val syntheticEvent = TherapyEvent(
                ts = tStarTs,
                type = "carbs",
                payload = mapOf("grams" to "1")
            )
            var numerator = 0.0
            var denominator = 0.0
            val regressors = DoubleArray(intervals.size)

            intervals.forEachIndexed { idx, interval ->
                val agePrev = maxOf(0.0, (interval.startTs - tStarTs) / 60_000.0)
                val ageCurr = maxOf(0.0, (interval.endTs - tStarTs) / 60_000.0)
                val a = context.csfMmolPerGram *
                    maxOf(0.0, context.carbCumulativeForEvent(syntheticEvent, ageCurr) -
                        context.carbCumulativeForEvent(syntheticEvent, agePrev))
                regressors[idx] = a
                numerator += interval.weight * interval.residualPlus * a
                denominator += interval.weight * a * a
            }

            if (denominator <= EPS) continue
            val carbs = maxOf(0.0, numerator / denominator)
            if (carbs !in 5.0..90.0) continue

            var sse = 0.0
            intervals.forEachIndexed { idx, interval ->
                val err = interval.residualPlus - carbs * regressors[idx]
                sse += interval.weight * err * err
            }

            if (best == null || sse < best!!.sse) {
                best = VirtualMealFit(
                    tStarTs = tStarTs,
                    carbsGrams = carbs,
                    confidence = 0.0,
                    sse = sse
                )
            }
        }

        val candidate = best ?: return null
        val confidence = (1.0 - candidate.sse / (denomResidual + EPS)).coerceIn(0.0, 1.0)
        return candidate.copy(confidence = confidence)
    }

    private fun buildVirtualMealSteps(context: Context, fit: VirtualMealFit): DoubleArray {
        val out = DoubleArray(STEPS_MAX + 1)
        val age0 = maxOf(0.0, (context.nowTs - fit.tStarTs) / 60_000.0)
        val syntheticEvent = TherapyEvent(
            ts = fit.tStarTs,
            type = "carbs",
            payload = mapOf("grams" to fit.carbsGrams.toString())
        )
        for (j in 1..STEPS_MAX) {
            val ageA = age0 + (j - 1) * 5.0
            val ageB = age0 + j * 5.0
            out[j] = fit.carbsGrams * context.csfMmolPerGram *
                maxOf(0.0, context.carbCumulativeForEvent(syntheticEvent, ageB) - context.carbCumulativeForEvent(syntheticEvent, ageA))
        }
        return out
    }

    private companion object {
        const val BUCKET_MS = 300_000L
        const val STEPS_MAX = 12
        const val EVENT_LOOKBACK_MS = 8 * 60 * 60_000L
        const val VIRTUAL_MEAL_LOOKBACK_MS = 60 * 60_000L

        const val UAM_ACTIVE_THRESHOLD = 0.10
        const val MAX_UAM_ABSORB_RATE_GPH = 30.0
        const val VIRTUAL_MEAL_MIN_CONFIDENCE = 0.55

        const val EPS = 1e-9
    }
}
