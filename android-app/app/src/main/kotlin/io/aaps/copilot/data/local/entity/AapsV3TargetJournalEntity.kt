package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "aaps_v3_target_journal")
data class AapsV3TargetJournalEntity(
    @PrimaryKey val row: String,
    val revision: Long,
    val sealed: ByteArray
) {
    override fun toString() = "AapsV3TargetJournalEntity(redacted)"
}
