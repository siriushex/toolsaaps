package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.data.repository.AlertEpisodeDetail
import io.aaps.copilot.data.repository.AlertEpisodeSummary

import io.aaps.copilot.config.ClinicalAiModelCatalog
import io.aaps.copilot.config.ClinicalAiConfigIdentityPolicy
import io.aaps.copilot.config.ClinicalAiModelIdPolicy
import io.aaps.copilot.config.ClinicalAiModelPreset
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.profile.ActivityProfile
import io.aaps.copilot.domain.profile.ActivityProfileMode
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EvidenceTier
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.ui.IsfCrHistoryPointUi
import io.aaps.copilot.ui.IsfCrOverlayPointUi

enum class ScreenLoadState {
    LOADING,
    EMPTY,
    ERROR,
    READY
}

data class AlertsUiState(
    val loadState: ScreenLoadState = ScreenLoadState.LOADING,
    val mutedUntilTs: Long = 0L,
    val active: AlertEpisodeSummary? = null,
    val history: List<AlertEpisodeSummary> = emptyList(),
    val selected: AlertEpisodeDetail? = null
)

enum class ForecastRangeUi(val hours: Int) {
    H3(3),
    H6(6),
    H12(12),
    H24(24)
}

enum class AuditWindowUi(val label: String, val durationMs: Long) {
    H6("6h", 6 * 60 * 60_000L),
    H24("24h", 24 * 60 * 60_000L),
    D7("7d", 7 * 24 * 60 * 60_000L)
}

data class AppHealthUiState(
    val staleData: Boolean,
    val killSwitchEnabled: Boolean,
    val lastSyncText: String
)

data class BaseTargetBannerUiState(
    val baseTargetMmol: Double,
    val minTargetMmol: Double,
    val maxTargetMmol: Double
)

data class BaseTargetSchedulePresentation(
    val schedule: BaseTargetSchedule,
    val effectiveTargetMmol: Double,
    val autoDeltaMmol: Double,
    val autoState: CircadianAutoState,
    val autoReason: String? = null
)

data class ForecastLayerState(
    val showTrend: Boolean = true,
    val showTherapy: Boolean = true,
    val showUam: Boolean = true,
    val showCi: Boolean = true
)

data class ChartPointUi(
    val ts: Long,
    val value: Double
)

data class ChartCiPointUi(
    val ts: Long,
    val low: Double,
    val high: Double
)

enum class OverviewWarningKind {
    STRONG_GLUCOSE_ALERT,
    SENSOR_OR_STALE,
    KILL_SWITCH,
    POWER_SAVE,
    SOFT_GLUCOSE_ALERT
}

data class OverviewWarningUi(
    val kind: OverviewWarningKind,
    val detail: String? = null
)

enum class EnergyActivityStatusKind {
    PROFILE_ISSUE,
    APPROACHING,
    ACTIVE
}

/** Shown on Overview only for an actionable activity/profile exception. */
data class EnergyActivityStatusUi(
    val kind: EnergyActivityStatusKind,
    val title: String? = null,
    val minutesUntilStart: Long? = null
)

data class ClinicalForecastChartUiState(
    val historyPoints: List<ChartPointUi> = emptyList(),
    val futurePath: List<ChartPointUi> = emptyList(),
    val futureCi: List<ChartCiPointUi> = emptyList(),
    val displayRangeLowMmol: Double = 3.9,
    val displayRangeHighMmol: Double = 6.7,
    val events: List<CompensationEvent> = emptyList(),
    val eventTimelineNowTs: Long = 0L,
    val showEvents: Boolean = true
)

data class HorizonPredictionUi(
    val horizonMinutes: Int,
    val pred: Double?,
    val ciLow: Double?,
    val ciHigh: Double?,
    val warningWideCi: Boolean = false
)

data class TelemetryChipUi(
    val label: String,
    val value: String,
    val unit: String? = null
)

data class LastActionUi(
    val type: String,
    val status: String,
    val timestamp: Long,
    val tempTargetMmol: Double? = null,
    val durationMinutes: Int? = null,
    val carbsGrams: Double? = null,
    val idempotencyKey: String? = null,
    val payloadSummary: String? = null
)

data class SensorLagRolloutVerdictUi(
    val status: String,
    val bucket: String
)

enum class MetricRuntimeAvailabilityUi {
    AVAILABLE,
    APPLYING,
    UNAVAILABLE
}

data class MetricCandidateDiagnosticsUi(
    val source: String,
    val value: Double?,
    val diagnosticsAvailable: Boolean = false,
    val timestamp: Long? = null,
    val ageMinutesAtDecision: Long? = null,
    val freshAtDecision: Boolean? = null,
    val confidence: Double? = null,
    val sampleCount: Int? = null,
    val coverage: Double? = null,
    val qualityPassed: Boolean? = null,
    val unavailableReason: String? = null
)

data class MetricRuntimeSourceUi(
    val requested: String = "UNAVAILABLE",
    val resolved: String = "NO_OVERRIDE",
    val selectedValue: Double? = null,
    val aapsValue: Double? = null,
    val evidenceValue: Double? = null,
    val copilotValue: Double? = null,
    val confidence: Double? = null,
    val fallbackActive: Boolean = false,
    val availability: MetricRuntimeAvailabilityUi = MetricRuntimeAvailabilityUi.UNAVAILABLE,
    val actualResolvedSource: String? = null,
    val fallbackPath: List<String> = emptyList(),
    val fallbackReason: String? = null,
    val acceptedSettingsRevision: Long? = null,
    val acceptedCycleId: String? = null,
    val acceptedTimestamp: Long? = null,
    val acceptedSnapshotTimestamp: Long? = null,
    val acceptedAgeMinutes: Long? = null,
    val acceptedFresh: Boolean? = null,
    val candidates: List<MetricCandidateDiagnosticsUi> = emptyList()
)

data class IobRuntimeDetailsUi(
    val effectivePositiveIobUnits: Double? = null,
    val signedNetIobUnits: Double? = null,
    val bolusIobUnits: Double? = null,
    val basalIobUnits: Double? = null,
    val insulinActivity: Double? = null,
    val actualSource: String? = null,
    val sampleTimestamp: Long? = null,
    val sampleAgeMinutes: Long? = null,
    val confidence: Double? = null,
    val evidenceTimestamp: Long? = null,
    val therapyCoverage: Double? = null,
    val fallbackReason: String? = null
)

enum class UamRuntimeStatusUi {
    OBSERVATION_ACTIVE,
    ACTIVE,
    DECAYING,
    INACTIVE,
    SENSOR_BLOCKED,
    DATA_STALE,
    CONFIDENCE_LOW,
    SIGNAL_UNSTABLE,
    FORECAST_UNAVAILABLE,
    LOW_GLUCOSE_RISK,
    COB_ACTIVE,
    MANUAL_CARBS,
    THERAPY_COVERAGE_LOW,
    RECONCILIATION_REQUIRED,
    OUTCOME_UNKNOWN,
    RESERVATION_PENDING,
    RATE_LIMITED,
    INTERVAL_WAIT,
    CAPACITY_REACHED
}

data class UamExportControlUi(
    val available: Boolean = true,
    val mode: String = "OFF",
    val runtimeStatus: UamRuntimeStatusUi = UamRuntimeStatusUi.INACTIVE,
    val runtimeReasonStatus: UamRuntimeStatusUi? = null,
    val estimatedCarbsGrams: Double? = null,
    val confidence: Double? = null,
    val active: Boolean = false,
    val exportBlockedStatus: UamRuntimeStatusUi? = null
)

enum class TargetManagerLiveStatusAvailabilityUi {
    PAUSED,
    CURRENT,
    WAITING,
    STALE,
    UNAVAILABLE
}

data class TargetManagerLiveStatusUi(
    val availability: TargetManagerLiveStatusAvailabilityUi =
        TargetManagerLiveStatusAvailabilityUi.UNAVAILABLE,
    val timestamp: Long? = null,
    val mode: String? = null,
    val priorityEnabled: Boolean? = null,
    val policyRevision: Long? = null,
    val currentTargetMmol: Double? = null,
    val proposedTargetMmol: Double? = null,
    val outcome: String? = null,
    val reason: String? = null
)

