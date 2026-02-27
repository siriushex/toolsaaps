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
        val hypo = sorted.lastOrNull { it.valueMmol <= context.postHypoThresholdMmol }
            ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("no_hypo"), null)
        if (context.nowTs - hypo.ts > context.postHypoLookbackMinutes * 60 * 1000L) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("hypo_outside_window"), null)
        }

        val afterHypo = sorted.filter { it.ts > hypo.ts }.takeLast(3)
        if (afterHypo.size < 3) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("insufficient_points"), null)
        }

        val d1 = afterHypo[1].valueMmol - afterHypo[0].valueMmol
        val d2 = afterHypo[2].valueMmol - afterHypo[1].valueMmol
        val rebound = d1 > context.postHypoDeltaThresholdMmol5m && d2 > context.postHypoDeltaThresholdMmol5m

        if (!rebound) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("no_rebound"), null)
        }

        val targetLower = context.postHypoTargetMmol - 0.1
        val targetUpper = context.postHypoTargetMmol + 0.1
        if (context.activeTempTargetMmol != null && context.activeTempTargetMmol in targetLower..targetUpper) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("already_in_target_band"), null)
        }

        val action = ActionProposal(
            type = "temp_target",
            targetMmol = context.postHypoTargetMmol,
            durationMinutes = context.postHypoDurationMinutes,
            reason = "post_hypo_rebound"
        )
        return RuleDecision(
            id,
            RuleState.TRIGGERED,
            listOf(
                "hypo_plus_rising_trend",
                "hypoThreshold=${context.postHypoThresholdMmol}",
                "deltaThreshold=${context.postHypoDeltaThresholdMmol5m}",
                "target=${context.postHypoTargetMmol}",
                "duration=${context.postHypoDurationMinutes}"
            ),
            action
        )
    }
}
