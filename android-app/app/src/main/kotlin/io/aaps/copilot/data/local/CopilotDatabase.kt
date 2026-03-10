package io.aaps.copilot.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.dao.BaselineDao
import io.aaps.copilot.data.local.dao.CircadianPatternDao
import io.aaps.copilot.data.local.dao.ForecastDao
import io.aaps.copilot.data.local.dao.GlucoseDao
import io.aaps.copilot.data.local.dao.IsfCrEvidenceDao
import io.aaps.copilot.data.local.dao.IsfCrModelStateDao
import io.aaps.copilot.data.local.dao.IsfCrSnapshotDao
import io.aaps.copilot.data.local.dao.PatternDao
import io.aaps.copilot.data.local.dao.PhysioContextTagDao
import io.aaps.copilot.data.local.dao.ProfileEstimateDao
import io.aaps.copilot.data.local.dao.ProfileSegmentEstimateDao
import io.aaps.copilot.data.local.dao.RuleExecutionDao
import io.aaps.copilot.data.local.dao.SyncStateDao
import io.aaps.copilot.data.local.dao.TelemetryDao
import io.aaps.copilot.data.local.dao.TherapyDao
import io.aaps.copilot.data.local.dao.UamInferenceEventDao
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.data.local.entity.CircadianPatternSnapshotEntity
import io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianSlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianTransitionStatEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.IsfCrEvidenceEntity
import io.aaps.copilot.data.local.entity.IsfCrModelStateEntity
import io.aaps.copilot.data.local.entity.IsfCrSnapshotEntity
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.local.entity.UamInferenceEventEntity

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
        CircadianSlotStatEntity::class,
        CircadianTransitionStatEntity::class,
        CircadianPatternSnapshotEntity::class,
        CircadianReplaySlotStatEntity::class,
        ProfileEstimateEntity::class,
        ProfileSegmentEstimateEntity::class,
        IsfCrSnapshotEntity::class,
        IsfCrEvidenceEntity::class,
        IsfCrModelStateEntity::class,
        PhysioContextTagEntity::class,
        TelemetrySampleEntity::class,
        UamInferenceEventEntity::class
    ],
    version = 12,
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
    abstract fun circadianPatternDao(): CircadianPatternDao
    abstract fun profileEstimateDao(): ProfileEstimateDao
    abstract fun profileSegmentEstimateDao(): ProfileSegmentEstimateDao
    abstract fun isfCrSnapshotDao(): IsfCrSnapshotDao
    abstract fun isfCrEvidenceDao(): IsfCrEvidenceDao
    abstract fun isfCrModelStateDao(): IsfCrModelStateDao
    abstract fun physioContextTagDao(): PhysioContextTagDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun uamInferenceEventDao(): UamInferenceEventDao
}
