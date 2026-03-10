package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.CircadianPatternSnapshotEntity
import io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianSlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianTransitionStatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CircadianPatternDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSlotStats(items: List<CircadianSlotStatEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransitionStats(items: List<CircadianTransitionStatEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshots(items: List<CircadianPatternSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReplaySlotStats(items: List<CircadianReplaySlotStatEntity>)

    @Query("DELETE FROM circadian_slot_stats")
    suspend fun clearSlotStats()

    @Query("DELETE FROM circadian_transition_stats")
    suspend fun clearTransitionStats()

    @Query("DELETE FROM circadian_pattern_snapshots")
    suspend fun clearSnapshots()

    @Query("DELETE FROM circadian_replay_slot_stats")
    suspend fun clearReplaySlotStats()

    @Query("SELECT * FROM circadian_slot_stats ORDER BY dayType, windowDays, slotIndex")
    fun observeSlotStats(): Flow<List<CircadianSlotStatEntity>>

    @Query("SELECT * FROM circadian_transition_stats ORDER BY dayType, windowDays, slotIndex, horizonMinutes")
    fun observeTransitionStats(): Flow<List<CircadianTransitionStatEntity>>

    @Query("SELECT * FROM circadian_pattern_snapshots ORDER BY dayType")
    fun observeSnapshots(): Flow<List<CircadianPatternSnapshotEntity>>

    @Query(
        "SELECT * FROM circadian_replay_slot_stats " +
            "ORDER BY dayType, windowDays, slotIndex, horizonMinutes"
    )
    fun observeReplaySlotStats(): Flow<List<CircadianReplaySlotStatEntity>>

    @Query("SELECT * FROM circadian_pattern_snapshots ORDER BY dayType")
    suspend fun allSnapshots(): List<CircadianPatternSnapshotEntity>

    @Query("SELECT * FROM circadian_slot_stats ORDER BY dayType, windowDays, slotIndex")
    suspend fun allSlotStats(): List<CircadianSlotStatEntity>

    @Query("SELECT * FROM circadian_transition_stats ORDER BY dayType, windowDays, slotIndex, horizonMinutes")
    suspend fun allTransitionStats(): List<CircadianTransitionStatEntity>

    @Query(
        "SELECT * FROM circadian_replay_slot_stats " +
            "ORDER BY dayType, windowDays, slotIndex, horizonMinutes"
    )
    suspend fun allReplaySlotStats(): List<CircadianReplaySlotStatEntity>

    @Query("SELECT * FROM circadian_slot_stats WHERE dayType = :dayType AND windowDays = :windowDays ORDER BY slotIndex")
    suspend fun slotStats(dayType: String, windowDays: Int): List<CircadianSlotStatEntity>

    @Query(
        "SELECT * FROM circadian_transition_stats " +
            "WHERE dayType = :dayType AND windowDays = :windowDays AND slotIndex = :slotIndex " +
            "ORDER BY horizonMinutes"
    )
    suspend fun transitionStats(dayType: String, windowDays: Int, slotIndex: Int): List<CircadianTransitionStatEntity>

    @Query(
        "SELECT * FROM circadian_replay_slot_stats " +
            "WHERE dayType = :dayType AND windowDays = :windowDays AND slotIndex = :slotIndex " +
            "ORDER BY horizonMinutes"
    )
    suspend fun replaySlotStats(dayType: String, windowDays: Int, slotIndex: Int): List<CircadianReplaySlotStatEntity>

    @Query(
        "SELECT * FROM circadian_replay_slot_stats " +
            "WHERE dayType = :dayType AND windowDays = :windowDays " +
            "ORDER BY slotIndex, horizonMinutes"
    )
    suspend fun replaySlotStatsForWindow(dayType: String, windowDays: Int): List<CircadianReplaySlotStatEntity>

    @Query("SELECT * FROM circadian_pattern_snapshots WHERE dayType = :dayType LIMIT 1")
    suspend fun snapshot(dayType: String): CircadianPatternSnapshotEntity?

    @Query("SELECT COUNT(*) FROM circadian_slot_stats")
    suspend fun countSlotStats(): Int

    @Query("SELECT COUNT(*) FROM circadian_transition_stats")
    suspend fun countTransitionStats(): Int

    @Query("SELECT COUNT(*) FROM circadian_pattern_snapshots")
    suspend fun countSnapshots(): Int

    @Query("SELECT COUNT(*) FROM circadian_replay_slot_stats")
    suspend fun countReplaySlotStats(): Int

    @Query(
        "SELECT COUNT(*) FROM circadian_replay_slot_stats " +
            "WHERE sampleCount >= 8 AND (" +
            "(dayType = 'ALL' AND coverageDays >= 4) OR " +
            "(dayType = 'WEEKDAY' AND coverageDays >= 3) OR " +
            "(dayType = 'WEEKEND' AND coverageDays >= 2)" +
            ")"
    )
    suspend fun countQualifiedReplaySlotStats(): Int

    @Query(
        "SELECT COUNT(*) FROM circadian_replay_slot_stats " +
            "WHERE sampleCount >= 8 AND (" +
            "(dayType = 'ALL' AND coverageDays >= 4) OR " +
            "(dayType = 'WEEKDAY' AND coverageDays >= 3) OR " +
            "(dayType = 'WEEKEND' AND coverageDays >= 2)" +
            ") AND ABS(maeImprovementMmol) < 1e-9 AND ABS(winRate) < 1e-9"
    )
    suspend fun countPollutedZeroQualityReplaySlotStats(): Int

    @Query("SELECT MAX(updatedAt) FROM circadian_pattern_snapshots")
    suspend fun latestSnapshotUpdatedAt(): Long?

    @Query("SELECT MAX(updatedAt) FROM circadian_replay_slot_stats")
    suspend fun latestReplayUpdatedAt(): Long?
}
