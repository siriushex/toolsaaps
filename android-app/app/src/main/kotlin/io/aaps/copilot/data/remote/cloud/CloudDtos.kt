package io.aaps.copilot.data.remote.cloud

import kotlinx.serialization.Serializable

@Serializable
data class SyncPullResponse(
    val glucose: List<CloudGlucosePoint>,
    val therapyEvents: List<CloudTherapyEvent>,
    val nextSince: Long
)

@Serializable
data class SyncPushRequest(
    val glucose: List<CloudGlucosePoint>,
    val therapyEvents: List<CloudTherapyEvent>
)

@Serializable
data class SyncPushResponse(
    val acceptedGlucose: Int,
    val acceptedTherapyEvents: Int,
    val nextSince: Long
)

@Serializable
data class CloudGlucosePoint(
    val ts: Long,
    val valueMmol: Double,
    val source: String,
    val quality: String
)

@Serializable
data class CloudTherapyEvent(
    val id: String,
    val ts: Long,
    val type: String,
    val payload: Map<String, String>
)

@Serializable
data class PredictRequest(
    val glucose: List<CloudGlucosePoint>,
    val therapyEvents: List<CloudTherapyEvent>
)

@Serializable
data class PredictResponse(
    val forecasts: List<CloudForecast>,
    val reasonCodes: List<String>
)

@Serializable
data class CloudForecast(
    val ts: Long,
    val horizon: Int,
    val valueMmol: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val modelVersion: String
)

@Serializable
data class EvaluateRulesRequest(
    val glucose: List<CloudGlucosePoint>,
    val therapyEvents: List<CloudTherapyEvent>
)

@Serializable
data class EvaluateRulesResponse(
    val decisions: List<CloudRuleDecision>
)

@Serializable
data class CloudRuleDecision(
    val ruleId: String,
    val state: String,
    val reasons: List<String>,
    val actionProposal: CloudActionProposal?
)

@Serializable
data class CloudActionProposal(
    val type: String,
    val targetMmol: Double,
    val durationMinutes: Int,
    val reason: String
)

@Serializable
data class CloudTempTargetRequest(
    val id: String,
    val targetMmol: Double,
    val durationMinutes: Int,
    val idempotencyKey: String
)

@Serializable
data class CloudActionResponse(
    val id: String,
    val status: String,
    val message: String
)

@Serializable
data class DailyAnalysisRequest(
    val date: String,
    val locale: String
)

@Serializable
data class DailyAnalysisResponse(
    val summary: String,
    val anomalies: List<String>,
    val recommendations: List<String>
)

@Serializable
data class AnalysisHistoryResponse(
    val items: List<AnalysisHistoryItem>
)

@Serializable
data class AnalysisHistoryItem(
    val runTs: Long,
    val date: String,
    val locale: String,
    val source: String,
    val status: String,
    val summary: String,
    val anomalies: List<String>,
    val recommendations: List<String>,
    val errorMessage: String? = null
)

@Serializable
data class AnalysisTrendResponse(
    val items: List<AnalysisTrendItem>
)

@Serializable
data class AnalysisTrendItem(
    val weekStart: String,
    val totalRuns: Int,
    val successRuns: Int,
    val failedRuns: Int,
    val manualRuns: Int,
    val schedulerRuns: Int,
    val anomaliesCount: Int,
    val recommendationsCount: Int
)

@Serializable
data class ActiveModelsResponse(
    val models: List<ModelInfo>
)

@Serializable
data class CloudJobsStatusResponse(
    val timezone: String,
    val jobs: List<CloudJobStatus>
)

@Serializable
data class CloudJobStatus(
    val jobId: String,
    val lastRunTs: Long?,
    val lastSuccessTs: Long?,
    val lastStatus: String?,
    val lastMessage: String?,
    val nextRunTs: Long?
)

@Serializable
data class ModelInfo(
    val horizon: Int,
    val modelVersion: String,
    val mae: Double,
    val updatedAt: Long
)

@Serializable
data class ReplayReportRequest(
    val since: Long? = null,
    val until: Long? = null,
    val stepMinutes: Int = 5
)

@Serializable
data class ReplayReportResponse(
    val since: Long,
    val until: Long,
    val points: Int,
    val forecastStats: List<ReplayForecastStats>,
    val ruleStats: List<ReplayRuleStats>,
    val dayTypeStats: List<ReplayDayTypeStats>,
    val hourlyStats: List<ReplayHourStats>,
    val driftStats: List<ReplayDriftStats>
)

@Serializable
data class ReplayForecastStats(
    val horizon: Int,
    val sampleCount: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double
)

@Serializable
data class ReplayRuleStats(
    val ruleId: String,
    val triggered: Int,
    val blocked: Int,
    val noMatch: Int
)

@Serializable
data class ReplayDayTypeStats(
    val dayType: String,
    val forecastStats: List<ReplayForecastStats>
)

@Serializable
data class ReplayHourStats(
    val hour: Int,
    val sampleCount: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double
)

@Serializable
data class ReplayDriftStats(
    val horizon: Int,
    val previousMae: Double,
    val recentMae: Double,
    val deltaMae: Double
)
