package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.HorizonReliability
import io.aaps.copilot.domain.target.HorizonReliabilityState
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManagerInput
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetManagerSafetyContext
import io.aaps.copilot.domain.target.TargetProposal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TargetManagerDiagnosticRetentionRoomTest {

    private lateinit var db: CopilotDatabase
    private lateinit var repository: TargetManagerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TargetManagerRepository(
            dao = db.targetManagerDao(),
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun diagnosticInsertPrunesAgeAtomicallyWithoutDeletingPendingOrSentEvidence() = runBlocking {
        repository.evaluateAndDispatch(input(NOW, "live-sent"))
        val pending = journalRow(
            id = "pending-retry-evidence",
            timestamp = NOW - TargetManagerRepository.DECISION_RETENTION_MS - MINUTE_MS,
            status = "pending"
        )
        db.targetManagerDao().insertDecision(pending)
        val oldDiagnosticTs = NOW - TargetManagerRepository.DECISION_RETENTION_MS - MINUTE_MS

        repository.evaluateReadOnly(input(oldDiagnosticTs, "old-read-only"))
        repository.evaluateReadOnly(input(NOW + MINUTE_MS, "new-read-only"))

        val rows = db.targetManagerDao().latestDecisions(1_000)
        assertThat(rows.filter { it.deliveryStatus == "read_only" }).hasSize(1)
        assertThat(rows.any { it.deliveryStatus == "sent" }).isTrue()
        assertThat(rows).contains(pending)
    }

    @Test
    fun recoveryFindsDurableCommandBehindDiagnosticsAndOtherModes() = runBlocking {
        val dao = db.targetManagerDao()
        TargetManagerRepository(dao, Gson(), TargetCommandDispatcher { false })
            .evaluateAndDispatch(input(NOW - 3 * MINUTE_MS, "old-failed"))
        val original = dao.latestDecisions(1).single()
        dao.updateDecision(original.copy(deliveryStatus = "quarantined"))
        dao.upsertState(checkNotNull(dao.state("ACTIVE")).copy(
            reconciliationStatus = "quarantined:existing_envelope_current_mismatch"
        ))
        repeat(80) { index ->
            dao.insertDecision(journalRow("noise-$index", NOW + index, "read_only"))
        }
        dao.insertDecision(journalRow("foreign-mode", NOW + 100, "quarantined").copy(mode = "SHADOW"))
        assertThat(dao.latestQuarantinedDecision("ACTIVE")?.id).isEqualTo(original.id)
        val dispatched = mutableListOf<String>()
        val recovered = TargetManagerRepository(
            dao, Gson(), TargetCommandDispatcher { command ->
                dispatched += command.idempotencyKey
                true
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        ).evaluateAndDispatch(input(NOW + MINUTE_MS, "new-after-recovery"))

        assertThat(dispatched).hasSize(1)
        assertThat(dispatched.single()).isNotEqualTo("TargetManager.v1:${original.semanticFingerprint}")
        assertThat(dao.decisionByFingerprint("ACTIVE", original.semanticFingerprint)?.deliveryStatus)
            .isEqualTo("superseded")
        assertThat(recovered.nextRuntimeState.reconciliationStatus).doesNotContain("quarantined")
        assertThat(dao.latestQuarantinedDecision("SHADOW")?.id).isEqualTo("foreign-mode")
    }

    @Test
    fun diagnosticRowCapSurvivesClockRollbackAndKeepsLiveEvidence() = runBlocking {
        repository.evaluateAndDispatch(input(NOW, "live-before-cap"))
        val pending = journalRow(
            id = "pending-during-cap",
            timestamp = NOW - 2 * MINUTE_MS,
            status = "pending"
        )
        db.targetManagerDao().insertDecision(pending)

        repeat(TargetManagerRepository.READ_ONLY_DIAGNOSTIC_MAX_ROWS + 20) { index ->
            repository.evaluateReadOnly(
                input(
                    nowTs = NOW - index.toLong(),
                    fingerprint = "rollback-room-$index"
                )
            )
        }

        val rows = db.targetManagerDao().latestDecisions(1_000)
        assertThat(rows.count { it.deliveryStatus == "read_only" })
            .isEqualTo(TargetManagerRepository.READ_ONLY_DIAGNOSTIC_MAX_ROWS)
        assertThat(rows.any { it.deliveryStatus == "sent" }).isTrue()
        assertThat(rows).contains(pending)
    }

    private fun input(nowTs: Long, fingerprint: String) = TargetManagerInput(
        nowTs = nowTs,
        glucoseTimestamp = nowTs - MINUTE_MS,
        therapyWatermark = nowTs - 2 * MINUTE_MS,
        mode = TargetManagerMode.ACTIVE,
        proposals = listOf(
            TargetProposal(
                sourceRuleId = "adaptive",
                intent = TargetIntent.NORMAL_CONTROL,
                targetMmol = 5.7,
                durationMinutes = 30,
                priority = 100,
                confidence = 0.9,
                reasonCodes = listOf("control"),
                generatedAt = nowTs,
                inputFingerprint = fingerprint
            )
        ),
        runtimeState = TargetManagerRuntimeState(TargetManagerMode.ACTIVE),
        activeAapsTarget = null,
        safety = TargetManagerSafetyContext(
            killSwitch = false,
            dataFresh = true,
            sensorTrust = SensorTrustState.TRUSTED,
            deliveryTrust = DeliveryTrustState.NORMAL,
            currentGlucoseMmol = 7.0,
            minimumPredictedOrCiMmol = 6.0,
            lowRiskThresholdMmol = 4.4,
            minTargetMmol = 4.0,
            maxTargetMmol = 10.0,
            minDurationMinutes = 15,
            maxDurationMinutes = 120,
            baseTargetMmol = 5.5,
            safetyIobUnits = 1.0
        ),
        reliability = listOf(5, 30, 60).associateWith(::reliability),
        lastAutomaticSent = null,
        baseProvenance = TargetBaseProvenance(1L, "interval", null),
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

    private fun journalRow(id: String, timestamp: Long, status: String) =
        TargetManagerDecisionEntity(
            id = id,
            timestamp = timestamp,
            mode = TargetManagerMode.ACTIVE.name,
            semanticFingerprint = id,
            outcome = "SEND",
            winnerJson = null,
            commandJson = null,
            cadenceOutcome = null,
            cadenceReason = null,
            lastSentTargetMmol = null,
            lastSentTimestamp = null,
            deliveryStatus = status,
            reasonCodesJson = "[]",
            rejectedProposalReasonsJson = "{}"
        )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE_MS = 60_000L
    }
}
