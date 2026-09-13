package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import io.aaps.copilot.data.local.entity.TargetManagerStateEntity

data class ClinicalTargetManagerEvidenceProjection(
    val id: String,
    val timestamp: Long,
    val outcome: String,
    val winnerJson: String?,
    val reasonCodesJson: String
)

@Dao
interface TargetManagerDao {

    @Query("SELECT * FROM target_manager_state WHERE mode = :mode LIMIT 1")
    suspend fun state(mode: String): TargetManagerStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: TargetManagerStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDecision(decision: TargetManagerDecisionEntity): Long

    @Update
    suspend fun updateDecision(decision: TargetManagerDecisionEntity): Int

    @Query(
        "DELETE FROM telemetry_samples " +
            "WHERE id = 'copilot_target_manager:target_manager_live_status' " +
            "AND source = 'copilot_target_manager' AND `key` = 'target_manager_live_status'"
    )
    suspend fun invalidateTargetManagerLiveStatus(): Int

    // Pending journal and live-status invalidation commit together before transport may start.
    @Transaction
    suspend fun insertPendingDecision(decision: TargetManagerDecisionEntity): Long {
        require(decision.deliveryStatus == "pending")
        val inserted = insertDecision(decision)
        if (inserted != -1L) invalidateTargetManagerLiveStatus()
        return inserted
    }

    @Transaction
    suspend fun updatePendingDecision(decision: TargetManagerDecisionEntity): Int {
        require(decision.deliveryStatus == "pending")
        val updated = updateDecision(decision)
        if (updated == 1) invalidateTargetManagerLiveStatus()
        return updated
    }

    @Query(
        "SELECT * FROM target_manager_decisions " +
            "WHERE mode = :mode AND semanticFingerprint = :semanticFingerprint LIMIT 1"
    )
    suspend fun decisionByFingerprint(
        mode: String,
        semanticFingerprint: String
    ): TargetManagerDecisionEntity?

    @Query(
        "SELECT * FROM target_manager_decisions " +
            "WHERE deliveryStatus = 'pending' ORDER BY timestamp ASC, id ASC"
    )
    suspend fun pendingDecisions(): List<TargetManagerDecisionEntity>

    @Query("SELECT * FROM target_manager_decisions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latestDecisions(limit: Int): List<TargetManagerDecisionEntity>

    @Query(
        "SELECT * FROM target_manager_decisions " +
            "WHERE mode = :mode AND deliveryStatus = 'quarantined' " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1"
    )
    suspend fun latestQuarantinedDecision(mode: String): TargetManagerDecisionEntity?

    @Query(
        "SELECT * FROM target_manager_decisions " +
            "WHERE timestamp BETWEEN :fromTs AND :throughTs ORDER BY timestamp ASC, id ASC"
    )
    suspend fun between(fromTs: Long, throughTs: Long): List<TargetManagerDecisionEntity>

    @Query(
        "SELECT id, timestamp, outcome, winnerJson, reasonCodesJson " +
            "FROM target_manager_decisions " +
            "WHERE timestamp BETWEEN :fromTs AND :throughTs " +
            "ORDER BY timestamp ASC, id ASC LIMIT :limit"
    )
    suspend fun clinicalEvidenceBetween(
        fromTs: Long,
        throughTs: Long,
        limit: Int
    ): List<ClinicalTargetManagerEvidenceProjection>

    @Query(
        "SELECT MAX(timestamp) FROM target_manager_decisions " +
            "WHERE deliveryStatus = 'read_only'"
    )
    suspend fun latestReadOnlyDiagnosticTimestamp(): Long?

    @Query(
        "DELETE FROM target_manager_decisions " +
            "WHERE deliveryStatus = 'read_only' AND timestamp < :olderThan"
    )
    suspend fun deleteReadOnlyDiagnosticsOlderThan(olderThan: Long): Int

    @Query(
        "DELETE FROM target_manager_decisions " +
            "WHERE deliveryStatus = 'read_only' AND id NOT IN (" +
            "SELECT id FROM target_manager_decisions WHERE deliveryStatus = 'read_only' " +
            "ORDER BY timestamp DESC, id DESC LIMIT :maxRows)"
    )
    suspend fun deleteReadOnlyDiagnosticsBeyondLimit(maxRows: Int): Int

    @Transaction
    suspend fun insertReadOnlyDiagnosticAndPrune(
        decision: TargetManagerDecisionEntity,
        retentionMs: Long,
        maxRows: Int
    ): Long {
        require(decision.deliveryStatus == "read_only")
        require(retentionMs > 0L)
        require(maxRows > 0)
        val inserted = insertDecision(decision)
        val highWater = maxOf(
            decision.timestamp,
            latestReadOnlyDiagnosticTimestamp() ?: decision.timestamp
        )
        val cutoff = try {
            Math.subtractExact(highWater, retentionMs)
        } catch (_: ArithmeticException) {
            Long.MIN_VALUE
        }
        deleteReadOnlyDiagnosticsOlderThan(cutoff)
        deleteReadOnlyDiagnosticsBeyondLimit(maxRows)
        return inserted
    }

    @Transaction
    suspend fun pruneReadOnlyDiagnostics(
        nowTs: Long,
        retentionMs: Long,
        maxRows: Int
    ): Int {
        require(retentionMs > 0L)
        require(maxRows > 0)
        val highWater = maxOf(nowTs, latestReadOnlyDiagnosticTimestamp() ?: nowTs)
        val cutoff = try {
            Math.subtractExact(highWater, retentionMs)
        } catch (_: ArithmeticException) {
            Long.MIN_VALUE
        }
        val removedByAge = deleteReadOnlyDiagnosticsOlderThan(cutoff)
        return removedByAge + deleteReadOnlyDiagnosticsBeyondLimit(maxRows)
    }

    @Transaction
    suspend fun insertDecisionAndState(
        decision: TargetManagerDecisionEntity,
        state: TargetManagerStateEntity
    ): Long {
        val inserted = insertDecision(decision)
        if (inserted != -1L) upsertState(state)
        return inserted
    }

    @Transaction
    suspend fun updateDecisionAndState(
        decision: TargetManagerDecisionEntity,
        state: TargetManagerStateEntity
    ): Int {
        val updated = updateDecision(decision)
        if (updated == 1) upsertState(state)
        return updated
    }
}
