package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "planned_activity_events",
    indices = [Index("enabled"), Index("updatedAtMs")]
)
data class PlannedActivityEventEntity(
    @PrimaryKey val eventId: String,
    val enabled: Boolean,
    val title: String,
    val activityType: String,
    val intensity: String,
    val localStartIso: String,
    val durationMinutes: Int,
    val timezoneId: String,
    val recurrenceDaysMask: Int,
    val recurrenceEndEpochDay: Long?,
    val revision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