data class OverviewUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val isProMode: Boolean = false,
    val glucose: Double? = null,
    val rawGlucose: Double? = null,
    val calibratedGlucose: Double? = null,
    val correctedGlucose: Double? = null,
    val delta: Double? = null,
    val sampleAgeMinutes: Long? = null,
    val calibrationGain: Double? = null,
    val calibrationOffsetMmol: Double? = null,
    val calibrationConfidence: Double? = null,
    val calibrationModelType: String? = null,
    val calibrationStatus: String? = null,
    val calibrationLastCheckAgeMinutes: Double? = null,
    val glucoseAlertState: String? = null,
    val glucoseAlertDirection: String? = null,
    val glucoseAlertDisableReason: String? = null,
    val glucoseAlertSoftActive: Boolean = false,
    val glucoseAlertStrongActive: Boolean = false,
    val glucoseAlertMutedUntilTs: Long = 0L,
    val sensorLagMode: String? = null,
    val sensorLagMinutes: Double? = null,
    val sensorLagDisableReason: String? = null,
    val sensorLagRolloutVerdict: SensorLagRolloutVerdictUi? = null,
    val chart: ClinicalForecastChartUiState = ClinicalForecastChartUiState(),
    val events: List<CompensationEvent> = emptyList(),
    val eventTimelineNowTs: Long = 0L,
    val physiologicalSex: PhysiologicalSex = PhysiologicalSex.UNSPECIFIED,
    val baseTargetMmol: Double? = null,
    val baseTargetSchedule: BaseTargetSchedule = BaseTargetSchedule.legacy(baseTargetMmol ?: 5.5),
    val effectiveBaseTargetMmol: Double = baseTargetMmol ?: 5.5,
    val baseTargetAutoDeltaMmol: Double = 0.0,
    val baseTargetAutoState: CircadianAutoState = CircadianAutoState.OFF,
    val baseTargetAutoReason: String? = null,
    val targetManagerLiveStatus: TargetManagerLiveStatusUi = TargetManagerLiveStatusUi(),
    val targetEditMinMmol: Double = 4.0,
    val targetEditMaxMmol: Double = 10.0,
    val currentIobUnits: Double? = null,
    val iobDetails: IobRuntimeDetailsUi = IobRuntimeDetailsUi(),
    val currentCobGrams: Double? = null,
    val currentIsfMmolPerUnit: Double? = null,
    val currentCrGramsPerUnit: Double? = null,
    val sensitivitySourceApplying: Boolean = false,
    val sensitivitySourcePendingMetric: String? = null,
    val sensitivitySourcePendingValue: String? = null,
    val sensitivitySourceApplyError: String? = null,
    val carbComputationMaxGrams: Double = 60.0,
    val isfRuntime: MetricRuntimeSourceUi = MetricRuntimeSourceUi(),
    val crRuntime: MetricRuntimeSourceUi = MetricRuntimeSourceUi(),
    val calculatedUamCarbsGrams: Double? = null,
    val calculatedUamConfidence: Double? = null,
    val uamExport: UamExportControlUi = UamExportControlUi(),
    val warning: OverviewWarningUi? = null,
    val energyActivityStatus: EnergyActivityStatusUi? = null,
    val pumpLinkStatus: io.aaps.copilot.data.repository.PumpLinkUiStatus = io.aaps.copilot.data.repository.PumpLinkUiStatus(),
    val horizons: List<HorizonPredictionUi> = emptyList(),
    val uamActive: Boolean? = null,
    val uci0Mmol5m: Double? = null,
    val inferredCarbsLast60g: Double? = null,
    val uamModeLabel: String? = null,
    val telemetryChips: List<TelemetryChipUi> = emptyList(),
    val lastAction: LastActionUi? = null,
    val canRunCycleNow: Boolean = true,
    val powerSaveActive: Boolean = false,
    val powerSaveUntilMs: Long = 0L,
    val powerSaveIndefinite: Boolean = false,
    val powerSaveRemainingText: String = "",
    val killSwitchEnabled: Boolean = false
)

data class ForecastDecompositionUi(
    val trend60: Double? = null,
    val therapy60: Double? = null,
    val uam60: Double? = null,
    val residualRoc0: Double? = null,
    val sigmaE: Double? = null,
    val kfSigmaG: Double? = null
)

data class ForecastUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val isProMode: Boolean = false,
    val range: ForecastRangeUi = ForecastRangeUi.H3,
    val layers: ForecastLayerState = ForecastLayerState(),
    val horizons: List<HorizonPredictionUi> = emptyList(),
    val historyPoints: List<ChartPointUi> = emptyList(),
    val futurePath: List<ChartPointUi> = emptyList(),
    val futureCi: List<ChartCiPointUi> = emptyList(),
    val decomposition: ForecastDecompositionUi = ForecastDecompositionUi(),
    val qualityLines: List<String> = emptyList()
)

data class UamEventUi(
    val id: String,
    val state: String,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ingestionTs: Long,
    val carbsDisplayG: Double,
    val confidence: Double,
    val exportSeq: Int,
    val exportedGrams: Double,
    val tag: String,
    val manualCarbsNearby: Boolean,
    val manualCobActive: Boolean,
    val exportBlockedReason: String?
)

data class UamUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val inferredActive: Boolean? = null,
    val inferredCarbsGrams: Double? = null,
    val inferredConfidence: Double? = null,
    val calculatedActive: Boolean? = null,
    val calculatedCarbsGrams: Double? = null,
    val calculatedConfidence: Double? = null,
    val events: List<UamEventUi> = emptyList(),
    val uamExport: UamExportControlUi = UamExportControlUi()
)

data class SafetyChecklistItemUi(
    val title: String,
    val ok: Boolean,
    val details: String
)

data class AiTuningStatusUi(
    val state: String,
    val reason: String,
    val generatedTs: Long? = null,
    val confidence: Double? = null,
    val statusRaw: String? = null
)

data class SafetyUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val killSwitchEnabled: Boolean,
    val staleMinutesLimit: Int,
    val hardBounds: String,
    val hardMinTargetMmol: Double,
    val hardMaxTargetMmol: Double,
    val adaptiveBounds: String,
    val baseTarget: Double,
    val maxActionsIn6h: Int,
    val glucoseAlertState: String? = null,
    val glucoseAlertDirection: String? = null,
    val glucoseAlertDisableReason: String? = null,
    val glucoseAlertLowThreshold: Double? = null,
    val glucoseAlertHighThreshold: Double? = null,
    val glucoseAlertUrgentLowThreshold: Double? = null,
    val glucoseAlertSoftLastTs: Long? = null,
    val glucoseAlertStrongLastTs: Long? = null,
    val glucoseAlertSoftClipLabel: String = "--",
    val glucoseAlertCriticalClip1Label: String = "--",
    val glucoseAlertCriticalClip2Label: String = "--",
    val glucoseAlertSoftClipValid: Boolean = true,
    val glucoseAlertCriticalClip1Valid: Boolean = true,
    val glucoseAlertCriticalClip2Valid: Boolean = true,
    val therapyHistorySourceMode: String? = null,
    val therapyHistoryRawInsulin30d: Int? = null,
    val therapyHistoryInferredInsulin30d: Int? = null,
    val therapyHistoryRealFetchedInsulin30d: Int? = null,
    val therapyHistoryRecoveredInsulin30d: Int? = null,
    val therapyHistoryUsableInsulin30d: Int? = null,
    val therapyHistoryBootstrapNeeded: Boolean? = null,
    val therapyHistoryPlateauOnly: Boolean? = null,
    val therapyHistorySyntheticRatioPct: Double? = null,
    val therapyHistoryLastSyncTreatmentCount: Int? = null,
    val therapyHistoryLastSyncInsulinLikeCount: Int? = null,
    val therapyHistoryLastSyncCarbLikeCount: Int? = null,
    val therapyHistoryLastSyncLocalActionCount: Int? = null,
    val therapyHistoryUpstreamTempTargetOnly: Boolean? = null,
    val cooldownStatusLines: List<String> = emptyList(),
    val localNightscoutEnabled: Boolean,
    val localNightscoutPort: Int,
    val localNightscoutRuntimeStatus: String = "SETUP",
    val localNightscoutRuntimeReason: String? = null,
    val localNightscoutCaFingerprint: String? = null,
    val localNightscoutTlsOk: Boolean? = null,
    val localNightscoutTlsStatusText: String = "--",
    val aiTuningStatus: AiTuningStatusUi? = null,
    val checklist: List<SafetyChecklistItemUi> = emptyList()
)

