package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.ForecastEntity
import kotlinx.coroutines.flow.Flow

data class ClinicalForecastProjection(
    val ts: Long,
    val horizonMin: Int,
    val mmol: Double,
    val lower: Double,
    val upper: Double,
    val rowId: Long = 0L,
    val modelVersion: String = ""
)

@Dao
interface ForecastDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ForecastEntity>)

    @Query("DELETE FROM forecasts WHERE timestamp = :timestamp AND horizonMinutes = :horizonMinutes")
    suspend fun deleteByTimestampAndHorizon(timestamp: Long, horizonMinutes: Int): Int

    @Query(
        "DELETE FROM forecasts WHERE id NOT IN (" +
            "SELECT MAX(id) FROM forecasts GROUP BY timestamp, horizonMinutes" +
            ")"
    )
    suspend fun deleteDuplicateByTimestampAndHorizon(): Int

    // Bound maintenance write locks; subsequent accepted cycles drain the backlog.
    @Query(
        "DELETE FROM forecasts WHERE id IN (" +
            "SELECT id FROM forecasts WHERE timestamp < :olderThan " +
            "ORDER BY timestamp ASC, id ASC LIMIT 256)"
    )
    suspend fun deleteOlderThan(olderThan: Long): Int

    @Query("SELECT * FROM forecasts ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ForecastEntity>>

    @Query("SELECT * FROM forecasts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ForecastEntity>

    @Query("SELECT * FROM forecasts WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<ForecastEntity>

    @Query(
        "SELECT * FROM forecasts WHERE timestamp BETWEEN :since AND :through " +
            "ORDER BY timestamp ASC, horizonMinutes ASC, id ASC"
    )
    suspend fun between(since: Long, through: Long): List<ForecastEntity>

    @Query(
        "SELECT timestamp AS ts, horizonMinutes AS horizonMin, valueMmol AS mmol, " +
            "ciLow AS lower, ciHigh AS upper, id AS rowId, modelVersion FROM forecasts " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs " +
            "ORDER BY timestamp ASC, horizonMinutes ASC, id ASC"
    )
    suspend fun betweenForClinicalReport(
        fromTs: Long,
        toTs: Long
    ): List<ClinicalForecastProjection>

    @Query(
        "SELECT timestamp AS ts, horizonMinutes AS horizonMin, valueMmol AS mmol, " +
            "ciLow AS lower, ciHigh AS upper, id AS rowId, modelVersion FROM forecasts " +
            "WHERE timestamp BETWEEN :fromTs AND :toTs " +
            "ORDER BY timestamp ASC, horizonMinutes ASC, id ASC LIMIT :limit"
    )
    suspend fun betweenForAlertAi(
        fromTs: Long,
        toTs: Long,
        limit: Int
    ): List<ClinicalForecastProjection>

    @Query(
        "SELECT timestamp AS ts, horizonMinutes AS horizonMin, valueMmol AS mmol, " +
            "ciLow AS lower, ciHigh AS upper, id AS rowId, modelVersion FROM forecasts " +
            "WHERE timestamp - (horizonMinutes * 60000) = :generationTimestamp " +
            "ORDER BY horizonMinutes ASC, id ASC"
    )
    suspend fun atGenerationTimestampForClinicalReport(
        generationTimestamp: Long
    ): List<ClinicalForecastProjection>

    @Query(
        "SELECT * FROM forecasts " +
            "WHERE timestamp - (horizonMinutes * 60000) = :generationTimestamp " +
            "ORDER BY horizonMinutes ASC, id ASC"
    )
    suspend fun atGenerationTimestamp(generationTimestamp: Long): List<ForecastEntity>

    @Query(
        "SELECT * FROM forecasts " +
            "WHERE timestamp - (horizonMinutes * 60000) = :generationTimestamp " +
            "ORDER BY horizonMinutes ASC, id ASC LIMIT :limit"
    )
    suspend fun atGenerationTimestampForAlertAi(
        generationTimestamp: Long,
        limit: Int
    ): List<ForecastEntity>

    @Query("SELECT * FROM forecasts WHERE horizonMinutes = :horizon ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun observeLatestByHorizon(horizon: Int): Flow<ForecastEntity?>

    @Query("SELECT * FROM forecasts WHERE horizonMinutes = :horizon ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestByHorizon(horizon: Int): ForecastEntity?
}
