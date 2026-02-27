package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "forecasts",
    indices = [Index("timestamp"), Index("horizonMinutes")]
)
data class ForecastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val horizonMinutes: Int,
    val valueMmol: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val modelVersion: String
)
