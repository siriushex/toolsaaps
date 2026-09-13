package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.model.DataQuality

class DeliveryTrustEvaluator(
    private val config: DeliveryTrustConfig = DeliveryTrustConfig()
) {

    fun evaluate(input: DeliveryTrustInput): DeliveryTrustState {
        if (input.sensorTrust != SensorTrustState.TRUSTED) return DeliveryTrustState.UNKNOWN
        if (input.evaluationTimestamp < 0L) return DeliveryTrustState.UNKNOWN
        if (input.iobUnits.isMalformedNonNegative() ||
            input.effectiveCobGrams.isMalformedNonNegative() ||
            input.setAgeHours.isMalformedNonNegative()
        ) {
            return DeliveryTrustState.UNKNOWN
        }

        val carbEvidence = carbEvidence(input)
        if (carbEvidence == CarbEvidence.MALFORMED) return DeliveryTrustState.UNKNOWN

        if ((input.effectiveCobGrams ?: 0.0) > config.maximumUnconfoundedCobGrams ||
            input.uamActive == true ||
            carbEvidence == CarbEvidence.RECENT ||
            input.setAgeHours?.let { it < config.minimumSetAgeHours } == true
        ) {
            return DeliveryTrustState.NORMAL
        }

        return when (persistentRise(input)) {
            RiseEvidence.MISSING -> DeliveryTrustState.UNKNOWN
            RiseEvidence.ABSENT -> DeliveryTrustState.NORMAL
            RiseEvidence.PRESENT -> classifyCompleteEvidence(input, carbEvidence)
        }
    }

    private fun classifyCompleteEvidence(
        input: DeliveryTrustInput,
        carbEvidence: CarbEvidence
    ): DeliveryTrustState {
        val iob = input.iobUnits ?: return DeliveryTrustState.UNKNOWN
        val cob = input.effectiveCobGrams ?: return DeliveryTrustState.UNKNOWN
        val uam = input.uamActive ?: return DeliveryTrustState.UNKNOWN
        val setAge = input.setAgeHours ?: return DeliveryTrustState.UNKNOWN
        if (carbEvidence == CarbEvidence.UNKNOWN) return DeliveryTrustState.UNKNOWN

        return if (iob >= config.minimumIobUnits &&
            cob <= config.maximumUnconfoundedCobGrams &&
            !uam &&
            setAge >= config.minimumSetAgeHours
        ) {
            DeliveryTrustState.SUSPECTED_NONRESPONSE
        } else {
            DeliveryTrustState.NORMAL
        }
    }

    private fun carbEvidence(input: DeliveryTrustInput): CarbEvidence {
        if (!input.announcedCarbsKnown) return CarbEvidence.UNKNOWN
        val lookbackMs = config.announcedCarbLookbackMinutes * MINUTE_MS
        var recent = false
        input.announcedCarbTimestamps.forEach { timestamp ->
            if (timestamp < 0L || timestamp > input.evaluationTimestamp) {
                return CarbEvidence.MALFORMED
            }
            val age = input.evaluationTimestamp - timestamp
            if (age in 0L..lookbackMs) recent = true
        }
        return if (recent) CarbEvidence.RECENT else CarbEvidence.NONE
    }

    private fun persistentRise(input: DeliveryTrustInput): RiseEvidence {
        val eligible = input.canonicalGlucose.filter { it.ts in 0L..input.evaluationTimestamp }.sortedBy { it.ts }
        if (eligible.size < REQUIRED_POINTS) return RiseEvidence.MISSING

        // Sample observed values by elapsed time, never by transport row count.
        // Interpolation or choosing one of conflicting duplicates would invent evidence.
        val minimumCadenceMs = (config.expectedCadenceMinutes - config.cadenceToleranceMinutes) * MINUTE_MS
        val maximumCadenceMs = (config.expectedCadenceMinutes + config.cadenceToleranceMinutes) * MINUTE_MS
        val expectedCadenceMs = config.expectedCadenceMinutes * MINUTE_MS
        val timestamps = eligible.map { it.ts }.distinct()
        val latestAge = input.evaluationTimestamp - timestamps.last()
        if (latestAge !in 0L..maximumCadenceMs) return RiseEvidence.MISSING
        val selectedTimestampsInOrder = sampleTimestamps(
            timestamps,
            minimumCadenceMs,
            maximumCadenceMs,
            expectedCadenceMs,
            config.minimumRiseSpanMinutes * MINUTE_MS
        ) ?: return RiseEvidence.MISSING
        val byTimestamp = eligible.groupBy { it.ts }
        val points = selectedTimestampsInOrder.map { byTimestamp.getValue(it).first() }
        val selectedTimestamps = points.mapTo(mutableSetOf()) { it.ts }
        val duplicateConflict = eligible.filter { it.ts in selectedTimestamps }.groupBy { it.ts }.values.any { group ->
            group.any { it.quality != DataQuality.OK || !it.valueMmol.isFinite() } ||
                group.any { it.valueMmol != group.first().valueMmol }
        }
        if (duplicateConflict) return RiseEvidence.MISSING
        if (points.any {
                it.ts < 0L || !it.valueMmol.isFinite() || it.quality != DataQuality.OK
            }
        ) {
            return RiseEvidence.MISSING
        }

        val gaps = points.zipWithNext { previous, next -> next.ts - previous.ts }
        if (gaps.any { it !in minimumCadenceMs..maximumCadenceMs }) return RiseEvidence.MISSING

        val span = points.last().ts - points.first().ts
        if (span < config.minimumRiseSpanMinutes * MINUTE_MS) return RiseEvidence.MISSING

        val strictlyIncreasing = points.zipWithNext().all { (previous, next) ->
            next.valueMmol > previous.valueMmol
        }
        val totalRise = points.last().valueMmol - points.first().valueMmol
        return if (strictlyIncreasing && totalRise + EPSILON >= config.minimumRiseMmol) {
            RiseEvidence.PRESENT
        } else {
            RiseEvidence.ABSENT
        }
    }

    private fun sampleTimestamps(
        timestamps: List<Long>,
        minimumGap: Long,
        maximumGap: Long,
        expectedGap: Long,
        minimumSpan: Long
    ): List<Long>? {
        if (timestamps.size < REQUIRED_POINTS) return null
        // Record temporal reachability before inspecting glucose. A locally nearest
        // sample may leave no complete 15-minute path when sensor cadence jitters.
        val earliestStart = Array(REQUIRED_POINTS) { LongArray(timestamps.size) { Long.MAX_VALUE } }
        timestamps.forEachIndexed { index, timestamp -> earliestStart[0][index] = timestamp }
        for (gaps in 1 until REQUIRED_POINTS) {
            for (end in timestamps.indices) {
                for (start in end - 1 downTo 0) {
                    val gap = timestamps[end] - timestamps[start]
                    if (gap > maximumGap) break
                    if (gap >= minimumGap) {
                        earliestStart[gaps][end] = minOf(earliestStart[gaps][end], earliestStart[gaps - 1][start])
                    }
                }
            }
        }
        val latestStart = timestamps.last() - minimumSpan
        if (earliestStart[REQUIRED_POINTS - 1].last() > latestStart) return null
        var end = timestamps.lastIndex
        val selected = mutableListOf(timestamps[end])
        for (gaps in REQUIRED_POINTS - 1 downTo 1) {
            end = (0 until end).asSequence()
                .filter { index ->
                    timestamps[end] - timestamps[index] in minimumGap..maximumGap &&
                        earliestStart[gaps - 1][index] <= latestStart
                }
                .minByOrNull { index -> kotlin.math.abs(timestamps[end] - timestamps[index] - expectedGap) }
                ?: return null
            selected += timestamps[end]
        }
        return selected.asReversed()
    }

    private fun Double?.isMalformedNonNegative(): Boolean =
        this != null && (!isFinite() || this < 0.0)

    private enum class CarbEvidence { NONE, RECENT, UNKNOWN, MALFORMED }

    private enum class RiseEvidence { PRESENT, ABSENT, MISSING }

    private companion object {
        const val REQUIRED_POINTS = 4
        const val MINUTE_MS = 60_000L
        const val EPSILON = 1e-9
    }
}
