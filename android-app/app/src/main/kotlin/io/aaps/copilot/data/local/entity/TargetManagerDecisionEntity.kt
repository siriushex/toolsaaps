package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "target_manager_decisions",
    indices = [
        Index("timestamp"),
        Index(value = ["mode", "semanticFingerprint"], unique = true),
        Index(value = ["deliveryStatus", "timestamp", "id"])
    ]
)
data class TargetManagerDecisionEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val mode: String,
    val semanticFingerprint: String,
    val outcome: String,
    val winnerJson: String?,
    val commandJson: String?,
    val cadenceOutcome: String?,
    val cadenceReason: String?,
    val lastSentTargetMmol: Double?,
    val lastSentTimestamp: Long?,
    val deliveryStatus: String,
    val reasonCodesJson: String,
    val rejectedProposalReasonsJson: String
)
