package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.aaps.copilot.data.local.entity.MealStateEntity
import io.aaps.copilot.data.local.entity.MealStateScenarioEntity
import io.aaps.copilot.data.local.entity.MealStateAbsorptionEntity

@Dao
interface MealStateDao {
    @Query("SELECT * FROM meal_states WHERE episodeId = :id")
    suspend fun get(id: String): MealStateEntity?

    @Query("SELECT episodeId FROM meal_states WHERE canonicalMealId = :canonicalId")
    suspend fun owner(canonicalId: String): String?

    @Query("SELECT * FROM meal_states WHERE canonicalMealId IN (:canonicalIds)")
    suspend fun canonicalOwners(canonicalIds: List<String>): List<MealStateEntity>

    @Query("SELECT * FROM meal_state_scenarios WHERE episodeId = :id ORDER BY position LIMIT 97")
    suspend fun scenarios(id: String): List<MealStateScenarioEntity>

    @Query("SELECT * FROM meal_state_absorption WHERE episodeId = :id ORDER BY scenarioId, position LIMIT 1537")
    suspend fun absorption(id: String): List<MealStateAbsorptionEntity>

    @Insert suspend fun insert(state: MealStateEntity)
    @Update suspend fun update(state: MealStateEntity)
    @Insert suspend fun insertScenarios(rows: List<MealStateScenarioEntity>)
    @Insert suspend fun insertAbsorption(rows: List<MealStateAbsorptionEntity>)

    @Query("DELETE FROM meal_state_scenarios WHERE episodeId = :id")
    suspend fun clearBelief(id: String)
}
