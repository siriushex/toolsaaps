package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.RuleDecision

interface TargetRule {
    val id: String
    fun evaluate(context: RuleContext): RuleDecision
}
