package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.MealInputObservationEntity

@Dao
interface MealInputObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: MealInputObservationEntity): Long

    @Query("UPDATE meal_input_observations SET finalizedAtMs=:atMs WHERE cycleId=:cycleId AND finalizedAtMs IS NULL")
    suspend fun finalize(cycleId: String, atMs: Long): Int

    @Query("DELETE FROM meal_input_observations WHERE observedAtMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int

    @Query("SELECT * FROM meal_input_observations WHERE observedAtMs BETWEEN :startMs AND :endMs ORDER BY observedAtMs,cycleId LIMIT :limit")
    suspend fun between(startMs: Long, endMs: Long, limit: Int): List<MealInputObservationEntity>
}
