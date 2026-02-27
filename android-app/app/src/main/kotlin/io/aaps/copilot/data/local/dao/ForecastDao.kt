package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.ForecastEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ForecastDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ForecastEntity>)

    @Query("DELETE FROM forecasts WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("SELECT * FROM forecasts ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ForecastEntity>>

    @Query("SELECT * FROM forecasts WHERE horizonMinutes = :horizon ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestByHorizon(horizon: Int): ForecastEntity?
}
