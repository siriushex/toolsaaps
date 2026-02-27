package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "baseline_points",
    indices = [Index("timestamp"), Index("algorithm")]
)
data class BaselinePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val algorithm: String,
    val valueMmol: Double,
    val horizonMinutes: Int
)
