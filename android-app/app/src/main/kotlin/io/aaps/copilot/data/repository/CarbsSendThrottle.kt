package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import kotlin.math.ceil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CarbsSendThrottle(
    private val actionCommandDao: ActionCommandDao
) {
    private val mutex = Mutex()

    suspend fun evaluate(nowMs: Long = System.currentTimeMillis()): Decision = mutex.withLock {
        val lastSentTs = actionCommandDao.latestTimestampByTypeAndStatus(
            type = ACTION_TYPE_CARBS,
            status = NightscoutActionRepository.STATUS_SENT
        )
        if (lastSentTs == null) {
            return@withLock Decision(
                allowed = true,
                waitMs = 0L,
                waitMinutes = 0,
                lastSentTs = null
            )
        }
        val waitMs = HARD_LIMIT_INTERVAL_MS - (nowMs - lastSentTs)
        if (waitMs <= 0L) {
            return@withLock Decision(
                allowed = true,
                waitMs = 0L,
                waitMinutes = 0,
                lastSentTs = lastSentTs
            )
        }
        Decision(
            allowed = false,
            waitMs = waitMs,
            waitMinutes = ceil(waitMs / 60_000.0).toInt().coerceAtLeast(1),
            lastSentTs = lastSentTs
        )
    }

    suspend fun recordGatewaySent(
        nowMs: Long,
        idempotencyKey: String,
        payloadJson: String,
        safetyJson: String = "{}"
    ) = mutex.withLock {
        actionCommandDao.upsert(
            ActionCommandEntity(
                id = "gateway:$idempotencyKey",
                timestamp = nowMs,
                type = ACTION_TYPE_CARBS,
                payloadJson = payloadJson,
                safetyJson = safetyJson,
                idempotencyKey = idempotencyKey,
                status = NightscoutActionRepository.STATUS_SENT
            )
        )
    }

    data class Decision(
        val allowed: Boolean,
        val waitMs: Long,
        val waitMinutes: Int,
        val lastSentTs: Long?
    )

    companion object {
        const val HARD_LIMIT_INTERVAL_MS = 30 * 60_000L
        const val ACTION_TYPE_CARBS = "carbs"
    }
}
