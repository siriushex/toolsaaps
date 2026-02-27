package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BaselineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(points: List<BaselinePointEntity>)

    @Query("SELECT * FROM baseline_points WHERE algorithm = :algorithm ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latestByAlgorithm(algorithm: String, limit: Int): List<BaselinePointEntity>

    @Query("SELECT * FROM baseline_points ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<BaselinePointEntity>>

    @Query("DELETE FROM baseline_points WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
