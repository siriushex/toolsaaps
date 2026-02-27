package io.aaps.copilot.data.remote.cloud

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CopilotCloudApi {

    @GET("v1/sync/pull")
    suspend fun pullSync(@Query("since") since: Long): SyncPullResponse

    @POST("v1/sync/push")
    suspend fun pushSync(@Body request: SyncPushRequest): SyncPushResponse

    @POST("v1/predict")
    suspend fun predict(@Body request: PredictRequest): PredictResponse

    @POST("v1/rules/evaluate")
    suspend fun evaluateRules(@Body request: EvaluateRulesRequest): EvaluateRulesResponse

    @POST("v1/actions/temp-target")
    suspend fun createTempTarget(@Body request: CloudTempTargetRequest): CloudActionResponse

    @GET("v1/actions/{id}")
    suspend fun getAction(@Path("id") id: String): CloudActionResponse

    @POST("v1/analysis/daily")
    suspend fun dailyAnalysis(@Body request: DailyAnalysisRequest): DailyAnalysisResponse

    @GET("v1/analysis/history")
    suspend fun analysisHistory(
        @Query("limit") limit: Int,
        @Query("source") source: String?,
        @Query("status") status: String?,
        @Query("days") days: Int
    ): AnalysisHistoryResponse

    @GET("v1/analysis/trend")
    suspend fun analysisTrend(
        @Query("weeks") weeks: Int,
        @Query("source") source: String?,
        @Query("status") status: String?
    ): AnalysisTrendResponse

    @GET("v1/models/active")
    suspend fun activeModels(): ActiveModelsResponse

    @GET("v1/jobs/status")
    suspend fun jobsStatus(): CloudJobsStatusResponse

    @POST("v1/replay/report")
    suspend fun replayReport(@Body request: ReplayReportRequest): ReplayReportResponse
}
