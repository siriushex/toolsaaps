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

    @Query("SELECT * FROM therapy_events WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun sinceDescLimit(since: Long, limit: Int): List<TherapyEventEntity>

    @Query("SELECT * FROM therapy_events WHERE type = :type AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun byTypeSince(type: String, since: Long): List<TherapyEventEntity>

    @Query(
        "SELECT COUNT(*) FROM therapy_events " +
            "WHERE timestamp >= :since " +
            "AND type IN ('correction_bolus','meal_bolus','bolus','insulin')"
    )
    suspend fun countInsulinLikeSince(since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM therapy_events " +
            "WHERE timestamp >= :since " +
            "AND type IN ('correction_bolus','meal_bolus','bolus','insulin') " +
            "AND payloadJson NOT LIKE '%\"inferred\":\"true\"%' " +
            "AND payloadJson NOT LIKE '%\"source\":\"aaps_ns_iob\"%'"
    )
    suspend fun countInsulinLikeForBootstrapSince(since: Long): Int

    @Query("SELECT * FROM therapy_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<TherapyEventEntity>>

    @Query(
        "DELETE FROM therapy_events " +
            "WHERE rowid NOT IN (" +
            "SELECT MAX(rowid) FROM therapy_events GROUP BY timestamp, type, payloadJson" +
            ")"
    )
    suspend fun deleteDuplicateByTimestampTypePayload(): Int

    @Query("DELETE FROM therapy_events WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int

    @Query(
        "DELETE FROM therapy_events " +
            "WHERE id LIKE 'br-local_broadcast-%' " +
            "AND type IN ('correction_bolus','meal_bolus','carbs','temp_target')"
    )
    suspend fun deleteLegacyBroadcastArtifacts(): Int

    @Query("DELETE FROM therapy_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int
}
