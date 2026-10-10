package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import kotlinx.coroutines.flow.Flow

data class AutomaticSentCommandEvidence(
    val commandCount: Int,
    val earliestTimestamp: Long?,
    val latestTimestamp: Long?
)

@Dao
interface ActionCommandDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(command: ActionCommandEntity)

    @Query(
        "SELECT * FROM action_commands WHERE idempotencyKey = :idempotencyKey " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1"
    )
    suspend fun byIdempotencyKey(idempotencyKey: String): ActionCommandEntity?

    @Query("SELECT * FROM action_commands WHERE idempotencyKey = :idempotencyKey LIMIT 2")
    suspend fun forTargetObservationProof(idempotencyKey: String): List<ActionCommandEntity>

    @Query(
        "DELETE FROM action_commands " +
            "WHERE idempotencyKey = :idempotencyKey AND type = :type AND status = :status"
    )
    suspend fun deleteByIdempotencyKeyTypeAndStatus(
        idempotencyKey: String,
        type: String,
        status: String
    ): Int

    @Query(
        "SELECT COUNT(*) AS commandCount, MIN(timestamp) AS earliestTimestamp, " +
            "MAX(timestamp) AS latestTimestamp FROM action_commands " +
            "WHERE status = 'SENT' AND type = 'temp_target' AND timestamp < :beforeTimestamp " +
            "AND (idempotencyKey LIKE 'AdaptiveTargetController.v1:%' " +
            "OR idempotencyKey LIKE 'TargetManager.v1:%')"
    )
    suspend fun automaticSentTargetEvidenceBefore(
        beforeTimestamp: Long
    ): AutomaticSentCommandEvidence

    @Query("SELECT COUNT(*) FROM action_commands WHERE status = :status AND timestamp >= :since")
    suspend fun countByStatusSince(status: String, since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM action_commands " +
            "WHERE status = :status AND timestamp >= :since AND idempotencyKey NOT LIKE :excludedPrefix"
    )
    suspend fun countByStatusSinceExcludingPrefix(
        status: String,
        since: Long,
        excludedPrefix: String
    ): Int

    @Query(
        "SELECT COUNT(*) FROM action_commands " +
            "WHERE status = :status AND timestamp BETWEEN :since AND :through " +
            "AND idempotencyKey NOT LIKE :excludedPrefix1 " +
            "AND idempotencyKey NOT LIKE :excludedPrefix2"
    )
    suspend fun countByStatusBetweenExcludingTwoPrefixes(
        status: String,
        since: Long,
        through: Long,
        excludedPrefix1: String,
        excludedPrefix2: String
    ): Int

    @Query(
        "SELECT MAX(timestamp) FROM action_commands " +
            "WHERE status = :status AND type = :type " +
            "AND timestamp <= :through " +
            "AND idempotencyKey NOT LIKE :excludedPrefix"
    )
    suspend fun latestTimestampByTypeAndStatusAtOrBeforeExcludingPrefix(
        type: String,
        status: String,
        through: Long,
        excludedPrefix: String
    ): Long?

    @Query(
        "SELECT * FROM action_commands " +
            "WHERE status = :status AND type = :type " +
            "AND timestamp <= :through " +
            "AND idempotencyKey NOT LIKE :excludedPrefix " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1"
    )
    suspend fun latestByTypeAndStatusAtOrBeforeExcludingPrefix(
        type: String,
        status: String,
        through: Long,
        excludedPrefix: String
    ): ActionCommandEntity?

    @Query("SELECT MAX(timestamp) FROM action_commands WHERE status = :status AND type = :type")
    suspend fun latestTimestampByTypeAndStatus(
        type: String,
        status: String
    ): Long?

    @Query(
        "SELECT * FROM action_commands " +
            "WHERE type = :type AND idempotencyKey LIKE :idempotencyPrefix " +
            "AND timestamp >= :since ORDER BY timestamp DESC"
    )
    suspend fun byTypeAndIdempotencyPrefixSince(
        type: String,
        idempotencyPrefix: String,
        since: Long
    ): List<ActionCommandEntity>

    @Query(
        "SELECT * FROM action_commands " +
            "WHERE type != 'uam_export_reservation' " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun latest(limit: Int): List<ActionCommandEntity>

    @Query(
        "UPDATE action_commands SET status = :newStatus " +
            "WHERE status = :currentStatus AND id IN (:ids)"
    )
    suspend fun updateStatusByIds(
        ids: List<String>,
        currentStatus: String,
        newStatus: String
    ): Int

    @Query(
        "SELECT * FROM action_commands " +
            "WHERE type != 'uam_export_reservation' " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    fun observeLatest(limit: Int): Flow<List<ActionCommandEntity>>

    @Query(
        "DELETE FROM action_commands " +
            "WHERE timestamp < :olderThan AND type != 'uam_export_reservation'"
    )
    suspend fun deleteOlderThan(olderThan: Long): Int
}
