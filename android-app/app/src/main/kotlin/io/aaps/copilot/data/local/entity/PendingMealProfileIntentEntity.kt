package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_meal_profile_intents",
    indices = [Index("expiresAtMs")]
)
data class PendingMealProfileIntentEntity(
    @PrimaryKey val idempotencyKey: String,
    val copilotNote: String,
    val profile: String,
    val durationMinutes: Int,
    val expectedCarbsGrams: Double,
    val manualMealEnergyKcal: Double? = null,
    val submittedAtMs: Long,
    val expiresAtMs: Long,
    val portion: String? = null,
    val portionProvenance: String? = null,
    val glycemicIndexValue: Double? = null,
    val glycemicIndexSource: String? = null,
    val glycemicIndexReference: String? = null
)
