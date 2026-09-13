package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import org.junit.Test

class TargetProposalFactoryTest {

    private val factory = TargetProposalFactory()

    @Test
    fun adaptiveHypoDecisionMapsToTypedHypoProposal() {
        val mapped = factory.fromRuleDecision(
            decision = RuleDecision(
                ruleId = "AdaptiveTargetController.v2",
                state = RuleState.TRIGGERED,
                reasons = listOf("hypo_preemptive_guard"),
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = 6.0,
                    durationMinutes = 30,
                    reason = "adaptive_pi_ci_v2|mode=hypo_preemptive_guard"
                )
            ),
            priority = 500,
            generatedAt = NOW,
            inputFingerprint = "cycle-a"
        )

        assertThat(mapped?.intent).isEqualTo(TargetIntent.HYPO_PROTECTION)
        assertThat(mapped?.priority).isEqualTo(500)
    }

    @Test
    fun activityRecoveryToBaseFlowsFromRuleDecisionThroughManager() {
        val recovery = factory.fromRuleDecision(
            decision = RuleDecision(
                ruleId = "activity_recovery_to_base",
                state = RuleState.TRIGGERED,
                reasons = listOf("activity_recovery_to_base"),
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = 5.6,
                    durationMinutes = 30,
                    reason = "activity_recovery_to_base"
                )
            ),
            priority = -100,
            generatedAt = NOW,
            inputFingerprint = "recovery-cycle"
        )
        val normal = TargetProposal(
            sourceRuleId = "normal",
            intent = TargetIntent.NORMAL_CONTROL,
            targetMmol = 5.8,
            durationMinutes = 30,
            priority = 10_000,
            confidence = 1.0,
            reasonCodes = listOf("normal"),
            generatedAt = NOW,
            inputFingerprint = "normal-cycle"
        )

        assertThat(recovery?.intent).isEqualTo(TargetIntent.RECOVERY_TO_BASE)

        val decision = TargetManager().decide(
            managerInput(proposals = listOf(checkNotNull(recovery), normal))
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.winner?.sourceRuleId).isEqualTo("activity_recovery_to_base")
        assertThat(decision.winner?.intent).isEqualTo(TargetIntent.RECOVERY_TO_BASE)
    }

    @Test
    fun nonTriggeredDecisionDoesNotCreateProposal() {
        val mapped = factory.fromRuleDecision(
            decision = RuleDecision("rule", RuleState.BLOCKED, listOf("blocked"), null),
            priority = 100,
            generatedAt = NOW,
            inputFingerprint = "cycle"
        )

        assertThat(mapped).isNull()
    }

    @Test
    fun sensorRollbackOnlyReleasesManagerOwnedLowTargetUpToBase() {
        val proposal = factory.sensorSafetyRelease(
            activeTarget = active(target = 4.2),
            baseTargetMmol = 5.5,
            sensorBlocked = true,
            sensorReason = "sensor_age",
            generatedAt = NOW,
            inputFingerprint = "sensor-b"
        )

        assertThat(proposal?.targetMmol).isWithin(1e-6).of(5.5)
        assertThat(proposal?.intent).isEqualTo(TargetIntent.SENSOR_SAFETY_RELEASE)
    }

    @Test
    fun sensorRollbackNeverLowersProtectiveHighTarget() {
        val proposal = factory.sensorSafetyRelease(
            activeTarget = active(target = 8.0),
            baseTargetMmol = 5.5,
            sensorBlocked = true,
            sensorReason = "sensor_age",
            generatedAt = NOW,
            inputFingerprint = "sensor-b"
        )

        assertThat(proposal).isNull()
    }

    @Test
    fun keepaliveCopiesAcceptedTargetExactlyNearExpiry() {
        val accepted = AcceptedTargetState(
            revision = 3,
            targetMmol = 6.35,
            durationMinutes = 30,
            ownerRuleId = "adaptive",
            intent = TargetIntent.HYPO_PROTECTION,
            acceptedAt = NOW - 25 * MINUTE_MS,
            expiresAt = NOW + 4 * MINUTE_MS,
            lastInputFingerprint = "accepted-input",
            lastCommandId = "old-command",
            lastCommandStatus = "sent"
        )

        val renewal = factory.keepalive(
            accepted = accepted,
            nowTs = NOW,
            sensorTrust = SensorTrustState.TRUSTED,
            mode = TargetManagerMode.ACTIVE,
            inputFingerprint = "renewal-input"
        )

        assertThat(renewal?.targetMmol).isWithin(1e-6).of(6.35)
        assertThat(renewal?.durationMinutes).isEqualTo(30)
        assertThat(renewal?.intent).isEqualTo(TargetIntent.HYPO_PROTECTION)
        assertThat(renewal?.reasonCodes).contains("owner=adaptive")
    }

    @Test
    fun keepaliveIsDisabledOutsideRenewalWindowOrWhenSensorNotTrusted() {
        val accepted = AcceptedTargetState(
            revision = 1,
            targetMmol = 5.5,
            durationMinutes = 30,
            ownerRuleId = "adaptive",
            intent = TargetIntent.NORMAL_CONTROL,
            acceptedAt = NOW,
            expiresAt = NOW + 20 * MINUTE_MS,
            lastInputFingerprint = "accepted",
            lastCommandId = "command",
            lastCommandStatus = "sent"
        )

        assertThat(
            factory.keepalive(
                accepted = accepted,
                nowTs = NOW,
                sensorTrust = SensorTrustState.TRUSTED,
                mode = TargetManagerMode.ACTIVE,
                inputFingerprint = "renew"
            )
        ).isNull()
        assertThat(
            factory.keepalive(
                accepted = accepted.copy(expiresAt = NOW + MINUTE_MS),
                nowTs = NOW,
                sensorTrust = SensorTrustState.WARN,
                mode = TargetManagerMode.ACTIVE,
                inputFingerprint = "renew"
            )
        ).isNull()
    }

    @Test
    fun keepaliveRemainsEligibleAtExpiryAndForOneCycleGrace() {
        val accepted = AcceptedTargetState(
            revision = 1,
            targetMmol = 5.8,
            durationMinutes = 30,
            ownerRuleId = "adaptive",
            intent = TargetIntent.NORMAL_CONTROL,
            acceptedAt = NOW - 30 * MINUTE_MS,
            expiresAt = NOW,
            lastInputFingerprint = "accepted",
            lastCommandId = "command",
            lastCommandStatus = "sent"
        )

        assertThat(
            factory.keepalive(
                accepted = accepted,
                nowTs = NOW,
                sensorTrust = SensorTrustState.TRUSTED,
                mode = TargetManagerMode.ACTIVE,
                inputFingerprint = "at-expiry"
            )
        ).isNotNull()
        assertThat(
            factory.keepalive(
                accepted = accepted,
                nowTs = NOW + 5 * MINUTE_MS,
                sensorTrust = SensorTrustState.TRUSTED,
                mode = TargetManagerMode.ACTIVE,
                inputFingerprint = "grace"
            )
        ).isNotNull()
        assertThat(
            factory.keepalive(
                accepted = accepted,
                nowTs = NOW + 5 * MINUTE_MS + 1,
                sensorTrust = SensorTrustState.TRUSTED,
                mode = TargetManagerMode.ACTIVE,
                inputFingerprint = "too-late"
            )
        ).isNull()
    }

    @Test
    fun keepaliveRequiresCommandStatusMatchingManagerMode() {
        val sent = accepted(lastCommandStatus = "sent")
        val shadow = accepted(lastCommandStatus = "shadow")

        assertThat(keepalive(sent, TargetManagerMode.ACTIVE)).isNotNull()
        assertThat(keepalive(sent, TargetManagerMode.SHADOW)).isNull()
        assertThat(keepalive(shadow, TargetManagerMode.SHADOW)).isNotNull()
        assertThat(keepalive(shadow, TargetManagerMode.ACTIVE)).isNull()
        assertThat(keepalive(sent, TargetManagerMode.OFF)).isNull()
        assertThat(keepalive(shadow, TargetManagerMode.OFF)).isNull()

        listOf<String?>(null, "pending", "failed").forEach { status ->
            val rejected = accepted(lastCommandStatus = status)
            assertThat(keepalive(rejected, TargetManagerMode.ACTIVE)).isNull()
            assertThat(keepalive(rejected, TargetManagerMode.SHADOW)).isNull()
        }
    }

    @Test
    fun keepaliveReturnsNullWhenRemainingTimeSubtractionOverflows() {
        val expiresAtMax = accepted(
            lastCommandStatus = "sent",
            expiresAt = Long.MAX_VALUE
        )
        val expiresAtMin = accepted(
            lastCommandStatus = "sent",
            expiresAt = Long.MIN_VALUE
        )

        assertThat(
            factory.keepalive(
                accepted = expiresAtMax,
                nowTs = Long.MIN_VALUE,
                sensorTrust = SensorTrustState.TRUSTED,
                mode = TargetManagerMode.ACTIVE,
                inputFingerprint = "positive-overflow"
            )
        ).isNull()
        assertThat(
            factory.keepalive(
                accepted = expiresAtMin,
                nowTs = Long.MAX_VALUE,
                sensorTrust = SensorTrustState.TRUSTED,
                mode = TargetManagerMode.ACTIVE,
                inputFingerprint = "negative-overflow"
            )
        ).isNull()
    }

    @Test
    fun renewalPreservesAcceptedOwnerAcrossManagerModes() {
        listOf(
            TargetManagerMode.ACTIVE to "sent",
            TargetManagerMode.SHADOW to "shadow"
        ).forEach { (mode, status) ->
            val accepted = accepted(
                ownerRuleId = "AdaptiveTargetController.v2",
                lastCommandStatus = status
            )
            val renewal = checkNotNull(keepalive(accepted, mode))
            val decision = TargetManager().decide(
                managerInput(
                    mode = mode,
                    currentGlucoseMmol = 3.8,
                    proposals = listOf(renewal),
                    runtimeState = TargetManagerRuntimeState(
                        mode = mode,
                        acceptedTarget = accepted
                    )
                )
            )

            assertThat(decision.nextRuntimeState.acceptedTarget?.ownerRuleId)
                .isEqualTo("AdaptiveTargetController.v2")
            if (mode == TargetManagerMode.ACTIVE) {
                assertThat(decision.command?.ownerRuleId).isEqualTo("AdaptiveTargetController.v2")
            }
        }
    }

    @Test
    fun renewalAtExactExpiryPreservesAcceptedOwner() {
        val accepted = accepted(
            ownerRuleId = "AdaptiveTargetController.v2",
            lastCommandStatus = "sent",
            expiresAt = NOW
        )
        val renewal = checkNotNull(keepalive(accepted, TargetManagerMode.ACTIVE))
        val decision = TargetManager().decide(
            managerInput(
                currentGlucoseMmol = 3.8,
                proposals = listOf(renewal),
                runtimeState = TargetManagerRuntimeState(
                    mode = TargetManagerMode.ACTIVE,
                    acceptedTarget = accepted
                )
            )
        )

        assertThat(decision.command?.ownerRuleId).isEqualTo("AdaptiveTargetController.v2")
        assertThat(decision.nextRuntimeState.acceptedTarget?.ownerRuleId)
            .isEqualTo("AdaptiveTargetController.v2")
    }

    private fun keepalive(
        accepted: AcceptedTargetState,
        mode: TargetManagerMode
    ): TargetProposal? = factory.keepalive(
        accepted = accepted,
        nowTs = NOW,
        sensorTrust = SensorTrustState.TRUSTED,
        mode = mode,
        inputFingerprint = "renewal-$mode-${accepted.lastCommandStatus}"
    )

    private fun accepted(
        ownerRuleId: String = "adaptive",
        lastCommandStatus: String?,
        expiresAt: Long = NOW + 4 * MINUTE_MS
    ) = AcceptedTargetState(
        revision = 3,
        targetMmol = 6.35,
        durationMinutes = 30,
        ownerRuleId = ownerRuleId,
        intent = TargetIntent.HYPO_PROTECTION,
        acceptedAt = NOW - 25 * MINUTE_MS,
        expiresAt = expiresAt,
        lastInputFingerprint = "accepted-input",
        lastCommandId = "old-command",
        lastCommandStatus = lastCommandStatus
    )

    private fun managerInput(
        mode: TargetManagerMode = TargetManagerMode.ACTIVE,
        proposals: List<TargetProposal>,
        currentGlucoseMmol: Double = 7.0,
        runtimeState: TargetManagerRuntimeState = TargetManagerRuntimeState(mode = mode)
    ) = TargetManagerInput(
        nowTs = NOW,
        glucoseTimestamp = NOW - MINUTE_MS,
        therapyWatermark = NOW - 2 * MINUTE_MS,
        mode = mode,
        proposals = proposals,
        runtimeState = runtimeState,
        activeAapsTarget = null,
        safety = TargetManagerSafetyContext(
            killSwitch = false,
            dataFresh = true,
            sensorTrust = SensorTrustState.TRUSTED,
            deliveryTrust = DeliveryTrustState.NORMAL,
            currentGlucoseMmol = currentGlucoseMmol,
            minimumPredictedOrCiMmol = 6.5,
            lowRiskThresholdMmol = 4.4,
            minTargetMmol = 4.0,
            maxTargetMmol = 10.0,
            minDurationMinutes = 15,
            maxDurationMinutes = 120,
            baseTargetMmol = 5.5
        ),
        reliability = mapOf(
            5 to reliability(5),
            30 to reliability(30),
            60 to reliability(60)
        ),
        lastAutomaticSent = null,
        baseProvenance = TargetBaseProvenance(
            scheduleRevision = 1,
            intervalId = "default",
            adjustmentRunId = null
        ),
        sensitivityRuntime = io.aaps.copilot.testTargetSensitivityRuntimeContext()
    )

    private fun reliability(horizon: Int) = HorizonReliability(
        horizonMinutes = horizon,
        state = HorizonReliabilityState.RELIABLE,
        sampleCount = 100,
        maeMmol = 0.5,
        biasMmol = 0.0,
        ciCoverage = 0.9,
        weightMultiplier = 1.0,
        evaluatedAt = NOW
    )

    private fun active(target: Double) = ActiveAapsTarget(
        targetMmol = target,
        startedAt = NOW - 10 * MINUTE_MS,
        expiresAt = NOW + 20 * MINUTE_MS,
        source = "copilot",
        ownership = ActiveTargetOwnership.TARGET_MANAGER,
        idempotencyKey = "target-manager"
    )

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val MINUTE_MS = 60_000L
    }
}
