package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<GlucoseSampleEntity>)

    @Query("SELECT * FROM glucose_samples ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<GlucoseSampleEntity>

    @Query("SELECT * FROM glucose_samples WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<GlucoseSampleEntity>

    @Query("SELECT * FROM glucose_samples ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<GlucoseSampleEntity>>

    @Query("SELECT MAX(timestamp) FROM glucose_samples")
    suspend fun maxTimestamp(): Long?

    @Query("SELECT * FROM glucose_samples ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestOne(): GlucoseSampleEntity?

    @Query("SELECT * FROM glucose_samples WHERE source = :source AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun bySourceSince(source: String, since: Long): List<GlucoseSampleEntity>

    @Query("SELECT * FROM glucose_samples WHERE source = :source AND timestamp = :timestamp LIMIT 1")
    suspend fun bySourceAndTimestamp(source: String, timestamp: Long): GlucoseSampleEntity?

    @Query("DELETE FROM glucose_samples WHERE source = :source AND timestamp = :timestamp")
    suspend fun deleteBySourceAndTimestamp(source: String, timestamp: Long): Int

    @Query(
        "DELETE FROM glucose_samples " +
            "WHERE source = :source " +
            "AND id NOT IN (" +
            "SELECT MAX(id) FROM glucose_samples WHERE source = :source GROUP BY timestamp" +
            ")"
    )
    suspend fun deleteDuplicateBySourceAndTimestamp(source: String): Int

    @Query("DELETE FROM glucose_samples WHERE source = :source AND mmol >= :thresholdMmol")
    suspend fun deleteBySourceAndThreshold(source: String, thresholdMmol: Double): Int
}
