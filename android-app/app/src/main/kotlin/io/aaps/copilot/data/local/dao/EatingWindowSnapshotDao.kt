package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.EatingWindowSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EatingWindowSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: EatingWindowSnapshotEntity)

    @Query(
        "SELECT * FROM eating_window_snapshots " +
            "ORDER BY generatedAt DESC, localCompletedDate DESC LIMIT 1"
    )
    fun observeLatest(): Flow<EatingWindowSnapshotEntity?>

    @Query(
        "SELECT * FROM eating_window_snapshots " +
            "WHERE localCompletedDate = :localCompletedDate LIMIT 1"
    )
    suspend fun forLocalCompletedDate(localCompletedDate: String): EatingWindowSnapshotEntity?
}
