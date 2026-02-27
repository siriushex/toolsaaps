package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TherapyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<TherapyEventEntity>)

    @Query("SELECT * FROM therapy_events WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<TherapyEventEntity>

    @Query("SELECT * FROM therapy_events WHERE type = :type AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun byTypeSince(type: String, since: Long): List<TherapyEventEntity>

    @Query("SELECT * FROM therapy_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<TherapyEventEntity>>

    @Query(
        "DELETE FROM therapy_events " +
            "WHERE (id LIKE 'br-aaps_broadcast-%' OR id LIKE 'br-local_broadcast-%') " +
            "AND type IN ('correction_bolus','meal_bolus','carbs','temp_target')"
    )
    suspend fun deleteLegacyBroadcastArtifacts(): Int
}
