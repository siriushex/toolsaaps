package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.rules.LowGlucoseTargetSafetyLatch
import org.junit.Test

class AutomationRepositoryLowGlucoseTargetSafetyTest {

    @Test
    fun oldLatchedTenRespectsNewConfiguredEightWithoutClearingRisk() {
        val state = LowGlucoseTargetSafetyLatch.State(true, true, 0, 10.0)
        val action = ActionProposal("temp_target", 7.55, 30, "safety_hypo_guard")

        val result = AutomationRepository.protectLowGlucoseTargetStatic(
            action, state, minTargetMmol = 4.0, maxTargetMmol = 8.0
        )

        assertThat(result.targetMmol).isEqualTo(8.0)
        assertThat(result.reason).contains("low_glucose_safety_latch")
        assertThat(result.durationMinutes).isEqualTo(30)
        assertThat(state).isEqualTo(LowGlucoseTargetSafetyLatch.State(true, true, 0, 10.0))
    }

    @Test
    fun finalProposalRespectsTighterBoundsEvenWithoutLatch() {
        val state = LowGlucoseTargetSafetyLatch.State(false, false, 0, null)
        for (target in listOf(4.2, 7.0, 9.0)) {
            val result = AutomationRepository.protectLowGlucoseTargetStatic(
                ActionProposal("temp_target", target, 30, "adaptive"), state,
                minTargetMmol = 5.0, maxTargetMmol = 7.0
            )
            assertThat(result.targetMmol).isEqualTo(target.coerceIn(5.0, 7.0))
        }
    }

    @Test
    fun malformedProposalIsNotConvertedIntoValidTarget() {
        val state = LowGlucoseTargetSafetyLatch.State(true, true, 0, 10.0)
        for (target in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val result = AutomationRepository.protectLowGlucoseTargetStatic(
                ActionProposal("temp_target", target, 30, "adaptive"), state,
                minTargetMmol = 4.0, maxTargetMmol = 8.0
            )
            assertThat(result.targetMmol.isFinite()).isFalse()
        }
    }

    @Test
    fun safetyReasonsAreRecognizedButKeepaliveIsNot() {
        assertThat(AutomationRepository.isLowGlucoseSafetyReasonStatic("safety_force_high")).isTrue()
        assertThat(AutomationRepository.isLowGlucoseSafetyReasonStatic("hypo_preemptive_guard")).isTrue()
        assertThat(AutomationRepository.isLowGlucoseSafetyReasonStatic("adaptive_keepalive_5m|low_glucose_safety_latch")).isTrue()
        assertThat(AutomationRepository.isLowGlucoseSafetyReasonStatic("adaptive_keepalive_5m")).isFalse()
    }

    @Test
    fun forecastMinimumIncludesConfidenceLowAndCurrentGlucose() {
        val minimum = AutomationRepository.resolveLowGlucoseForecastMinimumStatic(
            currentGlucoseMmol = 4.3,
            forecasts = listOf(
                Forecast(1L, 30, 4.4, 3.7, 5.1, "test"),
                Forecast(1L, 60, 4.1, 3.9, 5.0, "test")
            )
        )

        assertThat(minimum).isEqualTo(3.7)
    }

    @Test
    fun targetProtectionAddsAuditReasonOnlyWhenItRaisesTarget() {
        val latch = LowGlucoseTargetSafetyLatch()
        val state = latch.update(
            currentGlucoseMmol = 3.9,
            forecastMinimumMmol = 3.7,
            activeSafetyTargetMmol = 6.6,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = true
        )
        val lowProposal = ActionProposal("temp_target", 4.2, 30, "adaptive_keepalive_5m")
        val highProposal = ActionProposal("temp_target", 10.0, 30, "safety_force_high")

        val protectedLow = AutomationRepository.protectLowGlucoseTargetStatic(lowProposal, state)
        val protectedHigh = AutomationRepository.protectLowGlucoseTargetStatic(highProposal, state)

        assertThat(protectedLow.targetMmol).isEqualTo(6.6)
        assertThat(protectedLow.reason).contains("low_glucose_safety_latch")
        assertThat(protectedHigh).isEqualTo(highProposal)
    }
}
