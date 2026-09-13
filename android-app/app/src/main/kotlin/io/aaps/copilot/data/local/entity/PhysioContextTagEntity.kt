package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "physio_context_tags",
    indices = [
        Index("tsStart"),
        Index("tsEnd"),
        Index("tagType"),
        Index(value = ["status", "tsStart"]),
        Index("updatedAt")
    ]
)
data class PhysioContextTagEntity(
    @PrimaryKey val id: String,
    val tsStart: Long,
    val tsEnd: Long,
    val tagType: String,
    val severity: Double,
    val source: String,
    val note: String,
    @ColumnInfo(defaultValue = "''") val subtype: String = "",
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "'{}'") val attributesJson: String = "{}",
    @ColumnInfo(defaultValue = "1") val revision: Long = 1L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "'ACTIVE'") val status: String = "ACTIVE"
)
