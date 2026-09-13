package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alert_events",
    indices = [
        Index(value = ["status", "updatedAt"]),
        Index(value = ["eventType", "updatedAt"]),
        Index("resolvedAt")
    ]
)
data class AlertEventEntity(
    @PrimaryKey val episodeId: String,
    val eventType: String,
    val stage: String,
    val status: String,
    val severity: String,
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long?,
    val localSnapshotJson: String,
    val causeCode: String?,
    val causeSummary: String?,
    val suppressionUntil: Long?,
    val lastNotificationAt: Long?,
    val revision: Long
)
