package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import kotlin.math.abs

class PatternAdaptiveTargetRule : TargetRule {

    override val id: String = "PatternAdaptiveTarget.v1"

    override fun evaluate(context: RuleContext): RuleDecision {
        if (!context.dataFresh) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("stale_data"), null)
        }

        val pattern = context.currentDayPattern ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("no_pattern_window"), null)
        if (!pattern.isRiskWindow) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("no_risk_window"), null)
        }
        if (abs(pattern.recommendedTargetMmol - context.baseTargetMmol) < 0.15) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("close_to_base_target"), null)
        }

        if (context.activeTempTargetMmol != null && abs(context.activeTempTargetMmol - pattern.recommendedTargetMmol) < 0.15) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("same_temp_target_active"), null)
        }

        val reason = when {
            pattern.lowRate > pattern.highRate -> "pattern_frequent_lows"
            pattern.highRate > pattern.lowRate -> "pattern_frequent_highs"
            else -> "pattern_balanced"
        }

        return RuleDecision(
            ruleId = id,
            state = RuleState.TRIGGERED,
            reasons = listOf(
                reason,
                "day=${pattern.dayType}",
                "hour=${pattern.hour}",
                "samples=${pattern.sampleCount}",
                "activeDays=${pattern.activeDays}"
            ),
            actionProposal = ActionProposal(
                type = "temp_target",
                targetMmol = pattern.recommendedTargetMmol,
                durationMinutes = 60,
                reason = reason
            )
        )
    }
}
