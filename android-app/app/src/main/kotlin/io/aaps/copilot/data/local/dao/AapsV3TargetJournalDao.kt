package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.aaps.copilot.data.local.entity.AapsV3TargetJournalEntity

/** Private state; not a clinical or AI export source. Only the journal may mutate it. */
@Dao
interface AapsV3TargetJournalDao {
    @Query("SELECT * FROM aaps_v3_target_journal WHERE row = :row")
    suspend fun get(row: String): AapsV3TargetJournalEntity?

    @Query("SELECT COUNT(*) FROM aaps_v3_target_journal")
    suspend fun count(): Long

    @Upsert suspend fun put(value: AapsV3TargetJournalEntity)
}
