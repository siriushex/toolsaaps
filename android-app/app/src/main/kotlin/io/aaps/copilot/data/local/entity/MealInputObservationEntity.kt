package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "meal_input_observations", indices = [Index("observedAtMs"), Index("predictionAtMs")])
data class MealInputObservationEntity(
    @PrimaryKey val cycleId: String,
    val predictionAtMs: Long,
    val observedAtMs: Long,
    val finalizedAtMs: Long?,
    val settingsRevision: Long,
    val forecastDigest: String,
    val source: String,
    val sensorSessionKey: String?,
    val calibrationModelId: String?,
    val glucoseBasisId: String,
    val recordedZoneId: String,
    val payloadJson: String
)
