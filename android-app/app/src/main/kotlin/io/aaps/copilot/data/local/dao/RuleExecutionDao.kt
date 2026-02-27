package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleExecutionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RuleExecutionEntity)

    @Query("SELECT * FROM rule_executions WHERE ruleId = :ruleId AND state = :state AND timestamp >= :since")
    suspend fun findByStateSince(ruleId: String, state: String, since: Long): List<RuleExecutionEntity>

    @Query("SELECT * FROM rule_executions ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<RuleExecutionEntity>>
}
