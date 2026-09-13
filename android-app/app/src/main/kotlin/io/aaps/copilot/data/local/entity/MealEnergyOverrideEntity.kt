package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-entered energy total tied to one trusted canonical meal reference. */
@Entity(
    tableName = "meal_energy_overrides",
    indices = [Index("updatedAtMs")]
)
data class MealEnergyOverrideEntity(
    @PrimaryKey val canonicalTherapyIdentity: String,
    val therapyRevisionHash: String,
    val caloriesKcal: Double,
    val updatedAtMs: Long
)
