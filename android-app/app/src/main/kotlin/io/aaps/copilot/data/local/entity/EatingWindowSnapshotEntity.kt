package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "eating_window_snapshots",
    indices = [Index(value = ["generatedAt", "localCompletedDate"])]
)
data class EatingWindowSnapshotEntity(
    @PrimaryKey val localCompletedDate: String,
    val generatedAt: Long,
    val sourceFingerprint: String,
    val recentWindowsJson: String,
    val stableWindowsJson: String
)
