package io.aaps.copilot.tools

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.ClinicalTargetManagerEvidenceProjection
import io.aaps.copilot.data.local.dao.TargetManagerDao
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import io.aaps.copilot.data.local.entity.TargetManagerStateEntity
import io.aaps.copilot.data.repository.TargetCommandDispatcher
import io.aaps.copilot.data.repository.TargetDeliveryStatusProvider
import io.aaps.copilot.data.repository.TargetManagerRepository
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManager
import io.aaps.copilot.domain.target.TargetManagerInput
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetManagerSafetyContext
import io.aaps.copilot.domain.target.TargetProposal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TargetManagerReplayToolTest {

    private val manager = TargetManager()

    @Test
    fun activeModeDispatchesAtMostOneAutomaticTempTargetPerSemanticEvent() = runBlocking {
        val dispatches = AtomicInteger()
        val repository = TargetManagerRepository(
            dao = FakeTargetManagerDao(),
            gson = Gson(),
            dispatcher = TargetCommandDispatcher {
                dispatches.incrementAndGet()
                true
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        )
        val firstInput = input(mode = TargetManagerMode.ACTIVE)
        val first = repository.evaluateAndDispatch(firstInput)
        val replay = repository.evaluateAndDispatch(firstInput)

        assertThat(first.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(replay.outcome).isEqualTo(TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE)
        assertThat(dispatches.get()).isEqualTo(1)
    }

    @Test
    fun manualOrForeignActiveTargetBlocksManagerOverwrite() {
        val now = NOW
        val active = ActiveAapsTarget(
            targetMmol = 6.2,
            startedAt = now - 5 * 60_000L,
            expiresAt = now + 25 * 60_000L,
            source = "aaps_manual",
            ownership = ActiveTargetOwnership.MANUAL_OR_FOREIGN,
            idempotencyKey = null
        )

        val decision = manager.decide(
            input(mode = TargetManagerMode.ACTIVE).copy(activeAapsTarget = active)
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(decision.command).isNull()
    }

    private fun input(mode: TargetManagerMode): TargetManagerInput = TargetManagerInput(
        nowTs = NOW,
        glucoseTimestamp = NOW - 60_000L,
        therapyWatermark = NOW - 120_000L,
        mode = mode,
        proposals = listOf(
            TargetProposal(
                sourceRuleId = "AdaptiveTargetController.v1",
                intent = TargetIntent.NORMAL_CONTROL,
                targetMmol = 6.0,
                durationMinutes = 30,
                priority = 100,
                confidence = 1.0,
                reasonCodes = listOf("replay_candidate"),
                generatedAt = NOW,
                inputFingerprint = "glucose-therapy-semantic-event"
            )
        ),
        runtimeState = TargetManagerRuntimeState(mode),
        activeAapsTarget = null,
        safety = TargetManagerSafetyContext(
            killSwitch = false,
            dataFresh = true,
            sensorTrust = SensorTrustState.TRUSTED,
            deliveryTrust = DeliveryTrustState.NORMAL,
            currentGlucoseMmol = 6.4,
            minimumPredictedOrCiMmol = 5.2,
            lowRiskThresholdMmol = 4.4,
            minTargetMmol = 4.0,
            maxTargetMmol = 9.0,
            minDurationMinutes = 15,
            maxDurationMinutes = 120,
            baseTargetMmol = 5.8
        ),
        reliability = emptyMap(),
        lastAutomaticSent = null,
        baseProvenance = TargetBaseProvenance(
            scheduleRevision = 7L,
            intervalId = "morning",
            adjustmentRunId = "run-7"
        ),
        sensitivityRuntime = io.aaps.copilot.testTargetSensitivityRuntimeContext()
    )

    private class FakeTargetManagerDao : TargetManagerDao {
        override suspend fun invalidateTargetManagerLiveStatus(): Int = 0

        private val states = mutableMapOf<String, TargetManagerStateEntity>()
        private val decisions = mutableListOf<TargetManagerDecisionEntity>()

        override suspend fun state(mode: String): TargetManagerStateEntity? = states[mode]

        override suspend fun upsertState(state: TargetManagerStateEntity) {
            states[state.mode] = state
        }

        override suspend fun insertDecision(decision: TargetManagerDecisionEntity): Long {
            if (decisions.any {
                    it.mode == decision.mode && it.semanticFingerprint == decision.semanticFingerprint
                }
            ) return -1L
            decisions += decision
            return decisions.size.toLong()
        }

        override suspend fun updateDecision(decision: TargetManagerDecisionEntity): Int {
            val index = decisions.indexOfFirst { it.id == decision.id }
            if (index < 0) return 0
            decisions[index] = decision
            return 1
        }

        override suspend fun decisionByFingerprint(
            mode: String,
            semanticFingerprint: String
        ): TargetManagerDecisionEntity? = decisions.firstOrNull {
            it.mode == mode && it.semanticFingerprint == semanticFingerprint
        }

        override suspend fun pendingDecisions(): List<TargetManagerDecisionEntity> =
            decisions.filter { it.deliveryStatus == "pending" }

        override suspend fun latestDecisions(limit: Int): List<TargetManagerDecisionEntity> =
            decisions.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun latestQuarantinedDecision(mode: String): TargetManagerDecisionEntity? =
            decisions.filter { it.mode == mode && it.deliveryStatus == "quarantined" }
                .sortedWith(compareByDescending<TargetManagerDecisionEntity> { it.timestamp }.thenByDescending { it.id })
                .firstOrNull()

        override suspend fun between(
            fromTs: Long,
            throughTs: Long
        ): List<TargetManagerDecisionEntity> = decisions.filter { it.timestamp in fromTs..throughTs }

        override suspend fun clinicalEvidenceBetween(
            fromTs: Long,
            throughTs: Long,
            limit: Int
        ): List<ClinicalTargetManagerEvidenceProjection> = decisions
            .filter { it.timestamp in fromTs..throughTs }
            .sortedWith(compareBy<TargetManagerDecisionEntity> { it.timestamp }.thenBy { it.id })
            .take(limit)
            .map {
                ClinicalTargetManagerEvidenceProjection(
                    it.id,
                    it.timestamp,
                    it.outcome,
                    it.winnerJson,
                    it.reasonCodesJson
                )
            }

        override suspend fun latestReadOnlyDiagnosticTimestamp(): Long? = null

        override suspend fun deleteReadOnlyDiagnosticsOlderThan(olderThan: Long): Int = 0

        override suspend fun deleteReadOnlyDiagnosticsBeyondLimit(maxRows: Int): Int = 0
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
