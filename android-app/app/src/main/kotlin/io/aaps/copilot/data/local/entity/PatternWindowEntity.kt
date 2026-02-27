package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pattern_windows",
    indices = [Index("dayType"), Index("hour")]
)
data class PatternWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayType: String,
    val hour: Int,
    val sampleCount: Int,
    val activeDays: Int,
    val lowRate: Double,
    val highRate: Double,
    val recommendedTargetMmol: Double,
    val isRiskWindow: Boolean,
    val updatedAt: Long
)
