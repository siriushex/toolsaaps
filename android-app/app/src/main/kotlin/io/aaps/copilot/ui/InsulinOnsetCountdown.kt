package io.aaps.copilot.ui

import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.AiInsulinContext
import io.aaps.copilot.data.repository.BoundedClinicalPayloadParser
import io.aaps.copilot.data.repository.TherapySanitizer
import io.aaps.copilot.domain.model.resolveTherapyComponents
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

data class InsulinOnsetCountdown(val recordKey: String, val recordedAtMs: Long,
    val estimatedAtMs: Long, val onsetMinutes: Double) {
    fun remainingSeconds(nowMs: Long): Long = ceil((estimatedAtMs - nowMs).coerceAtLeast(0) / 1000.0).toLong()
    fun visibleAt(nowMs: Long): Boolean = nowMs >= recordedAtMs && nowMs <= estimatedAtMs + 15 * 60_000L
}

/** A clock from a meal-associated insulin record, not proof of delivery or permission to eat. */
internal fun insulinOnsetCountdown(rows: List<TherapyEventEntity>, model: AiInsulinContext,
    nowMs: Long): InsulinOnsetCountdown? {
    val onset = model.onsetMinutes?.takeIf { it.isFinite() && it in 1.0..180.0 } ?: return null
    val evidence = model.onsetEvidenceTs ?: return null
    if (model.knownProfile == null || model.profileMatches != true || evidence > nowMs || nowMs - evidence > 15 * 60_000L) return null
    val candidates = TherapySanitizer.filterEntities(rows).mapNotNull { row ->
        if (!row.type.equals("meal_bolus", true) || row.timestamp !in (nowMs - 195 * 60_000L)..nowMs) return@mapNotNull null
        val payload = BoundedClinicalPayloadParser.parse(row.payloadJson) ?: return@mapNotNull null
        val parts = resolveTherapyComponents(row.type.lowercase(Locale.US), payload.scalarValues(), payload.componentTrust())
        if (parts.insulinU?.let { it.isFinite() && it > 0 } != true) return@mapNotNull null
        InsulinOnsetCountdown(row.id, row.timestamp, row.timestamp + (onset * 60_000).roundToLong(), onset)
    }
    val latestTs = candidates.maxOfOrNull { it.recordedAtMs } ?: return null
    return candidates.filter { it.recordedAtMs == latestTs }.distinctBy { it.recordKey }.singleOrNull()
        ?.takeIf { it.visibleAt(nowMs) }
}
