package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.aaps.copilot.data.local.entity.ContextEventSyncEntity

@Dao
interface ContextEventSyncDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ContextEventSyncEntity): Long

    @Update
    suspend fun update(entity: ContextEventSyncEntity): Int

    @Query(
        "SELECT * FROM context_event_sync WHERE eventId = :eventId " +
            "AND revision = :revision AND operation = :operation LIMIT 1"
    )
    suspend fun forOperation(
        eventId: String,
        revision: Long,
        operation: String
    ): ContextEventSyncEntity?

    @Query(
        "SELECT * FROM context_event_sync WHERE eventId = :eventId " +
            "ORDER BY revision DESC, attemptedAt DESC LIMIT 1"
    )
    suspend fun latestForEvent(eventId: String): ContextEventSyncEntity?

    @Query(
        "SELECT * FROM context_event_sync WHERE eventId = :eventId AND status != 'COMPLETED' " +
            "ORDER BY revision ASC, operation ASC, attemptedAt ASC, syncId ASC LIMIT 1"
    )
    suspend fun firstUnresolvedForEvent(eventId: String): ContextEventSyncEntity?

    @Query("SELECT * FROM context_event_sync WHERE status = :status ORDER BY attemptedAt ASC")
    suspend fun byStatus(status: String): List<ContextEventSyncEntity>

    @Query(
        "SELECT candidate.* FROM context_event_sync AS candidate " +
            "WHERE candidate.status = :status AND NOT EXISTS (" +
            "SELECT 1 FROM context_event_sync AS predecessor " +
            "WHERE predecessor.eventId = candidate.eventId " +
            "AND predecessor.status != 'COMPLETED' " +
            "AND (predecessor.revision < candidate.revision " +
            "OR (predecessor.revision = candidate.revision AND predecessor.syncId < candidate.syncId))" +
            ") ORDER BY candidate.attemptedAt ASC, candidate.eventId ASC, " +
            "candidate.revision ASC, candidate.operation ASC, candidate.syncId ASC LIMIT :limit"
    )
    suspend fun orderedBatchByStatus(status: String, limit: Int): List<ContextEventSyncEntity>

    @Query("SELECT COUNT(*) FROM context_event_sync WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query(
        "DELETE FROM context_event_sync WHERE status = 'COMPLETED' " +
            "AND completedAt IS NOT NULL AND completedAt < :olderThan"
    )
    suspend fun deleteCompletedOlderThan(olderThan: Long): Int
}
