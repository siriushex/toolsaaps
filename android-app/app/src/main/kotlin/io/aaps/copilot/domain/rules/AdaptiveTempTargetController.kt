package io.aaps.copilot.domain.rules

import kotlin.math.abs

class AdaptiveTempTargetController {

    data class Input(
        val nowTs: Long,
        val baseTarget: Double,
        val targetMinMmol: Double = TMIN,
        val targetMaxMmol: Double = TMAX,
        val currentGlucoseMmol: Double? = null,
        val observedDelta5Mmol: Double? = null,
        val pred5: Double,
        val pred30: Double,
        val pred60: Double,
        val ciLow5: Double,
        val ciHigh5: Double,
        val ciLow30: Double,
        val ciHigh30: Double,
        val ciLow60: Double,
        val ciHigh60: Double,
        val uamActive: Boolean,
        val previousTempTarget: Double?,
        val previousI: Double,
        val cobGrams: Double? = null,
        val iobUnits: Double? = null
    )

    data class Output(
        val newTempTarget: Double,
        val durationMin: Int,
        val updatedI: Double,
        val reason: String,
        val debugFields: Map<String, Double>
    )

    fun evaluate(input: Input): Output {
        val tMin = input.targetMinMmol.coerceIn(HARD_TARGET_MIN_MMOL, HARD_TARGET_MAX_MMOL)
        val tMax = input.targetMaxMmol.coerceIn(tMin, HARD_TARGET_MAX_MMOL)
        val projectedMinIn60m = minOf(
            input.pred5,
            input.pred30,
            input.pred60,
            input.ciLow5,
            input.ciLow30,
            input.ciLow60
        )
        val lowPredictionRiskIn60m = projectedMinIn60m < LOW_GLUCOSE_RISK_THRESHOLD_MMOL
        val effectiveMinTarget = tMin
        val userBaseTarget = input.baseTarget.coerceIn(effectiveMinTarget, tMax)
        val currentGlucose = input.currentGlucoseMmol?.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val cob = (input.cobGrams ?: 0.0).coerceIn(0.0, 400.0)
        val iob = (input.iobUnits ?: 0.0).coerceIn(0.0, 30.0)
        val cobForcedBase = if (cob >= COB_SIGNIFICANT_GRAMS) COB_FORCED_BASE_TARGET_MMOL else userBaseTarget
        val iobRelief = ((iob - IOB_RELIEF_THRESHOLD_U).coerceAtLeast(0.0) * IOB_RELIEF_GAIN)
            .coerceIn(0.0, IOB_RELIEF_MAX_MMOL)
        val tb = (cobForcedBase + iobRelief).coerceIn(effectiveMinTarget, tMax)

        val w5CI = ((input.ciHigh5 - input.ciLow5) / 2.0).coerceAtLeast(1e-6)
        val w30CI = ((input.ciHigh30 - input.ciLow30) / 2.0).coerceAtLeast(1e-6)
        val w60CI = ((input.ciHigh60 - input.ciLow60) / 2.0).coerceAtLeast(1e-6)

        val (baseW5, baseW30, baseW60) = if (input.uamActive) {
            Triple(0.10, 0.30, 0.60)
        } else {
            Triple(0.15, 0.35, 0.50)
        }

        val riseFromNow = currentGlucose
            ?.let { (input.pred5 - it).coerceAtLeast(0.0) }
            ?: 0.0
        val observedDelta5 = input.observedDelta5Mmol
            ?.coerceIn(-MAX_OBSERVED_DELTA5_MMOL, MAX_OBSERVED_DELTA5_MMOL)
            ?: currentGlucose
                ?.let { (input.pred5 - it).coerceIn(-MAX_OBSERVED_DELTA5_MMOL, MAX_OBSERVED_DELTA5_MMOL) }
            ?: 0.0
        val riseShockUrgency = ((observedDelta5 - TREND_SHOCK_THRESHOLD_MMOL5) / TREND_SHOCK_FULL_SCALE_MMOL5)
            .coerceIn(0.0, 1.0)
        val fallShockUrgency = (((-observedDelta5) - TREND_SHOCK_THRESHOLD_MMOL5) / TREND_SHOCK_FULL_SCALE_MMOL5)
            .coerceIn(0.0, 1.0)
        val rise30 = (input.pred30 - input.pred5).coerceAtLeast(0.0)
        val rise60 = (input.pred60 - input.pred30).coerceAtLeast(0.0)
        val fastRiseSignal = riseFromNow + rise30 * FAST_RISE_30_WEIGHT + rise60 * FAST_RISE_60_WEIGHT
        val riseUrgency = maxOf(
            (fastRiseSignal / FAST_RISE_FULL_SCALE_MMOL).coerceIn(0.0, 1.0),
            riseShockUrgency
        )

        val w5Base = baseW5 + FAST_RISE_W5_BOOST * riseUrgency
        val w30Base = (baseW30 + FAST_RISE_W30_BOOST * riseUrgency).coerceAtLeast(0.05)
        val w60Base = (baseW60 - FAST_RISE_W60_REDUCTION * riseUrgency).coerceAtLeast(0.10)

        val w5Adj = w5Base / (w5CI * w5CI + EPS_W)
        val w30Adj = w30Base / (w30CI * w30CI + EPS_W)
        val w60Adj = w60Base / (w60CI * w60CI + EPS_W)
        val sumW = (w5Adj + w30Adj + w60Adj).coerceAtLeast(1e-9)

        val w5N = w5Adj / sumW
        val w30N = w30Adj / sumW
        val w60N = w60Adj / sumW

        val pMin = minOf(input.ciLow5, input.ciLow30, input.ciLow60)
        val pCtrlLow = w5N * input.ciLow5 + w30N * input.ciLow30 + w60N * input.ciLow60
        val nearTermLow = input.ciLow5
        val currentTempTarget = input.previousTempTarget?.coerceIn(tMin, tMax)
        if (pMin < LOW_BOUND_GUARD_THRESHOLD_MMOL && currentTempTarget != null) {
            if (currentTempTarget < LOW_BOUND_GUARD_TARGET_MMOL) {
                return Output(
                    newTempTarget = LOW_BOUND_GUARD_TARGET_MMOL.coerceIn(tMin, tMax),
                    durationMin = DURATION_MIN,
                    updatedI = input.previousI,
                    reason = "safety_raise_target_to_five",
                    debugFields = mapOf(
                        "targetMin" to tMin,
                        "targetMax" to tMax,
                        "currentTempTarget" to currentTempTarget,
                        "guardLowBoundThreshold" to LOW_BOUND_GUARD_THRESHOLD_MMOL,
                        "guardLowBoundTarget" to LOW_BOUND_GUARD_TARGET_MMOL,
                        "Pmin" to pMin
                    )
                )
            }
            return Output(
                newTempTarget = currentTempTarget,
                durationMin = DURATION_MIN,
                updatedI = input.previousI,
                reason = "safety_keep_existing_target",
                debugFields = mapOf(
                    "targetMin" to tMin,
                    "targetMax" to tMax,
                    "currentTempTarget" to currentTempTarget,
                    "guardLowBoundThreshold" to LOW_BOUND_GUARD_THRESHOLD_MMOL,
                    "guardLowBoundTarget" to LOW_BOUND_GUARD_TARGET_MMOL,
                    "Pmin" to pMin
                )
            )
        }
        val severeNearTermLow = nearTermLow < FORCE_HIGH_SEVERE_BELOW
        val safetySuppressedByHighTrajectory = input.pred5 >= tb + SAFETY_SUPPRESS_MARGIN_MMOL &&
            input.pred30 >= tb + SAFETY_SUPPRESS_MARGIN_MMOL &&
            input.pred60 >= tb + SAFETY_SUPPRESS_MARGIN_MMOL
        val safetySuppressedByCurrentHigh = !severeNearTermLow &&
            currentGlucose != null &&
            currentGlucose >= tb + HIGH_CURRENT_GLUCOSE_MARGIN_MMOL &&
            input.pred5 >= tb + HIGH_CURRENT_PRED5_MARGIN_MMOL
        val safetySuppressed = safetySuppressedByHighTrajectory || safetySuppressedByCurrentHigh

        val immediateForceHighRisk = nearTermLow < FORCE_HIGH_IF_BELOW
        val weightedForceHighRisk = pCtrlLow < FORCE_HIGH_CTRLLOW_BELOW &&
            input.pred5 < tb + FORCE_HIGH_NEAR_TERM_MARGIN_MMOL
        val shouldForceHigh = !safetySuppressed &&
            (immediateForceHighRisk || weightedForceHighRisk)
        if (shouldForceHigh) {
            val target = applyRateLimit(tMax, input.previousTempTarget).coerceIn(effectiveMinTarget, tMax)
            return Output(
                newTempTarget = target,
                durationMin = DURATION_MIN,
                updatedI = input.previousI,
                reason = "safety_force_high",
                debugFields = mapOf(
                    "Tb" to tb,
                    "TbUser" to userBaseTarget,
                    "targetMin" to tMin,
                    "targetMinEffective" to effectiveMinTarget,
                    "targetMax" to tMax,
                    "projectedMin60m" to projectedMinIn60m,
                    "lowPredictionRisk60m" to if (lowPredictionRiskIn60m) 1.0 else 0.0,
                    "currentGlucose" to (currentGlucose ?: Double.NaN),
                    "cobGrams" to cob,
                    "iobUnits" to iob,
                    "nearTermLow" to nearTermLow,
                    "severeNearTermLow" to if (severeNearTermLow) 1.0 else 0.0,
                    "Pmin" to pMin,
                    "PctrlLow" to pCtrlLow,
                    "immediateForceHighRisk" to if (immediateForceHighRisk) 1.0 else 0.0,
                    "weightedForceHighRisk" to if (weightedForceHighRisk) 1.0 else 0.0,
                    "safetySuppressedByCurrentHigh" to if (safetySuppressedByCurrentHigh) 1.0 else 0.0,
                    "safetySuppressedByHighTrajectory" to if (safetySuppressedByHighTrajectory) 1.0 else 0.0,
                    "w5CI" to w5CI,
                    "w30CI" to w30CI,
                    "w60CI" to w60CI
                )
            )
        }

        val immediateHypoGuardRisk = nearTermLow < tb - M_HYPO
        val weightedHypoGuardRisk = pCtrlLow < tb - M_HYPO &&
            input.pred5 < tb + HYPO_GUARD_NEAR_TERM_MARGIN_MMOL
        if (!safetySuppressed && (immediateHypoGuardRisk || weightedHypoGuardRisk)) {
            val raw = (tb + K_HYPO * (tb - pCtrlLow)).coerceIn(tb, tMax)
            val target = applyRateLimit(raw, input.previousTempTarget).coerceIn(effectiveMinTarget, tMax)
            return Output(
                newTempTarget = target,
                durationMin = DURATION_MIN,
                updatedI = input.previousI,
                reason = "safety_hypo_guard",
                debugFields = mapOf(
                    "Tb" to tb,
                    "TbUser" to userBaseTarget,
                    "targetMin" to tMin,
                    "targetMinEffective" to effectiveMinTarget,
                    "targetMax" to tMax,
                    "projectedMin60m" to projectedMinIn60m,
                    "lowPredictionRisk60m" to if (lowPredictionRiskIn60m) 1.0 else 0.0,
                    "currentGlucose" to (currentGlucose ?: Double.NaN),
                    "cobGrams" to cob,
                    "iobUnits" to iob,
                    "nearTermLow" to nearTermLow,
                    "severeNearTermLow" to if (severeNearTermLow) 1.0 else 0.0,
                    "Pmin" to pMin,
                    "PctrlLow" to pCtrlLow,
                    "immediateHypoGuardRisk" to if (immediateHypoGuardRisk) 1.0 else 0.0,
                    "weightedHypoGuardRisk" to if (weightedHypoGuardRisk) 1.0 else 0.0,
                    "rawTarget" to raw,
                    "safetySuppressedByCurrentHigh" to if (safetySuppressedByCurrentHigh) 1.0 else 0.0,
                    "safetySuppressedByHighTrajectory" to if (safetySuppressedByHighTrajectory) 1.0 else 0.0,
                    "w5CI" to w5CI,
                    "w30CI" to w30CI,
                    "w60CI" to w60CI
                )
            )
        }

        val pCtrlRaw = w5N * input.pred5 + w30N * input.pred30 + w60N * input.pred60
        val leadOvershoot = (input.pred5 - pCtrlRaw).coerceAtLeast(0.0)
        val rapidRiseBias = if (fastRiseSignal > 0.0) {
            (
                (
                    ((input.pred5 - tb).coerceAtLeast(0.0)) * RAPID_RISE_PRED5_GAIN +
                        leadOvershoot * RAPID_RISE_LEAD_GAIN
                    ) * riseUrgency
                ).coerceIn(0.0, RAPID_RISE_BIAS_MAX_MMOL)
        } else {
            0.0
        }
        val trendShockBias = when {
            observedDelta5 >= TREND_SHOCK_THRESHOLD_MMOL5 -> (
                observedDelta5 * TREND_SHOCK_BIAS_GAIN * riseShockUrgency
            ).coerceIn(0.0, TREND_SHOCK_BIAS_MAX_MMOL)
            observedDelta5 <= -TREND_SHOCK_THRESHOLD_MMOL5 -> -(
                (-observedDelta5) * TREND_SHOCK_BIAS_GAIN * fallShockUrgency
            ).coerceIn(0.0, TREND_SHOCK_BIAS_MAX_MMOL)
            else -> 0.0
        }
        val cobBias = (cob * COB_CONTROL_GAIN_PER_GRAM).coerceIn(0.0, COB_CONTROL_BIAS_MAX_MMOL)
        val iobBias = (iob * IOB_CONTROL_GAIN_PER_UNIT).coerceIn(0.0, IOB_CONTROL_BIAS_MAX_MMOL)
        val pCtrl = (pCtrlRaw + rapidRiseBias + trendShockBias + cobBias - iobBias)
            .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val e = pCtrl - tb
        val effectiveDeadband = (M_DEAD - FAST_RISE_DEADBAND_REDUCTION * riseUrgency)
            .coerceAtLeast(M_DEAD_MIN)

        var iNew: Double
        val tRaw: Double

        if (abs(e) < effectiveDeadband) {
            tRaw = tb
            iNew = input.previousI * 0.8
        } else {
            val iRaw = input.previousI + e * CYCLE_MIN
            iNew = iRaw.coerceIn(-I_MAX, I_MAX)

            val positiveErrorUrgency = if (e > 0.0) {
                maxOf(
                    riseUrgency,
                    ((pCtrl - tb) / HIGH_GLUCOSE_FULL_SCALE_MMOL).coerceIn(0.0, 1.0)
                )
            } else {
                0.0
            }
            val kpApplied = KP + HIGH_GLUCOSE_KP_BOOST * positiveErrorUrgency
            val kiApplied = KI + HIGH_GLUCOSE_KI_BOOST * positiveErrorUrgency

            var deltaT = -kpApplied * e - kiApplied * iNew
            deltaT = deltaT.coerceIn(-DELTA_T_MAX, DELTA_T_MAX)

            val unclampedTarget = tb + deltaT
            val clampedTarget = unclampedTarget.coerceIn(effectiveMinTarget, tMax)

            if (clampedTarget == effectiveMinTarget && deltaT < 0.0) iNew = input.previousI
            if (clampedTarget == tMax && deltaT > 0.0) iNew = input.previousI

            tRaw = clampedTarget
        }

        val guard = computeHighGlucoseGuard(
            userBaseTarget = userBaseTarget,
            pCtrlRaw = pCtrlRaw,
            pMin = pMin,
            tMin = effectiveMinTarget,
            tMax = tMax
        )
        val guardedRawTarget = if (guard.active) {
            tRaw.coerceAtMost(guard.maxTarget)
        } else {
            tRaw
        }
        val relaxedTarget = applyTrendStopRelaxation(
            rawTarget = guardedRawTarget,
            baseTarget = tb,
            currentTarget = currentTempTarget,
            observedDelta5 = observedDelta5,
            pred30 = input.pred30,
            pred60 = input.pred60
        )
        if (abs(relaxedTarget - guardedRawTarget) > 1e-6) {
            val relaxStrength = (abs(relaxedTarget - guardedRawTarget) / RELAXATION_FULL_EFFECT_MMOL).coerceIn(0.0, 1.0)
            iNew *= (1.0 - RELAXATION_I_DECAY_GAIN * relaxStrength).coerceIn(RELAXATION_I_MIN_SCALE, 1.0)
        }
        val target = applyRateLimit(relaxedTarget, input.previousTempTarget).coerceIn(effectiveMinTarget, tMax)
        val debugFields = mutableMapOf(
            "Tb" to tb,
            "TbUser" to userBaseTarget,
            "targetMin" to tMin,
            "targetMinEffective" to effectiveMinTarget,
            "targetMax" to tMax,
            "projectedMin60m" to projectedMinIn60m,
            "lowPredictionRisk60m" to if (lowPredictionRiskIn60m) 1.0 else 0.0,
            "currentGlucose" to (currentGlucose ?: Double.NaN),
            "cobGrams" to cob,
            "iobUnits" to iob,
            "nearTermLow" to nearTermLow,
            "severeNearTermLow" to if (severeNearTermLow) 1.0 else 0.0,
            "Pctrl" to pCtrl,
            "PctrlRaw" to pCtrlRaw,
            "observedDelta5" to observedDelta5,
            "fastRiseSignal" to fastRiseSignal,
            "riseUrgency" to riseUrgency,
            "riseShockUrgency" to riseShockUrgency,
            "fallShockUrgency" to fallShockUrgency,
            "leadOvershoot" to leadOvershoot,
            "rapidRiseBias" to rapidRiseBias,
            "trendShockBias" to trendShockBias,
            "cobBias" to cobBias,
            "iobBias" to iobBias,
            "error" to e,
            "effectiveDeadband" to effectiveDeadband,
            "Pmin" to pMin,
            "PctrlLow" to pCtrlLow,
            "safetySuppressedByCurrentHigh" to if (safetySuppressedByCurrentHigh) 1.0 else 0.0,
            "safetySuppressedByHighTrajectory" to if (safetySuppressedByHighTrajectory) 1.0 else 0.0,
            "w5N" to w5N,
            "w30N" to w30N,
            "w60N" to w60N,
            "updatedI" to iNew,
            "targetRaw" to tRaw,
            "targetGuarded" to guardedRawTarget,
            "targetRelaxed" to relaxedTarget,
            "targetFinal" to target,
            "highGuardActive" to if (guard.active) 1.0 else 0.0
        )
        if (guard.active) {
            debugFields["highGuardMaxTarget"] = guard.maxTarget
        }
        return Output(
            newTempTarget = target,
            durationMin = DURATION_MIN,
            updatedI = iNew,
            reason = if (abs(e) < M_DEAD) "control_deadband" else "control_pi",
            debugFields = debugFields
        )
    }

