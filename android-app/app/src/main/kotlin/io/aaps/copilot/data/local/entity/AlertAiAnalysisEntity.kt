package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alert_ai_analyses",
    foreignKeys = [
        ForeignKey(
            entity = AlertEventEntity::class,
            parentColumns = ["episodeId"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["episodeId"], unique = true),
        Index(value = ["status", "requestedAt"])
    ]
)
data class AlertAiAnalysisEntity(
    @PrimaryKey val analysisId: String,
    val episodeId: String,
    val provider: String,
    val model: String,
    val requestHash: String,
    val status: String,
    val resultJson: String?,
    val requestedAt: Long,
    val completedAt: Long?,
    val sanitizedError: String?
)
