package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealGlycemicIndex
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/** On-demand, bounded read-only snapshot. No worker, CGM subscription or therapy writes. */
internal class LentaMealHistoryRepository(private val db: CopilotDatabase, private val gson: Gson) {
    suspend fun load(cutoffMs: Long, zone: ZoneId): LentaMealHistory {
        require(cutoffMs > 0)
        val end = Instant.ofEpochMilli(cutoffMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val start = end.atZone(zone).minusDays(21).toInstant()
        return db.withTransaction {
            val rows = db.therapyDao().betweenForAlertAi(start.toEpochMilli(), end.toEpochMilli() - 1,
                listOf("carbs", "meal_bolus"), 5001).map { TherapyEventEntity(it.rowId, it.ts, it.type, it.payloadJson) }
            if (rows.size > 5000) return@withTransaction LentaMealHistory(cutoffMs, zone.id,
                status = LentaMealHistoryStatus.OVERFLOW)
            val ids = rows.mapNotNull { it.toDomain(gson).componentTrust.canonicalCarbId?.toString() }.distinct()
            val overrides = ids.chunked(400).flatMap { db.energyProfileDao().mealOverridesForIdentities(it) }
            fromRows(cutoffMs, zone, rows, overrides, gson)
        }
    }

    companion object {
        internal fun fromRows(cutoffMs: Long, zone: ZoneId, rows: List<TherapyEventEntity>,
            overrides: List<MealProfileOverrideEntity>, gson: Gson): LentaMealHistory {
            require(cutoffMs > 0)
            fun empty(status: LentaMealHistoryStatus) = LentaMealHistory(cutoffMs, zone.id, status = status)
            if (rows.size > 5000 || overrides.size > 5000) return empty(LentaMealHistoryStatus.OVERFLOW)
            val end = Instant.ofEpochMilli(cutoffMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val start = end.atZone(zone).minusDays(21).toInstant()
            val events = rows.filter { it.type in setOf("carbs", "meal_bolus") }
                .map { it.toDomain(gson).copy(sourceRowId = null) }
            if (events.any { it.componentTrust.canonicalReferenceConflict }) return empty(LentaMealHistoryStatus.UNAVAILABLE)
            val evidenceById = overrides.groupBy { it.canonicalTherapyIdentity }
            val entries = events.groupBy { it.componentTrust.canonicalCarbId }.mapNotNull { (id, versions) ->
                if (id == null || id <= 0) return@mapNotNull null
                val event = versions.distinct().singleOrNull() ?: return@mapNotNull null
                val revision = event.componentTrust.canonicalCarbRevision ?: return@mapNotNull null
                val evidence = evidenceById[id.toString()]?.distinct()?.singleOrNull() ?: return@mapNotNull null
                if (evidence.therapyRevisionHash != revision || evidence.source != "COPILOT_UI" || evidence.revision != 1L)
                    return@mapNotNull null
                val type = MealAbsorptionProfile.entries.find { it.name == evidence.profile } ?: return@mapNotNull null
                val portion = MealPortion.entries.find { it.name == evidence.portion } ?: return@mapNotNull null
                val provenance = MealPortionProvenance.entries.find { it.name == evidence.portionProvenance } ?: return@mapNotNull null
                val grams = evidence.confirmedCarbsGrams ?: return@mapNotNull null
                val observation = event.toPortionObservation(MealPortionEvidence(id.toString(), revision,
                    grams, portion, type, provenance, Instant.ofEpochMilli(evidence.updatedAtMs))) ?: return@mapNotNull null
                if (observation.timestamp < start || observation.timestamp >= end || observation.availableAt.toEpochMilli() > cutoffMs)
                    return@mapNotNull null
                LentaMealHistoryEntry(observation, MealGlycemicIndex.fromStored(evidence.glycemicIndexValue,
                    evidence.glycemicIndexSource, evidence.glycemicIndexReference))
            }.sortedBy { it.meal.timestamp }
            return LentaMealHistory(cutoffMs, zone.id, Collections.unmodifiableList(entries))
        }
    }
}