    private fun computeHighGlucoseGuard(
        userBaseTarget: Double,
        pCtrlRaw: Double,
        pMin: Double,
        tMin: Double,
        tMax: Double
    ): HighGlucoseGuard {
        val noHypoRisk = pMin >= userBaseTarget - HIGH_GUARD_HYPO_RISK_MARGIN_MMOL
        if (!noHypoRisk) return HighGlucoseGuard(active = false, maxTarget = tMax)

        return when {
            pCtrlRaw >= userBaseTarget + VERY_HIGH_GLUCOSE_MARGIN_MMOL -> {
                val forced = (userBaseTarget - VERY_HIGH_GLUCOSE_PULLDOWN_MMOL).coerceIn(tMin, tMax)
                HighGlucoseGuard(active = true, maxTarget = forced)
            }

            pCtrlRaw >= userBaseTarget + HIGH_GLUCOSE_MARGIN_MMOL -> {
                HighGlucoseGuard(active = true, maxTarget = userBaseTarget.coerceIn(tMin, tMax))
            }

            else -> HighGlucoseGuard(active = false, maxTarget = tMax)
        }
    }

    private data class HighGlucoseGuard(
        val active: Boolean,
        val maxTarget: Double
    )

    private fun applyRateLimit(target: Double, _previousTempTarget: Double?): Double {
        // Rate limiting is disabled: controller must be able to set the computed target immediately.
        return target
    }

