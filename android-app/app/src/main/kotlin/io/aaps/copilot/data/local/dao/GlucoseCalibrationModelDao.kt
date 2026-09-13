package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.GlucoseCalibrationModelEntity
import kotlinx.coroutines.flow.Flow

data class CalibrationInputWatermarkRow(
    val bloodCheckRowId: Long,
    val glucoseRowId: Long,
    val therapyRowId: Long,
    val telemetryRowId: Long,
    val calibrationModelRowId: Long,
    val therapyBoundarySignature: String?
)

@Dao
interface GlucoseCalibrationModelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GlucoseCalibrationModelEntity)

    @Query("SELECT * FROM glucose_calibration_models WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): GlucoseCalibrationModelEntity?

    @Query(
        "SELECT " +
            "(SELECT COALESCE(MAX(rowid), 0) FROM blood_glucose_checks) AS bloodCheckRowId, " +
            "(SELECT COALESCE(MAX(rowid), 0) FROM glucose_samples " +
            "WHERE timestamp BETWEEN :historySinceTs AND :relevantThroughTs) AS glucoseRowId, " +
            "(SELECT COALESCE(MAX(rowid), 0) FROM therapy_events " +
            "WHERE timestamp BETWEEN :historySinceTs AND :relevantThroughTs " +
            "AND lower(trim(type)) IN ('sensor_change','cgm_sensor_change','sensor_start','sensor_started')) AS therapyRowId, " +
            // Include full rows: payload validity affects fitting, and MAX(rowid) misses removals.
            "CASE WHEN (SELECT COUNT(*) <= 512 AND COALESCE(SUM(" +
            "LENGTH(CAST(id AS BLOB)) + LENGTH(CAST(type AS BLOB)) + LENGTH(CAST(payloadJson AS BLOB))), 0) <= 131072 " +
            "FROM therapy_events WHERE timestamp BETWEEN :historySinceTs AND :relevantThroughTs " +
            "AND lower(trim(type)) IN ('sensor_change','cgm_sensor_change','sensor_start','sensor_started')) THEN " +
            "COALESCE((SELECT GROUP_CONCAT(boundary, ',') FROM (" +
            "SELECT rowid || ':' || HEX(id) || ':' || timestamp || ':' || HEX(type) || ':' || " +
            "HEX(payloadJson) AS boundary FROM therapy_events " +
            "WHERE timestamp BETWEEN :historySinceTs AND :relevantThroughTs " +
            "AND lower(trim(type)) IN ('sensor_change','cgm_sensor_change','sensor_start','sensor_started') " +
            "ORDER BY rowid)), '') ELSE NULL END AS therapyBoundarySignature, " +
            "(SELECT COALESCE(MAX(rowid), 0) FROM telemetry_samples " +
            "WHERE timestamp BETWEEN :historySinceTs AND :relevantThroughTs " +
            "AND key IN (:telemetryKeys)) AS telemetryRowId, " +
            "(SELECT COALESCE(MAX(rowid), 0) FROM glucose_calibration_models) AS calibrationModelRowId"
    )
    suspend fun calibrationInputWatermark(
        historySinceTs: Long,
        relevantThroughTs: Long,
        telemetryKeys: List<String>
    ): CalibrationInputWatermarkRow

    @Query(
        "SELECT * FROM glucose_calibration_models " +
            "WHERE status = 'ACTIVE' " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun latestActive(): GlucoseCalibrationModelEntity?

    @Query(
        "SELECT * FROM glucose_calibration_models " +
            "WHERE status = 'ACTIVE' " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    fun observeLatestActive(): Flow<GlucoseCalibrationModelEntity?>

    @Query(
        "SELECT * FROM glucose_calibration_models " +
            "WHERE status IN ('ACTIVE', 'SHADOW') " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun latestNonRetired(): GlucoseCalibrationModelEntity?

    @Query(
        "SELECT * FROM glucose_calibration_models " +
            "WHERE sensorSessionKey = :sensorSessionKey " +
            "AND status IN ('ACTIVE', 'SHADOW') " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun latestNonRetiredInSession(sensorSessionKey: String): GlucoseCalibrationModelEntity?

    @Query(
        "SELECT * FROM glucose_calibration_models " +
            "WHERE validToTs >= :since " +
            "ORDER BY createdAt ASC"
    )
    suspend fun modelsSince(since: Long): List<GlucoseCalibrationModelEntity>

    @Query(
        "SELECT * FROM glucose_calibration_models " +
            "WHERE validToTs >= :since AND status = 'ACTIVE' " +
            "ORDER BY createdAt ASC"
    )
    suspend fun activeModelsSince(since: Long): List<GlucoseCalibrationModelEntity>

    @Query(
        "SELECT * FROM glucose_calibration_models " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeLatest(limit: Int): Flow<List<GlucoseCalibrationModelEntity>>

    @Query(
        "UPDATE glucose_calibration_models " +
            "SET status = 'RETIRED', " +
            "validToTs = MAX(validFromTs, MIN(validToTs, :retiredAt)) " +
            "WHERE status IN ('ACTIVE', 'SHADOW') AND sensorSessionKey != :sensorSessionKey"
    )
    suspend fun retireOtherSessions(sensorSessionKey: String, retiredAt: Long): Int

    @Query(
        "UPDATE glucose_calibration_models " +
            "SET status = 'RETIRED', " +
            "validToTs = MAX(validFromTs, MIN(validToTs, :retiredAt)) " +
            "WHERE status IN ('ACTIVE', 'SHADOW') AND sensorSessionKey = :sensorSessionKey"
    )
    suspend fun retireNonRetiredInSession(sensorSessionKey: String, retiredAt: Long): Int

    @Query(
        "UPDATE glucose_calibration_models " +
            "SET status = 'RETIRED', " +
            "validToTs = MAX(validFromTs, MIN(validToTs, :retiredAt)) " +
            "WHERE status IN ('ACTIVE', 'SHADOW')"
    )
    suspend fun retireAllNonRetired(retiredAt: Long): Int

    @Query(
        "UPDATE glucose_calibration_models " +
            "SET status = 'RETIRED', " +
            "validToTs = MAX(validFromTs, MIN(validToTs, :retiredAt)) " +
            "WHERE status IN ('ACTIVE', 'SHADOW') " +
            "AND sensorSessionKey = :sensorSessionKey AND id != :keepId"
    )
    suspend fun retireOtherNonRetiredModelsInSession(
        sensorSessionKey: String,
        keepId: String,
        retiredAt: Long
    ): Int

    @Query(
        "UPDATE glucose_calibration_models " +
            "SET status = 'RETIRED', " +
            "validToTs = MAX(validFromTs, MIN(validToTs, :retiredAt)) " +
            "WHERE status IN ('ACTIVE', 'SHADOW') AND createdAt <= :expiredCreatedAt"
    )
    suspend fun retireExpiredNonRetired(expiredCreatedAt: Long, retiredAt: Long): Int

    @Query(
        "UPDATE glucose_calibration_models " +
            "SET createdAt = :createdAt, " +
            "validToTs = :validToTs, " +
            "confidence = :confidence, " +
            "checkCount = :checkCount, " +
            "sensorAgeHours = :sensorAgeHours, " +
            "lagMinutesAtFit = :lagMinutesAtFit, " +
            "status = :status, " +
            "diagnosticsJson = :diagnosticsJson " +
            "WHERE id = :id"
    )
    suspend fun refreshExistingModel(
        id: String,
        createdAt: Long,
        validToTs: Long,
        confidence: Double,
        checkCount: Int,
        sensorAgeHours: Double?,
        lagMinutesAtFit: Double?,
        status: String,
        diagnosticsJson: String
    ): Int
}
