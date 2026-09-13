package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class UamRuntimeState { INACTIVE, SUSPECTED, ACTIVE, DECAYING, BLOCKED }

internal data class UnifiedUamInput(
    val nowTs: Long,
    val glucose: List<GlucosePoint>,
    val insulinImpactMmol5: Double,
    val announcedCarbImpactMmol5: Double = 0.0,
    val csfMmolPerGram: Double?,
    val sensorTrust: Double,
    val therapyCoverage: Double,
    val sensorBlocked: Boolean,
    val sensitivityRuntime: SensitivityRuntimeConsumerContext
) {
    init {
        require(sensitivityRuntime.consumer == SensitivityRuntimeConsumer.UAM) {
            "UnifiedUamInput requires UAM sensitivity context"
        }
    }
}

internal data class UnifiedUamRuntimeSnapshot(
    val timestamp: Long,
    val state: UamRuntimeState,
    val activeForForecast: Boolean,
    val activeForControl: Boolean,
    val impactMmolPer5m: Double,
    val signedResidualMmolPer5m: Double,
    val shortAverageDeltaMmol5: Double,
    private var forecastStepsStorage: DoubleArray,
    val equivalentCarbsGrams: Double?,
    val supportedLowerBoundCarbsGrams: Double?,
    val confidence: Double,
    val onsetTs: Long?,
    val firstDetectionTs: Long?,
    val activeSinceTs: Long?,
    val supportStableBuckets: Int,
    val lowerBoundStableBuckets: Int,
    val sensorTrust: Double,
    val therapyCoverage: Double,
    val source: String,
    val reasons: Set<String>,
    val sensitivityRuntime: SensitivityRuntimeConsumerContext
) {
    init {
        require(sensitivityRuntime.consumer == SensitivityRuntimeConsumer.UAM) {
            "UnifiedUamRuntimeSnapshot requires UAM sensitivity context"
        }
        forecastStepsStorage = forecastStepsStorage.copyOf()
    }

    val forecastStepsMmol: DoubleArray
        get() = forecastStepsStorage.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnifiedUamRuntimeSnapshot) return false

        return timestamp == other.timestamp &&
            state == other.state &&
            activeForForecast == other.activeForForecast &&
            activeForControl == other.activeForControl &&
            impactMmolPer5m.toBits() == other.impactMmolPer5m.toBits() &&
            signedResidualMmolPer5m.toBits() == other.signedResidualMmolPer5m.toBits() &&
            shortAverageDeltaMmol5.toBits() == other.shortAverageDeltaMmol5.toBits() &&
            forecastStepsStorage.contentEquals(other.forecastStepsStorage) &&
            equivalentCarbsGrams?.toBits() == other.equivalentCarbsGrams?.toBits() &&
            supportedLowerBoundCarbsGrams?.toBits() == other.supportedLowerBoundCarbsGrams?.toBits() &&
            confidence.toBits() == other.confidence.toBits() &&
            onsetTs == other.onsetTs &&
            firstDetectionTs == other.firstDetectionTs &&
            activeSinceTs == other.activeSinceTs &&
            supportStableBuckets == other.supportStableBuckets &&
            lowerBoundStableBuckets == other.lowerBoundStableBuckets &&
            sensorTrust.toBits() == other.sensorTrust.toBits() &&
            therapyCoverage.toBits() == other.therapyCoverage.toBits() &&
            source == other.source &&
            reasons == other.reasons &&
            sensitivityRuntime == other.sensitivityRuntime
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + activeForForecast.hashCode()
        result = 31 * result + activeForControl.hashCode()
        result = 31 * result + impactMmolPer5m.hashCode()
        result = 31 * result + signedResidualMmolPer5m.hashCode()
        result = 31 * result + shortAverageDeltaMmol5.hashCode()
        result = 31 * result + forecastStepsStorage.contentHashCode()
        result = 31 * result + (equivalentCarbsGrams?.hashCode() ?: 0)
        result = 31 * result + (supportedLowerBoundCarbsGrams?.hashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (onsetTs?.hashCode() ?: 0)
        result = 31 * result + (firstDetectionTs?.hashCode() ?: 0)
        result = 31 * result + (activeSinceTs?.hashCode() ?: 0)
        result = 31 * result + supportStableBuckets
        result = 31 * result + lowerBoundStableBuckets
        result = 31 * result + sensorTrust.hashCode()
        result = 31 * result + therapyCoverage.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + reasons.hashCode()
        result = 31 * result + sensitivityRuntime.hashCode()
        return result
    }
}

