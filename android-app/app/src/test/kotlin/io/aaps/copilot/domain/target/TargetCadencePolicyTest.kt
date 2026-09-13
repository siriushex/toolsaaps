package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TargetCadencePolicyTest {

    private val policy = TargetCadencePolicy()

    @Test
    fun exactCadenceConstantsRemainFrozen() {
        assertThat(TargetCadencePolicy.REPEAT_WINDOW_MS).isEqualTo(30 * MINUTE_MS)
        assertThat(TargetCadencePolicy.MATERIAL_CHANGE_MMOL).isEqualTo(0.15)
        assertThat(TargetCadencePolicy.URGENT_HYPO_RAISE_MMOL).isEqualTo(0.05)
    }

    @Test
    fun firstAutomaticTargetIsAllowed() {
        val decision = policy.decide(request(target = 5.5, last = null))

        assertThat(decision.allowed).isTrue()
        assertThat(decision.outcome).isEqualTo(TempTargetCadenceOutcome.ALLOW_NO_PREVIOUS)
    }

    @Test
    fun materialTargetChangeIsAllowedInsideThirtyMinutes() {
        val upward = policy.decide(request(target = 5.65, lastTarget = 5.5, elapsedMinutes = 5))
        val downward = policy.decide(request(target = 5.35, lastTarget = 5.5, elapsedMinutes = 5))

        assertThat(upward.outcome).isEqualTo(TempTargetCadenceOutcome.ALLOW_MATERIAL_CHANGE)
        assertThat(downward.outcome).isEqualTo(TempTargetCadenceOutcome.ALLOW_MATERIAL_CHANGE)
    }

    @Test
    fun urgentHypoRaiseIsAllowedInsideThirtyMinutes() {
        val decision = policy.decide(
            request(
                target = 5.55,
                lastTarget = 5.5,
                elapsedMinutes = 5,
                intent = TargetIntent.HYPO_PROTECTION
            )
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.outcome).isEqualTo(TempTargetCadenceOutcome.ALLOW_URGENT_HYPO_RAISE)
    }

    @Test
    fun onlyHypoProtectionUsesUrgentRaiseBypass() {
        val nonUrgentIntents = listOf(
            TargetIntent.SENSOR_SAFETY_RELEASE,
            TargetIntent.ACTIVITY_PROTECTION,
            TargetIntent.POST_HYPO_PROTECTION,
            TargetIntent.RECOVERY_TO_BASE,
            TargetIntent.NORMAL_CONTROL
        )

        nonUrgentIntents.forEach { intent ->
            val decision = policy.decide(
                request(
                    target = 5.55,
                    lastTarget = 5.5,
                    elapsedMinutes = 5,
                    intent = intent
                )
            )

            assertThat(decision.allowed).isFalse()
            assertThat(decision.outcome)
                .isEqualTo(TempTargetCadenceOutcome.BLOCK_DUPLICATE_WITHIN_WINDOW)
        }
    }

    @Test
    fun exactDuplicateIsBlockedInsideThirtyMinutes() {
        val decision = policy.decide(request(target = 5.5, lastTarget = 5.5, elapsedMinutes = 10))

        assertThat(decision.allowed).isFalse()
        assertThat(decision.outcome).isEqualTo(TempTargetCadenceOutcome.BLOCK_DUPLICATE_WITHIN_WINDOW)
    }

    @Test
    fun smallNormalChangeIsBlockedInsideThirtyMinutes() {
        val decision = policy.decide(request(target = 5.64, lastTarget = 5.5, elapsedMinutes = 10))

        assertThat(decision.allowed).isFalse()
        assertThat(decision.outcome).isEqualTo(TempTargetCadenceOutcome.BLOCK_DUPLICATE_WITHIN_WINDOW)
    }

    @Test
    fun sameTargetIsAllowedAfterThirtyMinutes() {
        val decision = policy.decide(request(target = 5.5, lastTarget = 5.5, elapsedMinutes = 30))

        assertThat(decision.allowed).isTrue()
        assertThat(decision.outcome).isEqualTo(TempTargetCadenceOutcome.ALLOW_WINDOW_ELAPSED)
    }

    @Test
    fun manualTargetBypassesAutomaticThrottle() {
        val decision = policy.decide(
            request(target = 5.5, lastTarget = 5.5, elapsedMinutes = 1, manual = true)
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.outcome).isEqualTo(TempTargetCadenceOutcome.ALLOW_MANUAL)
    }

    @Test
    fun automaticChronologyOverflowFailsClosed() {
        val decision = policy.decide(
            TargetCadenceRequest(
                nowTs = Long.MIN_VALUE,
                targetMmol = 5.5,
                intent = TargetIntent.NORMAL_CONTROL,
                manual = false,
                lastAutomaticSent = LastSentTempTarget(
                    timestamp = 1,
                    targetMmol = 5.5,
                    idempotencyKey = "previous"
                )
            )
        )

        assertInvalidAutomaticCadence(decision, "invalid_cadence_chronology")
    }

    @Test
    fun futureAutomaticTimestampFailsClosed() {
        val decision = policy.decide(
            TargetCadenceRequest(
                nowTs = NOW,
                targetMmol = 5.5,
                intent = TargetIntent.NORMAL_CONTROL,
                manual = false,
                lastAutomaticSent = LastSentTempTarget(
                    timestamp = NOW + 1,
                    targetMmol = 5.5,
                    idempotencyKey = "future"
                )
            )
        )

        assertInvalidAutomaticCadence(decision, "invalid_cadence_chronology")
    }

    @Test
    fun nonFiniteAutomaticTargetsFailClosed() {
        val nonFiniteValues = listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        )

        nonFiniteValues.forEach { target ->
            assertInvalidAutomaticCadence(
                policy.decide(request(target = target, last = null)),
                "invalid_cadence_target"
            )
        }
        nonFiniteValues.forEach { lastTarget ->
            assertInvalidAutomaticCadence(
                policy.decide(request(target = 5.5, lastTarget = lastTarget)),
                "invalid_cadence_target"
            )
        }
    }

    @Test
    fun manualBypassRemainsUnchangedForInvalidAutomaticContext() {
        val decision = policy.decide(
            TargetCadenceRequest(
                nowTs = Long.MIN_VALUE,
                targetMmol = Double.NaN,
                intent = TargetIntent.NORMAL_CONTROL,
                manual = true,
                lastAutomaticSent = LastSentTempTarget(
                    timestamp = 1,
                    targetMmol = Double.POSITIVE_INFINITY,
                    idempotencyKey = "manual"
                )
            )
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.outcome).isEqualTo(TempTargetCadenceOutcome.ALLOW_MANUAL)
        assertThat(decision.reason).isEqualTo("manual_bypass")
    }

    @Test
    fun downwardHypoIntentDoesNotUseUrgentBypass() {
        val decision = policy.decide(
            request(
                target = 5.45,
                lastTarget = 5.5,
                elapsedMinutes = 5,
                intent = TargetIntent.HYPO_PROTECTION
            )
        )

        assertThat(decision.allowed).isFalse()
        assertThat(decision.outcome).isEqualTo(TempTargetCadenceOutcome.BLOCK_DUPLICATE_WITHIN_WINDOW)
    }

    @Test
    fun activityProtectionBelowMaterialDeltaDoesNotUseHypoBypass() {
        val decision = policy.decide(
            request(
                target = 5.55,
                lastTarget = 5.5,
                elapsedMinutes = 5,
                intent = TargetIntent.ACTIVITY_PROTECTION
            )
        )

        assertThat(decision.allowed).isFalse()
    }

    @Test
    fun sensorSafetyReleaseBelowMaterialDeltaDoesNotUseHypoBypass() {
        val decision = policy.decide(
            request(
                target = 5.55,
                lastTarget = 5.5,
                elapsedMinutes = 5,
                intent = TargetIntent.SENSOR_SAFETY_RELEASE
            )
        )

        assertThat(decision.allowed).isFalse()
    }

    private fun assertInvalidAutomaticCadence(
        decision: TargetCadenceDecision,
        expectedReason: String
    ) {
        assertThat(decision.allowed).isFalse()
        assertThat(decision.outcome)
            .isEqualTo(TempTargetCadenceOutcome.BLOCK_DUPLICATE_WITHIN_WINDOW)
        assertThat(decision.reason).isEqualTo(expectedReason)
    }

    private fun request(
        target: Double,
        last: LastSentTempTarget? = LastSentTempTarget(
            timestamp = NOW - 5 * MINUTE_MS,
            targetMmol = 5.5,
            idempotencyKey = "previous"
        ),
        lastTarget: Double = 5.5,
        elapsedMinutes: Int = 5,
        intent: TargetIntent? = TargetIntent.NORMAL_CONTROL,
        manual: Boolean = false
    ): TargetCadenceRequest = TargetCadenceRequest(
        nowTs = NOW,
        targetMmol = target,
        intent = intent,
        manual = manual,
        lastAutomaticSent = last?.copy(
            timestamp = NOW - elapsedMinutes * MINUTE_MS,
            targetMmol = lastTarget
        )
    )

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val MINUTE_MS = 60_000L
    }
}
