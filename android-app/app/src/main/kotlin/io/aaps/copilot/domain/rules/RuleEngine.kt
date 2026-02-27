package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.domain.safety.SafetyPolicyConfig

class RuleEngine(
    private val rules: List<TargetRule>,
    private val safetyPolicy: SafetyPolicy
) {

    fun evaluate(
        context: RuleContext,
        config: SafetyPolicyConfig,
        runtimeConfig: RuleRuntimeConfig = RuleRuntimeConfig.allEnabled(rules.map { it.id })
    ): List<RuleDecision> {
        val evaluated = rules
            .sortedByDescending { runtimeConfig.priorities[it.id] ?: 0 }
            .map { rule ->
            if (!runtimeConfig.enabledRuleIds.contains(rule.id)) {
                return@map RuleDecision(
                    ruleId = rule.id,
                    state = RuleState.NO_MATCH,
                    reasons = listOf("rule_disabled"),
                    actionProposal = null
                )
            }
            val decision = rule.evaluate(context)
            if (decision.state != RuleState.TRIGGERED || decision.actionProposal == null) {
                decision
            } else {
                val safety = safetyPolicy.evaluate(
                    proposal = decision.actionProposal,
                    config = config,
                    dataFresh = context.dataFresh,
                    actionsLast6h = context.actionsLast6h
                )

                if (safety.allowed) {
                    decision
                } else {
                    decision.copy(
                        state = RuleState.BLOCKED,
                        reasons = decision.reasons + safety.reasons,
                        actionProposal = null
                    )
                }
            }
        }

        // Safety arbitration: keep only the highest-priority triggered action per cycle.
        var winnerRuleId: String? = null
        return evaluated.map { decision ->
            if (decision.state != RuleState.TRIGGERED || decision.actionProposal == null) {
                decision
            } else if (winnerRuleId == null) {
                winnerRuleId = decision.ruleId
                decision
            } else {
                decision.copy(
                    state = RuleState.BLOCKED,
                    reasons = decision.reasons + "skipped_due_to_higher_priority:${winnerRuleId}",
                    actionProposal = null
                )
            }
        }
    }
}