internal object UnifiedUamEstimator {

    internal data class StableLowerBoundTail(
        val count: Int,
        val confirmedMinimumGrams: Double?
    )

    fun estimate(input: UnifiedUamInput): UnifiedUamRuntimeSnapshot {
        val sensorTrust = input.sensorTrust.finiteOrZero().coerceIn(0.0, 1.0)
        val therapyCoverage = input.therapyCoverage.finiteOrZero().coerceIn(0.0, 1.0)
        val csf = input.csfMmolPerGram?.takeIf {
            it.isFinite() && it in MIN_CSF_MMOL_PER_GRAM..MAX_CSF_MMOL_PER_GRAM
        }
        val insulinImpactValid = input.insulinImpactMmol5.isFinite()
        val announcedCarbImpactValid = input.announcedCarbImpactMmol5.isFinite() &&
            input.announcedCarbImpactMmol5 >= 0.0
        val knownTherapyImpact = input.insulinImpactMmol5 + input.announcedCarbImpactMmol5
        val knownTherapyImpactValid = insulinImpactValid &&
            announcedCarbImpactValid &&
            knownTherapyImpact.isFinite()
        val causalRaw = deduplicateCausalRaw(input.glucose, input.nowTs)
        val latestCausal = causalRaw.lastOrNull()
        val latestCausalInvalid = latestCausal != null && (
            !latestCausal.valueMmol.isFinite() ||
                latestCausal.valueMmol !in MIN_GLUCOSE_MMOL..MAX_GLUCOSE_MMOL ||
                latestCausal.quality == DataQuality.STALE ||
                latestCausal.quality == DataQuality.SENSOR_ERROR
            )
        val causalGlucose = causalRaw.filter { point ->
            point.valueMmol.isFinite() &&
                point.valueMmol in MIN_GLUCOSE_MMOL..MAX_GLUCOSE_MMOL
        }
        val canonical = Glucose5mCanonicalizer.build(causalGlucose)
        val episodePoints = fullContiguousTail(canonical.points)
        val reasons = linkedSetOf<String>()
        val latestTs = canonical.points.lastOrNull()?.ts
        val latestQualityIsStale = latestCausal?.quality == DataQuality.STALE
        val stale = latestTs == null ||
            input.nowTs - latestTs > MAX_DATA_AGE_MS ||
            latestQualityIsStale
        val insufficient = episodePoints.size < MIN_CANONICAL_POINTS
        val implausibleCanonicalDelta = episodePoints.zipWithNext().any { (previous, current) ->
            abs(current.valueMmol - previous.valueMmol) > MAX_CANONICAL_DELTA_MMOL5 + EPSILON
        }

        if (input.sensorBlocked) reasons += REASON_SENSOR_BLOCKED
        if (stale) reasons += REASON_STALE_DATA
        if (insufficient) reasons += REASON_INSUFFICIENT_DATA
        if (latestCausalInvalid) reasons += REASON_INVALID_LATEST_CAUSAL_GLUCOSE
        if (implausibleCanonicalDelta) reasons += REASON_IMPLAUSIBLE_CANONICAL_DELTA
        if (!insulinImpactValid) reasons += REASON_INVALID_INSULIN_IMPACT
        if (!announcedCarbImpactValid) reasons += REASON_INVALID_ANNOUNCED_CARB_IMPACT
        if (!knownTherapyImpactValid) reasons += REASON_INVALID_KNOWN_THERAPY_IMPACT
        if (csf == null) reasons += REASON_CSF_UNAVAILABLE

        if (
            input.sensorBlocked ||
            stale ||
            insufficient ||
            latestCausalInvalid ||
            implausibleCanonicalDelta ||
            !insulinImpactValid ||
            !announcedCarbImpactValid ||
            !knownTherapyImpactValid
        ) {
            return blockedSnapshot(
                input = input,
                sensorTrust = sensorTrust,
                therapyCoverage = therapyCoverage,
                reasons = reasons
            )
        }

        val episodeDeltas = episodePoints.zipWithNext { previous, current ->
            current.valueMmol - previous.valueMmol
        }
        val featureDeltas = episodeDeltas.takeLast(LONG_WINDOW_BUCKETS)
        val currentDelta = featureDeltas.last()
        val shortAverageDelta = featureDeltas.takeLast(SHORT_WINDOW_BUCKETS).average()
        val longAverageDelta = featureDeltas.average()
        val robustObservedDelta = min(currentDelta, shortAverageDelta)
        val signedResidual = robustObservedDelta - knownTherapyImpact
        val impact = max(0.0, signedResidual)
        val supported = supportByInterval(episodeDeltas, knownTherapyImpact)
        val supportStableBuckets = supported.trailingTrueCount()
        val immediatelyPrecedingSupportBuckets = if (supported.lastOrNull() == false) {
            supported.dropLast(1).trailingTrueCount()
        } else {
            0
        }

        val state = when {
            shortAverageDelta <= 0.0 &&
                immediatelyPrecedingSupportBuckets >= ACTIVE_SUPPORT_BUCKETS -> UamRuntimeState.DECAYING
            shortAverageDelta <= 0.0 -> UamRuntimeState.INACTIVE
            supportStableBuckets >= ACTIVE_SUPPORT_BUCKETS -> UamRuntimeState.ACTIVE
            supportStableBuckets == 1 -> UamRuntimeState.SUSPECTED
            else -> UamRuntimeState.INACTIVE
        }
        if (supportStableBuckets > 0) reasons += REASON_POSITIVE_SUPPORT
        if (state == UamRuntimeState.ACTIVE) reasons += REASON_SUPPORT_CONFIRMED
        if (shortAverageDelta <= 0.0) reasons += REASON_FALLING_SHORT_TREND

        val runEndTs = episodePoints.last().ts.coerceAtMost(input.nowTs)
        val episodeSupportBuckets = when (state) {
            UamRuntimeState.DECAYING -> immediatelyPrecedingSupportBuckets
            else -> supportStableBuckets
        }
        val episodeSupportEndTs = if (state == UamRuntimeState.DECAYING) {
            runEndTs - STEP_MS
        } else {
            runEndTs
        }
        val onsetTs = episodeSupportBuckets.takeIf { it > 0 }?.let { stable ->
            (episodeSupportEndTs - stable * STEP_MS).coerceAtMost(input.nowTs)
        }
        val firstDetectionTs = onsetTs?.let { (it + STEP_MS).coerceAtMost(input.nowTs) }
        val activeSinceTs = if (
            state == UamRuntimeState.ACTIVE || state == UamRuntimeState.DECAYING
        ) {
            onsetTs
        } else {
            null
        }

        val confidence = confidence(
            supportStableBuckets = supportStableBuckets,
            currentDelta = currentDelta,
            shortAverageDelta = shortAverageDelta,
            longAverageDelta = longAverageDelta,
            sensorTrust = sensorTrust,
            therapyCoverage = therapyCoverage,
            csfAvailable = csf != null
        )
        if (confidence < MIN_CONTROL_CONFIDENCE) reasons += REASON_LOW_CONTROL_CONFIDENCE
        if (sensorTrust < MIN_CONTROL_SENSOR_TRUST) reasons += REASON_LOW_SENSOR_TRUST
        if (therapyCoverage < MIN_CONTROL_THERAPY_COVERAGE) reasons += REASON_LOW_THERAPY_COVERAGE
        val previousImpact = previousImpact(featureDeltas, knownTherapyImpact)
        val forecast = buildForecast(impact = impact, previousImpact = previousImpact)
        val contourSum = forecast.sum()
        val hasPositiveContour = impact > EPSILON && contourSum > EPSILON
        if (!hasPositiveContour) reasons += REASON_ZERO_FORECAST_CONTOUR
        val equivalentCarbs = csf?.let { (contourSum / it).coerceIn(0.0, MAX_EQUIVALENT_CARBS_GRAMS) }
        val provisionalLowerBounds = provisionalLowerBoundsByInterval(
            deltas = episodeDeltas,
            knownTherapyImpact = knownTherapyImpact,
            csf = csf,
            sensorTrust = sensorTrust,
            therapyCoverage = therapyCoverage
        )
        val stableLowerBoundTail = resolveStableLowerBoundTail(provisionalLowerBounds)
        val lowerBoundStableBuckets = stableLowerBoundTail.count
        val supportedLowerBound = stableLowerBoundTail.confirmedMinimumGrams?.takeIf {
            state == UamRuntimeState.ACTIVE &&
                lowerBoundStableBuckets >= ACTIVE_SUPPORT_BUCKETS &&
                confidence >= MIN_EXPORT_CONFIDENCE &&
                sensorTrust >= MIN_EXPORT_SENSOR_TRUST &&
                therapyCoverage >= MIN_EXPORT_THERAPY_COVERAGE &&
                it > EPSILON
        }

        val activeForForecast = hasPositiveContour &&
            (state == UamRuntimeState.ACTIVE || state == UamRuntimeState.DECAYING)
        val activeForControl = state == UamRuntimeState.ACTIVE &&
            supportedLowerBound != null &&
            supportedLowerBound > EPSILON &&
            confidence >= MIN_CONTROL_CONFIDENCE &&
            sensorTrust >= MIN_CONTROL_SENSOR_TRUST &&
            therapyCoverage >= MIN_CONTROL_THERAPY_COVERAGE

        return UnifiedUamRuntimeSnapshot(
            timestamp = input.nowTs,
            state = state,
            activeForForecast = activeForForecast,
            activeForControl = activeForControl,
            impactMmolPer5m = impact,
            signedResidualMmolPer5m = signedResidual,
            shortAverageDeltaMmol5 = shortAverageDelta,
            forecastStepsStorage = forecast,
            equivalentCarbsGrams = equivalentCarbs,
            supportedLowerBoundCarbsGrams = supportedLowerBound,
            confidence = confidence,
            onsetTs = onsetTs,
            firstDetectionTs = firstDetectionTs,
            activeSinceTs = activeSinceTs,
            supportStableBuckets = supportStableBuckets,
            lowerBoundStableBuckets = lowerBoundStableBuckets,
            sensorTrust = sensorTrust,
            therapyCoverage = therapyCoverage,
            source = SOURCE,
            reasons = reasons,
            sensitivityRuntime = input.sensitivityRuntime
        )
    }

