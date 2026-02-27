package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlin.math.abs

data class CalculatedUamSignal(
    val ts: Long,
    val confidence: Double,
    val rise15Mmol: Double,
    val rise30Mmol: Double,
    val delta5Mmol: Double
)

data class UamCalculatorConfig(
    val minRise15Mmol: Double = 0.45,
    val minRise30Mmol: Double = 0.70,
    val minDelta5Mmol: Double = 0.10,
    val minConfidence: Double = 0.55,
    val minPositiveStepsRatio: Double = 0.66,
    val minCarbsSuppressGrams: Double = 5.0,
    val carbsSuppressBeforeMinutes: Int = 45,
    val carbsSuppressAfterMinutes: Int = 120,
    val maxSignalGapMinutes: Int = 15,
    val activeSignalMaxAgeMinutes: Int = 45,
    val activeSignalSustainAfterMinutes: Int = 20,
    val activeSignalMinRise15Mmol: Double = 0.20,
    val minGlucoseMmol: Double = 2.2,
    val maxGlucoseMmol: Double = 22.0,
    val maxDeltaPer5mMmol: Double = 3.0
)

object UamCalculator {

    private data class PreparedPoint(
        val ts: Long,
        val valueMmol: Double
    )

    fun detectSignals(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        config: UamCalculatorConfig = UamCalculatorConfig()
    ): List<CalculatedUamSignal> {
        val sorted = prepareSeries(glucose, config)
        if (sorted.size < 7) return emptyList()
        val rawSignals = mutableListOf<CalculatedUamSignal>()

        for (index in sorted.indices) {
            val current = sorted[index]
            val g5 = nearestPoint(sorted, current.ts - 5 * 60_000L, 4 * 60_000L) ?: continue
            val g15 = nearestPoint(sorted, current.ts - 15 * 60_000L, 8 * 60_000L) ?: continue
            val g30 = nearestPoint(sorted, current.ts - 30 * 60_000L, 10 * 60_000L) ?: continue

            val rise15 = current.valueMmol - g15.valueMmol
            val rise30 = current.valueMmol - g30.valueMmol
            val delta5 = current.valueMmol - g5.valueMmol
            val prevRate5 = (g5.valueMmol - g15.valueMmol) / 2.0
            val acceleration = delta5 - prevRate5
            val recentWindow = sorted.subList((index - 6).coerceAtLeast(0), index + 1)
            if (recentWindow.size < 4) continue
            val recentDeltas = recentWindow.zipWithNext { a, b -> b.valueMmol - a.valueMmol }
            val positiveSteps = recentDeltas.count { it >= config.minDelta5Mmol * 0.5 }
            val positiveRatio = positiveSteps.toDouble() / recentDeltas.size.toDouble()

            if (rise15 < config.minRise15Mmol) continue
            if (rise30 < config.minRise30Mmol) continue
            if (delta5 < config.minDelta5Mmol) continue
            if (positiveRatio < config.minPositiveStepsRatio) continue
            if (hasAnnouncedCarbsNearby(current.ts, therapyEvents, config)) continue

            val confidence = computeConfidence(
                rise15 = rise15,
                rise30 = rise30,
                delta5 = delta5,
                acceleration = acceleration,
                positiveRatio = positiveRatio,
                config = config
            )
            if (confidence < config.minConfidence) continue

            rawSignals += CalculatedUamSignal(
                ts = current.ts,
                confidence = confidence,
                rise15Mmol = rise15,
                rise30Mmol = rise30,
                delta5Mmol = delta5
            )
        }

        return collapseSignals(rawSignals, config.maxSignalGapMinutes)
    }

    fun latestSignal(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        nowTs: Long,
        lookbackMinutes: Int = 120,
        config: UamCalculatorConfig = UamCalculatorConfig()
    ): CalculatedUamSignal? {
        val prepared = prepareSeries(glucose, config)
        if (prepared.size < 4) return null
        val cutoff = nowTs - lookbackMinutes.coerceAtLeast(10) * 60_000L
        val latest = detectSignals(glucose, therapyEvents, config)
            .lastOrNull { it.ts >= cutoff }
            ?: return null

        val ageMinutes = ((nowTs - latest.ts).coerceAtLeast(0L) / 60_000L).toInt()
        if (ageMinutes > config.activeSignalMaxAgeMinutes) return null

        if (ageMinutes > config.activeSignalSustainAfterMinutes) {
            val nowPoint = nearestPoint(prepared, nowTs, 6 * 60_000L) ?: return null
            val p15 = nearestPoint(prepared, nowTs - 15 * 60_000L, 8 * 60_000L) ?: return null
            val sustainedRise15 = nowPoint.valueMmol - p15.valueMmol
            if (sustainedRise15 < config.activeSignalMinRise15Mmol) return null
        }

        return latest
    }

    fun estimateCarbsGrams(
        signal: CalculatedUamSignal?,
        isfMmolPerUnit: Double?,
        crGramPerUnit: Double?,
        fallbackCsfMmolPerGram: Double = 0.18
    ): Double {
        if (signal == null || signal.confidence < 0.55) return 0.0
        val csf = when {
            isfMmolPerUnit != null &&
                crGramPerUnit != null &&
                isfMmolPerUnit > 0.0 &&
                crGramPerUnit > 0.0 -> (isfMmolPerUnit / crGramPerUnit).coerceIn(0.05, 1.5)
            else -> fallbackCsfMmolPerGram.coerceIn(0.05, 1.5)
        }
        val effectiveRise = (0.60 * signal.rise30Mmol + 0.40 * signal.rise15Mmol).coerceIn(0.0, 4.5)
        val confidenceGain = 0.65 + 0.35 * signal.confidence
        return ((effectiveRise / csf) * confidenceGain).coerceIn(0.0, 80.0)
    }

