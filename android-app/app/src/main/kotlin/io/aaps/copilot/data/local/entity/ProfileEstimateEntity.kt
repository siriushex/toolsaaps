package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profile_estimates")
data class ProfileEstimateEntity(
    @PrimaryKey val id: String = "active",
    val timestamp: Long,
    val isfMmolPerUnit: Double,
    val crGramPerUnit: Double,
    val confidence: Double,
    val sampleCount: Int
)
