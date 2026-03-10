package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

class AdaptiveTargetControllerRule : TargetRule {

    override val id: String = RULE_ID

    @Volatile
    private var previousI: Double = 0.0

    @Volatile
    private var previousStepsCount: Double? = null

    @Volatile
    private var previousActivityRatio: Double? = null

    @Volatile
    private var previousTelemetryTs: Long? = null

    @Volatile
    private var activityProtectionActive: Boolean = false

    @Volatile
    private var activityProtectionLowCycles: Int = 0

    @Volatile
    private var activityProtectionTargetMmol: Double = ACTIVITY_TARGET_MIN_MMOL

    @Volatile
    private var activityProtectionRestoreBaseMmol: Double? = null

    private val controller = AdaptiveTempTargetController()

    override fun evaluate(context: RuleContext): RuleDecision {
        val adaptiveMinTarget = context.adaptiveMinTargetMmol
            .coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val adaptiveMaxTarget = context.adaptiveMaxTargetMmol
            .coerceIn(adaptiveMinTarget, MAX_TARGET_MMOL)

        if (!context.dataFresh) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("stale_data"), null)
        }
        if (context.sensorBlocked) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("sensor_blocked"), null)
        }

        val activitySignal = evaluateActivitySignal(context)
        val base = context.baseTargetMmol.coerceIn(adaptiveMinTarget, adaptiveMaxTarget)
        val baseRounded = roundToStep(base, TARGET_STEP_MMOL)

        if (activitySignal.recoveryToBase) {
            val anchor = context.activeTempTargetMmol ?: baseRounded
            if (abs(anchor - baseRounded) < TARGET_STEP_MMOL / 2.0) {
                return RuleDecision(
                    id,
                    RuleState.NO_MATCH,
                    listOf(
                        "activity_recovery_no_change",
                        "base=${format2(baseRounded)}",
                        "anchor=${format2(anchor)}"
                    ),
                    null
                )
            }
            return RuleDecision(
                ruleId = id,
                state = RuleState.TRIGGERED,
                reasons = buildList {
                    add("activity_recovery_to_base")
                    add("base=${format2(baseRounded)}")
                    activitySignal.activityRatio?.let { add("activityRatio=${format2(it)}") }
                    activitySignal.stepsDelta5?.let { add("stepsDelta5=${format2(it)}") }
                    add("lowCycles=${activitySignal.lowCycles}")
                },
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = baseRounded,
                    durationMinutes = ACTIVITY_DURATION_MINUTES,
                    reason = "adaptive_pi_ci_v2|mode=activity_recovery"
                )
            )
        }

        if (activitySignal.active && activitySignal.targetMmol != null) {
            val target = roundToStep(
                activitySignal.targetMmol.coerceIn(ACTIVITY_TARGET_MIN_MMOL, ACTIVITY_TARGET_MAX_MMOL),
                TARGET_STEP_MMOL
            )
            val anchor = context.activeTempTargetMmol
            if (anchor != null && abs(anchor - target) < TARGET_STEP_MMOL / 2.0) {
                return RuleDecision(
                    id,
                    RuleState.NO_MATCH,
                    buildList {
                        add("activity_target_already_active")
                        add("target=${format2(target)}")
                        activitySignal.activityRatio?.let { add("activityRatio=${format2(it)}") }
                        activitySignal.stepsDelta5?.let { add("stepsDelta5=${format2(it)}") }
                        add("lowCycles=${activitySignal.lowCycles}")
                    },
                    null
                )
            }
            return RuleDecision(
                ruleId = id,
                state = RuleState.TRIGGERED,
                reasons = buildList {
                    add("activity_protection_active")
                    add("target=${format2(target)}")
                    add("base=${format2(baseRounded)}")
                    activitySignal.activityRatio?.let { add("activityRatio=${format2(it)}") }
                    activitySignal.activityRatioDelta?.let { add("activityRatioDelta=${format2(it)}") }
                    activitySignal.stepsDelta5?.let { add("stepsDelta5=${format2(it)}") }
                    add("triggerByRatio=${if (activitySignal.triggerByRatio) 1 else 0}")
                    add("triggerByRatioDelta=${if (activitySignal.triggerByRatioDelta) 1 else 0}")
                    add("triggerBySteps=${if (activitySignal.triggerBySteps) 1 else 0}")
                },
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = target,
                    durationMinutes = ACTIVITY_DURATION_MINUTES,
                    reason = "adaptive_pi_ci_v2|mode=activity_protection"
                )
            )
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
                targetMinMmol = adaptiveMinTarget,
                targetMaxMmol = adaptiveMaxTarget,
                currentGlucoseMmol = context.currentGlucoseMmol
                    ?: context.glucose.maxByOrNull { it.ts }?.valueMmol,
                observedDelta5Mmol = observedDelta5(context.glucose),
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
                cobGrams = resolveTelemetryMetric(
                    context.latestTelemetry,
                    "cob_effective_grams",
                    "cob_grams",
                    "cob",
                    "carbsonboard"
                ),
                iobUnits = resolveTelemetryMetric(
                    context.latestTelemetry,
                    "iob_effective_units",
                    "iob_real_units",
                    "iob_units",
                    "iob",
                    "insulinonboard"
                )
            )
        )

        previousI = controllerOut.updatedI

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
            controllerOut.newTempTarget.coerceIn(adaptiveMinTarget, adaptiveMaxTarget),
            TARGET_STEP_MMOL
        )
        val activeTarget = context.activeTempTargetMmol
            ?.coerceIn(adaptiveMinTarget, adaptiveMaxTarget)
            ?.let { roundToStep(it, TARGET_STEP_MMOL) }

        if (activeTarget != null && abs(target - activeTarget) < TARGET_STEP_MMOL / 2.0) {
            return RuleDecision(
                id,
                RuleState.NO_MATCH,
                listOf(
                    "target_equals_active",
                    "reason=${controllerOut.reason}",
                    "active=${format2(activeTarget)}",
                    "computed=${format2(target)}",
                    "updatedI=${format2(controllerOut.updatedI)}"
                ),
                null
            )
        }

        return RuleDecision(
            ruleId = id,
            state = RuleState.TRIGGERED,
            reasons = buildList {
                add("adaptive_temp_target_controller_v2")
                add("reason=${controllerOut.reason}")
                add("base=${format2(base)}")
                add("target=${format2(target)}")
                add("targetMin=${format2(adaptiveMinTarget)}")
                add("targetMax=${format2(adaptiveMaxTarget)}")
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

    private fun evaluateActivitySignal(context: RuleContext): ActivitySignal {
        val nowTs = context.nowTs
        val stepsCount = resolveTelemetryMetric(
            context.latestTelemetry,
            "steps_count",
            "steps",
            "step_count"
        )?.coerceAtLeast(0.0)
        val activityRatio = resolveTelemetryMetric(
            context.latestTelemetry,
            "activity_ratio",
            "activity",
            "activityratio",
            "sensitivity_ratio",
            "sensitivityratio"
        )?.coerceAtLeast(0.0)

        val previousTs = previousTelemetryTs
        val dtMin = if (previousTs != null && nowTs > previousTs) {
            (nowTs - previousTs).toDouble() / 60_000.0
        } else {
            null
        }

        val stepsDelta5 = if (
            stepsCount != null &&
            previousStepsCount != null &&
            dtMin != null &&
            dtMin in ACTIVITY_DT_MIN_ALLOWED_MIN..ACTIVITY_DT_MIN_ALLOWED_MAX
        ) {
            val rawDelta = (stepsCount - previousStepsCount!!).coerceAtLeast(0.0)
            (rawDelta * (5.0 / dtMin)).coerceAtLeast(0.0)
        } else {
            null
        }

        val activityRatioDelta = if (activityRatio != null && previousActivityRatio != null) {
            activityRatio - previousActivityRatio!!
        } else {
            null
        }

        val triggerByRatio = activityRatio != null && activityRatio >= ACTIVITY_RATIO_TRIGGER
        val triggerByRatioDelta = activityRatio != null &&
            activityRatioDelta != null &&
            activityRatio >= ACTIVITY_RATIO_DELTA_FLOOR &&
            activityRatioDelta >= ACTIVITY_RATIO_DELTA_TRIGGER
        val triggerBySteps = stepsDelta5 != null && stepsDelta5 >= STEPS_DELTA5_TRIGGER

        var recoveryToBase = false

        if (!activityProtectionActive && (triggerByRatio || triggerByRatioDelta || triggerBySteps)) {
            activityProtectionActive = true
            activityProtectionLowCycles = 0
            activityProtectionTargetMmol = mapActivityTarget(activityRatio, stepsDelta5)
            val adaptiveMin = context.adaptiveMinTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
            val adaptiveMax = context.adaptiveMaxTargetMmol.coerceIn(adaptiveMin, MAX_TARGET_MMOL)
            activityProtectionRestoreBaseMmol = context.baseTargetMmol.coerceIn(adaptiveMin, adaptiveMax)
        } else if (activityProtectionActive) {
            activityProtectionTargetMmol = mapActivityTarget(activityRatio, stepsDelta5)
            val hasLoadSignal = activityRatio != null || stepsDelta5 != null
            val lowLoad = hasLoadSignal &&
                (activityRatio?.let { it <= ACTIVITY_RATIO_END_MAX } ?: true) &&
                (stepsDelta5?.let { it <= STEPS_DELTA5_END_MAX } ?: true)
            activityProtectionLowCycles = if (lowLoad) {
                activityProtectionLowCycles + 1
            } else {
                0
            }
            if (activityProtectionLowCycles >= ACTIVITY_END_LOW_CYCLES) {
                activityProtectionActive = false
                activityProtectionLowCycles = 0
                recoveryToBase = true
            }
        }

        previousTelemetryTs = nowTs
        if (stepsCount != null) previousStepsCount = stepsCount
        if (activityRatio != null) previousActivityRatio = activityRatio

        if (recoveryToBase) {
            val target = activityProtectionRestoreBaseMmol ?: context.baseTargetMmol
            activityProtectionRestoreBaseMmol = null
            return ActivitySignal(
                active = false,
                targetMmol = target,
                recoveryToBase = true,
                stepsDelta5 = stepsDelta5,
                activityRatio = activityRatio,
                activityRatioDelta = activityRatioDelta,
                triggerByRatio = triggerByRatio,
                triggerByRatioDelta = triggerByRatioDelta,
                triggerBySteps = triggerBySteps,
                lowCycles = ACTIVITY_END_LOW_CYCLES
            )
        }

        return ActivitySignal(
            active = activityProtectionActive,
            targetMmol = if (activityProtectionActive) activityProtectionTargetMmol else null,
            recoveryToBase = false,
            stepsDelta5 = stepsDelta5,
            activityRatio = activityRatio,
            activityRatioDelta = activityRatioDelta,
            triggerByRatio = triggerByRatio,
            triggerByRatioDelta = triggerByRatioDelta,
            triggerBySteps = triggerBySteps,
            lowCycles = activityProtectionLowCycles
        )
    }

    private fun observedDelta5(glucose: List<GlucosePoint>): Double? {
        val latest = glucose.maxByOrNull { it.ts } ?: return null
        val previous = glucose
            .asSequence()
            .filter { it.ts < latest.ts }
            .sortedByDescending { it.ts }
            .firstOrNull { candidate ->
                val dtMin = (latest.ts - candidate.ts).toDouble() / 60_000.0
                dtMin in 2.0..15.0
            } ?: return null
        val dtMin = (latest.ts - previous.ts).toDouble() / 60_000.0
        return ((latest.valueMmol - previous.valueMmol) / (dtMin / 5.0)).coerceIn(-1.6, 1.6)
    }

    private fun mapActivityTarget(activityRatio: Double?, stepsDelta5: Double?): Double {
        val ratioScore = if (activityRatio != null) {
            ((activityRatio - ACTIVITY_RATIO_TRIGGER) /
                (ACTIVITY_RATIO_TARGET_MAX - ACTIVITY_RATIO_TRIGGER))
                .coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val stepsScore = if (stepsDelta5 != null) {
            ((stepsDelta5 - STEPS_DELTA5_TRIGGER) /
                (STEPS_DELTA5_TARGET_MAX - STEPS_DELTA5_TRIGGER))
                .coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val load = max(ratioScore, stepsScore)
        return (ACTIVITY_TARGET_MIN_MMOL + load * (ACTIVITY_TARGET_MAX_MMOL - ACTIVITY_TARGET_MIN_MMOL))
            .coerceIn(ACTIVITY_TARGET_MIN_MMOL, ACTIVITY_TARGET_MAX_MMOL)
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

    private data class ActivitySignal(
        val active: Boolean,
        val targetMmol: Double?,
        val recoveryToBase: Boolean,
        val stepsDelta5: Double?,
        val activityRatio: Double?,
        val activityRatioDelta: Double?,
        val triggerByRatio: Boolean,
        val triggerByRatioDelta: Boolean,
        val triggerBySteps: Boolean,
        val lowCycles: Int
    )

    companion object {
        const val RULE_ID = "AdaptiveTargetController.v1"
        private const val MIN_TARGET_MMOL = 4.0
        private const val MAX_TARGET_MMOL = 10.0
        private const val TARGET_STEP_MMOL = 0.05
        private const val EPS_EQ = 1e-6
        private const val ACTIVITY_TARGET_MIN_MMOL = 7.7
        private const val ACTIVITY_TARGET_MAX_MMOL = 8.7
        private const val ACTIVITY_DURATION_MINUTES = 30
        private const val ACTIVITY_RATIO_TRIGGER = 1.22
        private const val ACTIVITY_RATIO_DELTA_TRIGGER = 0.10
        private const val ACTIVITY_RATIO_DELTA_FLOOR = 1.10
        private const val ACTIVITY_RATIO_TARGET_MAX = 1.70
        private const val STEPS_DELTA5_TRIGGER = 90.0
        private const val STEPS_DELTA5_TARGET_MAX = 220.0
        private const val ACTIVITY_RATIO_END_MAX = 1.05
        private const val STEPS_DELTA5_END_MAX = 20.0
        private const val ACTIVITY_END_LOW_CYCLES = 6
        private const val ACTIVITY_DT_MIN_ALLOWED_MIN = 1.0
        private const val ACTIVITY_DT_MIN_ALLOWED_MAX = 30.0
    }
}
