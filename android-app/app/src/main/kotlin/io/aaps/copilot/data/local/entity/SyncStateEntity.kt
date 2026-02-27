package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val source: String,
    val lastSyncedTimestamp: Long
)
