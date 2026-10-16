package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.aaps.copilot.data.local.entity.LocalDiaryEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalDiaryDao {
    @Insert
    suspend fun insert(entry: LocalDiaryEntryEntity)

    @Query("SELECT * FROM local_diary_entries WHERE entryId = :id ORDER BY revision DESC LIMIT 1")
    suspend fun latest(id: String): LocalDiaryEntryEntity?

    @Query("SELECT * FROM local_diary_entries WHERE entryId = :id ORDER BY revision DESC LIMIT 100")
    suspend fun history(id: String): List<LocalDiaryEntryEntity>

    @Query("""SELECT e.* FROM local_diary_entries e
        WHERE e.deleted = :deleted AND NOT EXISTS (
            SELECT 1 FROM local_diary_entries newer
            WHERE newer.entryId = e.entryId AND newer.revision > e.revision)
        ORDER BY e.occurredAtMs DESC, e.entryId DESC LIMIT :limit""")
    fun observeLatest(deleted: Boolean, limit: Int): Flow<List<LocalDiaryEntryEntity>>
}
