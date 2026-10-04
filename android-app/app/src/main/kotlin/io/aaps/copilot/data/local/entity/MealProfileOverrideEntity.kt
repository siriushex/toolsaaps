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
    val updatedAtMs: Long,
    val portion: String? = null,
    val portionProvenance: String? = null,
    val confirmedCarbsGrams: Double? = null,
    val glycemicIndexValue: Double? = null,
    val glycemicIndexSource: String? = null,
    val glycemicIndexReference: String? = null
)
