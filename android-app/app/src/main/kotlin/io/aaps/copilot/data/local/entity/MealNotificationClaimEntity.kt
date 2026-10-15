package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meal_notification_claims")
data class MealNotificationClaimEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: String,
    val bootId: String,
    val elapsedAtMs: Long,
    val wallAtMs: Long
)

@Entity(tableName = "meal_notification_claim_aliases")
data class MealNotificationClaimAliasEntity(
    @PrimaryKey val alias: String,
    val claimId: Long
)