data class AuditItemUi(
    val id: String,
    val ts: Long,
    val source: String,
    val level: String,
    val summary: String,
    val context: String,
    val idempotencyKey: String? = null,
    val payloadSummary: String? = null
)

data class AuditUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val window: AuditWindowUi = AuditWindowUi.H24,
    val onlyErrors: Boolean = false,
    val rows: List<AuditItemUi> = emptyList()
)

data class AnalyticsUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val calibrationRawCurrentMmol: Double? = null,
    val calibrationCalibratedCurrentMmol: Double? = null,
    val calibrationGain: Double? = null,
    val calibrationOffsetMmol: Double? = null,
    val calibrationConfidence: Double? = null,
    val calibrationModelType: String? = null,
    val calibrationStatus: String? = null,
    val calibrationLastCheckAgeMinutes: Double? = null,
    val calibrationRawHistoryPoints: List<ChartPointUi> = emptyList(),
    val calibrationResolvedHistoryPoints: List<ChartPointUi> = emptyList(),
    val calibrationCheckPoints: List<ChartPointUi> = emptyList(),
    val sensorLagDiagnostics: SensorLagDiagnosticsUi? = null,
    val circadianStateStatus: CircadianStateStatusUi? = null,
    val qualityLines: List<String> = emptyList(),
    val baselineDeltaLines: List<String> = emptyList(),
    val dailyReportGeneratedAtTs: Long? = null,
    val dailyReportMatchedSamples: Int? = null,
    val dailyReportForecastRows: Int? = null,
    val dailyReportPeriodStartUtc: String? = null,
    val dailyReportPeriodEndUtc: String? = null,
    val dailyReportMarkdownPath: String? = null,
    val dailyReportHorizonStats: List<DailyReportHorizonUi> = emptyList(),
    val dailyReportRecommendations: List<String> = emptyList(),
    val dailyReportIsfCrQualityLines: List<String> = emptyList(),
    val dailyReportReplayHotspots: List<DailyReportReplayHotspotUi> = emptyList(),
    val dailyReportReplayFactorContributions: List<DailyReportReplayFactorUi> = emptyList(),
    val dailyReportReplayFactorCoverage: List<DailyReportReplayCoverageUi> = emptyList(),
    val dailyReportReplayFactorRegimes: List<DailyReportReplayRegimeUi> = emptyList(),
    val dailyReportReplayFactorPairs: List<DailyReportReplayPairUi> = emptyList(),
    val dailyReportReplayTopMisses: List<DailyReportReplayTopMissUi> = emptyList(),
    val dailyReportReplayErrorClusters: List<DailyReportReplayErrorClusterUi> = emptyList(),
    val dailyReportReplayDayTypeGaps: List<DailyReportReplayDayTypeGapUi> = emptyList(),
    val dailyReportReplayTopFactorsOverall: String? = null,
    val dailyReportSensorLagReplayBuckets: List<DailyReportSensorLagReplayUi> = emptyList(),
    val dailyReportSensorLagShadowBuckets: List<DailyReportSensorLagShadowUi> = emptyList(),
    val circadianReplaySummary: CircadianReplaySummaryUi? = null,
    val probableMealWindows: List<ProbableMealWindowUi> = emptyList(),
    val recentProbableMealWindows: List<ProbableMealWindowUi> = emptyList(),
    val rollingReportLines: List<String> = emptyList(),
    val currentIsfReal: Double? = null,
    val currentCrReal: Double? = null,
    val currentIsfMerged: Double? = null,
    val currentCrMerged: Double? = null,
    val currentIsfAapsRaw: Double? = null,
    val currentCrAapsRaw: Double? = null,
    val realtimeMode: String? = null,
    val realtimeConfidence: Double? = null,
    val realtimeQualityScore: Double? = null,
    val realtimeIsfEff: Double? = null,
    val realtimeCrEff: Double? = null,
    val realtimeIsfBase: Double? = null,
    val realtimeCrBase: Double? = null,
    val realtimeCiIsfLow: Double? = null,
    val realtimeCiIsfHigh: Double? = null,
    val realtimeCiCrLow: Double? = null,
    val realtimeCiCrHigh: Double? = null,
    val realtimeFactorLines: List<String> = emptyList(),
    val runtimeDiagnostics: IsfCrRuntimeDiagnosticsUi? = null,
    val activationGateLines: List<String> = emptyList(),
    val droppedReasons24hLines: List<String> = emptyList(),
    val droppedReasons7dLines: List<String> = emptyList(),
    val wearImpact24hLines: List<String> = emptyList(),
    val wearImpact7dLines: List<String> = emptyList(),
    val activeTagLines: List<String> = emptyList(),
    val historyPoints: List<IsfCrHistoryPointUi> = emptyList(),
    val historyOverlayPoints: List<IsfCrOverlayPointUi> = emptyList(),
    val historyLastUpdatedTs: Long? = null,
    val circadianSections: List<CircadianPatternSectionUi> = emptyList(),
    val deepLines: List<String> = emptyList(),
    val selectedInsulinProfileId: String = "NOVORAPID",
    val insulinProfileCurves: List<InsulinProfileCurveUi> = emptyList(),
    val insulinRealProfileCurvePoints: List<InsulinProfilePointUi> = emptyList(),
    val insulinRealProfileAvailable: Boolean = false,
    val insulinRealProfileUpdatedTs: Long? = null,
    val insulinRealProfileConfidence: Double? = null,
    val insulinRealProfileSamples: Int? = null,
    val insulinRealProfileOnsetMinutes: Double? = null,
    val insulinRealProfilePeakMinutes: Double? = null,
    val insulinRealProfileScale: Double? = null,
    val insulinRealProfileStatus: String? = null
)

data class CircadianStateStatusUi(
    val state: String,
    val reason: String,
    val slotCount: Int,
    val transitionCount: Int,
    val snapshotCount: Int,
    val replayCount: Int,
    val sectionCount: Int,
    val latestSnapshotTs: Long? = null,
    val latestReplayTs: Long? = null,
    val sourceSummary: String? = null
)

data class SensorLagDiagnosticsUi(
    val configuredMode: String = "OFF",
    val runtimeMode: String? = null,
    val rawGlucoseMmol: Double? = null,
    val correctedGlucoseMmol: Double? = null,
    val correctionMmol: Double? = null,
    val lagMinutes: Double? = null,
    val ageHours: Double? = null,
    val ageSource: String? = null,
    val confidence: Double? = null,
    val wearBucket: String? = null,
    val sourceConfidence: Double? = null,
    val trendConsistency: Double? = null,
    val replayMultiplier: Double? = null,
    val effectiveLagMinutes: Double? = null,
    val effectiveCorrectionCap: Double? = null,
    val disableReason: String? = null,
    val sensorQualityScore: Double? = null,
    val sensorQualityBlocked: Boolean? = null,
    val sensorQualitySuspectFalseLow: Boolean? = null,
    val sensorQualityReason: String? = null,
    val lagTrendPoints: List<ChartPointUi> = emptyList(),
    val correctionTrendPoints: List<ChartPointUi> = emptyList(),
    val modeSegments: List<SensorLagTimelineSegmentUi> = emptyList(),
    val bucketSegments: List<SensorLagTimelineSegmentUi> = emptyList(),
    val trendStartAgeHours: Double? = null,
    val trendEndAgeHours: Double? = null
)

data class SensorLagTimelineSegmentUi(
    val startTs: Long,
    val endTs: Long,
    val label: String
)

enum class CircadianPatternWindowUi(
    val days: Int,
    val label: String
) {
    DAYS_5(5, "5d"),
    DAYS_7(7, "7d"),
    DAYS_10(10, "10d"),
    DAYS_14(14, "14d")
}

