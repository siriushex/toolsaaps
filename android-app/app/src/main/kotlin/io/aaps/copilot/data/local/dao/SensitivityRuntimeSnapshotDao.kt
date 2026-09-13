package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SensitivityRuntimeSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SensitivityRuntimeSnapshotEntity)

    @Query("SELECT * FROM sensitivity_runtime_snapshots WHERE cycleId = :cycleId LIMIT 1")
    suspend fun byCycleId(cycleId: String): SensitivityRuntimeSnapshotEntity?

    @Query("SELECT * FROM sensitivity_runtime_snapshots ORDER BY generatedAt DESC, cycleId DESC LIMIT 1")
    suspend fun latest(): SensitivityRuntimeSnapshotEntity?

    @Query("SELECT * FROM sensitivity_runtime_snapshots WHERE generatedAt <= :atTs ORDER BY generatedAt DESC, cycleId DESC LIMIT 1")
    suspend fun latestAtOrBefore(atTs: Long): SensitivityRuntimeSnapshotEntity?

    @Query(
        "SELECT * FROM sensitivity_runtime_snapshots " +
            "WHERE generatedAt <= :atTs AND settingsRevision = :currentRevision " +
            "ORDER BY generatedAt DESC, cycleId DESC LIMIT 1"
    )
    suspend fun latestAuthoritativeAtOrBefore(
        atTs: Long,
        currentRevision: Long
    ): SensitivityRuntimeSnapshotEntity?

    @Query("SELECT * FROM sensitivity_runtime_snapshots ORDER BY generatedAt DESC, cycleId DESC LIMIT 1")
    fun observeLatest(): Flow<SensitivityRuntimeSnapshotEntity?>

    @Query("DELETE FROM sensitivity_runtime_snapshots WHERE generatedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int
}