    private fun applyTrendStopRelaxation(
        rawTarget: Double,
        baseTarget: Double,
        currentTarget: Double?,
        observedDelta5: Double,
        pred30: Double,
        pred60: Double
    ): Double {
        val activeTarget = currentTarget ?: return rawTarget
        val targetDistanceFromBase = activeTarget - baseTarget
        if (abs(targetDistanceFromBase) < RELAXATION_MIN_ACTIVE_DISTANCE_MMOL) return rawTarget

        val activePushDirection = when {
            activeTarget < baseTarget -> -1.0
            activeTarget > baseTarget -> 1.0
            else -> 0.0
        }
        if (activePushDirection == 0.0) return rawTarget

        val trendStoppedUrgency = ((TREND_STOP_THRESHOLD_MMOL5 - abs(observedDelta5)) / TREND_STOP_THRESHOLD_MMOL5)
            .coerceIn(0.0, 1.0)
        if (trendStoppedUrgency <= 0.0) return rawTarget

        val midTermProjected = pred30 * RELAXATION_MIDTERM_PRED30_WEIGHT + pred60 * RELAXATION_MIDTERM_PRED60_WEIGHT
        val midTermError = midTermProjected - baseTarget
        val stillNeedsSameDirectionPressure = when {
            activePushDirection < 0.0 -> midTermError >= RELAXATION_SAME_DIRECTION_MARGIN_MMOL
            else -> midTermError <= -RELAXATION_SAME_DIRECTION_MARGIN_MMOL
        }
        if (stillNeedsSameDirectionPressure) return rawTarget

        val closenessToBase = (1.0 - abs(midTermError) / RELAXATION_MIDTERM_FULL_SCALE_MMOL).coerceIn(0.0, 1.0)
        if (closenessToBase <= 0.0) return rawTarget

        val relaxBlend = (trendStoppedUrgency * closenessToBase * RELAXATION_MAX_BLEND).coerceIn(0.0, RELAXATION_MAX_BLEND)
        return rawTarget + (baseTarget - rawTarget) * relaxBlend
    }

