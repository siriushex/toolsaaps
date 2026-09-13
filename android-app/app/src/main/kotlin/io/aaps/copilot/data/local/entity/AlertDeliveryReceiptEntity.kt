package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alert_delivery_receipts",
    foreignKeys = [
        ForeignKey(
            entity = AlertEventEntity::class,
            parentColumns = ["episodeId"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["episodeId", "kind"], unique = true),
        Index("attemptedAt")
    ]
)
data class AlertDeliveryReceiptEntity(
    @PrimaryKey val receiptId: String,
    val episodeId: String,
    val kind: String,
    val attemptedAt: Long,
    val result: String,
    val suppressionUntil: Long?,
    val deliveredAt: Long?,
    val sanitizedError: String?
)
