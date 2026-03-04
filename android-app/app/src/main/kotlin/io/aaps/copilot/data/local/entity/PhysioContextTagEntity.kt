package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "physio_context_tags",
    indices = [Index("tsStart"), Index("tsEnd"), Index("tagType")]
)
data class PhysioContextTagEntity(
    @PrimaryKey val id: String,
    val tsStart: Long,
    val tsEnd: Long,
    val tagType: String,
    val severity: Double,
    val source: String,
    val note: String
)