    companion object {
        const val TMIN = 4.0
        const val TMAX = 9.0
        private const val HARD_TARGET_MIN_MMOL = 4.0
        private const val HARD_TARGET_MAX_MMOL = 10.0
        const val CYCLE_MIN = 5.0
        const val DURATION_MIN = 30

        const val M_DEAD = 0.2

        const val M_HYPO = 0.4
        const val K_HYPO = 1.0
        const val FORCE_HIGH_IF_BELOW = 4.2
        const val FORCE_HIGH_CTRLLOW_BELOW = 3.7

        const val EPS_W = 1e-6

        const val KP = 0.35
        const val KI = 0.02
        const val I_MAX = 200.0
        const val DELTA_T_MAX = 2.0

        const val COB_SIGNIFICANT_GRAMS = 20.0
        const val COB_FORCED_BASE_TARGET_MMOL = 4.2

        private const val IOB_RELIEF_THRESHOLD_U = 1.5
        private const val IOB_RELIEF_GAIN = 0.20
        private const val IOB_RELIEF_MAX_MMOL = 0.60

        private const val COB_CONTROL_GAIN_PER_GRAM = 0.006
        private const val COB_CONTROL_BIAS_MAX_MMOL = 1.20
        private const val IOB_CONTROL_GAIN_PER_UNIT = 0.35
        private const val IOB_CONTROL_BIAS_MAX_MMOL = 1.40
        private const val FAST_RISE_30_WEIGHT = 0.75
        private const val FAST_RISE_60_WEIGHT = 0.35
        private const val FAST_RISE_FULL_SCALE_MMOL = 1.20
        private const val FAST_RISE_W5_BOOST = 0.42
        private const val FAST_RISE_W30_BOOST = 0.10
        private const val FAST_RISE_W60_REDUCTION = 0.32
        private const val FAST_RISE_DEADBAND_REDUCTION = 0.10
        private const val M_DEAD_MIN = 0.08
        private const val RAPID_RISE_PRED5_GAIN = 0.30
        private const val RAPID_RISE_LEAD_GAIN = 0.28
        private const val RAPID_RISE_BIAS_MAX_MMOL = 1.20
        private const val TREND_SHOCK_THRESHOLD_MMOL5 = 0.30
        private const val TREND_SHOCK_FULL_SCALE_MMOL5 = 0.40
        private const val TREND_SHOCK_BIAS_GAIN = 0.90
        private const val TREND_SHOCK_BIAS_MAX_MMOL = 0.95
        private const val MAX_OBSERVED_DELTA5_MMOL = 1.6
        private const val HIGH_GLUCOSE_FULL_SCALE_MMOL = 1.80
        private const val HIGH_GLUCOSE_KP_BOOST = 0.16
        private const val HIGH_GLUCOSE_KI_BOOST = 0.02
        private const val TREND_STOP_THRESHOLD_MMOL5 = 0.10
        private const val RELAXATION_MIDTERM_PRED30_WEIGHT = 0.55
        private const val RELAXATION_MIDTERM_PRED60_WEIGHT = 0.45
        private const val RELAXATION_MIDTERM_FULL_SCALE_MMOL = 1.20
        private const val RELAXATION_SAME_DIRECTION_MARGIN_MMOL = 0.45
        private const val RELAXATION_MAX_BLEND = 0.55
        private const val RELAXATION_MIN_ACTIVE_DISTANCE_MMOL = 0.20
        private const val RELAXATION_I_DECAY_GAIN = 0.85
        private const val RELAXATION_I_MIN_SCALE = 0.25
        private const val RELAXATION_FULL_EFFECT_MMOL = 1.20

        private const val MIN_GLUCOSE_MMOL = 2.2
        private const val MAX_GLUCOSE_MMOL = 22.0
        private const val SAFETY_SUPPRESS_MARGIN_MMOL = 1.0
        private const val FORCE_HIGH_NEAR_TERM_MARGIN_MMOL = 0.35
        private const val FORCE_HIGH_SEVERE_BELOW = 3.6
        private const val HYPO_GUARD_NEAR_TERM_MARGIN_MMOL = 0.60
        private const val HIGH_CURRENT_GLUCOSE_MARGIN_MMOL = 1.5
        private const val HIGH_CURRENT_PRED5_MARGIN_MMOL = 0.8
        private const val HIGH_GUARD_HYPO_RISK_MARGIN_MMOL = 0.30
        private const val HIGH_GLUCOSE_MARGIN_MMOL = 0.80
        private const val VERY_HIGH_GLUCOSE_MARGIN_MMOL = 2.20
        private const val VERY_HIGH_GLUCOSE_PULLDOWN_MMOL = 1.10
        private const val LOW_GLUCOSE_RISK_THRESHOLD_MMOL = 3.0
        private const val LOW_BOUND_GUARD_THRESHOLD_MMOL = 3.6
        private const val LOW_BOUND_GUARD_TARGET_MMOL = 5.0

    }
}
