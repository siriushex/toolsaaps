package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.UamInferenceEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UamInferenceEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<UamInferenceEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: UamInferenceEventEntity)

    @Query("SELECT * FROM uam_inference_events ORDER BY updatedAt DESC")
    suspend fun all(): List<UamInferenceEventEntity>

    @Query("SELECT * FROM uam_inference_events ORDER BY updatedAt DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<UamInferenceEventEntity>>

    @Query("SELECT * FROM uam_inference_events WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): UamInferenceEventEntity?

    @Query("SELECT * FROM uam_inference_events WHERE state IN ('SUSPECTED', 'CONFIRMED') ORDER BY updatedAt DESC")
    suspend fun active(): List<UamInferenceEventEntity>

    @Query("DELETE FROM uam_inference_events WHERE updatedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int
}
