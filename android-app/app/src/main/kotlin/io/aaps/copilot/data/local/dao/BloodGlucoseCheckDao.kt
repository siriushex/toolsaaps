package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.BloodGlucoseCheckEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BloodGlucoseCheckDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BloodGlucoseCheckEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BloodGlucoseCheckEntity>)

    @Query("SELECT * FROM blood_glucose_checks ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<BloodGlucoseCheckEntity>>

    @Query("SELECT * FROM blood_glucose_checks ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<BloodGlucoseCheckEntity>

    @Query("SELECT MAX(timestamp) FROM blood_glucose_checks")
    suspend fun latestTimestamp(): Long?

    @Query("SELECT * FROM blood_glucose_checks WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<BloodGlucoseCheckEntity>

    @Query(
        "SELECT * FROM blood_glucose_checks " +
            "WHERE timestamp BETWEEN :since AND :through ORDER BY timestamp ASC"
    )
    suspend fun between(since: Long, through: Long): List<BloodGlucoseCheckEntity>

    @Query(
        "SELECT * FROM blood_glucose_checks " +
            "WHERE timestamp BETWEEN :since AND :through AND enteredAt BETWEEN 0 AND :through " +
            "ORDER BY timestamp DESC, enteredAt DESC, id DESC LIMIT :limit"
    )
    suspend fun recentForCalibrationSession(since: Long, through: Long, limit: Int): List<BloodGlucoseCheckEntity>

    @Query(
        "SELECT * FROM blood_glucose_checks WHERE timestamp BETWEEN :since AND :through " +
            "ORDER BY timestamp ASC, id ASC LIMIT :limit"
    )
    suspend fun betweenForAlertAi(
        since: Long,
        through: Long,
        limit: Int
    ): List<BloodGlucoseCheckEntity>

    @Query(
        "SELECT * FROM blood_glucose_checks " +
            "WHERE sensorSessionKey = :sensorSessionKey " +
            "ORDER BY timestamp ASC"
    )
    suspend fun bySensorSession(sensorSessionKey: String): List<BloodGlucoseCheckEntity>

    @Query(
        "SELECT * FROM blood_glucose_checks " +
            "WHERE sensorSessionKey = :sensorSessionKey AND status = :status " +
            "ORDER BY timestamp ASC"
    )
    suspend fun bySensorSessionAndStatus(sensorSessionKey: String, status: String): List<BloodGlucoseCheckEntity>

    @Query(
        "UPDATE blood_glucose_checks " +
            "SET status = 'REJECTED', reason = 'manual_reset' " +
            "WHERE source = 'MANUAL' AND enteredAt <= :resetAt AND reason != 'manual_reset'"
    )
    suspend fun resetManualChecksThrough(resetAt: Long): Int
}