data class CircadianCurvePointUi(
    val slotIndex: Int,
    val medianBg: Double,
    val p10: Double,
    val p25: Double,
    val p75: Double,
    val p90: Double,
    val lowRate: Double,
    val highRate: Double,
    val recommendedTargetMmol: Double? = null
)

data class CircadianDeltaPointUi(
    val slotIndex: Int,
    val delta30: Double?,
    val delta60: Double?,
    val confidence30: Double? = null,
    val confidence60: Double? = null
)

data class CircadianRiskWindowUi(
    val hour: Int,
    val lowRate: Double,
    val highRate: Double,
    val recommendedTargetMmol: Double
)

data class CircadianPatternWindowSeriesUi(
    val windowDays: Int,
    val coverageDays: Int,
    val sampleCount: Int,
    val confidence: Double,
    val qualityScore: Double,
    val points: List<CircadianCurvePointUi> = emptyList(),
    val deltaPoints: List<CircadianDeltaPointUi> = emptyList(),
    val topRiskWindows: List<CircadianRiskWindowUi> = emptyList(),
    val replayDiagnostics: List<CircadianReplayDiagnosticUi> = emptyList()
)

data class CircadianReplayDiagnosticUi(
    val horizonMinutes: Int,
    val bucketStatus: String,
    val winRate: Double,
    val maeBaseline: Double,
    val maeCircadian: Double,
    val sampleCount: Int,
    val fallbackToAll: Boolean
)

data class CircadianPatternSectionUi(
    val requestedDayType: String,
    val segmentSource: String,
    val stableWindowDays: Int,
    val recencyWindowDays: Int,
    val recencyWeight: Double,
    val coverageDays: Int,
    val sampleCount: Int,
    val segmentFallback: Boolean,
    val fallbackReason: String? = null,
    val confidence: Double,
    val qualityScore: Double,
    val windows: List<CircadianPatternWindowSeriesUi> = emptyList()
)

data class AiCloudJobUi(
    val jobId: String,
    val lastStatus: String?,
    val lastRunTs: Long?,
    val nextRunTs: Long?,
    val lastMessage: String?
)

data class AiAnalysisHistoryItemUi(
    val runTs: Long,
    val date: String,
    val source: String,
    val status: String,
    val summary: String,
    val anomalies: List<String>,
    val recommendations: List<String>,
    val errorMessage: String?
)

data class AiAnalysisTrendItemUi(
    val weekStart: String,
    val totalRuns: Int,
    val successRuns: Int,
    val failedRuns: Int,
    val anomaliesCount: Int,
    val recommendationsCount: Int
)

data class AiReplayForecastStatUi(
    val horizonMinutes: Int,
    val sampleCount: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double
)

data class AiReplayRuleStatUi(
    val ruleId: String,
    val triggered: Int,
    val blocked: Int,
    val noMatch: Int
)

data class AiReplayDayTypeStatUi(
    val dayType: String,
    val metrics: List<AiReplayForecastStatUi>
)

data class AiReplayHourStatUi(
    val hour: Int,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double
)

data class AiReplayDriftStatUi(
    val horizonMinutes: Int,
    val previousMae: Double,
    val recentMae: Double,
    val deltaMae: Double
)

data class AiHorizonScoreUi(
    val horizonMinutes: Int,
    val sampleCount: Int?,
    val mae: Double?,
    val mardPct: Double?,
    val scoreBand: String
)

data class AiTopFactorUi(
    val horizonMinutes: Int,
    val factor: String,
    val contributionScore: Double,
    val upliftPct: Double,
    val sampleCount: Int
)