    private fun prepareSeries(
        glucose: List<GlucosePoint>,
        config: UamCalculatorConfig
    ): List<PreparedPoint> {
        if (glucose.isEmpty()) return emptyList()
        val valid = glucose
            .asSequence()
            .filter { it.quality != DataQuality.SENSOR_ERROR }
            .filter { it.valueMmol in config.minGlucoseMmol..config.maxGlucoseMmol }
            .sortedBy { it.ts }
            .toList()
        if (valid.isEmpty()) return emptyList()

        val bucketed = valid
            .groupBy { it.ts / FIVE_MIN_MS }
            .values
            .map { bucket ->
                val sortedBucket = bucket.sortedBy { it.ts }
                PreparedPoint(
                    ts = sortedBucket.last().ts,
                    valueMmol = median(sortedBucket.map { it.valueMmol })
                )
            }
            .sortedBy { it.ts }

        if (bucketed.size < 3) return bucketed

        val cleaned = mutableListOf<PreparedPoint>()
        cleaned += bucketed.first()
        for (index in 1 until bucketed.lastIndex) {
            val prev = cleaned.last()
            val current = bucketed[index]
            val next = bucketed[index + 1]
            val jumpPrev = abs(current.valueMmol - prev.valueMmol)
            val jumpNext = abs(next.valueMmol - current.valueMmol)
            val bridge = abs(next.valueMmol - prev.valueMmol)
            val isolatedSpike = jumpPrev >= config.maxDeltaPer5mMmol &&
                jumpNext >= config.maxDeltaPer5mMmol &&
                bridge <= config.maxDeltaPer5mMmol * 0.5
            if (!isolatedSpike) cleaned += current
        }
        cleaned += bucketed.last()
        return cleaned
    }

    private fun nearestPoint(
        points: List<PreparedPoint>,
        targetTs: Long,
        maxDistanceMs: Long
    ): PreparedPoint? {
        val candidate = points
            .asSequence()
            .filter { it.ts in (targetTs - maxDistanceMs)..(targetTs + maxDistanceMs) }
            .minByOrNull { abs(it.ts - targetTs) }
            ?: return null
        return candidate
    }

    private fun hasAnnouncedCarbsNearby(
        ts: Long,
        events: List<TherapyEvent>,
        config: UamCalculatorConfig
    ): Boolean {
        val from = ts - config.carbsSuppressBeforeMinutes * 60_000L
        val to = ts + config.carbsSuppressAfterMinutes * 60_000L
        return events.any { event ->
            if (event.ts !in from..to) return@any false
            val type = event.type.lowercase()
            if (!type.contains("carbs") && !type.contains("meal")) return@any false
            val grams = extractCarbs(event) ?: return@any false
            grams >= config.minCarbsSuppressGrams
        }
    }

    private fun extractCarbs(event: TherapyEvent): Double? {
        val keys = listOf("grams", "carbs", "enteredCarbs", "mealCarbs")
        return keys.firstNotNullOfOrNull { key -> event.payload[key]?.replace(",", ".")?.toDoubleOrNull() }
    }

    private fun computeConfidence(
        rise15: Double,
        rise30: Double,
        delta5: Double,
        acceleration: Double,
        positiveRatio: Double,
        config: UamCalculatorConfig
    ): Double {
        val cRise15 = ((rise15 - config.minRise15Mmol) / 0.9).coerceIn(0.0, 1.0)
        val cRise30 = ((rise30 - config.minRise30Mmol) / 1.3).coerceIn(0.0, 1.0)
        val cDelta5 = ((delta5 - config.minDelta5Mmol) / 0.30).coerceIn(0.0, 1.0)
        val cAccel = ((acceleration - 0.02) / 0.15).coerceIn(0.0, 1.0)
        val cTrend = ((positiveRatio - config.minPositiveStepsRatio) / 0.34).coerceIn(0.0, 1.0)
        return (
            0.33 * cRise15 +
                0.30 * cRise30 +
                0.20 * cDelta5 +
                0.07 * cAccel +
                0.10 * cTrend
            ).coerceIn(0.0, 0.99)
    }

    private fun collapseSignals(
        signals: List<CalculatedUamSignal>,
        maxGapMinutes: Int
    ): List<CalculatedUamSignal> {
        if (signals.isEmpty()) return emptyList()
        val sorted = signals.sortedBy { it.ts }
        val maxGapMs = maxGapMinutes.coerceAtLeast(5) * 60_000L
        val collapsed = mutableListOf<CalculatedUamSignal>()
        var currentBest = sorted.first()

        for (index in 1 until sorted.size) {
            val next = sorted[index]
            if (next.ts - currentBest.ts <= maxGapMs) {
                if (next.confidence >= currentBest.confidence) currentBest = next
                continue
            }
            collapsed += currentBest
            currentBest = next
        }
        collapsed += currentBest
        return collapsed
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private const val FIVE_MIN_MS = 5 * 60_000L
}
