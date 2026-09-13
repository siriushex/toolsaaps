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
        val safetyIobUnits: Double? = null,
        val rapidFallPriorConfirmedCycles: Int = 0
    )

    data class Output(
        val newTempTarget: Double,
        val durationMin: Int,
        val updatedI: Double,
        val reason: String,
        val debugFields: Map<String, Double>
    )

    fun evaluate(input: Input): Output {
        val output = evaluateCore(input)
        val minimum = input.targetMinMmol.coerceIn(HARD_TARGET_MIN_MMOL, HARD_TARGET_MAX_MMOL)
        val maximum = input.targetMaxMmol.coerceIn(minimum, HARD_TARGET_MAX_MMOL)
        val base = input.baseTarget.coerceIn(minimum, maximum)
        val safetyIobQualified = input.safetyIobUnits
            ?.takeIf { it.isFinite() && it in 0.0..30.0 }
        if (output.newTempTarget >= base - EPS_TARGET || safetyIobQualified != null) return output

        val neutralOrRaisingTarget = maxOf(base, input.previousTempTarget ?: base)
            .coerceIn(minimum, maximum)
        return output.copy(
            newTempTarget = neutralOrRaisingTarget,
            updatedI = input.previousI,
            reason = "safety_iob_missing_blocks_lowering",
            debugFields = output.debugFields + mapOf(
                "safetyIobQualified" to 0.0,
                "blockedLowerTarget" to output.newTempTarget,
                "safetyBaseTarget" to base
            )
        )
    }

    private fun evaluateCore(input: Input): Output {
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
        val iob = (input.safetyIobUnits ?: 0.0).coerceIn(0.0, 30.0)
        val iobRelief = ((iob - IOB_RELIEF_THRESHOLD_U).coerceAtLeast(0.0) * IOB_RELIEF_GAIN)
            .coerceIn(0.0, IOB_RELIEF_MAX_MMOL)
        val tb = (userBaseTarget + iobRelief).coerceIn(effectiveMinTarget, tMax)

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

        val forecastDrop5To30 = input.pred5 - input.pred30
        val forecastDrop30To60 = input.pred30 - input.pred60
        val forecastDrop5To60 = input.pred5 - input.pred60
        val coherentFarTermFall = forecastDrop5To30 >= RAPID_FALL_MIN_DROP_5_TO_30_MMOL &&
            forecastDrop30To60 >= RAPID_FALL_MIN_DROP_30_TO_60_MMOL &&
            forecastDrop5To60 >= RAPID_FALL_MIN_DROP_5_TO_60_MMOL
        val rapidFallFarTermLowCandidate =
            observedDelta5 <= -RAPID_FALL_OBSERVED_DELTA5_MMOL &&
                coherentFarTermFall &&
                input.ciLow30 < RAPID_FALL_CI30_CONFIRM_BELOW_MMOL &&
                input.ciLow60 < LOW_GLUCOSE_RISK_THRESHOLD_MMOL &&
                input.pred60 < tb + RAPID_FALL_PRED60_BASE_MARGIN_MMOL
        val rapidFallFarTermLowConfirmed = rapidFallFarTermLowCandidate &&
            input.rapidFallPriorConfirmedCycles >= RAPID_FALL_REQUIRED_PRIOR_CYCLES
        val farTermLowConfirmed = rapidFallFarTermLowConfirmed ||
            input.ciLow30 < FAR_TERM_CONFIRM_CI30_BELOW_MMOL &&
            (
                input.pred30 < tb + FAR_TERM_CONFIRM_PRED30_MARGIN_MMOL ||
                    observedDelta5 <= -FAR_TERM_CONFIRM_FALL_MMOL5
                )
        val effectiveCiLow60 = if (farTermLowConfirmed) input.ciLow60 else input.ciLow30
        val pMin = minOf(input.ciLow5, input.ciLow30, effectiveCiLow60)
        val pCtrlLow = w5N * input.ciLow5 + w30N * input.ciLow30 + w60N * effectiveCiLow60
        val nearTermLow = input.ciLow5
        val severeNearTermLow = nearTermLow < FORCE_HIGH_SEVERE_BELOW
        val midTermLow = minOf(input.ciLow30, effectiveCiLow60)
        val currentTempTarget = input.previousTempTarget?.coerceIn(tMin, tMax)
        val midTermFallingSignal = maxOf(
            (((-observedDelta5) - PREEMPTIVE_FALL_THRESHOLD_MMOL5) / PREEMPTIVE_FALL_FULL_SCALE_MMOL5)
                .coerceIn(0.0, 1.0),
            ((input.pred5 - input.pred30 - PREEMPTIVE_PRED_DROP_THRESHOLD_MMOL) / PREEMPTIVE_PRED_DROP_FULL_SCALE_MMOL)
                .coerceIn(0.0, 1.0),
            ((input.pred30 - input.pred60 - PREEMPTIVE_PRED_DROP_THRESHOLD_MMOL) / PREEMPTIVE_PRED_DROP_FULL_SCALE_MMOL)
                .coerceIn(0.0, 1.0)
        )
        val projectedCross36 = midTermLow < PREEMPTIVE_FORCE_HIGH_BELOW
        val projectedLowGuard = midTermLow < PREEMPTIVE_GUARD_BELOW
        val projectedLowWatch = midTermLow < PREEMPTIVE_WATCH_BELOW
        val preemptiveMidTermSupport = rapidFallFarTermLowConfirmed ||
            input.pred30 < tb + PREEMPTIVE_MEAN_SUPPORT_MARGIN_30_MMOL ||
            input.pred60 < tb + PREEMPTIVE_MEAN_SUPPORT_MARGIN_60_MMOL
        val preemptiveForceSupport = input.pred30 < tb - PREEMPTIVE_FORCE_MEAN_SUPPORT_MARGIN_30_MMOL ||
            input.pred60 < tb - PREEMPTIVE_FORCE_MEAN_SUPPORT_MARGIN_60_MMOL
        val preemptiveSuppressedByCurrentHigh = !projectedCross36 &&
            currentGlucose != null &&
            currentGlucose >= tb + HIGH_CURRENT_GLUCOSE_MARGIN_MMOL &&
            input.pred5 >= tb + HIGH_CURRENT_PRED5_MARGIN_MMOL
        val preemptiveSuppressedByHighTrajectory = !projectedCross36 &&
            input.pred5 >= tb + SAFETY_SUPPRESS_MARGIN_MMOL &&
            input.pred30 >= tb + PREEMPTIVE_HIGH_TRAJECTORY_MARGIN_30_MMOL &&
            input.pred60 >= tb + PREEMPTIVE_HIGH_TRAJECTORY_MARGIN_60_MMOL
        val preemptiveSuppressed = preemptiveSuppressedByCurrentHigh || preemptiveSuppressedByHighTrajectory
        val iobForecastReversalCandidate = currentGlucose != null &&
            currentTempTarget != null &&
            currentTempTarget < tb - EPS_TARGET &&
            currentGlucose <= tb + IOB_REVERSAL_MAX_CURRENT_ABOVE_BASE_MMOL &&
            iob >= IOB_REVERSAL_MIN_IOB_U &&
            forecastDrop5To30 >= -IOB_REVERSAL_MAX_NEAR_TERM_RISE_MMOL &&
            forecastDrop30To60 >= IOB_REVERSAL_MIN_DROP_30_TO_60_MMOL &&
            forecastDrop5To60 >= IOB_REVERSAL_MIN_DROP_5_TO_60_MMOL &&
            input.pred60 <= currentGlucose - IOB_REVERSAL_MIN_CURRENT_TO_60_DROP_MMOL &&
            input.ciLow60 < tb - IOB_REVERSAL_CI60_BELOW_BASE_MMOL
        val preemptiveSeverity = when {
            projectedCross36 -> 1.0
            projectedLowGuard -> ((PREEMPTIVE_GUARD_BELOW - midTermLow) / PREEMPTIVE_GUARD_FULL_SCALE_MMOL).coerceIn(0.0, 1.0)
            projectedLowWatch -> ((PREEMPTIVE_WATCH_BELOW - midTermLow) / PREEMPTIVE_WATCH_FULL_SCALE_MMOL).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val preemptiveGuardMinTarget = if (projectedLowGuard) {
            PREEMPTIVE_GUARD_MIN_TARGET_MMOL
        } else {
            PREEMPTIVE_WATCH_MIN_TARGET_MMOL
        }
        val preemptiveGuardBaseLift = if (projectedLowGuard) {
            PREEMPTIVE_GUARD_BASE_RAISE_MMOL
        } else {
            PREEMPTIVE_WATCH_BASE_RAISE_MMOL
        }
        val preemptiveForceTarget = (
            maxOf(tb + PREEMPTIVE_FORCE_BASE_RAISE_MMOL, PREEMPTIVE_FORCE_MIN_TARGET_MMOL) +
                preemptiveSeverity * PREEMPTIVE_FORCE_SEVERITY_GAIN_MMOL +
                midTermFallingSignal * PREEMPTIVE_FORCE_TREND_GAIN_MMOL
            ).coerceIn(tb, tMax)
        val preemptiveGuardTarget = (
            maxOf(tb + preemptiveGuardBaseLift, preemptiveGuardMinTarget) +
                preemptiveSeverity * PREEMPTIVE_GUARD_SEVERITY_GAIN_MMOL +
                midTermFallingSignal * PREEMPTIVE_GUARD_TREND_GAIN_MMOL
            ).coerceIn(tb, tMax)
        val preemptiveForceNeedsHigherTarget = currentTempTarget == null ||
            preemptiveForceTarget > currentTempTarget + EPS_TARGET
        val preemptiveGuardNeedsHigherTarget = currentTempTarget == null ||
            preemptiveGuardTarget > currentTempTarget + EPS_TARGET

        fun buildPreemptiveForceHigh(): Output {
            val raw = preemptiveForceTarget
            val target = maxOf(currentTempTarget ?: raw, raw).coerceIn(effectiveMinTarget, tMax)
            return Output(
                newTempTarget = target,
                durationMin = DURATION_MIN,
                updatedI = input.previousI,
                reason = "hypo_preemptive_force_high",
                debugFields = mapOf(
                    "Tb" to tb,
                    "TbUser" to userBaseTarget,
                    "targetMin" to tMin,
                    "targetMinEffective" to effectiveMinTarget,
                    "targetMax" to tMax,
                    "projectedMin60m" to projectedMinIn60m,
                    "currentGlucose" to (currentGlucose ?: Double.NaN),
                    "nearTermLow" to nearTermLow,
                    "midTermLow" to midTermLow,
                    "Pmin" to pMin,
                    "PctrlLow" to pCtrlLow,
                    "effectiveCiLow60" to effectiveCiLow60,
                    "preemptiveSeverity" to preemptiveSeverity,
                    "midTermFallingSignal" to midTermFallingSignal,
                    "rapidFallFarTermLowCandidate" to if (rapidFallFarTermLowCandidate) 1.0 else 0.0,
                    "rapidFallFarTermLowConfirmed" to if (rapidFallFarTermLowConfirmed) 1.0 else 0.0,
                    "rapidFallPriorConfirmedCycles" to input.rapidFallPriorConfirmedCycles.toDouble(),
                    "forecastDrop5To30" to forecastDrop5To30,
                    "forecastDrop30To60" to forecastDrop30To60,
                    "forecastDrop5To60" to forecastDrop5To60,
                    "rawTarget" to raw,
                    "currentTempTarget" to (currentTempTarget ?: Double.NaN),
                    "projectedCross36" to 1.0,
                    "preemptiveMidTermSupport" to if (preemptiveMidTermSupport) 1.0 else 0.0,
                    "preemptiveForceSupport" to if (preemptiveForceSupport) 1.0 else 0.0,
                    "preemptiveSuppressedByCurrentHigh" to if (preemptiveSuppressedByCurrentHigh) 1.0 else 0.0,
                    "preemptiveSuppressedByHighTrajectory" to if (preemptiveSuppressedByHighTrajectory) 1.0 else 0.0
                )
            )
        }

        fun buildPreemptiveGuard(): Output {
            val raw = preemptiveGuardTarget
            val target = maxOf(currentTempTarget ?: raw, raw).coerceIn(effectiveMinTarget, tMax)
            return Output(
                newTempTarget = target,
                durationMin = DURATION_MIN,
                updatedI = input.previousI,
                reason = if (projectedLowGuard) "hypo_preemptive_guard" else "hypo_preemptive_watch",
                debugFields = mapOf(
                    "Tb" to tb,
                    "TbUser" to userBaseTarget,
                    "targetMin" to tMin,
                    "targetMinEffective" to effectiveMinTarget,
                    "targetMax" to tMax,
                    "projectedMin60m" to projectedMinIn60m,
                    "currentGlucose" to (currentGlucose ?: Double.NaN),
                    "nearTermLow" to nearTermLow,
                    "midTermLow" to midTermLow,
                    "Pmin" to pMin,
                    "PctrlLow" to pCtrlLow,
                    "effectiveCiLow60" to effectiveCiLow60,
                    "preemptiveSeverity" to preemptiveSeverity,
                    "midTermFallingSignal" to midTermFallingSignal,
                    "rapidFallFarTermLowCandidate" to if (rapidFallFarTermLowCandidate) 1.0 else 0.0,
                    "rapidFallFarTermLowConfirmed" to if (rapidFallFarTermLowConfirmed) 1.0 else 0.0,
                    "rapidFallPriorConfirmedCycles" to input.rapidFallPriorConfirmedCycles.toDouble(),
                    "forecastDrop5To30" to forecastDrop5To30,
                    "forecastDrop30To60" to forecastDrop30To60,
                    "forecastDrop5To60" to forecastDrop5To60,
                    "rawTarget" to raw,
                    "currentTempTarget" to (currentTempTarget ?: Double.NaN),
                    "projectedLowGuard" to if (projectedLowGuard) 1.0 else 0.0,
                    "projectedLowWatch" to if (projectedLowWatch) 1.0 else 0.0,
                    "preemptiveMidTermSupport" to if (preemptiveMidTermSupport) 1.0 else 0.0,
                    "preemptiveSuppressedByCurrentHigh" to if (preemptiveSuppressedByCurrentHigh) 1.0 else 0.0,
                    "preemptiveSuppressedByHighTrajectory" to if (preemptiveSuppressedByHighTrajectory) 1.0 else 0.0
                )
            )
        }

        val preemptiveForceEligible = !severeNearTermLow &&
            !preemptiveSuppressed &&
            projectedCross36 &&
            preemptiveForceSupport &&
            preemptiveForceNeedsHigherTarget
        val preemptiveGuardEligible = !severeNearTermLow &&
            !preemptiveSuppressed &&
            preemptiveMidTermSupport &&
            (projectedLowGuard || projectedLowWatch) &&
            (midTermFallingSignal > 0.0 || input.pred60 < tb) &&
            preemptiveGuardNeedsHigherTarget

        if (pMin < LOW_BOUND_GUARD_THRESHOLD_MMOL && currentTempTarget != null) {
            if (preemptiveForceEligible) {
                return buildPreemptiveForceHigh()
            }
            if (preemptiveGuardEligible) {
                return buildPreemptiveGuard()
            }
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

        if (preemptiveForceEligible) {
            return buildPreemptiveForceHigh()
        }

        if (preemptiveGuardEligible) {
            return buildPreemptiveGuard()
        }

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

        if (iobForecastReversalCandidate) {
            val target = maxOf(currentTempTarget ?: tb, tb).coerceIn(effectiveMinTarget, tMax)
            return Output(
                newTempTarget = target,
                durationMin = DURATION_MIN,
                updatedI = 0.0,
                reason = "hypo_forecast_reversal_guard",
                debugFields = mapOf(
                    "Tb" to tb,
                    "TbUser" to userBaseTarget,
                    "targetMin" to tMin,
                    "targetMinEffective" to effectiveMinTarget,
                    "targetMax" to tMax,
                    "currentGlucose" to currentGlucose,
                    "currentTempTarget" to (currentTempTarget ?: Double.NaN),
                    "pred5" to input.pred5,
                    "pred30" to input.pred30,
                    "pred60" to input.pred60,
                    "ciLow60" to input.ciLow60,
                    "iobUnits" to iob,
                    "forecastDrop5To30" to forecastDrop5To30,
                    "forecastDrop30To60" to forecastDrop30To60,
                    "forecastDrop5To60" to forecastDrop5To60,
                    "iobForecastReversalCandidate" to 1.0,
                    "targetFinal" to target
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
        val aggressiveRiseEligible = currentGlucose != null &&
            observedDelta5 >= AGGRESSIVE_RISE_DELTA5_MMOL &&
            input.pred5 >= currentGlucose + AGGRESSIVE_RISE_PRED5_LEAD_MMOL &&
            input.pred30 >= tb + AGGRESSIVE_RISE_PRED30_MARGIN_MMOL &&
            input.ciLow30 >= maxOf(AGGRESSIVE_RISE_CI30_FLOOR_MMOL, tb - AGGRESSIVE_RISE_CI30_BASE_MARGIN_MMOL) &&
            iob <= AGGRESSIVE_RISE_MAX_IOB_U
        val controlMinTarget = if (aggressiveRiseEligible) {
            effectiveMinTarget
        } else {
            maxOf(effectiveMinTarget, userBaseTarget - ROUTINE_MAX_PULLDOWN_MMOL)
        }
        val effectiveDeadband = (M_DEAD - FAST_RISE_DEADBAND_REDUCTION * riseUrgency)
            .coerceAtLeast(M_DEAD_MIN)

        var iNew: Double
        val tRaw: Double
        val previousIntegral = input.previousI.coerceIn(-I_MAX, I_MAX)
        val integralDirectionReset = abs(e) >= effectiveDeadband &&
            previousIntegral != 0.0 &&
            previousIntegral * e < 0.0
        val integralBase = if (integralDirectionReset) 0.0 else previousIntegral

        if (abs(e) < effectiveDeadband) {
            tRaw = tb
            iNew = integralBase * INTEGRAL_DEADBAND_DECAY
        } else {
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
            val integralLimit = minOf(I_MAX, MAX_INTEGRAL_TARGET_CONTRIBUTION_MMOL / kiApplied)
            val iRaw = integralBase + e * CYCLE_MIN
            iNew = iRaw.coerceIn(-integralLimit, integralLimit)

            var deltaT = -kpApplied * e - kiApplied * iNew
            val negativeDeltaLimit = if (aggressiveRiseEligible) {
                maxOf(DELTA_T_MAX, userBaseTarget - effectiveMinTarget)
            } else {
                DELTA_T_MAX
            }
            deltaT = deltaT.coerceIn(-negativeDeltaLimit, DELTA_T_MAX)

            val unclampedTarget = tb + deltaT
            val clampedTarget = unclampedTarget.coerceIn(controlMinTarget, tMax)

            if (clampedTarget == controlMinTarget && deltaT < 0.0) {
                iNew = integralBase.coerceIn(-integralLimit, integralLimit)
            }
            if (clampedTarget == tMax && deltaT > 0.0) {
                iNew = integralBase.coerceIn(-integralLimit, integralLimit)
            }

            tRaw = clampedTarget
        }

        val guard = computeHighGlucoseGuard(
            userBaseTarget = userBaseTarget,
            pCtrlRaw = pCtrlRaw,
            pMin = pMin,
            tMin = controlMinTarget,
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
        val target = applyRateLimit(relaxedTarget, input.previousTempTarget).coerceIn(controlMinTarget, tMax)
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
            "farTermLowConfirmed" to if (farTermLowConfirmed) 1.0 else 0.0,
            "rapidFallFarTermLowCandidate" to if (rapidFallFarTermLowCandidate) 1.0 else 0.0,
            "rapidFallFarTermLowConfirmed" to if (rapidFallFarTermLowConfirmed) 1.0 else 0.0,
            "rapidFallPriorConfirmedCycles" to input.rapidFallPriorConfirmedCycles.toDouble(),
            "iobForecastReversalCandidate" to if (iobForecastReversalCandidate) 1.0 else 0.0,
            "forecastDrop5To30" to forecastDrop5To30,
            "forecastDrop30To60" to forecastDrop30To60,
            "forecastDrop5To60" to forecastDrop5To60,
            "effectiveCiLow60" to effectiveCiLow60,
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
            "aggressiveRiseEligible" to if (aggressiveRiseEligible) 1.0 else 0.0,
            "controlMinTarget" to controlMinTarget,
            "integralDirectionReset" to if (integralDirectionReset) 1.0 else 0.0,
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
        private const val MAX_INTEGRAL_TARGET_CONTRIBUTION_MMOL = 0.30
        private const val INTEGRAL_DEADBAND_DECAY = 0.80
        private const val ROUTINE_MAX_PULLDOWN_MMOL = 1.0
        private const val AGGRESSIVE_RISE_DELTA5_MMOL = 0.30
        private const val AGGRESSIVE_RISE_PRED5_LEAD_MMOL = 0.30
        private const val AGGRESSIVE_RISE_PRED30_MARGIN_MMOL = 1.0
        private const val AGGRESSIVE_RISE_CI30_FLOOR_MMOL = 5.6
        private const val AGGRESSIVE_RISE_CI30_BASE_MARGIN_MMOL = 0.30
        private const val AGGRESSIVE_RISE_MAX_IOB_U = 1.5
        private const val FAR_TERM_CONFIRM_CI30_BELOW_MMOL = 4.4001
        private const val FAR_TERM_CONFIRM_PRED30_MARGIN_MMOL = 0.50
        private const val FAR_TERM_CONFIRM_FALL_MMOL5 = 0.15
        private const val RAPID_FALL_OBSERVED_DELTA5_MMOL = 0.30
        private const val RAPID_FALL_MIN_DROP_5_TO_30_MMOL = 0.35
        private const val RAPID_FALL_MIN_DROP_30_TO_60_MMOL = 0.20
        private const val RAPID_FALL_MIN_DROP_5_TO_60_MMOL = 0.75
        private const val RAPID_FALL_CI30_CONFIRM_BELOW_MMOL = 4.80
        private const val RAPID_FALL_PRED60_BASE_MARGIN_MMOL = 0.60
        private const val RAPID_FALL_REQUIRED_PRIOR_CYCLES = 1

        private const val IOB_RELIEF_THRESHOLD_U = 1.5
        private const val IOB_RELIEF_GAIN = 0.20
        private const val IOB_RELIEF_MAX_MMOL = 0.60
        private const val IOB_REVERSAL_MIN_IOB_U = 1.5
        private const val IOB_REVERSAL_MAX_CURRENT_ABOVE_BASE_MMOL = 2.5
        private const val IOB_REVERSAL_MAX_NEAR_TERM_RISE_MMOL = 0.10
        private const val IOB_REVERSAL_MIN_DROP_30_TO_60_MMOL = 0.60
        private const val IOB_REVERSAL_MIN_DROP_5_TO_60_MMOL = 0.90
        private const val IOB_REVERSAL_MIN_CURRENT_TO_60_DROP_MMOL = 0.75
        private const val IOB_REVERSAL_CI60_BELOW_BASE_MMOL = 1.5

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
        private const val LOW_GLUCOSE_RISK_THRESHOLD_MMOL = 4.0
        private const val EPS_TARGET = 1e-6
        private const val LOW_BOUND_GUARD_THRESHOLD_MMOL = 4.0
        private const val LOW_BOUND_GUARD_TARGET_MMOL = 5.0
        private const val PREEMPTIVE_WATCH_BELOW = 4.0
        private const val PREEMPTIVE_GUARD_BELOW = 3.8
        private const val PREEMPTIVE_FORCE_HIGH_BELOW = 3.6
        private const val PREEMPTIVE_WATCH_MIN_TARGET_MMOL = 5.2
        private const val PREEMPTIVE_GUARD_MIN_TARGET_MMOL = 5.8
        private const val PREEMPTIVE_FORCE_MIN_TARGET_MMOL = 6.4
        private const val PREEMPTIVE_WATCH_BASE_RAISE_MMOL = 0.35
        private const val PREEMPTIVE_GUARD_BASE_RAISE_MMOL = 0.70
        private const val PREEMPTIVE_FORCE_BASE_RAISE_MMOL = 1.00
        private const val PREEMPTIVE_GUARD_SEVERITY_GAIN_MMOL = 0.45
        private const val PREEMPTIVE_GUARD_TREND_GAIN_MMOL = 0.35
        private const val PREEMPTIVE_FORCE_SEVERITY_GAIN_MMOL = 0.55
        private const val PREEMPTIVE_FORCE_TREND_GAIN_MMOL = 0.45
        private const val PREEMPTIVE_FALL_THRESHOLD_MMOL5 = 0.10
        private const val PREEMPTIVE_FALL_FULL_SCALE_MMOL5 = 0.25
        private const val PREEMPTIVE_PRED_DROP_THRESHOLD_MMOL = 0.20
        private const val PREEMPTIVE_PRED_DROP_FULL_SCALE_MMOL = 0.50
        private const val PREEMPTIVE_HIGH_TRAJECTORY_MARGIN_30_MMOL = 0.75
        private const val PREEMPTIVE_HIGH_TRAJECTORY_MARGIN_60_MMOL = 0.55
        private const val PREEMPTIVE_GUARD_FULL_SCALE_MMOL = 0.40
        private const val PREEMPTIVE_WATCH_FULL_SCALE_MMOL = 0.50
        private const val PREEMPTIVE_MEAN_SUPPORT_MARGIN_30_MMOL = 0.50
        private const val PREEMPTIVE_MEAN_SUPPORT_MARGIN_60_MMOL = 0.35
        private const val PREEMPTIVE_FORCE_MEAN_SUPPORT_MARGIN_30_MMOL = 0.35
        private const val PREEMPTIVE_FORCE_MEAN_SUPPORT_MARGIN_60_MMOL = 0.25
    }
}