data class AiHotspotUi(
    val horizonMinutes: Int,
    val hour: Int,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class AiTopMissUi(
    val horizonMinutes: Int,
    val ts: Long,
    val absError: Double,
    val pred: Double,
    val actual: Double,
    val cob: Double,
    val iob: Double,
    val uam: Double,
    val ciWidth: Double,
    val activity: Double
)

data class AiDayTypeGapUi(
    val horizonMinutes: Int,
    val hour: Int,
    val worseDayType: String,
    val maeGapMmol: Double,
    val mardGapPct: Double,
    val dominantFactor: String?,
    val sampleCount: Int
)

data class AiReplayUi(
    val days: Int,
    val points: Int,
    val stepMinutes: Int,
    val forecastStats: List<AiReplayForecastStatUi> = emptyList(),
    val ruleStats: List<AiReplayRuleStatUi> = emptyList(),
    val dayTypeStats: List<AiReplayDayTypeStatUi> = emptyList(),
    val hourlyTop: List<AiReplayHourStatUi> = emptyList(),
    val driftStats: List<AiReplayDriftStatUi> = emptyList()
)

data class AiChatMessageUi(
    val id: String,
    val role: String,
    val text: String,
    val ts: Long,
    val attachments: List<AiChatAttachmentUi> = emptyList(),
    val voiceTranscript: Boolean = false
)

data class AiChatAttachmentUi(
    val id: String,
    val name: String,
    val kind: String,
    val mimeType: String? = null,
    val sizeLabel: String? = null,
    val previewLabel: String? = null
)

enum class ClinicalReportPhaseUi {
    IDLE,
    BUILDING,
    LOCAL_READY,
    UPLOADING,
    COMPLETE,
    FAILED,
    CANCELLED,
    UNKNOWN_OUTCOME
}

enum class ClinicalReportFailureUi {
    LOCAL_BUILD,
    CREDENTIAL_UNAVAILABLE,
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER,
    HTTP,
    TIMEOUT,
    NETWORK,
    REFUSAL,
    INCOMPLETE,
    INVALID_RESPONSE,
    OVERSIZED_RESPONSE,
    REQUEST_TOO_LARGE,
    DATASET_TOO_LARGE,
    INVALID_INPUT,
    PARTIAL_CHUNK,
    UNKNOWN_REMOTE_OUTCOME,
    PROCESS_INTERRUPTED_PRE_REQUEST,
    CANCELLED,
    OTHER
}

enum class ClinicalReportProgressStageUi {
    PREPARING,
    ANALYZING,
    REDUCING,
    SYNTHESIZING,
    VALIDATING,
    COMPLETED
}

enum class ClinicalLocalDataQualityUi {
    GOOD,
    LIMITED,
    INSUFFICIENT
}

enum class ClinicalReportTextKey {
    SUMMARY_STABLE,
    SUMMARY_HIGH_VARIABILITY,
    SUMMARY_LOW_EXPOSURE,
    SUMMARY_HIGH_EXPOSURE,
    SUMMARY_MIXED,
    SUMMARY_INSUFFICIENT_DATA,
    QUALITY_COMPLETE,
    QUALITY_PARTIAL_COVERAGE,
    QUALITY_MISSING_INTERVALS,
    QUALITY_SENSOR_GAPS,
    QUALITY_THERAPY_GAPS,
    QUALITY_TARGET_GAPS,
    QUALITY_FORECAST_GAPS,
    QUALITY_TELEMETRY_GAPS,
    QUALITY_INSUFFICIENT_DATA,
    OBSERVATION_GLUCOSE_STABILITY,
    OBSERVATION_GLUCOSE_VARIABILITY,
    OBSERVATION_LOW_EXPOSURE,
    OBSERVATION_HIGH_EXPOSURE,
    OBSERVATION_MEAL_ASSOCIATION,
    OBSERVATION_OVERNIGHT_PATTERN,
    OBSERVATION_TARGET_ALIGNMENT,
    OBSERVATION_SENSOR_RELIABILITY,
    OBSERVATION_INFUSION_SET_SIGNAL,
    OBSERVATION_DATA_COVERAGE,
    DIRECTION_STABLE,
    DIRECTION_INCREASING,
    DIRECTION_DECREASING,
    DIRECTION_INTERMITTENT,
    DIRECTION_MIXED,
    DIRECTION_NOT_APPLICABLE,
    TIME_ALL_DAY,
    TIME_OVERNIGHT,
    TIME_MORNING,
    TIME_AFTERNOON,
    TIME_EVENING,
    CONFIDENCE_LOW,
    CONFIDENCE_MEDIUM,
    CONFIDENCE_HIGH,
    EVIDENCE_MEAN_GLUCOSE,
    EVIDENCE_MEDIAN_GLUCOSE,
    EVIDENCE_MEAN_TARGET,
    EVIDENCE_VARIABILITY,
    EVIDENCE_BELOW_RANGE,
    EVIDENCE_IN_RANGE,
    EVIDENCE_ABOVE_RANGE,
    EVIDENCE_COVERAGE,
    EVIDENCE_MAX_GAP,
    EVIDENCE_DURATION,
    EVIDENCE_RECORDED_INSULIN,
    EVIDENCE_RECORDED_CARBS,
    EVIDENCE_SAMPLE_COUNT,
    DISCUSSION_SENSOR_RELIABILITY,
    DISCUSSION_INFUSION_SET_REVIEW,
    DISCUSSION_MEAL_TIMING_REVIEW,
    DISCUSSION_ISF_CR_REVIEW,
    DISCUSSION_TARGET_PATTERN_REVIEW,
    DISCUSSION_DATA_QUALITY_REVIEW,
    DISCUSSION_LOW_RISK_REVIEW,
    DISCUSSION_OTHER_CLINICAL_REVIEW,
    PRIORITY_LOW,
    PRIORITY_MEDIUM,
    PRIORITY_HIGH,
    PERIOD_LAST_24_HOURS,
    PERIOD_LAST_7_DAYS,
    PERIOD_LAST_30_DAYS,
    PERIOD_COMPARATIVE_7D_30D,
    SAFETY_RECURRENT_LOW_PATTERN,
    SAFETY_PROLONGED_LOW_PATTERN,
    SAFETY_HIGH_EXPOSURE_PATTERN,
    SAFETY_HIGH_VARIABILITY_PATTERN,
    SAFETY_SENSOR_RELIABILITY_CONCERN,
    SAFETY_INFUSION_SET_REVIEW_SIGNAL,
    SAFETY_INSUFFICIENT_DATA,
    SAFETY_NONE_IDENTIFIED,
    QUESTION_SENSOR_RELIABILITY_CONTEXT,
    QUESTION_INFUSION_SET_CONTEXT,
    QUESTION_MEAL_TIMING_CONTEXT,
    QUESTION_ISF_CR_CONTEXT,
    QUESTION_TARGET_PATTERN_CONTEXT,
    QUESTION_LOW_PATTERN_CONTEXT,
    QUESTION_HIGH_PATTERN_CONTEXT,
    QUESTION_DATA_COMPLETENESS_CONTEXT
}

data class ClinicalPeriodSummaryUi(
    val days: Int,
    val coveragePct: Double?,
    val coverageProgress: Float,
    val meanGlucose: Double?,
    val medianGlucose: Double?,
    val variability: Double?,
    val belowRange: Double?,
    val inRange: Double?,
    val aboveRange: Double?,
    val recordedInsulin: Double?,
    val realCarbs: Double?,
    val maxGapMinutes: Int?,
    val quality: ClinicalLocalDataQualityUi,
    val deliveredBasalInsulin: Double? = null,
    val deliveredBolusInsulin: Double? = null,
    val iobDerivedInsulin: Double? = null,
    val enteredCarbs: Double? = null,
    val uamCarbs: Double? = null,
    val aapsCarbs: Double? = null,
    val steps: Double? = null,
    val activeMinutes: Double? = null,
    val carbohydrateEnergyKcal: Double? = null,
    val activeCaloriesKcal: Double? = null,
    val activityCoveragePct: Double? = null,
    val therapyTotalsAuthoritative: Boolean = false,
    val probableMealWindows: List<ProbableMealWindowUi> = emptyList(),
    val recentProbableMealWindows: List<ProbableMealWindowUi> = emptyList()
)

data class ProbableMealWindowUi(
    val medianMinuteOfDay: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val iqrMinutes: Int,
    val supportDays: Int,
    val lookbackDays: Int,
    val episodeCount: Int,
    val enteredEpisodeCount: Int,
    val uamEpisodeCount: Int,
    val confidencePct: Double
)

enum class ClinicalNumericUnitUi {
    MMOL_L,
    PERCENT,
    INSULIN_UNITS,
    GRAMS,
    MINUTES,
    KILOCALORIES,
    COUNT
}

data class ClinicalEvidenceUi(
    val metric: ClinicalReportTextKey,
    val value: Double,
    val decimals: Int,
    val unit: ClinicalNumericUnitUi
)

data class ClinicalFindingUi(
    val topic: ClinicalReportTextKey,
    val period: ClinicalReportTextKey,
    val direction: ClinicalReportTextKey,
    val confidence: ClinicalReportTextKey,
    val timeBand: ClinicalReportTextKey,
    val evidence: ClinicalEvidenceUi
)

data class ClinicalRecommendationUi(
    val topic: ClinicalReportTextKey,
    val priority: ClinicalReportTextKey,
    val period: ClinicalReportTextKey,
    val linkedEvidence: List<ClinicalFindingUi>
)

data class ClinicalReportMetadataUi(
    val generatedAtTs: Long?,
    val providerId: ClinicalAiProviderId,
    val model: String?,
    val schemaName: String?,
    val schemaVersion: Int?
)

data class ClinicalReportDisclosureUi(
    val providerId: ClinicalAiProviderId,
    val model: String,
    val endpointHost: String? = null,
    val configIdentity: String
) {
    init {
        ClinicalAiModelIdPolicy.requireValid(model)
        ClinicalAiConfigIdentityPolicy.requireValid(configIdentity)
        if (providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE) {
            require(!endpointHost.isNullOrBlank()) {
                "Compatible provider disclosure requires a hostname"
            }
            require(
                "://" !in endpointHost &&
                    endpointHost.none { it == '/' || it == '?' || it == '#' || it == '@' } &&
                    endpointHost.none { it.isWhitespace() }
            ) {
                "Disclosure endpoint must contain only a hostname"
            }
        } else {
            require(endpointHost == null) {
                "Native provider disclosure cannot contain an endpoint"
            }
        }
    }
}

data class ClinicalCompleteReportUi(
    val summary7dStatus: ClinicalReportTextKey,
    val summary30dStatus: ClinicalReportTextKey,
    val dataQuality: List<ClinicalReportTextKey>,
    val patterns: List<ClinicalFindingUi>,
    val safetyObservations: List<ClinicalReportTextKey>,
    val recommendations: List<ClinicalRecommendationUi>,
    val careTeamQuestions: List<ClinicalReportTextKey>,
    val metadata: ClinicalReportMetadataUi
)

enum class ClinicalReportRetryFeedbackUi {
    PENDING,
    IN_FLIGHT,
    FAILED
}

enum class ClinicalPdfExportUiState {
    IDLE,
    PREPARING,
    READY,
    WRITING,
    COMPLETE,
    FAILED,
    TOO_LARGE,
    CANCELLED
}

data class ClinicalPdfExportTicketUi(
    val id: Long,
    val suggestedFilename: String
) {
    init {
        require(id > 0L)
        require(suggestedFilename.isNotBlank())
        require(suggestedFilename.endsWith(".pdf", ignoreCase = true))
        require('/' !in suggestedFilename && '\\' !in suggestedFilename)
    }
}

data class ClinicalReportUiState(
    val phase: ClinicalReportPhaseUi = ClinicalReportPhaseUi.IDLE,
    val summary24h: ClinicalPeriodSummaryUi? = null,
    val summary7d: ClinicalPeriodSummaryUi? = null,
    val summary30d: ClinicalPeriodSummaryUi? = null,
    val failure: ClinicalReportFailureUi? = null,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val progress: Float = 0f,
    val progressStage: ClinicalReportProgressStageUi =
        ClinicalReportProgressStageUi.PREPARING,
    val progressLevel: Int = 0,
    val complete: ClinicalCompleteReportUi? = null,
    val canSend: Boolean = false,
    val canCancel: Boolean = false,
    val canRequestGuardedRetry: Boolean = false,
    val canRetryLocalPreparation: Boolean = false,
    val showRetryConfirmation: Boolean = false,
    val retryFeedback: ClinicalReportRetryFeedbackUi? = null,
    val pointsToSettings: Boolean = false,
    val disclosure: ClinicalReportDisclosureUi? = null,
    val remoteEventPreviewText: String? = null,
    val canSavePdf: Boolean = false,
    val canSharePdf: Boolean = false,
    val pdfRequiresReprepare: Boolean = false,
    val pdfExportState: ClinicalPdfExportUiState = ClinicalPdfExportUiState.IDLE,
    val pdfExportTicket: ClinicalPdfExportTicketUi? = null
)

data class AiAnalysisUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val minDataHours: Int = 24,
    val dataCoverageHours: Double = 0.0,
    val analysisReady: Boolean = false,
    val cloudConfigured: Boolean = false,
    val windowDays: Int = 7,
    val filterLabel: String = "",
    val jobs: List<AiCloudJobUi> = emptyList(),
    val historyItems: List<AiAnalysisHistoryItemUi> = emptyList(),
    val trendItems: List<AiAnalysisTrendItemUi> = emptyList(),
    val replay: AiReplayUi? = null,
    val localDailyGeneratedAtTs: Long? = null,
    val localDailyPeriodStartUtc: String? = null,
    val localDailyPeriodEndUtc: String? = null,
    val localDailyMetrics: List<DailyReportHorizonUi> = emptyList(),
    val localHorizonScores: List<AiHorizonScoreUi> = emptyList(),
    val localTopFactorsOverall: String? = null,
    val localTopFactors: List<AiTopFactorUi> = emptyList(),
    val localHotspots: List<AiHotspotUi> = emptyList(),
    val localTopMisses: List<AiTopMissUi> = emptyList(),
    val localDayTypeGaps: List<AiDayTypeGapUi> = emptyList(),
    val circadianReplaySummary: CircadianReplaySummaryUi? = null,
    val localRecommendations: List<String> = emptyList(),
    val rollingLines: List<String> = emptyList(),
    val aiTuningStatus: AiTuningStatusUi? = null,
    val chatMessages: List<AiChatMessageUi> = emptyList(),
    val chatInProgress: Boolean = false,
    val chatDraft: String = "",
    val chatPendingAttachments: List<AiChatAttachmentUi> = emptyList(),
    val chatVoiceRepliesEnabled: Boolean = false,
    val chatRecording: Boolean = false,
    val chatVoiceBusy: Boolean = false,
    val chatSpeaking: Boolean = false,
    val clinicalReport: ClinicalReportUiState = ClinicalReportUiState()
)

