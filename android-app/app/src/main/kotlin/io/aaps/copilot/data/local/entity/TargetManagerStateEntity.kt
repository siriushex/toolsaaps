package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "target_manager_state")
data class TargetManagerStateEntity(
    @PrimaryKey val mode: String,
    val updatedAt: Long,
    val acceptedTargetJson: String?,
    val lastDecisionFingerprint: String?,
    val lastSafetyBypassFingerprint: String?,
    val reconciliationStatus: String
)
