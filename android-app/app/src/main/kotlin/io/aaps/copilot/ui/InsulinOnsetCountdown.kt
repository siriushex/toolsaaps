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
    val estimatedAtMs: Long, val onsetMinutes: Double, val mealAssociated: Boolean = true,
    val validUntilMs: Long = Long.MAX_VALUE) {
    fun remainingSeconds(nowMs: Long): Long = ceil((estimatedAtMs - nowMs).coerceAtLeast(0) / 1000.0).toLong()
    fun visibleAt(nowMs: Long): Boolean = nowMs >= recordedAtMs && nowMs <= estimatedAtMs + 15 * 60_000L && nowMs <= validUntilMs
}

/** A clock from recorded insulin, not proof of delivery or permission to eat. */
internal fun insulinOnsetCountdown(rows: List<TherapyEventEntity>, model: AiInsulinContext,
    nowMs: Long): InsulinOnsetCountdown? {
    val onset = model.onsetMinutes?.takeIf { it.isFinite() && it in 1.0..180.0 } ?: return null
    val evidence = model.onsetEvidenceTs ?: return null
    if (model.knownProfile == null || model.profileMatches != true || evidence <= 0 || evidence > nowMs) return null
    val candidates = TherapySanitizer.filterEntities(rows).mapNotNull { row ->
        val type = row.type.lowercase(Locale.US)
        if (type !in setOf("meal_bolus", "correction_bolus", "bolus", "insulin") ||
            row.timestamp !in (nowMs - 195 * 60_000L)..nowMs) return@mapNotNull null
        // Freshness admits the estimate for this event, not for each screen visit.
        if (evidence < row.timestamp - 15 * 60_000L) return@mapNotNull null
        val payload = BoundedClinicalPayloadParser.parse(row.payloadJson) ?: return@mapNotNull null
        val values = payload.scalarValues().mapKeys { io.aaps.copilot.data.repository.normalizeClinicalKey(it.key) }
        fun flag(key: String) = values[key]?.trim()?.lowercase(Locale.US) in setOf("true", "1")
        if (listOf("issmb", "inferred", "recovered", "synthetic", "cancelled", "canceled", "iscancelled", "iscanceled").any(::flag) ||
            listOf("bolustype", "type").any { values[it]?.trim()?.uppercase(Locale.US) in setOf("SMB", "MICROBOLUS", "PRIMING") } ||
            values["classification"]?.trim()?.uppercase(Locale.US) in setOf("INFERRED_IOB", "RECOVERED_MICROBOLUS", "LOCAL_ACTION", "UAM_SYNTHETIC") ||
            row.id.startsWith("iob-inf-") || row.id.startsWith("br-local_broadcast-")) return@mapNotNull null
        val parts = resolveTherapyComponents(type, payload.scalarValues(), payload.componentTrust())
        if (!parts.wholeEventValid || parts.insulinU?.let { it.isFinite() && it > 0 } != true) return@mapNotNull null
        val estimatedAtMs = row.timestamp + (onset * 60_000).roundToLong()
        InsulinOnsetCountdown(row.id, row.timestamp, estimatedAtMs, onset,
            mealAssociated = type == "meal_bolus", validUntilMs = estimatedAtMs + 15 * 60_000L)
    }
    val latestTs = candidates.maxOfOrNull { it.recordedAtMs } ?: return null
    return candidates.filter { it.recordedAtMs == latestTs }.distinctBy { it.recordKey }.singleOrNull()
        ?.takeIf { it.visibleAt(nowMs) }
}
