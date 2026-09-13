package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.aaps.copilot.data.local.entity.AlertEventEntity
import kotlinx.coroutines.flow.Flow

data class GlucoseAlertEpisodeProjection(
    @Embedded val event: AlertEventEntity,
    val receiptSuppressionUntil: Long?,
    val hasDeliveredReceipt: Boolean
)

@Dao
interface AlertEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AlertEventEntity): Long

    @Update
    suspend fun update(entity: AlertEventEntity): Int

    @Transaction
    suspend fun upsert(entity: AlertEventEntity) {
        if (insert(entity) == -1L) check(update(entity) == 1) {
            "Alert event disappeared while updating ${entity.episodeId}"
        }
    }

    @Query("SELECT * FROM alert_events WHERE episodeId = :episodeId LIMIT 1")
    suspend fun byEpisodeId(episodeId: String): AlertEventEntity?

    @Query("SELECT * FROM alert_events WHERE episodeId = :episodeId LIMIT 1")
    fun observeByEpisodeId(episodeId: String): Flow<AlertEventEntity?>

    @Query(
        "SELECT * FROM alert_events " +
            "WHERE eventType IN ('GLUCOSE_ALERT_LOW', 'GLUCOSE_ALERT_HIGH') AND status != 'RESOLVED' " +
            "ORDER BY createdAt DESC, episodeId DESC LIMIT 1"
    )
    suspend fun latestUnresolvedGlucoseEpisode(): AlertEventEntity?

    @Query("SELECT episodeId FROM alert_events WHERE eventType = 'DELIVERY_DIAGNOSTIC' AND status != 'RESOLVED' LIMIT 2")
    suspend fun unresolvedDeliveryDiagnosticIds(): List<String>

    @Query(
        "SELECT e.*, (SELECT MAX(r.suppressionUntil) FROM alert_delivery_receipts r " +
            "WHERE r.episodeId = e.episodeId AND r.kind = 'SUPPRESSED_SNOOZE' " +
            "AND r.result = 'SUPPRESSED') AS receiptSuppressionUntil, " +
            "EXISTS(SELECT 1 FROM alert_delivery_receipts d WHERE d.episodeId = e.episodeId " +
            "AND d.result = 'DELIVERED') AS hasDeliveredReceipt " +
            "FROM alert_events e WHERE e.eventType IN ('GLUCOSE_ALERT_LOW', 'GLUCOSE_ALERT_HIGH') " +
            "AND e.status != 'RESOLVED' ORDER BY e.createdAt DESC, e.episodeId DESC LIMIT 1"
    )
    fun observeLatestUnresolvedGlucoseEpisode(): Flow<GlucoseAlertEpisodeProjection?>

    @Query(
        "SELECT e.*, (SELECT MAX(r.suppressionUntil) FROM alert_delivery_receipts r " +
            "WHERE r.episodeId = e.episodeId AND r.kind = 'SUPPRESSED_SNOOZE' " +
            "AND r.result = 'SUPPRESSED') AS receiptSuppressionUntil, " +
            "EXISTS(SELECT 1 FROM alert_delivery_receipts d WHERE d.episodeId = e.episodeId " +
            "AND d.result = 'DELIVERED') AS hasDeliveredReceipt " +
            "FROM alert_events e WHERE e.eventType IN ('GLUCOSE_ALERT_LOW', 'GLUCOSE_ALERT_HIGH') " +
            "AND e.createdAt >= :fromTs AND e.createdAt < :throughTs " +
            "ORDER BY e.createdAt DESC, e.episodeId DESC LIMIT :limit"
    )
    fun observeGlucoseHistory(
        fromTs: Long,
        throughTs: Long,
        limit: Int
    ): Flow<List<GlucoseAlertEpisodeProjection>>

    @Query(
        "SELECT e.*, (SELECT MAX(r.suppressionUntil) FROM alert_delivery_receipts r " +
            "WHERE r.episodeId = e.episodeId AND r.kind = 'SUPPRESSED_SNOOZE' " +
            "AND r.result = 'SUPPRESSED') AS receiptSuppressionUntil, " +
            "EXISTS(SELECT 1 FROM alert_delivery_receipts d WHERE d.episodeId = e.episodeId " +
            "AND d.result = 'DELIVERED') AS hasDeliveredReceipt " +
            "FROM alert_events e WHERE e.episodeId = :episodeId " +
            "AND e.eventType IN ('GLUCOSE_ALERT_LOW', 'GLUCOSE_ALERT_HIGH') LIMIT 1"
    )
    fun observeGlucoseEpisode(episodeId: String): Flow<GlucoseAlertEpisodeProjection?>

    @Query(
        "SELECT COALESCE(MAX(createdAt), 0) FROM alert_events " +
            "WHERE eventType IN ('GLUCOSE_ALERT_LOW', 'GLUCOSE_ALERT_HIGH')"
    )
    suspend fun maxGlucoseEpisodeCreatedAt(): Long

    @Query("SELECT * FROM alert_events WHERE status != 'RESOLVED' ORDER BY updatedAt DESC")
    fun observeOpen(): Flow<List<AlertEventEntity>>

    @Query(
        "SELECT * FROM alert_events WHERE updatedAt BETWEEN :fromTs AND :throughTs " +
            "ORDER BY updatedAt ASC, episodeId ASC"
    )
    suspend fun between(fromTs: Long, throughTs: Long): List<AlertEventEntity>

    @Query("DELETE FROM alert_events WHERE resolvedAt IS NOT NULL AND resolvedAt < :olderThan")
    suspend fun deleteResolvedOlderThan(olderThan: Long): Int
}
