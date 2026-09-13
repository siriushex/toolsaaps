package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import kotlinx.coroutines.flow.Flow

data class ClinicalTherapyProjection(
    val ts: Long,
    val type: String,
    val payloadJson: String,
    val isBroadcastArtifact: Boolean = false,
    val rowId: String = ""
)

@Dao
interface TherapyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<TherapyEventEntity>)

    @Query("SELECT * FROM therapy_events WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TherapyEventEntity?

    @Query("SELECT * FROM therapy_events WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<TherapyEventEntity>

    @Query("SELECT * FROM therapy_events WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<TherapyEventEntity>

    @Query(
        "SELECT * FROM therapy_events " +
            "WHERE timestamp BETWEEN :since AND :through ORDER BY timestamp ASC"
    )
    suspend fun between(since: Long, through: Long): List<TherapyEventEntity>

    @Query(
        "SELECT timestamp FROM therapy_events WHERE timestamp > :checkTs AND timestamp <= :through " +
            "AND lower(trim(type)) IN ('sensor_change','cgm_sensor_change','sensor_start','sensor_started') " +
            "ORDER BY timestamp ASC LIMIT 1"
    )
    suspend fun sensorBoundaryAfterForCalibrationSession(checkTs: Long, through: Long): Long?

    @Query(
        "SELECT timestamp AS ts, type, payloadJson, id AS rowId, " +
            "CASE WHEN id LIKE 'br-local_broadcast-%' " +
            "AND type IN ('correction_bolus','meal_bolus','carbs','temp_target') " +
            "THEN 1 ELSE 0 END AS isBroadcastArtifact FROM therapy_events " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs AND type IN (:types) " +
            "ORDER BY timestamp ASC, type ASC, id ASC"
    )
    suspend fun betweenForClinicalReport(
        fromTs: Long,
        toTs: Long,
        types: List<String>
    ): List<ClinicalTherapyProjection>

    @Query(
        "SELECT timestamp AS ts, type, payloadJson, id AS rowId, " +
            "CASE WHEN id LIKE 'br-local_broadcast-%' " +
            "AND type IN ('correction_bolus','meal_bolus','carbs','temp_target') " +
            "THEN 1 ELSE 0 END AS isBroadcastArtifact FROM therapy_events " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs AND type IN (:types) " +
            "ORDER BY timestamp ASC, type ASC, id ASC LIMIT :limit"
    )
    suspend fun betweenForAlertAi(
        fromTs: Long,
        toTs: Long,
        types: List<String>,
        limit: Int
    ): List<ClinicalTherapyProjection>

    @Query(
        "SELECT * FROM therapy_events WHERE timestamp BETWEEN :fromTs AND :toTs " +
            "ORDER BY timestamp ASC, type ASC, id ASC LIMIT :limit"
    )
    suspend fun betweenForAlertAiTimeline(
        fromTs: Long,
        toTs: Long,
        limit: Int
    ): List<TherapyEventEntity>

    @Query("SELECT * FROM therapy_events WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun sinceDescLimit(since: Long, limit: Int): List<TherapyEventEntity>

    @Query(
        "SELECT COUNT(*) FROM therapy_events " +
            "WHERE timestamp >= :since AND (" +
            "payloadJson NOT LIKE '%\"classification\":\"%' " +
            "OR (id LIKE 'iob-inf-%' AND payloadJson NOT LIKE '%\"classification\":\"INFERRED_IOB\"%') " +
            "OR ((payloadJson LIKE '%UAM_ENGINE|%' " +
            "OR lower(payloadJson) LIKE '%\"reason\":\"%uam_engine%') " +
            "AND payloadJson NOT LIKE '%\"classification\":\"UAM_SYNTHETIC\"%')" +
            ")"
    )
    suspend fun countNightscoutRepairCandidatesSince(since: Long): Int

    @Query(
        "SELECT * FROM therapy_events " +
            "WHERE type IN ('carbs','meal_bolus') " +
            "AND timestamp BETWEEN :fromTs AND :toTs ORDER BY timestamp,id"
    )
    suspend fun carbCandidates(fromTs: Long, toTs: Long): List<TherapyEventEntity>

    @Query(
        "SELECT timestamp AS ts, type, payloadJson, id AS rowId, 0 AS isBroadcastArtifact " +
            "FROM therapy_events WHERE type IN ('carbs','meal_bolus') " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    fun observeLatestCarbCandidates(limit: Int): Flow<List<ClinicalTherapyProjection>>

    @Query("SELECT * FROM therapy_events WHERE type = :type AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun byTypeSince(type: String, since: Long): List<TherapyEventEntity>

    @Query(
        "SELECT * FROM therapy_events " +
            "WHERE type = :type AND timestamp BETWEEN :since AND :through " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun newestByTypeBetween(
        type: String,
        since: Long,
        through: Long,
        limit: Int
    ): List<TherapyEventEntity>

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
            "AND (" +
            "(payloadJson LIKE '%\"classification\":\"REAL_FETCHED\"%' AND id NOT LIKE 'local-ns-%') " +
            "OR payloadJson LIKE '%\"source\":\"nightscout_devicestatus_recovered\"%' " +
            "OR payloadJson LIKE '%\"classification\":\"RECOVERED_MICROBOLUS\"%'" +
            ")"
    )
    suspend fun countInsulinLikeForBootstrapSince(since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM therapy_events " +
            "WHERE timestamp >= :since " +
            "AND type IN ('correction_bolus','meal_bolus','bolus','insulin') " +
            "AND payloadJson LIKE '%\"classification\":\"REAL_FETCHED\"%' " +
            "AND id NOT LIKE 'local-ns-%'"
    )
    suspend fun countRealFetchedInsulinLikeSince(since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM therapy_events " +
            "WHERE timestamp >= :since " +
            "AND type IN ('correction_bolus','meal_bolus','bolus','insulin') " +
            "AND payloadJson LIKE '%\"inferred\":\"true\"%'"
    )
    suspend fun countInferredInsulinLikeSince(since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM therapy_events " +
            "WHERE timestamp >= :since " +
            "AND type IN ('correction_bolus','meal_bolus','bolus','insulin') " +
            "AND (" +
            "payloadJson LIKE '%\"source\":\"nightscout_devicestatus_recovered\"%' " +
            "OR payloadJson LIKE '%\"classification\":\"RECOVERED_MICROBOLUS\"%'" +
            ")"
    )
    suspend fun countRecoveredInsulinLikeSince(since: Long): Int

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
