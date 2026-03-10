package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "circadian_transition_stats",
    primaryKeys = ["dayType", "windowDays", "slotIndex", "horizonMinutes"],
    indices = [
        Index("dayType"),
        Index("windowDays"),
        Index("slotIndex"),
        Index("horizonMinutes"),
        Index(value = ["dayType", "windowDays", "horizonMinutes"])
    ]
)
data class CircadianTransitionStatEntity(
    val dayType: String,
    val windowDays: Int,
    val slotIndex: Int,
    val horizonMinutes: Int,
    val sampleCount: Int,
    val deltaMedian: Double,
    val deltaP25: Double,
    val deltaP75: Double,
    val residualBiasMmol: Double,
    val confidence: Double,
    val updatedAt: Long
)
