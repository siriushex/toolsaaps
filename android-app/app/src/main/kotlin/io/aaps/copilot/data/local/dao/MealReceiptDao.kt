package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.aaps.copilot.data.local.entity.MealReceiptEntity

@Dao
interface MealReceiptDao {
    @Query("SELECT * FROM meal_state_receipts WHERE canonicalId = :id")
    suspend fun get(id: String): MealReceiptEntity?

    @Query("SELECT * FROM meal_state_receipts WHERE canonicalId IN (:ids)")
    suspend fun getAll(ids: List<String>): List<MealReceiptEntity>

    @Upsert suspend fun upsert(row: MealReceiptEntity)

    @Query("SELECT COUNT(*) FROM meal_state_receipts WHERE inputId = :id")
    suspend fun ownerCount(id: String): Int

    @Query("UPDATE meal_state_receipts SET conflicted = 1 WHERE inputId = :id")
    suspend fun quarantineInput(id: String)

    @Query("SELECT COUNT(*) FROM meal_state_receipts WHERE conflicted = 1")
    suspend fun conflictCount(): Int

    @Query("""SELECT * FROM meal_state_receipts
        WHERE inputId = :inputId OR canonicalId = :canonicalId LIMIT 2""")
    suspend fun relevant(inputId: String, canonicalId: String?): List<MealReceiptEntity>

    @Query("""SELECT r.* FROM meal_state_receipts r INNER JOIN meal_states s ON s.episodeId = r.inputId
        WHERE r.conflicted = 0 AND (r.appliedRevision IS NULL OR r.appliedRevision != r.revision)
        ORDER BY r.canonicalId LIMIT 64""")
    suspend fun pending(): List<MealReceiptEntity>
}
