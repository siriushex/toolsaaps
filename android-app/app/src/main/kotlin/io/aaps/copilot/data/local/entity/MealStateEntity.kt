package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "meal_states", indices = [Index(value = ["canonicalMealId"], unique = true)])
data class MealStateEntity(
    @PrimaryKey val episodeId: String,
    val recordedAtMs: Long,
    val minimumGrams: Double,
    val maximumGrams: Double,
    val storageRevision: Long,
    val canonicalMealId: String?,
    val aapsRevision: Long?,
    val aapsRecordedAtMs: Long?,
    val aapsGrams: Double?,
    val aapsDeleted: Boolean?,
    val modelVersion: String?,
    val beliefRevision: Long?,
    val lastSampleAtMs: Long?,
    val lastSampleId: String?,
    val runtimeIdentity: String?
)

@Entity(tableName = "meal_state_scenarios", primaryKeys = ["episodeId", "scenarioId"],
    indices = [Index(value = ["episodeId"])],
    foreignKeys = [ForeignKey(entity = MealStateEntity::class, parentColumns = ["episodeId"],
        childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class MealStateScenarioEntity(
    val episodeId: String,
    val scenarioId: String,
    val position: Int,
    val kind: String,
    val earliestStartMs: Long?,
    val latestStartMs: Long?,
    val minimumGrams: Double,
    val maximumGrams: Double,
    val probability: Double,
    val canonicalMealId: String?
)

@Entity(tableName = "meal_state_absorption", primaryKeys = ["episodeId", "scenarioId", "position"],
    indices = [Index(value = ["episodeId", "scenarioId"])],
    foreignKeys = [ForeignKey(entity = MealStateScenarioEntity::class,
        parentColumns = ["episodeId", "scenarioId"], childColumns = ["episodeId", "scenarioId"],
        onDelete = ForeignKey.CASCADE)])
data class MealStateAbsorptionEntity(
    val episodeId: String,
    val scenarioId: String,
    val position: Int,
    val profile: String,
    val durationMinutes: Int,
    val probability: Double
)