    private fun blockedSnapshot(
        input: UnifiedUamInput,
        sensorTrust: Double,
        therapyCoverage: Double,
        reasons: Set<String>
    ): UnifiedUamRuntimeSnapshot = UnifiedUamRuntimeSnapshot(
        timestamp = input.nowTs,
        state = UamRuntimeState.BLOCKED,
        activeForForecast = false,
        activeForControl = false,
        impactMmolPer5m = 0.0,
        signedResidualMmolPer5m = 0.0,
        shortAverageDeltaMmol5 = 0.0,
        forecastStepsStorage = DoubleArray(FORECAST_STEPS),
        equivalentCarbsGrams = null,
        supportedLowerBoundCarbsGrams = null,
        confidence = 0.0,
        onsetTs = null,
        firstDetectionTs = null,
        activeSinceTs = null,
        supportStableBuckets = 0,
        lowerBoundStableBuckets = 0,
        sensorTrust = sensorTrust,
        therapyCoverage = therapyCoverage,
        source = SOURCE,
        reasons = reasons,
        sensitivityRuntime = input.sensitivityRuntime
    )

    private fun fullContiguousTail(points: List<GlucosePoint>): List<GlucosePoint> {
        if (points.isEmpty()) return emptyList()
        var startIndex = points.lastIndex
        while (
            startIndex > 0 &&
            points[startIndex].ts - points[startIndex - 1].ts == STEP_MS
        ) {
            startIndex -= 1
        }
        return points.subList(startIndex, points.size)
    }

