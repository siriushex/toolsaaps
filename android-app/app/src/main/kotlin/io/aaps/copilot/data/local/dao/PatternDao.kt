package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatternDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PatternWindowEntity>)

    @Query("DELETE FROM pattern_windows")
    suspend fun clear()

    @Query("DELETE FROM pattern_windows WHERE updatedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("SELECT * FROM pattern_windows WHERE dayType = :dayType AND hour = :hour ORDER BY updatedAt DESC LIMIT 1")
    suspend fun byDayAndHour(dayType: String, hour: Int): PatternWindowEntity?

    @Query(
        "SELECT p.* FROM pattern_windows p " +
            "INNER JOIN (" +
            "SELECT dayType, hour, MAX(updatedAt) AS maxUpdatedAt " +
            "FROM pattern_windows GROUP BY dayType, hour" +
            ") latest " +
            "ON p.dayType = latest.dayType AND p.hour = latest.hour AND p.updatedAt = latest.maxUpdatedAt"
    )
    suspend fun all(): List<PatternWindowEntity>

    @Query(
        "SELECT p.* FROM pattern_windows p " +
            "INNER JOIN (" +
            "SELECT dayType, hour, MAX(updatedAt) AS maxUpdatedAt " +
            "FROM pattern_windows GROUP BY dayType, hour" +
            ") latest " +
            "ON p.dayType = latest.dayType AND p.hour = latest.hour AND p.updatedAt = latest.maxUpdatedAt " +
            "ORDER BY p.dayType, p.hour"
    )
    fun observeAll(): Flow<List<PatternWindowEntity>>
}
