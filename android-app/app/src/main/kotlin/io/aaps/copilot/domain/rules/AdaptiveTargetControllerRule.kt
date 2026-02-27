package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import kotlin.math.abs
import kotlin.math.floor

class AdaptiveTargetControllerRule : TargetRule {

    override val id: String = RULE_ID

    override fun evaluate(context: RuleContext): RuleDecision {
        if (!context.dataFresh) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("stale_data"), null)
        }
        if (context.sensorBlocked) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("sensor_blocked"), null)
        }

        val sortedGlucose = context.glucose.sortedBy { it.ts }
        if (sortedGlucose.size < 2) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("insufficient_glucose_points"), null)
        }
        val last = sortedGlucose.last()
        val prev = sortedGlucose.dropLast(1).last()
        val trend5 = last.valueMmol - prev.valueMmol

        val forecast5 = latestForecast(context.forecasts, 5)
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("missing_forecast_5m"), null)
        val forecast60 = latestForecast(context.forecasts, 60)
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("missing_forecast_60m"), null)
        val forecast30 = latestForecast(context.forecasts, 30)
            ?: (0.55 * forecast5 + 0.45 * forecast60)

        val base = context.baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val e30 = forecast30 - base
        val e60 = forecast60 - base
        val weightedError = 0.65 * e30 + 0.35 * e60

        val baselineCandidate = base - 0.45 * weightedError - 0.20 * trend5
        val telemetryBias = computeTelemetryBias(context.latestTelemetry, trend5)
        val candidate = (baselineCandidate + telemetryBias).coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)

        val anchor = (context.activeTempTargetMmol ?: base).coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val maxStep = context.adaptiveMaxStepMmol.coerceIn(0.05, 1.0)
        val limitedDelta = (candidate - anchor).coerceIn(-maxStep, maxStep)
        val target = roundToStep((anchor + limitedDelta).coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL), TARGET_STEP_MMOL)

        val errorMagnitude = abs(target - anchor)
        if (errorMagnitude < MIN_ACTION_DELTA_MMOL) {
            return RuleDecision(
                id,
                RuleState.NO_MATCH,
                listOf(
                    "inside_deadband",
                    "f30=${format2(forecast30)}",
                    "f60=${format2(forecast60)}",
                    "weightedError=${format2(weightedError)}",
                    "anchor=${format2(anchor)}",
                    "candidate=${format2(candidate)}",
                    "maxStep=${format2(maxStep)}"
                ),
                null
            )
        }

        val durationMinutes = if (abs(e30) >= SHORT_DURATION_ERROR_THRESHOLD || abs(trend5) >= SHORT_DURATION_TREND_THRESHOLD) {
            30
        } else {
            60
        }
        val confidence = computeConfidence(context, telemetryBias, weightedError)

        return RuleDecision(
            ruleId = id,
            state = RuleState.TRIGGERED,
            reasons = listOf(
                "adaptive_30m_priority_60m_confirm",
                "f30=${format2(forecast30)}",
                "f60=${format2(forecast60)}",
                "e30=${format2(e30)}",
                "e60=${format2(e60)}",
                "weightedError=${format2(weightedError)}",
                "trend5=${format2(trend5)}",
                "telemetryBias=${format2(telemetryBias)}",
                "anchor=${format2(anchor)}",
                "candidate=${format2(candidate)}",
                "maxStep=${format2(maxStep)}",
                "target=${format2(target)}",
                "duration=${durationMinutes}",
                "confidence=${format2(confidence)}"
            ),
            actionProposal = ActionProposal(
                type = "temp_target",
                targetMmol = target,
                durationMinutes = durationMinutes,
                reason = "adaptive_30m_60m|confidence=${format2(confidence)}"
            )
        )
    }

    private fun latestForecast(forecasts: List<Forecast>, horizon: Int): Double? {
        return forecasts
            .asSequence()
            .filter { it.horizonMinutes == horizon }
            .maxByOrNull { it.ts }
            ?.valueMmol
    }

    private fun computeTelemetryBias(telemetry: Map<String, Double?>, trend5: Double): Double {
        var bias = 0.0
        val iob = firstTelemetry(telemetry, listOf("iob_units", "iob", "insulinonboard"))
        val cob = firstTelemetry(telemetry, listOf("cob_grams", "cob", "carbsonboard"))
        val activityRatio = firstTelemetry(telemetry, listOf("activity_ratio", "activity", "sensitivityratio"))
        val uam = firstTelemetry(telemetry, listOf("uam_value", "uam_calculated_flag", "uam"))

        if (iob != null && iob >= 2.0 && trend5 < 0.0) bias += 0.20
        if (activityRatio != null && activityRatio >= 1.2) bias += 0.15
        if (cob != null && cob >= 25.0 && trend5 > 0.0) bias -= 0.15
        if (uam != null && uam >= 1.0) bias -= 0.10

        return bias.coerceIn(-MAX_BIAS_MMOL, MAX_BIAS_MMOL)
    }

    private fun firstTelemetry(telemetry: Map<String, Double?>, aliases: List<String>): Double? {
        val normalized = telemetry
            .filterValues { it != null }
            .mapKeys { normalizeTelemetryKey(it.key) }
            .mapValues { it.value!! }

        aliases.forEach { alias ->
            normalized[normalizeTelemetryKey(alias)]?.let { return it }
        }

        val tokens = aliases.map { normalizeTelemetryKey(it) }
        return normalized.entries.firstOrNull { (key, _) ->
            tokens.any { token -> key.contains(token) }
        }?.value
    }

    private fun computeConfidence(
        context: RuleContext,
        telemetryBias: Double,
        weightedError: Double
    ): Double {
        var confidence = 0.55
        if (context.latestTelemetry.values.any { it != null }) confidence += 0.10
        if (context.currentDayPattern?.isRiskWindow == true) confidence += 0.05
        if (context.currentProfileSegment?.confidence != null) {
            confidence += (context.currentProfileSegment.confidence * 0.15).coerceAtMost(0.15)
        }
        if (abs(telemetryBias) >= 0.30) confidence -= 0.05
        if (abs(weightedError) >= 2.0) confidence -= 0.05
        return confidence.coerceIn(0.30, 0.95)
    }

    private fun roundToStep(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        val scaled = value / step
        return floor(scaled + 0.5) * step
    }

    private fun normalizeTelemetryKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun format2(value: Double): String = String.format("%.2f", value)

    companion object {
        const val RULE_ID = "AdaptiveTargetController.v1"
        private const val MIN_TARGET_MMOL = 4.0
        private const val MAX_TARGET_MMOL = 10.0
        private const val TARGET_STEP_MMOL = 0.05
        private const val MAX_BIAS_MMOL = 0.40
        private const val MIN_ACTION_DELTA_MMOL = 0.10
        private const val SHORT_DURATION_ERROR_THRESHOLD = 0.8
        private const val SHORT_DURATION_TREND_THRESHOLD = 0.25
    }
}