    private fun deduplicateCausalRaw(points: List<GlucosePoint>, nowTs: Long): List<GlucosePoint> =
        points
            .asSequence()
            .filter { it.ts <= nowTs }
            .sortedBy { it.ts }
            .fold(linkedMapOf<Long, GlucosePoint>()) { deduplicated, point ->
                deduplicated[point.ts] = point
                deduplicated
            }
            .values
            .toList()

    private fun supportByInterval(deltas: List<Double>, knownTherapyImpact: Double): List<Boolean> =
        deltas.mapIndexed { index, delta ->
            val localAverage = deltas
                .subList(max(0, index - SHORT_WINDOW_BUCKETS + 1), index + 1)
                .average()
            val residual = min(delta, localAverage) - knownTherapyImpact
            delta > 0.0 && localAverage > 0.0 && residual > 0.0
        }

    private fun previousImpact(deltas: List<Double>, knownTherapyImpact: Double): Double {
        if (deltas.size < 2) return 0.0
        val previousIndex = deltas.lastIndex - 1
        val previousShortAverage = deltas
            .subList(max(0, previousIndex - SHORT_WINDOW_BUCKETS + 1), previousIndex + 1)
            .average()
        return max(0.0, min(deltas[previousIndex], previousShortAverage) - knownTherapyImpact)
    }

