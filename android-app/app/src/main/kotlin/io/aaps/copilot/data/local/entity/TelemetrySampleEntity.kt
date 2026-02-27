package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "telemetry_samples",
    indices = [
        Index("timestamp"),
        Index("key"),
        Index(value = ["key", "timestamp"])
    ]
)
data class TelemetrySampleEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val source: String,
    val key: String,
    val valueDouble: Double?,
    val valueText: String?,
    val unit: String?,
    val quality: String
)
