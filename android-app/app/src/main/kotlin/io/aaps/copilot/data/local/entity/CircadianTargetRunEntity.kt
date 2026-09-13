package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "circadian_target_runs",
    indices = [
        Index("completedAt"),
        Index(value = ["scheduleRevision", "localRunDate"], unique = true)
    ]
)
data class CircadianTargetRunEntity(
    @PrimaryKey val runId: String,
    val scheduleRevision: Long,
    val localRunDate: String,
    val startedAt: Long,
    val completedAt: Long,
    val lookbackStart: Long,
    val lookbackEnd: Long,
    val status: String,
    val validDays: Int,
    val trustedShare: Double,
    val lowRiskPassed: Boolean,
    val reasonCodesJson: String
)
