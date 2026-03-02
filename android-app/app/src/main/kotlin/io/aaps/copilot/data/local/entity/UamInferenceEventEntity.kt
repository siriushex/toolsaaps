package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "uam_inference_events",
    indices = [Index("updatedAt"), Index("state"), Index("ingestionTs")]
)
data class UamInferenceEventEntity(
    @PrimaryKey val id: String,
    val state: String,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ingestionTs: Long,
    val carbsModelG: Double,
    val carbsDisplayG: Double,
    val confidence: Double,
    val exportedGrams: Double,
    val exportSeq: Int,
    val lastExportTs: Long?,
    val learnedEligible: Boolean
)
