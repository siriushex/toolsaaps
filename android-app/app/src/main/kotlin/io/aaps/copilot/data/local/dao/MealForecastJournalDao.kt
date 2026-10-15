package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.MealForecastJournalEntity

@Dao
interface MealForecastJournalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: MealForecastJournalEntity): Long

    @Query("UPDATE meal_forecast_journal SET finalizedAtMs = :atMs WHERE cycleId = :cycleId AND finalizedAtMs IS NULL")
    suspend fun finalize(cycleId: String, atMs: Long): Int

    @Query("SELECT * FROM meal_forecast_journal WHERE observedAtMs BETWEEN :since AND :through ORDER BY observedAtMs, cycleId LIMIT :limit")
    suspend fun between(since: Long, through: Long, limit: Int): List<MealForecastJournalEntity>

    @Query("DELETE FROM meal_forecast_journal WHERE observedAtMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
