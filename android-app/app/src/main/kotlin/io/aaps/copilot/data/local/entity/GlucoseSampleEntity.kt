package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "glucose_samples",
    indices = [Index("timestamp")]
)
data class GlucoseSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val mmol: Double,
    val source: String,
    val quality: String
)
