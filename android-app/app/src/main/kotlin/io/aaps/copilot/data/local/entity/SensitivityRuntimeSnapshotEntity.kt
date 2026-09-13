package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sensitivity_runtime_snapshots",
    indices = [
        Index("generatedAt"),
        Index(value = ["settingsRevision", "generatedAt"])
    ]
)
data class SensitivityRuntimeSnapshotEntity(
    @PrimaryKey val cycleId: String,
    val settingsRevision: Long,
    val generatedAt: Long,
    val isfRequestedSource: String,
    val isfResolvedSource: String,
    val isfRawAaps: Double?,
    val isfRawEvidence: Double?,
    val isfRawCopilot: Double,
    val isfBlended: Double?,
    val isfEffective: Double,
    val isfConfidence: Double,
    val isfFallbackReason: String?,
    val crRequestedSource: String,
    val crResolvedSource: String,
    val crRawAaps: Double?,
    val crRawEvidence: Double?,
    val crRawCopilot: Double,
    val crBlended: Double?,
    val crEffective: Double,
    val crConfidence: Double,
    val crFallbackReason: String?
)
