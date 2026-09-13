package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.MealEnergyOverrideEntity

@Dao
interface MealEnergyOverrideDao {

    @Query("SELECT * FROM meal_energy_overrides ORDER BY canonicalTherapyIdentity ASC")
    suspend fun all(): List<MealEnergyOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: MealEnergyOverrideEntity)
}
