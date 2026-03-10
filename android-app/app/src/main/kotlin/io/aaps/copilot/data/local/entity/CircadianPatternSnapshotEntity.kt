package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "circadian_pattern_snapshots",
    indices = [
        Index("segmentSource"),
        Index("stableWindowDays"),
        Index("updatedAt")
    ]
)
data class CircadianPatternSnapshotEntity(
    @PrimaryKey val dayType: String,
    val segmentSource: String,
    val stableWindowDays: Int,
    val recencyWindowDays: Int,
    val recencyWeight: Double,
    val coverageDays: Int,
    val sampleCount: Int,
    val segmentFallback: Boolean,
    val fallbackReason: String?,
    val confidence: Double,
    val qualityScore: Double,
    val updatedAt: Long
)
