package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState

class PostHypoReboundGuardRule : TargetRule {

    override val id: String = "PostHypoReboundGuard.v1"

    override fun evaluate(context: RuleContext): RuleDecision {
        if (!context.dataFresh) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("stale_data"), null)
        }
        if (context.sensorBlocked) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("sensor_blocked"), null)
        }

        val sorted = context.glucose.sortedBy { it.ts }
        val hypo = sorted.lastOrNull { it.valueMmol <= 3.0 } ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("no_hypo"), null)
        if (context.nowTs - hypo.ts > 90 * 60 * 1000L) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("hypo_outside_window"), null)
        }

        val afterHypo = sorted.filter { it.ts > hypo.ts }.takeLast(3)
        if (afterHypo.size < 3) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("insufficient_points"), null)
        }

        val d1 = afterHypo[1].valueMmol - afterHypo[0].valueMmol
        val d2 = afterHypo[2].valueMmol - afterHypo[1].valueMmol
        val rebound = d1 > 0.2 && d2 > 0.2

        if (!rebound) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("no_rebound"), null)
        }

        if (context.activeTempTargetMmol != null && context.activeTempTargetMmol in 4.3..4.7) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("already_in_target_band"), null)
        }

        val action = ActionProposal(
            type = "temp_target",
            targetMmol = 4.4,
            durationMinutes = 60,
            reason = "post_hypo_rebound"
        )
        return RuleDecision(id, RuleState.TRIGGERED, listOf("hypo_plus_rising_trend"), action)
    }
}
