package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import kotlinx.coroutines.flow.Flow

data class ClinicalGlucoseProjection(
    val ts: Long,
    val mmol: Double,
    val source: String,
    val quality: String,
    val rowId: Long = 0L
)

@Dao
interface GlucoseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<GlucoseSampleEntity>)

    @Query("SELECT * FROM glucose_samples ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<GlucoseSampleEntity>

    @Query(
        "SELECT * FROM glucose_samples " +
            "WHERE timestamp <= :atTs " +
            "AND " + ROW_VALID_SQL + " " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM glucose_samples AS candidate " +
            "WHERE candidate.timestamp = glucose_samples.timestamp " +
            "AND " + CANDIDATE_VALID_SQL + " " +
            "AND (" +
            CANDIDATE_PRIORITY_SQL + " > " + ROW_PRIORITY_SQL +
            " OR (" +
            CANDIDATE_PRIORITY_SQL + " = " + ROW_PRIORITY_SQL +
            " AND candidate.id > glucose_samples.id" +
            ")" +
            ")" +
            ") " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun latestValidDistinctAtOrBefore(atTs: Long, limit: Int): List<GlucoseSampleEntity>

    @Query("SELECT * FROM glucose_samples WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<GlucoseSampleEntity>

    @Query(
        "SELECT * FROM glucose_samples " +
            "WHERE timestamp BETWEEN :since AND :through ORDER BY timestamp ASC"
    )
    suspend fun between(since: Long, through: Long): List<GlucoseSampleEntity>

    @Query(
        "SELECT DISTINCT timestamp FROM glucose_samples " +
            "WHERE timestamp BETWEEN COALESCE((SELECT MAX(timestamp) FROM glucose_samples " +
            "WHERE timestamp < :since AND " + ROW_VALID_SQL + "), :since) AND :through " +
            "AND " + ROW_VALID_SQL + " " +
            "ORDER BY timestamp ASC LIMIT :limit"
    )
    suspend fun validTimestampsForCalibrationSession(since: Long, through: Long, limit: Int): List<Long>

    @Query(
        "SELECT timestamp AS ts, mmol, source, quality, id AS rowId FROM glucose_samples " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs ORDER BY timestamp ASC, id ASC"
    )
    suspend fun betweenForClinicalReport(
        fromTs: Long,
        toTs: Long
    ): List<ClinicalGlucoseProjection>

    @Query(
        "SELECT timestamp AS ts, mmol, source, quality, id AS rowId FROM glucose_samples " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs " +
            "ORDER BY timestamp ASC, id ASC LIMIT :limit"
    )
    suspend fun betweenForAlertAi(
        fromTs: Long,
        toTs: Long,
        limit: Int
    ): List<ClinicalGlucoseProjection>

    @Query("SELECT * FROM glucose_samples WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun sinceDescLimit(since: Long, limit: Int): List<GlucoseSampleEntity>

    @Query("SELECT * FROM glucose_samples ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<GlucoseSampleEntity>>

    @Query("SELECT MAX(timestamp) FROM glucose_samples")
    suspend fun maxTimestamp(): Long?

    @Query("SELECT MIN(timestamp) FROM glucose_samples")
    suspend fun minTimestamp(): Long?

    @Query("SELECT MIN(timestamp) FROM glucose_samples")
    fun observeMinTimestamp(): Flow<Long?>

    @Query("SELECT MAX(timestamp) FROM glucose_samples")
    fun observeMaxTimestamp(): Flow<Long?>

    @Query("SELECT * FROM glucose_samples ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestOne(): GlucoseSampleEntity?

    @Query("SELECT * FROM glucose_samples WHERE source = :source AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun bySourceSince(source: String, since: Long): List<GlucoseSampleEntity>

    @Query("SELECT * FROM glucose_samples WHERE source = :source AND timestamp = :timestamp LIMIT 1")
    suspend fun bySourceAndTimestamp(source: String, timestamp: Long): GlucoseSampleEntity?

    @Query("DELETE FROM glucose_samples WHERE source = :source AND timestamp = :timestamp")
    suspend fun deleteBySourceAndTimestamp(source: String, timestamp: Long): Int

    @Query("DELETE FROM glucose_samples WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

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

    @Query(
        "DELETE FROM glucose_samples " +
            "WHERE (" +
            "(NOT " + ROW_PREFERRED_SQL + " AND EXISTS (" +
            "SELECT 1 FROM glucose_samples AS candidate " +
            "WHERE candidate.timestamp = glucose_samples.timestamp " +
            "AND " + CANDIDATE_PREFERRED_SQL +
            ")) " +
            "OR (" + ROW_PREFERRED_SQL + " AND EXISTS (" +
            "SELECT 1 FROM glucose_samples AS candidate " +
            "WHERE candidate.timestamp = glucose_samples.timestamp " +
            "AND " + CANDIDATE_PREFERRED_SQL + " " +
            "AND " + CANDIDATE_BETTER_SQL +
            ")) " +
            "OR (NOT " + ROW_PREFERRED_SQL + " " +
            "AND NOT " + ROW_LEGACY_ARTIFACT_SQL + " " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM glucose_samples AS candidate " +
            "WHERE candidate.timestamp = glucose_samples.timestamp " +
            "AND " + CANDIDATE_PREFERRED_SQL +
            ") " +
            "AND EXISTS (" +
            "SELECT 1 FROM glucose_samples AS candidate " +
            "WHERE candidate.timestamp = glucose_samples.timestamp " +
            "AND NOT " + CANDIDATE_LEGACY_ARTIFACT_SQL + " " +
            "AND " + CANDIDATE_BETTER_SQL +
            "))" +
            ")"
    )
    suspend fun deleteDuplicateByTimestampWithPriority(): Int

    @Query("DELETE FROM glucose_samples WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int

    companion object {

        private const val CANDIDATE_SOURCE_PRIORITY_SQL =
            "(CASE " +
                "WHEN lower(candidate.source) = 'aaps_broadcast' THEN 60 " +
                "WHEN lower(candidate.source) = 'nightscout' THEN 50 " +
                "WHEN lower(candidate.source) = 'xdrip_broadcast' THEN 45 " +
                "WHEN lower(candidate.source) = 'local_nightscout_entry' THEN 42 " +
                "WHEN substr(lower(candidate.source), 1, length('local_nightscout')) = " +
                "'local_nightscout' THEN 40 " +
                "WHEN lower(candidate.source) = 'local_broadcast' THEN 10 " +
                "ELSE 20 END)"

        private const val ROW_SOURCE_PRIORITY_SQL =
            "(CASE " +
                "WHEN lower(glucose_samples.source) = 'aaps_broadcast' THEN 60 " +
                "WHEN lower(glucose_samples.source) = 'nightscout' THEN 50 " +
                "WHEN lower(glucose_samples.source) = 'xdrip_broadcast' THEN 45 " +
                "WHEN lower(glucose_samples.source) = 'local_nightscout_entry' THEN 42 " +
                "WHEN substr(lower(glucose_samples.source), 1, length('local_nightscout')) = " +
                "'local_nightscout' THEN 40 " +
                "WHEN lower(glucose_samples.source) = 'local_broadcast' THEN 10 " +
                "ELSE 20 END)"

        private const val CANDIDATE_QUALITY_PRIORITY_SQL =
            "(CASE upper(candidate.quality) " +
                "WHEN 'OK' THEN 3 " +
                "WHEN 'GOOD' THEN 3 " +
                "WHEN 'VALID' THEN 3 " +
                "WHEN 'STALE' THEN 2 " +
                "WHEN 'SENSOR_ERROR' THEN 1 " +
                "WHEN 'ERROR' THEN 1 " +
                "WHEN 'INVALID' THEN 1 " +
                "ELSE 0 END)"

        private const val ROW_QUALITY_PRIORITY_SQL =
            "(CASE upper(glucose_samples.quality) " +
                "WHEN 'OK' THEN 3 " +
                "WHEN 'GOOD' THEN 3 " +
                "WHEN 'VALID' THEN 3 " +
                "WHEN 'STALE' THEN 2 " +
                "WHEN 'SENSOR_ERROR' THEN 1 " +
                "WHEN 'ERROR' THEN 1 " +
                "WHEN 'INVALID' THEN 1 " +
                "ELSE 0 END)"

        private const val CANDIDATE_PRIORITY_SQL =
            "($CANDIDATE_SOURCE_PRIORITY_SQL * 10 + $CANDIDATE_QUALITY_PRIORITY_SQL)"

        private const val ROW_PRIORITY_SQL =
            "($ROW_SOURCE_PRIORITY_SQL * 10 + $ROW_QUALITY_PRIORITY_SQL)"

        private const val CANDIDATE_LEGACY_ARTIFACT_SQL =
            "(candidate.source = 'local_broadcast' AND candidate.mmol >= 30.0)"

        private const val ROW_LEGACY_ARTIFACT_SQL =
            "(glucose_samples.source = 'local_broadcast' AND glucose_samples.mmol >= 30.0)"

        private const val CANDIDATE_PREFERRED_SQL =
            "(upper(trim(candidate.quality)) NOT IN ('SENSOR_ERROR', 'ERROR', 'INVALID') " +
                "AND NOT $CANDIDATE_LEGACY_ARTIFACT_SQL)"

        private const val ROW_PREFERRED_SQL =
            "(upper(trim(glucose_samples.quality)) NOT IN ('SENSOR_ERROR', 'ERROR', 'INVALID') " +
                "AND NOT $ROW_LEGACY_ARTIFACT_SQL)"

        private const val CANDIDATE_BETTER_SQL =
            "($CANDIDATE_PRIORITY_SQL > $ROW_PRIORITY_SQL " +
                "OR ($CANDIDATE_PRIORITY_SQL = $ROW_PRIORITY_SQL " +
                "AND candidate.id > glucose_samples.id))"

        private const val CANDIDATE_VALID_SQL =
            "candidate.timestamp >= 0 " +
                "AND candidate.mmol > 0.0 " +
                "AND candidate.mmol <= 1.7976931348623157e308 " +
                "AND upper(trim(candidate.quality)) NOT IN ('SENSOR_ERROR', 'ERROR', 'INVALID') " +
                "AND NOT (candidate.source = 'local_broadcast' AND candidate.mmol >= 30.0)"

        private const val ROW_VALID_SQL =
            "glucose_samples.timestamp >= 0 " +
                "AND glucose_samples.mmol > 0.0 " +
                "AND glucose_samples.mmol <= 1.7976931348623157e308 " +
                "AND upper(trim(glucose_samples.quality)) NOT IN ('SENSOR_ERROR', 'ERROR', 'INVALID') " +
                "AND NOT (glucose_samples.source = 'local_broadcast' AND glucose_samples.mmol >= 30.0)"
    }
}
