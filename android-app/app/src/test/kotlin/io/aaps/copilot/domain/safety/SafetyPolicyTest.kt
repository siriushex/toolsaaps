package io.aaps.copilot.domain.safety

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.ActionProposal
import org.junit.Test

class SafetyPolicyTest {

    private val policy = SafetyPolicy()

    @Test
    fun blocks_whenKillSwitchEnabled() {
        val decision = policy.evaluate(
            proposal = proposal(target = 5.5, duration = 30),
            config = SafetyPolicyConfig(killSwitch = true),
            dataFresh = true,
            actionsLast6h = 0
        )

        assertThat(decision.allowed).isFalse()
        assertThat(decision.reasons).contains("kill_switch")
    }

    @Test
    fun blocks_whenStaleOrRateLimited() {
        val decision = policy.evaluate(
            proposal = proposal(target = 5.5, duration = 30),
            config = SafetyPolicyConfig(killSwitch = false, maxActionsIn6Hours = 3),
            dataFresh = false,
            actionsLast6h = 3
        )

        assertThat(decision.allowed).isFalse()
        assertThat(decision.reasons).contains("stale_data")
        assertThat(decision.reasons).contains("rate_limit_6h")
    }

    @Test
    fun blocks_whenTargetOrDurationOutOfBounds() {
        val decision = policy.evaluate(
            proposal = proposal(target = 3.2, duration = 150),
            config = SafetyPolicyConfig(killSwitch = false),
            dataFresh = true,
            actionsLast6h = 0
        )

        assertThat(decision.allowed).isFalse()
        assertThat(decision.reasons).contains("target_out_of_bounds")
        assertThat(decision.reasons).contains("duration_out_of_bounds")
    }

    @Test
    fun allows_whenAllChecksPass() {
        val decision = policy.evaluate(
            proposal = proposal(target = 5.6, duration = 60),
            config = SafetyPolicyConfig(killSwitch = false),
            dataFresh = true,
            actionsLast6h = 1
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.reasons).isEmpty()
    }

    private fun proposal(target: Double, duration: Int): ActionProposal {
        return ActionProposal(
            type = "temp_target",
            targetMmol = target,
            durationMinutes = duration,
            reason = "test"
        )
    }
}