    private fun provisionalLowerBoundsByInterval(
        deltas: List<Double>,
        knownTherapyImpact: Double,
        csf: Double?,
        sensorTrust: Double,
        therapyCoverage: Double
    ): List<Double?> = deltas.indices.map { index ->
        val causalDeltas = deltas.subList(0, index + 1)
        val causalSupport = supportByInterval(causalDeltas, knownTherapyImpact)
        val stableSupport = causalSupport.trailingTrueCount()
        if (stableSupport == 0 || causalSupport.lastOrNull() != true || csf == null) {
            return@map null
        }

        val features = causalDeltas.takeLast(LONG_WINDOW_BUCKETS)
        val current = features.last()
        val shortAverage = features.takeLast(SHORT_WINDOW_BUCKETS).average()
        val longAverage = features.average()
        val causalConfidence = confidence(
            supportStableBuckets = stableSupport,
            currentDelta = current,
            shortAverageDelta = shortAverage,
            longAverageDelta = longAverage,
            sensorTrust = sensorTrust,
            therapyCoverage = therapyCoverage,
            csfAvailable = true
        )
        val causalImpact = max(0.0, min(current, shortAverage) - knownTherapyImpact)
        val causalForecast = buildForecast(
            impact = causalImpact,
            previousImpact = previousImpact(features, knownTherapyImpact)
        )
        val equivalentCarbs = (causalForecast.sum() / csf)
            .coerceIn(0.0, MAX_EQUIVALENT_CARBS_GRAMS)
        (equivalentCarbs * causalConfidence * LOWER_BOUND_CONFIDENCE_FACTOR)
            .coerceIn(0.0, MAX_SUPPORTED_LOWER_BOUND_CARBS_GRAMS)
            .takeIf { it > EPSILON }
    }

    private fun buildForecast(impact: Double, previousImpact: Double): DoubleArray {
        if (impact <= 0.0) return DoubleArray(FORECAST_STEPS)
        val slope = impact - previousImpact
        return DoubleArray(FORECAST_STEPS) { index ->
            val step = index + 1.0
            val slopeDecay = max(0.0, impact + slope * step)
            val threeHourLinearDecay = max(0.0, impact * (1.0 - step / FORECAST_STEPS))
            min(slopeDecay, threeHourLinearDecay)
        }
    }

    private fun confidence(
        supportStableBuckets: Int,
        currentDelta: Double,
        shortAverageDelta: Double,
        longAverageDelta: Double,
        sensorTrust: Double,
        therapyCoverage: Double,
        csfAvailable: Boolean
    ): Double {
        val persistence = (supportStableBuckets / 3.0).coerceIn(0.0, 1.0)
        val positiveAgreement = listOf(currentDelta, shortAverageDelta, longAverageDelta)
            .count { it > 0.0 } / 3.0
        val denominator = max(max(abs(shortAverageDelta), abs(longAverageDelta)), EPSILON)
        val magnitudeAgreement = (1.0 - abs(shortAverageDelta - longAverageDelta) / denominator)
            .coerceIn(0.0, 1.0)
        val trendAgreement = (positiveAgreement + magnitudeAgreement) / 2.0
        val csfAvailability = if (csfAvailable) 1.0 else 0.0
        return (
            0.30 * persistence +
                0.20 * trendAgreement +
                0.20 * sensorTrust +
                0.20 * therapyCoverage +
                0.10 * csfAvailability
            ).coerceIn(0.0, 0.99)
    }

