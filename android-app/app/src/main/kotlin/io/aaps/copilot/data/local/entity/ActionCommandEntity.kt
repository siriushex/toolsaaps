package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "action_commands",
    indices = [Index("timestamp"), Index("status")]
)
data class ActionCommandEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val type: String,
    val payloadJson: String,
    val safetyJson: String,
    val idempotencyKey: String,
    val status: String
)
