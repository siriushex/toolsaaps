package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionCommandDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(command: ActionCommandEntity)

    @Query("SELECT * FROM action_commands WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun byIdempotencyKey(idempotencyKey: String): ActionCommandEntity?

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
            "WHERE status = :status AND timestamp >= :since " +
            "AND idempotencyKey NOT LIKE :excludedPrefix1 " +
            "AND idempotencyKey NOT LIKE :excludedPrefix2"
    )
    suspend fun countByStatusSinceExcludingTwoPrefixes(
        status: String,
        since: Long,
        excludedPrefix1: String,
        excludedPrefix2: String
    ): Int

    @Query(
        "SELECT MAX(timestamp) FROM action_commands " +
            "WHERE status = :status AND type = :type " +
            "AND idempotencyKey NOT LIKE :excludedPrefix"
    )
    suspend fun latestTimestampByTypeAndStatusExcludingPrefix(
        type: String,
        status: String,
        excludedPrefix: String
    ): Long?

    @Query("SELECT * FROM action_commands ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ActionCommandEntity>

    @Query("SELECT * FROM action_commands ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ActionCommandEntity>>
}
