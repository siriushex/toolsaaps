package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blood_glucose_checks",
    indices = [
        Index("timestamp"),
        Index("sensorSessionKey"),
        Index("status"),
        Index(value = ["sensorSessionKey", "timestamp"])
    ]
)
data class BloodGlucoseCheckEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val mmol: Double,
    val units: String,
    val source: String,
    val note: String,
    val enteredAt: Long,
    val sensorSessionKey: String?,
    val lagAlignedTs: Long?,
    val matchedRawGlucose: Double?,
    val status: String,
    val reason: String
)
