package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.aaps.copilot.data.local.entity.AlertLocalCycleEntity
import io.aaps.copilot.data.local.entity.AlertLocalStateEntity

@Dao
interface AlertLocalDao {
    @Query("SELECT * FROM alert_local_state WHERE sourceKind=:kind AND sourceId=:id")
    suspend fun state(kind: String, id: String): AlertLocalStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertState(entity: AlertLocalStateEntity): Long

    @Query("UPDATE alert_local_state SET generation=:generation, bootCount=:boot, ordinal=:ordinal, " +
        "revision=:revision, updatedAtMs=:updatedAt, nextDueElapsedMs=:due, pauseUntilWallMs=:pause, " +
        "cycleDeadlineElapsedMs=:deadline, stateJson=:json WHERE sourceKind=:kind AND sourceId=:id AND revision=:expected")
    suspend fun compareAndSetState(kind: String, id: String, expected: Long, generation: Long, boot: Int,
        ordinal: Long, revision: Long, updatedAt: Long, due: Long?, pause: Long?, deadline: Long?, json: String): Int

    @Query("SELECT * FROM alert_local_cycles WHERE sourceKind=:kind AND sourceId=:id AND generation=:generation AND ordinal=:ordinal")
    suspend fun cycle(kind: String, id: String, generation: Long, ordinal: Long): AlertLocalCycleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCycle(entity: AlertLocalCycleEntity)

    @Update
    suspend fun updateCycle(entity: AlertLocalCycleEntity): Int

    @Query("SELECT * FROM alert_local_state WHERE bootCount=:boot AND nextDueElapsedMs <= :now " +
        "ORDER BY nextDueElapsedMs, sourceKind, sourceId LIMIT MAX(1,MIN(:limit,64))")
    suspend fun dueRepeats(boot: Int, now: Long, limit: Int = 64): List<AlertLocalStateEntity>

    @Query("SELECT * FROM alert_local_state WHERE bootCount=:boot AND pauseUntilWallMs <= :now " +
        "ORDER BY pauseUntilWallMs, sourceKind, sourceId LIMIT MAX(1,MIN(:limit,64))")
    suspend fun expiredPauses(boot: Int, now: Long, limit: Int = 64): List<AlertLocalStateEntity>

    @Query("SELECT * FROM alert_local_state WHERE bootCount=:boot AND cycleDeadlineElapsedMs <= :now " +
        "ORDER BY cycleDeadlineElapsedMs, sourceKind, sourceId LIMIT MAX(1,MIN(:limit,64))")
    suspend fun expiredCycles(boot: Int, now: Long, limit: Int = 64): List<AlertLocalStateEntity>

    @Query("DELETE FROM alert_local_cycles WHERE rowid IN (SELECT rowid FROM alert_local_cycles " +
        "WHERE status IN ('FINISHED','CANCELLED','UNCERTAIN') AND claimedAtMs < :before AND terminalAtMs < :before " +
        "ORDER BY claimedAtMs LIMIT MAX(1,MIN(:limit,100)))")
    suspend fun pruneTerminal(before: Long, limit: Int): Int
}
