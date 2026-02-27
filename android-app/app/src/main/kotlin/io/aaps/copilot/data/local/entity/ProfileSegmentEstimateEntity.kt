package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "profile_segment_estimates",
    indices = [Index("dayType"), Index("timeSlot"), Index("updatedAt")]
)
data class ProfileSegmentEstimateEntity(
    @PrimaryKey val id: String,
    val dayType: String,
    val timeSlot: String,
    val isfMmolPerUnit: Double?,
    val crGramPerUnit: Double?,
    val confidence: Double,
    val isfSampleCount: Int,
    val crSampleCount: Int,
    val lookbackDays: Int,
    val updatedAt: Long
)