data class CircadianReplayMetricUi(
    val horizonMinutes: Int,
    val sampleCount: Int,
    val maeBaseline: Double,
    val maeCircadian: Double,
    val deltaMmol: Double,
    val deltaPct: Double,
    val winRate: Double,
    val qualityScore: Double,
    val bucketStatus: String
)

data class CircadianReplayBucketUi(
    val bucket: String,
    val metrics: List<CircadianReplayMetricUi> = emptyList()
)

data class CircadianReplayWindowUi(
    val days: Int,
    val appliedRows: Int,
    val appliedPct: Double,
    val meanShift30: Double? = null,
    val meanShift60: Double? = null,
    val buckets: List<CircadianReplayBucketUi> = emptyList()
)

data class CircadianReplaySummaryUi(
    val generatedAtTs: Long,
    val windows: List<CircadianReplayWindowUi> = emptyList()
)

data class IsfCrRuntimeDiagnosticsUi(
    val ts: Long? = null,
    val mode: String? = null,
    val confidence: Double? = null,
    val confidenceThreshold: Double? = null,
    val qualityScore: Double? = null,
    val usedEvidence: Int? = null,
    val droppedEvidence: Int? = null,
    val droppedReasons: String? = null,
    val droppedReasonCodes: List<String> = emptyList(),
    val currentDayType: String? = null,
    val isfBaseSource: String? = null,
    val crBaseSource: String? = null,
    val isfDayTypeBaseAvailable: Boolean? = null,
    val crDayTypeBaseAvailable: Boolean? = null,
    val hourWindowIsfEvidence: Int? = null,
    val hourWindowCrEvidence: Int? = null,
    val hourWindowIsfSameDayType: Int? = null,
    val hourWindowCrSameDayType: Int? = null,
    val minIsfEvidencePerHour: Int? = null,
    val minCrEvidencePerHour: Int? = null,
    val crMaxGapMinutes: Double? = null,
    val crMaxSensorBlockedRatePct: Double? = null,
    val crMaxUamAmbiguityRatePct: Double? = null,
    val coverageHoursIsf: Int? = null,
    val coverageHoursCr: Int? = null,
    val reasons: String? = null,
    val reasonCodes: List<String> = emptyList(),
    val lowConfidenceTs: Long? = null,
    val lowConfidenceReasons: String? = null,
    val lowConfidenceReasonCodes: List<String> = emptyList(),
    val fallbackTs: Long? = null,
    val fallbackReasons: String? = null,
    val fallbackReasonCodes: List<String> = emptyList()
)

data class InsulinProfileCurveUi(
    val id: String,
    val label: String,
    val isUltraRapid: Boolean,
    val isSelected: Boolean,
    val points: List<InsulinProfilePointUi>
)

data class InsulinProfilePointUi(
    val minute: Double,
    val cumulative: Double
)

data class DailyReportHorizonUi(
    val horizonMinutes: Int,
    val sampleCount: Int? = null,
    val mae: Double? = null,
    val rmse: Double? = null,
    val mardPct: Double? = null,
    val bias: Double? = null,
    val ciCoveragePct: Double? = null,
    val ciMeanWidth: Double? = null
)

