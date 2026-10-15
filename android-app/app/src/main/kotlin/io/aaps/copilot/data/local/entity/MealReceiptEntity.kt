package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "meal_state_receipts", indices = [Index("inputId")])
data class MealReceiptEntity(
    @PrimaryKey val canonicalId: String,
    val inputId: String?,
    val revision: Long,
    val recordedAtMs: Long,
    val grams: Double,
    val deleted: Boolean,
    val conflicted: Boolean = false,
    val appliedRevision: Long? = null
)
