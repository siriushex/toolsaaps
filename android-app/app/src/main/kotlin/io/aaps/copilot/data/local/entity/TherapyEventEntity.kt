package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "therapy_events",
    indices = [Index("timestamp"), Index("type")]
)
data class TherapyEventEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val type: String,
    val payloadJson: String
)
