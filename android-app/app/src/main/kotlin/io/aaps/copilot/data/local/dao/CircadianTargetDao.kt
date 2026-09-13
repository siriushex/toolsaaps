package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.aaps.copilot.data.local.entity.CircadianTargetAdjustmentEntity
import io.aaps.copilot.data.local.entity.CircadianTargetRunEntity

@Dao
interface CircadianTargetDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: CircadianTargetRunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAdjustments(adjustments: List<CircadianTargetAdjustmentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFailedRun(run: CircadianTargetRunEntity)

    @Query(
        "DELETE FROM circadian_target_runs WHERE scheduleRevision = :scheduleRevision " +
            "AND localRunDate = :localRunDate AND status = 'FAILED'"
    )
    suspend fun deleteFailedRunForDate(scheduleRevision: Long, localRunDate: String): Int

    @Transaction
    suspend fun publishRun(
        run: CircadianTargetRunEntity,
        adjustments: List<CircadianTargetAdjustmentEntity>
    ) {
        require(adjustments.size <= MAX_ADJUSTMENTS_PER_RUN) {
            "A circadian target run may publish at most $MAX_ADJUSTMENTS_PER_RUN adjustments"
        }
        require(adjustments.all { it.runId == run.runId }) {
            "Every circadian adjustment must reference the published run"
        }
        require(adjustments.all { it.scheduleRevision == run.scheduleRevision }) {
            "Every circadian adjustment must use the published schedule revision"
        }

        deleteFailedRunForDate(run.scheduleRevision, run.localRunDate)
        insertRun(run)
        if (adjustments.isNotEmpty()) insertAdjustments(adjustments)
    }

    @Query(
        "SELECT * FROM circadian_target_runs " +
            "WHERE scheduleRevision = :scheduleRevision " +
            "AND completedAt > 0 " +
            "ORDER BY completedAt DESC, startedAt DESC, runId DESC LIMIT 1"
    )
    suspend fun latestCompletedRun(
        scheduleRevision: Long
    ): CircadianTargetRunEntity?

    @Query(
        "SELECT * FROM circadian_target_runs " +
            "WHERE scheduleRevision = :scheduleRevision AND localRunDate = :localRunDate LIMIT 1"
    )
    suspend fun runForLocalDate(
        scheduleRevision: Long,
        localRunDate: String
    ): CircadianTargetRunEntity?

    @Query(
        "SELECT * FROM circadian_target_adjustments " +
            "WHERE runId = :runId ORDER BY dayType ASC, hour ASC, id ASC"
    )
    suspend fun adjustmentsByRunId(runId: String): List<CircadianTargetAdjustmentEntity>

    @Query(
        "SELECT adjustment.* FROM circadian_target_adjustments AS adjustment " +
            "WHERE adjustment.scheduleRevision = :scheduleRevision " +
            "AND adjustment.dayType = :dayType " +
            "AND adjustment.hour = :hour " +
            "AND adjustment.runId = (" +
            "SELECT run.runId FROM circadian_target_runs AS run " +
            "WHERE run.scheduleRevision = :scheduleRevision " +
            "AND run.completedAt > 0 " +
            "AND run.status IN ('SUCCESS', 'COMPLETED', 'OFF', 'WAITING_DATA', " +
            "'BLOCKED_SENSOR', 'BLOCKED_LOW_RISK', 'WAITING_WRITER', 'ACTIVE') " +
            "ORDER BY run.completedAt DESC, run.startedAt DESC, run.runId DESC LIMIT 1" +
            ") " +
            "ORDER BY adjustment.generatedAt DESC, adjustment.id DESC LIMIT 1"
    )
    suspend fun currentAdjustment(
        scheduleRevision: Long,
        dayType: String,
        hour: Int
    ): CircadianTargetAdjustmentEntity?

    @Query(
        "SELECT adjustment.* FROM circadian_target_adjustments AS adjustment " +
            "INNER JOIN circadian_target_runs AS run ON run.runId = adjustment.runId " +
            "WHERE adjustment.scheduleRevision = :scheduleRevision " +
            "AND adjustment.dayType = :dayType " +
            "AND adjustment.hour = :hour " +
            "AND run.scheduleRevision = :scheduleRevision " +
            "AND run.completedAt > 0 " +
            "AND run.completedAt < :beforeCompletedAtExclusive " +
            "AND run.status IN ('SUCCESS', 'COMPLETED', 'OFF', 'WAITING_DATA', " +
            "'BLOCKED_SENSOR', 'BLOCKED_LOW_RISK', 'WAITING_WRITER', 'ACTIVE') " +
            "ORDER BY run.completedAt DESC, run.startedAt DESC, run.runId DESC, " +
            "adjustment.generatedAt DESC, adjustment.id DESC LIMIT 1"
    )
    suspend fun previousSuccessfulAdjustment(
        scheduleRevision: Long,
        dayType: String,
        hour: Int,
        beforeCompletedAtExclusive: Long
    ): CircadianTargetAdjustmentEntity?

    @Query(
        "SELECT localRunDate FROM circadian_target_runs " +
        "WHERE scheduleRevision = :scheduleRevision AND completedAt > 0 " +
        "AND status IN ('SUCCESS', 'COMPLETED', 'OFF', 'WAITING_DATA', " +
        "'BLOCKED_SENSOR', 'BLOCKED_LOW_RISK', 'WAITING_WRITER', 'ACTIVE') " +
        "ORDER BY completedAt DESC, startedAt DESC, runId DESC LIMIT 1"
    )
    suspend fun latestCompletedLocalRunDate(scheduleRevision: Long): String?

    @Query("DELETE FROM circadian_target_runs WHERE completedAt < :cutoff")
    suspend fun deleteRunsCompletedBefore(cutoff: Long): Int

    companion object {
        const val MAX_ADJUSTMENTS_PER_RUN = 72
    }
}
