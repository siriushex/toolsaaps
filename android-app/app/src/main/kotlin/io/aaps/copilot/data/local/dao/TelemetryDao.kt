package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<TelemetrySampleEntity>)

    @Query("SELECT * FROM telemetry_samples ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<TelemetrySampleEntity>>

    @Query("SELECT * FROM telemetry_samples WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<TelemetrySampleEntity>

    @Query("DELETE FROM telemetry_samples WHERE key = :key AND valueDouble > :threshold")
    suspend fun deleteByKeyAboveThreshold(key: String, threshold: Double): Int

    @Query(
        "DELETE FROM telemetry_samples " +
            "WHERE key = :key AND valueDouble IS NOT NULL AND (valueDouble < :minValue OR valueDouble > :maxValue)"
    )
    suspend fun deleteByKeyOutsideRange(key: String, minValue: Double, maxValue: Double): Int

    @Query(
        "DELETE FROM telemetry_samples " +
            "WHERE source = :source AND key = :key AND (valueDouble IS NULL OR valueDouble <= :threshold)"
    )
    suspend fun deleteBySourceAndKeyAtOrBelow(source: String, key: String, threshold: Double): Int
}
