package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE
import kotlinx.coroutines.flow.Flow

data class TelemetrySampleLite(
    val id: String,
    val timestamp: Long,
    val source: String,
    val key: String,
    val valueDouble: Double?,
    val valueText: String?,
    val unit: String?,
    val quality: String
)

data class ClinicalTelemetryProjection(
    val ts: Long,
    val key: String,
    val value: Double?,
    val quality: String,
    val rowId: String = "",
    val source: String = "",
    val unit: String? = null
)

data class PhysicalActivityBucketProjection(
    val bucketTs: Long,
    val firstTs: Long,
    val lastTs: Long,
    val peakRatio: Double,
    val meanRatio: Double,
    val sampleCount: Int,
    val source: String,
    val qualityEvidence: String,
    val trustedSampleCount: Int,
    val okSampleCount: Int
)

data class PhysicalActivityMetricBucketProjection(
    val bucketTs: Long,
    val firstTs: Long,
    val lastTs: Long,
    val key: String,
    val source: String,
    val firstValue: Double,
    val lastValue: Double,
    val minValue: Double,
    val maxValue: Double,
    val meanValue: Double,
    val sampleCount: Int,
    val qualityEvidence: String,
    val trustedSampleCount: Int,
    val okSampleCount: Int
)

private const val PHYSICAL_ACTIVITY_METRIC_BUCKET_QUERY =
    "WITH ranked AS (" +
        "SELECT timestamp, source, key, valueDouble, quality, id, " +
        "CASE WHEN (timestamp / :bucketMs) * :bucketMs < :firstBucketTs " +
        "THEN :firstBucketTs ELSE (timestamp / :bucketMs) * :bucketMs END AS bucketTs, " +
        "ROW_NUMBER() OVER (" +
        "PARTITION BY source, key, CASE WHEN (timestamp / :bucketMs) * :bucketMs < :firstBucketTs " +
        "THEN :firstBucketTs ELSE (timestamp / :bucketMs) * :bucketMs END " +
        "ORDER BY timestamp ASC, id ASC) AS firstRank, " +
        "ROW_NUMBER() OVER (" +
        "PARTITION BY source, key, CASE WHEN (timestamp / :bucketMs) * :bucketMs < :firstBucketTs " +
        "THEN :firstBucketTs ELSE (timestamp / :bucketMs) * :bucketMs END " +
        "ORDER BY timestamp DESC, id DESC) AS lastRank " +
        "FROM telemetry_samples " +
        "WHERE timestamp >= :fromTs AND timestamp < :toTsExclusive " +
        "AND key IN (:keys) AND source IN (:sources) " +
        "AND (key != 'activity_ratio' OR timestamp >= :firstBucketTs) " +
        "AND UPPER(TRIM(quality)) IN (:qualities) " +
        "AND valueDouble IS NOT NULL AND (" +
        "(key = 'activity_ratio' AND valueDouble BETWEEN 0.2 AND 3.0) OR " +
        "(key = 'steps_count' AND valueDouble BETWEEN 0.0 AND 150000.0) OR " +
        "(key = 'distance_km' AND valueDouble BETWEEN 0.0 AND 250.0) OR " +
        "(key = 'active_minutes' AND valueDouble BETWEEN 0.0 AND 1440.0) OR " +
        "(key = 'calories_active_kcal' AND valueDouble BETWEEN 0.0 AND 12000.0)" +
        ")" +
        ") " +
        "SELECT bucketTs, MIN(timestamp) AS firstTs, MAX(timestamp) AS lastTs, key, source, " +
        "MAX(CASE WHEN firstRank = 1 THEN valueDouble END) AS firstValue, " +
        "MAX(CASE WHEN lastRank = 1 THEN valueDouble END) AS lastValue, " +
        "MIN(valueDouble) AS minValue, MAX(valueDouble) AS maxValue, " +
        "AVG(valueDouble) AS meanValue, COUNT(*) AS sampleCount, " +
        "CASE WHEN SUM(CASE WHEN UPPER(TRIM(quality)) = 'TRUSTED' THEN 1 ELSE 0 END) > 0 " +
        "THEN 'TRUSTED' ELSE 'OK' END AS qualityEvidence, " +
        "SUM(CASE WHEN UPPER(TRIM(quality)) = 'TRUSTED' THEN 1 ELSE 0 END) AS trustedSampleCount, " +
        "SUM(CASE WHEN UPPER(TRIM(quality)) = 'OK' THEN 1 ELSE 0 END) AS okSampleCount " +
        "FROM ranked GROUP BY source, key, bucketTs " +
        "ORDER BY bucketTs ASC, source ASC, key ASC"

