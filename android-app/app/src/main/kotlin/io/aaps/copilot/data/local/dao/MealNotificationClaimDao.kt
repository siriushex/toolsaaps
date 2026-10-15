package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.aaps.copilot.data.local.entity.MealNotificationClaimAliasEntity
import io.aaps.copilot.data.local.entity.MealNotificationClaimEntity

@Dao
interface MealNotificationClaimDao {
    @Query("SELECT * FROM meal_notification_claims ORDER BY id DESC LIMIT 1")
    suspend fun latest(): MealNotificationClaimEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM meal_notification_claim_aliases WHERE alias IN (:aliases))")
    suspend fun hasClaimedAlias(aliases: List<String>): Boolean

    @Insert
    suspend fun insert(claim: MealNotificationClaimEntity): Long

    @Insert
    suspend fun insertAliases(aliases: List<MealNotificationClaimAliasEntity>)

    @Query("SELECT COUNT(*) FROM meal_notification_claims")
    suspend fun count(): Int
}
