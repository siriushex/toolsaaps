package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileSegmentEstimateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ProfileSegmentEstimateEntity>)

    @Query("DELETE FROM profile_segment_estimates")
    suspend fun clear()

    @Query("SELECT * FROM profile_segment_estimates WHERE dayType = :dayType AND timeSlot = :timeSlot ORDER BY updatedAt DESC LIMIT 1")
    suspend fun byDayTypeAndTimeSlot(dayType: String, timeSlot: String): ProfileSegmentEstimateEntity?

    @Query("SELECT * FROM profile_segment_estimates")
    suspend fun all(): List<ProfileSegmentEstimateEntity>

    @Query("SELECT * FROM profile_segment_estimates ORDER BY dayType, timeSlot")
    fun observeAll(): Flow<List<ProfileSegmentEstimateEntity>>
}
