package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "clinical_reports",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["status"])
    ]
)
data class ClinicalReportEntity(
    @PrimaryKey val requestId: String,
    val status: String,
    val requestedFromTs: Long,
    val requestedThroughTs: Long,
    val requestHash: String,
    val coverageJson: String,
    val localSummaryJson: String,
    val responseJson: String?,
    val renderedText: String?,
    val model: String?,
    val provider: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val completedAt: Long?,
    val sanitizedError: String?
)
