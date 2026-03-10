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

    @Query("SELECT * FROM telemetry_samples WHERE key IN (:keys) ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatestByKeys(limit: Int, keys: List<String>): Flow<List<TelemetrySampleEntity>>

    @Query("SELECT * FROM telemetry_samples WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<TelemetrySampleEntity>

    @Query("SELECT * FROM telemetry_samples WHERE timestamp >= :since AND key IN (:keys) ORDER BY timestamp ASC")
    suspend fun sinceByKeys(since: Long, keys: List<String>): List<TelemetrySampleEntity>

    @Query("SELECT * FROM telemetry_samples WHERE timestamp >= :since AND key IN (:keys) ORDER BY timestamp DESC LIMIT :limit")
    suspend fun sinceByKeysDescLimit(
        since: Long,
        keys: List<String>,
        limit: Int
    ): List<TelemetrySampleEntity>

    @Query(
        "SELECT t.* FROM telemetry_samples t " +
            "INNER JOIN (" +
            "SELECT source, key, MAX(timestamp) AS maxTimestamp " +
            "FROM telemetry_samples " +
            "WHERE timestamp >= :since " +
            "GROUP BY source, key" +
            ") latest " +
            "ON t.source = latest.source AND t.key = latest.key AND t.timestamp = latest.maxTimestamp " +
            "ORDER BY t.timestamp ASC"
    )
    suspend fun latestBySourceAndKeySince(since: Long): List<TelemetrySampleEntity>

    @Query(
        "SELECT t.* FROM telemetry_samples t " +
            "INNER JOIN (" +
            "SELECT key, MAX(timestamp) AS maxTimestamp " +
            "FROM telemetry_samples " +
            "WHERE timestamp >= :since " +
            "AND (" +
            "key LIKE 'daily_report_%' OR " +
            "key LIKE 'rolling_report_%' OR " +
            "key LIKE 'insulin_profile_real_%'" +
            ") " +
            "GROUP BY key" +
            ") latest " +
            "ON t.key = latest.key AND t.timestamp = latest.maxTimestamp " +
            "ORDER BY t.timestamp ASC"
    )
    suspend fun latestReportAndProfileSince(since: Long): List<TelemetrySampleEntity>

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

    @Query("DELETE FROM telemetry_samples WHERE timestamp <= :maxInvalidTimestamp")
    suspend fun deleteByTimestampAtOrBelow(maxInvalidTimestamp: Long): Int

    @Query("DELETE FROM telemetry_samples WHERE source = :source AND key LIKE :likePattern")
    suspend fun deleteBySourceAndKeyLike(source: String, likePattern: String): Int

    @Query(
        "DELETE FROM telemetry_samples " +
            "WHERE rowid NOT IN (" +
            "SELECT MAX(rowid) FROM telemetry_samples " +
            "GROUP BY source, key, timestamp, " +
            "COALESCE(valueDouble, -1.0E308), COALESCE(valueText, ''), COALESCE(unit, ''), quality" +
            ")"
    )
    suspend fun deleteDuplicateRows(): Int

    @Query("DELETE FROM telemetry_samples WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int

    @Query("DELETE FROM telemetry_samples WHERE timestamp < :olderThan AND key LIKE :keyPattern")
    suspend fun deleteOlderThanByKeyPattern(olderThan: Long, keyPattern: String): Int

    @Query(
        "DELETE FROM telemetry_samples " +
            "WHERE timestamp < :olderThan " +
            "AND key NOT LIKE 'daily_report_%' " +
            "AND key NOT LIKE 'rolling_report_%' " +
            "AND key NOT LIKE 'insulin_profile_real_%'"
    )
    suspend fun deleteOlderThanExcludingReportAndProfile(olderThan: Long): Int
}