data class DailyReportReplayHotspotUi(
    val horizonMinutes: Int,
    val hour: Int,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayFactorUi(
    val horizonMinutes: Int,
    val factor: String,
    val sampleCount: Int,
    val corrAbsError: Double,
    val maeHigh: Double,
    val maeLow: Double,
    val upliftPct: Double,
    val contributionScore: Double
)

data class DailyReportReplayCoverageUi(
    val horizonMinutes: Int,
    val factor: String,
    val sampleCount: Int,
    val coveragePct: Double
)

data class DailyReportReplayRegimeUi(
    val horizonMinutes: Int,
    val factor: String,
    val bucket: String,
    val sampleCount: Int,
    val meanFactorValue: Double,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayPairUi(
    val horizonMinutes: Int,
    val factorA: String,
    val factorB: String,
    val bucketA: String,
    val bucketB: String,
    val sampleCount: Int,
    val meanFactorA: Double,
    val meanFactorB: Double,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayTopMissUi(
    val horizonMinutes: Int,
    val ts: Long,
    val absError: Double,
    val pred: Double,
    val actual: Double,
    val cob: Double,
    val iob: Double,
    val uam: Double,
    val ciWidth: Double,
    val diaHours: Double,
    val activity: Double,
    val sensorQuality: Double
)

data class DailyReportReplayErrorClusterUi(
    val horizonMinutes: Int,
    val hour: Int,
    val dayType: String,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double,
    val meanCob: Double,
    val meanIob: Double,
    val meanUam: Double,
    val meanCiWidth: Double,
    val dominantFactor: String? = null,
    val dominantScore: Double? = null
)

data class DailyReportReplayDayTypeGapUi(
    val horizonMinutes: Int,
    val hour: Int,
    val worseDayType: String,
    val weekdaySampleCount: Int,
    val weekendSampleCount: Int,
    val weekdayMae: Double,
    val weekendMae: Double,
    val weekdayMardPct: Double,
    val weekendMardPct: Double,
    val maeGapMmol: Double,
    val mardGapPct: Double,
    val worseMeanCob: Double,
    val worseMeanIob: Double,
    val worseMeanUam: Double,
    val worseMeanCiWidth: Double,
    val dominantFactor: String? = null,
    val dominantScore: Double? = null
)

data class DailyReportSensorLagReplayUi(
    val horizonMinutes: Int,
    val bucket: String,
    val sampleCount: Int,
    val rawMae: Double,
    val lagMae: Double,
    val maeImprovementMmol: Double,
    val rawBias: Double,
    val lagBias: Double
)

data class DailyReportSensorLagShadowUi(
    val bucket: String,
    val sampleCount: Int,
    val ruleChangedRatePct: Double,
    val meanAbsTargetDeltaMmol: Double? = null
)

data class AiCredentialUiState(
    val configured: Boolean = false,
    val busy: Boolean = true,
    val migrationError: Boolean = false,
    val readError: Boolean = false,
    val legacyCleanupPending: Boolean = false
) {
    val resetRequired: Boolean
        get() = migrationError || readError
}

data class ClinicalAiProviderOptionUi(
    val id: ClinicalAiProviderId,
    val label: String
)

data class ClinicalAiProtocolOptionUi(
    val id: OpenAiCompatibleProtocol,
    val label: String
)

data class ClinicalAiSettingsLabels(
    val sectionTitle: String = "Clinical AI",
    val provider: String = "Provider",
    val model: String = "Model",
    val customModel: String = "Custom",
    val customModelInput: String = "Custom model",
    val endpoint: String = "Endpoint",
    val protocol: String = "Protocol",
    val automaticCauseAnalysis: String = "Automatic cause analysis",
    val credential: String = "AI credential",
    val credentialAddTitle: String = "Add AI credential",
    val credentialReplaceTitle: String = "Replace AI credential",
    val credentialDeleteTitle: String = "Delete AI credential?",
    val credentialDeleteMessage: String =
        "AI reports will remain unavailable until a new credential is added.",
    val credentialResetTitle: String = "Reset damaged AI credential?",
    val credentialResetMessage: String =
        "The unreadable protected record will be removed.",
    val testConnection: String = "Test connection",
    val testingConnection: String = "Testing connection",
    val invalidEndpoint: String = "Invalid endpoint",
    val invalidModel: String = "Invalid model",
    val invalidConfiguration: String = "Invalid configuration",
    val configSaveFailed: String = "AI provider settings could not be saved",
    val connectionSuccess: String = "Connected",
    val connectionFailure: String = "Connection failed"
)

enum class ClinicalAiSettingsValidationErrorUi {
    INVALID_ENDPOINT,
    INVALID_MODEL,
    INVALID_CONFIGURATION
}

enum class ClinicalAiConnectionTestPhaseUi {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILURE
}

data class ClinicalAiConnectionTestUiState(
    val phase: ClinicalAiConnectionTestPhaseUi = ClinicalAiConnectionTestPhaseUi.IDLE,
    val providerId: ClinicalAiProviderId? = null,
    val modelId: String? = null
) {
    init {
        if (phase == ClinicalAiConnectionTestPhaseUi.IDLE) {
            require(providerId == null && modelId == null) {
                "Idle connection state cannot contain provider metadata"
            }
        } else {
            requireNotNull(providerId) {
                "Active connection state requires a provider"
            }
            ClinicalAiModelIdPolicy.requireValid(
                requireNotNull(modelId) {
                    "Active connection state requires a model"
                }
            )
        }
    }
}

data class ClinicalAiSettingsUiState(
    val effectiveConfig: ClinicalAiProviderConfig,
    val selectedProvider: ClinicalAiProviderId = effectiveConfig.providerId,
    val providerOptions: List<ClinicalAiProviderOptionUi> = DEFAULT_PROVIDER_OPTIONS,
    val modelPresets: List<ClinicalAiModelPreset> =
        ClinicalAiModelCatalog.forProvider(selectedProvider),
    val customModelSelected: Boolean =
        modelPresets.none { it.id == effectiveConfig.modelId },
    val customModelDraft: String =
        effectiveConfig.modelId.takeIf { customModelSelected }.orEmpty(),
    val endpointDraft: String = effectiveConfig.endpoint.orEmpty(),
    val selectedProtocol: OpenAiCompatibleProtocol =
        effectiveConfig.compatibleProtocol ?: OpenAiCompatibleProtocol.RESPONSES,
    val protocolOptions: List<ClinicalAiProtocolOptionUi> = DEFAULT_PROTOCOL_OPTIONS,
    val automaticCauseAnalysisEnabled: Boolean = true,
    val credential: AiCredentialUiState = AiCredentialUiState(),
    val usesLegacyCredentialState: Boolean = false,
    val localValidationError: ClinicalAiSettingsValidationErrorUi? = null,
    val saveError: Boolean = false,
    val connectionTest: ClinicalAiConnectionTestUiState = ClinicalAiConnectionTestUiState(),
    val canTestConnection: Boolean = false,
    val canSendReport: Boolean = false,
    val labels: ClinicalAiSettingsLabels = ClinicalAiSettingsLabels()
) {
    init {
        require(providerOptions.any { it.id == selectedProvider }) {
            "Selected provider must have a presentation option"
        }
        require(protocolOptions.any { it.id == selectedProtocol }) {
            "Selected protocol must have a presentation option"
        }
    }

    companion object {
        private val DEFAULT_PROVIDER_OPTIONS = listOf(
            ClinicalAiProviderOptionUi(ClinicalAiProviderId.OPENAI, "OpenAI"),
            ClinicalAiProviderOptionUi(ClinicalAiProviderId.ANTHROPIC, "Anthropic"),
            ClinicalAiProviderOptionUi(ClinicalAiProviderId.GEMINI, "Gemini"),
            ClinicalAiProviderOptionUi(
                ClinicalAiProviderId.OPENAI_COMPATIBLE,
                "OpenAI compatible"
            )
        )
        private val DEFAULT_PROTOCOL_OPTIONS = listOf(
            ClinicalAiProtocolOptionUi(OpenAiCompatibleProtocol.RESPONSES, "Responses"),
            ClinicalAiProtocolOptionUi(
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                "Chat completions"
            )
        )

        fun defaultOpenAi(
            credential: AiCredentialUiState = AiCredentialUiState()
        ): ClinicalAiSettingsUiState {
            val config = ClinicalAiProviderConfig.defaultOpenAi()
            return ClinicalAiSettingsUiState(
                effectiveConfig = config,
                credential = credential,
                usesLegacyCredentialState = true,
                canTestConnection =
                    credential.configured && !credential.busy && !credential.resetRequired,
                canSendReport =
                    credential.configured && !credential.busy && !credential.resetRequired
            )
        }
    }
}

data class SettingsUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val proModeEnabled: Boolean = false,
    val baseTarget: Double,
    val baseTargetSchedule: BaseTargetSchedule = BaseTargetSchedule.legacy(baseTarget),
    val effectiveBaseTargetMmol: Double = baseTarget,
    val baseTargetAutoDeltaMmol: Double = 0.0,
    val baseTargetAutoState: CircadianAutoState = CircadianAutoState.OFF,
    val baseTargetAutoReason: String? = null,
    val nightscoutUrl: String,
    val aiApiUrl: String,
    val aiCredential: AiCredentialUiState = AiCredentialUiState(),
    val energyProfile: EnergyProfileSettingsUiState = EnergyProfileSettingsUiState(),
    val clinicalAi: ClinicalAiSettingsUiState =
        ClinicalAiSettingsUiState.defaultOpenAi(aiCredential),
    val uiStyle: String,
    val resolvedNightscoutUrl: String,
    val insulinProfileId: String,
    val localNightscoutEnabled: Boolean,
    val localNightscoutLegacyMigrationAcknowledged: Boolean = false,
    val localNightscoutRuntimeStatus: String = "SETUP",
    val localNightscoutRuntimeReason: String? = null,
    val localNightscoutCaFingerprint: String? = null,
    val localBroadcastIngestEnabled: Boolean,
    val strictBroadcastSenderValidation: Boolean,
    val enableUamInference: Boolean,
    val enableUamBoost: Boolean,
    val uamExport: UamExportControlUi = UamExportControlUi(),
    val enableUamAutoExportCap: Boolean = false,
    val uamAutoExportCapGrams: Int = 10,
    val uamMinSnackG: Int,
    val uamMaxSnackG: Int,
    val uamSnackStepG: Int,
    val sensorLagCorrectionMode: String = "OFF",
    val targetManagerMode: String = "SHADOW",
    val targetManagerModeManualOverride: Boolean = false,
    val targetManagerCopilotPriorityEnabled: Boolean = false,
    val targetManagerPolicyRevision: Long = 0L,
    val circadianPatternsEnabled: Boolean,
    val adaptiveControllerRetargetMinutes: Int = 5,
    val rulePostHypoCooldownMinutes: Int = 30,
    val rulePatternCooldownMinutes: Int = 60,
    val ruleSegmentCooldownMinutes: Int = 60,
    val circadianStableLookbackDays: Int,
    val circadianRecencyLookbackDays: Int,
    val circadianUseWeekendSplit: Boolean,
    val circadianUseReplayResidualBias: Boolean,
    val circadianForecastWeight30: Double,
    val circadianForecastWeight60: Double,
    val softAlertEnabled: Boolean,
    val watch60AlertEnabled: Boolean,
    val warning30AlertEnabled: Boolean,
    val softHighAlertEnabled: Boolean,
    val critical5AlertEnabled: Boolean,
    val lowNowAlertEnabled: Boolean,
    val softAlertLowMmol: Double,
    val softAlertHighMmol: Double,
    val urgentLowMmol: Double,
    val softAlertClipLabel: String,
    val criticalAlertClip1Label: String,
    val criticalAlertClip2Label: String,
    val softAlertAudioStartSeconds: Int,
    val softAlertAudioDurationSeconds: Int,
    val criticalAlertAudio1StartSeconds: Int,
    val criticalAlertAudio1DurationSeconds: Int,
    val criticalAlertAudio2StartSeconds: Int,
    val criticalAlertAudio2DurationSeconds: Int,
    val softAlertAudioValid: Boolean,
    val criticalAlertAudio1Valid: Boolean,
    val criticalAlertAudio2Valid: Boolean,
    val softAlertAudioPreviewing: Boolean,
    val criticalAlertAudio1Previewing: Boolean,
    val criticalAlertAudio2Previewing: Boolean,
    val isfRuntimeSourcePreference: String = "UNAVAILABLE",
    val crRuntimeSourcePreference: String = "UNAVAILABLE",
    val sensitivitySourceApplying: Boolean = false,
    val sensitivitySourcePendingMetric: String? = null,
    val sensitivitySourcePendingValue: String? = null,
    val sensitivitySourceApplyError: String? = null,
    val isfCrShadowMode: Boolean,
    val isfCrConfidenceThreshold: Double,
    val isfCrUseActivity: Boolean,
    val isfCrUseManualTags: Boolean,
    val isfCrMinIsfEvidencePerHour: Int,
    val isfCrMinCrEvidencePerHour: Int,
    val isfCrCrMaxGapMinutes: Int,
    val isfCrCrMaxSensorBlockedRatePct: Double,
    val isfCrCrMaxUamAmbiguityRatePct: Double,
    val isfCrSnapshotRetentionDays: Int,
    val isfCrEvidenceRetentionDays: Int,
    val isfCrAutoActivationEnabled: Boolean,
    val isfCrAutoActivationLookbackHours: Int,
    val isfCrAutoActivationMinSamples: Int,
    val isfCrAutoActivationMinMeanConfidence: Double,
    val isfCrAutoActivationMaxMeanAbsIsfDeltaPct: Double,
    val isfCrAutoActivationMaxMeanAbsCrDeltaPct: Double,
    val isfCrAutoActivationMinSensorQualityScore: Double,
    val isfCrAutoActivationMinSensorFactor: Double,
    val isfCrAutoActivationMaxWearConfidencePenalty: Double,
    val isfCrAutoActivationMaxSensorAgeHighRatePct: Double,
    val isfCrAutoActivationMaxSuspectFalseLowRatePct: Double,
    val isfCrAutoActivationMinDayTypeRatio: Double,
    val isfCrAutoActivationMaxDayTypeSparseRatePct: Double,
    val isfCrAutoActivationRequireDailyQualityGate: Boolean,
    val isfCrAutoActivationDailyRiskBlockLevel: Int,
    val isfCrAutoActivationMinDailyMatchedSamples: Int,
    val isfCrAutoActivationMaxDailyMae30Mmol: Double,
    val isfCrAutoActivationMaxDailyMae60Mmol: Double,
    val isfCrAutoActivationMaxHypoRatePct: Double,
    val isfCrAutoActivationMinDailyCiCoverage30Pct: Double,
    val isfCrAutoActivationMinDailyCiCoverage60Pct: Double,
    val isfCrAutoActivationMaxDailyCiWidth30Mmol: Double,
    val isfCrAutoActivationMaxDailyCiWidth60Mmol: Double,
    val isfCrAutoActivationRollingMinRequiredWindows: Int = 2,
    val isfCrAutoActivationRollingMaeRelaxFactor: Double = 1.15,
    val isfCrAutoActivationRollingCiCoverageRelaxFactor: Double = 0.90,
    val isfCrAutoActivationRollingCiWidthRelaxFactor: Double = 1.25,
    val isfCrActiveTags: List<String>,
    val isfCrTagJournal: List<PhysioTagJournalItemUi> = emptyList(),
    val adaptiveControllerEnabled: Boolean,
    val safetyMinTargetMmol: Double,
    val safetyMaxTargetMmol: Double,
    val postHypoThresholdMmol: Double,
    val postHypoTargetMmol: Double,
    val verboseLogsEnabled: Boolean,
    val retentionDays: Int,
    val warningText: String
)

data class ResolvedProfileSummaryUi(
    val value: String = "Default",
    val source: String = "Default",
    val confidence: EvidenceTier = EvidenceTier.INSUFFICIENT_DATA,
    val qualityDays: Int = 0,
    val calculatedAtMs: Long? = null
)

data class PlannedActivityEventUi(
    val eventId: String,
    val enabled: Boolean,
    val title: String,
    val activityType: String,
    val intensity: String,
    val localStartIso: String,
    val durationMinutes: Int,
    val timezoneId: String,
    val recurrenceDaysMask: Int,
    val recurrenceEndEpochDay: Long?,
    val revision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class UserProfileDraftUi(
    val birthDateEpochDay: Long? = null,
    val physiologicalSex: PhysiologicalSex = PhysiologicalSex.UNSPECIFIED,
    val heightCm: Double? = null,
    val weightKg: Double? = null
)

data class FoodProfileSettingsUi(
    val mode: FoodProfileMode = FoodProfileMode.AUTO,
    val manualProfile: MealAbsorptionProfile = MealAbsorptionProfile.MIXED
)

data class ActivityProfileSettingsUi(
    val mode: ActivityProfileMode = ActivityProfileMode.AUTO,
    val manualProfile: ActivityProfile = ActivityProfile.MODERATE,
    val forecastInfluenceEnabled: Boolean = false
)

data class EnergyGoalSettingsUi(
    val mode: CalorieGoalMode = CalorieGoalMode.OFF,
    val manualTargetKcal: Int? = null,
    val shareProfileWithAi: Boolean = true
)

data class EnergyProfileSettingsUiState(
    val enabled: Boolean = false,
    val derivedAgeYears: Int? = null,
    val completeness: String = "Not configured",
    val foodSummary: ResolvedProfileSummaryUi = ResolvedProfileSummaryUi(),
    val activitySummary: ResolvedProfileSummaryUi = ResolvedProfileSummaryUi(),
    val calorieSummary: String = "Off",
    val shareProfileWithAi: Boolean = true,
    val userProfile: UserProfileDraftUi = UserProfileDraftUi(),
    val foodSettings: FoodProfileSettingsUi = FoodProfileSettingsUi(),
    val activitySettings: ActivityProfileSettingsUi = ActivityProfileSettingsUi(),
    val energyGoalSettings: EnergyGoalSettingsUi = EnergyGoalSettingsUi(),
    val plannedEvents: List<PlannedActivityEventUi> = emptyList(),
    val validationMessage: String? = null
)

data class PhysioTagJournalItemUi(
    val id: String,
    val revision: Long,
    val tagType: String,
    val severity: Double,
    val tsStart: Long,
    val tsEnd: Long,
    val isActive: Boolean,
    val source: String,
    val note: String
)
