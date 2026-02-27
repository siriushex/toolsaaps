package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE source = :source LIMIT 1")
    suspend fun bySource(source: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state")
    fun observeAll(): Flow<List<SyncStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
