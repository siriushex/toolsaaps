package io.aaps.copilot.domain.rules

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.domain.safety.SafetyPolicyConfig
import org.junit.Test

class RuleEngineRuntimeConfigTest {

    private class FixedRule(
        override val id: String,
        private val state: RuleState
    ) : TargetRule {
        override fun evaluate(context: RuleContext): RuleDecision {
            val action = if (state == RuleState.TRIGGERED) {
                ActionProposal(type = "temp_target", targetMmol = 5.0, durationMinutes = 30, reason = "test")
            } else {
                null
            }
            return RuleDecision(id, state, reasons = listOf("ok"), actionProposal = action)
        }
    }

    @Test
    fun respectsEnabledRulesAndPriorityOrder() {
        val ruleA = FixedRule("A", RuleState.NO_MATCH)
        val ruleB = FixedRule("B", RuleState.NO_MATCH)
        val engine = RuleEngine(listOf(ruleA, ruleB), SafetyPolicy())

        val runtime = RuleRuntimeConfig(
            enabledRuleIds = setOf("A"),
            priorities = mapOf("A" to 10, "B" to 99)
        )

        val result = engine.evaluate(
            context = RuleContext(
                nowTs = 0,
                glucose = emptyList(),
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = null,
                baseTargetMmol = 5.5,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = false
            ),
            config = SafetyPolicyConfig(killSwitch = false),
            runtimeConfig = runtime
        )

        assertThat(result).hasSize(2)
        assertThat(result[0].ruleId).isEqualTo("B")
        assertThat(result[0].reasons).contains("rule_disabled")
        assertThat(result[1].ruleId).isEqualTo("A")
    }
}
