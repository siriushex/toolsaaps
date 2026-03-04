package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "isf_cr_evidence",
    indices = [Index("ts"), Index("sampleType"), Index(value = ["sampleType", "hourLocal"])]
)
data class IsfCrEvidenceEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val sampleType: String,
    val hourLocal: Int,
    val dayType: String,
    val value: Double,
    val weight: Double,
    val qualityScore: Double,
    val contextJson: String,
    val windowJson: String
)
