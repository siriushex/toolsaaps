package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.ui.IsfCrHistoryPointUi

enum class ScreenLoadState {
    LOADING,
    EMPTY,
    ERROR,
    READY
}

enum class ForecastRangeUi(val hours: Int) {
    H3(3),
    H6(6),
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

data class OverviewUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val isProMode: Boolean = false,
    val glucose: Double? = null,
    val delta: Double? = null,
    val sampleAgeMinutes: Long? = null,
    val horizons: List<HorizonPredictionUi> = emptyList(),
    val uamActive: Boolean? = null,
    val uci0Mmol5m: Double? = null,
    val inferredCarbsLast60g: Double? = null,
    val uamModeLabel: String? = null,
    val telemetryChips: List<TelemetryChipUi> = emptyList(),
    val lastAction: LastActionUi? = null,
    val canRunCycleNow: Boolean = true,
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
    val enableUamExportToAaps: Boolean = false,
    val dryRunExport: Boolean = true
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
    val cooldownStatusLines: List<String> = emptyList(),
    val localNightscoutEnabled: Boolean,
    val localNightscoutPort: Int,
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
    val rollingReportLines: List<String> = emptyList(),
    val currentIsfReal: Double? = null,
    val currentCrReal: Double? = null,
    val currentIsfMerged: Double? = null,
    val currentCrMerged: Double? = null,
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
    val historyLastUpdatedTs: Long? = null,
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
    val ts: Long
)

data class AiAnalysisUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val minDataHours: Int = 24,
    val dataCoverageHours: Double = 0.0,
    val analysisReady: Boolean = false,
    val cloudConfigured: Boolean = false,
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
    val localRecommendations: List<String> = emptyList(),
    val rollingLines: List<String> = emptyList(),
    val aiTuningStatus: AiTuningStatusUi? = null,
    val chatMessages: List<AiChatMessageUi> = emptyList(),
    val chatInProgress: Boolean = false
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

data class SettingsUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val proModeEnabled: Boolean = false,
    val baseTarget: Double,
    val nightscoutUrl: String,
    val aiApiUrl: String,
    val aiApiKey: String,
    val uiStyle: String,
    val resolvedNightscoutUrl: String,
    val insulinProfileId: String,
    val localNightscoutEnabled: Boolean,
    val localBroadcastIngestEnabled: Boolean,
    val strictBroadcastSenderValidation: Boolean,
    val enableUamInference: Boolean,
    val enableUamBoost: Boolean,
    val enableUamExportToAaps: Boolean,
    val uamExportMode: String,
    val dryRunExport: Boolean,
    val uamMinSnackG: Int,
    val uamMaxSnackG: Int,
    val uamSnackStepG: Int,
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

data class PhysioTagJournalItemUi(
    val id: String,
    val tagType: String,
    val severity: Double,
    val tsStart: Long,
    val tsEnd: Long,
    val isActive: Boolean,
    val source: String,
    val note: String
)