@Dao
interface TelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<TelemetrySampleEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(samples: List<TelemetrySampleEntity>): List<Long>

    @Query("SELECT * FROM telemetry_samples WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TelemetrySampleEntity?

    @Query("SELECT * FROM telemetry_samples WHERE id IN (:ids) ORDER BY id ASC")
    suspend fun byIds(ids: List<String>): List<TelemetrySampleEntity>

    @Transaction
    suspend fun upsertPhysicalActivityMinute(samples: List<TelemetrySampleEntity>) {
        samples.forEach { raw ->
            require(raw.key in PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS) {
                "Physical activity minute storage accepts canonical persisted metrics only"
            }
            require(
                PhysicalActivityTelemetryPolicy.isTrustedSourceAndQuality(raw.source, raw.quality)
            ) { "Physical activity minute storage accepts trusted local sources only" }
            val incoming = raw.copy(
                id = PhysicalActivityTelemetryPolicy.minuteDurableId(raw.source, raw.key, raw.timestamp)
            )
            val existing = byId(incoming.id)
            val latest = when {
                existing == null -> incoming
                incoming.timestamp >= existing.timestamp -> incoming
                else -> existing
            }
            upsertAll(listOf(latest))
        }
    }

    @Transaction
    suspend fun upsertAcceptedSensitivityTuple(samples: List<TelemetrySampleEntity>) {
        val requiredKeys = setOf(
            SENSITIVITY_ACCEPTED_CYCLE_ID_KEY,
            SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
            SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
        )
        require(samples.size == requiredKeys.size)
        require(samples.all { it.source == SENSITIVITY_ACCEPTED_SOURCE })
        require(samples.map { it.timestamp }.distinct().size == 1)
        require(samples.map { it.key }.toSet() == requiredKeys)
        require(
            samples.single { it.key == SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY }.valueText in
                setOf(SENSITIVITY_ACCEPTED_PUBLICATION_PENDING, SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED)
        )
        deleteBySourceAndTimestamp(
            source = SENSITIVITY_ACCEPTED_SOURCE,
            timestamp = samples.first().timestamp
        )
        upsertAll(samples)
    }

    @Transaction
    suspend fun commitAcceptedSensitivityTuple(
        timestamp: Long,
        cycleId: String,
        settingsRevision: Long
    ): Boolean {
        val keys = listOf(
            SENSITIVITY_ACCEPTED_CYCLE_ID_KEY,
            SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
            SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
        )
        val rows = atTimestampBySourceAndKeys(SENSITIVITY_ACCEPTED_SOURCE, timestamp, keys)
        val groups = rows.groupBy(TelemetrySampleEntity::key)
        if (groups.keys != keys.toSet() || groups.values.any { it.size != 1 }) return false
        if (groups.getValue(SENSITIVITY_ACCEPTED_CYCLE_ID_KEY).single().valueText != cycleId) return false
        val revision = groups.getValue(SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY).single().valueDouble
        if (revision == null || revision != settingsRevision.toDouble()) return false
        val state = groups.getValue(SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY).single()
        if (state.valueText != SENSITIVITY_ACCEPTED_PUBLICATION_PENDING) return false
        upsertAll(listOf(state.copy(valueText = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED)))
        return true
    }

    @Query("DELETE FROM telemetry_samples WHERE source = :source AND timestamp = :timestamp")
    suspend fun deleteBySourceAndTimestamp(source: String, timestamp: Long): Int

    @Query("SELECT * FROM telemetry_samples ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<TelemetrySampleEntity>>

    @Query("SELECT * FROM telemetry_samples WHERE key IN (:keys) ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatestByKeys(limit: Int, keys: List<String>): Flow<List<TelemetrySampleEntity>>

    @Query("SELECT * FROM telemetry_samples WHERE timestamp >= :since AND key IN (:keys) ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatestByKeysSince(
        limit: Int,
        keys: List<String>,
        since: Long
    ): Flow<List<TelemetrySampleEntity>>

    @Query(
        "SELECT * FROM telemetry_samples " +
            "WHERE timestamp BETWEEN :fromTs AND :throughTs " +
            "AND (key = :stableKey OR (key = :legacyKey AND source = :legacySource)) " +
            "ORDER BY timestamp ASC, source ASC, key ASC, id ASC"
    )
    fun observeDeliveryTrustInWindow(
        stableKey: String,
        legacyKey: String,
        legacySource: String,
        fromTs: Long,
        throughTs: Long
    ): Flow<List<TelemetrySampleEntity>>

    @Query(
        "SELECT * FROM telemetry_samples WHERE key IN (:keys) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    fun observeCalibrationSessionEvidence(
        keys: List<String>,
        limit: Int
    ): Flow<List<TelemetrySampleEntity>>

    @Query("SELECT * FROM telemetry_samples WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<TelemetrySampleEntity>

    @Query("SELECT * FROM telemetry_samples WHERE timestamp >= :since AND key IN (:keys) ORDER BY timestamp ASC")
    suspend fun sinceByKeys(since: Long, keys: List<String>): List<TelemetrySampleEntity>

    @Query(
        "SELECT * FROM telemetry_samples " +
            "WHERE timestamp BETWEEN :sinceTs AND :atTs AND key IN (:keys) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun latestByKeysInWindow(
        keys: List<String>,
        sinceTs: Long,
        atTs: Long,
        limit: Int
    ): List<TelemetrySampleEntity>

    @Query(
        "SELECT timestamp AS ts, key, valueDouble AS value, quality, " +
            "id AS rowId, source, unit FROM telemetry_samples " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs AND key IN (:keys) " +
            "ORDER BY timestamp ASC, key ASC, id ASC"
    )
    suspend fun betweenForClinicalReport(
        fromTs: Long,
        toTs: Long,
        keys: List<String>
    ): List<ClinicalTelemetryProjection>

    @Query(
        "SELECT timestamp AS ts, key, valueDouble AS value, quality, " +
            "id AS rowId, source, unit FROM telemetry_samples " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs AND key IN (:keys) " +
            "ORDER BY timestamp ASC, key ASC, id ASC LIMIT :limit"
    )
    suspend fun betweenForAlertAi(
        fromTs: Long,
        toTs: Long,
        keys: List<String>,
        limit: Int
    ): List<ClinicalTelemetryProjection>

    @Query(
        "SELECT timestamp AS ts, key, valueDouble AS value, quality, " +
            "id AS rowId, source, unit FROM telemetry_samples " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs AND key IN (:keys) " +
            "AND source IN (:sources) AND UPPER(TRIM(quality)) IN (:qualities) " +
            "ORDER BY timestamp ASC, key ASC, id ASC"
    )
    suspend fun trustedPhysicalActivityForClinicalReport(
        fromTs: Long,
        toTs: Long,
        keys: List<String>,
        sources: List<String>,
        qualities: List<String>
    ): List<ClinicalTelemetryProjection>

    @Query(
        "SELECT * FROM telemetry_samples " +
            "WHERE source = :source AND key = :key AND timestamp <= :atTs " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1"
    )
    suspend fun latestBySourceAndKeyAtOrBefore(
        source: String,
        key: String,
        atTs: Long
    ): TelemetrySampleEntity?

    @Query(
        "SELECT * FROM telemetry_samples " +
            "WHERE source = :source AND key = :key " +
            "ORDER BY rowid DESC LIMIT 1"
    )
    fun observeCurrentBySourceAndKey(
        source: String,
        key: String
    ): Flow<TelemetrySampleEntity?>

    @Query(
        "SELECT * FROM telemetry_samples " +
            "WHERE source = :source AND key = :key " +
            "ORDER BY rowid DESC LIMIT 1"
    )
    suspend fun currentBySourceAndKey(
        source: String,
        key: String
    ): TelemetrySampleEntity?

    @Query(
        "SELECT MAX(timestamp) FROM telemetry_samples " +
            "WHERE source = :source AND key = :key"
    )
    suspend fun latestTimestampBySourceAndKey(source: String, key: String): Long?

    @Query(
        "SELECT timestamp FROM telemetry_samples " +
            "WHERE source = :source AND key = :publicationStateKey " +
            "AND valueText = :committedState AND timestamp = :markerTs " +
            "LIMIT 1"
    )
    suspend fun committedMarkerTimestampExact(
        source: String,
        publicationStateKey: String,
        committedState: String,
        markerTs: Long
    ): Long?

    @Query(
        "SELECT timestamp FROM telemetry_samples " +
            "WHERE source = :source AND key = :publicationStateKey " +
            "AND valueText = :committedState AND timestamp <= :atTs " +
            "AND (:beforeTimestamp IS NULL OR timestamp < :beforeTimestamp) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun committedMarkerTimestampsPageAtOrBefore(
        source: String,
        publicationStateKey: String,
        committedState: String,
        atTs: Long,
        beforeTimestamp: Long?,
        limit: Int
    ): List<Long>

    @Query(
        "SELECT * FROM telemetry_samples " +
            "WHERE source = :source AND timestamp = :timestamp AND key IN (:keys) " +
            "ORDER BY key ASC, id ASC"
    )
    suspend fun atTimestampBySourceAndKeys(
        source: String,
        timestamp: Long,
        keys: List<String>
    ): List<TelemetrySampleEntity>

    @Query(
        "SELECT * FROM telemetry_samples " +
            "WHERE source = :source AND timestamp = :timestamp AND key IN (:keys) " +
            "ORDER BY key ASC, id ASC LIMIT :limit"
    )
    suspend fun atTimestampBySourceAndKeysForAlertAi(
        source: String,
        timestamp: Long,
        keys: List<String>,
        limit: Int
    ): List<TelemetrySampleEntity>

    @Query(
        "SELECT id, timestamp, source, key, valueDouble, valueText, unit, quality FROM telemetry_samples " +
            "WHERE timestamp >= :since AND key IN (:keys) " +
            "AND (timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND id > :afterId)) " +
            "ORDER BY timestamp ASC, id ASC LIMIT :limit"
    )
    suspend fun sinceByKeysPage(
        since: Long,
        keys: List<String>,
        afterTimestamp: Long,
        afterId: String,
        limit: Int
    ): List<TelemetrySampleLite>

    @Query(
        "SELECT id, timestamp, source, key, valueDouble, valueText, unit, quality FROM telemetry_samples " +
            "WHERE timestamp BETWEEN :since AND :through AND key IN (:keys) " +
            "AND (timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND id > :afterId)) " +
            "ORDER BY timestamp ASC, id ASC LIMIT :limit"
    )
    suspend fun betweenByKeysPage(
        since: Long,
        through: Long,
        keys: List<String>,
        afterTimestamp: Long,
        afterId: String,
        limit: Int
    ): List<TelemetrySampleLite>

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
            "SELECT source, key, MAX(timestamp) AS maxTimestamp " +
            "FROM telemetry_samples " +
            "WHERE timestamp >= :since AND key IN (:keys) " +
            "GROUP BY source, key" +
            ") latest " +
            "ON t.source = latest.source AND t.key = latest.key AND t.timestamp = latest.maxTimestamp " +
            "ORDER BY t.timestamp ASC"
    )
    suspend fun latestBySourceAndKeySinceForKeys(
        since: Long,
        keys: List<String>
    ): List<TelemetrySampleEntity>

    @Query(
        "SELECT t.* FROM telemetry_samples t " +
            "INNER JOIN (" +
            "SELECT source, key, MAX(timestamp) AS maxTimestamp " +
            "FROM telemetry_samples " +
            "WHERE timestamp BETWEEN :since AND :through AND key IN (:keys) " +
            "GROUP BY source, key" +
            ") latest " +
            "ON t.source = latest.source AND t.key = latest.key AND t.timestamp = latest.maxTimestamp " +
            "ORDER BY t.timestamp ASC"
    )
    suspend fun latestBySourceAndKeyBetweenForKeys(
        since: Long,
        through: Long,
        keys: List<String>
    ): List<TelemetrySampleEntity>

    @Query(
        "SELECT t.* FROM telemetry_samples t " +
            "INNER JOIN (" +
            "SELECT key, MAX(timestamp) AS maxTimestamp " +
            "FROM telemetry_samples " +
            "WHERE timestamp >= :since AND key IN (:keys) " +
            "GROUP BY key" +
            ") latest " +
            "ON t.key = latest.key AND t.timestamp = latest.maxTimestamp " +
            "ORDER BY t.timestamp ASC"
    )
    suspend fun latestByKeysSince(since: Long, keys: List<String>): List<TelemetrySampleEntity>

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

    @Query(
        "SELECT * FROM telemetry_samples " +
            "WHERE key = 'activity_ratio' " +
            "AND source IN ('local_sensor', 'health_connect') " +
            "AND UPPER(COALESCE(quality, '')) IN ('OK', 'TRUSTED') " +
            "AND timestamp BETWEEN :since AND :through " +
            "AND valueDouble IS NOT NULL " +
            "ORDER BY timestamp DESC, " +
            "CASE source WHEN 'health_connect' THEN 0 WHEN 'local_sensor' THEN 1 ELSE 2 END, id DESC " +
            "LIMIT 1"
    )
    suspend fun latestPhysicalActivityRatioSince(
        since: Long,
        through: Long
    ): TelemetrySampleEntity?

    @Query(
        "SELECT (timestamp / :bucketMs) * :bucketMs AS bucketTs, " +
            "MIN(timestamp) AS firstTs, MAX(timestamp) AS lastTs, " +
            "MAX(valueDouble) AS peakRatio, AVG(valueDouble) AS meanRatio, " +
            "COUNT(*) AS sampleCount, source, " +
            "CASE WHEN SUM(CASE WHEN UPPER(TRIM(quality)) = 'TRUSTED' THEN 1 ELSE 0 END) > 0 " +
            "THEN 'TRUSTED' ELSE 'OK' END AS qualityEvidence, " +
            "SUM(CASE WHEN UPPER(TRIM(quality)) = 'TRUSTED' THEN 1 ELSE 0 END) AS trustedSampleCount, " +
            "SUM(CASE WHEN UPPER(TRIM(quality)) = 'OK' THEN 1 ELSE 0 END) AS okSampleCount " +
            "FROM telemetry_samples " +
            "WHERE timestamp >= :fromTs AND timestamp < :toTsExclusive " +
            "AND key = :key AND source IN (:sources) " +
            "AND UPPER(TRIM(quality)) IN (:qualities) " +
            "AND valueDouble IS NOT NULL AND valueDouble BETWEEN 0.2 AND 3.0 " +
            "GROUP BY source, (timestamp / :bucketMs) " +
            "ORDER BY bucketTs ASC, source ASC"
    )
    suspend fun physicalActivity5MinuteBuckets(
        fromTs: Long,
        toTsExclusive: Long,
        key: String,
        sources: List<String>,
        qualities: List<String>,
        bucketMs: Long
    ): List<PhysicalActivityBucketProjection>

    @Query(PHYSICAL_ACTIVITY_METRIC_BUCKET_QUERY)
    suspend fun physicalActivityMetric5MinuteBuckets(
        fromTs: Long,
        toTsExclusive: Long,
        firstBucketTs: Long,
        keys: List<String>,
        sources: List<String>,
        qualities: List<String>,
        bucketMs: Long
    ): List<PhysicalActivityMetricBucketProjection>

    @Query(PHYSICAL_ACTIVITY_METRIC_BUCKET_QUERY + " LIMIT :limit")
    suspend fun physicalActivityMetric5MinuteBucketsForAlertAi(
        fromTs: Long,
        toTsExclusive: Long,
        firstBucketTs: Long,
        keys: List<String>,
        sources: List<String>,
        qualities: List<String>,
        bucketMs: Long,
        limit: Int
    ): List<PhysicalActivityMetricBucketProjection>

    @Query(
        "SELECT (timestamp / :bucketMs) * :bucketMs AS bucketTs, " +
            "MIN(timestamp) AS firstTs, MAX(timestamp) AS lastTs, " +
            "MAX(valueDouble) AS peakRatio, AVG(valueDouble) AS meanRatio, " +
            "COUNT(*) AS sampleCount, source, " +
            "CASE WHEN SUM(CASE WHEN UPPER(TRIM(quality)) = 'TRUSTED' THEN 1 ELSE 0 END) > 0 " +
            "THEN 'TRUSTED' ELSE 'OK' END AS qualityEvidence, " +
            "SUM(CASE WHEN UPPER(TRIM(quality)) = 'TRUSTED' THEN 1 ELSE 0 END) AS trustedSampleCount, " +
            "SUM(CASE WHEN UPPER(TRIM(quality)) = 'OK' THEN 1 ELSE 0 END) AS okSampleCount " +
            "FROM telemetry_samples " +
            "WHERE timestamp >= :fromTs AND timestamp < :toTsExclusive " +
            "AND key = :key AND source IN (:sources) " +
            "AND UPPER(TRIM(quality)) IN (:qualities) " +
            "AND valueDouble IS NOT NULL AND valueDouble BETWEEN 0.2 AND 3.0 " +
            "GROUP BY source, (timestamp / :bucketMs) " +
            "ORDER BY bucketTs ASC, source ASC LIMIT :limit"
    )
    suspend fun physicalActivity5MinuteBucketsForAlertAi(
        fromTs: Long,
        toTsExclusive: Long,
        key: String,
        sources: List<String>,
        qualities: List<String>,
        bucketMs: Long,
        limit: Int
    ): List<PhysicalActivityBucketProjection>

    @Query(PHYSICAL_ACTIVITY_METRIC_BUCKET_QUERY)
    fun observePhysicalActivityMetric5MinuteBuckets(
        fromTs: Long,
        toTsExclusive: Long,
        firstBucketTs: Long,
        keys: List<String>,
        sources: List<String>,
        qualities: List<String>,
        bucketMs: Long
    ): Flow<List<PhysicalActivityMetricBucketProjection>>

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

    @Query(
        "DELETE FROM telemetry_samples " +
            "WHERE timestamp >= :since AND rowid NOT IN (" +
            "SELECT MAX(rowid) FROM telemetry_samples " +
            "WHERE timestamp >= :since " +
            "GROUP BY source, key, timestamp, " +
            "COALESCE(valueDouble, -1.0E308), COALESCE(valueText, ''), COALESCE(unit, ''), quality" +
            ")"
    )
    suspend fun deleteDuplicateRowsSince(since: Long): Int

    @Query("DELETE FROM telemetry_samples WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int

    @Query("DELETE FROM telemetry_samples WHERE timestamp < :olderThan AND key LIKE :keyPattern")
    suspend fun deleteOlderThanByKeyPattern(olderThan: Long, keyPattern: String): Int

    @Query("DELETE FROM telemetry_samples WHERE timestamp < :olderThan AND key IN (:keys)")
    suspend fun deleteOlderThanByKeys(olderThan: Long, keys: List<String>): Int

    @Query(
        "DELETE FROM telemetry_samples " +
            "WHERE (" +
            "timestamp < :generalOlderThan " +
            "AND key NOT LIKE 'daily_report_%' " +
            "AND key NOT LIKE 'rolling_report_%' " +
            "AND key NOT LIKE 'insulin_profile_real_%'" +
            ") OR (" +
            "timestamp < :reportOlderThan " +
            "AND (" +
            "key LIKE 'daily_report_%' " +
            "OR key LIKE 'rolling_report_%' " +
            "OR key LIKE 'insulin_profile_real_%'" +
            ")" +
            ")"
    )
    suspend fun deleteOlderThanWithReportAndProfileRetention(
        generalOlderThan: Long,
        reportOlderThan: Long
    ): Int

    @Query(
        "DELETE FROM telemetry_samples " +
            "WHERE rowid IN (" +
            "SELECT rowid FROM telemetry_samples " +
            "WHERE (" +
            "timestamp < :generalOlderThan " +
            "AND key NOT LIKE 'daily_report_%' " +
            "AND key NOT LIKE 'rolling_report_%' " +
            "AND key NOT LIKE 'insulin_profile_real_%'" +
            ") OR (" +
            "timestamp < :reportOlderThan " +
            "AND (" +
            "key LIKE 'daily_report_%' " +
            "OR key LIKE 'rolling_report_%' " +
            "OR key LIKE 'insulin_profile_real_%'" +
            ")" +
            ") " +
            "ORDER BY timestamp ASC " +
            "LIMIT :limit" +
            ")"
    )
    suspend fun deleteOlderThanWithReportAndProfileRetentionLimit(
        generalOlderThan: Long,
        reportOlderThan: Long,
        limit: Int
    ): Int

    @Query(
        "DELETE FROM telemetry_samples WHERE rowid IN (" +
            "SELECT rowid FROM telemetry_samples WHERE " +
            "NOT (" +
            "id = 'copilot-local-safety-clock-high-water-v1' " +
            "AND timestamp BETWEEN 1 AND 253402300799999 " +
            "AND source = 'copilot_local_safety_clock' " +
            "AND key = 'wall_clock_high_water_ms' " +
            "AND valueDouble IS NOT NULL " +
            "AND valueDouble BETWEEN 0 AND 9007199254740992 " +
            "AND CAST(valueDouble AS INTEGER) = valueDouble " +
            "AND valueText = CAST(timestamp AS TEXT) " +
            "AND unit = 'epoch_ms|elapsed_ms_v1' " +
            "AND quality = 'OK'" +
            ") AND " +
            "(" +
            "(" +
            "key IN (:physicalActivityKeys) AND source IN (:physicalActivitySources) " +
            "AND UPPER(TRIM(quality)) IN (:physicalActivityQualities) " +
            "AND timestamp < :physicalActivityOlderThan" +
            ") OR (" +
            "NOT (key IN (:physicalActivityKeys) AND source IN (:physicalActivitySources) " +
            "AND UPPER(TRIM(quality)) IN (:physicalActivityQualities)) " +
            "AND timestamp < :generalOlderThan " +
            "AND key NOT LIKE 'daily_report_%' " +
            "AND key NOT LIKE 'rolling_report_%' " +
            "AND key NOT LIKE 'insulin_profile_real_%'" +
            ") OR (" +
            "timestamp < :reportOlderThan AND (" +
            "key LIKE 'daily_report_%' OR key LIKE 'rolling_report_%' " +
            "OR key LIKE 'insulin_profile_real_%'" +
            ")" +
            ")" +
            ") ORDER BY timestamp ASC LIMIT :limit)"
    )
    suspend fun deleteOlderThanWithReportProfileAndPhysicalActivityRetentionLimit(
        generalOlderThan: Long,
        reportOlderThan: Long,
        physicalActivityOlderThan: Long,
        physicalActivityKeys: List<String>,
        physicalActivitySources: List<String>,
        physicalActivityQualities: List<String>,
        limit: Int
    ): Int
}
