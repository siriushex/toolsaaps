package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.IsfCrSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IsfCrSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IsfCrSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<IsfCrSnapshotEntity>)

    @Query("SELECT * FROM isf_cr_snapshots ORDER BY ts DESC LIMIT 1")
    suspend fun latest(): IsfCrSnapshotEntity?

    @Query("SELECT * FROM isf_cr_snapshots ORDER BY ts DESC LIMIT 1")
    fun observeLatest(): Flow<IsfCrSnapshotEntity?>

    @Query("SELECT * FROM isf_cr_snapshots ORDER BY ts DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<IsfCrSnapshotEntity>>

    @Query("SELECT * FROM isf_cr_snapshots WHERE ts >= :since ORDER BY ts DESC")
    suspend fun since(since: Long): List<IsfCrSnapshotEntity>

    @Query("DELETE FROM isf_cr_snapshots WHERE ts < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
