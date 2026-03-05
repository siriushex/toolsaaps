package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.isCopilotCloudBackendEndpoint
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.remote.cloud.CloudGlucosePoint
import io.aaps.copilot.data.remote.cloud.CloudTherapyEvent
import io.aaps.copilot.data.remote.cloud.PredictRequest
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.predict.CarbAbsorptionProfiles
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.InsulinActionPoint
import io.aaps.copilot.domain.predict.InsulinActionProfile
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.InsulinActionProfiles
import io.aaps.copilot.domain.predict.UamInferenceEngine
import io.aaps.copilot.domain.predict.UamCalculator
import io.aaps.copilot.domain.predict.UamUserSettings
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.rules.RuleContext
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.RuleRuntimeConfig
import io.aaps.copilot.domain.safety.SafetyPolicyConfig
import io.aaps.copilot.service.ApiFactory
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.min

class AutomationRepository(
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val syncRepository: SyncRepository,
    private val exportRepository: AapsExportRepository,
    private val autoConnectRepository: AapsAutoConnectRepository,
    private val rootDbRepository: RootDbExperimentalRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val isfCrRepository: IsfCrRepository,
    private val actionRepository: NightscoutActionRepository,
    private val predictionEngine: PredictionEngine,
    private val uamInferenceEngine: UamInferenceEngine,
    private val uamEventStore: UamEventStore,
    private val uamExportCoordinator: UamExportCoordinator,
    private val ruleEngine: RuleEngine,
    private val apiFactory: ApiFactory,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {

    private val cycleMutex = Mutex()
    private val isfCrRealtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var lastUamProcessingBucketTs: Long = Long.MIN_VALUE
    @Volatile
    private var lastIsfCrShadowActivationEvalBucketTs: Long = Long.MIN_VALUE
    @Volatile
    private var currentCycleStartedAtTs: Long = 0L
    @Volatile
    private var isfCrRealtimeRefreshInFlight = false
    @Volatile
    private var isfCrRealtimeRefreshStartedAtTs = 0L
    @Volatile
    private var isfCrRealtimeLastFailureTs = 0L
    @Volatile
    private var isfCrRealtimeRefreshJob: Job? = null

    data class DryRunRuleSummary(
        val ruleId: String,
        val triggered: Int,
        val blocked: Int,
        val noMatch: Int
    )

    data class DryRunReport(
        val periodDays: Int,
        val samplePoints: Int,
        val rules: List<DryRunRuleSummary>
    )

    private data class CalculatedUamSnapshot(
        val flag: Double,
        val confidence: Double,
        val estimatedCarbsGrams: Double?,
        val rise15Mmol: Double?,
        val rise30Mmol: Double?,
        val delta5Mmol: Double?
    )

    private data class UamInferenceCycleResult(
        val activeFlag: Double,
        val confidence: Double?,
        val inferredCarbsGrams: Double?,
        val ingestionTs: Long?,
        val modeBoosted: Boolean,
        val manualCobGrams: Double,
        val gAbsRecent: List<Double>,
        val events: List<io.aaps.copilot.domain.predict.UamInferenceEvent>,
        val createdNewEvent: Boolean
    )

    data class SensorQualityAssessment(
        val score: Double,
        val blocked: Boolean,
        val reason: String,
        val suspectFalseLow: Boolean,
        val delta5Mmol: Double?,
        val noiseStd5Mmol: Double?,
        val gapMinutes: Double
    )

    data class ForecastCalibrationPoint(
        val horizonMinutes: Int,
        val errorMmol: Double,
        val ageMs: Long,
        val predictedMmol: Double = Double.NaN
    )

    data class CalibrationAiTuning(
        val gainScale: Double,
        val maxUpScale: Double,
        val maxDownScale: Double
    )

    data class ForecastDecompositionSnapshot(
        val trend60Mmol: Double,
        val therapy60Mmol: Double,
        val uam60Mmol: Double,
        val residualRoc0Mmol5: Double,
        val sigmaEMmol5: Double,
        val kfSigmaGMmol: Double,
        val modelVersion: String
    )

    data class IsfCrRuntimeGate(
        val applyToRuntime: Boolean,
        val reason: String
    )

    data class IsfCrShadowDiffSample(
        val confidence: Double,
        val isfDeltaPct: Double,
        val crDeltaPct: Double
    )

    data class IsfCrShadowActivationAssessment(
        val eligible: Boolean,
        val reason: String,
        val sampleCount: Int,
        val meanConfidence: Double,
        val meanAbsIsfDeltaPct: Double,
        val meanAbsCrDeltaPct: Double
    )

    data class IsfCrDayTypeStabilitySample(
        val isfSameDayTypeRatio: Double,
        val crSameDayTypeRatio: Double,
        val isfSparseFlag: Boolean,
        val crSparseFlag: Boolean
    )

    data class IsfCrDayTypeStabilityAssessment(
        val eligible: Boolean,
        val reason: String,
        val sampleCount: Int,
        val meanIsfSameDayTypeRatio: Double,
        val meanCrSameDayTypeRatio: Double,
        val isfSparseRatePct: Double,
        val crSparseRatePct: Double
    )

    data class IsfCrSensorQualitySample(
        val qualityScore: Double,
        val sensorFactor: Double,
        val wearConfidencePenalty: Double,
        val sensorAgeHighFlag: Boolean,
        val suspectFalseLowFlag: Boolean
    )

    data class IsfCrSensorQualityAssessment(
        val eligible: Boolean,
        val reason: String,
        val sampleCount: Int,
        val meanQualityScore: Double,
        val meanSensorFactor: Double,
        val meanWearPenalty: Double,
        val sensorAgeHighRatePct: Double,
        val suspectFalseLowRatePct: Double
    )

    data class IsfCrDailyQualityGateAssessment(
        val eligible: Boolean,
        val reason: String,
        val matchedSamples: Int?,
        val mae30Mmol: Double?,
        val mae60Mmol: Double?,
        val hypoRatePct24h: Double?,
        val ciCoverage30Pct: Double?,
        val ciCoverage60Pct: Double?,
        val ciWidth30Mmol: Double?,
        val ciWidth60Mmol: Double?
    )

    data class IsfCrRollingQualityWindowAssessment(
        val days: Int,
        val available: Boolean,
        val eligible: Boolean,
        val reason: String,
        val matchedSamples: Int?,
        val mae30Mmol: Double?,
        val mae60Mmol: Double?,
        val ciCoverage30Pct: Double?,
        val ciCoverage60Pct: Double?,
        val ciWidth30Mmol: Double?,
        val ciWidth60Mmol: Double?
    )

    data class IsfCrRollingQualityGateAssessment(
        val eligible: Boolean,
        val reason: String,
        val requiredWindowCount: Int,
        val evaluatedWindowCount: Int,
        val passedWindowCount: Int,
        val windows: List<IsfCrRollingQualityWindowAssessment>
    )

    data class IsfCrDailyRiskGateAssessment(
        val eligible: Boolean,
        val reason: String,
        val riskLevel: Int
    )

    data class RuntimeCobIobInputs(
        val cobGrams: Double,
        val iobUnits: Double,
        val realIobUnits: Double,
        val localCobGrams: Double,
        val localIobUnits: Double,
        val realOnsetMinutes: Double,
        val baseOnsetMinutes: Double,
        val onsetSampleCount: Int,
        val usedLocalFallback: Boolean,
        val mergedWithTelemetry: Boolean
    )

    private data class LocalCobIobEstimate(
        val cobGrams: Double,
        val iobUnits: Double,
        val explicitInsulinEvents: Int,
        val realOnsetMinutes: Double,
        val baseOnsetMinutes: Double,
        val onsetSampleCount: Int
    )

    private data class RealInsulinProfileEstimate(
        val updatedTs: Long,
        val pointsCompact: String,
        val confidence: Double,
        val sampleCount: Int,
        val onsetMinutes: Double,
        val peakMinutes: Double,
        val shapeScale: Double,
        val sourceProfileId: String,
        val status: String,
        val lastPublishedTs: Long,
        val algoVersion: String
    )

    private data class InsulinPulseCandidate(
        val ts: Long,
        val units: Double,
        val source: String
    )

    suspend fun runAutomationCycle() {
        val now = System.currentTimeMillis()
        if (!cycleMutex.tryLock()) {
            val runningForMs = now - currentCycleStartedAtTs
            val meta = mutableMapOf<String, Any>(
                "reason" to "already_running",
                "runningForMs" to runningForMs
            )
            if (runningForMs >= AUTOMATION_STALL_WARN_MS) {
                meta["stallSuspected"] = true
                auditLogger.warn("automation_cycle_skipped", meta)
            } else {
                auditLogger.info("automation_cycle_skipped", meta)
            }
            return
        }
        currentCycleStartedAtTs = now
        auditLogger.info("automation_cycle_started", mapOf("startedAtTs" to now))
        try {
            withTimeout(AUTOMATION_CYCLE_TIMEOUT_MS) {
                runAutomationCycleLocked()
            }
            auditLogger.info(
                "automation_cycle_finished",
                mapOf(
                    "status" to "success",
                    "durationMs" to (System.currentTimeMillis() - now)
                )
            )
        } catch (error: TimeoutCancellationException) {
            auditLogger.error(
                "automation_cycle_timeout",
                mapOf(
                    "timeoutMs" to AUTOMATION_CYCLE_TIMEOUT_MS,
                    "durationMs" to (System.currentTimeMillis() - now)
                )
            )
            throw error
        } catch (error: Throwable) {
            auditLogger.warn(
                "automation_cycle_failed",
                mapOf(
                    "durationMs" to (System.currentTimeMillis() - now),
                    "error" to (error.message ?: error::class.simpleName.orEmpty())
                )
            )
            throw error
        } finally {
            currentCycleStartedAtTs = 0L
            cycleMutex.unlock()
        }
    }

    private suspend fun runAutomationCycleLocked() {
        runCycleStep("auto_connect_bootstrap") {
            autoConnectRepository.bootstrap()
        }
        val settings = settingsStore.settings.first()
        configurePredictionEngine(settings)
        runCycleStep("root_db_sync") {
            rootDbRepository.syncIfEnabled()
        }
        runCycleStep("nightscout_sync") {
            syncRepository.syncNightscoutIncremental()
        }
        runCycleStep("cloud_push_sync") {
            syncRepository.pushCloudIncremental()
        }
        runCycleStep("baseline_import") {
            exportRepository.importBaselineFromExports()
        }
        maybeRecalculateAnalytics(settings)
        val removedInvalidTelemetryTs = db.telemetryDao().deleteByTimestampAtOrBelow(0L)
        if (removedInvalidTelemetryTs > 0) {
            auditLogger.info(
                "telemetry_invalid_timestamp_cleanup",
                mapOf("removedRows" to removedInvalidTelemetryTs)
            )
        }
        auditLogger.info("automation_cycle_checkpoint", mapOf("stage" to "post_initial_steps"))

        val glucose = syncRepository.recentGlucose(limit = 72)
        if (glucose.isEmpty()) {
            auditLogger.warn("automation_skipped", mapOf("reason" to "no_glucose_data"))
            return
        }

        val therapy = syncRepository.recentTherapyEvents(hoursBack = 24)
        auditLogger.info(
            "automation_cycle_checkpoint",
            mapOf(
                "stage" to "post_recent_data",
                "glucosePoints" to glucose.size,
                "therapyEvents" to therapy.size
            )
        )
        val now = System.currentTimeMillis()
        val latestTelemetry = resolveLatestTelemetry(nowTs = now, settings = settings).toMutableMap()
        val realtimeIsfCrSnapshot = resolveRealtimeIsfCrSnapshot(settings = settings, nowTs = now)
        val isfCrRuntimeGate = resolveIsfCrRuntimeGateStatic(
            snapshot = realtimeIsfCrSnapshot,
            confidenceThreshold = settings.isfCrConfidenceThreshold
        )
        val isfCrOverrideBlendWeight = resolveIsfCrOverrideBlendWeightStatic(
            snapshot = realtimeIsfCrSnapshot,
            runtimeGate = isfCrRuntimeGate,
            confidenceThreshold = settings.isfCrConfidenceThreshold
        )
        configurePredictionEngine(
            settings = settings,
            realtimeIsfCr = realtimeIsfCrSnapshot,
            runtimeGate = isfCrRuntimeGate,
            overrideBlendWeight = isfCrOverrideBlendWeight,
            runtimeTelemetry = latestTelemetry
        )
        realtimeIsfCrSnapshot?.let { snapshot ->
            latestTelemetry["isf_realtime_value"] = snapshot.isfEff
            latestTelemetry["cr_realtime_value"] = snapshot.crEff
            latestTelemetry["isf_realtime_confidence"] = snapshot.confidence
            latestTelemetry["isf_realtime_quality_score"] = snapshot.qualityScore
            latestTelemetry["isf_realtime_applied"] = if (isfCrRuntimeGate.applyToRuntime) 1.0 else 0.0
            latestTelemetry["isf_realtime_override_blend_weight"] = isfCrOverrideBlendWeight ?: 0.0
            latestTelemetry["isf_realtime_mode"] = when (snapshot.mode.name) {
                "ACTIVE" -> 1.0
                "SHADOW" -> 0.5
                else -> 0.0
            }
            latestTelemetry["isf_factor_set_factor"] = snapshot.factors["set_factor"]
            latestTelemetry["isf_factor_sensor_factor"] = snapshot.factors["sensor_factor"]
            latestTelemetry["isf_factor_activity_factor"] = snapshot.factors["activity_factor"]
            latestTelemetry["isf_factor_dawn_factor"] = snapshot.factors["dawn_factor"]
            latestTelemetry["isf_factor_stress_factor"] = snapshot.factors["stress_factor"]
            latestTelemetry["isf_factor_hormone_factor"] = snapshot.factors["hormone_factor"]
            latestTelemetry["isf_factor_steroid_factor"] = snapshot.factors["steroid_factor"]
            latestTelemetry["isf_factor_uam_penalty"] = snapshot.factors["uam_penalty_factor"]
            latestTelemetry["isf_factor_set_age_hours"] = snapshot.factors["set_age_hours"]
            latestTelemetry["isf_factor_sensor_age_hours"] = snapshot.factors["sensor_age_hours"]
            latestTelemetry["isf_factor_context_ambiguity"] = listOfNotNull(
                snapshot.factors["latent_stress"],
                snapshot.factors["manual_stress_tag"],
                snapshot.factors["manual_illness_tag"],
                snapshot.factors["manual_hormone_tag"],
                snapshot.factors["manual_steroid_tag"],
                snapshot.factors["manual_dawn_tag"]
            ).maxOrNull() ?: 0.0
            auditLogger.info(
                "isfcr_runtime_gate",
                mapOf(
                    "mode" to snapshot.mode.name,
                    "confidence" to snapshot.confidence,
                    "threshold" to settings.isfCrConfidenceThreshold,
                    "applied" to isfCrRuntimeGate.applyToRuntime,
                    "reason" to isfCrRuntimeGate.reason,
                    "overrideBlendWeight" to isfCrOverrideBlendWeight,
                    "shadowSoftBlendApplied" to (!isfCrRuntimeGate.applyToRuntime && (isfCrOverrideBlendWeight ?: 0.0) > 0.0)
                )
            )
        }
        auditLogger.info(
            "automation_cycle_checkpoint",
            mapOf(
                "stage" to "post_isfcr",
                "hasRealtimeSnapshot" to (realtimeIsfCrSnapshot != null),
                "runtimeGateApplied" to isfCrRuntimeGate.applyToRuntime
            )
        )
        val runtimeCobIob = resolveRuntimeCobIobInputs(
            nowTs = now,
            glucose = glucose,
            therapy = therapy,
            telemetry = latestTelemetry,
            settings = settings
        )
        val insulinTherapyAvailable = hasInsulinTherapyEvidence(therapy)
        val rawCobFromTelemetry = latestTelemetry["cob_grams"]
        val rawIobFromTelemetry = latestTelemetry["iob_units"]
        latestTelemetry["cob_grams"] = runtimeCobIob.cobGrams
        latestTelemetry["iob_units"] = runtimeCobIob.iobUnits
        latestTelemetry["cob_effective_grams"] = runtimeCobIob.cobGrams
        latestTelemetry["iob_effective_units"] = runtimeCobIob.iobUnits
        latestTelemetry["iob_real_units"] = runtimeCobIob.realIobUnits
        latestTelemetry["cob_local_fallback_grams"] = runtimeCobIob.localCobGrams
        latestTelemetry["iob_local_fallback_units"] = runtimeCobIob.localIobUnits
        latestTelemetry["insulin_real_onset_min"] = runtimeCobIob.realOnsetMinutes
        latestTelemetry["insulin_profile_base_onset_min"] = runtimeCobIob.baseOnsetMinutes
        latestTelemetry["insulin_real_onset_samples"] = runtimeCobIob.onsetSampleCount.toDouble()
        rawCobFromTelemetry?.let { latestTelemetry["cob_external_raw_grams"] = it }
        rawIobFromTelemetry?.let { latestTelemetry["iob_external_raw_units"] = it }
        latestTelemetry["cob_iob_local_used"] = if (runtimeCobIob.usedLocalFallback) 1.0 else 0.0
        latestTelemetry["cob_iob_merged"] = if (runtimeCobIob.mergedWithTelemetry) 1.0 else 0.0
        persistRuntimeCobIobTelemetry(
            nowTs = now,
            runtime = runtimeCobIob,
            rawCobGrams = rawCobFromTelemetry,
            rawIobUnits = rawIobFromTelemetry
        )
        val realInsulinProfile = refreshRealInsulinProfileTelemetry(
            nowTs = now,
            settings = settings
        )
        if (realInsulinProfile != null) {
            latestTelemetry["insulin_profile_real_updated_ts"] = realInsulinProfile.updatedTs.toDouble()
            latestTelemetry["insulin_profile_real_confidence"] = realInsulinProfile.confidence
            latestTelemetry["insulin_profile_real_samples"] = realInsulinProfile.sampleCount.toDouble()
            latestTelemetry["insulin_profile_real_onset_min"] = realInsulinProfile.onsetMinutes
            latestTelemetry["insulin_profile_real_peak_min"] = realInsulinProfile.peakMinutes
            latestTelemetry["insulin_profile_real_scale"] = realInsulinProfile.shapeScale
            latestTelemetry["insulin_profile_real_published_ts"] = realInsulinProfile.lastPublishedTs.toDouble()
        }
        if (runtimeCobIob.usedLocalFallback || runtimeCobIob.mergedWithTelemetry) {
            auditLogger.info(
                "cob_iob_runtime_resolved",
                mapOf(
                    "cobGrams" to runtimeCobIob.cobGrams,
                    "iobUnits" to runtimeCobIob.iobUnits,
                    "realIobUnits" to runtimeCobIob.realIobUnits,
                    "localCobGrams" to runtimeCobIob.localCobGrams,
                    "localIobUnits" to runtimeCobIob.localIobUnits,
                    "realOnsetMinutes" to runtimeCobIob.realOnsetMinutes,
                    "baseOnsetMinutes" to runtimeCobIob.baseOnsetMinutes,
                    "onsetSampleCount" to runtimeCobIob.onsetSampleCount,
                    "usedLocalFallback" to runtimeCobIob.usedLocalFallback,
                    "mergedWithTelemetry" to runtimeCobIob.mergedWithTelemetry
                )
            )
        }
        if (!insulinTherapyAvailable && runtimeCobIob.iobUnits >= 0.3) {
            auditLogger.warn(
                "forecast_insulin_events_missing",
                mapOf(
                    "iobUnits" to runtimeCobIob.iobUnits,
                    "therapyEvents24h" to therapy.size,
                    "reason" to "iob_present_without_insulin_therapy_events"
                )
            )
        }
        auditLogger.info("automation_cycle_checkpoint", mapOf("stage" to "post_cob_iob_runtime"))
        val zoned = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
        val currentPattern = db.patternDao().byDayAndHour(dayType.name, zoned.hour)?.let {
            io.aaps.copilot.domain.model.PatternWindow(
                dayType = io.aaps.copilot.domain.model.DayType.valueOf(it.dayType),
                hour = it.hour,
                sampleCount = it.sampleCount,
                activeDays = it.activeDays,
                lowRate = it.lowRate,
                highRate = it.highRate,
                recommendedTargetMmol = it.recommendedTargetMmol,
                isRiskWindow = it.isRiskWindow
            )
        }
        val effectiveBaseTarget = resolveEffectiveBaseTarget(settings.baseTargetMmol, latestTelemetry)
        val latestGlucose = glucose.maxBy { it.ts }
        val localForecasts = predictionEngine.predict(glucose, therapy)
        val mergedForecastsRaw = ensureForecast30(
            maybeMergeCloudPrediction(glucose, therapy, localForecasts)
        )
        val calibrationErrors = collectForecastCalibrationErrors(nowTs = now)
        val calibrationTuning = resolveAiCalibrationTuning(
            latestTelemetry = latestTelemetry,
            nowTs = now
        )
        val mergedForecastsCalibrated = applyRecentForecastCalibrationBias(
            forecasts = mergedForecastsRaw,
            history = calibrationErrors,
            aiTuning = calibrationTuning
        )
        val calibrationApplied = mergedForecastsCalibrated != mergedForecastsRaw
        if (mergedForecastsCalibrated != mergedForecastsRaw) {
            auditLogger.info(
                "forecast_calibration_bias_applied",
                buildCalibrationAuditMeta(
                    source = mergedForecastsRaw,
                    adjusted = mergedForecastsCalibrated,
                    aiTuning = calibrationTuning
                )
            )
        }
        val mergedForecastsContextBiased = applyContextFactorForecastBias(
            forecasts = mergedForecastsCalibrated,
            telemetry = latestTelemetry,
            latestGlucoseMmol = latestGlucose.valueMmol,
            pattern = currentPattern
        )
        val contextBiasApplied = mergedForecastsContextBiased != mergedForecastsCalibrated
        if (mergedForecastsContextBiased != mergedForecastsCalibrated) {
            auditLogger.info(
                "forecast_context_bias_applied",
                mapOf(
                    "setFactor" to latestTelemetry["isf_factor_set_factor"],
                    "sensorFactor" to latestTelemetry["isf_factor_sensor_factor"],
                    "activityFactor" to latestTelemetry["isf_factor_activity_factor"],
                    "dawnFactor" to latestTelemetry["isf_factor_dawn_factor"],
                    "stressFactor" to latestTelemetry["isf_factor_stress_factor"],
                    "hormoneFactor" to latestTelemetry["isf_factor_hormone_factor"],
                    "steroidFactor" to latestTelemetry["isf_factor_steroid_factor"],
                    "contextAmbiguity" to latestTelemetry["isf_factor_context_ambiguity"],
                    "sensorQuality" to latestTelemetry["sensor_quality_score"]
                )
            )
        }
        val mergedForecastsBiased = applyCobIobForecastBias(
            forecasts = mergedForecastsContextBiased,
            cobGrams = latestTelemetry["cob_grams"],
            iobUnits = latestTelemetry["iob_units"],
            latestGlucoseMmol = latestGlucose.valueMmol,
            uamActive = resolveUamActiveTelemetry(latestTelemetry)
        )
        val cobIobBiasApplied = mergedForecastsBiased != mergedForecastsContextBiased
        if (mergedForecastsBiased != mergedForecastsContextBiased) {
            auditLogger.info(
                "forecast_bias_applied",
                mapOf(
                    "cobGrams" to latestTelemetry["cob_grams"],
                    "iobUnits" to latestTelemetry["iob_units"]
                )
            )
        }
        val mergedForecasts = normalizeForecastSet(mergedForecastsBiased)
        if (mergedForecasts.size != mergedForecastsBiased.size) {
            auditLogger.warn(
                "forecast_duplicate_horizon_deduped",
                mapOf(
                    "before" to mergedForecastsBiased.size,
                    "after" to mergedForecasts.size
                )
            )
        }
        val forecastDecomposition = extractForecastDecompositionSnapshotStatic(
            diagnostics = (predictionEngine as? HybridPredictionEngine)?.diagnosticsSnapshot(),
            localForecasts = localForecasts
        )
        persistForecastDecompositionTelemetry(nowTs = now, decomposition = forecastDecomposition)

        val forecastEntities = mergedForecasts.map { it.toForecastEntity() }
        forecastEntities.forEach { row ->
            db.forecastDao().deleteByTimestampAndHorizon(
                timestamp = row.timestamp,
                horizonMinutes = row.horizonMinutes
            )
        }
        db.forecastDao().insertAll(forecastEntities)
        val removedDuplicates = db.forecastDao().deleteDuplicateByTimestampAndHorizon()
        auditLogger.info(
            "forecast_storage_normalized",
            mapOf(
                "insertedRows" to forecastEntities.size,
                "removedDuplicateRows" to removedDuplicates
            )
        )
        if (removedDuplicates > 0) {
            auditLogger.warn("forecast_storage_duplicates_cleaned", mapOf("removedRows" to removedDuplicates))
        }
        db.forecastDao().deleteOlderThan(System.currentTimeMillis() - FORECAST_RETENTION_MS)
        auditLogger.info("automation_cycle_checkpoint", mapOf("stage" to "post_forecast_storage"))

        val effectiveStaleMaxMinutes = resolveEffectiveStaleMaxMinutes(settings)
        val dataFresh = now - latestGlucose.ts <= effectiveStaleMaxMinutes * 60 * 1000L
        val actionsLast6h = actionRepository.countSentActionsLast6h()
        val activeTempTarget = resolveActiveTempTarget(now)
        val therapySensorBlocked = isSensorBlocked(therapy, now)
        val sensorQuality = evaluateSensorQuality(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = effectiveStaleMaxMinutes
        )
        val sensorBlocked = therapySensorBlocked || sensorQuality.blocked
        persistSensorQualityTelemetry(nowTs = now, assessment = sensorQuality)
        latestTelemetry["sensor_quality_score"] = sensorQuality.score
        latestTelemetry["sensor_quality_blocked"] = if (sensorQuality.blocked) 1.0 else 0.0
        latestTelemetry["sensor_quality_suspect_false_low"] = if (sensorQuality.suspectFalseLow) 1.0 else 0.0
        sensorQuality.delta5Mmol?.let { latestTelemetry["sensor_quality_delta5_mmol"] = it }
        sensorQuality.noiseStd5Mmol?.let { latestTelemetry["sensor_quality_noise_std5"] = it }
        latestTelemetry["sensor_quality_gap_min"] = sensorQuality.gapMinutes

        if (sensorQuality.blocked) {
            auditLogger.warn(
                "sensor_quality_gate_blocked",
                mapOf(
                    "reason" to sensorQuality.reason,
                    "score" to sensorQuality.score,
                    "delta5Mmol" to sensorQuality.delta5Mmol,
                    "noiseStd5Mmol" to sensorQuality.noiseStd5Mmol,
                    "gapMinutes" to sensorQuality.gapMinutes
                )
            )
        }
        if (therapySensorBlocked && sensorQuality.blocked) {
            auditLogger.warn(
                "sensor_quality_gate_and_sensor_state_blocked",
                mapOf("reason" to sensorQuality.reason)
            )
        }

        maybeSendSensorQualityRollbackTempTarget(
            settings = settings,
            nowTs = now,
            dataFresh = dataFresh,
            assessment = sensorQuality,
            activeTempTarget = activeTempTarget,
            actionsLast6h = actionsLast6h,
            baseTargetMmol = effectiveBaseTarget
        )
        auditLogger.info("automation_cycle_checkpoint", mapOf("stage" to "post_sensor_quality"))

        val legacyProfile = db.profileEstimateDao().active()?.toProfileEstimate()
        realtimeIsfCrSnapshot?.let { snapshot ->
            if (snapshot.mode.name == "SHADOW") {
                logIsfCrShadowDiff(snapshot = snapshot, legacyProfile = legacyProfile)
                maybeProcessIsfCrShadowAutoActivation(
                    settings = settings,
                    nowTs = now,
                    latestTelemetry = latestTelemetry
                )
            }
        }
        val currentProfile = when {
            isfCrRuntimeGate.applyToRuntime && realtimeIsfCrSnapshot != null ->
                realtimeIsfCrSnapshot.toProfileEstimate(lookbackDays = settings.analyticsLookbackDays)
            legacyProfile != null -> legacyProfile
            realtimeIsfCrSnapshot != null -> {
                auditLogger.warn(
                    "isfcr_runtime_bootstrap_profile",
                    mapOf(
                        "reason" to "legacy_profile_missing",
                        "mode" to realtimeIsfCrSnapshot.mode.name
                    )
                )
                realtimeIsfCrSnapshot.toProfileEstimate(lookbackDays = settings.analyticsLookbackDays)
            }
            else -> null
        }
        val currentSlot = resolveTimeSlot(zoned.hour)
        val currentSegment = db.profileSegmentEstimateDao()
            .byDayTypeAndTimeSlot(dayType.name, currentSlot.name)
            ?.toProfileSegmentEstimate()
        val calculatedUam = calculateCalculatedUamSnapshot(
            glucose = glucose,
            therapy = therapy,
            profile = currentProfile,
            nowTs = now
        )
        latestTelemetry["uam_calculated_flag"] = calculatedUam.flag
        latestTelemetry["uam_calculated_confidence"] = calculatedUam.confidence
        calculatedUam.estimatedCarbsGrams?.let { latestTelemetry["uam_calculated_carbs_grams"] = it }
        persistCalculatedUamTelemetry(nowTs = now, snapshot = calculatedUam)

        val inferredUam = maybeProcessUamInferenceCycle(
            settings = settings,
            nowTs = now,
            glucose = glucose,
            therapy = therapy,
            profile = currentProfile,
            calculatedSnapshot = calculatedUam
        )
        if (inferredUam != null) {
            latestTelemetry["uam_inferred_flag"] = inferredUam.activeFlag
            latestTelemetry["uam_inferred_confidence"] = inferredUam.confidence ?: 0.0
            inferredUam.inferredCarbsGrams?.let { latestTelemetry["uam_inferred_carbs_grams"] = it }
            inferredUam.ingestionTs?.let { latestTelemetry["uam_inferred_ingestion_ts"] = it.toDouble() }
            latestTelemetry["uam_inferred_boost_mode"] = if (inferredUam.modeBoosted) 1.0 else 0.0
            latestTelemetry["uam_manual_cob_grams"] = inferredUam.manualCobGrams
            inferredUam.gAbsRecent.lastOrNull()?.let { latestTelemetry["uam_inferred_gabs_last5_g"] = it }
            latestTelemetry["uam_inferred_events_active"] = inferredUam.events
                .count { event ->
                    event.state == io.aaps.copilot.domain.predict.UamInferenceState.SUSPECTED ||
                        event.state == io.aaps.copilot.domain.predict.UamInferenceState.CONFIRMED
                }
                .toDouble()
            if (inferredUam.activeFlag >= 0.5) {
                latestTelemetry["uam_value"] = 1.0
            }
            persistInferredUamTelemetry(nowTs = now, result = inferredUam)
            if (inferredUam.createdNewEvent) {
                auditLogger.info(
                    "uam_inference_event_created",
                    mapOf(
                        "mode" to if (inferredUam.modeBoosted) "BOOST" else "NORMAL",
                        "confidence" to inferredUam.confidence,
                        "carbsGrams" to inferredUam.inferredCarbsGrams
                    )
                )
            }
        }
        if (latestTelemetry["uam_value"] == null) {
            latestTelemetry["uam_value"] = calculatedUam.flag
        }
        auditLogger.info(
            "forecast_factor_coverage",
            buildForecastFactorCoverageMeta(
                latestTelemetry = latestTelemetry,
                realtimeIsfCrSnapshot = realtimeIsfCrSnapshot,
                runtimeGate = isfCrRuntimeGate,
                runtimeCobIob = runtimeCobIob,
                currentPattern = currentPattern,
                calibrationSampleCount = calibrationErrors.size,
                calibrationApplied = calibrationApplied,
                contextBiasApplied = contextBiasApplied,
                cobIobBiasApplied = cobIobBiasApplied,
                calculatedUam = calculatedUam,
                inferredUam = inferredUam,
                insulinTherapyAvailable = insulinTherapyAvailable
            )
        )
        auditLogger.info("automation_cycle_checkpoint", mapOf("stage" to "post_uam_and_coverage"))

        val context = RuleContext(
            nowTs = now,
            glucose = glucose,
            therapyEvents = therapy,
            forecasts = mergedForecasts,
            currentDayPattern = currentPattern,
            baseTargetMmol = effectiveBaseTarget,
            postHypoThresholdMmol = settings.postHypoThresholdMmol,
            postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m,
            postHypoTargetMmol = settings.postHypoTargetMmol,
            postHypoDurationMinutes = settings.postHypoDurationMinutes,
            postHypoLookbackMinutes = settings.postHypoLookbackMinutes,
            dataFresh = dataFresh,
            activeTempTargetMmol = activeTempTarget,
            actionsLast6h = actionsLast6h,
            sensorBlocked = sensorBlocked,
            currentProfileEstimate = currentProfile,
            currentProfileSegment = currentSegment,
            latestTelemetry = latestTelemetry,
            retargetCooldownMinutes = settings.adaptiveControllerRetargetMinutes,
            adaptiveMaxStepMmol = settings.adaptiveControllerMaxStepMmol,
            adaptiveMinTargetMmol = settings.safetyMinTargetMmol,
            adaptiveMaxTargetMmol = settings.safetyMaxTargetMmol
        )

        val decisions = ruleEngine.evaluate(
            context = context,
            config = SafetyPolicyConfig(
                killSwitch = settings.killSwitch,
                maxActionsIn6Hours = resolveEffectiveMaxActions6h(settings),
                minTargetMmol = settings.safetyMinTargetMmol,
                maxTargetMmol = settings.safetyMaxTargetMmol
            ),
            runtimeConfig = runtimeConfig(settings)
        )
        auditLogger.info(
            "automation_cycle_checkpoint",
            mapOf(
                "stage" to "post_rule_evaluate",
                "decisions" to decisions.size
            )
        )

        val effectiveDecisions = mutableListOf<RuleDecision>()
        var adaptiveTriggeredThisCycle = false
        for (decision in decisions) {
            val effectiveDecision = if (decision.state == RuleState.TRIGGERED && decision.actionProposal != null) {
                val cooldownMinutes = ruleCooldownMinutes(decision.ruleId, settings)
                if (cooldownMinutes > 0 && isRuleInCooldown(decision.ruleId, now, cooldownMinutes)) {
                    val cooldownReason = if (decision.ruleId == AdaptiveTargetControllerRule.RULE_ID) {
                        "retarget_cooldown_${cooldownMinutes}m"
                    } else {
                        "rule_cooldown_active:${cooldownMinutes}m"
                    }
                    decision.copy(
                        state = RuleState.BLOCKED,
                        reasons = decision.reasons + cooldownReason,
                        actionProposal = null
                    )
                } else {
                    decision
                }
            } else {
                decision
            }
            effectiveDecisions += effectiveDecision

            db.ruleExecutionDao().insert(
                RuleExecutionEntity(
                    timestamp = now,
                    ruleId = effectiveDecision.ruleId,
                    state = effectiveDecision.state.name,
                    reasonsJson = gson.toJson(effectiveDecision.reasons),
                    actionJson = effectiveDecision.actionProposal?.let { gson.toJson(it) }
                )
            )

            if (effectiveDecision.state == RuleState.TRIGGERED && effectiveDecision.actionProposal != null) {
                if (effectiveDecision.ruleId == AdaptiveTargetControllerRule.RULE_ID &&
                    effectiveDecision.actionProposal.type.equals("temp_target", ignoreCase = true)
                ) {
                    adaptiveTriggeredThisCycle = true
                }
                val idempotencyKey = buildIdempotencyKey(effectiveDecision.ruleId, now, settings)
                val normalizedAction = alignTempTargetToBaseTarget(
                    action = effectiveDecision.actionProposal,
                    forecasts = mergedForecasts,
                    baseTargetMmol = effectiveBaseTarget,
                    sourceRuleId = effectiveDecision.ruleId
                )
                val command = ActionCommand(
                    id = UUID.randomUUID().toString(),
                    type = normalizedAction.type,
                    params = mapOf(
                        "targetMmol" to normalizedAction.targetMmol.toString(),
                        "durationMinutes" to normalizedAction.durationMinutes.toString(),
                        "reason" to normalizedAction.reason
                    ),
                    safetySnapshot = SafetySnapshot(
                        killSwitch = settings.killSwitch,
                        dataFresh = dataFresh,
                        activeTempTargetMmol = activeTempTarget,
                        actionsLast6h = actionsLast6h
                    ),
                    idempotencyKey = idempotencyKey
                )

                when (command.type.lowercase()) {
                    "temp_target" -> actionRepository.submitTempTarget(command)
                    "carbs" -> actionRepository.submitCarbs(command)
                    else -> auditLogger.warn(
                        "automation_action_skipped",
                        mapOf("reason" to "unsupported_action_type", "type" to command.type)
                    )
                }
            }
        }

        if (!adaptiveTriggeredThisCycle) {
            maybeSendAdaptiveKeepaliveTempTarget(
                settings = settings,
                nowTs = now,
                dataFresh = dataFresh,
                sensorBlocked = sensorBlocked,
                activeTempTarget = activeTempTarget,
                actionsLast6h = actionsLast6h,
                forecasts = mergedForecasts,
                baseTargetMmol = effectiveBaseTarget
            )
        }
        auditLogger.info("automation_cycle_checkpoint", mapOf("stage" to "post_actions"))

        auditAdaptiveController(effectiveDecisions, context, settings, mergedForecasts)
        auditLogger.info("automation_cycle_checkpoint", mapOf("stage" to "post_adaptive_audit"))

        auditLogger.info(
            "automation_cycle_completed",
            mapOf(
                "glucosePoints" to glucose.size,
                "therapyEvents" to therapy.size,
                "forecasts" to mergedForecasts.size,
                "decisions" to decisions.size,
                "staleMaxMin" to effectiveStaleMaxMinutes,
                "actionsLimit6h" to resolveEffectiveMaxActions6h(settings)
            )
        )
    }

    private suspend fun <T> runCycleStep(
        name: String,
        block: suspend () -> T
    ): T {
        val startedAt = System.currentTimeMillis()
        auditLogger.info("automation_cycle_step_started", mapOf("step" to name))
        return try {
            val result = block()
            val durationMs = System.currentTimeMillis() - startedAt
            val level = if (durationMs >= AUTOMATION_STEP_SLOW_MS) "warn" else "info"
            val meta = mapOf("step" to name, "durationMs" to durationMs)
            if (level == "warn") {
                auditLogger.warn("automation_cycle_step_completed", meta)
            } else {
                auditLogger.info("automation_cycle_step_completed", meta)
            }
            result
        } catch (error: Throwable) {
            auditLogger.warn(
                "automation_cycle_step_failed",
                mapOf(
                    "step" to name,
                    "durationMs" to (System.currentTimeMillis() - startedAt),
                    "error" to (error.message ?: error::class.simpleName.orEmpty())
                )
            )
            throw error
        }
    }

    private suspend fun maybeRecalculateAnalytics(settings: AppSettings) {
        // Analytics recalculation is intentionally decoupled from reactive runtime cycle.
        // Heavy DB writes can block forecast/action loop; use dedicated analysis workers/manual runs instead.
        auditLogger.info(
            "analytics_recalculate_skipped",
            mapOf(
                "reason" to "decoupled_from_reactive_cycle",
                "trigger" to "automation_cycle"
            )
        )
    }

    private suspend fun resolveRealtimeIsfCrSnapshot(
        settings: AppSettings,
        nowTs: Long
    ): IsfCrRealtimeSnapshot? {
        if (
            isfCrRealtimeRefreshInFlight &&
            isfCrRealtimeRefreshStartedAtTs > 0L &&
            (nowTs - isfCrRealtimeRefreshStartedAtTs) > ISFCR_REALTIME_IN_FLIGHT_STALE_MS
        ) {
            val staleJob = isfCrRealtimeRefreshJob
            val hadActiveJob = staleJob?.isActive == true
            staleJob?.cancel()
            isfCrRealtimeRefreshJob = null
            isfCrRealtimeRefreshInFlight = false
            isfCrRealtimeRefreshStartedAtTs = 0L
            isfCrRealtimeLastFailureTs = nowTs
            auditLogger.warn(
                "isfcr_realtime_refresh_recovered",
                mapOf(
                    "reason" to "stale_in_flight_guard",
                    "hadActiveJob" to hadActiveJob
                )
            )
        }

        val latest = runCatching { isfCrRepository.latestSnapshot() }.getOrNull()
        val ageMs = latest?.let { nowTs - it.ts } ?: Long.MAX_VALUE
        if (latest != null && ageMs in 0..ISFCR_SNAPSHOT_FRESHNESS_MS) {
            return latest
        }

        val inFailureBackoff = isfCrRealtimeLastFailureTs > 0L &&
            (nowTs - isfCrRealtimeLastFailureTs) < ISFCR_REALTIME_RETRY_BACKOFF_MS

        if (!isfCrRealtimeRefreshInFlight && !inFailureBackoff) {
            val refreshReason = when {
                latest == null -> "missing_snapshot"
                ageMs >= ISFCR_SYNC_REFRESH_STALE_MS -> "stale_snapshot"
                else -> "background_refresh"
            }
            scheduleRealtimeIsfCrRefresh(settings = settings, nowTs = nowTs, reason = refreshReason)
        } else if (inFailureBackoff && !isfCrRealtimeRefreshInFlight) {
            auditLogger.info(
                "isfcr_realtime_refresh_skipped",
                mapOf("reason" to "backoff")
            )
        } else {
            auditLogger.info(
                "isfcr_realtime_refresh_skipped",
                mapOf("reason" to "in_flight")
            )
        }

        if (latest == null) {
            auditLogger.warn(
                "isfcr_realtime_unavailable",
                mapOf("reason" to "snapshot_missing_or_stale")
            )
        } else {
            auditLogger.warn(
                "isfcr_realtime_unavailable",
                mapOf(
                    "reason" to "snapshot_stale",
                    "snapshotTs" to latest.ts,
                    "snapshotAgeMs" to ageMs,
                    "freshnessMs" to ISFCR_SNAPSHOT_FRESHNESS_MS
                )
            )
        }
        return null
    }

    private suspend fun scheduleRealtimeIsfCrRefresh(
        settings: AppSettings,
        nowTs: Long,
        reason: String
    ) {
        if (isfCrRealtimeRefreshInFlight) return
        isfCrRealtimeRefreshInFlight = true
        isfCrRealtimeRefreshStartedAtTs = nowTs
        auditLogger.info("isfcr_realtime_refresh_scheduled", mapOf("reason" to reason))

        var refreshJob: Job? = null
        refreshJob = isfCrRealtimeScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                runCatching {
                    withTimeout(ISFCR_REALTIME_TIMEOUT_MS) {
                        isfCrRepository.computeRealtimeSnapshot(
                            settings = settings,
                            nowTs = System.currentTimeMillis()
                        )
                    }
                }.onSuccess { snapshot ->
                    isfCrRealtimeLastFailureTs = 0L
                    auditLogger.info(
                        "isfcr_realtime_refresh_completed",
                        mapOf(
                            "durationMs" to (System.currentTimeMillis() - startedAt),
                            "snapshotTs" to snapshot.ts
                        )
                    )
                }.onFailure { error ->
                    isfCrRealtimeLastFailureTs = System.currentTimeMillis()
                    auditLogger.warn(
                        "isfcr_realtime_refresh_failed",
                        mapOf(
                            "timeout" to (error is TimeoutCancellationException),
                            "durationMs" to (System.currentTimeMillis() - startedAt),
                            "reason" to (error.message ?: error::class.simpleName.orEmpty())
                        )
                    )
                }
            } finally {
                isfCrRealtimeRefreshInFlight = false
                isfCrRealtimeRefreshStartedAtTs = 0L
                if (isfCrRealtimeRefreshJob == refreshJob) {
                    isfCrRealtimeRefreshJob = null
                }
            }
        }
        isfCrRealtimeRefreshJob = refreshJob
        refreshJob.invokeOnCompletion {
            isfCrRealtimeRefreshInFlight = false
            isfCrRealtimeRefreshStartedAtTs = 0L
            if (isfCrRealtimeRefreshJob == refreshJob) {
                isfCrRealtimeRefreshJob = null
            }
        }
    }

    private fun alignTempTargetToBaseTarget(
        action: ActionProposal,
        forecasts: List<Forecast>,
        baseTargetMmol: Double,
        sourceRuleId: String? = null
    ): ActionProposal {
        if (!action.type.equals("temp_target", ignoreCase = true)) return action
        val boundedBase = baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val boundedProposed = action.targetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        if (shouldSkipBaseAlignmentStatic(sourceRuleId = sourceRuleId, actionReason = action.reason)) {
            return action.copy(targetMmol = boundedProposed)
        }
        val forecast60 = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol
            ?: return action.copy(targetMmol = boundedProposed)

        val driftVsBase = forecast60 - boundedBase
        if (abs(driftVsBase) < 0.15) {
            return action.copy(targetMmol = boundedProposed)
        }

        // Move temp target so 1h trajectory drifts toward base target.
        val correction = (-driftVsBase * ALIGN_GAIN).coerceIn(-MAX_ALIGN_STEP_MMOL, MAX_ALIGN_STEP_MMOL)
        val aligned = roundToStep((boundedProposed + correction).coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL), 0.05)
        val reason = if (action.reason.contains("base_align_60m")) action.reason else "${action.reason}|base_align_60m"
        return action.copy(targetMmol = aligned, reason = reason)
    }

    private fun roundToStep(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        val scaled = value / step
        return floor(scaled + 0.5) * step
    }

    private fun ensureForecast30(forecasts: List<Forecast>): List<Forecast> {
        val has30 = forecasts.any { it.horizonMinutes == 30 }
        if (has30) return normalizeForecastSet(forecasts)

        val f5 = forecasts.firstOrNull { it.horizonMinutes == 5 }?.valueMmol
        val f60 = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol
        if (f5 == null || f60 == null) return forecasts
        val baseTs = forecasts.maxOfOrNull { it.ts } ?: System.currentTimeMillis()
        val f30 = 0.55 * f5 + 0.45 * f60
        val synthetic = Forecast(
            ts = baseTs + 30 * 60_000L,
            horizonMinutes = 30,
            valueMmol = f30,
            ciLow = (f30 - 0.8).coerceAtLeast(2.2),
            ciHigh = f30 + 0.8,
                modelVersion = "local-interpolated-30m-v1"
        )
        return normalizeForecastSet(forecasts + synthetic)
    }

    private fun normalizeForecastSet(forecasts: List<Forecast>): List<Forecast> {
        return normalizeForecastSetStatic(forecasts)
    }

    private suspend fun collectForecastCalibrationErrors(nowTs: Long): List<ForecastCalibrationPoint> {
        val forecastHistory = db.forecastDao().latest(CALIBRATION_FORECAST_LIMIT)
        if (forecastHistory.isEmpty()) return emptyList()
        val glucoseHistory = db.glucoseDao().latest(CALIBRATION_GLUCOSE_LIMIT).sortedBy { it.timestamp }
        if (glucoseHistory.isEmpty()) return emptyList()

        return forecastHistory.asSequence()
            .filter { row ->
                val age = nowTs - row.timestamp
                age in CALIBRATION_MIN_AGE_MS..CALIBRATION_LOOKBACK_MS
            }
            .mapNotNull { row ->
                val nearest = nearestGlucoseAt(
                    targetTs = row.timestamp,
                    sorted = glucoseHistory,
                    toleranceMs = CALIBRATION_MATCH_TOLERANCE_MS
                ) ?: return@mapNotNull null
                ForecastCalibrationPoint(
                    horizonMinutes = row.horizonMinutes,
                    errorMmol = nearest.mmol - row.valueMmol,
                    ageMs = nowTs - row.timestamp,
                    predictedMmol = row.valueMmol
                )
            }
            .toList()
    }

    private fun nearestGlucoseAt(
        targetTs: Long,
        sorted: List<GlucoseSampleEntity>,
        toleranceMs: Long
    ): GlucoseSampleEntity? {
        if (sorted.isEmpty()) return null
        var lo = 0
        var hi = sorted.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val midTs = sorted[mid].timestamp
            when {
                midTs < targetTs -> lo = mid + 1
                midTs > targetTs -> hi = mid - 1
                else -> return sorted[mid]
            }
        }

        val right = sorted.getOrNull(lo)
        val left = sorted.getOrNull(lo - 1)
        val rightDiff = right?.let { abs(it.timestamp - targetTs) } ?: Long.MAX_VALUE
        val leftDiff = left?.let { abs(it.timestamp - targetTs) } ?: Long.MAX_VALUE
        val best = if (rightDiff < leftDiff) right else left
        return best?.takeIf { abs(it.timestamp - targetTs) <= toleranceMs }
    }

    private fun applyRecentForecastCalibrationBias(
        forecasts: List<Forecast>,
        history: List<ForecastCalibrationPoint>,
        aiTuning: Map<Int, CalibrationAiTuning>
    ): List<Forecast> {
        return applyRecentForecastCalibrationBiasStatic(
            forecasts = forecasts,
            history = history,
            aiTuning = aiTuning
        )
    }

    private fun buildCalibrationAuditMeta(
        source: List<Forecast>,
        adjusted: List<Forecast>,
        aiTuning: Map<Int, CalibrationAiTuning>
    ): Map<String, Any> {
        val byH = source.associateBy { it.horizonMinutes }
        val shifts = adjusted.associate { forecast ->
            val src = byH[forecast.horizonMinutes]
            val delta = if (src == null) 0.0 else forecast.valueMmol - src.valueMmol
            "h${forecast.horizonMinutes}" to roundToStep(delta, 0.001)
        }
        val tuningMeta = aiTuning.entries
            .sortedBy { it.key }
            .associate { (horizon, tuning) ->
                "h${horizon}_tuning" to "g=${roundToStep(tuning.gainScale, 0.001)},up=${roundToStep(tuning.maxUpScale, 0.001)},down=${roundToStep(tuning.maxDownScale, 0.001)}"
            }
        return shifts + tuningMeta + mapOf("model" to "recent_calibration_v1")
    }

    private fun resolveAiCalibrationTuning(
        latestTelemetry: Map<String, Double?>,
        nowTs: Long
    ): Map<Int, CalibrationAiTuning> {
        return resolveAiCalibrationTuningStatic(
            latestTelemetry = latestTelemetry,
            nowTs = nowTs
        )
    }

    private fun applyContextFactorForecastBias(
        forecasts: List<Forecast>,
        telemetry: Map<String, Double?>,
        latestGlucoseMmol: Double,
        pattern: io.aaps.copilot.domain.model.PatternWindow?
    ): List<Forecast> {
        return applyContextFactorForecastBiasStatic(
            forecasts = forecasts,
            telemetry = telemetry,
            latestGlucoseMmol = latestGlucoseMmol,
            pattern = pattern
        )
    }

    private fun applyCobIobForecastBias(
        forecasts: List<Forecast>,
        cobGrams: Double?,
        iobUnits: Double?,
        latestGlucoseMmol: Double? = null,
        uamActive: Boolean? = null
    ): List<Forecast> {
        return applyCobIobForecastBiasStatic(
            forecasts = forecasts,
            cobGrams = cobGrams,
            iobUnits = iobUnits,
            latestGlucoseMmol = latestGlucoseMmol,
            uamActive = uamActive
        )
    }

    private fun resolveEffectiveBaseTarget(
        configuredBaseTargetMmol: Double,
        telemetry: Map<String, Double?>
    ): Double {
        val base = configuredBaseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val cob = telemetry["cob_grams"]?.coerceIn(0.0, 400.0) ?: return base
        if (cob < COB_FORCE_BASE_THRESHOLD_G) return base
        return COB_FORCE_BASE_TARGET_MMOL.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
    }

    private suspend fun resolveLatestTelemetry(
        nowTs: Long,
        settings: AppSettings
    ): Map<String, Double?> {
        val baseRows = db.telemetryDao().since(nowTs - TELEMETRY_LOOKBACK_MS)
        val reportRows = db.telemetryDao().since(nowTs - TELEMETRY_REPORT_LOOKBACK_MS)
            .filter { row -> isReportTelemetryKey(row.key) }
        val rows = (baseRows + reportRows)
            .distinctBy { row -> "${row.source}:${row.key}:${row.timestamp}" }
        if (rows.isEmpty()) return emptyMap()
        val todayStart = Instant.ofEpochMilli(nowTs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val usableRows = rows
            .filter { it.valueDouble != null && telemetryValueUsable(it.key, it.valueDouble) }
        val latestTimestampByKey = usableRows
            .groupBy { it.key }
            .mapValues { (_, values) -> values.maxOfOrNull { it.timestamp } ?: 0L }
        val latestByKey = usableRows
            .groupBy { it.key }
            .mapValues { (key, values) ->
                if (key in CUMULATIVE_ACTIVITY_KEYS) {
                    val dayValues = values.filter { it.timestamp >= todayStart }
                    val sourceValues = if (dayValues.isNotEmpty()) dayValues else values
                    sourceValues.maxWithOrNull(
                        compareBy<TelemetrySampleEntity> { it.valueDouble ?: Double.NEGATIVE_INFINITY }
                            .thenBy { it.timestamp }
                    )?.valueDouble
                } else {
                    values.maxByOrNull { it.timestamp }?.valueDouble
                }
            }
            .toMutableMap()

        fun alias(
            targetKey: String,
            preferredKeys: List<String>,
            tokenAliases: List<String> = emptyList()
        ) {
            if (latestByKey[targetKey] != null) return

            val preferred = preferredKeys.asSequence()
                .mapNotNull { key ->
                    val value = latestByKey[key] ?: return@mapNotNull null
                    val ts = latestTimestampByKey[key] ?: Long.MIN_VALUE
                    key to (ts to value)
                }
                .maxByOrNull { it.second.first }
            if (preferred != null) {
                latestByKey[targetKey] = preferred.second.second
                return
            }

            if (tokenAliases.isEmpty()) return
            val candidate = latestByKey.keys.asSequence()
                .filter { key ->
                    latestByKey[key] != null &&
                        tokenAliases.any { alias -> telemetryKeyContainsAlias(key, alias) } &&
                        !key.endsWith("_flag") &&
                        !key.endsWith("_available") &&
                        !key.endsWith("_used") &&
                        !key.endsWith("_merged") &&
                        !key.endsWith("_blocked")
                }
                .maxByOrNull { key -> latestTimestampByKey[key] ?: Long.MIN_VALUE }
            latestByKey[targetKey] = candidate?.let { latestByKey[it] }
        }

        alias(
            targetKey = "iob_units",
            preferredKeys = listOf(
                "iob_units",
                "iob_effective_units",
                "iob_real_units",
                "iob_external_raw_units",
                "raw_iob"
            ),
            tokenAliases = listOf("insulinonboard")
        )
        alias(
            targetKey = "cob_grams",
            preferredKeys = listOf(
                "cob_grams",
                "cob_effective_grams",
                "cob_external_raw_grams",
                "raw_cob"
            ),
            tokenAliases = listOf("carbsonboard")
        )
        alias(
            targetKey = "activity_ratio",
            preferredKeys = listOf("activity_ratio", "raw_activityratio"),
            tokenAliases = listOf("activityratio", "sensitivityratio")
        )
        alias(
            targetKey = "uam_value",
            preferredKeys = listOf("uam_value", "uam_inferred_flag", "uam_calculated_flag", "uam_flag"),
            tokenAliases = listOf("enable_uam", "uam_detected", "unannounced_meal", "has_uam", "is_uam")
        )
        var usedRiskTextFallback = false
        if (latestByKey["daily_report_isfcr_quality_risk_level"] == null) {
            val latestRiskText = rows
                .asSequence()
                .filter { it.key == "daily_report_isfcr_quality_risk" }
                .maxByOrNull { it.timestamp }
                ?.valueText
            parseIsfCrQualityRiskLevelFromTextStatic(latestRiskText)?.let { level ->
                latestByKey["daily_report_isfcr_quality_risk_level"] = level.toDouble()
                usedRiskTextFallback = true
            }
        }
        latestByKey["daily_report_isfcr_quality_risk_level_fallback_used"] =
            if (usedRiskTextFallback) 1.0 else 0.0

        val runtimeFreshnessMs = settings.staleDataMaxMinutes.coerceIn(5, 60) * 60_000L
        val staleRuntimeKeys = latestByKey.keys.filter { key ->
            requiresRuntimeFreshness(key) &&
                !isReportTelemetryKey(key) &&
                ((latestTimestampByKey[key]?.let { ts -> nowTs - ts > runtimeFreshnessMs }) == true)
        }
        staleRuntimeKeys.forEach { key -> latestByKey[key] = null }
        if (staleRuntimeKeys.isNotEmpty()) {
            auditLogger.info(
                "telemetry_runtime_freshness_filtered",
                mapOf(
                    "runtimeFreshnessMs" to runtimeFreshnessMs,
                    "filteredCount" to staleRuntimeKeys.size,
                    "keysSample" to staleRuntimeKeys.sorted().take(12)
                )
            )
        }
        return latestByKey
    }

    private fun requiresRuntimeFreshness(key: String): Boolean {
        return key in setOf(
            "dia_hours",
            "activity_ratio",
            "steps_count",
            "uam_value",
            "uam_calculated_flag",
            "uam_inferred_flag",
            "temp_target_mmol",
            "temp_target_high_mmol"
        ) || key.startsWith("cob_") ||
            key.startsWith("iob_") ||
            key.startsWith("sensor_quality_") ||
            key.startsWith("isf_factor_") ||
            key.startsWith("isf_realtime_")
    }

    private fun isReportTelemetryKey(key: String): Boolean {
        return key.startsWith("daily_report_") ||
            key.startsWith("rolling_report_") ||
            key.startsWith(REAL_PROFILE_KEY_PREFIX)
    }

    private fun telemetryValueUsable(key: String, value: Double?): Boolean {
        if (value == null) return false
        val normalizedKey = normalizeTelemetryKey(key)
        if (normalizedKey == "uam_value") {
            return value in 0.0..1.5
        }
        return true
    }

    private fun telemetryKeyContainsAlias(key: String, alias: String): Boolean {
        val normalizedAlias = normalizeTelemetryKey(alias)
        if (normalizedAlias.isBlank()) return false
        val normalizedKey = normalizeTelemetryKey(key)
        if (normalizedKey == normalizedAlias || normalizedKey.endsWith("_$normalizedAlias")) return true
        return normalizedKey.split('_').any { it == normalizedAlias }
    }

    private fun normalizeTelemetryKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun configurePredictionEngine(
        settings: AppSettings,
        realtimeIsfCr: IsfCrRealtimeSnapshot? = null,
        runtimeGate: IsfCrRuntimeGate = resolveIsfCrRuntimeGateStatic(
            snapshot = realtimeIsfCr,
            confidenceThreshold = settings.isfCrConfidenceThreshold
        ),
        overrideBlendWeight: Double? = resolveIsfCrOverrideBlendWeightStatic(
            snapshot = realtimeIsfCr,
            runtimeGate = runtimeGate,
            confidenceThreshold = settings.isfCrConfidenceThreshold
        ),
        runtimeTelemetry: Map<String, Double?> = emptyMap()
    ) {
        val engine = predictionEngine as? HybridPredictionEngine ?: return
        val profileId = InsulinActionProfileId.fromRaw(settings.insulinProfileId)
        engine.setInsulinProfile(profileId)
        engine.setCarbSafetyLimits(
            maxAgeMinutes = settings.carbAbsorptionMaxAgeMinutes,
            maxGrams = settings.carbComputationMaxGrams
        )
        val diaHours = runtimeTelemetry["dia_hours"]
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.5, 24.0)
        engine.setInsulinDurationHours(diaHours)
        val effectiveBlendWeight = overrideBlendWeight?.coerceIn(0.0, 1.0)
        val applyOverride = (effectiveBlendWeight ?: 0.0) > 0.0
        val overrideIsf = if (applyOverride) realtimeIsfCr?.isfEff else null
        val overrideCr = if (applyOverride) realtimeIsfCr?.crEff else null
        val overrideConfidence = if (applyOverride) realtimeIsfCr?.confidence else null
        engine.setSensitivityOverride(
            isfMmolPerUnit = overrideIsf,
            crGramPerUnit = overrideCr,
            confidence = overrideConfidence,
            source = realtimeIsfCr?.mode?.name ?: runtimeGate.reason,
            minConfidenceRequired = settings.isfCrConfidenceThreshold,
            blendWeight = effectiveBlendWeight ?: 1.0
        )
    }

    private fun resolveRuntimeCobIobInputs(
        nowTs: Long,
        glucose: List<GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        telemetry: Map<String, Double?>,
        settings: AppSettings
    ): RuntimeCobIobInputs {
        val carbMax = settings.carbComputationMaxGrams.coerceIn(20.0, 60.0)
        val telemetryCob = telemetry["cob_grams"]?.takeIf { it.isFinite() }?.coerceIn(0.0, carbMax)
        val telemetryIob = telemetry["iob_units"]?.takeIf { it.isFinite() }?.coerceIn(0.0, 30.0)
        val hasRecentCarbEvents = hasRecentCarbEvents(
            nowTs = nowTs,
            therapy = therapy,
            cutoffMinutes = settings.carbAbsorptionMaxAgeMinutes.coerceIn(60, 180)
        )
        val localEstimate = estimateLocalCobIob(
            nowTs = nowTs,
            glucose = glucose,
            therapy = therapy,
            settings = settings
        )
        val localCob = localEstimate.cobGrams
        val localIob = localEstimate.iobUnits
        val mergedCob = when {
            telemetryCob != null && localCob > 0.0 -> {
                val telemetryWeight = cobTelemetryWeight(telemetryCob = telemetryCob, localCob = localCob)
                (telemetryCob * telemetryWeight + localCob * (1.0 - telemetryWeight)).coerceIn(0.0, carbMax)
            }
            // Trust external COB even when local carb events are missing.
            // This keeps runtime/input parity with AAPS/xDrip and avoids false persistent zero COB.
            telemetryCob != null && !hasRecentCarbEvents -> telemetryCob.coerceIn(0.0, carbMax)
            telemetryCob != null -> telemetryCob
            else -> localCob.coerceIn(0.0, carbMax)
        }
        // Prefer locally computed "real IOB" from the insulin activity profile adapted by observed onset.
        // If local bolus evidence is missing, safely fall back to telemetry IOB to avoid persistent zero real IOB.
        val realIobUnits = when {
            localEstimate.explicitInsulinEvents > 0 && localIob > 0.0 && telemetryIob != null ->
                (localIob * REAL_IOB_LOCAL_CONFIDENT_WEIGHT + telemetryIob * (1.0 - REAL_IOB_LOCAL_CONFIDENT_WEIGHT))
                    .coerceIn(0.0, 30.0)
            localIob > 0.0 -> localIob.coerceIn(0.0, 30.0)
            telemetryIob != null -> telemetryIob.coerceIn(0.0, 30.0)
            else -> 0.0
        }
        val mergedIob = when {
            localIob > 0.0 && telemetryIob != null ->
                (localIob * REAL_IOB_WEIGHT + telemetryIob * (1.0 - REAL_IOB_WEIGHT)).coerceIn(0.0, 30.0)
            localIob > 0.0 -> localIob.coerceIn(0.0, 30.0)
            telemetryIob != null -> telemetryIob
            else -> 0.0
        }
        val usedLocalFallback = (telemetryCob == null && localCob > 0.0) || (telemetryIob == null && localIob > 0.0)
        val mergedWithTelemetry = telemetryCob != null && telemetryIob != null && (localCob > 0.0 || localIob > 0.0)
        return RuntimeCobIobInputs(
            cobGrams = mergedCob,
            iobUnits = mergedIob,
            realIobUnits = realIobUnits,
            localCobGrams = localCob,
            localIobUnits = localIob,
            realOnsetMinutes = localEstimate.realOnsetMinutes,
            baseOnsetMinutes = localEstimate.baseOnsetMinutes,
            onsetSampleCount = localEstimate.onsetSampleCount,
            usedLocalFallback = usedLocalFallback,
            mergedWithTelemetry = mergedWithTelemetry
        )
    }

    private fun buildForecastFactorCoverageMeta(
        latestTelemetry: Map<String, Double?>,
        realtimeIsfCrSnapshot: IsfCrRealtimeSnapshot?,
        runtimeGate: IsfCrRuntimeGate,
        runtimeCobIob: RuntimeCobIobInputs,
        currentPattern: io.aaps.copilot.domain.model.PatternWindow?,
        calibrationSampleCount: Int,
        calibrationApplied: Boolean,
        contextBiasApplied: Boolean,
        cobIobBiasApplied: Boolean,
        calculatedUam: CalculatedUamSnapshot,
        inferredUam: UamInferenceCycleResult?,
        insulinTherapyAvailable: Boolean
    ): Map<String, Any> {
        fun hasValue(key: String): Boolean = latestTelemetry[key]?.isFinite() == true
        fun boolFlag(value: Boolean): Double = if (value) 1.0 else 0.0

        val isfConfidence = latestTelemetry["isf_realtime_confidence"] ?: 0.0
        val isfApplied = runtimeGate.applyToRuntime && isfConfidence > 0.0
        val hasIsfRealtime = realtimeIsfCrSnapshot != null
        val hasDia = hasValue("dia_hours")
        val hasCob = (latestTelemetry["cob_grams"] ?: 0.0) > 0.0
        val hasIob = (latestTelemetry["iob_units"] ?: 0.0) > 0.0
        val hasSensor = hasValue("sensor_quality_score")
        val hasActivity = hasValue("activity_ratio") ||
            hasValue("steps_count") ||
            hasValue("isf_factor_activity_factor")
        val hasSetContext = hasValue("isf_factor_set_age_hours") ||
            hasValue("isf_factor_set_factor")
        val hasHormoneContext = listOf(
            "manual_hormone_tag",
            "manual_steroid_tag",
            "isf_factor_hormone_factor",
            "isf_factor_steroid_factor"
        ).any(::hasValue)
        val hasStressContext = listOf(
            "manual_stress_tag",
            "manual_illness_tag",
            "latent_stress",
            "isf_factor_stress_factor",
            "isf_factor_context_ambiguity"
        ).any(::hasValue)
        val hasDawnContext = hasValue("manual_dawn_tag") ||
            hasValue("isf_factor_dawn_factor")
        val hasPattern = currentPattern != null
        val hasUam = resolveUamActiveTelemetry(latestTelemetry) ||
            calculatedUam.flag >= 0.5 ||
            ((inferredUam?.activeFlag ?: 0.0) >= 0.5)
        val historyReady = calibrationSampleCount >= 8

        val coverageSignals = listOf(
            hasIsfRealtime,
            hasDia,
            hasCob || runtimeCobIob.localCobGrams > 0.0,
            hasIob || runtimeCobIob.localIobUnits > 0.0,
            hasUam,
            hasSetContext,
            hasSensor,
            hasActivity,
            hasHormoneContext,
            hasStressContext,
            hasDawnContext,
            hasPattern,
            historyReady
        )
        val coveragePct = (coverageSignals.count { it } * 100.0 / coverageSignals.size).coerceIn(0.0, 100.0)

        return mapOf(
            "coveragePct" to coveragePct,
            "historyCalibrationSamples" to calibrationSampleCount,
            "historyCalibrationReady" to boolFlag(historyReady),
            "isfRealtimeAvailable" to boolFlag(hasIsfRealtime),
            "isfRealtimeApplied" to boolFlag(isfApplied),
            "isfRealtimeMode" to (realtimeIsfCrSnapshot?.mode?.name ?: "NONE"),
            "isfRealtimeConfidence" to isfConfidence,
            "diaAvailable" to boolFlag(hasDia),
            "cobAvailable" to boolFlag(hasCob),
            "iobAvailable" to boolFlag(hasIob),
            "uamDetected" to boolFlag(hasUam),
            "sensorQualityAvailable" to boolFlag(hasSensor),
            "activityAvailable" to boolFlag(hasActivity),
            "setContextAvailable" to boolFlag(hasSetContext),
            "hormoneContextAvailable" to boolFlag(hasHormoneContext),
            "stressContextAvailable" to boolFlag(hasStressContext),
            "dawnContextAvailable" to boolFlag(hasDawnContext),
            "patternContextAvailable" to boolFlag(hasPattern),
            "localCobIobFallbackUsed" to boolFlag(runtimeCobIob.usedLocalFallback),
            "localCobIobMergedWithTelemetry" to boolFlag(runtimeCobIob.mergedWithTelemetry),
            "insulinTherapyAvailable" to boolFlag(insulinTherapyAvailable),
            "forecastCalibrationApplied" to boolFlag(calibrationApplied),
            "forecastContextBiasApplied" to boolFlag(contextBiasApplied),
            "forecastCobIobBiasApplied" to boolFlag(cobIobBiasApplied)
        )
    }

    private fun estimateLocalCobIob(
        nowTs: Long,
        glucose: List<GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        settings: AppSettings
    ): LocalCobIobEstimate {
        val carbCutoffMinutes = settings.carbAbsorptionMaxAgeMinutes.coerceIn(60, 180).toDouble()
        val carbMaxGrams = settings.carbComputationMaxGrams.coerceIn(20.0, 60.0)
        val profile = InsulinActionProfiles.profile(InsulinActionProfileId.fromRaw(settings.insulinProfileId))
        val baseOnsetMinutes = profileOnsetMinutes(profile)
        val realOnsetEstimate = estimateRealInsulinOnsetMinutes(
            nowTs = nowTs,
            glucose = glucose,
            therapy = therapy
        )
        val realOnsetMinutes = (realOnsetEstimate?.first ?: baseOnsetMinutes)
            .coerceIn(INSULIN_ONSET_MIN_MINUTES, INSULIN_ONSET_MAX_MINUTES)
        val onsetSampleCount = realOnsetEstimate?.second ?: 0
        val recentEvents = therapy.asSequence()
            .filter { event -> nowTs - event.ts <= LOCAL_COB_IOB_LOOKBACK_MS }
            .toList()
        var cob = 0.0
        var iob = 0.0
        var explicitInsulinEvents = 0
        recentEvents.forEach { event ->
            val ageMin = ((nowTs - event.ts).coerceAtLeast(0L)) / 60_000.0
            val insulinUnits = extractInsulinUnits(event)
            if (insulinUnits != null) {
                explicitInsulinEvents += 1
                val delivered = adjustedInsulinCumulativeAt(
                    profile = profile,
                    ageMinutes = ageMin,
                    baseOnsetMinutes = baseOnsetMinutes,
                    realOnsetMinutes = realOnsetMinutes
                )
                iob += insulinUnits * (1.0 - delivered)
            }
            val carbsGramsRaw = payloadDouble(event, "grams", "carbs", "enteredCarbs", "mealCarbs")
                ?.takeIf { it in 0.5..400.0 }
            if (carbsGramsRaw != null && ageMin <= carbCutoffMinutes) {
                val carbsGrams = carbsGramsRaw.coerceAtMost(carbMaxGrams)
                val carbType = CarbAbsorptionProfiles.classifyCarbEvent(
                    event = event,
                    glucose = glucose,
                    nowTs = nowTs
                ).type
                val absorbed = CarbAbsorptionProfiles.cumulative(type = carbType, ageMinutes = ageMin).coerceIn(0.0, 1.0)
                cob += carbsGrams * (1.0 - absorbed)
            }
        }
        return LocalCobIobEstimate(
            cobGrams = cob.coerceIn(0.0, carbMaxGrams),
            iobUnits = iob.coerceAtLeast(0.0),
            explicitInsulinEvents = explicitInsulinEvents,
            realOnsetMinutes = realOnsetMinutes,
            baseOnsetMinutes = baseOnsetMinutes,
            onsetSampleCount = onsetSampleCount
        )
    }

    private fun adjustedInsulinCumulativeAt(
        profile: InsulinActionProfile,
        ageMinutes: Double,
        baseOnsetMinutes: Double,
        realOnsetMinutes: Double
    ): Double {
        val shiftMinutes = (realOnsetMinutes - baseOnsetMinutes).coerceIn(-30.0, 75.0)
        val adjustedAge = (ageMinutes - shiftMinutes).coerceAtLeast(0.0)
        return profile.cumulativeAt(adjustedAge).coerceIn(0.0, 1.0)
    }

    private fun profileOnsetMinutes(profile: InsulinActionProfile): Double {
        val points = profile.points.sortedBy { it.minute }
        if (points.isEmpty()) return 30.0
        if (points.first().cumulative >= INSULIN_ONSET_FRACTION_THRESHOLD) {
            return points.first().minute
        }
        for (index in 1 until points.size) {
            val left = points[index - 1]
            val right = points[index]
            if (right.cumulative >= INSULIN_ONSET_FRACTION_THRESHOLD) {
                val span = (right.cumulative - left.cumulative).coerceAtLeast(1e-6)
                val ratio = ((INSULIN_ONSET_FRACTION_THRESHOLD - left.cumulative) / span).coerceIn(0.0, 1.0)
                return left.minute + ratio * (right.minute - left.minute)
            }
        }
        return points.last().minute
    }

    private fun estimateRealInsulinOnsetMinutes(
        nowTs: Long,
        glucose: List<GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>
    ): Pair<Double, Int>? {
        val sortedGlucose = glucose.sortedBy { it.ts }
        if (sortedGlucose.size < 8) return null
        val candidates = mutableListOf<Double>()
        therapy.asSequence()
            .filter { event ->
                (nowTs - event.ts) in 0..LOCAL_COB_IOB_LOOKBACK_MS &&
                    (extractInsulinUnits(event) ?: 0.0) >= 0.5
            }
            .forEach { bolusEvent ->
                val hasNearbyMeal = therapy.any { other ->
                    val grams = payloadDouble(other, "grams", "carbs", "enteredCarbs", "mealCarbs") ?: 0.0
                    grams >= 5.0 && kotlin.math.abs(other.ts - bolusEvent.ts) <= INSULIN_ONSET_MEAL_EXCLUSION_MS
                }
                if (hasNearbyMeal) return@forEach
                val baseline = nearestGlucoseValueMmol(
                    sortedGlucose = sortedGlucose,
                    targetTs = bolusEvent.ts,
                    toleranceMs = INSULIN_ONSET_BASELINE_TOLERANCE_MS
                ) ?: return@forEach
                val window = sortedGlucose.filter { point ->
                    point.ts in (bolusEvent.ts + INSULIN_ONSET_SEARCH_START_MS)..(bolusEvent.ts + INSULIN_ONSET_SEARCH_END_MS)
                }
                if (window.size < 3) return@forEach

                for (index in 1 until window.lastIndex) {
                    val prev = window[index - 1]
                    val current = window[index]
                    val next = window[index + 1]
                    val dtCurrent = ((current.ts - prev.ts).toDouble() / 60_000.0).coerceAtLeast(1.0)
                    val dtNext = ((next.ts - current.ts).toDouble() / 60_000.0).coerceAtLeast(1.0)
                    val deltaCurrent5 = (current.valueMmol - prev.valueMmol) / (dtCurrent / 5.0)
                    val deltaNext5 = (next.valueMmol - current.valueMmol) / (dtNext / 5.0)
                    val dropCurrent = baseline - current.valueMmol
                    val dropNext = baseline - next.valueMmol
                    val ageMinutes = (current.ts - bolusEvent.ts) / 60_000.0
                    if (
                        ageMinutes in INSULIN_ONSET_MIN_MINUTES..INSULIN_ONSET_MAX_MINUTES &&
                        dropCurrent >= INSULIN_ONSET_DROP_THRESHOLD_MMOL &&
                        dropNext >= INSULIN_ONSET_DROP_THRESHOLD_MMOL &&
                        deltaCurrent5 <= INSULIN_ONSET_DELTA5_THRESHOLD_MMOL &&
                        deltaNext5 <= INSULIN_ONSET_DELTA5_THRESHOLD_MMOL
                    ) {
                        candidates += ageMinutes
                        break
                    }
                }
            }

        if (candidates.isEmpty()) return null
        val median = median(candidates).coerceIn(INSULIN_ONSET_MIN_MINUTES, INSULIN_ONSET_MAX_MINUTES)
        return median to candidates.size
    }

    private fun nearestGlucoseValueMmol(
        sortedGlucose: List<GlucosePoint>,
        targetTs: Long,
        toleranceMs: Long
    ): Double? {
        if (sortedGlucose.isEmpty()) return null
        var best: GlucosePoint? = null
        var bestDiff = Long.MAX_VALUE
        sortedGlucose.forEach { point ->
            val diff = kotlin.math.abs(point.ts - targetTs)
            if (diff < bestDiff) {
                bestDiff = diff
                best = point
            }
        }
        return best?.takeIf { bestDiff <= toleranceMs }?.valueMmol
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun cobTelemetryWeight(telemetryCob: Double, localCob: Double): Double {
        val denominator = maxOf(5.0, maxOf(telemetryCob, localCob))
        val divergenceRatio = abs(telemetryCob - localCob) / denominator
        return when {
            divergenceRatio >= 1.5 -> 0.20
            divergenceRatio >= 0.8 -> 0.35
            divergenceRatio >= 0.4 -> 0.50
            else -> COB_IOB_TELEMETRY_WEIGHT
        }
    }

    private fun hasRecentCarbEvents(
        nowTs: Long,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        cutoffMinutes: Int
    ): Boolean {
        val cutoffMs = cutoffMinutes.coerceIn(60, 180) * 60_000L
        return therapy.any { event ->
            val ageMs = nowTs - event.ts
            ageMs in 0..cutoffMs &&
                payloadDouble(event, "grams", "carbs", "enteredCarbs", "mealCarbs")
                    ?.let { it in 0.5..400.0 } == true
        }
    }

    private fun hasInsulinTherapyEvidence(
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>
    ): Boolean {
        return therapy.any { event ->
            extractInsulinUnits(event)?.let { it > 0.0 } == true ||
                event.type.equals("meal_bolus", ignoreCase = true) ||
                event.type.equals("correction_bolus", ignoreCase = true) ||
                event.type.equals("bolus", ignoreCase = true) ||
                event.type.equals("insulin", ignoreCase = true)
        }
    }

    private fun resolveUamActiveTelemetry(latestTelemetry: Map<String, Double?>): Boolean {
        return ((latestTelemetry["uam_value"] ?: 0.0) >= 0.5) ||
            ((latestTelemetry["uam_inferred_flag"] ?: 0.0) >= 0.5) ||
            ((latestTelemetry["uam_calculated_flag"] ?: 0.0) >= 0.5) ||
            ((latestTelemetry["uam_flag"] ?: 0.0) >= 0.5)
    }

    private fun payloadDouble(
        event: io.aaps.copilot.domain.model.TherapyEvent,
        vararg keys: String
    ): Double? {
        val normalizedPayload = event.payload.entries.associate { normalizeTelemetryKey(it.key) to it.value }
        return keys.firstNotNullOfOrNull { key ->
            normalizedPayload[normalizeTelemetryKey(key)]?.replace(",", ".")?.toDoubleOrNull()
        }
    }

    private fun extractInsulinUnits(
        event: io.aaps.copilot.domain.model.TherapyEvent
    ): Double? {
        return payloadDouble(event, "units", "bolusUnits", "insulin", "enteredInsulin")
            ?.takeIf { it in 0.02..30.0 }
    }

    private fun resolveEffectiveStaleMaxMinutes(settings: AppSettings): Int {
        val global = settings.staleDataMaxMinutes
        if (!settings.adaptiveControllerEnabled) return global
        val profileLimit = when (settings.adaptiveControllerSafetyProfile.uppercase(Locale.US)) {
            "STRICT" -> 10
            "AGGRESSIVE" -> 20
            else -> 15
        }
        val adaptiveLimit = min(settings.adaptiveControllerStaleMaxMinutes, profileLimit)
        return min(global, adaptiveLimit)
    }

    private fun resolveEffectiveMaxActions6h(settings: AppSettings): Int {
        val global = settings.maxActionsIn6Hours
        if (!settings.adaptiveControllerEnabled) return global
        val profileLimit = when (settings.adaptiveControllerSafetyProfile.uppercase(Locale.US)) {
            "STRICT" -> 3
            "AGGRESSIVE" -> 6
            else -> 4
        }
        val adaptiveLimit = min(settings.adaptiveControllerMaxActions6h, profileLimit)
        return min(global, adaptiveLimit)
    }

    private fun buildIdempotencyKey(ruleId: String, nowTs: Long, settings: AppSettings): String {
        val bucketMinutes = if (ruleId == AdaptiveTargetControllerRule.RULE_ID) {
            settings.adaptiveControllerRetargetMinutes.coerceIn(5, 30)
        } else {
            30
        }
        return "$ruleId:${nowTs / (bucketMinutes * 60_000L)}"
    }

    private fun evaluateSensorQuality(
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        nowTs: Long,
        staleMaxMinutes: Int
    ): SensorQualityAssessment {
        return evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = nowTs,
            staleMaxMinutes = staleMaxMinutes
        )
    }

    private suspend fun persistSensorQualityTelemetry(nowTs: Long, assessment: SensorQualityAssessment) {
        val source = "copilot_sensor_quality"
        val rows = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            val numeric = value ?: return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = numeric,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
        }

        fun addText(key: String, value: String?) {
            val text = value?.trim().orEmpty()
            if (text.isBlank()) return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = text.take(64),
                unit = null,
                quality = "OK"
            )
        }

        addNumeric("sensor_quality_score", assessment.score)
        addNumeric("sensor_quality_blocked", if (assessment.blocked) 1.0 else 0.0)
        addNumeric("sensor_quality_suspect_false_low", if (assessment.suspectFalseLow) 1.0 else 0.0)
        addNumeric("sensor_quality_delta5_mmol", assessment.delta5Mmol, "mmol/5m")
        addNumeric("sensor_quality_noise_std5", assessment.noiseStd5Mmol, "mmol/5m")
        addNumeric("sensor_quality_gap_min", assessment.gapMinutes, "min")
        addText("sensor_quality_reason", assessment.reason)

        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    private suspend fun persistRuntimeCobIobTelemetry(
        nowTs: Long,
        runtime: RuntimeCobIobInputs,
        rawCobGrams: Double?,
        rawIobUnits: Double?
    ) {
        val source = "copilot_runtime_cob_iob"
        val rows = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            val numeric = value ?: return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = numeric,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
        }

        addNumeric("cob_effective_grams", runtime.cobGrams, "g")
        addNumeric("iob_effective_units", runtime.iobUnits, "U")
        addNumeric("iob_real_units", runtime.realIobUnits, "U")
        addNumeric("insulin_real_onset_min", runtime.realOnsetMinutes, "min")
        addNumeric("insulin_profile_base_onset_min", runtime.baseOnsetMinutes, "min")
        addNumeric("insulin_real_onset_samples", runtime.onsetSampleCount.toDouble())
        addNumeric("cob_local_fallback_grams", runtime.localCobGrams, "g")
        addNumeric("iob_local_fallback_units", runtime.localIobUnits, "U")
        addNumeric("cob_external_raw_grams", rawCobGrams, "g")
        addNumeric("iob_external_raw_units", rawIobUnits, "U")
        addNumeric("cob_iob_local_used", if (runtime.usedLocalFallback) 1.0 else 0.0)
        addNumeric("cob_iob_merged", if (runtime.mergedWithTelemetry) 1.0 else 0.0)

        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    private suspend fun refreshRealInsulinProfileTelemetry(
        nowTs: Long,
        settings: AppSettings
    ): RealInsulinProfileEstimate? {
        val existing = readLatestRealInsulinProfileEstimate(nowTs)
        val versionMismatch = existing?.algoVersion != null && existing.algoVersion != REAL_PROFILE_ALGO_VERSION
        val shouldRetryFallback = existing?.status == "fallback_template" && existing.sampleCount <= 0
        val shouldRecomputeDaily = existing == null ||
            !isSameLocalDay(existing.updatedTs, nowTs) ||
            versionMismatch ||
            shouldRetryFallback
        val recomputed = if (shouldRecomputeDaily) {
            computeRealInsulinProfileEstimate(nowTs = nowTs, settings = settings)
        } else {
            null
        }
        val effective = recomputed ?: existing
        if (effective == null) return null

        val shouldPublish = shouldRecomputeDaily ||
            nowTs - effective.lastPublishedTs >= REAL_PROFILE_PUBLISH_KEEPALIVE_MS
        auditLogger.info(
            "insulin_profile_real_refresh",
            mapOf(
                "existingFound" to (existing != null),
                "recomputed" to (recomputed != null),
                "shouldRecomputeDaily" to shouldRecomputeDaily,
                "versionMismatch" to versionMismatch,
                "shouldRetryFallback" to shouldRetryFallback,
                "shouldPublish" to shouldPublish,
                "updatedTs" to effective.updatedTs,
                "lastPublishedTs" to effective.lastPublishedTs,
                "sampleCount" to effective.sampleCount,
                "confidence" to effective.confidence,
                "status" to effective.status,
                "algoVersion" to effective.algoVersion
            )
        )
        if (shouldPublish) {
            persistRealInsulinProfileTelemetry(
                nowTs = nowTs,
                estimate = effective.copy(lastPublishedTs = nowTs)
            )
            return effective.copy(lastPublishedTs = nowTs)
        }
        return effective
    }

    private suspend fun readLatestRealInsulinProfileEstimate(nowTs: Long): RealInsulinProfileEstimate? {
        val rows = db.telemetryDao().since(nowTs - REAL_PROFILE_READ_LOOKBACK_MS)
            .filter { it.key.startsWith(REAL_PROFILE_KEY_PREFIX) }
        if (rows.isEmpty()) return null
        val latestByKey = rows
            .groupBy { it.key }
            .mapValues { (_, values) -> values.maxByOrNull { it.timestamp } }

        val curveRow = latestByKey[REAL_PROFILE_CURVE_KEY] ?: return null
        val compact = curveRow?.valueText?.trim().orEmpty()
        if (compact.isBlank()) return null

        val updatedTs = latestByKey[REAL_PROFILE_UPDATED_TS_KEY]?.valueDouble?.toLong()
            ?: curveRow.timestamp
        val confidence = latestByKey[REAL_PROFILE_CONFIDENCE_KEY]?.valueDouble
            ?.coerceIn(0.0, 1.0)
            ?: 0.30
        val sampleCount = latestByKey[REAL_PROFILE_SAMPLES_KEY]?.valueDouble
            ?.toInt()
            ?.coerceAtLeast(0)
            ?: 0
        val onsetMinutes = latestByKey[REAL_PROFILE_ONSET_KEY]?.valueDouble
            ?.coerceIn(INSULIN_ONSET_MIN_MINUTES, INSULIN_ONSET_MAX_MINUTES)
            ?: 30.0
        val peakMinutes = latestByKey[REAL_PROFILE_PEAK_KEY]?.valueDouble
            ?.coerceAtLeast(onsetMinutes + 5.0)
            ?: onsetMinutes + 70.0
        val shapeScale = latestByKey[REAL_PROFILE_SCALE_KEY]?.valueDouble
            ?.coerceIn(REAL_PROFILE_MIN_SCALE, REAL_PROFILE_MAX_SCALE)
            ?: 1.0
        val status = latestByKey[REAL_PROFILE_STATUS_KEY]?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "estimated_daily"
        val sourceProfile = latestByKey[REAL_PROFILE_SOURCE_PROFILE_KEY]?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: InsulinActionProfileId.NOVORAPID.name
        val lastPublishedTs = latestByKey[REAL_PROFILE_PUBLISHED_TS_KEY]?.valueDouble?.toLong()
            ?: curveRow.timestamp
        val algoVersion = latestByKey[REAL_PROFILE_ALGO_VERSION_KEY]?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "v1"

        return RealInsulinProfileEstimate(
            updatedTs = updatedTs,
            pointsCompact = compact,
            confidence = confidence,
            sampleCount = sampleCount,
            onsetMinutes = onsetMinutes,
            peakMinutes = peakMinutes,
            shapeScale = shapeScale,
            sourceProfileId = sourceProfile,
            status = status,
            lastPublishedTs = lastPublishedTs,
            algoVersion = algoVersion
        )
    }

    private suspend fun computeRealInsulinProfileEstimate(
        nowTs: Long,
        settings: AppSettings
    ): RealInsulinProfileEstimate {
        val historyStart = nowTs - REAL_PROFILE_HISTORY_LOOKBACK_MS
        val glucose = GlucoseSanitizer
            .filterEntities(db.glucoseDao().since(historyStart))
            .map { it.toDomain() }
            .sortedBy { it.ts }
        val therapy = TherapySanitizer
            .filterEntities(db.therapyDao().since(historyStart))
            .map { it.toDomain(gson) }
            .sortedBy { it.ts }
        val telemetry = db.telemetryDao().since(historyStart)

        val profileId = InsulinActionProfileId.fromRaw(settings.insulinProfileId)
        val profile = InsulinActionProfiles.profile(profileId)
        val baseOnset = profileOnsetMinutes(profile)
        val basePeak = profilePeakMinutes(profile)

        val onsetCandidates = mutableListOf<Double>()
        val peakCandidates = mutableListOf<Double>()
        var usableEvents = 0
        var explicitPulseCount = 0
        var implicitPulseCount = 0

        if (glucose.size >= 10) {
            val explicitPulses = therapy
                .asSequence()
                .mapNotNull { event ->
                    val units = extractInsulinUnits(event) ?: return@mapNotNull null
                    val ageMs = nowTs - event.ts
                    if (units < REAL_PROFILE_MIN_BOLUS_UNITS) return@mapNotNull null
                    if (ageMs < REAL_PROFILE_MIN_EVENT_AGE_MS) return@mapNotNull null
                    InsulinPulseCandidate(
                        ts = event.ts,
                        units = units,
                        source = "therapy_bolus"
                    )
                }
                .toList()
            explicitPulseCount = explicitPulses.size

            val implicitPulses = extractImplicitInsulinPulseCandidates(telemetry)
                .filter { candidate ->
                    explicitPulses.none { explicit -> abs(explicit.ts - candidate.ts) <= REAL_PROFILE_IMPLICIT_MERGE_WINDOW_MS }
                }
            implicitPulseCount = implicitPulses.size

            val pulseCandidates = (explicitPulses + implicitPulses)
                .sortedBy { it.ts }

            pulseCandidates.forEach { pulse ->
                val nearbyMealCarbs = therapy
                    .asSequence()
                    .filter { event -> abs(event.ts - pulse.ts) <= REAL_PROFILE_MEAL_EXCLUSION_MS }
                    .sumOf { event ->
                        payloadDouble(event, "grams", "carbs", "enteredCarbs", "mealCarbs") ?: 0.0
                    }
                val hasNearbyMeal = nearbyMealCarbs >= REAL_PROFILE_MEAL_EXCLUSION_CARBS_G
                if (hasNearbyMeal) return@forEach

                val baseline = nearestGlucoseValueMmol(
                    sortedGlucose = glucose,
                    targetTs = pulse.ts,
                    toleranceMs = REAL_PROFILE_BASELINE_TOLERANCE_MS
                ) ?: return@forEach

                val window = glucose.filter { point ->
                    point.ts in (pulse.ts + REAL_PROFILE_WINDOW_START_MS)..(pulse.ts + REAL_PROFILE_WINDOW_END_MS)
                }
                if (window.size < 4) return@forEach
                usableEvents += 1

                for (index in 1 until window.size) {
                    val prev = window[index - 1]
                    val current = window[index]
                    val dtMin = (current.ts - prev.ts) / 60_000.0
                    if (dtMin !in 2.0..15.0) continue
                    val ageMin = (current.ts - pulse.ts) / 60_000.0
                    if (ageMin < INSULIN_ONSET_MIN_MINUTES || ageMin > INSULIN_ONSET_MAX_MINUTES) continue
                    val delta5 = (current.valueMmol - prev.valueMmol) / (dtMin / 5.0)
                    val drop = baseline - current.valueMmol
                    if (drop >= REAL_PROFILE_ONSET_DROP_THRESHOLD_MMOL && delta5 <= REAL_PROFILE_ONSET_DELTA5_THRESHOLD_MMOL) {
                        onsetCandidates += ageMin
                        break
                    }
                }

                val peakPoint = window
                    .asSequence()
                    .filter { point ->
                        val ageMin = (point.ts - pulse.ts) / 60_000.0
                        ageMin in REAL_PROFILE_MIN_PEAK_MINUTES..REAL_PROFILE_MAX_PEAK_MINUTES
                    }
                    .minByOrNull { it.valueMmol }
                if (peakPoint != null) {
                    val dropAtPeak = baseline - peakPoint.valueMmol
                    if (dropAtPeak >= REAL_PROFILE_PEAK_DROP_THRESHOLD_MMOL) {
                        val peakAgeMin = (peakPoint.ts - pulse.ts) / 60_000.0
                        peakCandidates += peakAgeMin
                    }
                }
            }
        }

        val rawOnset = if (onsetCandidates.isNotEmpty()) {
            median(onsetCandidates).coerceIn(INSULIN_ONSET_MIN_MINUTES, INSULIN_ONSET_MAX_MINUTES)
        } else {
            baseOnset
        }
        val onsetSampleWeight = when {
            onsetCandidates.size >= 8 -> 1.0
            onsetCandidates.size >= 6 -> 0.80
            onsetCandidates.size >= 4 -> 0.60
            onsetCandidates.size >= 3 -> 0.45
            onsetCandidates.size >= 2 -> 0.30
            else -> 0.0
        }
        val onsetSpreadWeight = (1.0 - (stdDev(onsetCandidates) / 35.0)).coerceIn(0.20, 1.0)
        val onsetSourceWeight = when {
            explicitPulseCount > 0 && implicitPulseCount > 0 -> 1.0
            explicitPulseCount > 0 -> 0.90
            implicitPulseCount > 0 -> 0.55
            else -> 0.0
        }
        val onsetBlendWeight = (onsetSampleWeight * onsetSpreadWeight * onsetSourceWeight).coerceIn(0.0, 1.0)
        val onsetMaxShift = if (profile.id.isUltraRapid) 28.0 else 40.0
        val onsetMinBound = (baseOnset - 8.0).coerceAtLeast(INSULIN_ONSET_MIN_MINUTES)
        val onsetMaxBound = (baseOnset + onsetMaxShift).coerceAtMost(INSULIN_ONSET_MAX_MINUTES)
        val realOnset = (baseOnset + (rawOnset - baseOnset) * onsetBlendWeight)
            .coerceIn(onsetMinBound, onsetMaxBound)

        val rawPeak = if (peakCandidates.isNotEmpty()) {
            median(peakCandidates).coerceAtLeast(realOnset + 15.0)
        } else {
            (realOnset + (basePeak - baseOnset)).coerceAtLeast(realOnset + 25.0)
        }
        val peakSampleWeight = when {
            peakCandidates.size >= 10 -> 1.0
            peakCandidates.size >= 7 -> 0.82
            peakCandidates.size >= 5 -> 0.66
            peakCandidates.size >= 3 -> 0.50
            peakCandidates.size >= 2 -> 0.38
            else -> 0.0
        }
        val peakSpreadWeight = (1.0 - (stdDev(peakCandidates) / 75.0)).coerceIn(0.20, 1.0)
        val peakSourceWeight = when {
            explicitPulseCount > 0 && implicitPulseCount > 0 -> 1.0
            explicitPulseCount > 0 -> 0.92
            implicitPulseCount > 0 -> 0.65
            else -> 0.0
        }
        val peakBlendWeight = (peakSampleWeight * peakSpreadWeight * peakSourceWeight).coerceIn(0.0, 1.0)
        val peakMaxShift = if (profile.id.isUltraRapid) 55.0 else 70.0
        val peakMinBound = (basePeak - 15.0).coerceAtLeast(realOnset + 20.0)
        val peakMaxBound = (basePeak + peakMaxShift).coerceAtMost(REAL_PROFILE_MAX_PEAK_MINUTES)
        val realPeak = (basePeak + (rawPeak - basePeak) * peakBlendWeight)
            .coerceIn(peakMinBound, peakMaxBound)
        val baseWindow = (basePeak - baseOnset).coerceAtLeast(20.0)
        val realWindow = (realPeak - realOnset).coerceAtLeast(20.0)
        val shapeScale = (realWindow / baseWindow).coerceIn(REAL_PROFILE_MIN_SCALE, REAL_PROFILE_MAX_SCALE)
        val shiftMinutes = realOnset - baseOnset

        val points = (0..REAL_PROFILE_CURVE_MAX_MINUTES step REAL_PROFILE_CURVE_STEP_MINUTES).map { minute ->
            val currentMinute = minute.toDouble()
            val cumulative = if (minute == 0) {
                0.0
            } else {
                val adjustedAge = ((currentMinute - shiftMinutes) / shapeScale).coerceAtLeast(0.0)
                profile.cumulativeAt(adjustedAge).coerceIn(0.0, 1.0)
            }
            InsulinActionPoint(
                minute = currentMinute,
                cumulative = cumulative
            )
        }

        val monotonicPoints = buildList {
            var last = 0.0
            points.forEach { point ->
                val next = if (point.cumulative >= last) point.cumulative else last
                add(point.copy(cumulative = next.coerceIn(0.0, 1.0)))
                last = next
            }
        }

        val onsetStd = stdDev(onsetCandidates)
        val peakStd = stdDev(peakCandidates)
        var confidence = when {
            usableEvents >= 8 -> 0.90
            usableEvents >= 5 -> 0.78
            usableEvents >= 3 -> 0.64
            usableEvents >= 2 -> 0.52
            else -> 0.36
        }
        val spreadPenalty = (
            (onsetStd / 42.0).coerceIn(0.0, 0.30) +
                (peakStd / 90.0).coerceIn(0.0, 0.30)
            ) * 0.5
        confidence -= spreadPenalty
        if (onsetCandidates.isEmpty() || peakCandidates.isEmpty()) {
            confidence -= 0.08
        }
        confidence = confidence.coerceIn(0.20, 0.95)
        val status = if (usableEvents >= 2) "estimated_daily" else "fallback_template"
        auditLogger.info(
            "insulin_profile_real_computed",
            mapOf(
                "eventsEvaluated" to usableEvents,
                "explicitPulses" to explicitPulseCount,
                "implicitPulses" to implicitPulseCount,
                "onsetSamples" to onsetCandidates.size,
                "peakSamples" to peakCandidates.size,
                "rawOnsetMinutes" to rawOnset,
                "realOnsetMinutes" to realOnset,
                "onsetBlendWeight" to onsetBlendWeight,
                "rawPeakMinutes" to rawPeak,
                "realPeakMinutes" to realPeak,
                "peakBlendWeight" to peakBlendWeight,
                "shapeScale" to shapeScale,
                "confidence" to confidence,
                "status" to status,
                "profileId" to profileId.name
            )
        )

        return RealInsulinProfileEstimate(
            updatedTs = nowTs,
            pointsCompact = encodeInsulinCurveCompact(monotonicPoints),
            confidence = confidence,
            sampleCount = usableEvents,
            onsetMinutes = realOnset,
            peakMinutes = realPeak,
            shapeScale = shapeScale,
            sourceProfileId = profileId.name,
            status = status,
            lastPublishedTs = nowTs,
            algoVersion = REAL_PROFILE_ALGO_VERSION
        )
    }

    private suspend fun persistRealInsulinProfileTelemetry(
        nowTs: Long,
        estimate: RealInsulinProfileEstimate
    ) {
        val source = REAL_PROFILE_SOURCE
        val rows = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            val numeric = value ?: return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = numeric,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
        }

        fun addText(key: String, value: String?) {
            val text = value?.trim().orEmpty()
            if (text.isBlank()) return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = text,
                unit = null,
                quality = "OK"
            )
        }

        addText(REAL_PROFILE_CURVE_KEY, estimate.pointsCompact)
        addNumeric(REAL_PROFILE_UPDATED_TS_KEY, estimate.updatedTs.toDouble())
        addNumeric(REAL_PROFILE_CONFIDENCE_KEY, estimate.confidence)
        addNumeric(REAL_PROFILE_SAMPLES_KEY, estimate.sampleCount.toDouble())
        addNumeric(REAL_PROFILE_ONSET_KEY, estimate.onsetMinutes, "min")
        addNumeric(REAL_PROFILE_PEAK_KEY, estimate.peakMinutes, "min")
        addNumeric(REAL_PROFILE_SCALE_KEY, estimate.shapeScale)
        addNumeric(REAL_PROFILE_PUBLISHED_TS_KEY, estimate.lastPublishedTs.toDouble())
        addText(REAL_PROFILE_SOURCE_PROFILE_KEY, estimate.sourceProfileId)
        addText(REAL_PROFILE_STATUS_KEY, estimate.status)
        addText(REAL_PROFILE_ALGO_VERSION_KEY, estimate.algoVersion)
        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
            auditLogger.info(
                "insulin_profile_real_persisted",
                mapOf(
                    "rows" to rows.size,
                    "updatedTs" to estimate.updatedTs,
                    "publishedTs" to estimate.lastPublishedTs,
                    "confidence" to estimate.confidence,
                    "sampleCount" to estimate.sampleCount,
                    "status" to estimate.status,
                    "algoVersion" to estimate.algoVersion
                )
            )
        }
    }

    private fun encodeInsulinCurveCompact(points: List<InsulinActionPoint>): String {
        return points.joinToString(separator = ";") { point ->
            val minute = String.format(Locale.US, "%.1f", point.minute.coerceAtLeast(0.0))
            val cumulative = String.format(Locale.US, "%.4f", point.cumulative.coerceIn(0.0, 1.0))
            "$minute:$cumulative"
        }
    }

    private fun profilePeakMinutes(profile: InsulinActionProfile): Double {
        val points = profile.points.sortedBy { it.minute }
        if (points.size < 2) return points.firstOrNull()?.minute ?: 120.0
        var bestMinute = points.first().minute
        var bestSlope = Double.NEGATIVE_INFINITY
        for (index in 1 until points.size) {
            val left = points[index - 1]
            val right = points[index]
            val deltaMinute = (right.minute - left.minute).coerceAtLeast(1e-6)
            val slope = (right.cumulative - left.cumulative) / deltaMinute
            if (slope > bestSlope) {
                bestSlope = slope
                bestMinute = (left.minute + right.minute) / 2.0
            }
        }
        return bestMinute.coerceAtLeast(0.0)
    }

    private fun isSameLocalDay(tsA: Long, tsB: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val dayA = Instant.ofEpochMilli(tsA).atZone(zone).toLocalDate()
        val dayB = Instant.ofEpochMilli(tsB).atZone(zone).toLocalDate()
        return dayA == dayB
    }

    private fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values
            .map { value ->
                val delta = value - mean
                delta * delta
            }
            .average()
        return sqrt(variance).coerceAtLeast(0.0)
    }

    private fun extractImplicitInsulinPulseCandidates(
        telemetry: List<TelemetrySampleEntity>
    ): List<InsulinPulseCandidate> {
        if (telemetry.isEmpty()) return emptyList()
        val iobSeries = telemetry
            .asSequence()
            .filter { sample -> sample.key == "iob_effective_units" || sample.key == "iob_units" }
            .mapNotNull { sample ->
                val value = sample.valueDouble ?: return@mapNotNull null
                sample.timestamp to value.coerceAtLeast(0.0)
            }
            .groupBy { it.first }
            .map { (ts, values) -> ts to (values.maxOfOrNull { it.second } ?: 0.0) }
            .sortedBy { it.first }

        if (iobSeries.size < 3) return emptyList()

        val pulses = mutableListOf<InsulinPulseCandidate>()
        for (index in 1 until iobSeries.size) {
            val prev = iobSeries[index - 1]
            val current = iobSeries[index]
            val dtMin = (current.first - prev.first) / 60_000.0
            if (dtMin !in 1.0..20.0) continue
            val delta = current.second - prev.second
            if (delta < REAL_PROFILE_IMPLICIT_IOB_STEP_MIN_UNITS) continue
            if (current.second < REAL_PROFILE_IMPLICIT_IOB_LEVEL_MIN_UNITS) continue
            val futureWindow = iobSeries
                .drop(index + 1)
                .take(REAL_PROFILE_IMPLICIT_CONFIRM_STEPS)
                .filter { (ts, _) -> ts - current.first <= REAL_PROFILE_IMPLICIT_CONFIRM_WINDOW_MS }
            if (futureWindow.isNotEmpty()) {
                val futureMean = futureWindow.map { it.second }.average()
                if (futureMean > current.second - REAL_PROFILE_IMPLICIT_CONFIRM_DROP_MIN_UNITS) {
                    continue
                }
            }
            val last = pulses.lastOrNull()
            if (last != null && current.first - last.ts < REAL_PROFILE_IMPLICIT_MERGE_WINDOW_MS) continue
            val inferredTs = (current.first - REAL_PROFILE_IMPLICIT_BACKDATE_MS).coerceAtLeast(0L)

            pulses += InsulinPulseCandidate(
                ts = inferredTs,
                units = delta.coerceAtLeast(REAL_PROFILE_IMPLICIT_IOB_STEP_MIN_UNITS),
                source = "implicit_iob_step"
            )
        }
        return pulses
    }

    private suspend fun maybeSendSensorQualityRollbackTempTarget(
        settings: AppSettings,
        nowTs: Long,
        dataFresh: Boolean,
        assessment: SensorQualityAssessment,
        activeTempTarget: Double?,
        actionsLast6h: Int,
        baseTargetMmol: Double
    ) {
        if (settings.killSwitch) return
        if (!dataFresh) return
        if (!shouldSendSensorQualityRollbackStatic(activeTempTarget, baseTargetMmol, assessment)) return

        val rollbackTarget = roundToStep(baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL), 0.05)
        val idempotencyKey = "$SENSOR_QUALITY_ROLLBACK_IDEMPOTENCY_PREFIX${nowTs / SENSOR_QUALITY_ROLLBACK_INTERVAL_MS}"
        val command = ActionCommand(
            id = UUID.randomUUID().toString(),
            type = "temp_target",
            params = mapOf(
                "targetMmol" to rollbackTarget.toString(),
                "durationMinutes" to SENSOR_QUALITY_ROLLBACK_DURATION_MINUTES.toString(),
                "reason" to "sensor_quality_rollback:${assessment.reason}"
            ),
            safetySnapshot = SafetySnapshot(
                killSwitch = settings.killSwitch,
                dataFresh = dataFresh,
                activeTempTargetMmol = activeTempTarget,
                actionsLast6h = actionsLast6h
            ),
            idempotencyKey = idempotencyKey
        )
        val sent = actionRepository.submitTempTarget(command)
        if (sent) {
            auditLogger.warn(
                "sensor_quality_rollback_sent",
                mapOf(
                    "targetMmol" to rollbackTarget,
                    "reason" to assessment.reason,
                    "activeTempTarget" to activeTempTarget
                )
            )
        } else {
            auditLogger.warn(
                "sensor_quality_rollback_failed",
                mapOf(
                    "targetMmol" to rollbackTarget,
                    "reason" to assessment.reason,
                    "activeTempTarget" to activeTempTarget
                )
            )
        }
    }

    private suspend fun auditAdaptiveController(
        decisions: List<RuleDecision>,
        context: RuleContext,
        settings: AppSettings,
        forecasts: List<Forecast>
    ) {
        val adaptive = decisions.firstOrNull { it.ruleId == AdaptiveTargetControllerRule.RULE_ID } ?: return
        val f30 = forecasts.firstOrNull { it.horizonMinutes == 30 }?.valueMmol
        val f60 = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol
        val weightedError = if (f30 != null && f60 != null) {
            val e30 = f30 - context.baseTargetMmol
            val e60 = f60 - context.baseTargetMmol
            0.65 * e30 + 0.35 * e60
        } else {
            null
        }
        val mode = when {
            adaptive.reasons.any { it == "activity_protection_active" } -> "activity_protection"
            adaptive.reasons.any { it == "activity_recovery_to_base" } -> "activity_recovery"
            adaptive.reasons.any { it.startsWith("reason=") } -> adaptive.reasons
                .first { it.startsWith("reason=") }
                .substringAfter("=")
            else -> "unknown"
        }

        val metadata = linkedMapOf<String, Any?>(
            "state" to adaptive.state.name,
            "mode" to mode,
            "reasons" to adaptive.reasons,
            "target" to adaptive.actionProposal?.targetMmol,
            "duration" to adaptive.actionProposal?.durationMinutes,
            "f30" to f30,
            "f60" to f60,
            "weightedError" to weightedError,
            "dataFresh" to context.dataFresh,
            "actionsLast6h" to context.actionsLast6h,
            "adaptiveEnabled" to settings.adaptiveControllerEnabled,
            "retargetMinutes" to settings.adaptiveControllerRetargetMinutes
        )
        auditLogger.info("adaptive_controller_evaluated", metadata)

        when (adaptive.state) {
            RuleState.TRIGGERED -> auditLogger.info("adaptive_controller_triggered", metadata)
            RuleState.BLOCKED -> {
                val blockedByCooldown = adaptive.reasons.any {
                    it.startsWith("retarget_cooldown_") || it.startsWith("rule_cooldown_active:")
                }
                if (blockedByCooldown) {
                    auditLogger.info("adaptive_controller_blocked", metadata + ("blockedKind" to "cooldown"))
                } else {
                    auditLogger.warn("adaptive_controller_blocked", metadata)
                }
            }
            RuleState.NO_MATCH -> Unit
        }

        val fallbackTriggered = decisions.firstOrNull {
            it.ruleId != AdaptiveTargetControllerRule.RULE_ID && it.state == RuleState.TRIGGERED
        }
        if (adaptive.state != RuleState.TRIGGERED && fallbackTriggered != null) {
            auditLogger.info(
                "adaptive_controller_fallback_to_rules",
                mapOf(
                    "adaptiveState" to adaptive.state.name,
                    "fallbackRuleId" to fallbackTriggered.ruleId,
                    "fallbackTarget" to fallbackTriggered.actionProposal?.targetMmol
                )
            )
        }
    }

    private fun calculateCalculatedUamSnapshot(
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        profile: ProfileEstimate?,
        nowTs: Long
    ): CalculatedUamSnapshot {
        val signal = UamCalculator.latestSignal(
            glucose = glucose,
            therapyEvents = therapy,
            nowTs = nowTs,
            lookbackMinutes = CALCULATED_UAM_LOOKBACK_MINUTES
        )
        val carbs = UamCalculator.estimateCarbsGrams(
            signal = signal,
            isfMmolPerUnit = profile?.isfMmolPerUnit,
            crGramPerUnit = profile?.crGramPerUnit
        ).takeIf { it > 0.0 }
        return CalculatedUamSnapshot(
            flag = if (signal != null) 1.0 else 0.0,
            confidence = signal?.confidence ?: 0.0,
            estimatedCarbsGrams = carbs,
            rise15Mmol = signal?.rise15Mmol,
            rise30Mmol = signal?.rise30Mmol,
            delta5Mmol = signal?.delta5Mmol
        )
    }

    private suspend fun persistCalculatedUamTelemetry(nowTs: Long, snapshot: CalculatedUamSnapshot) {
        val source = "copilot_calculated"
        val rows = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            val numeric = value ?: return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = numeric,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
        }

        addNumeric("uam_calculated_flag", snapshot.flag)
        addNumeric("uam_calculated_confidence", snapshot.confidence)
        addNumeric("uam_calculated_carbs_grams", snapshot.estimatedCarbsGrams ?: 0.0, "g")
        addNumeric("uam_calculated_rise15_mmol", snapshot.rise15Mmol ?: 0.0, "mmol/L")
        addNumeric("uam_calculated_rise30_mmol", snapshot.rise30Mmol ?: 0.0, "mmol/L")
        addNumeric("uam_calculated_delta5_mmol", snapshot.delta5Mmol ?: 0.0, "mmol/5m")
        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    private suspend fun maybeProcessUamInferenceCycle(
        settings: AppSettings,
        nowTs: Long,
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        profile: ProfileEstimate?,
        calculatedSnapshot: CalculatedUamSnapshot
    ): UamInferenceCycleResult? {
        val cycleBucket = (nowTs / UAM_PROCESSING_BUCKET_MS) * UAM_PROCESSING_BUCKET_MS
        val existingEvents = uamEventStore.loadAll()
        val shouldProcessBucket = cycleBucket > lastUamProcessingBucketTs
        if (!shouldProcessBucket) {
            val active = existingEvents
                .filter {
                    it.state == io.aaps.copilot.domain.predict.UamInferenceState.SUSPECTED ||
                        it.state == io.aaps.copilot.domain.predict.UamInferenceState.CONFIRMED
                }
                .maxByOrNull { it.updatedAt }
            return UamInferenceCycleResult(
                activeFlag = if (active != null) 1.0 else 0.0,
                confidence = active?.confidence,
                inferredCarbsGrams = active?.carbsDisplayG,
                ingestionTs = active?.ingestionTs,
                modeBoosted = settings.enableUamBoost,
                manualCobGrams = 0.0,
                gAbsRecent = emptyList(),
                events = existingEvents,
                createdNewEvent = false
            )
        }
        lastUamProcessingBucketTs = cycleBucket

        val inferenceOutput = uamInferenceEngine.infer(
            UamInferenceEngine.Input(
                nowTs = cycleBucket,
                glucose = glucose,
                therapyEvents = therapy,
                existingEvents = existingEvents,
                isfMmolPerUnit = profile?.isfMmolPerUnit,
                crGramPerUnit = profile?.crGramPerUnit,
                insulinProfileId = settings.insulinProfileId,
                enableUamInference = settings.enableUamInference,
                enableUamBoost = settings.enableUamBoost,
                learnedMultiplier = settings.uamLearnedMultiplier,
                userSettings = settings.toUamUserSettingsLocal()
            )
        )

        val exportOutcome = uamExportCoordinator.process(
            nowTs = cycleBucket,
            events = inferenceOutput.events,
            config = UamExportCoordinator.Config(
                enableUamExportToAaps = settings.enableUamExportToAaps,
                exportMode = settings.uamExportMode,
                dryRunExport = settings.dryRunExport,
                minSnackG = settings.uamMinSnackG,
                maxSnackG = settings.uamMaxSnackG,
                snackStepG = settings.uamSnackStepG,
                exportMinIntervalMin = settings.uamExportMinIntervalMin,
                exportMaxBackdateMin = settings.uamExportMaxBackdateMin,
                calculatedCarbsGrams = calculatedSnapshot.estimatedCarbsGrams,
                calculatedToOriginalMultiplier = CALCULATED_TO_ORIGINAL_MULTIPLIER
            )
        )
        val finalizedEvents = exportOutcome.events
        uamEventStore.upsert(finalizedEvents)
        uamEventStore.prune(cycleBucket - UAM_EVENT_RETENTION_MS)

        inferenceOutput.learnedMultiplierUpdate?.let { updated ->
            settingsStore.update { current ->
                val currentValue = current.uamLearnedMultiplier.coerceIn(0.8, 1.6)
                if (kotlin.math.abs(currentValue - updated) < 1e-6) {
                    current
                } else {
                    current.copy(uamLearnedMultiplier = updated.coerceIn(0.8, 1.6))
                }
            }
        }

        val active = finalizedEvents
            .filter {
                it.state == io.aaps.copilot.domain.predict.UamInferenceState.SUSPECTED ||
                    it.state == io.aaps.copilot.domain.predict.UamInferenceState.CONFIRMED
            }
            .maxByOrNull { it.updatedAt }
        return UamInferenceCycleResult(
            activeFlag = if (active != null) 1.0 else 0.0,
            confidence = active?.confidence,
            inferredCarbsGrams = active?.carbsDisplayG,
            ingestionTs = active?.ingestionTs,
            modeBoosted = settings.enableUamBoost,
            manualCobGrams = inferenceOutput.manualCobNow,
            gAbsRecent = inferenceOutput.gAbsRecent,
            events = finalizedEvents,
            createdNewEvent = inferenceOutput.createdNewEvent
        )
    }

    private suspend fun persistInferredUamTelemetry(nowTs: Long, result: UamInferenceCycleResult) {
        val source = "copilot_uam_inference"
        val rows = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            val numeric = value ?: return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = numeric,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
        }

        fun addText(key: String, value: String?) {
            val text = value?.trim().orEmpty()
            if (text.isBlank()) return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = text,
                unit = null,
                quality = "OK"
            )
        }

        addNumeric("uam_inferred_flag", result.activeFlag)
        addNumeric("uam_inferred_confidence", result.confidence ?: 0.0)
        addNumeric("uam_inferred_carbs_grams", result.inferredCarbsGrams ?: 0.0, "g")
        addNumeric("uam_inferred_ingestion_ts", result.ingestionTs?.toDouble())
        addNumeric("uam_inferred_boost_mode", if (result.modeBoosted) 1.0 else 0.0)
        addNumeric("uam_manual_cob_grams", result.manualCobGrams, "g")
        addNumeric("uam_inferred_events_active", result.events.count {
            it.state == io.aaps.copilot.domain.predict.UamInferenceState.SUSPECTED ||
                it.state == io.aaps.copilot.domain.predict.UamInferenceState.CONFIRMED
        }.toDouble())
        result.gAbsRecent.lastOrNull()?.let { addNumeric("uam_inferred_gabs_last5_g", it, "g") }
        addText("uam_inferred_mode", if (result.modeBoosted) "BOOST" else "NORMAL")

        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    private suspend fun persistForecastDecompositionTelemetry(
        nowTs: Long,
        decomposition: ForecastDecompositionSnapshot?
    ) {
        val source = "copilot_forecast_decomposition"
        val rows = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = value,
                valueText = null,
                unit = unit,
                quality = if (value == null) "STALE" else "OK"
            )
        }

        fun addText(key: String, value: String?) {
            val text = value?.trim().orEmpty()
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = text.ifBlank { null },
                unit = null,
                quality = if (text.isBlank()) "STALE" else "OK"
            )
        }

        addNumeric("forecast_trend_60_mmol", decomposition?.trend60Mmol, "mmol/L")
        addNumeric("forecast_therapy_60_mmol", decomposition?.therapy60Mmol, "mmol/L")
        addNumeric("forecast_uam_60_mmol", decomposition?.uam60Mmol, "mmol/L")
        addNumeric("forecast_residual_roc0_mmol5", decomposition?.residualRoc0Mmol5, "mmol/5m")
        addNumeric("forecast_sigmae_mmol5", decomposition?.sigmaEMmol5, "mmol/5m")
        addNumeric("forecast_kf_sigma_g_mmol", decomposition?.kfSigmaGMmol, "mmol/L")
        addNumeric("forecast_decomp_available", if (decomposition != null) 1.0 else 0.0)
        addText("forecast_decomp_model_version", decomposition?.modelVersion)

        db.telemetryDao().upsertAll(rows)
    }

    suspend fun runDryRunSimulation(days: Int): DryRunReport {
        val settings = settingsStore.settings.first()
        configurePredictionEngine(settings)
        val periodDays = days.coerceIn(1, 60)
        val startTs = System.currentTimeMillis() - periodDays * 24L * 60 * 60 * 1000

        val glucose = db.glucoseDao().since(startTs).map { it.toGlucosePoint() }.sortedBy { it.ts }
        val therapy = db.therapyDao().since(startTs).map { it.toTherapyEvent(gson) }.sortedBy { it.ts }
        val patterns = db.patternDao().all()
        val profile = db.profileEstimateDao().active()?.toProfileEstimate()
        val segments = db.profileSegmentEstimateDao().all().associateBy { it.dayType to it.timeSlot }

        if (glucose.size < 8) {
            return DryRunReport(periodDays, glucose.size, emptyList())
        }

        val counters = mutableMapOf<String, Triple<Int, Int, Int>>()
        val lastTriggeredTsByRule = mutableMapOf<String, Long>()
        val runtimeConfig = runtimeConfig(settings)

        for (index in 7 until glucose.size step 2) {
            val point = glucose[index]
            val pointTs = point.ts
            val windowStart = pointTs - 6L * 60 * 60 * 1000
            val therapyStart = pointTs - 24L * 60 * 60 * 1000
            val gWindow = glucose.filter { it.ts in windowStart..pointTs }
            val tWindow = therapy.filter { it.ts in therapyStart..pointTs }
            val forecasts = ensureForecast30(predictionEngine.predict(gWindow, tWindow))

            val zoned = Instant.ofEpochMilli(pointTs).atZone(ZoneId.systemDefault())
            val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
            val pattern = patterns.firstOrNull {
                it.dayType == dayType.name && it.hour == zoned.hour
            }?.let {
                io.aaps.copilot.domain.model.PatternWindow(
                    dayType = dayType,
                    hour = it.hour,
                    sampleCount = it.sampleCount,
                    activeDays = it.activeDays,
                    lowRate = it.lowRate,
                    highRate = it.highRate,
                    recommendedTargetMmol = it.recommendedTargetMmol,
                    isRiskWindow = it.isRiskWindow
                )
            }
            val segmentSlot = resolveTimeSlot(zoned.hour)
            val segment = segments[dayType.name to segmentSlot.name]?.toProfileSegmentEstimate()

            val context = RuleContext(
                nowTs = pointTs,
                glucose = gWindow,
                therapyEvents = tWindow,
                forecasts = forecasts,
                currentDayPattern = pattern,
                baseTargetMmol = settings.baseTargetMmol,
                postHypoThresholdMmol = settings.postHypoThresholdMmol,
                postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m,
                postHypoTargetMmol = settings.postHypoTargetMmol,
                postHypoDurationMinutes = settings.postHypoDurationMinutes,
                postHypoLookbackMinutes = settings.postHypoLookbackMinutes,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = isSensorBlocked(tWindow, pointTs),
                currentProfileEstimate = profile,
                currentProfileSegment = segment,
                adaptiveMaxStepMmol = settings.adaptiveControllerMaxStepMmol,
                adaptiveMinTargetMmol = settings.safetyMinTargetMmol,
                adaptiveMaxTargetMmol = settings.safetyMaxTargetMmol
            )

            val decisions = ruleEngine.evaluate(
                context = context,
                config = SafetyPolicyConfig(
                    killSwitch = false,
                    maxActionsIn6Hours = resolveEffectiveMaxActions6h(settings),
                    minTargetMmol = settings.safetyMinTargetMmol,
                    maxTargetMmol = settings.safetyMaxTargetMmol
                ),
                runtimeConfig = runtimeConfig
            )

            decisions.forEach { decision ->
                val effectiveDecision = if (decision.state == RuleState.TRIGGERED && decision.actionProposal != null) {
                    val cooldown = ruleCooldownMinutes(decision.ruleId, settings)
                    val lastTs = lastTriggeredTsByRule[decision.ruleId]
                    if (cooldown > 0 && lastTs != null && (pointTs - lastTs) < cooldown * 60_000L) {
                        decision.copy(
                            state = RuleState.BLOCKED,
                            reasons = decision.reasons + "rule_cooldown_active:${cooldown}m",
                            actionProposal = null
                        )
                    } else {
                        lastTriggeredTsByRule[decision.ruleId] = pointTs
                        decision
                    }
                } else {
                    decision
                }

                val current = counters[effectiveDecision.ruleId] ?: Triple(0, 0, 0)
                counters[effectiveDecision.ruleId] = when (effectiveDecision.state) {
                    RuleState.TRIGGERED -> Triple(current.first + 1, current.second, current.third)
                    RuleState.BLOCKED -> Triple(current.first, current.second + 1, current.third)
                    RuleState.NO_MATCH -> Triple(current.first, current.second, current.third + 1)
                }
            }
        }

        return DryRunReport(
            periodDays = periodDays,
            samplePoints = glucose.size,
            rules = counters.map { (ruleId, triple) ->
                DryRunRuleSummary(
                    ruleId = ruleId,
                    triggered = triple.first,
                    blocked = triple.second,
                    noMatch = triple.third
                )
            }.sortedBy { it.ruleId }
        )
    }

    private suspend fun maybeMergeCloudPrediction(
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        localForecasts: List<io.aaps.copilot.domain.model.Forecast>
    ): List<io.aaps.copilot.domain.model.Forecast> {
        val settings = settingsStore.settings.first()
        if (!isCopilotCloudBackendEndpoint(settings.cloudBaseUrl)) return localForecasts

        return runCatching {
            val cloudApi = apiFactory.cloudApi(settings)
            val response = cloudApi.predict(
                PredictRequest(
                    glucose = glucose.map {
                        CloudGlucosePoint(
                            ts = it.ts,
                            valueMmol = it.valueMmol,
                            source = it.source,
                            quality = it.quality.name
                        )
                    },
                    therapyEvents = therapy.map {
                        CloudTherapyEvent(
                            id = "local-${it.ts}-${it.type}",
                            ts = it.ts,
                            type = it.type,
                            payload = it.payload
                        )
                    }
                )
            )
            val cloud = response.forecasts.map {
                io.aaps.copilot.domain.model.Forecast(
                    ts = it.ts,
                    horizonMinutes = it.horizon,
                    valueMmol = it.valueMmol,
                    ciLow = it.ciLow,
                    ciHigh = it.ciHigh,
                    modelVersion = it.modelVersion
                )
            }
            mergeForecasts(localForecasts, cloud)
        }.onFailure {
            auditLogger.warn("cloud_predict_failed", mapOf("error" to (it.message ?: "unknown")))
        }.getOrDefault(localForecasts)
    }

    private fun mergeForecasts(
        local: List<io.aaps.copilot.domain.model.Forecast>,
        cloud: List<io.aaps.copilot.domain.model.Forecast>
    ): List<io.aaps.copilot.domain.model.Forecast> {
        if (cloud.isEmpty()) return local
        val byHorizon = local.associateBy { it.horizonMinutes }.toMutableMap()
        cloud.forEach { byHorizon[it.horizonMinutes] = it }
        return byHorizon.values.sortedBy { it.horizonMinutes }
    }

    private suspend fun resolveActiveTempTarget(now: Long): Double? {
        val since = now - 3L * 60 * 60 * 1000
        val recentTargets = db.therapyDao().byTypeSince("temp_target", since)
        val active = recentTargets.lastOrNull() ?: return null
        val payload = runCatching {
            gson.fromJson(active.payloadJson, MutableMap::class.java)
        }.getOrNull() ?: return null
        val target = payload["targetBottom"]?.toString()?.toDoubleOrNull()
        val duration = payload["duration"]?.toString()?.toIntOrNull() ?: 0
        val activeUntil = active.timestamp + duration * 60_000L
        return target?.takeIf { now <= activeUntil }
    }

    private fun runtimeConfig(settings: AppSettings): RuleRuntimeConfig {
        val enabled = buildSet {
            add(AdaptiveTargetControllerRule.RULE_ID)
            if (settings.rulePostHypoEnabled) add("PostHypoReboundGuard.v1")
            if (settings.rulePatternEnabled) add("PatternAdaptiveTarget.v1")
            if (settings.ruleSegmentEnabled) add("SegmentProfileGuard.v1")
        }
        val priorities = mapOf(
            AdaptiveTargetControllerRule.RULE_ID to settings.adaptiveControllerPriority,
            "PostHypoReboundGuard.v1" to settings.rulePostHypoPriority,
            "PatternAdaptiveTarget.v1" to settings.rulePatternPriority,
            "SegmentProfileGuard.v1" to settings.ruleSegmentPriority
        )
        return RuleRuntimeConfig(enabledRuleIds = enabled, priorities = priorities)
    }

    private suspend fun maybeSendAdaptiveKeepaliveTempTarget(
        settings: AppSettings,
        nowTs: Long,
        dataFresh: Boolean,
        sensorBlocked: Boolean,
        activeTempTarget: Double?,
        actionsLast6h: Int,
        forecasts: List<Forecast>,
        baseTargetMmol: Double
    ) {
        if (settings.killSwitch) {
            auditLogger.info("adaptive_keepalive_skipped", mapOf("reason" to "kill_switch"))
            return
        }
        if (!dataFresh) {
            auditLogger.info("adaptive_keepalive_skipped", mapOf("reason" to "stale_data"))
            return
        }
        if (sensorBlocked) {
            auditLogger.info("adaptive_keepalive_skipped", mapOf("reason" to "sensor_blocked"))
            return
        }

        val lastAutoSentTs = db.actionCommandDao().latestTimestampByTypeAndStatusExcludingPrefix(
            type = "temp_target",
            status = NightscoutActionRepository.STATUS_SENT,
            excludedPrefix = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%"
        )
        if (lastAutoSentTs != null && nowTs - lastAutoSentTs < ADAPTIVE_KEEPALIVE_INTERVAL_MS) {
            return
        }

        val baseTarget = baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val proposal = alignTempTargetToBaseTarget(
            action = ActionProposal(
                type = "temp_target",
                targetMmol = baseTarget,
                durationMinutes = ADAPTIVE_KEEPALIVE_DURATION_MINUTES,
                reason = "adaptive_keepalive_30m"
            ),
            forecasts = forecasts,
            baseTargetMmol = baseTarget,
            sourceRuleId = AdaptiveTargetControllerRule.RULE_ID
        )
        if (activeTempTarget != null && abs(activeTempTarget - proposal.targetMmol) < 0.05) {
            auditLogger.info(
                "adaptive_keepalive_skipped",
                mapOf("reason" to "already_active", "activeTarget" to activeTempTarget)
            )
            return
        }

        val idempotencyKey = "${NightscoutActionRepository.KEEPALIVE_IDEMPOTENCY_PREFIX}${nowTs / ADAPTIVE_KEEPALIVE_INTERVAL_MS}"
        val command = ActionCommand(
            id = UUID.randomUUID().toString(),
            type = "temp_target",
            params = mapOf(
                "targetMmol" to proposal.targetMmol.toString(),
                "durationMinutes" to proposal.durationMinutes.toString(),
                "reason" to proposal.reason
            ),
            safetySnapshot = SafetySnapshot(
                killSwitch = settings.killSwitch,
                dataFresh = dataFresh,
                activeTempTargetMmol = activeTempTarget,
                actionsLast6h = actionsLast6h
            ),
            idempotencyKey = idempotencyKey
        )
        val sent = actionRepository.submitTempTarget(command)
        if (sent) {
            auditLogger.info(
                "adaptive_keepalive_sent",
                mapOf(
                    "targetMmol" to proposal.targetMmol,
                    "durationMinutes" to proposal.durationMinutes,
                    "reason" to proposal.reason
                )
            )
        } else {
            auditLogger.warn(
                "adaptive_keepalive_failed",
                mapOf(
                    "targetMmol" to proposal.targetMmol,
                    "durationMinutes" to proposal.durationMinutes
                )
            )
        }
    }

    private fun ruleCooldownMinutes(ruleId: String, settings: AppSettings): Int = when (ruleId) {
        AdaptiveTargetControllerRule.RULE_ID -> settings.adaptiveControllerRetargetMinutes.coerceIn(5, 30)
        "PostHypoReboundGuard.v1" -> settings.rulePostHypoCooldownMinutes
        "PatternAdaptiveTarget.v1" -> settings.rulePatternCooldownMinutes
        "SegmentProfileGuard.v1" -> settings.ruleSegmentCooldownMinutes
        else -> 0
    }

    private suspend fun isRuleInCooldown(ruleId: String, nowTs: Long, cooldownMinutes: Int): Boolean {
        if (cooldownMinutes <= 0) return false
        val since = nowTs - cooldownMinutes * 60_000L
        return db.ruleExecutionDao()
            .findByStateSince(ruleId = ruleId, state = RuleState.TRIGGERED.name, since = since)
            .isNotEmpty()
    }

    private fun isSensorBlocked(
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        nowTs: Long
    ): Boolean {
        val lastSensorState = therapy
            .asSequence()
            .filter { it.type.equals("sensor_state", ignoreCase = true) }
            .maxByOrNull { it.ts } ?: return false

        val blocked = lastSensorState.payload["blocked"]?.equals("true", ignoreCase = true) == true
        if (!blocked) return false
        return nowTs - lastSensorState.ts <= SENSOR_BLOCK_TTL_MS
    }

    private fun resolveTimeSlot(hour: Int): ProfileTimeSlot = when (hour) {
        in 0..5 -> ProfileTimeSlot.NIGHT
        in 6..11 -> ProfileTimeSlot.MORNING
        in 12..17 -> ProfileTimeSlot.AFTERNOON
        else -> ProfileTimeSlot.EVENING
    }

    companion object {
        private const val AUTOMATION_CYCLE_TIMEOUT_MS = 180_000L
        private const val AUTOMATION_STALL_WARN_MS = 180_000L
        private const val AUTOMATION_STEP_SLOW_MS = 10_000L
        private const val ISFCR_SNAPSHOT_FRESHNESS_MS = 10 * 60_000L
        private const val ISFCR_REALTIME_TIMEOUT_MS = 45_000L
        private const val ISFCR_REALTIME_SYNC_TIMEOUT_MS = 15_000L
        private const val ISFCR_REALTIME_IN_FLIGHT_STALE_MS = 180_000L
        private const val ISFCR_REALTIME_RETRY_BACKOFF_MS = 5 * 60_000L
        private const val ISFCR_SYNC_REFRESH_STALE_MS = 30 * 60_000L
        private const val SENSOR_BLOCK_TTL_MS = 30 * 60 * 1000L
        private const val MIN_TARGET_MMOL = 4.0
        private const val MAX_TARGET_MMOL = 10.0
        private const val ALIGN_GAIN = 0.35
        private const val MAX_ALIGN_STEP_MMOL = 1.20
        private const val ADAPTIVE_KEEPALIVE_INTERVAL_MS = 30 * 60 * 1000L
        private const val ADAPTIVE_KEEPALIVE_DURATION_MINUTES = 30
        private const val SENSOR_QUALITY_ROLLBACK_INTERVAL_MS = 20 * 60 * 1000L
        private const val SENSOR_QUALITY_ROLLBACK_DURATION_MINUTES = 30
        private const val SENSOR_QUALITY_ROLLBACK_IDEMPOTENCY_PREFIX = "sensor_quality_rollback:"
        private const val TELEMETRY_LOOKBACK_MS = 6 * 60 * 60 * 1000L
        private const val TELEMETRY_REPORT_LOOKBACK_MS = 72 * 60 * 60 * 1000L
        private const val CALCULATED_UAM_LOOKBACK_MINUTES = 120
        private const val CALCULATED_TO_ORIGINAL_MULTIPLIER = 2.4
        private const val UAM_PROCESSING_BUCKET_MS = 5 * 60 * 1000L
        private const val UAM_EVENT_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        private const val FORECAST_RETENTION_MS = 400L * 24 * 60 * 60 * 1000

        private const val COB_FORCE_BASE_THRESHOLD_G = 20.0
        private const val COB_FORCE_BASE_TARGET_MMOL = 4.2
        private val CUMULATIVE_ACTIVITY_KEYS = setOf(
            "steps_count",
            "distance_km",
            "active_minutes",
            "calories_active_kcal"
        )

        private const val COB_FORECAST_GAIN_5 = 0.006
        private const val COB_FORECAST_GAIN_30 = 0.012
        private const val COB_FORECAST_GAIN_60 = 0.018
        private const val COB_FORECAST_BIAS_MAX = 2.5

        private const val IOB_FORECAST_GAIN_5 = 0.14
        private const val IOB_FORECAST_GAIN_30 = 0.28
        private const val IOB_FORECAST_GAIN_60 = 0.42
        private const val IOB_FORECAST_BIAS_MAX = 4.0
        private const val COB_IOB_TELEMETRY_WEIGHT = 0.60
        private const val REAL_IOB_WEIGHT = 0.85
        private const val REAL_IOB_LOCAL_CONFIDENT_WEIGHT = 0.70
        private const val LOCAL_COB_IOB_LOOKBACK_MS = 12L * 60 * 60 * 1000
        private const val INSULIN_ONSET_FRACTION_THRESHOLD = 0.05
        private const val INSULIN_ONSET_MIN_MINUTES = 8.0
        private const val INSULIN_ONSET_MAX_MINUTES = 120.0
        private const val INSULIN_ONSET_MEAL_EXCLUSION_MS = 60L * 60 * 1000
        private const val INSULIN_ONSET_BASELINE_TOLERANCE_MS = 12L * 60 * 1000
        private const val INSULIN_ONSET_SEARCH_START_MS = 10L * 60 * 1000
        private const val INSULIN_ONSET_SEARCH_END_MS = 3L * 60 * 60 * 1000
        private const val INSULIN_ONSET_DROP_THRESHOLD_MMOL = 0.30
        private const val INSULIN_ONSET_DELTA5_THRESHOLD_MMOL = -0.04
        private const val ISFCR_SHADOW_BLEND_MIN = 0.25
        private const val ISFCR_SHADOW_BLEND_MAX = 0.65

        private const val FORECAST_BIAS_MIN = -4.0
        private const val FORECAST_BIAS_MAX = 3.0
        private const val CONTEXT_VALUE_BIAS_ABS_MAX = 1.0
        private const val CONTEXT_PATTERN_BIAS_MAX = 0.45
        private const val CONTEXT_CI_ADD_SENSOR_MAX = 0.55
        private const val CONTEXT_CI_ADD_AMBIGUITY_MAX = 0.40
        private const val CONTEXT_CI_ADD_ABS_MAX = 0.90
        private const val CONTEXT_LOW_QUALITY_MAX_DEVIATION_MMOL = 2.8
        private const val CONTEXT_LOW_GLUCOSE_GUARD_HARD_MMOL = 4.0
        private const val CONTEXT_LOW_GLUCOSE_GUARD_SOFT_MMOL = 4.6
        private const val COB_IOB_LOW_RISK_MMOL = 4.2
        private const val COB_IOB_HARD_LOW_MMOL = 3.8
        private const val COB_IOB_LOW_RISK_MIN_IOB = 2.0
        private const val COB_IOB_LOW_RISK_MIN_COB = 25.0
        private const val COB_IOB_EXTRA_GUARD_MIN_IOB = 3.0
        private const val COB_IOB_EXTRA_GUARD_MIN_COB = 35.0
        private const val COB_IOB_FALLING_SIGNAL_STEP_MMOL = 0.25
        private const val COB_BIAS_SUPPRESSION_SOFT = 0.80
        private const val COB_BIAS_SUPPRESSION_HARD = 0.55
        private const val COB_BIAS_SUPPRESSION_FALLING = 0.85
        private const val IOB_BIAS_BOOST_SOFT = 1.08
        private const val IOB_BIAS_BOOST_HARD = 1.20
        private const val IOB_BIAS_BOOST_FALLING = 1.05
        private const val IOB_LOW_GUARD_GAIN = 0.14
        private const val COB_LOW_GUARD_GAIN = 0.004
        private const val GLUCOSE_LOW_GUARD_GAIN = 0.30
        private const val LOW_GUARD_FALLING_MULTIPLIER = 1.10
        private const val LOW_GUARD_EXTRA_DOWN_MAX = 0.90
        private const val MIN_GLUCOSE_MMOL = 2.2
        private const val MAX_GLUCOSE_MMOL = 22.0
        private const val CALIBRATION_FORECAST_LIMIT = 4_000
        private const val CALIBRATION_GLUCOSE_LIMIT = 8_000
        private const val CALIBRATION_LOOKBACK_MS = 12L * 60 * 60 * 1000
        private const val CALIBRATION_MIN_AGE_MS = 2L * 60 * 1000
        private const val CALIBRATION_MATCH_TOLERANCE_MS = 2L * 60 * 1000
        private const val CALIBRATION_HALF_LIFE_MS = 90.0 * 60 * 1000
        private const val AI_CALIBRATION_MIN_CONFIDENCE = 0.45
        private const val AI_CALIBRATION_MAX_AGE_MS = 36L * 60 * 60 * 1000
        private const val AI_CALIBRATION_MIN_MATCHED_SAMPLES = 36.0
        private const val AI_CALIBRATION_BLOCK_RISK_LEVEL = 3.0
        private const val AI_CALIBRATION_FUTURE_SKEW_TOLERANCE_MS = 5L * 60 * 1000
        private const val AI_CALIBRATION_GAIN_SCALE_MIN = 0.80
        private const val AI_CALIBRATION_GAIN_SCALE_MAX = 1.50
        private const val AI_CALIBRATION_MAX_UP_SCALE_MIN = 0.80
        private const val AI_CALIBRATION_MAX_UP_SCALE_MAX = 1.80
        private const val AI_CALIBRATION_MAX_DOWN_SCALE_MIN = 0.80
        private const val AI_CALIBRATION_MAX_DOWN_SCALE_MAX = 1.50
        private const val SENSOR_QUALITY_FALSE_LOW_LEVEL_MMOL = 4.2
        private const val SENSOR_QUALITY_FALSE_LOW_DROP_MMOL = 1.4
        private const val SENSOR_QUALITY_DELTA_BLOCK_MMOL5 = 1.6
        private const val SENSOR_QUALITY_NOISE_BLOCK_STD = 0.95
        private const val SENSOR_QUALITY_ROLLBACK_MIN_DELTA_MMOL = 0.20
        private const val REAL_PROFILE_HISTORY_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
        private const val REAL_PROFILE_READ_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
        private const val REAL_PROFILE_PUBLISH_KEEPALIVE_MS = 60L * 60 * 1000
        private const val REAL_PROFILE_MIN_EVENT_AGE_MS = 45L * 60 * 1000
        private const val REAL_PROFILE_MEAL_EXCLUSION_MS = 45L * 60 * 1000
        private const val REAL_PROFILE_MEAL_EXCLUSION_CARBS_G = 35.0
        private const val REAL_PROFILE_IMPLICIT_IOB_STEP_MIN_UNITS = 0.20
        private const val REAL_PROFILE_IMPLICIT_IOB_LEVEL_MIN_UNITS = 0.35
        private const val REAL_PROFILE_IMPLICIT_MERGE_WINDOW_MS = 30L * 60 * 1000
        private const val REAL_PROFILE_IMPLICIT_BACKDATE_MS = 12L * 60 * 1000
        private const val REAL_PROFILE_IMPLICIT_CONFIRM_STEPS = 3
        private const val REAL_PROFILE_IMPLICIT_CONFIRM_WINDOW_MS = 20L * 60 * 1000
        private const val REAL_PROFILE_IMPLICIT_CONFIRM_DROP_MIN_UNITS = 0.05
        private const val REAL_PROFILE_BASELINE_TOLERANCE_MS = 15L * 60 * 1000
        private const val REAL_PROFILE_WINDOW_START_MS = 10L * 60 * 1000
        private const val REAL_PROFILE_WINDOW_END_MS = 4L * 60 * 60 * 1000
        private const val REAL_PROFILE_MIN_BOLUS_UNITS = 0.5
        private const val REAL_PROFILE_ONSET_DROP_THRESHOLD_MMOL = 0.25
        private const val REAL_PROFILE_ONSET_DELTA5_THRESHOLD_MMOL = -0.03
        private const val REAL_PROFILE_PEAK_DROP_THRESHOLD_MMOL = 0.35
        private const val REAL_PROFILE_MIN_PEAK_MINUTES = 25.0
        private const val REAL_PROFILE_MAX_PEAK_MINUTES = 300.0
        private const val REAL_PROFILE_MIN_SCALE = 0.65
        private const val REAL_PROFILE_MAX_SCALE = 1.80
        private const val REAL_PROFILE_CURVE_STEP_MINUTES = 5
        private const val REAL_PROFILE_CURVE_MAX_MINUTES = 360
        private const val REAL_PROFILE_SOURCE = "copilot_real_insulin_profile"
        private const val REAL_PROFILE_KEY_PREFIX = "insulin_profile_real_"
        private const val REAL_PROFILE_CURVE_KEY = "insulin_profile_real_curve_compact"
        private const val REAL_PROFILE_UPDATED_TS_KEY = "insulin_profile_real_updated_ts"
        private const val REAL_PROFILE_CONFIDENCE_KEY = "insulin_profile_real_confidence"
        private const val REAL_PROFILE_SAMPLES_KEY = "insulin_profile_real_samples"
        private const val REAL_PROFILE_ONSET_KEY = "insulin_profile_real_onset_min"
        private const val REAL_PROFILE_PEAK_KEY = "insulin_profile_real_peak_min"
        private const val REAL_PROFILE_SCALE_KEY = "insulin_profile_real_scale"
        private const val REAL_PROFILE_STATUS_KEY = "insulin_profile_real_status"
        private const val REAL_PROFILE_SOURCE_PROFILE_KEY = "insulin_profile_real_source_profile"
        private const val REAL_PROFILE_PUBLISHED_TS_KEY = "insulin_profile_real_published_ts"
        private const val REAL_PROFILE_ALGO_VERSION_KEY = "insulin_profile_real_algo_version"
        private const val REAL_PROFILE_ALGO_VERSION = "v2"

        private data class CalibrationConfig(
            val minSamples: Int,
            val gain: Double,
            val maxUp: Double,
            val maxDown: Double,
            val minBucketSamples: Int,
            val bucketBlend: Double
        )

        internal fun evaluateSensorQualityStatic(
            glucose: List<GlucosePoint>,
            nowTs: Long,
            staleMaxMinutes: Int
        ): SensorQualityAssessment {
            if (glucose.isEmpty()) {
                return SensorQualityAssessment(
                    score = 0.0,
                    blocked = true,
                    reason = "no_glucose",
                    suspectFalseLow = false,
                    delta5Mmol = null,
                    noiseStd5Mmol = null,
                    gapMinutes = staleMaxMinutes.toDouble()
                )
            }

            val sorted = glucose.sortedBy { it.ts }
            val latest = sorted.last()
            val gapMinutes = ((nowTs - latest.ts).coerceAtLeast(0L) / 60_000.0)
            val staleLimit = staleMaxMinutes.coerceAtLeast(8).toDouble()

            var latestDelta5: Double? = null
            val recentDeltas = mutableListOf<Double>()
            val recent = sorted.takeLast(12)
            for (idx in 1 until recent.size) {
                val prev = recent[idx - 1]
                val current = recent[idx]
                val dtMin = (current.ts - prev.ts) / 60_000.0
                if (dtMin !in 2.0..15.0) continue
                val delta5 = (current.valueMmol - prev.valueMmol) / (dtMin / 5.0)
                recentDeltas += delta5
                if (current.ts == latest.ts) {
                    latestDelta5 = delta5
                }
            }
            if (latestDelta5 == null) {
                val prev = sorted.dropLast(1).lastOrNull { candidate ->
                    val dtMin = (latest.ts - candidate.ts) / 60_000.0
                    dtMin in 2.0..15.0
                }
                if (prev != null) {
                    val dtMin = (latest.ts - prev.ts) / 60_000.0
                    latestDelta5 = (latest.valueMmol - prev.valueMmol) / (dtMin / 5.0)
                }
            }

            val noiseStd = if (recentDeltas.size >= 3) {
                val mean = recentDeltas.average()
                val variance = recentDeltas
                    .map { delta -> (delta - mean) * (delta - mean) }
                    .average()
                sqrt(variance).coerceAtLeast(0.0)
            } else {
                0.0
            }

            val sensorErrorQuality = latest.quality == DataQuality.SENSOR_ERROR
            val previousWindowAvg = sorted.dropLast(1).takeLast(3).map { it.valueMmol }
                .takeIf { it.isNotEmpty() }
                ?.average()
            val suspectFalseLow = latest.valueMmol <= SENSOR_QUALITY_FALSE_LOW_LEVEL_MMOL &&
                previousWindowAvg != null &&
                (previousWindowAvg - latest.valueMmol) >= SENSOR_QUALITY_FALSE_LOW_DROP_MMOL &&
                (latestDelta5 ?: 0.0) <= -0.75

            val staleBlocked = gapMinutes > staleLimit
            val rapidDeltaBlocked = abs(latestDelta5 ?: 0.0) >= SENSOR_QUALITY_DELTA_BLOCK_MMOL5
            val noisyBlocked = noiseStd >= SENSOR_QUALITY_NOISE_BLOCK_STD &&
                abs(latestDelta5 ?: 0.0) >= 0.70

            var score = 1.0
            if (gapMinutes > staleLimit / 2.0) {
                val ratio = ((gapMinutes - staleLimit / 2.0) / staleLimit).coerceIn(0.0, 1.0)
                score -= 0.45 * ratio
            }
            val deltaAbs = abs(latestDelta5 ?: 0.0)
            if (deltaAbs > 0.60) {
                val ratio = ((deltaAbs - 0.60) / 1.10).coerceIn(0.0, 1.0)
                score -= 0.30 * ratio
            }
            if (noiseStd > 0.45) {
                val ratio = ((noiseStd - 0.45) / 0.80).coerceIn(0.0, 1.0)
                score -= 0.25 * ratio
            }
            if (suspectFalseLow) score -= 0.30
            if (sensorErrorQuality) score -= 0.35
            score = score.coerceIn(0.0, 1.0)

            val blocked = sensorErrorQuality || staleBlocked || suspectFalseLow || rapidDeltaBlocked || noisyBlocked
            val reason = when {
                sensorErrorQuality -> "sensor_error"
                staleBlocked -> "stale_gap"
                suspectFalseLow -> "suspect_false_low"
                rapidDeltaBlocked -> "rapid_delta"
                noisyBlocked -> "high_noise"
                else -> "ok"
            }

            return SensorQualityAssessment(
                score = score,
                blocked = blocked,
                reason = reason,
                suspectFalseLow = suspectFalseLow,
                delta5Mmol = latestDelta5,
                noiseStd5Mmol = noiseStd,
                gapMinutes = gapMinutes
            )
        }

        internal fun shouldSendSensorQualityRollbackStatic(
            activeTempTarget: Double?,
            baseTargetMmol: Double,
            assessment: SensorQualityAssessment
        ): Boolean {
            if (!assessment.blocked) return false
            val active = activeTempTarget ?: return false
            val base = baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
            val delta = abs(active - base)
            if (delta < SENSOR_QUALITY_ROLLBACK_MIN_DELTA_MMOL) return false
            if (assessment.suspectFalseLow && active <= base) return false
            return true
        }

        internal fun resolveIsfCrRuntimeGateStatic(
            snapshot: IsfCrRealtimeSnapshot?,
            confidenceThreshold: Double
        ): IsfCrRuntimeGate {
            if (snapshot == null) {
                return IsfCrRuntimeGate(
                    applyToRuntime = false,
                    reason = "no_snapshot"
                )
            }
            return when (snapshot.mode.name) {
                "SHADOW" -> IsfCrRuntimeGate(
                    applyToRuntime = false,
                    reason = "shadow_mode"
                )

                "FALLBACK" -> IsfCrRuntimeGate(
                    applyToRuntime = false,
                    reason = "fallback_mode"
                )

                "ACTIVE" -> {
                    val threshold = confidenceThreshold.coerceIn(0.2, 0.95)
                    if (snapshot.confidence >= threshold) {
                        IsfCrRuntimeGate(
                            applyToRuntime = true,
                            reason = "active_confident"
                        )
                    } else {
                        IsfCrRuntimeGate(
                            applyToRuntime = false,
                            reason = "low_confidence"
                        )
                    }
                }

                else -> IsfCrRuntimeGate(
                    applyToRuntime = false,
                    reason = "unknown_mode"
                )
            }
        }

        internal fun resolveIsfCrOverrideBlendWeightStatic(
            snapshot: IsfCrRealtimeSnapshot?,
            runtimeGate: IsfCrRuntimeGate,
            confidenceThreshold: Double
        ): Double? {
            val current = snapshot ?: return null
            val threshold = confidenceThreshold.coerceIn(0.2, 0.95)
            if (current.confidence < threshold) return null
            if (runtimeGate.applyToRuntime) return 1.0
            if (current.mode == IsfCrRuntimeMode.SHADOW) {
                val confidenceScale = ((current.confidence - threshold) / (1.0 - threshold)).coerceIn(0.0, 1.0)
                return (
                    ISFCR_SHADOW_BLEND_MIN +
                        (ISFCR_SHADOW_BLEND_MAX - ISFCR_SHADOW_BLEND_MIN) * confidenceScale
                    ).coerceIn(ISFCR_SHADOW_BLEND_MIN, ISFCR_SHADOW_BLEND_MAX)
            }
            return null
        }

        internal fun evaluateIsfCrShadowActivationStatic(
            samples: List<IsfCrShadowDiffSample>,
            minSamples: Int,
            minMeanConfidence: Double,
            maxMeanAbsIsfDeltaPct: Double,
            maxMeanAbsCrDeltaPct: Double
        ): IsfCrShadowActivationAssessment {
            val safeMinSamples = minSamples.coerceIn(12, 288)
            if (samples.size < safeMinSamples) {
                return IsfCrShadowActivationAssessment(
                    eligible = false,
                    reason = "insufficient_samples",
                    sampleCount = samples.size,
                    meanConfidence = 0.0,
                    meanAbsIsfDeltaPct = 0.0,
                    meanAbsCrDeltaPct = 0.0
                )
            }

            val meanConfidence = samples.map { it.confidence }.average().coerceIn(0.0, 1.0)
            val meanAbsIsfDeltaPct = samples.map { abs(it.isfDeltaPct) }.average().coerceIn(0.0, 200.0)
            val meanAbsCrDeltaPct = samples.map { abs(it.crDeltaPct) }.average().coerceIn(0.0, 200.0)
            val safeMinConfidence = minMeanConfidence.coerceIn(0.2, 0.95)
            val safeMaxIsf = maxMeanAbsIsfDeltaPct.coerceIn(5.0, 100.0)
            val safeMaxCr = maxMeanAbsCrDeltaPct.coerceIn(5.0, 100.0)

            val reason = when {
                meanConfidence < safeMinConfidence -> "low_mean_confidence"
                meanAbsIsfDeltaPct > safeMaxIsf -> "isf_delta_out_of_bounds"
                meanAbsCrDeltaPct > safeMaxCr -> "cr_delta_out_of_bounds"
                else -> "eligible"
            }
            return IsfCrShadowActivationAssessment(
                eligible = reason == "eligible",
                reason = reason,
                sampleCount = samples.size,
                meanConfidence = meanConfidence,
                meanAbsIsfDeltaPct = meanAbsIsfDeltaPct,
                meanAbsCrDeltaPct = meanAbsCrDeltaPct
            )
        }

        internal fun evaluateIsfCrDayTypeStabilityStatic(
            samples: List<IsfCrDayTypeStabilitySample>,
            minSamples: Int,
            minMeanSameDayTypeRatio: Double,
            maxSparseRatePct: Double
        ): IsfCrDayTypeStabilityAssessment {
            val safeMinSamples = minSamples.coerceIn(12, 288)
            if (samples.size < safeMinSamples) {
                return IsfCrDayTypeStabilityAssessment(
                    eligible = false,
                    reason = "insufficient_day_type_samples",
                    sampleCount = samples.size,
                    meanIsfSameDayTypeRatio = 0.0,
                    meanCrSameDayTypeRatio = 0.0,
                    isfSparseRatePct = 0.0,
                    crSparseRatePct = 0.0
                )
            }

            val meanIsfRatio = samples.map { it.isfSameDayTypeRatio }.average().coerceIn(0.0, 1.0)
            val meanCrRatio = samples.map { it.crSameDayTypeRatio }.average().coerceIn(0.0, 1.0)
            val isfSparseRate = samples.count { it.isfSparseFlag } * 100.0 / samples.size.toDouble()
            val crSparseRate = samples.count { it.crSparseFlag } * 100.0 / samples.size.toDouble()
            val safeMinRatio = minMeanSameDayTypeRatio.coerceIn(0.0, 1.0)
            val safeMaxSparseRate = maxSparseRatePct.coerceIn(0.0, 100.0)

            val reason = when {
                meanIsfRatio < safeMinRatio -> "isf_day_type_ratio_low"
                meanCrRatio < safeMinRatio -> "cr_day_type_ratio_low"
                isfSparseRate > safeMaxSparseRate -> "isf_day_type_sparse_rate_high"
                crSparseRate > safeMaxSparseRate -> "cr_day_type_sparse_rate_high"
                else -> "eligible"
            }
            return IsfCrDayTypeStabilityAssessment(
                eligible = reason == "eligible",
                reason = reason,
                sampleCount = samples.size,
                meanIsfSameDayTypeRatio = meanIsfRatio,
                meanCrSameDayTypeRatio = meanCrRatio,
                isfSparseRatePct = isfSparseRate,
                crSparseRatePct = crSparseRate
            )
        }

        internal fun evaluateIsfCrSensorQualityStatic(
            samples: List<IsfCrSensorQualitySample>,
            minSamples: Int,
            minMeanQualityScore: Double,
            minMeanSensorFactor: Double,
            maxMeanWearPenalty: Double,
            maxSensorAgeHighRatePct: Double,
            maxSuspectFalseLowRatePct: Double
        ): IsfCrSensorQualityAssessment {
            val safeMinSamples = minSamples.coerceIn(12, 288)
            if (samples.size < safeMinSamples) {
                return IsfCrSensorQualityAssessment(
                    eligible = false,
                    reason = "insufficient_sensor_quality_samples",
                    sampleCount = samples.size,
                    meanQualityScore = 0.0,
                    meanSensorFactor = 0.0,
                    meanWearPenalty = 0.0,
                    sensorAgeHighRatePct = 0.0,
                    suspectFalseLowRatePct = 0.0
                )
            }

            val meanQualityScore = samples.map { it.qualityScore }.average().coerceIn(0.0, 1.0)
            val meanSensorFactor = samples.map { it.sensorFactor }.average().coerceIn(0.0, 1.0)
            val meanWearPenalty = samples.map { it.wearConfidencePenalty }.average().coerceIn(0.0, 1.0)
            val sensorAgeHighRate = samples.count { it.sensorAgeHighFlag } * 100.0 / samples.size.toDouble()
            val suspectFalseLowRate = samples.count { it.suspectFalseLowFlag } * 100.0 / samples.size.toDouble()
            val safeMinQuality = minMeanQualityScore.coerceIn(0.0, 1.0)
            val safeMinSensorFactor = minMeanSensorFactor.coerceIn(0.0, 1.0)
            val safeMaxWearPenalty = maxMeanWearPenalty.coerceIn(0.0, 1.0)
            val safeMaxSensorAgeHighRate = maxSensorAgeHighRatePct.coerceIn(0.0, 100.0)
            val safeMaxSuspectFalseLowRate = maxSuspectFalseLowRatePct.coerceIn(0.0, 100.0)

            val reason = when {
                meanQualityScore < safeMinQuality -> "sensor_quality_score_low"
                meanSensorFactor < safeMinSensorFactor -> "sensor_factor_low"
                meanWearPenalty > safeMaxWearPenalty -> "wear_penalty_high"
                sensorAgeHighRate > safeMaxSensorAgeHighRate -> "sensor_age_high_rate"
                suspectFalseLowRate > safeMaxSuspectFalseLowRate -> "sensor_suspect_false_low_rate"
                else -> "eligible"
            }
            return IsfCrSensorQualityAssessment(
                eligible = reason == "eligible",
                reason = reason,
                sampleCount = samples.size,
                meanQualityScore = meanQualityScore,
                meanSensorFactor = meanSensorFactor,
                meanWearPenalty = meanWearPenalty,
                sensorAgeHighRatePct = sensorAgeHighRate,
                suspectFalseLowRatePct = suspectFalseLowRate
            )
        }

        internal fun evaluateIsfCrDailyQualityGateStatic(
            matchedSamples: Int?,
            mae30Mmol: Double?,
            mae60Mmol: Double?,
            hypoRatePct24h: Double?,
            ciCoverage30Pct: Double?,
            ciCoverage60Pct: Double?,
            ciWidth30Mmol: Double?,
            ciWidth60Mmol: Double?,
            minDailyMatchedSamples: Int,
            maxDailyMae30Mmol: Double,
            maxDailyMae60Mmol: Double,
            maxHypoRatePct: Double,
            minDailyCiCoverage30Pct: Double,
            minDailyCiCoverage60Pct: Double,
            maxDailyCiWidth30Mmol: Double,
            maxDailyCiWidth60Mmol: Double
        ): IsfCrDailyQualityGateAssessment {
            val safeMinMatched = minDailyMatchedSamples.coerceIn(24, 720)
            val safeMaxMae30 = maxDailyMae30Mmol.coerceIn(0.3, 4.0)
            val safeMaxMae60 = maxDailyMae60Mmol.coerceIn(0.5, 6.0)
            val safeMaxHypoRate = maxHypoRatePct.coerceIn(0.5, 30.0)
            val safeMinCoverage30 = minDailyCiCoverage30Pct.coerceIn(20.0, 99.0)
            val safeMinCoverage60 = minDailyCiCoverage60Pct.coerceIn(20.0, 99.0)
            val safeMaxCiWidth30 = maxDailyCiWidth30Mmol.coerceIn(0.3, 6.0)
            val safeMaxCiWidth60 = maxDailyCiWidth60Mmol.coerceIn(0.5, 8.0)

            val reason = when {
                matchedSamples == null || mae30Mmol == null || mae60Mmol == null -> "daily_report_missing"
                matchedSamples < safeMinMatched -> "daily_report_sparse"
                mae30Mmol > safeMaxMae30 -> "daily_mae30_out_of_bounds"
                mae60Mmol > safeMaxMae60 -> "daily_mae60_out_of_bounds"
                hypoRatePct24h == null -> "hypo_rate_missing"
                hypoRatePct24h > safeMaxHypoRate -> "daily_hypo_rate_out_of_bounds"
                ciCoverage30Pct == null || ciCoverage60Pct == null -> "daily_ci_coverage_missing"
                ciCoverage30Pct < safeMinCoverage30 -> "daily_ci_coverage30_out_of_bounds"
                ciCoverage60Pct < safeMinCoverage60 -> "daily_ci_coverage60_out_of_bounds"
                ciWidth30Mmol == null || ciWidth60Mmol == null -> "daily_ci_width_missing"
                ciWidth30Mmol > safeMaxCiWidth30 -> "daily_ci_width30_out_of_bounds"
                ciWidth60Mmol > safeMaxCiWidth60 -> "daily_ci_width60_out_of_bounds"
                else -> "eligible"
            }
            return IsfCrDailyQualityGateAssessment(
                eligible = reason == "eligible",
                reason = reason,
                matchedSamples = matchedSamples,
                mae30Mmol = mae30Mmol,
                mae60Mmol = mae60Mmol,
                hypoRatePct24h = hypoRatePct24h,
                ciCoverage30Pct = ciCoverage30Pct,
                ciCoverage60Pct = ciCoverage60Pct,
                ciWidth30Mmol = ciWidth30Mmol,
                ciWidth60Mmol = ciWidth60Mmol
            )
        }

        internal fun evaluateIsfCrRollingQualityGateStatic(
            windows: List<IsfCrRollingQualityWindowAssessment>,
            minRequiredWindows: Int
        ): IsfCrRollingQualityGateAssessment {
            val required = minRequiredWindows.coerceIn(1, windows.size.coerceAtLeast(1))
            val evaluated = windows.count { it.available }
            val passed = windows.count { it.available && it.eligible }
            if (evaluated < required) {
                return IsfCrRollingQualityGateAssessment(
                    eligible = false,
                    reason = "rolling_windows_insufficient",
                    requiredWindowCount = required,
                    evaluatedWindowCount = evaluated,
                    passedWindowCount = passed,
                    windows = windows
                )
            }

            val failedWindow = windows.firstOrNull { it.available && !it.eligible }
            if (failedWindow != null) {
                return IsfCrRollingQualityGateAssessment(
                    eligible = false,
                    reason = "rolling_${failedWindow.days}d_${failedWindow.reason}",
                    requiredWindowCount = required,
                    evaluatedWindowCount = evaluated,
                    passedWindowCount = passed,
                    windows = windows
                )
            }

            return IsfCrRollingQualityGateAssessment(
                eligible = true,
                reason = "eligible",
                requiredWindowCount = required,
                evaluatedWindowCount = evaluated,
                passedWindowCount = passed,
                windows = windows
            )
        }

        internal fun evaluateIsfCrDailyRiskGateStatic(
            riskLevel: Int?,
            blockedRiskLevel: Int
        ): IsfCrDailyRiskGateAssessment {
            val safeBlockedLevel = blockedRiskLevel.coerceIn(2, 3)
            val normalizedRiskLevel = (riskLevel ?: 0).coerceIn(0, 3)
            return when {
                normalizedRiskLevel <= 0 -> IsfCrDailyRiskGateAssessment(
                    eligible = true,
                    reason = "daily_risk_missing_or_unknown",
                    riskLevel = normalizedRiskLevel
                )

                normalizedRiskLevel >= safeBlockedLevel -> IsfCrDailyRiskGateAssessment(
                    eligible = false,
                    reason = "daily_risk_high",
                    riskLevel = normalizedRiskLevel
                )

                else -> IsfCrDailyRiskGateAssessment(
                    eligible = true,
                    reason = "eligible",
                    riskLevel = normalizedRiskLevel
                )
            }
        }

        internal fun resolveIsfCrDailyRiskLevelSourceStatic(
            riskLevel: Int?,
            fallbackUsed: Boolean?
        ): String {
            val hasResolvedRisk = riskLevel != null && riskLevel > 0
            if (!hasResolvedRisk) return "missing_or_unknown"
            return if (fallbackUsed == true) "text_fallback" else "numeric"
        }

        internal fun parseIsfCrQualityRiskLevelFromTextStatic(raw: String?): Int? {
            val text = raw?.trim()?.uppercase(Locale.ROOT)
            if (text.isNullOrEmpty()) return null
            val compact = text
                .replace(Regex("[^A-ZА-Я0-9]+"), " ")
                .trim()
            compact.toIntOrNull()?.let { numeric ->
                if (numeric in 0..3) return numeric
            }
            return when {
                compact.contains("HIGH") || compact.contains("ВЫСОК") -> 3
                compact.contains("MEDIUM") || compact.contains("СРЕДН") -> 2
                compact.contains("LOW") || compact.contains("НИЗК") -> 1
                compact.contains("UNKNOWN") || compact.contains("НЕИЗВЕСТ") -> 0
                else -> null
            }
        }

        internal fun normalizeForecastSetStatic(forecasts: List<Forecast>): List<Forecast> {
            if (forecasts.isEmpty()) return emptyList()
            val grouped = forecasts.groupBy { it.horizonMinutes }
            return grouped.mapNotNull { (_, rows) ->
                rows.sortedWith(
                    compareByDescending<Forecast> { it.ts }
                        .thenByDescending { forecast -> forecast.modelVersion.contains("cloud", ignoreCase = true) }
                        .thenBy { forecast -> abs(forecast.ciHigh - forecast.ciLow) }
                ).firstOrNull()
            }.sortedBy { it.horizonMinutes }
        }

        private fun calibrationConfig(horizonMinutes: Int): CalibrationConfig? = when (horizonMinutes) {
            5 -> CalibrationConfig(
                minSamples = 24,
                gain = 0.35,
                maxUp = 0.35,
                maxDown = 0.25,
                minBucketSamples = 14,
                bucketBlend = 0.45
            )
            30 -> CalibrationConfig(
                minSamples = 18,
                gain = 0.45,
                maxUp = 0.70,
                maxDown = 0.45,
                minBucketSamples = 10,
                bucketBlend = 0.55
            )
            60 -> CalibrationConfig(
                minSamples = 12,
                gain = 0.55,
                maxUp = 1.10,
                maxDown = 0.65,
                minBucketSamples = 8,
                bucketBlend = 0.65
            )
            else -> null
        }

        private fun applyAiCalibrationTuning(
            base: CalibrationConfig,
            tuning: CalibrationAiTuning?
        ): CalibrationConfig {
            if (tuning == null) return base
            return base.copy(
                gain = (base.gain * tuning.gainScale).coerceIn(0.15, 1.20),
                maxUp = (base.maxUp * tuning.maxUpScale).coerceIn(0.10, FORECAST_BIAS_MAX),
                maxDown = (base.maxDown * tuning.maxDownScale).coerceIn(0.10, abs(FORECAST_BIAS_MIN))
            )
        }

        internal fun resolveAiCalibrationTuningStatic(
            latestTelemetry: Map<String, Double?>,
            nowTs: Long
        ): Map<Int, CalibrationAiTuning> {
            val applyFlag = latestTelemetry["daily_report_ai_opt_apply_flag"] ?: 0.0
            if (applyFlag < 0.5) return emptyMap()

            val confidence = latestTelemetry["daily_report_ai_opt_confidence"] ?: 0.0
            if (confidence < AI_CALIBRATION_MIN_CONFIDENCE) return emptyMap()

            val generatedTs = latestTelemetry["daily_report_ai_opt_generated_ts"]
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.toLong()
                ?: return emptyMap()
            if (generatedTs > nowTs + AI_CALIBRATION_FUTURE_SKEW_TOLERANCE_MS) return emptyMap()
            val ageMs = nowTs - generatedTs
            if (ageMs !in 0..AI_CALIBRATION_MAX_AGE_MS) return emptyMap()

            val matchedSamples = (latestTelemetry["daily_report_matched_samples"] ?: 0.0)
                .coerceAtLeast(0.0)
            if (matchedSamples < AI_CALIBRATION_MIN_MATCHED_SAMPLES) return emptyMap()

            val riskLevel = (latestTelemetry["daily_report_isfcr_quality_risk_level"] ?: 0.0)
                .coerceAtLeast(0.0)
            if (riskLevel >= AI_CALIBRATION_BLOCK_RISK_LEVEL) return emptyMap()

            return buildMap {
                put(
                    5,
                    CalibrationAiTuning(
                        gainScale = (latestTelemetry["daily_report_ai_opt_gain_scale_5m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_GAIN_SCALE_MIN, AI_CALIBRATION_GAIN_SCALE_MAX),
                        maxUpScale = (latestTelemetry["daily_report_ai_opt_max_up_scale_5m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_MAX_UP_SCALE_MIN, AI_CALIBRATION_MAX_UP_SCALE_MAX),
                        maxDownScale = (latestTelemetry["daily_report_ai_opt_max_down_scale_5m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_MAX_DOWN_SCALE_MIN, AI_CALIBRATION_MAX_DOWN_SCALE_MAX)
                    )
                )
                put(
                    30,
                    CalibrationAiTuning(
                        gainScale = (latestTelemetry["daily_report_ai_opt_gain_scale_30m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_GAIN_SCALE_MIN, AI_CALIBRATION_GAIN_SCALE_MAX),
                        maxUpScale = (latestTelemetry["daily_report_ai_opt_max_up_scale_30m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_MAX_UP_SCALE_MIN, AI_CALIBRATION_MAX_UP_SCALE_MAX),
                        maxDownScale = (latestTelemetry["daily_report_ai_opt_max_down_scale_30m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_MAX_DOWN_SCALE_MIN, AI_CALIBRATION_MAX_DOWN_SCALE_MAX)
                    )
                )
                put(
                    60,
                    CalibrationAiTuning(
                        gainScale = (latestTelemetry["daily_report_ai_opt_gain_scale_60m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_GAIN_SCALE_MIN, AI_CALIBRATION_GAIN_SCALE_MAX),
                        maxUpScale = (latestTelemetry["daily_report_ai_opt_max_up_scale_60m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_MAX_UP_SCALE_MIN, AI_CALIBRATION_MAX_UP_SCALE_MAX),
                        maxDownScale = (latestTelemetry["daily_report_ai_opt_max_down_scale_60m"] ?: 1.0)
                            .coerceIn(AI_CALIBRATION_MAX_DOWN_SCALE_MIN, AI_CALIBRATION_MAX_DOWN_SCALE_MAX)
                    )
                )
            }.filterValues { tuning ->
                abs(tuning.gainScale - 1.0) > 1e-6 ||
                    abs(tuning.maxUpScale - 1.0) > 1e-6 ||
                    abs(tuning.maxDownScale - 1.0) > 1e-6
            }
        }

        private fun forecastValueBucket(mmol: Double): Int = when {
            mmol < 5.0 -> 0
            mmol < 8.0 -> 1
            else -> 2
        }

        internal fun applyRecentForecastCalibrationBiasStatic(
            forecasts: List<Forecast>,
            history: List<ForecastCalibrationPoint>,
            aiTuning: Map<Int, CalibrationAiTuning> = emptyMap()
        ): List<Forecast> {
            if (forecasts.isEmpty() || history.isEmpty()) return forecasts
            val historyByHorizon = history.groupBy { it.horizonMinutes }
            return forecasts.map { forecast ->
                val cfg = applyAiCalibrationTuning(
                    base = calibrationConfig(forecast.horizonMinutes) ?: return@map forecast,
                    tuning = aiTuning[forecast.horizonMinutes]
                )
                val points = historyByHorizon[forecast.horizonMinutes]
                    .orEmpty()
                    .filter { it.ageMs in CALIBRATION_MIN_AGE_MS..CALIBRATION_LOOKBACK_MS }
                if (points.size < cfg.minSamples) return@map forecast

                var sumW = 0.0
                var sumErr = 0.0
                val bucketErrSum = DoubleArray(3)
                val bucketWeightSum = DoubleArray(3)
                val bucketCount = IntArray(3)
                points.forEach { point ->
                    val age = point.ageMs.coerceAtLeast(0L).toDouble()
                    val weight = exp(-age / CALIBRATION_HALF_LIFE_MS)
                    sumW += weight
                    sumErr += weight * point.errorMmol
                    if (point.predictedMmol.isFinite()) {
                        val bucket = forecastValueBucket(point.predictedMmol)
                        bucketErrSum[bucket] += weight * point.errorMmol
                        bucketWeightSum[bucket] += weight
                        bucketCount[bucket] += 1
                    }
                }
                if (sumW <= 1e-9) return@map forecast

                val overallMeanErr = sumErr / sumW
                val bucket = forecastValueBucket(forecast.valueMmol)
                val bucketMeanErr = if (
                    bucketCount[bucket] >= cfg.minBucketSamples &&
                    bucketWeightSum[bucket] > 1e-9
                ) {
                    bucketErrSum[bucket] / bucketWeightSum[bucket]
                } else {
                    null
                }
                val blendedErr = if (bucketMeanErr != null) {
                    overallMeanErr * (1.0 - cfg.bucketBlend) + bucketMeanErr * cfg.bucketBlend
                } else {
                    overallMeanErr
                }
                val bias = (blendedErr * cfg.gain).coerceIn(-cfg.maxDown, cfg.maxUp)
                if (abs(bias) < 0.02) return@map forecast

                val shiftedValue = (forecast.valueMmol + bias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedLow = (forecast.ciLow + bias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedHigh = (forecast.ciHigh + bias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                if (shiftedLow > shiftedValue) shiftedLow = shiftedValue
                if (shiftedHigh < shiftedValue) shiftedHigh = shiftedValue
                val version = if (forecast.modelVersion.contains("|calib_v1")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|calib_v1"
                }
                forecast.copy(
                    valueMmol = shiftedValue,
                    ciLow = shiftedLow,
                    ciHigh = shiftedHigh,
                    modelVersion = version
                )
            }
        }

        internal fun extractForecastDecompositionSnapshotStatic(
            diagnostics: HybridPredictionEngine.V3Diagnostics?,
            localForecasts: List<Forecast>
        ): ForecastDecompositionSnapshot? {
            if (diagnostics == null) return null

            fun sum60(values: List<Double>): Double = values.drop(1).take(12).sum()

            val trend60 = sum60(diagnostics.trendStep)
            val therapy60 = diagnostics.therapyCumClamped.getOrNull(12)
                ?: diagnostics.therapyCumClamped.lastOrNull()
                ?: sum60(diagnostics.therapyStep)
            val uam60 = sum60(diagnostics.uamStep)
            val modelVersion = localForecasts.maxByOrNull { it.horizonMinutes }?.modelVersion
                ?: "local-hybrid-v3"

            return ForecastDecompositionSnapshot(
                trend60Mmol = trend60,
                therapy60Mmol = therapy60,
                uam60Mmol = uam60,
                residualRoc0Mmol5 = diagnostics.residualRoc0,
                sigmaEMmol5 = diagnostics.arSigmaE,
                kfSigmaGMmol = diagnostics.kfSigmaG,
                modelVersion = modelVersion
            )
        }

        internal fun applyContextFactorForecastBiasStatic(
            forecasts: List<Forecast>,
            telemetry: Map<String, Double?>,
            latestGlucoseMmol: Double,
            pattern: io.aaps.copilot.domain.model.PatternWindow?
        ): List<Forecast> {
            if (forecasts.isEmpty()) return forecasts

            val activityFactor = (
                telemetry["isf_factor_activity_factor"]
                    ?: telemetry["activity_factor"]
                    ?: telemetry["activity_ratio"]
                    ?: 1.0
                ).coerceIn(0.6, 1.8)
            val setFactor = (telemetry["isf_factor_set_factor"] ?: 1.0).coerceIn(0.5, 1.2)
            val dawnFactor = (telemetry["isf_factor_dawn_factor"] ?: 1.0).coerceIn(0.7, 1.1)
            val stressFactor = (telemetry["isf_factor_stress_factor"] ?: 1.0).coerceIn(0.7, 1.1)
            val hormoneFactor = (telemetry["isf_factor_hormone_factor"] ?: 1.0).coerceIn(0.7, 1.1)
            val steroidFactor = (telemetry["isf_factor_steroid_factor"] ?: 1.0).coerceIn(0.7, 1.1)
            val sensorQuality = (telemetry["sensor_quality_score"] ?: 1.0).coerceIn(0.0, 1.0)
            val contextAmbiguity = (telemetry["isf_factor_context_ambiguity"] ?: 0.0).coerceIn(0.0, 1.0)
            val currentGlucose = latestGlucoseMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)

            val patternBias = when {
                pattern == null || !pattern.isRiskWindow -> 0.0
                pattern.highRate > pattern.lowRate -> ((pattern.highRate - pattern.lowRate) * 1.2)
                    .coerceIn(0.0, CONTEXT_PATTERN_BIAS_MAX)
                pattern.lowRate > pattern.highRate -> -((pattern.lowRate - pattern.highRate) * 1.2)
                    .coerceIn(0.0, CONTEXT_PATTERN_BIAS_MAX)
                else -> 0.0
            }

            val rawBias = (
                (1.0 - setFactor) * 0.80 +
                    (1.0 - dawnFactor) * 0.55 +
                    (1.0 - stressFactor) * 0.70 +
                    (1.0 - hormoneFactor) * 0.45 +
                    (1.0 - steroidFactor) * 0.55 -
                    ((activityFactor - 1.0) * 0.85) +
                    patternBias
                ).coerceIn(-CONTEXT_VALUE_BIAS_ABS_MAX, CONTEXT_VALUE_BIAS_ABS_MAX)

            val ciAddFromSensor = ((1.0 - sensorQuality) * CONTEXT_CI_ADD_SENSOR_MAX)
                .coerceIn(0.0, CONTEXT_CI_ADD_SENSOR_MAX)
            val ciAddFromAmbiguity = (contextAmbiguity * CONTEXT_CI_ADD_AMBIGUITY_MAX)
                .coerceIn(0.0, CONTEXT_CI_ADD_AMBIGUITY_MAX)
            val ciAddBase = ciAddFromSensor + ciAddFromAmbiguity

            if (abs(rawBias) < 1e-6 && ciAddBase < 1e-6) return forecasts

            return forecasts.map { forecast ->
                val horizonScale = when (forecast.horizonMinutes) {
                    5 -> 0.35
                    30 -> 0.75
                    60 -> 1.0
                    else -> (forecast.horizonMinutes / 60.0).coerceIn(0.35, 1.2)
                }
                val ciWidth = (forecast.ciHigh - forecast.ciLow).coerceAtLeast(0.0)
                val uncertaintyAttenuation = when {
                    ciWidth >= 4.0 -> 0.55
                    ciWidth >= 3.2 -> 0.70
                    ciWidth >= 2.5 -> 0.85
                    else -> 1.0
                }
                val lowRiskAnchor = minOf(currentGlucose, forecast.ciLow)
                val lowGlucosePositiveBiasAttenuation = if (rawBias > 0.0) {
                    when {
                        lowRiskAnchor <= CONTEXT_LOW_GLUCOSE_GUARD_HARD_MMOL -> 0.0
                        lowRiskAnchor >= CONTEXT_LOW_GLUCOSE_GUARD_SOFT_MMOL -> 1.0
                        else -> (
                            (lowRiskAnchor - CONTEXT_LOW_GLUCOSE_GUARD_HARD_MMOL) /
                                (CONTEXT_LOW_GLUCOSE_GUARD_SOFT_MMOL - CONTEXT_LOW_GLUCOSE_GUARD_HARD_MMOL)
                            ).coerceIn(0.0, 1.0)
                    }
                } else {
                    1.0
                }
                val bias = (rawBias * horizonScale * uncertaintyAttenuation * lowGlucosePositiveBiasAttenuation)
                    .coerceIn(-CONTEXT_VALUE_BIAS_ABS_MAX, CONTEXT_VALUE_BIAS_ABS_MAX)
                val shiftedValue = (forecast.valueMmol + bias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)

                val ciAdd = (ciAddBase * horizonScale).coerceIn(0.0, CONTEXT_CI_ADD_ABS_MAX)
                var shiftedLow = (forecast.ciLow + bias - ciAdd).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedHigh = (forecast.ciHigh + bias + ciAdd).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                if (shiftedLow > shiftedValue) shiftedLow = shiftedValue
                if (shiftedHigh < shiftedValue) shiftedHigh = shiftedValue

                // For low-quality sensors, avoid overly aggressive prediction displacement.
                val guardedValue = if (sensorQuality < 0.45) {
                    val maxDeviation = CONTEXT_LOW_QUALITY_MAX_DEVIATION_MMOL
                    (shiftedValue - currentGlucose)
                        .coerceIn(-maxDeviation, maxDeviation)
                        .plus(currentGlucose)
                        .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                } else {
                    shiftedValue
                }
                if (shiftedLow > guardedValue) shiftedLow = guardedValue
                if (shiftedHigh < guardedValue) shiftedHigh = guardedValue

                val version = if (forecast.modelVersion.contains("|ctx_bias_v1")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|ctx_bias_v1"
                }
                forecast.copy(
                    valueMmol = guardedValue,
                    ciLow = shiftedLow,
                    ciHigh = shiftedHigh,
                    modelVersion = version
                )
            }
        }

        internal fun applyCobIobForecastBiasStatic(
            forecasts: List<Forecast>,
            cobGrams: Double?,
            iobUnits: Double?,
            latestGlucoseMmol: Double? = null,
            uamActive: Boolean? = null
        ): List<Forecast> {
            if (forecasts.isEmpty()) return forecasts
            val cob = (cobGrams ?: 0.0).coerceIn(0.0, 400.0)
            val iob = (iobUnits ?: 0.0).coerceIn(0.0, 30.0)
            if (cob <= 0.0 && iob <= 0.0) return forecasts
            val latestGlucose = latestGlucoseMmol?.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            val hasUam = uamActive == true
            val lowRisk = !hasUam &&
                latestGlucose != null &&
                latestGlucose <= COB_IOB_LOW_RISK_MMOL &&
                iob >= COB_IOB_LOW_RISK_MIN_IOB &&
                cob >= COB_IOB_LOW_RISK_MIN_COB
            val hardLowRisk = lowRisk && (latestGlucose ?: MAX_GLUCOSE_MMOL) <= COB_IOB_HARD_LOW_MMOL

            val pred5 = forecasts.firstOrNull { it.horizonMinutes == 5 }?.valueMmol
            val pred30 = forecasts.firstOrNull { it.horizonMinutes == 30 }?.valueMmol
            val pred60 = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol
            val fallingSignal = pred5 != null && pred30 != null && pred60 != null &&
                pred30 <= pred5 - COB_IOB_FALLING_SIGNAL_STEP_MMOL &&
                pred60 <= pred30 - COB_IOB_FALLING_SIGNAL_STEP_MMOL

            return forecasts.map { forecast ->
                val cobGain = when (forecast.horizonMinutes) {
                    5 -> COB_FORECAST_GAIN_5
                    30 -> COB_FORECAST_GAIN_30
                    60 -> COB_FORECAST_GAIN_60
                    else -> COB_FORECAST_GAIN_60 * (forecast.horizonMinutes / 60.0)
                }
                val iobGain = when (forecast.horizonMinutes) {
                    5 -> IOB_FORECAST_GAIN_5
                    30 -> IOB_FORECAST_GAIN_30
                    60 -> IOB_FORECAST_GAIN_60
                    else -> IOB_FORECAST_GAIN_60 * (forecast.horizonMinutes / 60.0)
                }

                val baseCobBias = (cob * cobGain).coerceIn(0.0, COB_FORECAST_BIAS_MAX)
                val baseIobBias = (iob * iobGain).coerceIn(0.0, IOB_FORECAST_BIAS_MAX)
                val cobRiskAttenuation = if (hasUam) {
                    1.0
                } else {
                    val lowRiskScale = when {
                        hardLowRisk -> COB_BIAS_SUPPRESSION_HARD
                        lowRisk -> COB_BIAS_SUPPRESSION_SOFT
                        else -> 1.0
                    }
                    val fallingScale = if (fallingSignal) COB_BIAS_SUPPRESSION_FALLING else 1.0
                    lowRiskScale * fallingScale
                }
                val cobBias = (baseCobBias * cobRiskAttenuation).coerceIn(0.0, COB_FORECAST_BIAS_MAX)

                var iobRiskBoost = 1.0
                if (!hasUam && lowRisk) {
                    iobRiskBoost *= if (hardLowRisk) IOB_BIAS_BOOST_HARD else IOB_BIAS_BOOST_SOFT
                }
                if (!hasUam && fallingSignal) {
                    iobRiskBoost *= IOB_BIAS_BOOST_FALLING
                }
                val iobBias = (baseIobBias * iobRiskBoost).coerceIn(0.0, IOB_FORECAST_BIAS_MAX)

                val horizonLowRiskScale = when (forecast.horizonMinutes) {
                    5 -> 0.45
                    30 -> 0.75
                    60 -> 1.0
                    else -> (forecast.horizonMinutes / 60.0).coerceIn(0.45, 1.1)
                }
                val extraLowGuardDown = if (
                    !hasUam &&
                    hardLowRisk &&
                    iob >= COB_IOB_EXTRA_GUARD_MIN_IOB &&
                    cob >= COB_IOB_EXTRA_GUARD_MIN_COB
                ) {
                    val glucoseDelta = (COB_IOB_HARD_LOW_MMOL - (latestGlucose ?: forecast.valueMmol))
                        .coerceAtLeast(0.0)
                    var guard = (
                        (iob - COB_IOB_EXTRA_GUARD_MIN_IOB).coerceAtLeast(0.0) * IOB_LOW_GUARD_GAIN +
                            (cob - COB_IOB_EXTRA_GUARD_MIN_COB).coerceAtLeast(0.0) * COB_LOW_GUARD_GAIN +
                            glucoseDelta * GLUCOSE_LOW_GUARD_GAIN
                        ) * horizonLowRiskScale
                    if (fallingSignal) {
                        guard *= LOW_GUARD_FALLING_MULTIPLIER
                    }
                    guard.coerceIn(0.0, LOW_GUARD_EXTRA_DOWN_MAX)
                } else {
                    0.0
                }

                val totalBias = (cobBias - iobBias - extraLowGuardDown).coerceIn(FORECAST_BIAS_MIN, FORECAST_BIAS_MAX)
                if (abs(totalBias) < 1e-6) return@map forecast

                val shiftedValue = (forecast.valueMmol + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedLow = (forecast.ciLow + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedHigh = (forecast.ciHigh + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                if (shiftedLow > shiftedValue) shiftedLow = shiftedValue
                if (shiftedHigh < shiftedValue) shiftedHigh = shiftedValue
                val version = if (forecast.modelVersion.contains("|cob_iob_bias_v1")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|cob_iob_bias_v1"
                }
                forecast.copy(
                    valueMmol = shiftedValue,
                    ciLow = shiftedLow,
                    ciHigh = shiftedHigh,
                    modelVersion = version
                )
            }
        }

        internal fun shouldSkipBaseAlignmentStatic(
            sourceRuleId: String?,
            actionReason: String
        ): Boolean {
            return sourceRuleId == AdaptiveTargetControllerRule.RULE_ID ||
                actionReason.contains("adaptive_pi_ci", ignoreCase = true) ||
                actionReason.contains("adaptive_keepalive", ignoreCase = true)
        }

        private const val ISFCR_SHADOW_DIFF_EVENT = "isfcr_shadow_diff_logged"
        private const val ISFCR_REALTIME_EVENT = "isfcr_realtime_computed"
        private const val ISFCR_AUTO_ACTIVATION_EVAL_INTERVAL_MINUTES = 30
        private const val ISFCR_AUTO_ACTIVATION_HYPO_THRESHOLD_MMOL = 3.9
        private val ISFCR_ROLLING_WINDOWS_DAYS = listOf(14, 30, 90)
    }

    private fun IsfCrRealtimeSnapshot.toProfileEstimate(lookbackDays: Int): ProfileEstimate {
        return ProfileEstimate(
            isfMmolPerUnit = isfEff,
            crGramPerUnit = crEff,
            confidence = confidence,
            sampleCount = maxOf(1, isfEvidenceCount + crEvidenceCount),
            isfSampleCount = maxOf(0, isfEvidenceCount),
            crSampleCount = maxOf(0, crEvidenceCount),
            lookbackDays = lookbackDays.coerceIn(30, 730),
            telemetryIsfSampleCount = 0,
            telemetryCrSampleCount = 0,
            uamObservedCount = 0,
            uamFilteredIsfSamples = 0,
            uamEpisodeCount = 0,
            uamEstimatedCarbsGrams = 0.0,
            uamEstimatedRecentCarbsGrams = 0.0
        )
    }

    private suspend fun logIsfCrShadowDiff(
        snapshot: IsfCrRealtimeSnapshot,
        legacyProfile: ProfileEstimate?
    ) {
        val legacyIsf = legacyProfile?.isfMmolPerUnit ?: return
        val legacyCr = legacyProfile.crGramPerUnit
        val isfDelta = snapshot.isfEff - legacyIsf
        val crDelta = snapshot.crEff - legacyCr
        val isfDeltaPct = if (legacyIsf > 1e-6) (isfDelta / legacyIsf) * 100.0 else 0.0
        val crDeltaPct = if (legacyCr > 1e-6) (crDelta / legacyCr) * 100.0 else 0.0

        auditLogger.info(
            "isfcr_shadow_diff_logged",
            mapOf(
                "mode" to snapshot.mode.name,
                "confidence" to snapshot.confidence,
                "legacyIsf" to legacyIsf,
                "legacyCr" to legacyCr,
                "realtimeIsf" to snapshot.isfEff,
                "realtimeCr" to snapshot.crEff,
                "isfDelta" to isfDelta,
                "crDelta" to crDelta,
                "isfDeltaPct" to isfDeltaPct,
                "crDeltaPct" to crDeltaPct
            )
        )
    }

    private suspend fun maybeProcessIsfCrShadowAutoActivation(
        settings: AppSettings,
        nowTs: Long,
        latestTelemetry: Map<String, Double?>
    ) {
        if (!settings.isfCrShadowMode || !settings.isfCrAutoActivationEnabled) return
        val evalBucketMs = ISFCR_AUTO_ACTIVATION_EVAL_INTERVAL_MINUTES * 60_000L
        val evalBucketTs = (nowTs / evalBucketMs) * evalBucketMs
        if (evalBucketTs == lastIsfCrShadowActivationEvalBucketTs) return
        lastIsfCrShadowActivationEvalBucketTs = evalBucketTs

        val lookbackHours = settings.isfCrAutoActivationLookbackHours.coerceIn(6, 72)
        val minSamples = settings.isfCrAutoActivationMinSamples.coerceIn(12, 288)
        val sinceTs = nowTs - lookbackHours * 60L * 60L * 1000L
        val fetchLimit = (minSamples * 3).coerceAtMost(1024)
        val rows = db.auditLogDao().recentByMessage(
            message = ISFCR_SHADOW_DIFF_EVENT,
            sinceTs = sinceTs,
            limit = fetchLimit
        )
        val samples = extractIsfCrShadowDiffSamples(rows)
        val assessment = evaluateIsfCrShadowActivationStatic(
            samples = samples,
            minSamples = minSamples,
            minMeanConfidence = settings.isfCrAutoActivationMinMeanConfidence.coerceIn(0.2, 0.95),
            maxMeanAbsIsfDeltaPct = settings.isfCrAutoActivationMaxMeanAbsIsfDeltaPct.coerceIn(5.0, 100.0),
            maxMeanAbsCrDeltaPct = settings.isfCrAutoActivationMaxMeanAbsCrDeltaPct.coerceIn(5.0, 100.0)
        )

        auditLogger.info(
            "isfcr_shadow_activation_evaluated",
            mapOf(
                "eligible" to assessment.eligible,
                "reason" to assessment.reason,
                "sampleCount" to assessment.sampleCount,
                "meanConfidence" to assessment.meanConfidence,
                "meanAbsIsfDeltaPct" to assessment.meanAbsIsfDeltaPct,
                "meanAbsCrDeltaPct" to assessment.meanAbsCrDeltaPct,
                "lookbackHours" to lookbackHours,
                "minSamples" to minSamples
            )
        )

        if (!assessment.eligible) return

        val realtimeRows = db.auditLogDao().recentByMessage(
            message = ISFCR_REALTIME_EVENT,
            sinceTs = sinceTs,
            limit = fetchLimit
        )
        val dayTypeSamples = extractIsfCrDayTypeStabilitySamples(realtimeRows)
        val minDayTypeRatio = settings.isfCrAutoActivationMinDayTypeRatio.coerceIn(0.0, 1.0)
        val maxDayTypeSparseRatePct = settings.isfCrAutoActivationMaxDayTypeSparseRatePct.coerceIn(0.0, 100.0)
        val dayTypeAssessment = evaluateIsfCrDayTypeStabilityStatic(
            samples = dayTypeSamples,
            minSamples = maxOf(18, minSamples / 2),
            minMeanSameDayTypeRatio = minDayTypeRatio,
            maxSparseRatePct = maxDayTypeSparseRatePct
        )
        auditLogger.info(
            "isfcr_shadow_day_type_gate_evaluated",
            mapOf(
                "eligible" to dayTypeAssessment.eligible,
                "reason" to dayTypeAssessment.reason,
                "sampleCount" to dayTypeAssessment.sampleCount,
                "meanIsfSameDayTypeRatio" to dayTypeAssessment.meanIsfSameDayTypeRatio,
                "meanCrSameDayTypeRatio" to dayTypeAssessment.meanCrSameDayTypeRatio,
                "isfSparseRatePct" to dayTypeAssessment.isfSparseRatePct,
                "crSparseRatePct" to dayTypeAssessment.crSparseRatePct,
                "minMeanSameDayTypeRatio" to minDayTypeRatio,
                "maxSparseRatePct" to maxDayTypeSparseRatePct
            )
        )
        if (!dayTypeAssessment.eligible) return

        val sensorQualitySamples = extractIsfCrSensorQualitySamples(realtimeRows)
        val sensorQualityAssessment = evaluateIsfCrSensorQualityStatic(
            samples = sensorQualitySamples,
            minSamples = maxOf(18, minSamples / 2),
            minMeanQualityScore = settings.isfCrAutoActivationMinSensorQualityScore.coerceIn(0.0, 1.0),
            minMeanSensorFactor = settings.isfCrAutoActivationMinSensorFactor.coerceIn(0.0, 1.0),
            maxMeanWearPenalty = settings.isfCrAutoActivationMaxWearConfidencePenalty.coerceIn(0.0, 1.0),
            maxSensorAgeHighRatePct = settings.isfCrAutoActivationMaxSensorAgeHighRatePct.coerceIn(0.0, 100.0),
            maxSuspectFalseLowRatePct = settings.isfCrAutoActivationMaxSuspectFalseLowRatePct.coerceIn(0.0, 100.0)
        )
        auditLogger.info(
            "isfcr_shadow_sensor_gate_evaluated",
            mapOf(
                "eligible" to sensorQualityAssessment.eligible,
                "reason" to sensorQualityAssessment.reason,
                "sampleCount" to sensorQualityAssessment.sampleCount,
                "meanQualityScore" to sensorQualityAssessment.meanQualityScore,
                "meanSensorFactor" to sensorQualityAssessment.meanSensorFactor,
                "meanWearPenalty" to sensorQualityAssessment.meanWearPenalty,
                "sensorAgeHighRatePct" to sensorQualityAssessment.sensorAgeHighRatePct,
                "suspectFalseLowRatePct" to sensorQualityAssessment.suspectFalseLowRatePct,
                "minMeanQualityScore" to settings.isfCrAutoActivationMinSensorQualityScore.coerceIn(0.0, 1.0),
                "minMeanSensorFactor" to settings.isfCrAutoActivationMinSensorFactor.coerceIn(0.0, 1.0),
                "maxMeanWearPenalty" to settings.isfCrAutoActivationMaxWearConfidencePenalty.coerceIn(0.0, 1.0),
                "maxSensorAgeHighRatePct" to settings.isfCrAutoActivationMaxSensorAgeHighRatePct.coerceIn(0.0, 100.0),
                "maxSuspectFalseLowRatePct" to settings.isfCrAutoActivationMaxSuspectFalseLowRatePct.coerceIn(0.0, 100.0)
            )
        )
        if (!sensorQualityAssessment.eligible) return

        val qualityAssessment = if (settings.isfCrAutoActivationRequireDailyQualityGate) {
            val matchedSamples = latestTelemetry["daily_report_matched_samples"]?.toInt()
            val mae30 = latestTelemetry["daily_report_mae_30m"]
            val mae60 = latestTelemetry["daily_report_mae_60m"]
            val ciCoverage30 = latestTelemetry["daily_report_ci_coverage_30m_pct"]
            val ciCoverage60 = latestTelemetry["daily_report_ci_coverage_60m_pct"]
            val ciWidth30 = latestTelemetry["daily_report_ci_width_30m"]
            val ciWidth60 = latestTelemetry["daily_report_ci_width_60m"]
            val hypoRate24h = runCatching {
                val glucose24h = db.glucoseDao().since(nowTs - 24L * 60L * 60L * 1000L)
                if (glucose24h.isEmpty()) {
                    null
                } else {
                    val hypoCount = glucose24h.count { it.mmol < ISFCR_AUTO_ACTIVATION_HYPO_THRESHOLD_MMOL }
                    hypoCount * 100.0 / glucose24h.size.toDouble()
                }
            }.getOrNull()
            evaluateIsfCrDailyQualityGateStatic(
                matchedSamples = matchedSamples,
                mae30Mmol = mae30,
                mae60Mmol = mae60,
                hypoRatePct24h = hypoRate24h,
                ciCoverage30Pct = ciCoverage30,
                ciCoverage60Pct = ciCoverage60,
                ciWidth30Mmol = ciWidth30,
                ciWidth60Mmol = ciWidth60,
                minDailyMatchedSamples = settings.isfCrAutoActivationMinDailyMatchedSamples.coerceIn(24, 720),
                maxDailyMae30Mmol = settings.isfCrAutoActivationMaxDailyMae30Mmol.coerceIn(0.3, 4.0),
                maxDailyMae60Mmol = settings.isfCrAutoActivationMaxDailyMae60Mmol.coerceIn(0.5, 6.0),
                maxHypoRatePct = settings.isfCrAutoActivationMaxHypoRatePct.coerceIn(0.5, 30.0),
                minDailyCiCoverage30Pct = settings.isfCrAutoActivationMinDailyCiCoverage30Pct.coerceIn(20.0, 99.0),
                minDailyCiCoverage60Pct = settings.isfCrAutoActivationMinDailyCiCoverage60Pct.coerceIn(20.0, 99.0),
                maxDailyCiWidth30Mmol = settings.isfCrAutoActivationMaxDailyCiWidth30Mmol.coerceIn(0.3, 6.0),
                maxDailyCiWidth60Mmol = settings.isfCrAutoActivationMaxDailyCiWidth60Mmol.coerceIn(0.5, 8.0)
            )
        } else {
            IsfCrDailyQualityGateAssessment(
                eligible = true,
                reason = "quality_gate_disabled",
                matchedSamples = latestTelemetry["daily_report_matched_samples"]?.toInt(),
                mae30Mmol = latestTelemetry["daily_report_mae_30m"],
                mae60Mmol = latestTelemetry["daily_report_mae_60m"],
                hypoRatePct24h = null,
                ciCoverage30Pct = latestTelemetry["daily_report_ci_coverage_30m_pct"],
                ciCoverage60Pct = latestTelemetry["daily_report_ci_coverage_60m_pct"],
                ciWidth30Mmol = latestTelemetry["daily_report_ci_width_30m"],
                ciWidth60Mmol = latestTelemetry["daily_report_ci_width_60m"]
            )
        }
        auditLogger.info(
            "isfcr_shadow_quality_gate_evaluated",
            mapOf(
                "eligible" to qualityAssessment.eligible,
                "reason" to qualityAssessment.reason,
                "matchedSamples" to qualityAssessment.matchedSamples,
                "mae30Mmol" to qualityAssessment.mae30Mmol,
                "mae60Mmol" to qualityAssessment.mae60Mmol,
                "hypoRatePct24h" to qualityAssessment.hypoRatePct24h,
                "ciCoverage30Pct" to qualityAssessment.ciCoverage30Pct,
                "ciCoverage60Pct" to qualityAssessment.ciCoverage60Pct,
                "ciWidth30Mmol" to qualityAssessment.ciWidth30Mmol,
                "ciWidth60Mmol" to qualityAssessment.ciWidth60Mmol,
                "enabled" to settings.isfCrAutoActivationRequireDailyQualityGate
            )
        )
        if (!qualityAssessment.eligible) return

        val dailyRiskBlockLevel = settings.isfCrAutoActivationDailyRiskBlockLevel.coerceIn(2, 3)
        val dailyRiskFallbackUsed = latestTelemetry["daily_report_isfcr_quality_risk_level_fallback_used"]
            ?.let { it >= 0.5 }
        val dailyRiskAssessment = evaluateIsfCrDailyRiskGateStatic(
            riskLevel = latestTelemetry["daily_report_isfcr_quality_risk_level"]?.toInt(),
            blockedRiskLevel = dailyRiskBlockLevel
        )
        val dailyRiskLevelSource = resolveIsfCrDailyRiskLevelSourceStatic(
            riskLevel = dailyRiskAssessment.riskLevel,
            fallbackUsed = dailyRiskFallbackUsed
        )
        auditLogger.info(
            "isfcr_shadow_data_quality_risk_gate_evaluated",
            mapOf(
                "eligible" to dailyRiskAssessment.eligible,
                "reason" to dailyRiskAssessment.reason,
                "riskLevel" to dailyRiskAssessment.riskLevel,
                "blockedRiskLevel" to dailyRiskBlockLevel,
                "riskLevelSource" to dailyRiskLevelSource
            )
        )
        if (!dailyRiskAssessment.eligible) return

        val rollingRequiredWindows = settings.isfCrAutoActivationRollingMinRequiredWindows
            .coerceIn(1, ISFCR_ROLLING_WINDOWS_DAYS.size)
        val rollingMaeRelaxFactor = settings.isfCrAutoActivationRollingMaeRelaxFactor.coerceIn(1.0, 1.5)
        val rollingCiCoverageRelaxFactor =
            settings.isfCrAutoActivationRollingCiCoverageRelaxFactor.coerceIn(0.70, 1.0)
        val rollingCiWidthRelaxFactor = settings.isfCrAutoActivationRollingCiWidthRelaxFactor.coerceIn(1.0, 1.5)
        val rollingMinMatchedBase = settings.isfCrAutoActivationMinDailyMatchedSamples.coerceIn(24, 720)
        val rollingWindowAssessments = ISFCR_ROLLING_WINDOWS_DAYS.map { days ->
            val prefix = "rolling_report_${days}d"
            val matchedSamples = latestTelemetry["${prefix}_matched_samples"]?.toInt()
            val mae30 = latestTelemetry["${prefix}_mae_30m"]
            val mae60 = latestTelemetry["${prefix}_mae_60m"]
            val ciCoverage30 = latestTelemetry["${prefix}_ci_coverage_30m_pct"]
            val ciCoverage60 = latestTelemetry["${prefix}_ci_coverage_60m_pct"]
            val ciWidth30 = latestTelemetry["${prefix}_ci_width_30m"]
            val ciWidth60 = latestTelemetry["${prefix}_ci_width_60m"]
            val available = matchedSamples != null || mae30 != null || mae60 != null
            if (!available) {
                IsfCrRollingQualityWindowAssessment(
                    days = days,
                    available = false,
                    eligible = false,
                    reason = "rolling_report_missing",
                    matchedSamples = matchedSamples,
                    mae30Mmol = mae30,
                    mae60Mmol = mae60,
                    ciCoverage30Pct = ciCoverage30,
                    ciCoverage60Pct = ciCoverage60,
                    ciWidth30Mmol = ciWidth30,
                    ciWidth60Mmol = ciWidth60
                )
            } else {
                val scaledMinMatched = (rollingMinMatchedBase * (days / 3.0))
                    .toInt()
                    .coerceAtLeast(rollingMinMatchedBase)
                val dailyBased = evaluateIsfCrDailyQualityGateStatic(
                    matchedSamples = matchedSamples,
                    mae30Mmol = mae30,
                    mae60Mmol = mae60,
                    hypoRatePct24h = 0.0,
                    ciCoverage30Pct = ciCoverage30,
                    ciCoverage60Pct = ciCoverage60,
                    ciWidth30Mmol = ciWidth30,
                    ciWidth60Mmol = ciWidth60,
                    minDailyMatchedSamples = scaledMinMatched,
                    maxDailyMae30Mmol = settings.isfCrAutoActivationMaxDailyMae30Mmol * rollingMaeRelaxFactor,
                    maxDailyMae60Mmol = settings.isfCrAutoActivationMaxDailyMae60Mmol * rollingMaeRelaxFactor,
                    maxHypoRatePct = 100.0,
                    minDailyCiCoverage30Pct = settings.isfCrAutoActivationMinDailyCiCoverage30Pct * rollingCiCoverageRelaxFactor,
                    minDailyCiCoverage60Pct = settings.isfCrAutoActivationMinDailyCiCoverage60Pct * rollingCiCoverageRelaxFactor,
                    maxDailyCiWidth30Mmol = settings.isfCrAutoActivationMaxDailyCiWidth30Mmol * rollingCiWidthRelaxFactor,
                    maxDailyCiWidth60Mmol = settings.isfCrAutoActivationMaxDailyCiWidth60Mmol * rollingCiWidthRelaxFactor
                )
                IsfCrRollingQualityWindowAssessment(
                    days = days,
                    available = true,
                    eligible = dailyBased.eligible,
                    reason = dailyBased.reason,
                    matchedSamples = dailyBased.matchedSamples,
                    mae30Mmol = dailyBased.mae30Mmol,
                    mae60Mmol = dailyBased.mae60Mmol,
                    ciCoverage30Pct = dailyBased.ciCoverage30Pct,
                    ciCoverage60Pct = dailyBased.ciCoverage60Pct,
                    ciWidth30Mmol = dailyBased.ciWidth30Mmol,
                    ciWidth60Mmol = dailyBased.ciWidth60Mmol
                )
            }
        }
        val rollingGateAssessment = evaluateIsfCrRollingQualityGateStatic(
            windows = rollingWindowAssessments,
            minRequiredWindows = rollingRequiredWindows
        )
        auditLogger.info(
            "isfcr_shadow_rolling_gate_evaluated",
            mapOf(
                "eligible" to rollingGateAssessment.eligible,
                "reason" to rollingGateAssessment.reason,
                "requiredWindowCountConfigured" to rollingRequiredWindows,
                "maeRelaxFactor" to rollingMaeRelaxFactor,
                "ciCoverageRelaxFactor" to rollingCiCoverageRelaxFactor,
                "ciWidthRelaxFactor" to rollingCiWidthRelaxFactor,
                "requiredWindowCount" to rollingGateAssessment.requiredWindowCount,
                "evaluatedWindowCount" to rollingGateAssessment.evaluatedWindowCount,
                "passedWindowCount" to rollingGateAssessment.passedWindowCount,
                "windows" to rollingGateAssessment.windows.map { window ->
                    mapOf(
                        "days" to window.days,
                        "available" to window.available,
                        "eligible" to window.eligible,
                        "reason" to window.reason,
                        "matchedSamples" to window.matchedSamples,
                        "mae30Mmol" to window.mae30Mmol,
                        "mae60Mmol" to window.mae60Mmol,
                        "ciCoverage30Pct" to window.ciCoverage30Pct,
                        "ciCoverage60Pct" to window.ciCoverage60Pct,
                        "ciWidth30Mmol" to window.ciWidth30Mmol,
                        "ciWidth60Mmol" to window.ciWidth60Mmol
                    )
                }
            )
        )
        if (!rollingGateAssessment.eligible) return

        var promoted = false
        settingsStore.update { current ->
            if (current.isfCrShadowMode && current.isfCrAutoActivationEnabled) {
                promoted = true
                current.copy(isfCrShadowMode = false)
            } else {
                current
            }
        }
        if (!promoted) return

        auditLogger.warn(
            "isfcr_shadow_auto_promoted",
            mapOf(
                "reason" to assessment.reason,
                "sampleCount" to assessment.sampleCount,
                "meanConfidence" to assessment.meanConfidence,
                "meanAbsIsfDeltaPct" to assessment.meanAbsIsfDeltaPct,
                "meanAbsCrDeltaPct" to assessment.meanAbsCrDeltaPct,
                "dayTypeReason" to dayTypeAssessment.reason,
                "dayTypeSampleCount" to dayTypeAssessment.sampleCount,
                "dayTypeMeanIsfRatio" to dayTypeAssessment.meanIsfSameDayTypeRatio,
                "dayTypeMeanCrRatio" to dayTypeAssessment.meanCrSameDayTypeRatio,
                "dayTypeIsfSparseRatePct" to dayTypeAssessment.isfSparseRatePct,
                "dayTypeCrSparseRatePct" to dayTypeAssessment.crSparseRatePct,
                "sensorGateReason" to sensorQualityAssessment.reason,
                "sensorGateSampleCount" to sensorQualityAssessment.sampleCount,
                "sensorGateMeanQualityScore" to sensorQualityAssessment.meanQualityScore,
                "sensorGateMeanSensorFactor" to sensorQualityAssessment.meanSensorFactor,
                "sensorGateMeanWearPenalty" to sensorQualityAssessment.meanWearPenalty,
                "sensorGateSensorAgeHighRatePct" to sensorQualityAssessment.sensorAgeHighRatePct,
                "sensorGateSuspectFalseLowRatePct" to sensorQualityAssessment.suspectFalseLowRatePct,
                "qualityReason" to qualityAssessment.reason,
                "qualityMatchedSamples" to qualityAssessment.matchedSamples,
                "qualityMae30Mmol" to qualityAssessment.mae30Mmol,
                "qualityMae60Mmol" to qualityAssessment.mae60Mmol,
                "qualityHypoRatePct24h" to qualityAssessment.hypoRatePct24h,
                "qualityCiCoverage30Pct" to qualityAssessment.ciCoverage30Pct,
                "qualityCiCoverage60Pct" to qualityAssessment.ciCoverage60Pct,
                "qualityCiWidth30Mmol" to qualityAssessment.ciWidth30Mmol,
                "qualityCiWidth60Mmol" to qualityAssessment.ciWidth60Mmol,
                "dailyRiskGateReason" to dailyRiskAssessment.reason,
                "dailyRiskLevel" to dailyRiskAssessment.riskLevel,
                "dailyRiskLevelSource" to dailyRiskLevelSource,
                "dailyRiskBlockedLevel" to dailyRiskBlockLevel,
                "rollingGateReason" to rollingGateAssessment.reason,
                "rollingGateRequiredWindowCount" to rollingGateAssessment.requiredWindowCount,
                "rollingGateEvaluatedWindowCount" to rollingGateAssessment.evaluatedWindowCount,
                "rollingGatePassedWindowCount" to rollingGateAssessment.passedWindowCount
            )
        )
    }

    private fun extractIsfCrShadowDiffSamples(rows: List<AuditLogEntity>): List<IsfCrShadowDiffSample> {
        val metaType = object : TypeToken<Map<String, Any?>>() {}.type
        return rows.mapNotNull { row ->
            val metadata = runCatching {
                gson.fromJson<Map<String, Any?>>(row.metadataJson, metaType)
            }.getOrNull() ?: return@mapNotNull null
            val confidence = metadata["confidence"].toLooseDouble() ?: return@mapNotNull null
            val isfDeltaPct = metadata["isfDeltaPct"].toLooseDouble() ?: return@mapNotNull null
            val crDeltaPct = metadata["crDeltaPct"].toLooseDouble() ?: return@mapNotNull null
            IsfCrShadowDiffSample(
                confidence = confidence,
                isfDeltaPct = isfDeltaPct,
                crDeltaPct = crDeltaPct
            )
        }
    }

    private fun extractIsfCrDayTypeStabilitySamples(rows: List<AuditLogEntity>): List<IsfCrDayTypeStabilitySample> {
        val metaType = object : TypeToken<Map<String, Any?>>() {}.type
        return rows.mapNotNull { row ->
            val metadata = runCatching {
                gson.fromJson<Map<String, Any?>>(row.metadataJson, metaType)
            }.getOrNull() ?: return@mapNotNull null

            val isfHourWindowEvidence = (metadata["hourWindowIsfEvidence"].toLooseDouble() ?: 0.0).coerceAtLeast(0.0)
            val crHourWindowEvidence = (metadata["hourWindowCrEvidence"].toLooseDouble() ?: 0.0).coerceAtLeast(0.0)
            val isfHourWindowSame = (metadata["hourWindowIsfSameDayType"].toLooseDouble() ?: 0.0).coerceAtLeast(0.0)
            val crHourWindowSame = (metadata["hourWindowCrSameDayType"].toLooseDouble() ?: 0.0).coerceAtLeast(0.0)

            val isfRatio = if (isfHourWindowEvidence > 0.0) {
                (isfHourWindowSame / isfHourWindowEvidence).coerceIn(0.0, 1.0)
            } else {
                1.0
            }
            val crRatio = if (crHourWindowEvidence > 0.0) {
                (crHourWindowSame / crHourWindowEvidence).coerceIn(0.0, 1.0)
            } else {
                1.0
            }

            val reasonCodes = metadata["reasons"]
                ?.toString()
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val isfSparseFlag = if (isfHourWindowEvidence > 0.0) {
                isfHourWindowSame <= 0.0 || reasonCodes.contains("isf_day_type_evidence_sparse")
            } else {
                false
            }
            val crSparseFlag = if (crHourWindowEvidence > 0.0) {
                crHourWindowSame <= 0.0 || reasonCodes.contains("cr_day_type_evidence_sparse")
            } else {
                false
            }

            IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = isfRatio,
                crSameDayTypeRatio = crRatio,
                isfSparseFlag = isfSparseFlag,
                crSparseFlag = crSparseFlag
            )
        }
    }

    private fun extractIsfCrSensorQualitySamples(rows: List<AuditLogEntity>): List<IsfCrSensorQualitySample> {
        val metaType = object : TypeToken<Map<String, Any?>>() {}.type
        return rows.mapNotNull { row ->
            val metadata = runCatching {
                gson.fromJson<Map<String, Any?>>(row.metadataJson, metaType)
            }.getOrNull() ?: return@mapNotNull null

            val qualityScore = metadata["qualityScore"].toLooseDouble()?.coerceIn(0.0, 1.0)
                ?: return@mapNotNull null
            val sensorFactor = (metadata["sensorFactor"].toLooseDouble() ?: 1.0).coerceIn(0.0, 1.0)
            val wearPenalty = (metadata["wearConfidencePenalty"].toLooseDouble() ?: 0.0).coerceIn(0.0, 1.0)
            val sensorAgeHours = (metadata["sensorAgeHours"].toLooseDouble() ?: 0.0).coerceAtLeast(0.0)
            val reasonCodes = metadata["reasons"]
                ?.toString()
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val sensorAgeHighFlag = sensorAgeHours > 120.0 || reasonCodes.contains("sensor_age_high")
            val suspectFalseLowFlag = (metadata["sensorQualitySuspectFalseLow"].toLooseDouble() ?: 0.0) >= 0.5 ||
                reasonCodes.contains("sensor_quality_suspect_false_low")

            IsfCrSensorQualitySample(
                qualityScore = qualityScore,
                sensorFactor = sensorFactor,
                wearConfidencePenalty = wearPenalty,
                sensorAgeHighFlag = sensorAgeHighFlag,
                suspectFalseLowFlag = suspectFalseLowFlag
            )
        }
    }

    private fun Any?.toLooseDouble(): Double? {
        return when (this) {
            null -> null
            is Number -> this.toDouble()
            is String -> this.trim().replace(',', '.').toDoubleOrNull()
            else -> null
        }
    }

    private fun io.aaps.copilot.data.local.entity.ProfileEstimateEntity.toProfileEstimate(): ProfileEstimate {
        val useCalculatedIsf = calculatedIsfMmolPerUnit != null && calculatedIsfSampleCount > 0
        val useCalculatedCr = calculatedCrGramPerUnit != null && calculatedCrSampleCount > 0
        val realFirstIsf = if (useCalculatedIsf) calculatedIsfMmolPerUnit!! else isfMmolPerUnit
        val realFirstCr = if (useCalculatedCr) calculatedCrGramPerUnit!! else crGramPerUnit
        val realFirstIsfSamples = if (useCalculatedIsf) calculatedIsfSampleCount else isfSampleCount
        val realFirstCrSamples = if (useCalculatedCr) calculatedCrSampleCount else crSampleCount
        val realFirstSampleCount = if (useCalculatedIsf || useCalculatedCr) {
            maxOf(1, calculatedSampleCount)
        } else {
            sampleCount
        }
        val realFirstConfidence = if (useCalculatedIsf || useCalculatedCr) {
            calculatedConfidence ?: confidence
        } else {
            confidence
        }
        return ProfileEstimate(
            isfMmolPerUnit = realFirstIsf,
            crGramPerUnit = realFirstCr,
            confidence = realFirstConfidence,
            sampleCount = realFirstSampleCount,
            isfSampleCount = realFirstIsfSamples,
            crSampleCount = realFirstCrSamples,
            lookbackDays = lookbackDays,
            telemetryIsfSampleCount = telemetryIsfSampleCount,
            telemetryCrSampleCount = telemetryCrSampleCount,
            uamObservedCount = uamObservedCount,
            uamFilteredIsfSamples = uamFilteredIsfSamples,
            uamEpisodeCount = uamEpisodeCount,
            uamEstimatedCarbsGrams = uamEstimatedCarbsGrams,
            uamEstimatedRecentCarbsGrams = uamEstimatedRecentCarbsGrams
        )
    }

    private fun AppSettings.toUamUserSettingsLocal(): UamUserSettings = UamUserSettings(
        minSnackG = uamMinSnackG,
        maxSnackG = uamMaxSnackG,
        snackStepG = uamSnackStepG,
        backdateMinutesDefault = uamBackdateMinutesDefault,
        disableUamWhenManualCobActive = uamDisableWhenManualCobActive,
        manualCobThresholdG = uamManualCobThresholdG,
        disableUamIfManualCarbsNearby = uamDisableIfManualCarbsNearby,
        manualMergeWindowMinutes = uamManualMergeWindowMinutes,
        maxUamAbsorbRateGph_Normal = uamMaxAbsorbRateGphNormal,
        maxUamAbsorbRateGph_Boost = uamMaxAbsorbRateGphBoost,
        maxUamTotalG = uamMaxTotalG,
        maxActiveUamEvents = uamMaxActiveEvents,
        uamCarbMultiplier_Normal = uamCarbMultiplierNormal,
        uamCarbMultiplier_Boost = uamCarbMultiplierBoost,
        gAbsThreshold_Normal = uamGAbsThresholdNormal,
        gAbsThreshold_Boost = uamGAbsThresholdBoost,
        mOfN_Normal = uamMOfNNormalM to uamMOfNNormalN,
        mOfN_Boost = uamMOfNBoostM to uamMOfNBoostN,
        confirmConf_Normal = uamConfirmConfNormal,
        confirmConf_Boost = uamConfirmConfBoost,
        minConfirmAgeMin = uamMinConfirmAgeMin,
        exportMinIntervalMin = uamExportMinIntervalMin,
        exportMaxBackdateMin = uamExportMaxBackdateMin
    )

    private fun io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity.toProfileSegmentEstimate(): ProfileSegmentEstimate =
        ProfileSegmentEstimate(
            dayType = DayType.valueOf(dayType),
            timeSlot = ProfileTimeSlot.valueOf(timeSlot),
            isfMmolPerUnit = isfMmolPerUnit,
            crGramPerUnit = crGramPerUnit,
            confidence = confidence,
            isfSampleCount = isfSampleCount,
            crSampleCount = crSampleCount,
            lookbackDays = lookbackDays
        )

    private fun io.aaps.copilot.data.local.entity.GlucoseSampleEntity.toGlucosePoint():
        io.aaps.copilot.domain.model.GlucosePoint {
        val quality = runCatching {
            io.aaps.copilot.domain.model.DataQuality.valueOf(this.quality)
        }.getOrDefault(io.aaps.copilot.domain.model.DataQuality.OK)
        return io.aaps.copilot.domain.model.GlucosePoint(
            ts = timestamp,
            valueMmol = mmol,
            source = source,
            quality = quality
        )
    }

    private fun io.aaps.copilot.data.local.entity.TherapyEventEntity.toTherapyEvent(gson: Gson):
        io.aaps.copilot.domain.model.TherapyEvent {
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        val payload = gson.fromJson<Map<String, String>>(payloadJson, mapType) ?: emptyMap()
        return io.aaps.copilot.domain.model.TherapyEvent(
            ts = timestamp,
            type = type,
            payload = payload
        )
    }

    private fun Forecast.toForecastEntity() = io.aaps.copilot.data.local.entity.ForecastEntity(
        timestamp = ts,
        horizonMinutes = horizonMinutes,
        valueMmol = valueMmol,
        ciLow = ciLow,
        ciHigh = ciHigh,
        modelVersion = modelVersion
    )
}
