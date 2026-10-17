package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "local_diary_entries", primaryKeys = ["entryId", "revision"],
    indices = [Index("occurredAtMs")])
data class LocalDiaryEntryEntity(
    val entryId: String,
    val revision: Int,
    val kind: String,
    val occurredAtMs: Long,
    val recordedZoneId: String,
    val amountMilli: Long,
    val insulinName: String,
    val insulinKind: String,
    val note: String,
    val deleted: Boolean,
    val recordedAtMs: Long,
    val source: String = "MANUAL_LOCAL"
)
