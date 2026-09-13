package io.aaps.copilot.domain.alerts

data class DeliveryDiagnosticObservation(
    val observedAt: Long,
    val glucoseTs: Long,
    val glucoseMmol: Double,
    val positiveIobUnits: Double,
    val activityUnitsPerMinute: Double,
    val isfMmolPerUnit: Double,
    val crGramsPerUnit: Double,
    val cobGrams: Double,
    val uamActive: Boolean,
    val forecast30Mmol: Double,
    val forecast30CiLow: Double,
    val forecast30CiHigh: Double,
    val forecast30TargetTs: Long,
    val basisKey: String
)

enum class DeliveryDiagnosticReason {
    UNAVAILABLE,
    WARMING_UP,
    NONE,
    UNEXPECTED_RISE,
    PERSISTENT_HIGH,
    RECOVERY
}

data class DeliveryDiagnosticAssessment(
    val reason: DeliveryDiagnosticReason,
    val expectedInsulinEffectMmol: Double = 0.0,
    val observedRiseMmol: Double = 0.0,
    val foodConfounded: Boolean = false
)

object DeliveryDiagnosticPolicy {
    const val MAX_OBSERVATIONS = 96
    const val HISTORY_MS = 90 * 60_000L
    const val MAX_GAP_MS = 6 * 60_000L

    fun validObservation(o: DeliveryDiagnosticObservation): Boolean {
        if (
            o.observedAt <= 0L ||
            o.glucoseTs <= 0L ||
            o.glucoseTs > o.observedAt ||
            o.observedAt - o.glucoseTs > MAX_GAP_MS ||
            o.forecast30TargetTs <= FORECAST_HORIZON_MS
        ) return false

        val forecastOriginTs = o.forecast30TargetTs - FORECAST_HORIZON_MS
        if (
            forecastOriginTs <= 0L ||
            forecastOriginTs > o.observedAt ||
            o.observedAt - forecastOriginTs > MAX_GAP_MS
        ) return false

        if (o.basisKey.isBlank() || o.basisKey.length > MAX_BASIS_LENGTH) return false

        val values = listOf(
            o.glucoseMmol,
            o.positiveIobUnits,
            o.activityUnitsPerMinute,
            o.isfMmolPerUnit,
            o.crGramsPerUnit,
            o.cobGrams,
            o.forecast30Mmol,
            o.forecast30CiLow,
            o.forecast30CiHigh
        )
        if (values.any { !it.isFinite() }) return false

        return o.glucoseMmol in MIN_GLUCOSE_MMOL..MAX_GLUCOSE_MMOL &&
            o.positiveIobUnits in 0.0..MAX_POSITIVE_IOB_UNITS &&
            o.activityUnitsPerMinute in 0.0..MAX_ACTIVITY_UNITS_PER_MINUTE &&
            o.isfMmolPerUnit in MIN_ISF_MMOL_PER_UNIT..MAX_ISF_MMOL_PER_UNIT &&
            o.crGramsPerUnit in MIN_CR_GRAMS_PER_UNIT..MAX_CR_GRAMS_PER_UNIT &&
            o.cobGrams in 0.0..MAX_COB_GRAMS &&
            o.forecast30Mmol in MIN_GLUCOSE_MMOL..MAX_GLUCOSE_MMOL &&
            o.forecast30CiLow in MIN_GLUCOSE_MMOL..MAX_GLUCOSE_MMOL &&
            o.forecast30CiHigh in MIN_GLUCOSE_MMOL..MAX_GLUCOSE_MMOL &&
            o.forecast30CiLow <= o.forecast30Mmol &&
            o.forecast30Mmol <= o.forecast30CiHigh
    }

