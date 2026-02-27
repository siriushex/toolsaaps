package io.aaps.copilot.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.dao.BaselineDao
import io.aaps.copilot.data.local.dao.ForecastDao
import io.aaps.copilot.data.local.dao.GlucoseDao
import io.aaps.copilot.data.local.dao.PatternDao
import io.aaps.copilot.data.local.dao.ProfileEstimateDao
import io.aaps.copilot.data.local.dao.RuleExecutionDao
import io.aaps.copilot.data.local.dao.SyncStateDao
import io.aaps.copilot.data.local.dao.TherapyDao
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity

@Database(
    entities = [
        GlucoseSampleEntity::class,
        TherapyEventEntity::class,
        ForecastEntity::class,
        RuleExecutionEntity::class,
        ActionCommandEntity::class,
        SyncStateEntity::class,
        AuditLogEntity::class,
        BaselinePointEntity::class,
        PatternWindowEntity::class,
        ProfileEstimateEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class CopilotDatabase : RoomDatabase() {
    abstract fun glucoseDao(): GlucoseDao
    abstract fun therapyDao(): TherapyDao
    abstract fun forecastDao(): ForecastDao
    abstract fun ruleExecutionDao(): RuleExecutionDao
    abstract fun actionCommandDao(): ActionCommandDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun baselineDao(): BaselineDao
    abstract fun patternDao(): PatternDao
    abstract fun profileEstimateDao(): ProfileEstimateDao
}
