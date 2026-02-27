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
}