    fun assess(
        history: List<DeliveryDiagnosticObservation>,
        nowTs: Long
    ): DeliveryDiagnosticAssessment {
        if (
            nowTs <= 0L ||
            history.isEmpty() ||
            history.size > MAX_OBSERVATIONS ||
            history.any { !validObservation(it) } ||
            !validHistory(history)
        ) return unavailable()

        val latest = history.last()
        if (!freshAt(latest.observedAt, nowTs) || !freshAt(latest.glucoseTs, nowTs)) {
            return unavailable()
        }

        val start30 = windowStartIndex(
            history = history,
            endTs = latest.glucoseTs,
            minimumAgeMs = UNEXPECTED_WINDOW_MS,
            maximumAgeMs = UNEXPECTED_MAX_WINDOW_MS
        ) ?: return DeliveryDiagnosticAssessment(DeliveryDiagnosticReason.WARMING_UP)
        val metrics30 = windowMetrics(history, start30)

        if (latest.glucoseMmol < SUSPICION_MIN_CURRENT_MMOL || latest.forecast30CiLow < SUSPICION_MIN_CURRENT_MMOL) {
            return metrics30.assessment(DeliveryDiagnosticReason.NONE)
        }

        if (
            metrics30.observations.all { it.glucoseMmol in RECOVERY_MIN_MMOL..RECOVERY_MAX_MMOL } &&
            latest.glucoseMmol < RECOVERY_CURRENT_MAX_MMOL
        ) {
            return DeliveryDiagnosticAssessment(DeliveryDiagnosticReason.RECOVERY)
        }

        val previous = history[history.lastIndex - 1]
        val fiveMinuteAnchor = history.lastOrNull { latest.glucoseTs - it.glucoseTs >= 5 * MINUTE_MS }
        val forecastAnchor = matchingForecast(history, latest.glucoseTs)
        if (
            latest.glucoseMmol >= UNEXPECTED_MIN_CURRENT_MMOL &&
            metrics30.observedRiseMmol >= UNEXPECTED_MIN_RISE_MMOL &&
            latest.glucoseMmol >= previous.glucoseMmol &&
            fiveMinuteAnchor != null && latest.glucoseMmol >= fiveMinuteAnchor.glucoseMmol &&
            metrics30.expectedInsulinEffectMmol >= UNEXPECTED_MIN_EFFECT_MMOL &&
            metrics30.positiveActivityAndIobMinutes >= UNEXPECTED_MIN_ACTIVE_MINUTES &&
            forecastAnchor != null &&
            latest.glucoseMmol - forecastAnchor.forecast30CiHigh >= UNEXPECTED_MIN_FORECAST_MISS_MMOL
        ) {
            return metrics30.assessment(DeliveryDiagnosticReason.UNEXPECTED_RISE)
        }

        val start60 = windowStartIndex(
            history = history,
            endTs = latest.glucoseTs,
            minimumAgeMs = PERSISTENT_WINDOW_MS,
            maximumAgeMs = PERSISTENT_MAX_WINDOW_MS
        )
        if (start60 != null) {
            val metrics60 = windowMetrics(history, start60)
            if (
                metrics60.observations.all { it.glucoseMmol >= PERSISTENT_MIN_GLUCOSE_MMOL } &&
                metrics60.observedRiseMmol >= PERSISTENT_MIN_NET_CHANGE_MMOL &&
                metrics60.expectedInsulinEffectMmol >= PERSISTENT_MIN_EFFECT_MMOL &&
                metrics60.positiveActivityAndIobMinutes >= PERSISTENT_MIN_ACTIVE_MINUTES
            ) {
                return metrics60.assessment(DeliveryDiagnosticReason.PERSISTENT_HIGH)
            }
        }

        return metrics30.assessment(DeliveryDiagnosticReason.NONE)
    }

    private fun validHistory(history: List<DeliveryDiagnosticObservation>): Boolean {
        val first = history.first()
        val latest = history.last()
        if (
            latest.observedAt - first.observedAt > HISTORY_MS ||
            latest.glucoseTs - first.glucoseTs > HISTORY_MS
        ) return false

        val basisKey = first.basisKey
        for (index in 1..history.lastIndex) {
            val previous = history[index - 1]
            val current = history[index]
            if (
                current.basisKey != basisKey ||
                current.observedAt <= previous.observedAt ||
                current.glucoseTs <= previous.glucoseTs ||
                current.forecast30TargetTs <= previous.forecast30TargetTs ||
                current.observedAt - previous.observedAt > MAX_GAP_MS ||
                current.glucoseTs - previous.glucoseTs > MAX_GAP_MS
            ) return false
        }
        return true
    }

    private fun freshAt(timestamp: Long, nowTs: Long): Boolean =
        timestamp <= nowTs && nowTs - timestamp <= MAX_GAP_MS

    private fun windowStartIndex(
        history: List<DeliveryDiagnosticObservation>,
        endTs: Long,
        minimumAgeMs: Long,
        maximumAgeMs: Long
    ): Int? {
        val threshold = endTs - minimumAgeMs
        val index = history.indexOfLast { it.glucoseTs <= threshold }
        if (index < 0) return null
        val ageMs = endTs - history[index].glucoseTs
        return index.takeIf { ageMs <= maximumAgeMs }
    }

