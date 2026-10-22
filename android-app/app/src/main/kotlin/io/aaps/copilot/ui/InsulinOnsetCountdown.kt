package io.aaps.copilot.ui

import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.AiInsulinContext
import io.aaps.copilot.data.repository.BoundedClinicalPayloadParser
import io.aaps.copilot.data.repository.TherapySanitizer
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.model.isExcludedInsulinTimingRecord
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

data class InsulinOnsetCountdown(val recordKey: String, val recordedAtMs: Long,
    val estimatedAtMs: Long, val onsetMinutes: Double, val mealAssociated: Boolean = true,
    val validUntilMs: Long = Long.MAX_VALUE,
    val retained: Boolean = false, val requiresReview: Boolean = false) {
    fun remainingSeconds(nowMs: Long): Long = ceil((estimatedAtMs - nowMs).coerceAtLeast(0) / 1000.0).toLong()
    fun visibleAt(nowMs: Long): Boolean = retained ||
        (nowMs >= recordedAtMs && nowMs <= estimatedAtMs + 15 * 60_000L && nowMs <= validUntilMs)
    fun needsReviewAt(nowMs: Long): Boolean = requiresReview || nowMs < recordedAtMs
    fun showsMealCompletionAt(nowMs: Long): Boolean = mealAssociated && !needsReviewAt(nowMs) &&
        nowMs in estimatedAtMs..(estimatedAtMs + 15 * 60_000L)
}

/** A clock from recorded insulin, not proof of delivery or permission to eat. */
internal fun insulinOnsetCountdown(rows: List<TherapyEventEntity>, model: AiInsulinContext,
    nowMs: Long, retainedRecordKey: String? = null): InsulinOnsetCountdown? {
    val onset = model.onsetMinutes?.takeIf { it.isFinite() && it in 1.0..180.0 } ?: return null
    val evidence = model.onsetEvidenceTs ?: return null
    if (model.knownProfile == null || model.profileMatches != true || evidence <= 0 || evidence > nowMs) return null
    val candidates = TherapySanitizer.filterEntities(rows).mapNotNull { row ->
        if (row.id.isBlank() || row.id.length > 512) return@mapNotNull null
        val type = row.type.lowercase(Locale.US)
        if (type !in setOf("meal_bolus", "correction_bolus", "bolus", "insulin") ||
            row.timestamp <= 0 || row.timestamp > nowMs ||
            (row.id != retainedRecordKey && row.timestamp < nowMs - 195 * 60_000L)) return@mapNotNull null
        // Freshness admits the estimate for this event, not for each screen visit.
        if (evidence < row.timestamp - 15 * 60_000L) return@mapNotNull null
        val payload = BoundedClinicalPayloadParser.parse(row.payloadJson) ?: return@mapNotNull null
        if (isExcludedInsulinTimingRecord(row.id, payload.scalarValues())) return@mapNotNull null
        val parts = resolveTherapyComponents(type, payload.scalarValues(), payload.componentTrust())
        if (!parts.wholeEventValid || parts.insulinU?.let { it.isFinite() && it > 0 } != true) return@mapNotNull null
        val estimatedAtMs = row.timestamp + (onset * 60_000).roundToLong()
        InsulinOnsetCountdown(row.id, row.timestamp, estimatedAtMs, onset,
            mealAssociated = type == "meal_bolus", validUntilMs = estimatedAtMs + 15 * 60_000L,
            retained = row.id == retainedRecordKey)
    }
    val latestTs = candidates.maxOfOrNull { it.recordedAtMs } ?: return null
    return candidates.filter { it.recordedAtMs == latestTs }.distinctBy { it.recordKey }.singleOrNull()
        ?.takeIf { it.visibleAt(nowMs) }
}
