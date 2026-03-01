package io.aaps.copilot.domain.rules

import kotlin.math.abs

class AdaptiveTempTargetController {

    data class Input(
        val nowTs: Long,
        val baseTarget: Double,
        val currentGlucoseMmol: Double? = null,
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
        val userBaseTarget = input.baseTarget.coerceIn(TMIN, TMAX)
        val currentGlucose = input.currentGlucoseMmol?.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val cob = (input.cobGrams ?: 0.0).coerceIn(0.0, 400.0)
        val iob = (input.iobUnits ?: 0.0).coerceIn(0.0, 30.0)
        val cobForcedBase = if (cob >= COB_SIGNIFICANT_GRAMS) COB_FORCED_BASE_TARGET_MMOL else userBaseTarget
        val iobRelief = ((iob - IOB_RELIEF_THRESHOLD_U).coerceAtLeast(0.0) * IOB_RELIEF_GAIN)
            .coerceIn(0.0, IOB_RELIEF_MAX_MMOL)
        val tb = (cobForcedBase + iobRelief).coerceIn(TMIN, TMAX)

        val w5CI = ((input.ciHigh5 - input.ciLow5) / 2.0).coerceAtLeast(1e-6)
        val w30CI = ((input.ciHigh30 - input.ciLow30) / 2.0).coerceAtLeast(1e-6)
        val w60CI = ((input.ciHigh60 - input.ciLow60) / 2.0).coerceAtLeast(1e-6)

        val (w5Base, w30Base, w60Base) = if (input.uamActive) {
            Triple(0.10, 0.30, 0.60)
        } else {
            Triple(0.15, 0.35, 0.50)
        }

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
            val target = applyRateLimit(TMAX, input.previousTempTarget).coerceIn(TMIN, TMAX)
            return Output(
                newTempTarget = target,
                durationMin = DURATION_MIN,
                updatedI = input.previousI,
                reason = "safety_force_high",
                debugFields = mapOf(
                    "Tb" to tb,
                    "TbUser" to userBaseTarget,
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
            val raw = (tb + K_HYPO * (tb - pCtrlLow)).coerceIn(tb, TMAX)
            val target = applyRateLimit(raw, input.previousTempTarget).coerceIn(TMIN, TMAX)
            return Output(
                newTempTarget = target,
                durationMin = DURATION_MIN,
                updatedI = input.previousI,
                reason = "safety_hypo_guard",
                debugFields = mapOf(
                    "Tb" to tb,
                    "TbUser" to userBaseTarget,
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
        val cobBias = (cob * COB_CONTROL_GAIN_PER_GRAM).coerceIn(0.0, COB_CONTROL_BIAS_MAX_MMOL)
        val iobBias = (iob * IOB_CONTROL_GAIN_PER_UNIT).coerceIn(0.0, IOB_CONTROL_BIAS_MAX_MMOL)
        val pCtrl = (pCtrlRaw + cobBias - iobBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val e = pCtrl - tb

        var iNew: Double
        val tRaw: Double

        if (abs(e) < M_DEAD) {
            tRaw = tb
            iNew = input.previousI * 0.8
        } else {
            val iRaw = input.previousI + e * CYCLE_MIN
            iNew = iRaw.coerceIn(-I_MAX, I_MAX)

            var deltaT = -KP * e - KI * iNew
            deltaT = deltaT.coerceIn(-DELTA_T_MAX, DELTA_T_MAX)

            val unclampedTarget = tb + deltaT
            val clampedTarget = unclampedTarget.coerceIn(TMIN, TMAX)

            if (clampedTarget == TMIN && deltaT < 0.0) iNew = input.previousI
            if (clampedTarget == TMAX && deltaT > 0.0) iNew = input.previousI

            tRaw = clampedTarget
        }

        val guard = computeHighGlucoseGuard(
            userBaseTarget = userBaseTarget,
            pCtrlRaw = pCtrlRaw,
            pMin = pMin
        )
        val guardedRawTarget = if (guard.active) {
            tRaw.coerceAtMost(guard.maxTarget)
        } else {
            tRaw
        }
        val target = applyRateLimit(guardedRawTarget, input.previousTempTarget).coerceIn(TMIN, TMAX)
        val debugFields = mutableMapOf(
            "Tb" to tb,
            "TbUser" to userBaseTarget,
            "currentGlucose" to (currentGlucose ?: Double.NaN),
            "cobGrams" to cob,
            "iobUnits" to iob,
            "nearTermLow" to nearTermLow,
            "severeNearTermLow" to if (severeNearTermLow) 1.0 else 0.0,
            "Pctrl" to pCtrl,
            "PctrlRaw" to pCtrlRaw,
            "cobBias" to cobBias,
            "iobBias" to iobBias,
            "error" to e,
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
        pMin: Double
    ): HighGlucoseGuard {
        val noHypoRisk = pMin >= userBaseTarget - HIGH_GUARD_HYPO_RISK_MARGIN_MMOL
        if (!noHypoRisk) return HighGlucoseGuard(active = false, maxTarget = TMAX)

        return when {
            pCtrlRaw >= userBaseTarget + VERY_HIGH_GLUCOSE_MARGIN_MMOL -> {
                val forced = (userBaseTarget - VERY_HIGH_GLUCOSE_PULLDOWN_MMOL).coerceIn(TMIN, TMAX)
                HighGlucoseGuard(active = true, maxTarget = forced)
            }

            pCtrlRaw >= userBaseTarget + HIGH_GLUCOSE_MARGIN_MMOL -> {
                HighGlucoseGuard(active = true, maxTarget = userBaseTarget.coerceIn(TMIN, TMAX))
            }

            else -> HighGlucoseGuard(active = false, maxTarget = TMAX)
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

    companion object {
        const val TMIN = 4.0
        const val TMAX = 9.0
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

    }
}
