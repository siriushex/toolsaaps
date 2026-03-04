package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "isf_cr_snapshots",
    indices = [Index("ts"), Index("mode")]
)
data class IsfCrSnapshotEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val isfEff: Double,
    val crEff: Double,
    val isfBase: Double,
    val crBase: Double,
    val ciIsfLow: Double,
    val ciIsfHigh: Double,
    val ciCrLow: Double,
    val ciCrHigh: Double,
    val confidence: Double,
    val qualityScore: Double,
    val factorsJson: String,
    val mode: String
)
