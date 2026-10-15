package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "meal_forecast_journal", indices = [Index("observedAtMs"), Index("predictionAtMs")])
data class MealForecastJournalEntity(
    @PrimaryKey val cycleId: String,
    val predictionAtMs: Long,
    val acceptedAtMs: Long,
    val observedAtMs: Long,
    val finalizedAtMs: Long?,
    val settingsRevision: Long,
    val forecastDigest: String,
    val source: String,
    val sensorSessionKey: String?,
    val calibrationModelId: String?,
    val recordedZoneId: String,
    val modelVersion: String,
    val glucoseMmol: Double,
    val quality: String,
    val forecastsJson: String
)
