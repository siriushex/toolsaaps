package io.aaps.copilot.domain.target

import kotlin.math.abs

class TargetCadencePolicy {

    fun decide(request: TargetCadenceRequest): TargetCadenceDecision {
        if (request.manual) {
            return allow(TempTargetCadenceOutcome.ALLOW_MANUAL, "manual_bypass")
        }
        if (!request.targetMmol.isFinite()) {
            return block("invalid_cadence_target")
        }

        val last = request.lastAutomaticSent
            ?: return allow(TempTargetCadenceOutcome.ALLOW_NO_PREVIOUS, "no_previous_send")
        if (!last.targetMmol.isFinite()) {
            return block("invalid_cadence_target")
        }

        val elapsedMs = try {
            Math.subtractExact(request.nowTs, last.timestamp)
        } catch (_: ArithmeticException) {
            return block("invalid_cadence_chronology")
        }
        if (elapsedMs < 0L) {
            return block("invalid_cadence_chronology")
        }
        if (elapsedMs >= REPEAT_WINDOW_MS) {
            return allow(TempTargetCadenceOutcome.ALLOW_WINDOW_ELAPSED, "window_elapsed")
        }

        val deltaMmol = request.targetMmol - last.targetMmol
        if (
            request.intent == TargetIntent.HYPO_PROTECTION &&
            deltaMmol >= URGENT_HYPO_RAISE_MMOL - EPSILON
        ) {
            return allow(TempTargetCadenceOutcome.ALLOW_URGENT_HYPO_RAISE, "urgent_hypo_target_changed")
        }

        if (abs(deltaMmol) >= MATERIAL_CHANGE_MMOL - EPSILON) {
            return allow(TempTargetCadenceOutcome.ALLOW_MATERIAL_CHANGE, "target_changed")
        }

        return block("duplicate_target_within_window")
    }

    private fun block(reason: String) = TargetCadenceDecision(
        allowed = false,
        outcome = TempTargetCadenceOutcome.BLOCK_DUPLICATE_WITHIN_WINDOW,
        reason = reason
    )

    private fun allow(
        outcome: TempTargetCadenceOutcome,
        reason: String
    ) = TargetCadenceDecision(allowed = true, outcome = outcome, reason = reason)

    companion object {
        const val REPEAT_WINDOW_MS = 30 * 60_000L
        const val MATERIAL_CHANGE_MMOL = 0.15
        const val URGENT_HYPO_RAISE_MMOL = 0.05

        private const val EPSILON = 1e-9
    }
}
