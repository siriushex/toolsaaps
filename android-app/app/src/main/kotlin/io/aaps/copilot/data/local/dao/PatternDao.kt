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

    @Query("SELECT * FROM pattern_windows WHERE dayType = :dayType AND hour = :hour ORDER BY updatedAt DESC LIMIT 1")
    suspend fun byDayAndHour(dayType: String, hour: Int): PatternWindowEntity?

    @Query("SELECT * FROM pattern_windows")
    suspend fun all(): List<PatternWindowEntity>

    @Query("SELECT * FROM pattern_windows ORDER BY dayType, hour")
    fun observeAll(): Flow<List<PatternWindowEntity>>
}
