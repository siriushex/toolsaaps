package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit offline requests only; not registered in the UI, runtime or any worker. */
internal class MealPortionSuggestionRepository(
    private val clock: Clock,
    private val loadHistory: suspend (Instant, Instant) -> List<MealPortionObservation>
) {
    private data class CacheKey(
        val history: Set<MealPortionObservation>,
        val settings: MealPortionSettings,
        val portion: MealPortion,
        val profile: MealAbsorptionProfile,
        val zone: ZoneId,
        val minute: Long
    )
    private val mutex = Mutex()
    private var cached: Pair<CacheKey, MealPortionEstimate>? = null

    suspend fun candidate(
        settings: MealPortionSettings,
        portion: MealPortion,
        profile: MealAbsorptionProfile,
        zone: ZoneId
    ): MealPortionEstimate = mutex.withLock {
        require(settings.isValid())
        val now = clock.instant()
        val start = now.minus(Duration.ofDays(14))
        val loaded = loadHistory(start, now)
        val bounded = loaded.takeIf { it.size <= MAX_HISTORY_ROWS } ?: emptyList()
        val history = bounded.groupBy { it.canonicalId }.values
            .mapNotNull { it.distinct().singleOrNull() }.filter {
            it.timestamp >= start && it.timestamp < now &&
                it.availableAt >= it.timestamp && it.availableAt <= now
        }
        val key = CacheKey(history.toSet(), settings, portion, profile, zone, now.epochSecond / 60)
        cached?.takeIf { it.first == key }?.second ?: MealPortionEstimator(Clock.fixed(now, zone))
            .estimate(history, settings, portion, profile, zone).also { cached = key to it }
    }

    companion object {
        private const val MAX_HISTORY_ROWS = 5000

        fun fromDatabase(db: CopilotDatabase, gson: Gson, clock: Clock): MealPortionSuggestionRepository =
            MealPortionSuggestionRepository(clock) { start, now ->
                db.withTransaction {
                    // Existing bounded canonical timeline reader. Overflow is a fallback, not a sample.
                    val rows = db.therapyDao().betweenForAlertAiTimeline(
                        start.toEpochMilli(), now.toEpochMilli() - 1, MAX_HISTORY_ROWS + 1)
                    if (rows.size > MAX_HISTORY_ROWS) return@withTransaction emptyList()
                    val events = rows.filter { it.type == "carbs" || it.type == "meal_bolus" }
                        .map { it.toDomain(gson).copy(sourceRowId = null) }
                    // An ambiguous reference may conceal an alias/tombstone. Do not revive it.
                    if (events.any { it.componentTrust.canonicalReferenceConflict })
                        return@withTransaction emptyList()
                    events.groupBy { it.componentTrust.canonicalCarbId }.mapNotNull { (id, versions) ->
                        if (id == null || id <= 0) return@mapNotNull null
                        val event = versions.distinct().singleOrNull() ?: return@mapNotNull null
                        val revision = event.componentTrust.canonicalCarbRevision ?: return@mapNotNull null
                        val override = db.energyProfileDao().matchingMealOverrideForIdentity(id.toString(), revision)
                            ?: return@mapNotNull null
                        if (override.source != "COPILOT_UI" || override.revision != 1L || override.updatedAtMs <= 0)
                            return@mapNotNull null
                        val portion = MealPortion.entries.find { it.name == override.portion } ?: return@mapNotNull null
                        val provenance = MealPortionProvenance.entries.find { it.name == override.portionProvenance }
                            ?: return@mapNotNull null
                        val profile = MealAbsorptionProfile.entries.find { it.name == override.profile }
                            ?: return@mapNotNull null
                        val grams = override.confirmedCarbsGrams ?: return@mapNotNull null
                        event.toPortionObservation(MealPortionEvidence(
                            id.toString(), revision, grams, portion, profile, provenance,
                            Instant.ofEpochMilli(override.updatedAtMs)))
                    }
                }
            }
    }
}
