package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AutomationLocalSafetyEvidenceRoomTest {

    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun boundedLocalEvidenceCountsRecentAutomaticActionsAndResolvesForeignActiveTarget() = runBlocking {
        db.actionCommandDao().upsert(command("auto-1", NOW - 5 * MINUTE_MS, "TargetManager.v1:one"))
        db.actionCommandDao().upsert(command("auto-2", NOW - 10 * MINUTE_MS, "rule:two"))
        db.actionCommandDao().upsert(command("auto-3", NOW - 15 * MINUTE_MS, "rule:three"))
        db.actionCommandDao().upsert(command("manual", NOW - MINUTE_MS, "manual:ignored"))
        db.actionCommandDao().upsert(command("keepalive", NOW - MINUTE_MS, "adaptive_keepalive:ignored"))
        db.actionCommandDao().upsert(command("old", NOW - 7 * HOUR_MS, "rule:old"))
        db.therapyDao().upsertAll(
            listOf(
                TherapyEventEntity(
                    id = "foreign-target",
                    timestamp = NOW - 10 * MINUTE_MS,
                    type = "temp_target",
                    payloadJson = """{"targetBottom":7.2,"duration":60,"notes":"AAPS manual"}"""
                )
            )
        )

        val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW
        )

        assertThat(evidence.actionsLast6h).isEqualTo(3)
        assertThat(evidence.activeAapsTarget?.targetMmol).isEqualTo(7.2)
        assertThat(evidence.activeAapsTarget?.ownership).isEqualTo(ActiveTargetOwnership.MANUAL_OR_FOREIGN)
        assertThat(evidence.activeAapsTarget?.expiresAt).isEqualTo(NOW + 50 * MINUTE_MS)
    }

    @Test
    fun boundedLocalEvidenceExcludesFutureSentActions() = runBlocking {
        db.actionCommandDao().upsert(command("causal", NOW, "rule:causal"))
        db.actionCommandDao().upsert(command("future", NOW + 1L, "rule:future"))

        val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW
        )

        assertThat(evidence.actionsLast6h).isEqualTo(1)
    }

    @Test
    fun clockRollbackKeepsTargetAndSentChronologyBlockingUntilRecovery() = runBlocking {
        val sentAtHighWater = NOW - 10 * MINUTE_MS
        db.actionCommandDao().upsert(
            command("automatic-before-rollback", sentAtHighWater, "TargetManager.v1:rollback")
                .copy(payloadJson = """{"targetMmol":"5.8"}""")
        )
        db.therapyDao().upsertAll(
            listOf(
                TherapyEventEntity(
                    id = "rollback-active-foreign-target",
                    timestamp = NOW - 4 * HOUR_MS,
                    type = "temp_target",
                    payloadJson = """{"targetBottom":7.4,"duration":720,"notes":"AAPS manual"}"""
                )
            )
        )

        val atHighWater = AutomationRepository.loadLocalSafetyEvidenceStatic(db, Gson(), NOW)
        val rolledBack = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW - 30 * MINUTE_MS
        )
        val recovered = AutomationRepository.loadLocalSafetyEvidenceStatic(db, Gson(), NOW)

        assertThat(atHighWater.chronologyResolved).isTrue()
        assertThat(rolledBack.chronologyResolved).isFalse()
        assertThat(rolledBack.causalThroughTs).isEqualTo(NOW)
        assertThat(rolledBack.actionsLast6h).isEqualTo(1)
        assertThat(rolledBack.latestAutomaticSent?.timestamp).isEqualTo(sentAtHighWater)
        assertThat(rolledBack.activeAapsTarget?.ownership)
            .isEqualTo(ActiveTargetOwnership.MANUAL_OR_FOREIGN)
        assertThat(rolledBack.activeAapsTarget?.expiresAt).isEqualTo(NOW + 8 * HOUR_MS)
        assertThat(recovered.chronologyResolved).isTrue()
        assertThat(recovered.causalThroughTs).isEqualTo(NOW)
    }

    @Test
    fun smallCanonicalRollbackDoesNotLowerHighWaterWithinSkewTolerance() = runBlocking {
        db.actionCommandDao().upsert(
            command("automatic-at-high-water", NOW, "TargetManager.v1:small-rollback")
                .copy(payloadJson = """{"targetMmol":"5.8"}""")
        )
        AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW,
            monotonicNowTs = MONOTONIC_NOW
        )

        val rolledBack = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW - MINUTE_MS,
            monotonicNowTs = MONOTONIC_NOW + MINUTE_MS
        )

        assertThat(rolledBack.chronologyResolved).isFalse()
        assertThat(rolledBack.causalThroughTs).isEqualTo(NOW)
        assertThat(rolledBack.actionsLast6h).isEqualTo(1)
        assertThat(rolledBack.latestAutomaticSent?.timestamp).isEqualTo(NOW)
    }

    @Test
    fun corruptFarFutureTargetDoesNotExtendRollbackBlockAndRecoversWithoutDeletion() = runBlocking {
        val corrupt = TherapyEventEntity(
            id = "far-future-corrupt-after-rollback",
            timestamp = NOW + 24 * HOUR_MS,
            type = "temp_target",
            payloadJson = "{malformed"
        )
        db.therapyDao().upsertAll(listOf(corrupt))

        val initial = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW,
            monotonicNowTs = MONOTONIC_NOW
        )
        val rolledBack = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW - 30 * MINUTE_MS,
            monotonicNowTs = MONOTONIC_NOW + MINUTE_MS
        )
        val nearCorruptTimestamp = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            corrupt.timestamp - MINUTE_MS,
            monotonicNowTs = MONOTONIC_NOW + 24 * HOUR_MS - MINUTE_MS
        )
        val afterFiniteWindow = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            corrupt.timestamp + 13 * HOUR_MS,
            monotonicNowTs = MONOTONIC_NOW + 37 * HOUR_MS
        )

        assertThat(initial.activeAapsTarget).isNull()
        assertThat(initial.chronologyResolved).isTrue()
        assertThat(rolledBack.chronologyResolved).isFalse()
        assertThat(rolledBack.activeAapsTarget).isNull()
        assertThat(nearCorruptTimestamp.activeAapsTarget?.evidenceResolved).isFalse()
        assertThat(afterFiniteWindow.activeAapsTarget).isNull()
        assertThat(afterFiniteWindow.chronologyResolved).isTrue()
        assertThat(db.therapyDao().byId(corrupt.id)).isEqualTo(corrupt)
    }

    @Test
    fun roomBackedNormalDispatchCadenceExcludesFutureAutomaticTarget() = runBlocking {
        db.actionCommandDao().upsert(
            command(
                id = "future-normal-cadence",
                timestamp = NOW + 10 * MINUTE_MS,
                idempotencyKey = "TargetManager.v1:future-normal"
            ).copy(payloadJson = """{"targetMmol":"5.8"}""")
        )

        val decision = TempTargetSendThrottle(db.actionCommandDao()).evaluate(
            nowMs = NOW,
            idempotencyKey = "TargetManager.v1:causal-normal",
            targetMmol = 5.8
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.lastSentTs).isNull()
        assertThat(decision.lastTargetMmol).isNull()
        assertThat(decision.reason).isEqualTo("no_previous_send")
    }

    @Test
    fun roomBackedDispatchCadenceUsesHighWaterAndBlocksNegativeChronology() = runBlocking {
        val sentAtHighWater = NOW - 10 * MINUTE_MS
        db.actionCommandDao().upsert(
            command(
                id = "automatic-before-dispatch-rollback",
                timestamp = sentAtHighWater,
                idempotencyKey = "TargetManager.v1:before-dispatch-rollback"
            ).copy(payloadJson = """{"targetMmol":"5.8"}""")
        )
        AutomationRepository.loadLocalSafetyEvidenceStatic(db, Gson(), NOW)
        val throttle = TempTargetSendThrottle(
            actionCommandDao = db.actionCommandDao(),
            causalClockReader = CausalSafetyClockReader { nowTs ->
                val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(db, Gson(), nowTs)
                CausalSafetyClock(evidence.causalThroughTs, evidence.chronologyResolved)
            }
        )

        val rolledBack = throttle.evaluate(
            nowMs = NOW - 30 * MINUTE_MS,
            idempotencyKey = "TargetManager.v1:during-rollback",
            targetMmol = 5.8
        )
        val recovered = throttle.evaluate(
            nowMs = NOW,
            idempotencyKey = "TargetManager.v1:after-recovery",
            targetMmol = 5.8
        )

        assertThat(rolledBack.allowed).isFalse()
        assertThat(rolledBack.reason).isEqualTo("invalid_cadence_chronology")
        assertThat(rolledBack.lastSentTs).isEqualTo(sentAtHighWater)
        assertThat(recovered.allowed).isFalse()
        assertThat(recovered.reason).isEqualTo("duplicate_target_within_window")
    }

    @Test
    fun boundedLocalEvidenceResolvesSupportedTwelveHourTargetStartedFourHoursAgo() = runBlocking {
        db.therapyDao().upsertAll(
            listOf(
                TherapyEventEntity(
                    id = "long-foreign-target",
                    timestamp = NOW - 4 * HOUR_MS,
                    type = "temp_target",
                    payloadJson = """{"targetBottom":7.4,"duration":720,"notes":"AAPS manual"}"""
                )
            )
        )

        val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW
        )

        assertThat(evidence.activeAapsTarget?.targetMmol).isEqualTo(7.4)
        assertThat(evidence.activeAapsTarget?.ownership).isEqualTo(ActiveTargetOwnership.MANUAL_OR_FOREIGN)
        assertThat(evidence.activeAapsTarget?.expiresAt).isEqualTo(NOW + 8 * HOUR_MS)
    }

    @Test
    fun malformedLatestTargetEvidenceFailsClosedInsteadOfBecomingAbsence() = runBlocking {
        db.therapyDao().upsertAll(
            listOf(
                TherapyEventEntity(
                    id = "older-active-target",
                    timestamp = NOW - 4 * HOUR_MS,
                    type = "temp_target",
                    payloadJson = """{"targetBottom":7.4,"duration":720,"notes":"AAPS manual"}"""
                ),
                TherapyEventEntity(
                    id = "latest-malformed-target",
                    timestamp = NOW - MINUTE_MS,
                    type = "temp_target",
                    payloadJson = "{malformed"
                )
            )
        )

        val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW
        )

        assertThat(evidence.activeAapsTarget).isNotNull()
        assertThat(evidence.activeAapsTarget?.ownership).isEqualTo(ActiveTargetOwnership.UNKNOWN)
        assertThat(evidence.activeAapsTarget?.source).isEqualTo("unresolved_therapy_history")
        assertThat(evidence.activeAapsTarget?.expiresAt).isGreaterThan(NOW)
    }

    @Test
    fun conflictingRowsAtNewestTimestampFailClosedAsAmbiguousEvidence() = runBlocking {
        db.therapyDao().upsertAll(
            listOf(
                TherapyEventEntity(
                    id = "newest-manual-target",
                    timestamp = NOW - MINUTE_MS,
                    type = "temp_target",
                    payloadJson = """{"targetBottom":7.4,"duration":60,"notes":"AAPS manual"}"""
                ),
                TherapyEventEntity(
                    id = "newest-manager-target",
                    timestamp = NOW - MINUTE_MS,
                    type = "temp_target",
                    payloadJson = """{"targetBottom":5.8,"duration":60,"notes":"copilot:TargetManager.v1:same-ts"}"""
                )
            )
        )

        val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW
        )

        assertThat(evidence.activeAapsTarget?.evidenceResolved).isFalse()
        assertThat(evidence.activeAapsTarget?.ownership).isEqualTo(ActiveTargetOwnership.UNKNOWN)
        assertThat(evidence.activeAapsTarget?.source).isEqualTo("unresolved_therapy_history")
    }

    @Test
    fun farFutureTargetOutsideCausalBoundDoesNotCreateRenewableAmbiguity() = runBlocking {
        val futureRow = TherapyEventEntity(
            id = "far-future-target",
            timestamp = NOW + 6 * HOUR_MS,
            type = "temp_target",
            payloadJson = """{"targetBottom":7.4,"duration":720,"notes":"AAPS manual"}"""
        )
        db.therapyDao().upsertAll(listOf(futureRow))

        val first = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW,
            monotonicNowTs = MONOTONIC_NOW
        )
        val repeated = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW + 4 * MINUTE_MS,
            monotonicNowTs = MONOTONIC_NOW + 4 * MINUTE_MS
        )
        val recovered = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW + FIVE_MINUTE_MS + 1L,
            monotonicNowTs = MONOTONIC_NOW + FIVE_MINUTE_MS + 1L
        )

        assertThat(first.activeAapsTarget).isNull()
        assertThat(first.chronologyResolved).isTrue()
        assertThat(repeated.activeAapsTarget).isNull()
        assertThat(repeated.chronologyResolved).isTrue()
        assertThat(recovered.activeAapsTarget).isNull()
        assertThat(db.therapyDao().byId(futureRow.id)).isEqualTo(futureRow)
    }

    @Test
    fun nearFutureTargetWithinSupportedClockSkewFailsClosedThenResolvesNormally() = runBlocking {
        db.therapyDao().upsertAll(
            listOf(
                TherapyEventEntity(
                    id = "near-future-target",
                    timestamp = NOW + MINUTE_MS,
                    type = "temp_target",
                    payloadJson = """{"targetBottom":7.1,"duration":60,"notes":"AAPS manual"}"""
                )
            )
        )

        val skewed = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW
        )
        val causal = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW + MINUTE_MS
        )

        assertThat(skewed.activeAapsTarget?.evidenceResolved).isFalse()
        assertThat(skewed.activeAapsTarget?.expiresAt).isAtMost(NOW + FIVE_MINUTE_MS)
        assertThat(causal.activeAapsTarget?.evidenceResolved).isTrue()
        assertThat(causal.activeAapsTarget?.targetMmol).isEqualTo(7.1)
        assertThat(causal.activeAapsTarget?.ownership)
            .isEqualTo(ActiveTargetOwnership.MANUAL_OR_FOREIGN)
    }

    @Test
    fun emptyBoundedTargetRangeIsValidNoTargetEvidence() = runBlocking {
        val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW
        )

        assertThat(evidence.actionsLast6h).isEqualTo(0)
        assertThat(evidence.activeAapsTarget).isNull()
    }

    @Test
    fun productionTelemetryRetentionKeepsMonotonicSafetyHighWater() = runBlocking {
        AutomationRepository.loadLocalSafetyEvidenceStatic(db, Gson(), NOW)
        val marker = requireNotNull(
            db.telemetryDao().latestBySourceAndKeyAtOrBefore(
                source = "copilot_local_safety_clock",
                key = "wall_clock_high_water_ms",
                atTs = NOW
            )
        )
        val foreignSameKey = marker.copy(
            id = "foreign-same-high-water-key",
            source = "foreign_source",
            valueText = "foreign"
        )
        db.telemetryDao().upsertAll(listOf(foreignSameKey))

        db.telemetryDao().deleteOlderThanWithReportProfileAndPhysicalActivityRetentionLimit(
            generalOlderThan = NOW + 1L,
            reportOlderThan = NOW + 1L,
            physicalActivityOlderThan = NOW + 1L,
            physicalActivityKeys = emptyList(),
            physicalActivitySources = emptyList(),
            physicalActivityQualities = emptyList(),
            limit = 1_000
        )

        assertThat(db.telemetryDao().byId(marker.id)).isEqualTo(marker)
        assertThat(db.telemetryDao().byId(foreignSameKey.id)).isNull()
    }

    @Test
    fun quarantinedRowsCrossingCausalRangeCannotHideOlderActiveTwelveHourTarget() = runBlocking {
        val activeTarget = TherapyEventEntity(
            id = "older-active-foreign-target",
            timestamp = NOW - 4 * HOUR_MS,
            type = "temp_target",
            payloadJson = """{"targetBottom":7.4,"duration":720,"notes":"AAPS manual"}"""
        )
        val futureRows = (10..12).map { minutesAhead ->
            TherapyEventEntity(
                id = "future-target-$minutesAhead",
                timestamp = NOW + minutesAhead * MINUTE_MS,
                type = "temp_target",
                payloadJson = """{"targetBottom":6.0,"duration":30,"notes":"future artifact"}"""
            )
        }
        db.therapyDao().upsertAll(listOf(activeTarget) + futureRows)
        db.telemetryDao().upsertAll(
            futureRows.map { row ->
                AutomationRepository.localFutureTargetMarkerStatic(row, NOW)
            }
        )

        val firstDetection = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW,
            monotonicNowTs = MONOTONIC_NOW
        )
        val afterRowsBecomeCausal = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW + 13 * MINUTE_MS,
            monotonicNowTs = MONOTONIC_NOW + 13 * MINUTE_MS
        )

        assertThat(firstDetection.activeAapsTarget?.evidenceResolved).isTrue()
        assertThat(firstDetection.activeAapsTarget?.targetMmol).isEqualTo(7.4)
        assertThat(afterRowsBecomeCausal.activeAapsTarget?.evidenceResolved).isTrue()
        assertThat(afterRowsBecomeCausal.activeAapsTarget?.targetMmol).isEqualTo(7.4)
        assertThat(afterRowsBecomeCausal.activeAapsTarget?.ownership)
            .isEqualTo(ActiveTargetOwnership.MANUAL_OR_FOREIGN)
        assertThat(afterRowsBecomeCausal.activeAapsTarget?.expiresAt)
            .isEqualTo(NOW + 8 * HOUR_MS)
    }

    @Test
    fun truncatedTargetEvidenceFailsClosedThenRecoversWhenWindowAdvances() = runBlocking {
        db.therapyDao().upsertAll(
            (0..TARGET_EVIDENCE_MAX_ROWS).map { index ->
                TherapyEventEntity(
                    id = "overflow-target-$index",
                    timestamp = NOW - index * 1_000L,
                    type = "temp_target",
                    payloadJson = """{"duration":0}"""
                )
            }
        )

        val truncated = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW,
            monotonicNowTs = MONOTONIC_NOW
        )
        val recovered = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW + 13 * HOUR_MS,
            monotonicNowTs = MONOTONIC_NOW + 13 * HOUR_MS
        )

        assertThat(truncated.activeAapsTarget?.evidenceResolved).isFalse()
        assertThat(truncated.activeAapsTarget?.source)
            .isEqualTo("unresolved_therapy_history_truncated")
        assertThat(recovered.activeAapsTarget).isNull()
    }

    @Test
    fun collidingQuarantineMarkerFailsClosedWithoutOverwritingForeignTelemetry() = runBlocking {
        val futureRow = TherapyEventEntity(
            id = "collision-future-target",
            timestamp = NOW + MINUTE_MS,
            type = "temp_target",
            payloadJson = """{"targetBottom":7.4,"duration":720,"notes":"AAPS manual"}"""
        )
        db.therapyDao().upsertAll(listOf(futureRow))
        val marker = AutomationRepository.localFutureTargetMarkerStatic(futureRow, NOW)
        val collision = TelemetrySampleEntity(
            id = marker.id,
            timestamp = NOW,
            source = "foreign_source",
            key = "foreign_key",
            valueDouble = 1.0,
            valueText = "must-not-be-overwritten",
            unit = "foreign",
            quality = "TRUSTED"
        )
        db.telemetryDao().upsertAll(listOf(collision))

        val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = NOW + MINUTE_MS
        )

        assertThat(evidence.activeAapsTarget?.evidenceResolved).isFalse()
        assertThat(evidence.activeAapsTarget?.source)
            .isEqualTo("unresolved_target_quarantine_marker")
        assertThat(db.telemetryDao().byId(marker.id)).isEqualTo(collision)
    }

    @Test
    fun noncanonicalFixedClockMarkerUsesBoundedRecoveryAndCleanupDoesNotExemptCollision() = runBlocking {
        val collision = TelemetrySampleEntity(
            id = "copilot-local-safety-clock-high-water-v1",
            timestamp = NOW - HOUR_MS,
            source = "foreign_source",
            key = "foreign_key",
            valueDouble = 7.0,
            valueText = "foreign",
            unit = "foreign",
            quality = "TRUSTED"
        )
        db.telemetryDao().upsertAll(listOf(collision))

        val first = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW,
            monotonicNowTs = MONOTONIC_NOW
        )
        val recovered = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW + 1L,
            monotonicNowTs = MONOTONIC_NOW + 1L
        )

        assertThat(first.chronologyResolved).isFalse()
        assertThat(recovered.chronologyResolved).isTrue()
        assertThat(db.telemetryDao().byId(collision.id)).isEqualTo(collision)

        db.telemetryDao().deleteOlderThanWithReportProfileAndPhysicalActivityRetentionLimit(
            generalOlderThan = NOW,
            reportOlderThan = NOW,
            physicalActivityOlderThan = NOW,
            physicalActivityKeys = emptyList(),
            physicalActivitySources = emptyList(),
            physicalActivityQualities = emptyList(),
            limit = 1_000
        )

        assertThat(db.telemetryDao().byId(collision.id)).isNull()
        assertThat(
            AutomationRepository.loadLocalSafetyEvidenceStatic(
                db,
                Gson(),
                NOW + 2L,
                monotonicNowTs = MONOTONIC_NOW + 2L
            ).chronologyResolved
        ).isTrue()
    }

    @Test
    fun canonicalFarFutureClockMarkerIsQuarantinedAndRecoversOnCorrectedClock() = runBlocking {
        val futureTs = NOW + 24 * HOUR_MS
        db.telemetryDao().upsertAll(
            listOf(
                TelemetrySampleEntity(
                    id = "copilot-local-safety-clock-high-water-v1",
                    timestamp = futureTs,
                    source = "copilot_local_safety_clock",
                    key = "wall_clock_high_water_ms",
                    valueDouble = MONOTONIC_NOW.toDouble(),
                    valueText = futureTs.toString(),
                    unit = "epoch_ms|elapsed_ms_v1",
                    quality = "OK"
                )
            )
        )

        val quarantined = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW,
            monotonicNowTs = MONOTONIC_NOW + MINUTE_MS
        )
        val recovered = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW + MINUTE_MS,
            monotonicNowTs = MONOTONIC_NOW + 2 * MINUTE_MS
        )

        assertThat(quarantined.chronologyResolved).isFalse()
        assertThat(quarantined.causalThroughTs).isAtMost(NOW + 2 * MINUTE_MS)
        assertThat(recovered.chronologyResolved).isTrue()
        assertThat(recovered.causalThroughTs).isEqualTo(NOW + MINUTE_MS)
    }

    @Test
    fun longMaxObservationDoesNotPersistOrRenewUnboundedHighWater() = runBlocking {
        val rejected = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            Long.MAX_VALUE,
            monotonicNowTs = MONOTONIC_NOW
        )
        val recovered = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW,
            monotonicNowTs = MONOTONIC_NOW + MINUTE_MS
        )

        assertThat(rejected.chronologyResolved).isFalse()
        assertThat(rejected.causalThroughTs).isLessThan(Long.MAX_VALUE)
        assertThat(recovered.chronologyResolved).isTrue()
        assertThat(recovered.causalThroughTs).isEqualTo(NOW)
    }

    @Test
    fun unvalidatedForwardJumpFailsClosedThenCorrectedClockRecovers() = runBlocking {
        AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW,
            monotonicNowTs = MONOTONIC_NOW
        )

        val jumped = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW + 24 * HOUR_MS,
            monotonicNowTs = MONOTONIC_NOW + MINUTE_MS
        )
        val corrected = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW + 2 * MINUTE_MS,
            monotonicNowTs = MONOTONIC_NOW + 2 * MINUTE_MS
        )

        assertThat(jumped.chronologyResolved).isFalse()
        assertThat(jumped.causalThroughTs).isAtMost(NOW + 3 * MINUTE_MS)
        assertThat(corrected.chronologyResolved).isTrue()
        assertThat(corrected.causalThroughTs).isEqualTo(NOW + 2 * MINUTE_MS)
    }

    @Test
    fun rebootMonotonicResetFailsClosedThenRecoversFromConsistentObservations() = runBlocking {
        AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW,
            monotonicNowTs = MONOTONIC_NOW
        )

        val firstAfterReboot = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW + MINUTE_MS,
            monotonicNowTs = 10_000L
        )
        val recovered = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            NOW + MINUTE_MS + 1_000L,
            monotonicNowTs = 11_000L
        )
        val marker = db.telemetryDao().byId("copilot-local-safety-clock-high-water-v1")

        assertThat(firstAfterReboot.chronologyResolved).isFalse()
        assertThat(firstAfterReboot.causalThroughTs).isEqualTo(NOW)
        assertThat(recovered.chronologyResolved).isTrue()
        assertThat(recovered.causalThroughTs).isEqualTo(NOW + MINUTE_MS + 1_000L)
        assertThat(marker?.timestamp).isEqualTo(NOW + MINUTE_MS + 1_000L)
        assertThat(marker?.valueDouble).isEqualTo(11_000.0)
        assertThat(marker?.unit).isEqualTo("epoch_ms|elapsed_ms_v1")
    }

    @Test
    fun concurrentFirstClockObservationCreatesOneCanonicalAuthority() = runBlocking {
        val observations = coroutineScope {
            (1..8).map {
                async {
                    AutomationRepository.loadLocalSafetyEvidenceStatic(
                        db,
                        Gson(),
                        NOW,
                        monotonicNowTs = MONOTONIC_NOW
                    )
                }
            }.awaitAll()
        }

        assertThat(observations.map { it.chronologyResolved }).containsExactly(true, true, true, true, true, true, true, true)
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM telemetry_samples WHERE source = ? AND key = ? AND quality = 'OK'",
            arrayOf("copilot_local_safety_clock", "wall_clock_high_water_ms")
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    private fun command(id: String, timestamp: Long, idempotencyKey: String) = ActionCommandEntity(
        id = id,
        timestamp = timestamp,
        type = "temp_target",
        payloadJson = "{}",
        safetyJson = "{}",
        idempotencyKey = idempotencyKey,
        status = NightscoutActionRepository.STATUS_SENT
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE_MS = 60_000L
        const val FIVE_MINUTE_MS = 5 * MINUTE_MS
        const val HOUR_MS = 60 * MINUTE_MS
        const val MONOTONIC_NOW = 500_000_000L
        const val TARGET_EVIDENCE_MAX_ROWS = 256
    }
}