    private fun List<Boolean>.trailingTrueCount(): Int {
        var count = 0
        for (index in indices.reversed()) {
            if (!this[index]) break
            count += 1
        }
        return count
    }

    internal fun resolveStableLowerBoundTail(values: List<Double?>): StableLowerBoundTail {
        var count = 0
        var newer: Double? = null
        var minimum: Double? = null
        for (index in values.indices.reversed()) {
            val current = values[index]?.takeIf { it.isFinite() && it > EPSILON } ?: break
            if (newer != null) {
                val ratio = newer / current
                if (ratio !in MIN_STABLE_LOWER_BOUND_RATIO..MAX_STABLE_LOWER_BOUND_RATIO) break
            }
            count += 1
            minimum = minimum?.let { min(it, current) } ?: current
            newer = current
        }
        return StableLowerBoundTail(
            count = count,
            confirmedMinimumGrams = minimum
        )
    }

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

    private const val STEP_MS = 5 * 60_000L
    private const val MAX_DATA_AGE_MS = 10 * 60_000L
    private const val MIN_GLUCOSE_MMOL = 2.2
    private const val MAX_GLUCOSE_MMOL = 22.0
    private const val MAX_CANONICAL_DELTA_MMOL5 = 3.0
    private const val MIN_CSF_MMOL_PER_GRAM = 0.05
    private const val MAX_CSF_MMOL_PER_GRAM = 1.5
    private const val MIN_CANONICAL_POINTS = 4
    private const val SHORT_WINDOW_BUCKETS = 3
    private const val LONG_WINDOW_BUCKETS = 6
    private const val ACTIVE_SUPPORT_BUCKETS = 2
    private const val FORECAST_STEPS = 36
    private const val LOWER_BOUND_CONFIDENCE_FACTOR = 0.50
    private const val MIN_EXPORT_CONFIDENCE = 0.55
    private const val MIN_EXPORT_SENSOR_TRUST = 0.70
    private const val MIN_EXPORT_THERAPY_COVERAGE = 0.70
    private const val MIN_CONTROL_CONFIDENCE = 0.65
    private const val MIN_CONTROL_SENSOR_TRUST = 0.80
    private const val MIN_CONTROL_THERAPY_COVERAGE = 0.70
    private const val MAX_EQUIVALENT_CARBS_GRAMS = 15.0
    private const val MAX_SUPPORTED_LOWER_BOUND_CARBS_GRAMS = 15.0
    private const val MIN_STABLE_LOWER_BOUND_RATIO = 0.67
    private const val MAX_STABLE_LOWER_BOUND_RATIO = 1.50
    private const val EPSILON = 1e-9
    private const val SOURCE = "unified_uam"
    private const val REASON_SENSOR_BLOCKED = "sensor_blocked"
    private const val REASON_STALE_DATA = "stale_canonical_glucose"
    private const val REASON_INSUFFICIENT_DATA = "insufficient_canonical_glucose"
    private const val REASON_INVALID_LATEST_CAUSAL_GLUCOSE = "invalid_latest_causal_glucose"
    private const val REASON_IMPLAUSIBLE_CANONICAL_DELTA = "implausible_canonical_delta"
    private const val REASON_INVALID_INSULIN_IMPACT = "invalid_insulin_impact"
    private const val REASON_INVALID_ANNOUNCED_CARB_IMPACT = "invalid_announced_carb_impact"
    private const val REASON_INVALID_KNOWN_THERAPY_IMPACT = "invalid_known_therapy_impact"
    private const val REASON_CSF_UNAVAILABLE = "csf_unavailable"
    private const val REASON_POSITIVE_SUPPORT = "positive_support"
    private const val REASON_SUPPORT_CONFIRMED = "support_confirmed"
    private const val REASON_FALLING_SHORT_TREND = "falling_short_trend"
    private const val REASON_LOW_CONTROL_CONFIDENCE = "confidence_below_control_threshold"
    private const val REASON_LOW_SENSOR_TRUST = "sensor_trust_below_control_threshold"
    private const val REASON_LOW_THERAPY_COVERAGE = "therapy_coverage_below_control_threshold"
    private const val REASON_ZERO_FORECAST_CONTOUR = "zero_forecast_contour"
}
