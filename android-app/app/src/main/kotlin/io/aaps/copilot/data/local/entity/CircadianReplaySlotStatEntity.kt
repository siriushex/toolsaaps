package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "circadian_replay_slot_stats",
    primaryKeys = ["dayType", "windowDays", "slotIndex", "horizonMinutes"],
    indices = [
        Index("dayType"),
        Index("windowDays"),
        Index("slotIndex"),
        Index("horizonMinutes"),
        Index("dayType", "windowDays"),
        Index("dayType", "slotIndex", "horizonMinutes")
    ]
)
data class CircadianReplaySlotStatEntity(
    val dayType: String,
    val windowDays: Int,
    val slotIndex: Int,
    val horizonMinutes: Int,
    val sampleCount: Int,
    val coverageDays: Int,
    val maeBaseline: Double,
    val maeCircadian: Double,
    val maeImprovementMmol: Double,
    val medianSignedErrorBaseline: Double,
    val medianSignedErrorCircadian: Double,
    val winRate: Double,
    val qualityScore: Double,
    val updatedAt: Long
)
