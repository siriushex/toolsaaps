package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "glucose_calibration_models",
    indices = [
        Index("sensorSessionKey"),
        Index("status"),
        Index("createdAt"),
        Index(value = ["status", "createdAt"])
    ]
)
data class GlucoseCalibrationModelEntity(
    @PrimaryKey val id: String,
    val sensorSessionKey: String,
    val createdAt: Long,
    val validFromTs: Long,
    val validToTs: Long,
    val modelType: String,
    val gain: Double,
    val offsetMmol: Double,
    val confidence: Double,
    val checkCount: Int,
    val sensorAgeHours: Double?,
    val lagMinutesAtFit: Double?,
    val status: String,
    val diagnosticsJson: String
)
