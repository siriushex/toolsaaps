package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "energy_profile_snapshots",
    indices = [Index("calculatedAtMs")]
)
data class EnergyProfileSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val schemaVersion: Int,
    val evidenceStartMs: Long,
    val evidenceEndMs: Long,
    val qualityDays: Int,
    val tier: String,
    val foodProfile: String?,
    val foodDurationMinutes: Int?,
    val activityProfile: String?,
    val confidence: Double,
    val replayPassed: Boolean,
    val sourceHashSha256: String,
    val calculatedAtMs: Long,
    val stale: Boolean
)
