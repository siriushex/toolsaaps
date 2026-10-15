package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.nutrition.MealPortion
import io.aaps.copilot.domain.nutrition.MealPortionEvidence
import io.aaps.copilot.domain.nutrition.MealPortionProvenance
import io.aaps.copilot.domain.nutrition.LentaMealHistoryStatus
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionEpisodeEvidence
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionEpisodeProducer
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionSourceSnapshot
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionSourceStatus
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionRequest
import io.aaps.copilot.domain.nutrition.PersonalGiAnalysis
import io.aaps.copilot.domain.nutrition.PersonalGiAnalyzer
import io.aaps.copilot.domain.nutrition.PersonalGiEvidence
import io.aaps.copilot.domain.nutrition.toPortionObservation
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

data class PersonalAbsorptionHistoryAudit(
    val rawCarbRows: Int,
    val syntheticCarbRows: Int,
    val independentDeclaredMeals: Int,
    val missingInputs: Set<String>,
    val inputOverflow: Boolean = false
) {
    val eligibleTimingMeals: Int get() = 0
    val clinicalInfluenceAllowed: Boolean get() = false
    val actualMassAccuracyValidated: Boolean get() = false
}

/** Explicit read-only diagnostic requests; no app graph/worker/UI registration. */
internal class PersonalAbsorptionRepository(
    private val db: CopilotDatabase, private val gson: Gson, private val clock: Clock
) {
    suspend fun produce(zone: ZoneId, evidence: List<PersonalAbsorptionEpisodeEvidence> = emptyList()): PersonalAbsorptionSourceSnapshot {
        return loadAnalysisSource(zone, evidence).first
    }

    suspend fun analyzeGi(zone: ZoneId, profile: MealAbsorptionProfile, portion: MealPortion,
        minuteOfDay: Int, evidence: List<PersonalAbsorptionEpisodeEvidence> = emptyList(),
        comparisonCohort: String? = null): PersonalGiAnalysis {
        require(minuteOfDay in 0..1439)
        val (snapshot, gi) = loadAnalysisSource(zone, evidence)
        return PersonalGiAnalyzer().analyze(PersonalAbsorptionRequest(snapshot.cutoffMs, profile,
            portion, zone.id, minuteOfDay), snapshot, gi, comparisonCohort)
    }

    private suspend fun loadAnalysisSource(zone: ZoneId, evidence: List<PersonalAbsorptionEpisodeEvidence>):
        Pair<PersonalAbsorptionSourceSnapshot, List<PersonalGiEvidence>> {
        val cutoffMs = clock.millis()
        val producer = PersonalAbsorptionEpisodeProducer()
        if (PersonalAbsorptionEpisodeProducer.exceedsLimits(emptyList(), evidence))
            return producer.produce(cutoffMs, zone.id, emptyList(), evidence) to emptyList()
        val frozenEvidence = PersonalAbsorptionEpisodeProducer.freezeEvidence(evidence)
        val history = LentaMealHistoryRepository(db, gson).load(cutoffMs, zone)
        val status = when (history.status) {
            LentaMealHistoryStatus.READY -> PersonalAbsorptionSourceStatus.READY
            LentaMealHistoryStatus.UNAVAILABLE -> PersonalAbsorptionSourceStatus.UNAVAILABLE
            LentaMealHistoryStatus.OVERFLOW -> PersonalAbsorptionSourceStatus.OVERFLOW
        }
        val gi = history.entries.mapNotNull { entry -> entry.glycemicIndex?.let { index ->
            PersonalGiEvidence(entry.meal.canonicalId, requireNotNull(entry.meal.therapyRevision),
                index.value, index.source.name, index.reference, entry.meal.availableAt.toEpochMilli())
        } }
        return producer.produce(cutoffMs, zone.id, history.entries.map { it.meal }, frozenEvidence, status) to gi
    }

    suspend fun audit(zone: ZoneId): PersonalAbsorptionHistoryAudit {
        val now = clock.instant()
        val end = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val start = end.atZone(zone).minusDays(21).toInstant()
        return db.withTransaction {
            val rows = db.therapyDao().betweenForAlertAi(start.toEpochMilli(), end.toEpochMilli() - 1,
                listOf("carbs", "meal_bolus"), 5001).map {
                TherapyEventEntity(it.rowId, it.ts, it.type, it.payloadJson)
            }
            if (rows.size > 5000) return@withTransaction overflow()
            val identities = rows.filter { it.type == "carbs" || it.type == "meal_bolus" }
                .mapNotNull { row ->
                    val trust = row.toDomain(gson).componentTrust
                    val id = trust.canonicalCarbId ?: return@mapNotNull null
                    val revision = trust.canonicalCarbRevision ?: return@mapNotNull null
                    id.toString() to revision
                }.distinct()
            if (identities.size > 5000) return@withTransaction overflow()
            val overrides = identities.mapNotNull { (id, revision) ->
                db.energyProfileDao().matchingMealOverrideForIdentity(id, revision)
            }
            auditRows(rows, overrides, gson, start, end, now)
        }
    }

    companion object {
        private val missing = setOf("independent_insulin_intervals", "historical_timezone",
            "glucose_availability", "historical_model_context", "context_review")
        private fun overflow() = PersonalAbsorptionHistoryAudit(0, 0, 0, missing, true)

        internal fun auditRows(
            rows: List<TherapyEventEntity>, overrides: List<MealProfileOverrideEntity>, gson: Gson,
            start: Instant, end: Instant, knownAt: Instant
        ): PersonalAbsorptionHistoryAudit {
            if (rows.size > 30000 || overrides.size > 5000) return overflow()
            val carbRows = rows.filter { it.type == "carbs" || it.type == "meal_bolus" }
            if (carbRows.size > 5000) return overflow()
            val events = carbRows.map { it.toDomain(gson).copy(sourceRowId = null) }
            val synthetic = events.count { resolveTherapyComponents(it).carbKind == TherapyCarbComponentKind.UAM_SYNTHETIC }
            val byIdentity = overrides.groupBy { it.canonicalTherapyIdentity }
            val independent = events.groupBy { it.componentTrust.canonicalCarbId }.count { (id, versions) ->
                if (id == null || id <= 0) return@count false
                val event = versions.distinct().singleOrNull() ?: return@count false
                val revision = event.componentTrust.canonicalCarbRevision ?: return@count false
                val evidence = byIdentity[id.toString()]?.distinct()?.singleOrNull() ?: return@count false
                if (evidence.therapyRevisionHash != revision || evidence.source != "COPILOT_UI" || evidence.revision != 1L)
                    return@count false
                val profile = MealAbsorptionProfile.entries.find { it.name == evidence.profile } ?: return@count false
                val portion = MealPortion.entries.find { it.name == evidence.portion } ?: return@count false
                val origin = MealPortionProvenance.entries.find { it.name == evidence.portionProvenance } ?: return@count false
                val grams = evidence.confirmedCarbsGrams ?: return@count false
                val observation = event.toPortionObservation(MealPortionEvidence(id.toString(), revision, grams,
                    portion, profile, origin, Instant.ofEpochMilli(evidence.updatedAtMs))) ?: return@count false
                observation.timestamp >= start && observation.timestamp < end && observation.availableAt <= knownAt
            }
            return PersonalAbsorptionHistoryAudit(carbRows.size, synthetic, independent, missing)
        }
    }
}
