package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "isf_cr_model_state")
data class IsfCrModelStateEntity(
    @PrimaryKey val id: String = "active",
    val updatedAt: Long,
    val hourlyIsfJson: String,
    val hourlyCrJson: String,
    val paramsJson: String,
    val fitMetricsJson: String
)
