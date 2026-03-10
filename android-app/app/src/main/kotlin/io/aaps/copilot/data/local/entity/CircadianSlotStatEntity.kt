package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "circadian_slot_stats",
    primaryKeys = ["dayType", "windowDays", "slotIndex"],
    indices = [
        Index("dayType"),
        Index("windowDays"),
        Index("slotIndex"),
        Index(value = ["dayType", "windowDays"])
    ]
)
data class CircadianSlotStatEntity(
    val dayType: String,
    val windowDays: Int,
    val slotIndex: Int,
    val sampleCount: Int,
    val activeDays: Int,
    val medianBg: Double,
    val p10: Double,
    val p25: Double,
    val p75: Double,
    val p90: Double,
    val pLow: Double,
    val pHigh: Double,
    val pInRange: Double,
    val fastRiseRate: Double,
    val fastDropRate: Double,
    val meanCob: Double?,
    val meanIob: Double?,
    val meanUam: Double?,
    val meanActivity: Double?,
    val confidence: Double,
    val qualityScore: Double,
    val updatedAt: Long
)
