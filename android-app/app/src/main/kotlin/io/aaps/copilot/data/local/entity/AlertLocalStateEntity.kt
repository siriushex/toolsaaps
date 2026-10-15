package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "alert_local_state",
    primaryKeys = ["sourceKind", "sourceId"],
    indices = [Index(value = ["bootCount", "nextDueElapsedMs"]),
        Index(value = ["bootCount", "pauseUntilWallMs"]),
        Index(value = ["bootCount", "cycleDeadlineElapsedMs"])]
)
data class AlertLocalStateEntity(
    val sourceKind: String,
    val sourceId: String,
    val generation: Long,
    val bootCount: Int,
    val ordinal: Long,
    val revision: Long,
    val updatedAtMs: Long,
    val nextDueElapsedMs: Long?,
    val pauseUntilWallMs: Long?,
    val cycleDeadlineElapsedMs: Long?,
    val stateJson: String
)

@Entity(
    tableName = "alert_local_cycles",
    primaryKeys = ["sourceKind", "sourceId", "generation", "ordinal"],
    indices = [Index("claimedAtMs"), Index(value = ["status", "claimedAtMs"])]
)
data class AlertLocalCycleEntity(
    val sourceKind: String,
    val sourceId: String,
    val generation: Long,
    val ordinal: Long,
    val bootCount: Int,
    val level: String,
    val startedElapsedMs: Long,
    val deadlineElapsedMs: Long,
    val claimedAtMs: Long,
    val terminalAtMs: Long?,
    val status: String,
    val resultJson: String
)
