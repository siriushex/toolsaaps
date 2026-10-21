package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_ns_v3_documents",
    primaryKeys = ["collection", "identifier"],
    indices = [Index(value = ["modifiedRevision"], unique = true),
        Index(value = ["collection", "modifiedRevision"]),
        Index(value = ["collection", "deleted", "eventAtMs", "identifier"])]
)
data class LocalNightscoutV3DocumentEntity(
    val collection: String,
    val identifier: String,
    val source: String,
    val eventAtMs: Long?,
    val payloadJson: String,
    val payloadBytes: Int,
    val createdRevision: Long,
    val modifiedRevision: Long,
    val deleted: Boolean,
    val readOnly: Boolean
) {
    override fun toString() = "LocalNightscoutV3DocumentEntity(redacted)"
}

@Entity(tableName = "local_ns_v3_clock")
data class LocalNightscoutV3ClockEntity(
    @PrimaryKey val singleton: Int = 1,
    val revision: Long,
    val documentCount: Long,
    val payloadBytes: Long
)
