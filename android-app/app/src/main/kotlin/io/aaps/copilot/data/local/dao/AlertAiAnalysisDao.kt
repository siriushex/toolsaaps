package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.aaps.copilot.data.local.entity.AlertAiAnalysisEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertAiAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AlertAiAnalysisEntity): Long

    @Update
    suspend fun update(entity: AlertAiAnalysisEntity): Int

    @Query(
        """
        UPDATE alert_ai_analyses
        SET requestHash = :newRequestHash,
            status = :newStatus,
            resultJson = :newResultJson,
            completedAt = :newCompletedAt,
            sanitizedError = :newSanitizedError
        WHERE analysisId = :analysisId
          AND episodeId = :episodeId
          AND provider = :provider
          AND model = :model
          AND requestHash = :expectedRequestHash
          AND status = 'RUNNING'
          AND resultJson IS NULL
          AND completedAt IS NULL
          AND sanitizedError IS NULL
        """
    )
    suspend fun transitionOwnedRunning(
        analysisId: String,
        episodeId: String,
        provider: String,
        model: String,
        expectedRequestHash: String,
        newRequestHash: String,
        newStatus: String,
        newResultJson: String?,
        newCompletedAt: Long?,
        newSanitizedError: String?
    ): Int

    @Query("SELECT * FROM alert_ai_analyses WHERE episodeId = :episodeId LIMIT 1")
    suspend fun byEpisodeId(episodeId: String): AlertAiAnalysisEntity?

    @Query("SELECT * FROM alert_ai_analyses WHERE episodeId = :episodeId LIMIT 1")
    fun observeByEpisodeId(episodeId: String): Flow<AlertAiAnalysisEntity?>

    @Query("SELECT * FROM alert_ai_analyses WHERE status = :status ORDER BY requestedAt ASC")
    suspend fun byStatus(status: String): List<AlertAiAnalysisEntity>
}