    private fun windowMetrics(
        history: List<DeliveryDiagnosticObservation>,
        startIndex: Int
    ): WindowMetrics {
        var expectedEffect = 0.0
        var positiveActivityAndIobMinutes = 0.0
        for (index in startIndex until history.lastIndex) {
            val left = history[index]
            val right = history[index + 1]
            // Do not project later accepted insulin activity backwards across a delayed CGM sample.
            if (right.observedAt > history.last().glucoseTs) break
            val minutes = (right.observedAt - left.observedAt) / MINUTE_MS.toDouble()
            val leftEffectPerMinute = left.activityUnitsPerMinute * left.isfMmolPerUnit
            val rightEffectPerMinute = right.activityUnitsPerMinute * right.isfMmolPerUnit
            expectedEffect += (leftEffectPerMinute + rightEffectPerMinute) * 0.5 * minutes
            if (
                left.activityUnitsPerMinute > 0.0 &&
                right.activityUnitsPerMinute > 0.0 &&
                left.positiveIobUnits > 0.0 &&
                right.positiveIobUnits > 0.0
            ) {
                positiveActivityAndIobMinutes += minutes
            }
        }

        val observations = history.subList(startIndex, history.size)
        return WindowMetrics(
            observations = observations,
            expectedInsulinEffectMmol = expectedEffect,
            observedRiseMmol = observations.last().glucoseMmol - observations.first().glucoseMmol,
            positiveActivityAndIobMinutes = positiveActivityAndIobMinutes,
            foodConfounded = observations.any { it.cobGrams > 0.0 || it.uamActive }
        )
    }

    private fun matchingForecast(
        history: List<DeliveryDiagnosticObservation>,
        glucoseTs: Long
    ): DeliveryDiagnosticObservation? = history.dropLast(1)
        .asSequence()
        .mapNotNull { observation ->
            timestampDistance(observation.forecast30TargetTs, glucoseTs)
                .takeIf { it <= FORECAST_TARGET_TOLERANCE_MS }
                ?.let { distance -> observation to distance }
        }
        .minWithOrNull(
            compareBy<Pair<DeliveryDiagnosticObservation, Long>> { it.second }
                .thenByDescending { it.first.observedAt }
        )
        ?.first

    private fun timestampDistance(left: Long, right: Long): Long =
        if (left >= right) left - right else right - left

    private fun unavailable() = DeliveryDiagnosticAssessment(DeliveryDiagnosticReason.UNAVAILABLE)

    private data class WindowMetrics(
        val observations: List<DeliveryDiagnosticObservation>,
        val expectedInsulinEffectMmol: Double,
        val observedRiseMmol: Double,
        val positiveActivityAndIobMinutes: Double,
        val foodConfounded: Boolean
    ) {
        fun assessment(reason: DeliveryDiagnosticReason) = DeliveryDiagnosticAssessment(
            reason = reason,
            expectedInsulinEffectMmol = expectedInsulinEffectMmol,
            observedRiseMmol = observedRiseMmol,
            foodConfounded = foodConfounded
        )
    }

    private const val MINUTE_MS = 60_000L
    private const val FORECAST_HORIZON_MS = 30 * MINUTE_MS
    private const val FORECAST_TARGET_TOLERANCE_MS = 90_000L
    private const val MAX_BASIS_LENGTH = 256

    private const val MIN_GLUCOSE_MMOL = 2.2
    private const val MAX_GLUCOSE_MMOL = 22.0
    private const val MAX_POSITIVE_IOB_UNITS = 60.0
    private const val MAX_ACTIVITY_UNITS_PER_MINUTE = 5.0
    private const val MIN_ISF_MMOL_PER_UNIT = 0.8
    private const val MAX_ISF_MMOL_PER_UNIT = 18.0
    private const val MIN_CR_GRAMS_PER_UNIT = 2.0
    private const val MAX_CR_GRAMS_PER_UNIT = 60.0
    private const val MAX_COB_GRAMS = 400.0

    private const val UNEXPECTED_WINDOW_MS = 30 * MINUTE_MS
    private const val UNEXPECTED_MAX_WINDOW_MS = 36 * MINUTE_MS
    private const val UNEXPECTED_MIN_CURRENT_MMOL = 10.0
    private const val UNEXPECTED_MIN_RISE_MMOL = 1.5
    private const val UNEXPECTED_MIN_EFFECT_MMOL = 0.6
    private const val UNEXPECTED_MIN_ACTIVE_MINUTES = 20.0
    private const val UNEXPECTED_MIN_FORECAST_MISS_MMOL = 1.0

    private const val PERSISTENT_WINDOW_MS = 60 * MINUTE_MS
    private const val PERSISTENT_MAX_WINDOW_MS = 66 * MINUTE_MS
    private const val PERSISTENT_MIN_GLUCOSE_MMOL = 13.9
    private const val PERSISTENT_MIN_NET_CHANGE_MMOL = -0.5
    private const val PERSISTENT_MIN_EFFECT_MMOL = 1.0
    private const val PERSISTENT_MIN_ACTIVE_MINUTES = 40.0

    private const val SUSPICION_MIN_CURRENT_MMOL = 4.0
    private const val RECOVERY_MIN_MMOL = 4.0
    private const val RECOVERY_MAX_MMOL = 10.0
    private const val RECOVERY_CURRENT_MAX_MMOL = 9.0
}
