package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhysioContextTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PhysioContextTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<PhysioContextTagEntity>)

    @Query("SELECT * FROM physio_context_tags WHERE tsStart <= :ts AND tsEnd >= :ts ORDER BY severity DESC")
    suspend fun activeAt(ts: Long): List<PhysioContextTagEntity>

    @Query("SELECT * FROM physio_context_tags WHERE tsEnd >= :since ORDER BY tsStart DESC")
    fun observeRecent(since: Long): Flow<List<PhysioContextTagEntity>>

    @Query(
        """
        UPDATE physio_context_tags
        SET tsEnd = CASE WHEN tsEnd > :closeTs THEN :closeTs ELSE tsEnd END
        WHERE id = :id
        """
    )
    suspend fun closeById(id: String, closeTs: Long): Int

    @Query("DELETE FROM physio_context_tags WHERE tsEnd < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
