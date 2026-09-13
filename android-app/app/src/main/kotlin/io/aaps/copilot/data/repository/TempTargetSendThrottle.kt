package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.domain.target.LastSentTempTarget
import io.aaps.copilot.domain.target.TargetCadencePolicy
import io.aaps.copilot.domain.target.TargetCadenceRequest
import io.aaps.copilot.domain.target.TargetIntent
import kotlin.math.ceil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TempTargetSendThrottle(
    private val actionCommandDao: ActionCommandDao,
    private val cadencePolicy: TargetCadencePolicy = TargetCadencePolicy(),
    private val causalClockReader: CausalSafetyClockReader = CausalSafetyClockReader { nowTs ->
        CausalSafetyClock(throughTs = nowTs, evidenceResolved = true)
    }
) {
    private val mutex = Mutex()

    suspend fun evaluate(
        nowMs: Long = System.currentTimeMillis(),
        idempotencyKey: String? = null,
        targetMmol: Double? = null,
        actionReason: String? = null,
        targetIntent: TargetIntent? = null
    ): Decision = mutex.withLock {
        val manual = idempotencyKey
            ?.startsWith(NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX) == true
        if (manual) {
            return@withLock Decision(
                allowed = true,
                waitMs = 0L,
                waitMinutes = 0,
                lastSentTs = null,
                lastTargetMmol = null,
                reason = "manual_bypass"
            )
        }
        val causalClock = causalClockReader.observe(nowMs)
        val lastSent = actionCommandDao.latestByTypeAndStatusAtOrBeforeExcludingPrefix(
            type = ACTION_TYPE_TEMP_TARGET,
            status = NightscoutActionRepository.STATUS_SENT,
            through = causalClock.throughTs,
            excludedPrefix = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%"
        )
        val lastTargetMmol = lastSent?.let(::extractTargetMmol)
        if (!causalClock.evidenceResolved) {
            return@withLock Decision(
                allowed = false,
                waitMs = HARD_LIMIT_INTERVAL_MS,
                waitMinutes = ceil(HARD_LIMIT_INTERVAL_MS / 60_000.0).toInt(),
                lastSentTs = lastSent?.timestamp,
                lastTargetMmol = lastTargetMmol,
                reason = "invalid_cadence_chronology"
            )
        }
        if (lastSent != null && lastTargetMmol == null) {
            val elapsed = runCatching { Math.subtractExact(nowMs, lastSent.timestamp) }.getOrNull()
            val remaining = elapsed
                ?.takeIf { it >= 0L }
                ?.let { HARD_LIMIT_INTERVAL_MS - it }
            val allowed = remaining != null && remaining <= 0L
            return@withLock Decision(
                allowed = allowed,
                waitMs = remaining?.coerceAtLeast(0L) ?: HARD_LIMIT_INTERVAL_MS,
                waitMinutes = if (allowed) 0 else ceil(
                    (remaining?.coerceAtLeast(0L) ?: HARD_LIMIT_INTERVAL_MS) / 60_000.0
                ).toInt().coerceAtLeast(1),
                lastSentTs = lastSent.timestamp,
                lastTargetMmol = null,
                reason = if (allowed) "window_elapsed" else "duplicate_target_within_window"
            )
        }
        val effectiveIntent = targetIntent ?: legacyIntent(idempotencyKey, actionReason)
        val cadence = cadencePolicy.decide(
            TargetCadenceRequest(
                nowTs = nowMs,
                targetMmol = targetMmol ?: lastTargetMmol ?: Double.NaN,
                intent = effectiveIntent,
                manual = false,
                lastAutomaticSent = if (lastSent != null && lastTargetMmol != null) {
                    LastSentTempTarget(lastSent.timestamp, lastTargetMmol, lastSent.idempotencyKey)
                } else {
                    null
                }
            )
        )
        val waitMs = if (cadence.allowed || lastSent == null) 0L else {
            runCatching {
                val elapsed = Math.subtractExact(nowMs, lastSent.timestamp)
                if (elapsed < 0L) HARD_LIMIT_INTERVAL_MS else (HARD_LIMIT_INTERVAL_MS - elapsed).coerceAtLeast(0L)
            }.getOrDefault(HARD_LIMIT_INTERVAL_MS)
        }
        Decision(
            allowed = cadence.allowed,
            waitMs = waitMs,
            waitMinutes = if (waitMs > 0L) ceil(waitMs / 60_000.0).toInt().coerceAtLeast(1) else 0,
            lastSentTs = lastSent?.timestamp,
            lastTargetMmol = lastTargetMmol,
            reason = cadence.reason
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

    private fun legacyIntent(idempotencyKey: String?, actionReason: String?): TargetIntent? {
        val trustedProducer = idempotencyKey?.startsWith(LEGACY_ADAPTIVE_PREFIX) == true ||
            idempotencyKey?.startsWith(NightscoutActionRepository.KEEPALIVE_IDEMPOTENCY_PREFIX) == true
        if (!trustedProducer) return null
        val reason = actionReason.orEmpty()
        val urgentHypo = reason.contains("safety_force_high") ||
            reason.contains("safety_hypo_guard") ||
            reason.contains("safety_raise_target_to_five") ||
            reason.contains("hypo_preemptive_")
        return if (urgentHypo) TargetIntent.HYPO_PROTECTION else null
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
        const val HARD_LIMIT_INTERVAL_MS = TargetCadencePolicy.REPEAT_WINDOW_MS
        const val ACTION_TYPE_TEMP_TARGET = "temp_target"
        const val SIGNIFICANT_TARGET_DELTA_MMOL = TargetCadencePolicy.MATERIAL_CHANGE_MMOL
        const val URGENT_HYPO_TARGET_DELTA_MMOL = TargetCadencePolicy.URGENT_HYPO_RAISE_MMOL

        private const val LEGACY_ADAPTIVE_PREFIX = "AdaptiveTargetController.v1:"
        private val TARGET_MMOL_REGEX = Regex("\"targetMmol\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)")
    }
}
