package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.dao.AutomaticSentCommandEvidence
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.LastSentTempTarget
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TempTargetSendThrottleTest {

    @Test
    fun guardedPersistedReleasePassesTransportRepeatGate() = runBlocking {
        val now = 1_800_000_000_000L
        val previous = LastSentTempTarget(now - 5 * 60_000L, 5.0, "TargetManager.v1:previous")
        val throttle = TempTargetSendThrottle(
            actionCommandDao = FakeActionCommandDao(lastSent = actionEntity(
                timestamp = previous.timestamp, idempotencyKey = previous.idempotencyKey,
                payloadJson = """{"targetMmol":"5.0"}"""
            )),
            managedReleaseReader = { managedReleaseFixture(now, previous) }
        )
        val decision = throttle.evaluate(now, "TargetManager.v1:release", 5.05,
            managedDeliveryGuardPresent = true)

        assertThat(decision.allowed).isTrue()
        assertThat(decision.reason).isEqualTo("managed_episode_release")
    }

    @Test
    fun releasePermissionCannotBypassGuardOrNewerHistoryOrPayloadBinding() = runBlocking {
        val now = 1_800_000_000_000L
        val previous = LastSentTempTarget(now - 5 * 60_000L, 5.0, "TargetManager.v1:previous")
        for (release in listOf(
            managedReleaseFixture(now, previous),
            managedReleaseFixture(now - 6 * 60_000L, previous),
            managedReleaseFixture(now + 1, previous),
            managedReleaseFixture(now, previous.copy(idempotencyKey = "other")),
            managedReleaseFixture(now, previous.copy(targetMmol = 5.01)),
            managedReleaseFixture(now, previous.copy(timestamp = previous.timestamp - 1))
        )) {
            val throttle = TempTargetSendThrottle(
                actionCommandDao = FakeActionCommandDao(lastSent = actionEntity(
                    timestamp = previous.timestamp, idempotencyKey = previous.idempotencyKey,
                    payloadJson = """{"targetMmol":"5.0"}"""
                )), managedReleaseReader = { release }
            )
            assertThat(throttle.evaluate(now, "TargetManager.v1:release", 5.05).allowed).isFalse()
            assertThat(throttle.evaluate(now, "legacy-release", 5.05, managedDeliveryGuardPresent = true).allowed).isFalse()
            assertThat(throttle.evaluate(now, "TargetManager.v1:release", 5.10, managedDeliveryGuardPresent = true).allowed).isFalse()
            if (release != managedReleaseFixture(now, previous)) {
                assertThat(throttle.evaluate(now, "TargetManager.v1:release", 5.05,
                    managedDeliveryGuardPresent = true).allowed).isFalse()
            }
        }
    }

    private fun managedReleaseFixture(now: Long, previous: LastSentTempTarget) =
        TempTargetSendThrottle.ManagedRelease(5.05, previous, now)

    @Test
    fun onlyExactPendingManagerReleaseJournalCanAuthorizeTransport() {
        val now = 1_800_000_000_000L
        val key = "TargetManager.v1:release"
        val command = """{"command":{"targetMmol":5.05,"durationMinutes":30,
            "ownerRuleId":"AdaptiveTargetController.v1","intent":"NORMAL_CONTROL",
            "idempotencyKey":"$key","semanticFingerprint":"release","generatedAt":$now,
            "targetObservation":{"activeAapsTarget":{"targetMmol":5.0,
                "ownership":"TARGET_MANAGER","idempotencyKey":"previous","evidenceResolved":true}}}}"""
        val entity = TargetManagerDecisionEntity(
            id = "ACTIVE:release", timestamp = now, mode = "ACTIVE", semanticFingerprint = "release",
            outcome = "SEND", winnerJson = null, commandJson = command,
            cadenceOutcome = "ALLOW_EPISODE_RELEASE", cadenceReason = "forecast_confirmed_trend_release",
            lastSentTargetMmol = 5.0, lastSentTimestamp = now - 5 * 60_000L,
            deliveryStatus = "pending", reasonCodesJson = "[]", rejectedProposalReasonsJson = "{}"
        )
        assertThat(TempTargetSendThrottle.managedReleaseFromJournal(entity, key, Gson()))
            .isEqualTo(managedReleaseFixture(now, LastSentTempTarget(now - 5 * 60_000L, 5.0, "previous")))
        val rounded = com.google.gson.JsonParser.parseString(command).asJsonObject
        rounded.getAsJsonObject("command").getAsJsonObject("targetObservation")
            .getAsJsonObject("activeAapsTarget").addProperty("targetMmol",
                io.aaps.copilot.util.UnitConverter.mgdlToMmol(
                    io.aaps.copilot.util.UnitConverter.mmolToMgdl(5.0).toDouble()
                ))
        assertThat(TempTargetSendThrottle.managedReleaseFromJournal(entity.copy(commandJson = rounded.toString()), key, Gson()))
            .isNotNull()
        for (invalid in listOf(
            entity.copy(mode = "SHADOW"), entity.copy(outcome = "BLOCK_CADENCE"),
            entity.copy(deliveryStatus = "sent"), entity.copy(cadenceOutcome = "ALLOW_MATERIAL_CHANGE"),
            entity.copy(cadenceReason = "unapproved"), entity.copy(commandJson = "{}"),
            entity.copy(commandJson = "invalid"), entity.copy(lastSentTargetMmol = 5.1),
            entity.copy(lastSentTimestamp = now), entity.copy(timestamp = now + 1),
            entity.copy(semanticFingerprint = "other"), entity.copy(commandJson = null)
        )) assertThat(TempTargetSendThrottle.managedReleaseFromJournal(invalid, key, Gson())).isNull()
        assertThat(TempTargetSendThrottle.managedReleaseFromJournal(entity, "TargetManager.v1:other", Gson())).isNull()
    }

    @Test
    fun blocksAutomaticTempTargetInsideThirtyMinuteWindow() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            actionCommandDao = FakeActionCommandDao(
                lastSent = actionEntity(
                    timestamp = now - 10 * 60_000L,
                    idempotencyKey = "AdaptiveTargetController.v1:bucket:4.10:control_pi",
                    payloadJson = """{"targetMmol":"4.10","durationMinutes":"30","reason":"control_pi"}"""
                )
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "AdaptiveTargetController.v1:${now / 300_000L}:4.10:control_pi",
            targetMmol = 4.10
        )

        assertThat(decision.allowed).isFalse()
        assertThat(decision.waitMinutes).isAtLeast(20)
        assertThat(decision.reason).isEqualTo("duplicate_target_within_window")
    }

    @Test
    fun allowsManualTempTargetBypass() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            actionCommandDao = FakeActionCommandDao(
                lastSent = actionEntity(
                    timestamp = now - 5 * 60_000L,
                    idempotencyKey = "AdaptiveTargetController.v1:bucket:4.10:control_pi",
                    payloadJson = """{"targetMmol":"4.10","durationMinutes":"30","reason":"control_pi"}"""
                )
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}${now}",
            targetMmol = 4.10
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.waitMinutes).isEqualTo(0)
        assertThat(decision.reason).isEqualTo("manual_bypass")
    }

    @Test
    fun manualBypassDoesNotReadAutomaticHistory() = runBlocking {
        val dao = FakeActionCommandDao(lastSent = null, throwOnLatest = true)
        val throttle = TempTargetSendThrottle(dao)

        val decision = throttle.evaluate(
            nowMs = 1_800_000_000_000L,
            idempotencyKey = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}current",
            targetMmol = 5.5
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.reason).isEqualTo("manual_bypass")
        assertThat(dao.latestCalls.get()).isEqualTo(0)
    }

    @Test
    fun allowsMateriallyChangedTargetInsideThirtyMinuteWindow() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            actionCommandDao = FakeActionCommandDao(
                lastSent = actionEntity(
                    timestamp = now - 5 * 60_000L,
                    idempotencyKey = "AdaptiveTargetController.v1:bucket:4.65:control_pi",
                    payloadJson = """{"targetMmol":"4.65","durationMinutes":"30","reason":"control_pi"}"""
                )
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "AdaptiveTargetController.v1:${now / 300_000L}:4.10:control_pi",
            targetMmol = 4.10
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.reason).isEqualTo("target_changed")
        assertThat(decision.lastTargetMmol).isWithin(1e-6).of(4.65)
    }

    @Test
    fun allowsUrgentHypoRetargetInsideThirtyMinuteWindowForSmallUpwardChange() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            actionCommandDao = FakeActionCommandDao(
                lastSent = actionEntity(
                    timestamp = now - 5 * 60_000L,
                    idempotencyKey = "AdaptiveTargetController.v1:bucket:5.80:hypo_preemptive_guard",
                    payloadJson = """{"targetMmol":"5.80","durationMinutes":"30","reason":"hypo_preemptive_guard"}"""
                )
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "AdaptiveTargetController.v1:${now / 300_000L}:5.85:hypo_preemptive_force_high",
            targetMmol = 5.85,
            actionReason = "adaptive_pi_ci_v2|mode=hypo_preemptive_force_high"
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.reason).isEqualTo("urgent_hypo_target_changed")
    }

    @Test
    fun keepsBlockingExactUrgentHypoDuplicateInsideThirtyMinuteWindow() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            actionCommandDao = FakeActionCommandDao(
                lastSent = actionEntity(
                    timestamp = now - 5 * 60_000L,
                    idempotencyKey = "AdaptiveTargetController.v1:bucket:6.40:hypo_preemptive_force_high",
                    payloadJson = """{"targetMmol":"6.40","durationMinutes":"30","reason":"hypo_preemptive_force_high"}"""
                )
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "AdaptiveTargetController.v1:${now / 300_000L}:6.40:hypo_preemptive_force_high",
            targetMmol = 6.40,
            actionReason = "adaptive_pi_ci_v2|mode=hypo_preemptive_force_high"
        )

        assertThat(decision.allowed).isFalse()
        assertThat(decision.reason).isEqualTo("duplicate_target_within_window")
    }

    @Test
    fun typedHypoIntentUsesSharedUrgentCadence() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            FakeActionCommandDao(
                actionEntity(
                    now - 5 * 60_000L,
                    "TargetManager.v1:old",
                    """{"targetMmol":"5.80"}"""
                )
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "TargetManager.v1:new",
            targetMmol = 5.85,
            targetIntent = TargetIntent.HYPO_PROTECTION
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.reason).isEqualTo("urgent_hypo_target_changed")
    }

    @Test
    fun arbitraryUrgentReasonCannotGainHypoBypass() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            FakeActionCommandDao(
                actionEntity(now - 5 * 60_000L, "other:old", """{"targetMmol":"5.80"}""")
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "other:new",
            targetMmol = 5.85,
            actionReason = "hypo_preemptive_force_high"
        )

        assertThat(decision.allowed).isFalse()
    }

    @Test
    fun trustedLegacyAdaptiveProducerRetainsHypoCompatibilityBypass() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            FakeActionCommandDao(
                actionEntity(now - 5 * 60_000L, "AdaptiveTargetController.v1:old", """{"targetMmol":"5.80"}""")
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "AdaptiveTargetController.v1:new",
            targetMmol = 5.85,
            actionReason = "adaptive_pi_ci_v2|mode=hypo_preemptive_force_high"
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.reason).isEqualTo("urgent_hypo_target_changed")
    }

    @Test
    fun unparseableHistoricalSendFailsClosedUntilWindowExpires() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            FakeActionCommandDao(
                actionEntity(now - 5 * 60_000L, "legacy-without-target", "{}")
            )
        )

        val blocked = throttle.evaluate(nowMs = now, targetMmol = 5.5)
        val elapsed = throttle.evaluate(nowMs = now + 31 * 60_000L, targetMmol = 5.5)

        assertThat(blocked.allowed).isFalse()
        assertThat(blocked.reason).isEqualTo("duplicate_target_within_window")
        assertThat(elapsed.allowed).isTrue()
        assertThat(elapsed.reason).isEqualTo("window_elapsed")
    }

    @Test
    fun futureAutomaticSendCannotReserveNormalDispatchCadence() = runBlocking {
        val now = 1_800_000_000_000L
        val throttle = TempTargetSendThrottle(
            FakeActionCommandDao(
                lastSent = actionEntity(
                    now + 10 * 60_000L,
                    "TargetManager.v1:future",
                    """{"targetMmol":"5.80"}"""
                )
            )
        )

        val decision = throttle.evaluate(
            nowMs = now,
            idempotencyKey = "TargetManager.v1:causal-cycle",
            targetMmol = 5.80
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.lastSentTs).isNull()
        assertThat(decision.reason).isEqualTo("no_previous_send")
    }

    private fun actionEntity(
        timestamp: Long,
        idempotencyKey: String,
        payloadJson: String
    ) = ActionCommandEntity(
        id = "cmd-$timestamp",
        timestamp = timestamp,
        type = "temp_target",
        payloadJson = payloadJson,
        safetyJson = "{}",
        idempotencyKey = idempotencyKey,
        status = NightscoutActionRepository.STATUS_SENT
    )

    private class FakeActionCommandDao(
        private val lastSent: ActionCommandEntity?,
        private val throwOnLatest: Boolean = false
    ) : ActionCommandDao {
        val latestCalls = AtomicInteger()

        override suspend fun upsert(command: ActionCommandEntity) = Unit

        override suspend fun byIdempotencyKey(idempotencyKey: String): ActionCommandEntity? = null

        override suspend fun deleteByIdempotencyKeyTypeAndStatus(
            idempotencyKey: String,
            type: String,
            status: String
        ): Int = 0

        override suspend fun automaticSentTargetEvidenceBefore(
            beforeTimestamp: Long
        ): AutomaticSentCommandEvidence = AutomaticSentCommandEvidence(0, null, null)

        override suspend fun countByStatusSince(status: String, since: Long): Int = 0

        override suspend fun countByStatusSinceExcludingPrefix(
            status: String,
            since: Long,
            excludedPrefix: String
        ): Int = 0

        override suspend fun countByStatusBetweenExcludingTwoPrefixes(
            status: String,
            since: Long,
            through: Long,
            excludedPrefix1: String,
            excludedPrefix2: String
        ): Int = 0

        override suspend fun latestTimestampByTypeAndStatusAtOrBeforeExcludingPrefix(
            type: String,
            status: String,
            through: Long,
            excludedPrefix: String
        ): Long? = lastSent?.timestamp?.takeIf { it <= through }

        override suspend fun latestByTypeAndStatusAtOrBeforeExcludingPrefix(
            type: String,
            status: String,
            through: Long,
            excludedPrefix: String
        ): ActionCommandEntity? {
            latestCalls.incrementAndGet()
            if (throwOnLatest) error("automatic history unavailable")
            return lastSent?.takeIf { it.timestamp <= through }
        }

        override suspend fun latestTimestampByTypeAndStatus(type: String, status: String): Long? = null

        override suspend fun byTypeAndIdempotencyPrefixSince(
            type: String,
            idempotencyPrefix: String,
            since: Long
        ): List<ActionCommandEntity> = emptyList()

        override suspend fun latest(limit: Int): List<ActionCommandEntity> = emptyList()

        override suspend fun updateStatusByIds(
            ids: List<String>,
            currentStatus: String,
            newStatus: String
        ): Int = 0

        override fun observeLatest(limit: Int): Flow<List<ActionCommandEntity>> = flowOf(emptyList())

        override suspend fun deleteOlderThan(olderThan: Long): Int = 0
    }
}
