package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TempTargetSendThrottle(
    private val actionCommandDao: ActionCommandDao
) {
    private val mutex = Mutex()

    suspend fun evaluate(
        nowMs: Long = System.currentTimeMillis(),
        idempotencyKey: String? = null,
        targetMmol: Double? = null
    ): Decision = mutex.withLock {
        if (idempotencyKey?.startsWith(NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX) == true) {
            return@withLock Decision(
                allowed = true,
                waitMs = 0L,
                waitMinutes = 0,
                lastSentTs = null,
                lastTargetMmol = null,
                reason = "manual_bypass"
            )
        }

        val lastSent = actionCommandDao.latestByTypeAndStatusExcludingPrefix(
            type = ACTION_TYPE_TEMP_TARGET,
            status = NightscoutActionRepository.STATUS_SENT,
            excludedPrefix = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%"
        )
        if (lastSent == null) {
            return@withLock Decision(
                allowed = true,
                waitMs = 0L,
                waitMinutes = 0,
                lastSentTs = null,
                lastTargetMmol = null,
                reason = "no_previous_send"
            )
        }

        val waitMs = HARD_LIMIT_INTERVAL_MS - (nowMs - lastSent.timestamp)
        if (waitMs <= 0L) {
            return@withLock Decision(
                allowed = true,
                waitMs = 0L,
                waitMinutes = 0,
                lastSentTs = lastSent.timestamp,
                lastTargetMmol = extractTargetMmol(lastSent),
                reason = "window_elapsed"
            )
        }

        val lastTargetMmol = extractTargetMmol(lastSent)
        if (targetMmol != null && lastTargetMmol != null) {
            val delta = abs(targetMmol - lastTargetMmol)
            if (delta >= SIGNIFICANT_TARGET_DELTA_MMOL) {
                return@withLock Decision(
                    allowed = true,
                    waitMs = 0L,
                    waitMinutes = 0,
                    lastSentTs = lastSent.timestamp,
                    lastTargetMmol = lastTargetMmol,
                    reason = "target_changed"
                )
            }
        }

        Decision(
            allowed = false,
            waitMs = waitMs,
            waitMinutes = ceil(waitMs / 60_000.0).toInt().coerceAtLeast(1),
            lastSentTs = lastSent.timestamp,
            lastTargetMmol = lastTargetMmol,
            reason = "duplicate_target_within_window"
        )
    }

    private fun extractTargetMmol(command: ActionCommandEntity): Double? {
        val payloadMatch = TARGET_MMOL_REGEX.find(command.payloadJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        if (payloadMatch != null) return payloadMatch

        val idempotencyParts = command.idempotencyKey.split(':')
        if (idempotencyParts.size >= 3) {
            return idempotencyParts[idempotencyParts.lastIndex - 1].toDoubleOrNull()
        }
        return null
    }

    data class Decision(
        val allowed: Boolean,
        val waitMs: Long,
        val waitMinutes: Int,
        val lastSentTs: Long?,
        val lastTargetMmol: Double?,
        val reason: String
    )

    companion object {
        const val HARD_LIMIT_INTERVAL_MS = 30 * 60_000L
        const val ACTION_TYPE_TEMP_TARGET = "temp_target"
        const val SIGNIFICANT_TARGET_DELTA_MMOL = 0.15

        private val TARGET_MMOL_REGEX = Regex("\"targetMmol\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)")
    }
}
