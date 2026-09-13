package io.aaps.copilot.domain.profile

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class ActivityTargetProposalFactoryTest {

    private val factory = ActivityTargetProposalFactory()
    private val occurrence = PlannedActivityOccurrence(
        eventId = "walk",
        revision = 3L,
        type = PlannedActivityType.WALKING,
        intensity = PlannedActivityIntensity.LIGHT,
        start = Instant.parse("2026-08-08T09:00:00Z"),
        end = Instant.parse("2026-08-08T10:00:00Z"),
        evaluationStarts = Instant.parse("2026-08-08T08:00:00Z"),
        dstResolution = DstResolution.EXACT
    )

    @Test
    fun safePlannedActivityCreatesRaiseAtLeadBoundaryWithoutConfirmation() {
        val proposal = factory.create(
            input(now = occurrence.evaluationStarts)
        )

        assertThat(proposal.direction).isEqualTo(ActivityTargetProposalDirection.RAISE)
        assertThat(proposal.targetMmol).isEqualTo(7.7)
        assertThat(proposal.requiresUserConfirmation).isFalse()
        assertThat(proposal.blockers).isEmpty()
    }

    @Test
    fun missingFlatOrRisingProtectionIndicationProducesHold() {
        val missing = factory.create(input(safePredictedFall = null))
        val flat = factory.create(
            input(safePredictedFall = safePredictedFall().copy(observedDelta5Mmol = 0.0))
        )
        val rising = factory.create(
            input(safePredictedFall = safePredictedFall().copy(pred30Mmol = 7.3, pred60Mmol = 7.6))
        )

        listOf(missing, flat, rising).forEach { proposal ->
            assertThat(proposal.direction).isEqualTo(ActivityTargetProposalDirection.HOLD)
            assertThat(proposal.blockers).contains(ActivityTargetBlocker.MISSING_SAFE_PREDICTED_FALL)
        }
    }

    @Test
    fun lowerRequiresReplayAndPersonalEvidenceAndSameCycleCandidate() {
        val withoutEvidence = factory.create(
            input(candidate = 4.8)
        )
        val withProof = factory.create(
            input(
                candidate = 4.8,
                candidateFingerprint = "candidate-proof",
                personalEvidence = ActivityPersonalRiseEvidence("personal-proof"),
                replayEvidence = ActivityReplayTargetEvidence("replay-proof")
            )
        )

        assertThat(withoutEvidence.direction).isEqualTo(ActivityTargetProposalDirection.RAISE)
        assertThat(withoutEvidence.blockers).isEmpty()
        assertThat(withProof.direction).isEqualTo(ActivityTargetProposalDirection.LOWER)
        assertThat(withProof.targetMmol).isEqualTo(4.8)
    }

    @Test
    fun lowerWithoutCandidateFailsClosedEvenWithEvidence() {
        val proposal = factory.create(
            input(
                candidate = null,
                personalEvidence = ActivityPersonalRiseEvidence("personal-proof"),
                replayEvidence = ActivityReplayTargetEvidence("replay-proof")
            )
        )

        assertThat(proposal.direction).isEqualTo(ActivityTargetProposalDirection.HOLD)
        assertThat(proposal.blockers).contains(ActivityTargetBlocker.MISSING_SAME_CYCLE_CANDIDATE)
    }

    @Test
    fun unprovenControllerDecreaseFallsBackToBoundedPlannedActivityRaise() {
        val proposal = factory.create(input(candidate = 4.8))

        assertThat(proposal.direction).isEqualTo(ActivityTargetProposalDirection.RAISE)
        assertThat(proposal.targetMmol).isEqualTo(7.7)
        assertThat(proposal.blockers).isEmpty()
    }

    private fun input(
        now: Instant = occurrence.start,
        candidate: Double? = null,
        candidateFingerprint: String? = null,
        safePredictedFall: ActivitySafePredictedFall? = safePredictedFall(),
        personalEvidence: ActivityPersonalRiseEvidence? = null,
        replayEvidence: ActivityReplayTargetEvidence? = null
    ) = ActivityTargetProposalInput(
        now = now,
        occurrence = occurrence,
        moduleEnabled = true,
        baseTargetMmol = 5.5,
        minTargetMmol = 4.0,
        maxTargetMmol = 10.0,
        sameCycleAdaptiveCandidateMmol = candidate,
        sameCycleAdaptiveCandidateFingerprint = candidateFingerprint,
        safePredictedFall = safePredictedFall,
        personalRiseEvidence = personalEvidence,
        replayEvidence = replayEvidence
    )

    private fun safePredictedFall() = ActivitySafePredictedFall(
        evidenceHash = "control-proof",
        currentGlucoseMmol = 7.0,
        observedDelta5Mmol = -0.1,
        pred5Mmol = 6.8,
        pred30Mmol = 6.5,
        pred60Mmol = 6.2,
        ciLow5Mmol = 6.4,
        ciLow30Mmol = 6.1,
        ciLow60Mmol = 5.8,
        lowRiskThresholdMmol = 4.4
    )
}
