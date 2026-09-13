package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meal_profile_overrides")
data class MealProfileOverrideEntity(
    @PrimaryKey val canonicalTherapyIdentity: String,
    val therapyRevisionHash: String,
    val profile: String,
    val durationMinutes: Int,
    val source: String,
    val revision: Long,
    val updatedAtMs: Long
)
