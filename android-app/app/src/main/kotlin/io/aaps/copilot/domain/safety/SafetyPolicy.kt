package io.aaps.copilot.domain.safety

import io.aaps.copilot.domain.model.ActionProposal

data class SafetyPolicyConfig(
    val killSwitch: Boolean,
    val minTargetMmol: Double = 4.0,
    val maxTargetMmol: Double = 10.0,
    val maxActionsIn6Hours: Int = 3
)

data class SafetyDecision(
    val allowed: Boolean,
    val reasons: List<String>
)

class SafetyPolicy {

    fun evaluate(
        proposal: ActionProposal,
        config: SafetyPolicyConfig,
        dataFresh: Boolean,
        actionsLast6h: Int
    ): SafetyDecision {
        val reasons = mutableListOf<String>()
        if (config.killSwitch) reasons += "kill_switch"
        if (!dataFresh) reasons += "stale_data"
        if (!proposal.type.equals("temp_target", ignoreCase = true) &&
            actionsLast6h >= config.maxActionsIn6Hours
        ) {
            reasons += "rate_limit_6h"
        }
        if (proposal.targetMmol !in config.minTargetMmol..config.maxTargetMmol) reasons += "target_out_of_bounds"
        if (proposal.durationMinutes !in 15..120) reasons += "duration_out_of_bounds"

        return SafetyDecision(
            allowed = reasons.isEmpty(),
            reasons = reasons
        )
    }
}
