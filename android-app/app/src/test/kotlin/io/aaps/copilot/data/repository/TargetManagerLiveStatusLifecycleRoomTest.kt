package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.HorizonReliability
import io.aaps.copilot.domain.target.HorizonReliabilityState
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManagerDecision
import io.aaps.copilot.domain.target.TargetManagerInput
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetManagerSafetyContext
import io.aaps.copilot.domain.target.TargetProposal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TargetManagerLiveStatusLifecycleRoomTest {
    private lateinit var context: Context
    private lateinit var db: CopilotDatabase
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "target-live-lifecycle-${UUID.randomUUID()}.db"
        openDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun sameFingerprintAbsentRetryCancellationInvalidatesOldLiveStatusBeforePossiblePost() = runBlocking {
        interruptedDispatch(newFingerprint = false, interruption = CancellationException("after POST"))
    }

    @Test
    fun newFingerprintProcessInterruptionCannotRestoreOldKnownNotSentStatusOnReopen() = runBlocking {
        interruptedDispatch(newFingerprint = true, interruption = AssertionError("process interruption"))
    }

    @Test
    fun failedReplacementLiveWriteCannotRetainOldRefusalAfterRetryTransport() = runBlocking {
        listOf("false", "unknown", "sent").forEach { transportResult ->
            val request = input("replacement-$transportResult")
            seedRefusal(request)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_live_replacement BEFORE INSERT ON telemetry_samples " +
                    "WHEN NEW.id = '${TargetManagerLiveStatusCodec.ROW_ID}' " +
                    "BEGIN SELECT RAISE(ABORT, 'replacement diagnostics unavailable'); END"
            )
            var posts = 0
            val decision = repository {
                posts++
                if (transportResult == "unknown") throw IllegalStateException("after POST")
                transportResult == "sent"
            }.evaluateAndDispatch(request)
            publish(decision)

            assertThat(posts).isEqualTo(1)
            assertThat(db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID)).isNull()
            assertThat(decision.outcome).isEqualTo(
                if (transportResult == "sent") TargetDecisionOutcome.SEND else TargetDecisionOutcome.DELIVERY_FAILED
            )
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_live_replacement")
            db.clearAllTables()
        }
    }

    @Test
    fun pendingJournalAndLiveInvalidationRollBackTogetherBeforeDispatch() = runBlocking {
        listOf(false, true).forEach { newFingerprint ->
            val request = input("rollback-original")
            seedRefusal(request)
            val beforeRow = db.targetManagerDao().latestDecisions(1).single()
            val beforeState = db.targetManagerDao().state("ACTIVE")
            val beforeLive = db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER abort_live_invalidation AFTER DELETE ON telemetry_samples " +
                    "WHEN OLD.id = '${TargetManagerLiveStatusCodec.ROW_ID}' " +
                    "BEGIN SELECT RAISE(ABORT, 'atomic pending transaction interrupted'); END"
            )
            var dispatches = 0
            val caught = runCatching {
                repository { dispatches++; true }
                    .evaluateAndDispatch(if (newFingerprint) input("rollback-new") else request)
            }.exceptionOrNull()

            assertThat(caught).isNotNull()
            assertThat(dispatches).isEqualTo(0)
            assertThat(db.targetManagerDao().latestDecisions(10)).containsExactly(beforeRow)
            assertThat(db.targetManagerDao().state("ACTIVE")).isEqualTo(beforeState)
            assertThat(db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID)).isEqualTo(beforeLive)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_live_invalidation")
            db.clearAllTables()
        }
    }

    @Test
    fun pendingInsertConflictOrMissingUpdateDoesNotInvalidatePriorLiveStatus() = runBlocking {
        seedRefusal(input("conflict"))
        val dao = db.targetManagerDao()
        val row = dao.latestDecisions(1).single().copy(deliveryStatus = "pending")
        val beforeLive = db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID)

        assertThat(dao.insertPendingDecision(row)).isEqualTo(-1L)
        assertThat(dao.updatePendingDecision(row.copy(id = "missing"))).isEqualTo(0)
        assertThat(db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID)).isEqualTo(beforeLive)
        assertThat(dao.pendingDecisions()).isEmpty()
    }

    @Test
    fun invalidationRequiresExactTelemetryIdSourceAndKey() = runBlocking {
        seedRefusal(input("exact-live-identity"))
        val original = checkNotNull(db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID))
        val pending = db.targetManagerDao().latestDecisions(1).single().copy(deliveryStatus = "pending")
        listOf(
            original.copy(id = "other-id"),
            original.copy(source = "other-source"),
            original.copy(key = "other-key")
        ).forEach { unrelated ->
            db.telemetryDao().upsertAll(listOf(unrelated))
            assertThat(db.targetManagerDao().updatePendingDecision(pending)).isEqualTo(1)
            assertThat(db.telemetryDao().byId(unrelated.id)).isEqualTo(unrelated)
        }
    }

    @Test
    fun confirmedAbsentPendingRecoveryAlsoInvalidatesBeforeRedispatch() = runBlocking {
        val request = input("pending-recovery")
        seedRefusal(request)
        val oldLive = checkNotNull(db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID))
        runCatching {
            repository { throw CancellationException("first retry interrupted") }.evaluateAndDispatch(request)
        }
        assertThat(db.targetManagerDao().pendingDecisions()).hasSize(1)
        db.telemetryDao().upsertAll(listOf(oldLive))
        var dispatches = 0
        var oldRowWasAbsentBeforePossiblePost = false

        repository {
            oldRowWasAbsentBeforePossiblePost = db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID) == null
            dispatches++
            false
        }.evaluateAndDispatch(request.copy(nowTs = NOW + 3L * 60_000L))

        assertThat(dispatches).isEqualTo(1)
        assertThat(oldRowWasAbsentBeforePossiblePost).isTrue()
        assertThat(liveStatus()).isNull()
    }

    @Test
    fun stillRefusedRetryCanPublishFreshKnownNotSentStatusWithoutPost() = runBlocking {
        val request = input("still-refused")
        seedRefusal(request)
        var posts = 0
        var oldRowWasAbsentAtPreflight = false
        val decision = repository {
            oldRowWasAbsentAtPreflight = db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID) == null
            io.aaps.copilot.service.dispatchManagedTargetAfterPreflightStatic(
                preflight = { "manual_target_active_or_pending" },
                onPreflightBlocked = {},
                deliver = { posts++; true }
            )
        }.evaluateAndDispatch(request)
        publish(decision)

        assertThat(oldRowWasAbsentAtPreflight).isTrue()
        assertThat(posts).isEqualTo(0)
        assertThat(liveStatus()?.reason).isEqualTo("manual_target_active_or_pending")
    }

    private suspend fun interruptedDispatch(newFingerprint: Boolean, interruption: Throwable) {
        val request = input("original")
        seedRefusal(request)
        val beforeState = db.targetManagerDao().state("ACTIVE")
        val unrelated = checkNotNull(db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID))
            .copy(id = "other-live-status", source = "other_source", key = "other_key")
        db.telemetryDao().upsertAll(listOf(unrelated))
        var posts = 0
        var reconciliations = 0
        var oldRowWasAbsentBeforePossiblePost = false
        val repository = TargetManagerRepository(
            dao = db.targetManagerDao(),
            gson = Gson(),
            dispatcher = TargetCommandDispatcher {
                oldRowWasAbsentBeforePossiblePost =
                    db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID) == null
                posts++
                throw interruption
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider {
                reconciliations++
                "absent"
            }
        )
        val caught = runCatching {
            repository.evaluateAndDispatch(if (newFingerprint) input("new") else request)
        }.exceptionOrNull()

        assertThat(caught).isSameInstanceAs(interruption)
        assertThat(posts).isEqualTo(1)
        assertThat(reconciliations).isEqualTo(if (newFingerprint) 0 else 1)
        assertThat(oldRowWasAbsentBeforePossiblePost).isTrue()
        assertThat(db.targetManagerDao().state("ACTIVE")).isEqualTo(beforeState)
        assertThat(db.targetManagerDao().pendingDecisions()).hasSize(1)
        db.close()
        openDatabase()
        assertThat(liveStatus()).isNull()
        assertThat(db.telemetryDao().byId(unrelated.id)).isEqualTo(unrelated)
        assertThat(db.targetManagerDao().pendingDecisions()).hasSize(1)
    }

    private suspend fun seedRefusal(request: TargetManagerInput) {
        val refusal = repository {
            throw TargetCommandPreflightBlockedException(
                TargetCommandPreflightFailure.MANUAL_TARGET_ACTIVE_OR_PENDING
            )
        }.evaluateAndDispatch(request)
        publish(refusal)
        assertThat(liveStatus()?.reason).isEqualTo("manual_target_active_or_pending")
    }

    private suspend fun publish(decision: TargetManagerDecision) {
        AutomationRepository.reportTargetManagerLiveStatusStatic(
            liveEvaluation = true,
            status = AutomationRepository.buildTargetManagerLiveStatusStatic(
                nowTs = NOW,
                mode = TargetManagerMode.ACTIVE,
                priorityEnabled = true,
                policyRevision = 1L,
                activeAapsTarget = null,
                decision = decision
            ),
            persist = { db.telemetryDao().upsertAll(listOf(it)) }
        )
    }

    private suspend fun liveStatus() = TargetManagerLiveStatusCodec.decodeTelemetryRow(
        db.telemetryDao().byId(TargetManagerLiveStatusCodec.ROW_ID)
    )

    private fun repository(dispatch: suspend () -> Boolean) = TargetManagerRepository(
        dao = db.targetManagerDao(),
        gson = Gson(),
        dispatcher = TargetCommandDispatcher { dispatch() },
        deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
    )

    private fun openDatabase() {
        db = Room.databaseBuilder(context, CopilotDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
    }

    private fun input(fingerprint: String) = TargetManagerInput(
        nowTs = NOW,
        glucoseTimestamp = NOW - 60_000L,
        therapyWatermark = NOW - 120_000L,
        mode = TargetManagerMode.ACTIVE,
        proposals = listOf(TargetProposal(
            sourceRuleId = "adaptive", intent = TargetIntent.NORMAL_CONTROL,
            targetMmol = 5.7, durationMinutes = 30, priority = 100, confidence = 0.9,
            reasonCodes = listOf("control"), generatedAt = NOW, inputFingerprint = fingerprint
        )),
        runtimeState = TargetManagerRuntimeState(TargetManagerMode.ACTIVE),
        activeAapsTarget = null,
        safety = TargetManagerSafetyContext(
            killSwitch = false, dataFresh = true, sensorTrust = SensorTrustState.TRUSTED,
            deliveryTrust = DeliveryTrustState.NORMAL, currentGlucoseMmol = 7.0,
            minimumPredictedOrCiMmol = 6.0, lowRiskThresholdMmol = 4.4,
            minTargetMmol = 4.0, maxTargetMmol = 10.0, minDurationMinutes = 15,
            maxDurationMinutes = 120, baseTargetMmol = 5.5, safetyIobUnits = 1.0
        ),
        reliability = listOf(5, 30, 60).associateWith {
            HorizonReliability(it, HorizonReliabilityState.RELIABLE, 100, 0.5, 0.0, 0.9, 1.0, NOW)
        },
        lastAutomaticSent = null,
        baseProvenance = TargetBaseProvenance(1L, "interval", null),
        sensitivityRuntime = io.aaps.copilot.testTargetSensitivityRuntimeContext()
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
