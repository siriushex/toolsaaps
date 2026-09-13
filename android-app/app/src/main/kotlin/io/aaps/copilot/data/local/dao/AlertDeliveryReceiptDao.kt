package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.aaps.copilot.data.local.entity.AlertDeliveryReceiptEntity

@Dao
interface AlertDeliveryReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AlertDeliveryReceiptEntity): Long

    @Update
    suspend fun update(entity: AlertDeliveryReceiptEntity): Int

    @Query("SELECT * FROM alert_delivery_receipts WHERE episodeId = :episodeId ORDER BY attemptedAt ASC")
    suspend fun byEpisodeId(episodeId: String): List<AlertDeliveryReceiptEntity>

    @Query("SELECT * FROM alert_delivery_receipts WHERE episodeId = :episodeId AND kind = :kind LIMIT 1")
    suspend fun byEpisodeAndKind(episodeId: String, kind: String): AlertDeliveryReceiptEntity?

}
