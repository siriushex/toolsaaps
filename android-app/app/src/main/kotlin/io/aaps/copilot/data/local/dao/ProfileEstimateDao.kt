package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileEstimateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProfileEstimateEntity)

    @Query("SELECT * FROM profile_estimates WHERE id = 'active' LIMIT 1")
    suspend fun active(): ProfileEstimateEntity?

    @Query("SELECT * FROM profile_estimates WHERE id = 'active' LIMIT 1")
    fun observeActive(): Flow<ProfileEstimateEntity?>
}
