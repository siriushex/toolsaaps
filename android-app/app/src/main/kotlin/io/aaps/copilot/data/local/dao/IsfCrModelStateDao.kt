package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aaps.copilot.data.local.entity.IsfCrModelStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IsfCrModelStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IsfCrModelStateEntity)

    @Query("SELECT * FROM isf_cr_model_state WHERE id = 'active' LIMIT 1")
    suspend fun active(): IsfCrModelStateEntity?

    @Query("SELECT * FROM isf_cr_model_state WHERE id = 'active' LIMIT 1")
    fun observeActive(): Flow<IsfCrModelStateEntity?>
}
