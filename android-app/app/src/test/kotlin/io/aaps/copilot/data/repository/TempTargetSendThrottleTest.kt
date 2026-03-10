package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TempTargetSendThrottleTest {

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
        private val lastSent: ActionCommandEntity?
    ) : ActionCommandDao {
        override suspend fun upsert(command: ActionCommandEntity) = Unit

        override suspend fun byIdempotencyKey(idempotencyKey: String): ActionCommandEntity? = null

        override suspend fun countByStatusSince(status: String, since: Long): Int = 0

        override suspend fun countByStatusSinceExcludingPrefix(
            status: String,
            since: Long,
            excludedPrefix: String
        ): Int = 0

        override suspend fun countByStatusSinceExcludingTwoPrefixes(
            status: String,
            since: Long,
            excludedPrefix1: String,
            excludedPrefix2: String
        ): Int = 0

        override suspend fun latestTimestampByTypeAndStatusExcludingPrefix(
            type: String,
            status: String,
            excludedPrefix: String
        ): Long? = lastSent?.timestamp

        override suspend fun latestByTypeAndStatusExcludingPrefix(
            type: String,
            status: String,
            excludedPrefix: String
        ): ActionCommandEntity? = lastSent

        override suspend fun latestTimestampByTypeAndStatus(type: String, status: String): Long? = null

        override suspend fun latest(limit: Int): List<ActionCommandEntity> = emptyList()

        override suspend fun updateStatusByIds(
            ids: List<String>,
            currentStatus: String,
            newStatus: String
        ): Int = 0

        override fun observeLatest(limit: Int): Flow<List<ActionCommandEntity>> = flowOf(emptyList())
    }
}
