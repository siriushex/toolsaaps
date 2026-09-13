package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManagerDecision
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetProposal
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AutomationTargetManagerLiveStatusTest {

    @Test
    fun liveBlockedWritersPublishSafetyDecisionButNeverClaimHypotheticalSend() = runTest {
        val rows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()
        for (outcome in listOf(TargetDecisionOutcome.SEND, TargetDecisionOutcome.RENEW_SAME_TARGET,
            TargetDecisionOutcome.BLOCK_KILL_SWITCH)) {
            val status = AutomationRepository.buildTargetManagerLiveStatusStatic(
                nowTs = NOW,
                mode = TargetManagerMode.ACTIVE,
                priorityEnabled = true,
                policyRevision = 9L,
                activeAapsTarget = activeTarget(6.4),
                decision = decision(outcome, 5.8, emptyList()),
                dispatchAllowed = false
            )
            AutomationRepository.reportTargetManagerLiveStatusStatic(true, status) { rows += it }
            val decoded = TargetManagerLiveStatusCodec.decodeTelemetryRow(rows.last())!!
            if (outcome == TargetDecisionOutcome.BLOCK_KILL_SWITCH) {
                assertThat(decoded.outcome).isEqualTo("BLOCK_KILL_SWITCH")
                assertThat(decoded.reason).isEqualTo("kill_switch")
            } else {
                assertThat(decoded.outcome).isEqualTo("DISPATCH_DISABLED")
                assertThat(decoded.reason).isEqualTo("therapy_writes_disabled")
            }
        }
        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.id }.distinct()).containsExactly(TargetManagerLiveStatusCodec.ROW_ID)
    }

    @Test
    fun unresolvedHistorySentinelIsNeverPresentedAsAnImportedTarget() {
        val status = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 9L,
            activeAapsTarget = activeTarget(4.0).copy(evidenceResolved = false),
            decision = decision(TargetDecisionOutcome.BLOCK_MANUAL_TARGET, null,
                listOf("active_target_evidence_unresolved"))
        )
        assertThat(status.currentTargetMmol).isNull()
    }

    @Test
    fun productionPublishesLiveBlockedDecisionsIndependentlyOfWriterPermission() {
        val relative = "src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt"
        val source = java.io.File(relative).takeIf { it.exists() }
            ?: java.io.File("app/$relative")
        assertThat(source.readText()).contains("liveEvaluation = mode == RuleEvaluationMode.LIVE")
    }

    @Test
    fun externalWriterConflictRemainsDistinctFromDisabledPriority() {
        fun status(reason: String) = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 9L,
            activeAapsTarget = activeTarget(6.4),
            decision = decision(TargetDecisionOutcome.BLOCK_MANUAL_TARGET, null, listOf(reason))
        )
        val conflict = status("external_target_writer_conflict")
        assertThat(conflict.reason).isEqualTo("external_target_writer_conflict")
        assertThat(TargetManagerLiveStatusCodec.decode(TargetManagerLiveStatusCodec.encode(conflict)))
            .isEqualTo(conflict)
        assertThat(status("manual_or_foreign_target_active").reason)
            .isEqualTo("external_target_retained")
        assertThat(status("private-detail=id-123").reason).isEqualTo("external_target_retained")
    }

    @Test
    fun statusUsesObservedCurrentTargetAndFinalWinnerWithoutClaimingFailedDeliveryApplied() {
        val status = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 9L,
            activeAapsTarget = activeTarget(6.4),
            decision = decision(
                outcome = TargetDecisionOutcome.DELIVERY_FAILED,
                winnerTargetMmol = 5.8,
                reasonCodes = listOf("eligible", "delivery_failed")
            )
        )

        assertThat(status.currentTargetMmol).isEqualTo(6.4)
        assertThat(status.proposedTargetMmol).isEqualTo(5.8)
        assertThat(status.outcome).isEqualTo("DELIVERY_FAILED")
        assertThat(status.reason).isEqualTo("delivery_failed")
        assertThat(status.priorityEnabled).isTrue()
        assertThat(status.policyRevision).isEqualTo(9L)
    }

    @Test
    fun historicalPreflightMarkerDoesNotOverrideSentOrUnknownOutcome() {
        listOf(TargetDecisionOutcome.SEND, TargetDecisionOutcome.DELIVERY_FAILED).forEach { outcome ->
            val status = AutomationRepository.buildTargetManagerLiveStatusStatic(
                nowTs = NOW,
                mode = TargetManagerMode.ACTIVE,
                priorityEnabled = true,
                policyRevision = 9L,
                activeAapsTarget = null,
                decision = decision(
                    outcome,
                    4.9,
                    listOf(
                        TargetCommandPreflightFailure.MARKER,
                        TargetCommandPreflightFailure.MANUAL_TARGET_ACTIVE_OR_PENDING.reasonCode,
                        "delivery_status_unknown"
                    )
                )
            )
            assertThat(status.outcome).isEqualTo(outcome.name)
            assertThat(status.reason).isEqualTo(
                if (outcome == TargetDecisionOutcome.SEND) "eligible" else "delivery_failed"
            )
        }
    }

    @Test
    fun typedPreflightRefusalPersistsConcreteKnownNotSentReason() = runTest {
        val rows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()
        val decision = decision(
            outcome = TargetDecisionOutcome.DELIVERY_FAILED,
            winnerTargetMmol = 4.9,
            reasonCodes = listOf(
                "eligible",
                TargetCommandPreflightFailure.MARKER,
                TargetCommandPreflightFailure.MANUAL_TARGET_ACTIVE_OR_PENDING.reasonCode
            )
        )

        val status = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 9L,
            activeAapsTarget = activeTarget(4.1),
            decision = decision
        )
        AutomationRepository.reportTargetManagerLiveStatusStatic(true, status) { rows += it }
        val persisted = TargetManagerLiveStatusCodec.decodeTelemetryRow(rows.single())!!

        assertThat(persisted.currentTargetMmol).isEqualTo(4.1)
        assertThat(persisted.proposedTargetMmol).isEqualTo(4.9)
        assertThat(persisted.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET.name)
        assertThat(persisted.reason).isEqualTo("manual_target_active_or_pending")
    }

    @Test
    fun newerGlucosePreflightAndTransportFailurePublishDifferentReasons() {
        val preflight = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 9L,
            activeAapsTarget = activeTarget(4.1),
            decision = decision(
                TargetDecisionOutcome.DELIVERY_FAILED,
                4.9,
                listOf(
                    TargetCommandPreflightFailure.MARKER,
                    TargetCommandPreflightFailure.CURRENT_GLUCOSE_NEWER_THAN_CANDIDATE.reasonCode
                )
            )
        )
        val transport = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 9L,
            activeAapsTarget = activeTarget(4.1),
            decision = decision(
                TargetDecisionOutcome.DELIVERY_FAILED,
                4.9,
                listOf("eligible", "delivery_failed")
            )
        )

        assertThat(preflight.reason).isEqualTo("current_glucose_newer_than_candidate")
        assertThat(preflight.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_STALE_DATA.name)
        assertThat(transport.reason).isEqualTo("delivery_failed")
        assertThat(transport.outcome).isEqualTo(TargetDecisionOutcome.DELIVERY_FAILED.name)
    }

    @Test
    fun reporterWritesOnlyLiveEvaluationAndOperationalFailureCannotFailCompletedDispatch() = runTest {
        val status = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 2L,
            activeAapsTarget = null,
            decision = decision(TargetDecisionOutcome.SEND, 5.9, listOf("eligible"))
        )
        var writes = 0

        AutomationRepository.reportTargetManagerLiveStatusStatic(
            liveEvaluation = false,
            status = status
        ) { writes++ }
        AutomationRepository.reportTargetManagerLiveStatusStatic(
            liveEvaluation = true,
            status = status
        ) {
            writes++
            throw IllegalStateException("diagnostic store unavailable")
        }

        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun noProposalExposesOnlyQualifiedIobMachineReasonWhenUpstreamSignalIsExact() {
        val noProposal = decision(
            outcome = TargetDecisionOutcome.NO_PROPOSAL,
            winnerTargetMmol = null,
            reasonCodes = listOf("no_eligible_proposals")
        )

        val qualifiedIobReason = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 3L,
            activeAapsTarget = null,
            decision = noProposal,
            noProposalReason = "safety_iob_missing_blocks_lowering"
        )
        val unrecognizedReason = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 3L,
            activeAapsTarget = null,
            decision = noProposal,
            noProposalReason = "private-detail=id-123"
        )

        assertThat(qualifiedIobReason.reason).isEqualTo("iob_unqualified")
        assertThat(unrecognizedReason.reason).isEqualTo("no_eligible_proposal")
        assertThat(qualifiedIobReason.proposedTargetMmol).isNull()
    }

    @Test
    fun reporterPropagatesCancellationAndEveryError() = runTest {
        val status = AutomationRepository.buildTargetManagerLiveStatusStatic(
            nowTs = NOW,
            mode = TargetManagerMode.ACTIVE,
            priorityEnabled = true,
            policyRevision = 2L,
            activeAapsTarget = null,
            decision = decision(TargetDecisionOutcome.SEND, 5.9, listOf("eligible"))
        )
        val cancellation = CancellationException("cancel")
        val fatal = AssertionError("fatal")

        assertThat(captureFailure {
            AutomationRepository.reportTargetManagerLiveStatusStatic(true, status) { throw cancellation }
        }).isSameInstanceAs(cancellation)
        assertThat(captureFailure {
            AutomationRepository.reportTargetManagerLiveStatusStatic(true, status) { throw fatal }
        }).isSameInstanceAs(fatal)
    }

    private fun activeTarget(targetMmol: Double) = ActiveAapsTarget(
        targetMmol = targetMmol,
        startedAt = NOW - 60_000L,
        expiresAt = NOW + 30 * 60_000L,
        source = "aaps",
        ownership = ActiveTargetOwnership.MANUAL_OR_FOREIGN,
        idempotencyKey = null
    )

    private fun decision(
        outcome: TargetDecisionOutcome,
        winnerTargetMmol: Double?,
        reasonCodes: List<String>
    ) = TargetManagerDecision(
        outcome = outcome,
        winner = winnerTargetMmol?.let { targetMmol -> TargetProposal(
            sourceRuleId = "final-protected-proposal",
            intent = TargetIntent.NORMAL_CONTROL,
            targetMmol = targetMmol,
            durationMinutes = 30,
            priority = 100,
            confidence = 0.9,
            reasonCodes = listOf("final_proposal"),
            generatedAt = NOW,
            inputFingerprint = "input"
        ) },
        command = null,
        semanticFingerprint = "decision",
        cadenceOutcome = null,
        cadenceReason = null,
        reasonCodes = reasonCodes,
        rejectedProposalReasons = emptyMap(),
        nextRuntimeState = TargetManagerRuntimeState(TargetManagerMode.ACTIVE)
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        IllegalStateException("expected failure")
    } catch (failure: Throwable) {
        failure
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
