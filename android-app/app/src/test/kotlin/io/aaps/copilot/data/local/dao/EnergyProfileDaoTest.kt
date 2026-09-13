package io.aaps.copilot.data.local.dao

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.PendingMealProfileIntentEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EnergyProfileDaoTest {

    @Test
    fun resolveMealOverrideForRevision_returnsMatchingOverrideAndRetainsIt() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val override = mealOverride(therapyRevisionHash = "revision-a")
        dao.mealOverrides[override.canonicalTherapyIdentity] = override

        val resolved = dao.resolveMealOverrideForRevision(
            canonicalTherapyIdentity = override.canonicalTherapyIdentity,
            expectedTherapyRevisionHash = "revision-a"
        )

        assertThat(resolved).isEqualTo(override)
        assertThat(dao.mealOverrides).containsExactly(override.canonicalTherapyIdentity, override)
        assertThat(dao.deletedMealOverrideIdentities).isEmpty()
    }

    @Test
    fun resolveMealOverrideForRevision_deletesStaleOverrideAndReturnsNull() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val override = mealOverride(therapyRevisionHash = "revision-a")
        dao.mealOverrides[override.canonicalTherapyIdentity] = override

        val resolved = dao.resolveMealOverrideForRevision(
            canonicalTherapyIdentity = override.canonicalTherapyIdentity,
            expectedTherapyRevisionHash = "revision-b"
        )

        assertThat(resolved).isNull()
        assertThat(dao.mealOverrides).isEmpty()
        assertThat(dao.deletedMealOverrideIdentities)
            .containsExactly(override.canonicalTherapyIdentity)
        Unit
    }

    private fun mealOverride(therapyRevisionHash: String): MealProfileOverrideEntity =
        MealProfileOverrideEntity(
            canonicalTherapyIdentity = "meal-1",
            therapyRevisionHash = therapyRevisionHash,
            profile = "LOWER",
            durationMinutes = 60,
            source = "MANUAL",
            revision = 1L,
            updatedAtMs = 2L
        )

    private class RecordingEnergyProfileDao : EnergyProfileDao {
        val mealOverrides = linkedMapOf<String, MealProfileOverrideEntity>()
        val deletedMealOverrideIdentities = mutableListOf<String>()

        override fun observeEnabledEvents(): Flow<List<PlannedActivityEventEntity>> = emptyFlow()

        override fun observeAllEvents(): Flow<List<PlannedActivityEventEntity>> = emptyFlow()

        override suspend fun allEvents(): List<PlannedActivityEventEntity> = emptyList()

        override suspend fun enabledEvents(): List<PlannedActivityEventEntity> = emptyList()

        override suspend fun enabledEventsLimited(limit: Int): List<PlannedActivityEventEntity> =
            enabledEvents().take(limit)

        override suspend fun alertCauseEnabledCandidatesPage(
            afterLocalStartIso: String?,
            afterEventId: String?,
            limit: Int
        ): List<PlannedActivityEventEntity> = emptyList()

        override suspend fun upsertEvent(event: PlannedActivityEventEntity) = Unit

        override suspend fun deleteEvent(eventId: String): Int = 0

        override suspend fun deleteAllEvents(): Int = 0

        override suspend fun insertEvents(events: List<PlannedActivityEventEntity>) = Unit

        override suspend fun matchingMealOverrideForIdentity(
            canonicalTherapyIdentity: String,
            expectedTherapyRevisionHash: String
        ): MealProfileOverrideEntity? = mealOverrides[canonicalTherapyIdentity]
            ?.takeIf { it.therapyRevisionHash == expectedTherapyRevisionHash }

        override suspend fun upsertMealOverride(override: MealProfileOverrideEntity) {
            mealOverrides[override.canonicalTherapyIdentity] = override
        }

        override suspend fun deleteMealOverride(canonicalTherapyIdentity: String): Int {
            deletedMealOverrideIdentities += canonicalTherapyIdentity
            return if (mealOverrides.remove(canonicalTherapyIdentity) != null) 1 else 0
        }

        override suspend fun deleteMealOverrideWithDifferentRevision(
            canonicalTherapyIdentity: String,
            expectedTherapyRevisionHash: String
        ): Int {
            val override = mealOverrides[canonicalTherapyIdentity]
                ?.takeIf { it.therapyRevisionHash != expectedTherapyRevisionHash }
                ?: return 0
            deletedMealOverrideIdentities += canonicalTherapyIdentity
            mealOverrides.remove(override.canonicalTherapyIdentity)
            return 1
        }

        override suspend fun upsertPendingMealProfileIntent(intent: PendingMealProfileIntentEntity) = Unit

        override suspend fun activePendingMealProfileIntents(nowMs: Long): List<PendingMealProfileIntentEntity> =
            emptyList()

        override suspend fun pendingMealProfileIntent(idempotencyKey: String): PendingMealProfileIntentEntity? =
            null

        override suspend fun deletePendingMealProfileIntent(idempotencyKey: String): Int = 0

        override suspend fun deleteExpiredPendingMealProfileIntents(nowMs: Long): Int = 0

        override fun observeLatestSnapshot(): Flow<EnergyProfileSnapshotEntity?> = emptyFlow()

        override suspend fun snapshot(snapshotId: String): EnergyProfileSnapshotEntity? = null

        override suspend fun upsertSnapshot(snapshot: EnergyProfileSnapshotEntity) = Unit

        override suspend fun trimSnapshots(): Int = 0
    }
}
