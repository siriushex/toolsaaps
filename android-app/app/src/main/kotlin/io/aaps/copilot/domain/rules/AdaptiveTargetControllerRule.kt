package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import kotlin.math.abs
import kotlin.math.floor

class AdaptiveTargetControllerRule : TargetRule {

    override val id: String = RULE_ID

    @Volatile
    private var previousI: Double = 0.0

    private val controller = AdaptiveTempTargetController()

    override fun evaluate(context: RuleContext): RuleDecision {
        if (!context.dataFresh) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("stale_data"), null)
        }
        if (context.sensorBlocked) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("sensor_blocked"), null)
        }

        val forecast5 = latestForecast(context.forecasts, 5)
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("missing_forecast_5m"), null)
        val forecast30 = latestForecast(context.forecasts, 30)
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("missing_forecast_30m"), null)
        val forecast60 = latestForecast(context.forecasts, 60)
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("missing_forecast_60m"), null)

        val forecast5Row = latestForecastRow(context.forecasts, 5)
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("missing_forecast_5m"), null)
        val forecast30Row = latestForecastRow(context.forecasts, 30)
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("missing_forecast_30m"), null)
        val forecast60Row = latestForecastRow(context.forecasts, 60)
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("missing_forecast_60m"), null)

        val controllerOut = controller.evaluate(
            AdaptiveTempTargetController.Input(
                nowTs = context.nowTs,
                baseTarget = context.baseTargetMmol,
                pred5 = forecast5,
                pred30 = forecast30,
                pred60 = forecast60,
                ciLow5 = forecast5Row.ciLow,
                ciHigh5 = forecast5Row.ciHigh,
                ciLow30 = forecast30Row.ciLow,
                ciHigh30 = forecast30Row.ciHigh,
                ciLow60 = forecast60Row.ciLow,
                ciHigh60 = forecast60Row.ciHigh,
                uamActive = resolveUamActive(context.latestTelemetry),
                previousTempTarget = context.activeTempTargetMmol,
                previousI = previousI,
                cobGrams = resolveTelemetryMetric(context.latestTelemetry, "cob_grams", "cob", "carbsonboard"),
                iobUnits = resolveTelemetryMetric(context.latestTelemetry, "iob_units", "iob", "insulinonboard")
            )
        )

        previousI = controllerOut.updatedI

        val base = context.baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        if (abs(controllerOut.newTempTarget - base) < EPS_EQ) {
            return RuleDecision(
                id,
                RuleState.NO_MATCH,
                listOf(
                    "target_equals_base",
                    "reason=${controllerOut.reason}",
                    "base=${format2(base)}",
                    "computed=${format2(controllerOut.newTempTarget)}",
                    "updatedI=${format2(controllerOut.updatedI)}"
                ),
                null
            )
        }

        val target = roundToStep(
            controllerOut.newTempTarget.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL),
            TARGET_STEP_MMOL
        )

        return RuleDecision(
            ruleId = id,
            state = RuleState.TRIGGERED,
            reasons = buildList {
                add("adaptive_temp_target_controller_v2")
                add("reason=${controllerOut.reason}")
                add("base=${format2(base)}")
                add("target=${format2(target)}")
                add("pred5=${format2(forecast5)}")
                add("pred30=${format2(forecast30)}")
                add("pred60=${format2(forecast60)}")
                add("i=${format2(controllerOut.updatedI)}")
                controllerOut.debugFields.forEach { (k, v) -> add("$k=${format2(v)}") }
            },
            actionProposal = ActionProposal(
                type = "temp_target",
                targetMmol = target,
                durationMinutes = controllerOut.durationMin,
                reason = "adaptive_pi_ci_v2|mode=${controllerOut.reason}"
            )
        )
    }

    private fun latestForecast(forecasts: List<Forecast>, horizon: Int): Double? {
        return latestForecastRow(forecasts, horizon)?.valueMmol
    }

    private fun latestForecastRow(forecasts: List<Forecast>, horizon: Int): Forecast? {
        return forecasts
            .asSequence()
            .filter { it.horizonMinutes == horizon }
            .maxByOrNull { it.ts }
    }

    private fun resolveUamActive(telemetry: Map<String, Double?>): Boolean {
        val normalized = normalizedTelemetry(telemetry)

        val direct = listOf("uam_active", "uam_value", "uam_calculated_flag", "uam_detected")
            .map { normalizeTelemetryKey(it) }
            .firstNotNullOfOrNull { normalized[it] }
        if (direct != null) return direct >= 0.5

        return normalized.entries.any { (k, v) -> k.contains("uam") && v >= 0.5 }
    }

    private fun resolveTelemetryMetric(telemetry: Map<String, Double?>, vararg aliases: String): Double? {
        if (aliases.isEmpty()) return null
        val normalized = normalizedTelemetry(telemetry)
        val normalizedAliases = aliases.map { normalizeTelemetryKey(it) }
        val exact = normalizedAliases.firstNotNullOfOrNull { key -> normalized[key] }
        if (exact != null) return exact
        return normalized.entries.firstOrNull { (key, _) ->
            normalizedAliases.any { alias ->
                key == alias || key.endsWith("_$alias") || key.split('_').any { token -> token == alias }
            }
        }?.value
    }

    private fun normalizedTelemetry(telemetry: Map<String, Double?>): Map<String, Double> {
        return telemetry
            .filterValues { it != null }
            .mapKeys { normalizeTelemetryKey(it.key) }
            .mapValues { it.value!! }
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
        private const val MAX_TARGET_MMOL = 9.0
        private const val TARGET_STEP_MMOL = 0.05
        private const val EPS_EQ = 1e-6
    }
}
