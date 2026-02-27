package io.aaps.copilot.domain.rules

data class RuleRuntimeConfig(
    val enabledRuleIds: Set<String>,
    val priorities: Map<String, Int>
) {
    companion object {
        fun allEnabled(ruleIds: Collection<String>): RuleRuntimeConfig = RuleRuntimeConfig(
            enabledRuleIds = ruleIds.toSet(),
            priorities = ruleIds.associateWith { 0 }
        )
    }
}
