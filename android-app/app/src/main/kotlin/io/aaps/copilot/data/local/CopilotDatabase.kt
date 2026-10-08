package io.aaps.copilot.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import io.aaps.copilot.data.local.dao.AlertLocalDao
import io.aaps.copilot.data.local.entity.AlertLocalStateEntity
import io.aaps.copilot.data.local.entity.AlertLocalCycleEntity
import io.aaps.copilot.data.local.dao.MealStateDao
import io.aaps.copilot.data.local.dao.MealReceiptDao
import io.aaps.copilot.data.local.entity.MealReceiptEntity
import io.aaps.copilot.data.local.entity.MealStateEntity
import io.aaps.copilot.data.local.entity.MealStateScenarioEntity
import io.aaps.copilot.data.local.entity.MealStateAbsorptionEntity
import io.aaps.copilot.data.local.dao.MealNotificationClaimDao
import io.aaps.copilot.data.local.entity.MealNotificationClaimEntity
import io.aaps.copilot.data.local.entity.MealNotificationClaimAliasEntity
import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.dao.AlertAiAnalysisDao
import io.aaps.copilot.data.local.dao.AlertAiDatasetDao
import io.aaps.copilot.data.local.dao.AlertDeliveryReceiptDao
import io.aaps.copilot.data.local.dao.AlertEventDao
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.dao.BaselineDao
import io.aaps.copilot.data.local.dao.CircadianPatternDao
import io.aaps.copilot.data.local.dao.CircadianTargetDao
import io.aaps.copilot.data.local.dao.ClinicalReportDao
import io.aaps.copilot.data.local.dao.ContextEventSyncDao
import io.aaps.copilot.data.local.dao.EatingWindowSnapshotDao
import io.aaps.copilot.data.local.dao.EnergyProfileDao
import io.aaps.copilot.data.local.dao.ForecastDao
import io.aaps.copilot.data.local.dao.GlucoseDao
import io.aaps.copilot.data.local.dao.BloodGlucoseCheckDao
import io.aaps.copilot.data.local.dao.GlucoseCalibrationModelDao
import io.aaps.copilot.data.local.dao.IsfCrEvidenceDao
import io.aaps.copilot.data.local.dao.IsfCrModelStateDao
import io.aaps.copilot.data.local.dao.IsfCrSnapshotDao
import io.aaps.copilot.data.local.dao.MealEnergyOverrideDao
import io.aaps.copilot.data.local.dao.PatternDao
import io.aaps.copilot.data.local.dao.PhysioContextTagDao
import io.aaps.copilot.data.local.dao.ProfileEstimateDao
import io.aaps.copilot.data.local.dao.ProfileSegmentEstimateDao
import io.aaps.copilot.data.local.dao.RuleExecutionDao
import io.aaps.copilot.data.local.dao.SyncStateDao
import io.aaps.copilot.data.local.dao.SensitivityRuntimeSnapshotDao
import io.aaps.copilot.data.local.dao.TelemetryDao
import io.aaps.copilot.data.local.dao.TherapyDao
import io.aaps.copilot.data.local.dao.TargetManagerDao
import io.aaps.copilot.data.local.dao.UamInferenceEventDao
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.local.entity.AlertAiAnalysisEntity
import io.aaps.copilot.data.local.entity.AlertDeliveryReceiptEntity
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.data.local.entity.CircadianPatternSnapshotEntity
import io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianSlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianTargetAdjustmentEntity
import io.aaps.copilot.data.local.entity.CircadianTargetRunEntity
import io.aaps.copilot.data.local.entity.CircadianTransitionStatEntity
import io.aaps.copilot.data.local.entity.ClinicalReportEntity
import io.aaps.copilot.data.local.entity.ContextEventSyncEntity
import io.aaps.copilot.data.local.entity.EatingWindowSnapshotEntity
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.BloodGlucoseCheckEntity
import io.aaps.copilot.data.local.entity.GlucoseCalibrationModelEntity
import io.aaps.copilot.data.local.entity.IsfCrEvidenceEntity
import io.aaps.copilot.data.local.entity.IsfCrModelStateEntity
import io.aaps.copilot.data.local.entity.IsfCrSnapshotEntity
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.MealEnergyOverrideEntity
import io.aaps.copilot.data.local.entity.PendingMealProfileIntentEntity
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import io.aaps.copilot.data.local.entity.TargetManagerStateEntity
import io.aaps.copilot.data.local.entity.UamInferenceEventEntity

@Database(
    entities = [
        GlucoseSampleEntity::class,
        TherapyEventEntity::class,
        ForecastEntity::class,
        BloodGlucoseCheckEntity::class,
        GlucoseCalibrationModelEntity::class,
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
        UamInferenceEventEntity::class,
        TargetManagerStateEntity::class,
        TargetManagerDecisionEntity::class,
        CircadianTargetRunEntity::class,
        CircadianTargetAdjustmentEntity::class,
        ClinicalReportEntity::class,
        EatingWindowSnapshotEntity::class,
        PlannedActivityEventEntity::class,
        MealProfileOverrideEntity::class,
        MealEnergyOverrideEntity::class,
        PendingMealProfileIntentEntity::class,
        EnergyProfileSnapshotEntity::class,
        SensitivityRuntimeSnapshotEntity::class,
        AlertEventEntity::class,
        AlertDeliveryReceiptEntity::class,
        AlertAiAnalysisEntity::class,
        ContextEventSyncEntity::class,
        MealNotificationClaimEntity::class,
        MealNotificationClaimAliasEntity::class,
        MealStateEntity::class,
        MealStateScenarioEntity::class,
        MealStateAbsorptionEntity::class,
        MealReceiptEntity::class,
        AlertLocalStateEntity::class,
        AlertLocalCycleEntity::class
    ],
    version = 32,
    exportSchema = false
)
abstract class CopilotDatabase : RoomDatabase() {
    abstract fun alertLocalDao(): AlertLocalDao
    abstract fun mealStateDao(): MealStateDao
    abstract fun mealReceiptDao(): MealReceiptDao
    abstract fun mealNotificationClaimDao(): MealNotificationClaimDao
    abstract fun glucoseDao(): GlucoseDao
    abstract fun bloodGlucoseCheckDao(): BloodGlucoseCheckDao
    abstract fun glucoseCalibrationModelDao(): GlucoseCalibrationModelDao
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
    abstract fun targetManagerDao(): TargetManagerDao
    abstract fun circadianTargetDao(): CircadianTargetDao
    abstract fun clinicalReportDao(): ClinicalReportDao
    abstract fun eatingWindowSnapshotDao(): EatingWindowSnapshotDao
    abstract fun energyProfileDao(): EnergyProfileDao
    abstract fun mealEnergyOverrideDao(): MealEnergyOverrideDao
    abstract fun sensitivityRuntimeSnapshotDao(): SensitivityRuntimeSnapshotDao
    abstract fun alertEventDao(): AlertEventDao
    abstract fun alertDeliveryReceiptDao(): AlertDeliveryReceiptDao
    abstract fun alertAiAnalysisDao(): AlertAiAnalysisDao
    abstract fun alertAiDatasetDao(): AlertAiDatasetDao
    abstract fun contextEventSyncDao(): ContextEventSyncDao
}
