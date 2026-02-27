package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rule_executions",
    indices = [Index("timestamp"), Index("ruleId")]
)
data class RuleExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val ruleId: String,
    val state: String,
    val reasonsJson: String,
    val actionJson: String?
)
