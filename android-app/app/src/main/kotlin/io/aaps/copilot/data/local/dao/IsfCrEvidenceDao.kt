package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.IsfCrEvidenceEntity

@Dao
interface IsfCrEvidenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<IsfCrEvidenceEntity>)

    @Query("SELECT * FROM isf_cr_evidence WHERE ts >= :since ORDER BY ts DESC")
    suspend fun since(since: Long): List<IsfCrEvidenceEntity>

    @Query("DELETE FROM isf_cr_evidence WHERE ts < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
