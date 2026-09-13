package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.PendingMealProfileIntentEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EnergyProfileDao {

    @Query(
        "SELECT * FROM planned_activity_events WHERE enabled = 1 " +
            "ORDER BY localStartIso ASC, eventId ASC"
    )
    fun observeEnabledEvents(): Flow<List<PlannedActivityEventEntity>>

    @Query("SELECT * FROM planned_activity_events ORDER BY localStartIso ASC, eventId ASC")
    suspend fun allEvents(): List<PlannedActivityEventEntity>

    @Query("SELECT * FROM planned_activity_events ORDER BY localStartIso ASC, eventId ASC")
    fun observeAllEvents(): Flow<List<PlannedActivityEventEntity>>

    @Query(
        "SELECT * FROM planned_activity_events WHERE enabled = 1 " +
            "ORDER BY localStartIso ASC, eventId ASC"
    )
    suspend fun enabledEvents(): List<PlannedActivityEventEntity>

    @Query(
        "SELECT * FROM planned_activity_events WHERE enabled = 1 " +
            "ORDER BY localStartIso DESC, eventId ASC LIMIT :limit"
    )
    suspend fun enabledEventsLimited(limit: Int): List<PlannedActivityEventEntity>

    @Query(
        "SELECT * FROM planned_activity_events WHERE enabled = 1 " +
            "AND (:afterLocalStartIso IS NULL OR localStartIso > :afterLocalStartIso " +
            "OR (localStartIso = :afterLocalStartIso AND eventId > :afterEventId)) " +
            "ORDER BY localStartIso ASC, eventId ASC LIMIT :limit"
    )
    suspend fun alertCauseEnabledCandidatesPage(
        afterLocalStartIso: String?,
        afterEventId: String?,
        limit: Int
    ): List<PlannedActivityEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: PlannedActivityEventEntity)

    @Query("DELETE FROM planned_activity_events WHERE eventId = :eventId")
    suspend fun deleteEvent(eventId: String): Int

    @Query("DELETE FROM planned_activity_events")
    suspend fun deleteAllEvents(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<PlannedActivityEventEntity>)

    @Transaction
    suspend fun replaceEvents(events: List<PlannedActivityEventEntity>) {
        deleteAllEvents()
        if (events.isNotEmpty()) insertEvents(events)
    }

    @Query(
        "SELECT * FROM meal_profile_overrides " +
            "WHERE canonicalTherapyIdentity = :canonicalTherapyIdentity " +
            "AND therapyRevisionHash = :expectedTherapyRevisionHash LIMIT 1"
    )
    suspend fun matchingMealOverrideForIdentity(
        canonicalTherapyIdentity: String,
        expectedTherapyRevisionHash: String
    ): MealProfileOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMealOverride(override: MealProfileOverrideEntity)

    @Query(
        "DELETE FROM meal_profile_overrides " +
            "WHERE canonicalTherapyIdentity = :canonicalTherapyIdentity"
    )
    suspend fun deleteMealOverride(canonicalTherapyIdentity: String): Int

    @Query(
        "DELETE FROM meal_profile_overrides " +
            "WHERE canonicalTherapyIdentity = :canonicalTherapyIdentity " +
            "AND therapyRevisionHash != :expectedTherapyRevisionHash"
    )
    suspend fun deleteMealOverrideWithDifferentRevision(
        canonicalTherapyIdentity: String,
        expectedTherapyRevisionHash: String
    ): Int

    @Transaction
    suspend fun resolveMealOverrideForRevision(
        canonicalTherapyIdentity: String,
        expectedTherapyRevisionHash: String
    ): MealProfileOverrideEntity? {
        val matching = matchingMealOverrideForIdentity(
            canonicalTherapyIdentity,
            expectedTherapyRevisionHash
        )
        if (matching != null) return matching

        deleteMealOverrideWithDifferentRevision(
            canonicalTherapyIdentity,
            expectedTherapyRevisionHash
        )
        return null
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingMealProfileIntent(intent: PendingMealProfileIntentEntity)

    @Query(
        "SELECT * FROM pending_meal_profile_intents " +
            "WHERE expiresAtMs > :nowMs ORDER BY submittedAtMs ASC, idempotencyKey ASC"
    )
    suspend fun activePendingMealProfileIntents(nowMs: Long): List<PendingMealProfileIntentEntity>

    @Query(
        "SELECT * FROM pending_meal_profile_intents " +
            "WHERE idempotencyKey = :idempotencyKey LIMIT 1"
    )
    suspend fun pendingMealProfileIntent(idempotencyKey: String): PendingMealProfileIntentEntity?

    @Query(
        "DELETE FROM pending_meal_profile_intents WHERE idempotencyKey = :idempotencyKey"
    )
    suspend fun deletePendingMealProfileIntent(idempotencyKey: String): Int

    @Query("DELETE FROM pending_meal_profile_intents WHERE expiresAtMs <= :nowMs")
    suspend fun deleteExpiredPendingMealProfileIntents(nowMs: Long): Int

    /**
     * Re-reads the intent inside the transaction so concurrent runtime cycles
     * cannot both promote the same Copilot action.
     */
    @Transaction
    suspend fun promotePendingMealProfileIntent(
        expectedIntent: PendingMealProfileIntentEntity,
        override: MealProfileOverrideEntity,
        nowMs: Long
    ): Boolean {
        val current = pendingMealProfileIntent(expectedIntent.idempotencyKey)
        if (current != expectedIntent || current.expiresAtMs <= nowMs) return false
        upsertMealOverride(override)
        return deletePendingMealProfileIntent(expectedIntent.idempotencyKey) == 1
    }

    @Query(
        "SELECT * FROM energy_profile_snapshots " +
            "ORDER BY calculatedAtMs DESC, snapshotId DESC LIMIT 1"
    )
    fun observeLatestSnapshot(): Flow<EnergyProfileSnapshotEntity?>

    @Query("SELECT * FROM energy_profile_snapshots WHERE snapshotId = :snapshotId LIMIT 1")
    suspend fun snapshot(snapshotId: String): EnergyProfileSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: EnergyProfileSnapshotEntity)

    @Query(
        "DELETE FROM energy_profile_snapshots WHERE snapshotId NOT IN (" +
            "SELECT snapshotId FROM energy_profile_snapshots " +
            "ORDER BY calculatedAtMs DESC, snapshotId DESC LIMIT 90)"
    )
    suspend fun trimSnapshots(): Int

    @Transaction
    suspend fun insertSnapshotAndTrim(snapshot: EnergyProfileSnapshotEntity) {
        upsertSnapshot(snapshot)
        trimSnapshots()
    }
}
