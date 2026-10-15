package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.rules.SustainedRiseTargetPolicy
import org.junit.Assert.*
import org.junit.Test

class SustainedRiseTargetManagerTest {
    private val now = 1_700_000_000_000L
    private val accepted = AcceptedTargetState(1, 4.1, 30, SustainedRiseTargetPolicy.SOURCE,
        TargetIntent.NORMAL_CONTROL, now-28*60_000L, now+2*60_000L, "previous", "accepted", "sent")
    private fun proposal(source: String = SustainedRiseTargetPolicy.SOURCE, target: Double = 4.1) =
        TargetProposal(source, TargetIntent.NORMAL_CONTROL, target, 30, 120, 1.0,
            listOf("sustained_rise_control"), now, "new-input")
    private fun input(proposals: List<TargetProposal> = listOf(proposal())) = TargetManagerInput(
        nowTs = now, glucoseTimestamp = now, therapyWatermark = now,
        mode = TargetManagerMode.ACTIVE, proposals = proposals,
        runtimeState = TargetManagerRuntimeState(TargetManagerMode.ACTIVE, acceptedTarget = accepted),
        activeAapsTarget = ActiveAapsTarget(4.1, accepted.acceptedAt, accepted.expiresAt, "copilot",
            ActiveTargetOwnership.TARGET_MANAGER, "accepted"),
        safety = TargetManagerSafetyContext(false, true, SensorTrustState.TRUSTED, DeliveryTrustState.NORMAL,
            9.4, 7.0, 4.4, 4.0, 8.0, 15, 120, 6.7, 3.6),
        reliability = listOf(5,30,60).associateWith {
            HorizonReliability(it, HorizonReliabilityState.RELIABLE, 100, 0.3, 0.0, 0.9, 1.0, now)
        },
        lastAutomaticSent = null, baseProvenance = TargetBaseProvenance(1, null, null),
        sensitivityRuntime = io.aaps.copilot.testTargetSensitivityRuntimeContext()
    )

    @Test fun equalTargetMustStillPassLowRiskAndReliabilityGates() {
        val initial = input()
        val low = TargetManager().decide(initial.copy(safety = initial.safety.copy(minimumPredictedOrCiMmol = 3.9)))
        assertNull(low.command)
        assertEquals("low_risk_blocks_target_decrease", low.rejectedProposalReasons[SustainedRiseTargetPolicy.SOURCE])
        val unreliable = TargetManager().decide(initial.copy(reliability = emptyMap()))
        assertNull(unreliable.command)
    }

    @Test fun oldProposalAndUncertainDeliveryDoNotSustainFourPointOne() {
        val initial = input()
        assertNull(TargetManager().decide(initial.copy(proposals = listOf(proposal().copy(generatedAt = now-1)))).command)
        for (trust in listOf(DeliveryTrustState.WATCH, DeliveryTrustState.UNKNOWN, DeliveryTrustState.SUSPECTED_NONRESPONSE)) {
            assertNull(TargetManager().decide(initial.copy(safety = initial.safety.copy(deliveryTrust = trust))).command)
        }
    }

    @Test fun blindKeepaliveCannotExtendThisEpisode() {
        val keepalive = proposal(TargetManager.RENEWAL_SOURCE_RULE_ID)
        assertNull(TargetManager().decide(input(listOf(keepalive))).command)
    }

    @Test fun freshEvidenceCanReauthorizeButNormalCadenceStillApplies() {
        val fresh = input()
        assertEquals(4.1, TargetManager().decide(fresh).command!!.targetMmol, 1e-9)
        val duplicate = TargetManager().decide(fresh.copy(lastAutomaticSent = LastSentTempTarget(now-60_000L, 4.1, "accepted")))
        assertEquals(TargetDecisionOutcome.BLOCK_CADENCE, duplicate.outcome)
    }

    @Test fun smallUpwardReleaseIsNotHeldByDuplicateCooldown() {
        val release = input(listOf(proposal("AdaptiveTargetController.v1", 4.2))).copy(
            lastAutomaticSent = LastSentTempTarget(now-60_000L, 4.1, "accepted"))
        assertEquals(4.2, TargetManager().decide(release).command!!.targetMmol, 1e-9)
        val unrelated = release.copy(runtimeState = release.runtimeState.copy(acceptedTarget = accepted.copy(ownerRuleId = "other")))
        assertNull(TargetManager().decide(unrelated).command)
        val mismatched = release.copy(activeAapsTarget = release.activeAapsTarget!!.copy(idempotencyKey = "different"))
        assertNull(TargetManager().decide(mismatched).command)
        assertNull(TargetManager().decide(release.copy(safety = release.safety.copy(killSwitch = true))).command)
    }
}
