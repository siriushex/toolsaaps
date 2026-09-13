package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "context_event_sync",
    indices = [
        Index(value = ["eventId", "revision", "operation"], unique = true),
        Index(value = ["status", "attemptedAt"])
    ]
)
data class ContextEventSyncEntity(
    @PrimaryKey val syncId: String,
    val eventId: String,
    val revision: Long,
    val operation: String,
    val requestHash: String,
    val status: String,
    val attemptedAt: Long,
    val completedAt: Long?,
    val sanitizedError: String?
)
