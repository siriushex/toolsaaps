package io.aaps.copilot.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.SensitivitySettingsMutation
import io.aaps.copilot.config.SensorLagCorrectionMode
import io.aaps.copilot.config.UamExportUiMode
import io.aaps.copilot.config.UamExportUiModePolicy
import io.aaps.copilot.config.sensitivityRuntimeFingerprint
import io.aaps.copilot.config.sensitivityRuntimeIdentity
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.TelemetryDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.CircadianForecastPrior
import io.aaps.copilot.domain.model.CircadianReplayBucketStatus
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.model.GlucoseCalibrationModelType
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.ResolvedGlucosePoint
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.model.SensorLagAgeSource
import io.aaps.copilot.domain.model.SensorLagEstimate
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.domain.alerts.AlertCauseAnalysis
import io.aaps.copilot.domain.alerts.AlertCauseAnalyzer
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseInput
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.domain.alerts.AlertCircadianEvidence
import io.aaps.copilot.domain.alerts.AlertContextEventType
import io.aaps.copilot.domain.alerts.AlertGlucoseEvidence
import io.aaps.copilot.domain.alerts.AlertInsulinEvidence
import io.aaps.copilot.domain.alerts.AlertInsulinSource
import io.aaps.copilot.domain.alerts.AlertSensitivityEvidence
import io.aaps.copilot.domain.alerts.AlertSensitivitySource
import io.aaps.copilot.domain.alerts.AlertSensorEvidence
import io.aaps.copilot.domain.alerts.AlertTargetEvidence
import io.aaps.copilot.domain.alerts.AlertTargetState
import io.aaps.copilot.domain.alerts.AlertUamEvidence
import io.aaps.copilot.domain.alerts.AlertUamState
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.predict.CarbAbsorptionProfiles
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.InsulinActionPoint
import io.aaps.copilot.domain.predict.InsulinActionProfile
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.InsulinActionProfiles
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshotResolver
import io.aaps.copilot.domain.predict.InsulinRuntimeResolution
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.InsulinComponentTelemetry
import io.aaps.copilot.domain.predict.QualifiedLocalInsulinEstimate
import io.aaps.copilot.domain.predict.TimedInsulinValue
import io.aaps.copilot.domain.predict.IsfRuntimeSourcePreference
import io.aaps.copilot.domain.predict.MetricRuntimeResolvedSource
import io.aaps.copilot.domain.predict.MetricRuntimeSourceDecision
import io.aaps.copilot.domain.predict.SensitivityMetricOverride
import io.aaps.copilot.domain.predict.SensitivityMetricKind
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumerContext
import io.aaps.copilot.domain.predict.SensitivityRuntimeFanOut
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDigest
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecomposition
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecompositionCodec
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastRow
import io.aaps.copilot.domain.predict.UamInferenceEngine
import io.aaps.copilot.domain.predict.UamCalculator
import io.aaps.copilot.domain.predict.UamUserSettings
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.predict.ProfileEstimatorConfig
import io.aaps.copilot.domain.predict.isSyntheticUamCarbEvent
import io.aaps.copilot.domain.profile.ActivityCoverage
import io.aaps.copilot.domain.profile.ActivityContextSource
import io.aaps.copilot.domain.profile.ActivityEffectContext
import io.aaps.copilot.domain.profile.ActivityEffectModel
import io.aaps.copilot.domain.profile.ActivityMeasurement
import io.aaps.copilot.domain.profile.ActivityScheduleEngine
import io.aaps.copilot.domain.profile.ActivityShadowSuppressionReason
import io.aaps.copilot.domain.profile.ActivitySafePredictedFall
import io.aaps.copilot.domain.profile.ActivityTargetProposalFactory
import io.aaps.copilot.domain.profile.ActivityTargetProposalInput
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.PlannedActivityOccurrence
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityType
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.rules.LowGlucoseTargetSafetyLatch
import io.aaps.copilot.domain.rules.PatternAdaptiveTargetRule
import io.aaps.copilot.domain.rules.PostHypoReboundGuardRule
import io.aaps.copilot.domain.rules.RuleContext
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.RuleRuntimeConfig
import io.aaps.copilot.domain.rules.SegmentProfileGuardRule
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.domain.safety.SafetyPolicyConfig
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.DeliveryTrustStateWireCodec
import io.aaps.copilot.domain.target.DeliveryTrustEvaluator
import io.aaps.copilot.domain.target.DeliveryTrustInput
import io.aaps.copilot.domain.target.EffectiveBaseTarget
import io.aaps.copilot.domain.target.EffectiveTargetRuntimeGates
import io.aaps.copilot.domain.target.HorizonReliability
import io.aaps.copilot.domain.target.HorizonReliabilityState
import io.aaps.copilot.domain.target.LastSentTempTarget
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManagerInput
import io.aaps.copilot.domain.target.TargetManagerDecision
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetManagerSafetyContext
import io.aaps.copilot.domain.target.ActivityForecastSafety
import io.aaps.copilot.domain.target.ActivityTargetSafetyContext
import io.aaps.copilot.domain.target.TargetProposal
import io.aaps.copilot.domain.target.TargetProposalFactory
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.util.UnitConverter
import io.aaps.copilot.util.ordinaryExceptionOrNull
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TreeMap
import java.util.UUID
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.min

internal data class AlertCauseBoundedRows<T>(
    val rows: List<T>,
    val overflow: Boolean
)

internal enum class AlertCauseContextSource {
    THERAPY,
    CONTEXT_TAGS,
    PLANNED_ACTIVITY
}

internal sealed interface AlertCauseContextLoadResult<out T> {
    data class Success<T>(val value: T) : AlertCauseContextLoadResult<T>
    data object Timeout : AlertCauseContextLoadResult<Nothing>
    data class Failure(val errorType: String) : AlertCauseContextLoadResult<Nothing>
    data class Overflow(val source: AlertCauseContextSource) : AlertCauseContextLoadResult<Nothing>
}

internal sealed interface AlertCausePlannedLoadResult {
    data class Complete(
        val events: List<CompensationEvent>,
        val pagesRead: Int
    ) : AlertCausePlannedLoadResult

    data object RelevantOverflow : AlertCausePlannedLoadResult
    data object ScanLimitReached : AlertCausePlannedLoadResult
    data object CursorStalled : AlertCausePlannedLoadResult
}

class AutomationRepository(
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val syncRepository: SyncRepository,
    private val exportRepository: AapsExportRepository,
    private val autoConnectRepository: AapsAutoConnectRepository,
    private val rootDbRepository: RootDbExperimentalRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val isfCrRepository: IsfCrRepository,
    private val sensitivityRuntimeRepository: SensitivityRuntimeRepository,
    private val glucoseCalibrationRepository: GlucoseCalibrationRepository,
    private val glucoseAlertStateStore: GlucoseAlertStateStore,
    private val episodeAlertDelivery: EpisodeAlertDeliveryStateMachine,
    private val glucoseAlertNotifier: GlucoseAlertNotifier,
    private val actionRepository: NightscoutActionRepository,
    private val targetManagerRepository: TargetManagerRepository,
    private val circadianTargetRepository: CircadianTargetRepository,
    private val energyProfileRepository: EnergyProfileRepository,
    private val predictionEngine: PredictionEngine,
    private val uamInferenceEngine: UamInferenceEngine,
    private val uamEventStore: UamEventStore,
    private val uamExportCoordinator: UamExportCoordinator,
    private val ruleEngine: RuleEngine,
    private val gson: Gson,
    private val auditLogger: AuditLogger,
    private val onWidgetDataChanged: (suspend () -> Unit)? = null,
    private val deliveryDiagnostic: DeliveryDiagnosticRepository? = null
) {

    internal enum class AutomationCycleIntent {
        NORMAL,
        LOCAL_READ_ONLY,
        SENSITIVITY_SOURCE_CHANGE
    }

    private enum class RuleEvaluationMode {
        LIVE,
        DIAGNOSTIC_READ_ONLY
    }

    internal data class AutomationCyclePolicy(
        val runCalculations: Boolean,
        val runRemoteRefresh: Boolean,
        val therapyWritersAllowed: Boolean,
        val allowSensitivityMaintenance: Boolean,
        val allowActionRepositoryAccess: Boolean,
        val allowLocalSafetyEvidence: Boolean,
        val allowAlertPublication: Boolean,
        val publishWidgetAfterAcceptance: Boolean
    )

    internal data class LocalSafetyEvidence(
        val actionsLast6h: Int,
        val activeAapsTarget: ActiveAapsTarget?,
        val latestAutomaticSent: LastSentTempTarget?,
        val chronologyResolved: Boolean,
        val causalThroughTs: Long
    )

    internal data class AcceptedClinicalForecasts(
        val forecasts: List<Forecast>,
        val generationTimestamp: Long,
        val digest: String
    )

    private data class SensitivitySettingsAcceptanceExpectation(
        val settingsRevision: Long,
        val isfSource: SensitivitySourcePreference,
        val crSource: SensitivitySourcePreference,
        val fingerprint: List<Any?>
    )

    private class SensitivitySettingsRestartRequired(
        val mutation: SensitivitySettingsMutation
    ) : RuntimeException("sensitivity settings changed during calculation")

    private class AcceptedForecastGenerationNotFresh(
        val reason: String
    ) : IllegalStateException("accepted sensitivity forecast generation is not fresh")

    private val cycleMutex = Mutex()
    private val mealRuntimeCapture = MealRuntimeCaptureRelay()
    internal val mealRuntimeUpdates = mealRuntimeCapture.updates
    private val isfCrRealtimeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val isfCrRealtimeScope = CoroutineScope(SupervisorJob() + isfCrRealtimeDispatcher)
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
    @Volatile
    private var isfCrRealtimeLastRefreshDurationMs = 0L
    @Volatile
    private var lastCalibrationRefreshBucketTs: Long = Long.MIN_VALUE
    @Volatile
    private var lastCalibrationPointsCache: List<ForecastCalibrationPoint> = emptyList()
    @Volatile
    private var lastUamInferenceResultCache: UamInferenceCycleResult? = null
    @Volatile
    private var lastLocalCobIobEstimate: LocalCobIobEstimate? = null
    @Volatile
    private var lastBaselineImportAttemptTs: Long = 0L
    private val activityEffectModel = ActivityEffectModel()
    private val activityScheduleEngine = ActivityScheduleEngine()
    @Volatile
    private var lastActivityForecastTelemetryAtMs: Long = 0L
    @Volatile
    private var lastActivityForecastTelemetryFingerprint: String? = null
    @Volatile
    private var glucoseAlertRestoreLogged: Boolean = false
    @Volatile
    private var cumulativeActivityDayCache: CumulativeActivityDayCache? = null
    private val sensorLagShadowRuleEngine: RuleEngine = buildSensorLagShadowRuleEngine()
    private val glucoseAlertEngine = GlucoseAlertEngine()
    private val lowGlucoseTargetSafetyLatch = LowGlucoseTargetSafetyLatch()
    private val targetProposalFactory = TargetProposalFactory()

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

    internal data class AutomaticTargetWriterRouting(
        val evaluateManager: Boolean,
        val submitLegacyAutomatic: Boolean,
        val fallbackToLegacyOnManagerFailure: Boolean = false
    )

    internal data class TargetManagerDeliveryTrustEvaluation<T : Any>(
        val evaluation: T,
        val telemetryPersisted: Boolean,
        val persistenceFailureType: String?
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

    internal data class UamRuntimeQualityAssessment(
        val sensorTrust: Double,
        val therapyCoverage: Double,
        val announcedCarbCoverage: Double,
        val sensorBlocked: Boolean,
        val reasons: Set<String>
    )

    internal data class IobSampleProvenance(
        val sampleTs: Long?,
        val ageMinutes: Double?,
        val fresh: Boolean
    )

    internal data class MetricRuntimeApplication(
        val decision: MetricRuntimeSourceDecision,
        val override: SensitivityMetricOverride?
    )

    internal data class SensitivityRuntimeApplication(
        val isf: MetricRuntimeApplication,
        val cr: MetricRuntimeApplication
    )

    internal data class UnifiedUamRuntimeSnapshot(
        val timestamp: Long,
        val state: String,
        val flag: Double,
        val controlFlag: Double,
        val confidence: Double,
        val impactMmol5: Double,
        val signedResidualMmol5: Double,
        val shortAverageDeltaMmol5: Double,
        val forecastComponent60Mmol: Double,
        val equivalentCarbsGrams: Double?,
        val supportedLowerBoundGrams: Double?,
        val onsetTs: Long?,
        val firstDetectionTs: Long?,
        val activeSinceTs: Long?,
        val supportStableBuckets: Int,
        val lowerBoundStableBuckets: Int,
        val sensorTrust: Double,
        val therapyCoverage: Double,
        val source: String,
        val reasons: Set<String>,
        val algorithmVersion: String,
        val episodeId: String,
        val effectiveCobGrams: Double,
        val sensorBlocked: Boolean,
        val sensitivityCycleId: String,
        val sensitivitySettingsRevision: Long,
        val sensitivityIsfMmolPerUnit: Double,
        val sensitivityCrGramPerUnit: Double,
        val calibrationIdentity: GlucoseCalibrationCycleIdentity? = null,
        val acceptedForecastGenerationTimestamp: Long? = null,
        val acceptedForecastDigest: String? = null,
        val acceptedForecasts: List<Forecast> = emptyList()
    )

    internal data class UnifiedUamExportTelemetrySnapshot(
        val liveEnabled: Boolean,
        val eligible: Boolean,
        val blockReason: String,
        val episodeId: String,
        val episodeAgeMinutes: Double,
        val lowerBoundGrams: Double,
        val cumulativeGrams: Double,
        val rolling30Grams: Double,
        val rolling60Grams: Double,
        val lastIncrementGrams: Double,
        val lastIncrementTs: Long?,
        val delivered: Boolean
    )

    internal data class UnifiedUamExportRuntimeRoute(
        val invokeCoordinator: Boolean,
        val dryRun: Boolean
    )

    internal data class UnifiedUamExportDispatch(
        val route: UnifiedUamExportRuntimeRoute,
        val outcome: UamExportCoordinator.Outcome
    )

    internal data class ForecastVirtualMealGateDecision(
        val active: Boolean,
        val reason: String
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
        val predictedMmol: Double = Double.NaN,
        val ciLowMmol: Double = Double.NaN,
        val ciHighMmol: Double = Double.NaN,
        val sensorTrusted: Boolean = false,
        val modelVersion: String = ""
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
        val modelVersion: String,
        val announcedCarbSteps: List<Double> = emptyList()
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
        val externalCobRawGrams: Double?,
        val externalCobAdjustedGrams: Double?,
        val syntheticUamCobGrams: Double,
        val realOnsetMinutes: Double,
        val baseOnsetMinutes: Double,
        val onsetSampleCount: Int,
        val usedLocalFallback: Boolean,
        val mergedWithTelemetry: Boolean,
        val insulinCycleContext: InsulinCycleContext
    ) {
        val insulinSnapshot: InsulinRuntimeSnapshot?
            get() = insulinCycleContext.snapshot
    }

    data class InsulinCycleContext(
        val cycleTimestamp: Long,
        val causalReferenceTimestamp: Long,
        val snapshot: InsulinRuntimeSnapshot?,
        val alignedSnapshot: InsulinRuntimeSnapshot?,
        val safetyIobUnits: Double?,
        val modeledActiveInsulinUnits: Double?,
        val signedResidualUnits: Double?,
        val residualComparisonAllowed: Boolean,
        val residualComparisonReason: String
    )

    enum class InsulinCycleConsumer {
        FORECAST,
        UAM,
        TARGET,
        ALERTS
    }

    class InsulinCycleFanOut internal constructor(
        val revision: Long,
        private val context: InsulinCycleContext
    ) {
        fun contextFor(consumer: InsulinCycleConsumer): InsulinCycleContext = when (consumer) {
            InsulinCycleConsumer.FORECAST,
            InsulinCycleConsumer.UAM,
            InsulinCycleConsumer.TARGET,
            InsulinCycleConsumer.ALERTS -> context
        }
    }

    fun interface ForecastGateway {
        fun request(): List<Forecast>
    }

    private data class LocalCobIobEstimate(
        val cobGrams: Double,
        val iobUnits: Double,
        val explicitInsulinEvents: Int,
        val insulinEvidenceTimestamp: Long?,
        val insulinTherapyCoverage: Double,
        val insulinConfidence: Double,
        val realOnsetMinutes: Double,
        val baseOnsetMinutes: Double,
        val onsetSampleCount: Int
    )

    private data class RuntimeDiaInputs(
        val profileHours: Double,
        val effectiveHours: Double,
        val rawEstimatedHours: Double?,
        val rawExternalHours: Double?,
        val source: String
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

    internal data class ProfileEstimatorRevisionPreparation(
        val wasPending: Boolean,
        val ready: Boolean
    )

    suspend fun runAutomationCycle(): SensitivityRuntimeSnapshot? =
        runCycle(AutomationCycleIntent.NORMAL)

    suspend fun runLocalReadOnlyCycle(): SensitivityRuntimeSnapshot? =
        runCycle(AutomationCycleIntent.LOCAL_READ_ONLY)

    internal suspend fun runReactiveCycle(intent: AutomationCycleIntent): SensitivityRuntimeSnapshot? {
        require(intent != AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE) {
            "reactive work cannot start a sensitivity source-change cycle"
        }
        return runReactiveCycleUnderLeaseStatic(cycleMutex) {
            runCycle(intent, cycleLeaseOwned = true)
        }
    }

    internal suspend fun applySensitivitySettings(
        updater: (AppSettings) -> AppSettings
    ): SensitivityRuntimeSnapshot {
        val revisionPreparation = prepareProfileEstimatorRevision(
            settings = settingsStore.settings.first(),
            intent = AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE
        )
        check(revisionPreparation.ready) {
            "profile estimator revision rebuild is not ready; sensitivity settings were not changed"
        }
        return applySensitivitySettingsChangeUnderCycleLeaseStatic(
            mutex = cycleMutex,
            lockTimeoutMs = SENSITIVITY_SOURCE_CHANGE_LOCK_TIMEOUT_MS,
            mutateSettings = {
                settingsStore.beginSensitivitySettingsMutation(updater)
            },
            runAcceptedCycle = { mutation ->
                runWithFailedSensitivitySettingsRestoration(mutation) {
                    val settings = settingsStore.settings.first()
                    require(settings.sensitivitySettingsRevision == mutation.applied.sensitivitySettingsRevision) {
                        "tentative sensitivity settings revision was replaced before calculation"
                    }
                    require(settings.sensitivityRuntimeFingerprint() == mutation.appliedFingerprint) {
                        "tentative sensitivity runtime settings were replaced before calculation"
                    }
                    val policy = resolveCyclePolicyStatic(
                        intent = AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
                        therapyActionsArmed = settings.therapyActionsArmed,
                        killSwitch = settings.killSwitch,
                        powerSaveActive = PowerSaveController.isActive(settings)
                    )
                    requireNotNull(
                        runAcquiredCycleAfterProfileRevisionPreparation(
                            intent = AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
                            bootstrapPolicy = policy,
                            markEstimatorRevisionOnAcceptance = revisionPreparation.wasPending,
                            expectedSettings = SensitivitySettingsAcceptanceExpectation(
                                settingsRevision = mutation.applied.sensitivitySettingsRevision,
                                isfSource = mutation.applied.isfSourcePreference,
                                crSource = mutation.applied.crSourcePreference,
                                fingerprint = mutation.appliedFingerprint
                            )
                        )
                    ) { "calculation-only cycle did not publish an accepted sensitivity snapshot" }
                }
            }
        )
    }

    private suspend fun <T> runWithFailedSensitivitySettingsRestoration(
        mutation: SensitivitySettingsMutation,
        calculation: suspend () -> T
    ): T = try {
        calculation()
    } catch (failure: Exception) {
        val acceptedRevision = sensitivityRuntimeRepository.current.value?.settingsRevision
        if (mutation.changed && acceptedRevision != mutation.applied.sensitivitySettingsRevision) {
            withContext(NonCancellable) {
                withTimeout(SENSITIVITY_ACCEPTED_DURABILITY_STEP_TIMEOUT_MS) {
                    settingsStore.beginSensitivitySettingsMutation { current ->
                        if (
                            current.sensitivitySettingsRevision != mutation.applied.sensitivitySettingsRevision ||
                            current.sensitivityRuntimeFingerprint() != mutation.appliedFingerprint
                        ) {
                            current
                        } else {
                            val before = mutation.before
                            current.copy(
                                isfSourcePreference = before.isfSourcePreference,
                                crSourcePreference = before.crSourcePreference,
                                isfCrShadowMode = before.isfCrShadowMode,
                                isfCrConfidenceThreshold = before.isfCrConfidenceThreshold,
                                isfCrUseActivity = before.isfCrUseActivity,
                                isfCrUseManualTags = before.isfCrUseManualTags,
                                isfCrMinIsfEvidencePerHour = before.isfCrMinIsfEvidencePerHour,
                                isfCrMinCrEvidencePerHour = before.isfCrMinCrEvidencePerHour,
                                isfCrCrMaxGapMinutes = before.isfCrCrMaxGapMinutes,
                                isfCrCrMaxSensorBlockedRatePct = before.isfCrCrMaxSensorBlockedRatePct,
                                isfCrCrMaxUamAmbiguityRatePct = before.isfCrCrMaxUamAmbiguityRatePct,
                                analyticsLookbackDays = before.analyticsLookbackDays,
                                energyProfile = current.energyProfile.copy(
                                    physiologicalSex = before.energyProfile.physiologicalSex
                                )
                            )
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        throw failure
    }

    internal suspend fun ensureAnalyticsRetentionDefault30Days(): Boolean {
        if (!settingsStore.ensureAnalyticsRetentionDefault30Days()) return false
        applySensitivitySettings { settings ->
            settings.copy(analyticsLookbackDays = 30)
        }
        settingsStore.markAnalyticsRetentionDefaultMigrationComplete()
        return true
    }

    private suspend fun runCycle(
        intent: AutomationCycleIntent,
        cycleLeaseOwned: Boolean = false
    ): SensitivityRuntimeSnapshot? {
        val bootstrapSettings = settingsStore.settings.first()
        val bootstrapPolicy = resolveCyclePolicyStatic(
            intent = intent,
            therapyActionsArmed = bootstrapSettings.therapyActionsArmed,
            killSwitch = bootstrapSettings.killSwitch,
            powerSaveActive = PowerSaveController.isActive(bootstrapSettings)
        )
        if (!bootstrapPolicy.runCalculations) {
            auditLogger.infoThrottled(
                throttleKey = "automation_cycle_skipped:therapy_actions_not_armed",
                intervalMs = 15 * 60_000L,
                message = "automation_cycle_skipped",
                metadata = mapOf("reason" to "therapy_actions_not_armed")
            )
            return null
        }
        if (intent == AutomationCycleIntent.NORMAL) {
            settingsStore.ensureUamThreeModeConsentV1()
        }
        require(intent != AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE) {
            "source changes must use applySensitivitySourcePreference"
        }

        val revisionPreparation = prepareProfileEstimatorRevision(
            settings = bootstrapSettings,
            intent = intent
        )
        if (!revisionPreparation.ready) return null

        val now = System.currentTimeMillis()
        val calculate: suspend () -> SensitivityRuntimeSnapshot? = {
            runAcquiredCycleAfterProfileRevisionPreparation(
                intent = intent,
                bootstrapPolicy = bootstrapPolicy,
                markEstimatorRevisionOnAcceptance = revisionPreparation.wasPending
            )
        }
        val execution = if (cycleLeaseOwned) {
            NormalCycleExecution(acquired = true, value = calculate())
        } else {
            runNormalCycleIfIdleStatic(cycleMutex, calculate)
        }
        if (!execution.acquired) {
            val startedAtTs = currentCycleStartedAtTs
            val runningForMs = if (startedAtTs > 0L) {
                runCatching { Math.subtractExact(now, startedAtTs) }
                    .getOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
            } else {
                0L
            }
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
            return null
        }
        return execution.value
    }

    private suspend fun prepareProfileEstimatorRevision(
        settings: AppSettings,
        intent: AutomationCycleIntent
    ): ProfileEstimatorRevisionPreparation = prepareProfileEstimatorRevisionStatic(
        isPending = analyticsRepository::isProfileEstimatorRevisionPending,
        rebuild = {
            analyticsRepository.ensureProfileStateHealthy(
                settings = settings,
                reasonHint = "automation:${intent.name.lowercase()}"
            )
        },
        onNotReady = { reason ->
            auditLogger.warn(
                "profile_estimator_revision_not_ready",
                mapOf(
                    "intent" to intent.name,
                    "reason" to reason
                )
            )
        }
    )

    private suspend fun runAcquiredCycleAfterProfileRevisionPreparation(
        intent: AutomationCycleIntent,
        bootstrapPolicy: AutomationCyclePolicy,
        markEstimatorRevisionOnAcceptance: Boolean,
        expectedSettings: SensitivitySettingsAcceptanceExpectation? = null
    ): SensitivityRuntimeSnapshot? {
        return runAcquiredCycle(
            intent = intent,
            bootstrapPolicy = bootstrapPolicy,
            expectedSettings = expectedSettings,
            beforeClinicalSideEffects = if (markEstimatorRevisionOnAcceptance) {
                { analyticsRepository.markProfileEstimatorRevisionApplied() }
            } else {
                null
            }
        )
    }

    private suspend fun runAcquiredCycle(
        intent: AutomationCycleIntent,
        bootstrapPolicy: AutomationCyclePolicy,
        expectedSettings: SensitivitySettingsAcceptanceExpectation? = null,
        beforeClinicalSideEffects: (suspend () -> Unit)? = null
    ): SensitivityRuntimeSnapshot? {
        val now = System.currentTimeMillis()
        currentCycleStartedAtTs = now
        auditLogger.info("automation_cycle_started", mapOf("startedAtTs" to now))
        return try {
            val acceptedSnapshot = withTimeout(AUTOMATION_CYCLE_TIMEOUT_MS) {
                try {
                    runAutomationCycleLocked(
                        intent = intent,
                        bootstrapPolicy = bootstrapPolicy,
                        expectedSettings = expectedSettings,
                        beforeClinicalSideEffects = beforeClinicalSideEffects
                    )
                } catch (restart: SensitivitySettingsRestartRequired) {
                    runSensitivitySettingsRestartUnderOwnedLease(
                        mutation = restart.mutation,
                        beforeClinicalSideEffects = beforeClinicalSideEffects
                    )
                }
            }
            if (intent == AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE) {
                runCatching {
                    auditLogger.info(
                        "sensitivity_source_change_cycle_completed",
                        mapOf(
                            "status" to "success",
                            "durationMs" to (System.currentTimeMillis() - now),
                            "cycleId" to acceptedSnapshot?.forecastCycleId.orEmpty()
                        )
                    )
                }
                return acceptedSnapshot
            }
            auditLogger.info(
                "automation_cycle_finished",
                mapOf(
                    "status" to "success",
                    "durationMs" to (System.currentTimeMillis() - now)
                )
            )
            acceptedSnapshot
        } catch (blocked: AcceptedForecastGenerationNotFresh) {
            currentCoroutineContext().ensureActive()
            auditLogger.info(
                "automation_cycle_finished",
                mapOf(
                    "status" to "blocked",
                    "reason" to blocked.reason,
                    "cycleAccepted" to false,
                    "intent" to intent.name,
                    "durationMs" to (System.currentTimeMillis() - now)
                )
            )
            if (intent == AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE) throw blocked
            null
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
        }
    }

    private suspend fun runSensitivitySettingsRestartUnderOwnedLease(
        mutation: SensitivitySettingsMutation,
        beforeClinicalSideEffects: (suspend () -> Unit)? = null
    ): SensitivityRuntimeSnapshot? {
        val applied = settingsStore.settings.first()
        require(applied.sensitivityRuntimeFingerprint() == mutation.appliedFingerprint)
        val expectation = SensitivitySettingsAcceptanceExpectation(
            settingsRevision = applied.sensitivitySettingsRevision,
            isfSource = applied.isfSourcePreference,
            crSource = applied.crSourcePreference,
            fingerprint = mutation.appliedFingerprint
        )
        return runWithFailedSensitivitySettingsRestoration(mutation) {
            requireNotNull(
                runAutomationCycleLocked(
                    intent = AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
                    bootstrapPolicy = resolveCyclePolicyStatic(
                        intent = AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
                        therapyActionsArmed = applied.therapyActionsArmed,
                        killSwitch = applied.killSwitch,
                        powerSaveActive = PowerSaveController.isActive(applied)
                    ),
                    expectedSettings = expectation,
                    beforeClinicalSideEffects = beforeClinicalSideEffects
                )
            ) { "automatic sensitivity setting change did not publish an accepted cycle" }
        }
    }

    private suspend fun runAutomationCycleLocked(
        intent: AutomationCycleIntent,
        bootstrapPolicy: AutomationCyclePolicy,
        expectedSettings: SensitivitySettingsAcceptanceExpectation?,
        beforeClinicalSideEffects: (suspend () -> Unit)? = null
    ): SensitivityRuntimeSnapshot? {
        mealRuntimeCapture.invalidate()
        var settings = settingsStore.settings.first()
        runRemoteRefreshStatic(bootstrapPolicy) {
            runCycleStep("auto_connect_bootstrap") {
                autoConnectRepository.bootstrap()
            }
            runCycleStep("root_db_sync") {
                rootDbRepository.syncIfEnabled()
            }
            runNonFatalCycleStep(name = "nightscout_sync") {
                syncRepository.syncNightscoutIncremental()
            }
            runNonFatalCycleStep(name = "cloud_push_sync", timeoutMs = CLOUD_PUSH_STEP_TIMEOUT_MS) {
                syncRepository.pushCloudIncremental()
            }
            runCycleStep("baseline_import") {
                maybeRunBaselineImport(settings = settings, nowTs = System.currentTimeMillis())
            }
            runCycleStep("db_housekeeping") {
                runDbHousekeepingIfDue(nowTs = System.currentTimeMillis(), settings = settings)
            }
            maybeRecalculateAnalytics(settings)
            settings = settingsStore.settings.first()
            runNonFatalCycleStep("sensitivity_candidate_refresh") {
                isfCrRepository.computeRealtimeSnapshot(
                    settings = settings,
                    nowTs = System.currentTimeMillis()
                )
            }
        }
        val sensitivitySnapshot = runCycleStep("sensitivity_runtime_snapshot") {
            sensitivityRuntimeRepository.recompute(SensitivityRuntimeTrigger.FORECAST)
        }
        val sensitivityCandidateInputGeneration =
            sensitivityRuntimeRepository.isfCrInputGenerationForCandidate(sensitivitySnapshot)
        require(sensitivitySnapshot.settingsRevision == settings.sensitivitySettingsRevision) {
            "sensitivity snapshot/settings revision mismatch"
        }
        expectedSettings?.let { expectation ->
            requireSensitivitySettingsExpectation(sensitivitySnapshot, settings, expectation)
        }
        val sensitivityFanOut = SensitivityRuntimeFanOut(sensitivitySnapshot)
        auditCycleCheckpoint("post_initial_steps")

        val rawGlucose = syncRepository.recentGlucose(limit = 72)
        if (rawGlucose.isEmpty()) {
            auditLogger.warn("automation_skipped", mapOf("reason" to "no_glucose_data"))
            return null
        }
        // Freeze telemetry before the cycle clock and slow calibration/history preparation.
        val resolvedTelemetry = resolveLatestTelemetry(System.currentTimeMillis(), settings)
        val now = System.currentTimeMillis()
        val calibrationRuntime = prepareCalibrationRuntimeContext(
            rawGlucose = rawGlucose,
            nowTs = now,
            settings = settings,
            intent = intent,
            resolvedTelemetry = resolvedTelemetry
        )
        val preparedCalibration = calibrationRuntime.preparedCalibration
        val resolvedGlucose = calibrationRuntime.resolvedGlucose
        val glucose = calibrationRuntime.glucose
        val therapy = calibrationRuntime.therapy
        auditCycleCheckpoint(
            stage = "post_recent_data",
            metadata = mapOf(
                "glucosePoints" to glucose.size,
                "therapyEvents" to therapy.size
            )
        )
        val sensorLagGlucoseHistory = calibrationRuntime.sensorLagGlucoseHistory
        val sensorLagTherapyHistory = calibrationRuntime.sensorLagTherapyHistory
        val sensorLagRuntimeContext = calibrationRuntime.sensorLagRuntimeContext
        val latestTelemetry = calibrationRuntime.latestTelemetry
        val deliveryTrustTelemetry = calibrationRuntime.deliveryTrustTelemetry.toMutableList()
        val latestResolved = calibrationRuntime.latestResolved
        val latestCheck = calibrationRuntime.latestCheck
        val prepared = preparePredictionPreparationContext(
            settings = settings,
            nowTs = now,
            glucose = glucose,
            therapy = therapy,
            latestTelemetry = latestTelemetry,
            sensitivityRuntime = sensitivityFanOut.contextFor(SensitivityRuntimeConsumer.FORECAST_5_30_60),
            allowActionRepositoryAccess = bootstrapPolicy.allowActionRepositoryAccess,
            allowLocalSafetyEvidence = bootstrapPolicy.allowLocalSafetyEvidence
        )
        val realtimeIsfCrSnapshot = prepared.realtimeIsfCrSnapshot
        val realtimeInputGeneration = realtimeIsfCrSnapshot?.let { snapshot ->
            requireNotNull(realtimeSnapshotInputGeneration(snapshot)) {
                "realtime ISF/CR snapshot has no input generation"
            }
        }
        val expectedIsfCrInputGeneration = listOfNotNull(
            sensitivityCandidateInputGeneration,
            realtimeInputGeneration
        ).distinct().also { generations ->
            require(generations.size <= 1) {
                "sensitivity cycle mixed ISF/CR input generations"
            }
        }.single()
        val isfCrRuntimeGate = prepared.isfCrRuntimeGate
        val runtimeCobIob = prepared.runtimeCobIob
        val insulinTherapyAvailable = prepared.insulinTherapyAvailable
        val currentPattern = prepared.currentPattern
        val latestGlucose = prepared.latestGlucose
        val effectiveStaleMaxMinutes = prepared.effectiveStaleMaxMinutes
        val dataFresh = prepared.dataFresh
        val actionsLast6h = prepared.actionsLast6h
        val activeAapsTarget = prepared.activeAapsTarget
        val localSafetyChronologyResolved = prepared.localSafetyChronologyResolved
        val localSafetyCausalThroughTs = prepared.localSafetyCausalThroughTs
        val latestAutomaticSent = prepared.latestAutomaticSent
        val activeTempTarget = activeAapsTarget
            ?.takeIf(ActiveAapsTarget::evidenceResolved)
            ?.targetMmol
        val sensorQuality = prepared.sensorQuality
        val sensorBlocked = prepared.sensorBlocked
        val insulinCycleFanOut = prepared.insulinCycleFanOut
        val forecastInsulinCycle = insulinCycleFanOut.contextFor(InsulinCycleConsumer.FORECAST)
        val targetInsulinCycle = insulinCycleFanOut.contextFor(InsulinCycleConsumer.TARGET)
        val circadianPrior = prepared.circadianPrior
        val currentProfile = prepared.currentProfile
        val currentSegment = prepared.currentSegment
        val forecastRuntime = buildForecastRuntimeContext(
            settings = settings,
            nowTs = now,
            glucose = glucose,
            therapy = therapy,
            latestTelemetry = latestTelemetry,
            latestGlucose = latestGlucose,
            sensorLagGlucoseHistory = sensorLagGlucoseHistory,
            sensorLagTherapyHistory = sensorLagTherapyHistory,
            sensorLagRuntimeContext = sensorLagRuntimeContext,
            effectiveStaleMaxMinutes = effectiveStaleMaxMinutes,
            sensorQuality = sensorQuality,
            sensorBlocked = sensorBlocked,
            effectiveCobGrams = runtimeCobIob.cobGrams,
            insulinCycleContext = forecastInsulinCycle,
            currentPattern = currentPattern,
            circadianPrior = circadianPrior,
            sensitivityRuntime = prepared.sensitivityRuntime,
            uamSensitivityRuntime = sensitivityFanOut.contextFor(SensitivityRuntimeConsumer.UAM),
            calibrationIdentity = preparedCalibration.identity
        )
        val mergedForecasts = forecastRuntime.mergedForecasts
        val controlForecasts = forecastRuntime.controlForecasts
        val lagCorrectedForecasts = forecastRuntime.lagCorrectedForecasts
        val calibrationApplied = forecastRuntime.calibrationApplied
        val contextBiasApplied = forecastRuntime.contextBiasApplied
        val cobIobBiasApplied = forecastRuntime.cobIobBiasApplied
        val circadianBiasApplied = forecastRuntime.circadianBiasApplied
        auditForecastFactorCoverage(
            nowTs = now,
            latestTelemetry = latestTelemetry,
            realtimeIsfCrSnapshot = realtimeIsfCrSnapshot,
            runtimeGate = isfCrRuntimeGate,
            runtimeCobIob = runtimeCobIob,
            currentPattern = currentPattern,
            calibrationSampleCount = forecastRuntime.calibrationSampleCount,
            calibrationApplied = calibrationApplied,
            contextBiasApplied = contextBiasApplied,
            cobIobBiasApplied = cobIobBiasApplied,
            circadianPrior = circadianPrior,
            circadianBiasApplied = circadianBiasApplied,
            insulinTherapyAvailable = insulinTherapyAvailable
        )

        val authoritativeSettings = settingsStore.settings.first()
        require(authoritativeSettings.sensitivitySettingsRevision == sensitivitySnapshot.settingsRevision) {
            "settings revision changed before accepted sensitivity cycle publication"
        }
        expectedSettings?.let { expectation ->
            requireSensitivitySettingsExpectation(sensitivitySnapshot, authoritativeSettings, expectation)
        }
        val acceptedIdentity = authoritativeSettings.sensitivityRuntimeIdentity()
        require(sensitivitySnapshot.isf.requested == acceptedIdentity.isfSource) {
            "accepted sensitivity ISF source does not match authoritative settings"
        }
        require(sensitivitySnapshot.cr.requested == acceptedIdentity.crSource) {
            "accepted sensitivity CR source does not match authoritative settings"
        }
        val acceptedForecastCandidate = controlForecasts
        val expectedForecastGeneration = requireNotNull(
            resolveAcceptedForecastTimestampStatic(acceptedForecastCandidate)
        ) { "accepted sensitivity cycle requires one 5/30/60 forecast generation timestamp" }
        val expectedAcceptedDecomposition = forecastRuntime.forecastDecomposition.toAcceptedDecomposition()
        val expectedAcceptedForecastRows = acceptedForecastCandidate.map { forecast ->
            SensitivityAcceptedForecastRow(
                horizonMinutes = forecast.horizonMinutes,
                targetTimestamp = forecast.ts,
                valueMmol = forecast.valueMmol,
                ciLow = forecast.ciLow,
                ciHigh = forecast.ciHigh,
                modelVersion = forecast.modelVersion
            )
        }.sortedBy(SensitivityAcceptedForecastRow::horizonMinutes)
        val acceptedAtTs = allocateAcceptedSensitivityMarkerStatic(
            telemetryDao = db.telemetryDao(),
            wallClockTs = now
        )
        // Allocation may suspend; the tuple's sequence marker is not the freshness clock.
        requireFreshAcceptedForecastGenerationStatic(expectedForecastGeneration, System.currentTimeMillis())
        var authenticatedRoomTuple: AcceptedSensitivityRoomTuple? = null
        val decisions = runAfterAcceptedSensitivityCycleStatic(
            persistPendingRoomTuple = {
                persistPendingSensitivityCycle(
                    acceptedAtTs = acceptedAtTs,
                    snapshot = sensitivitySnapshot,
                    forecasts = acceptedForecastCandidate,
                    decomposition = forecastRuntime.forecastDecomposition
                )
            },
            reserveAccepted = {
                sensitivityRuntimeRepository.reserveAccepted(
                    snapshot = sensitivitySnapshot,
                    acceptedAtTs = acceptedAtTs,
                    acceptedCycleId = sensitivitySnapshot.forecastCycleId
                )
            },
            commitAcceptedRoomTuple = {
                commitAcceptedSensitivityCycle(
                    acceptedAtTs = acceptedAtTs,
                    snapshot = sensitivitySnapshot,
                    forecasts = acceptedForecastCandidate,
                    preparedCalibration = preparedCalibration,
                    expectedIsfCrInputGeneration = expectedIsfCrInputGeneration
                )
            },
            reconcileAcceptedRoomTuple = {
                val accepted = glucoseCalibrationRepository.readPreparedCalibrationAcceptance(
                    prepared = preparedCalibration,
                    acceptedAtTs = acceptedAtTs
                ) {
                    AcceptedSensitivityTupleRoomLoader(db).loadExact(
                        currentSettings = acceptedIdentity,
                        acceptedAtTs = acceptedAtTs,
                        atTs = System.currentTimeMillis()
                    )
                }
                authenticatedRoomTuple = accepted
                accepted?.acceptedAtTs == acceptedAtTs &&
                    accepted.snapshot == sensitivitySnapshot &&
                    accepted.accepted.generationTimestamp == expectedForecastGeneration &&
                    accepted.accepted.decomposition == expectedAcceptedDecomposition &&
                    accepted.forecasts.map { forecast ->
                        SensitivityAcceptedForecastRow(
                            horizonMinutes = forecast.horizonMinutes,
                            targetTimestamp = forecast.timestamp,
                            valueMmol = forecast.valueMmol,
                            ciLow = forecast.ciLow,
                            ciHigh = forecast.ciHigh,
                            modelVersion = forecast.modelVersion
                        )
                    }.sortedBy(SensitivityAcceptedForecastRow::horizonMinutes) ==
                    expectedAcceptedForecastRows
            },
            finalizeAccepted = sensitivityRuntimeRepository::finalizeAccepted,
            abortReservation = sensitivityRuntimeRepository::abortAcceptedReservation,
            beforeClinicalSideEffects = beforeClinicalSideEffects,
            clinicalSideEffects = acceptedEffects@{
                val acceptedClinicalForecasts = requireAcceptedClinicalForecastsStatic(
                    requireNotNull(authenticatedRoomTuple) {
                        "accepted clinical forecast readback was not retained"
                    }
                )
                mealRuntimeCapture.publish(
                    engine = predictionEngine,
                    glucose = glucose,
                    therapy = therapy,
                    localForecasts = forecastRuntime.rawLocalForecasts,
                    sourceSensitivity = sensitivitySnapshot,
                    sourceCalibration = forecastRuntime.calibrationIdentity,
                    roomTuple = requireNotNull(authenticatedRoomTuple)
                )
                val acceptedForecasts = acceptedClinicalForecasts.forecasts
                val acceptedUnifiedUam = bindUnifiedUamToAcceptedForecastsStatic(
                    unified = forecastRuntime.unifiedUam,
                    authority = acceptedClinicalForecasts
                )
                publishAcceptedCycleStateStatic(
                    intent = intent,
                    acceptedSnapshot = sensitivitySnapshot,
                    publishUiTelemetry = { acceptedSnapshot ->
                        persistAcceptedCycleRuntimeTelemetry(
                            nowTs = now,
                            settings = authoritativeSettings,
                            acceptedSnapshot = acceptedSnapshot,
                            prepared = prepared,
                            forecastRuntime = forecastRuntime,
                            latestTelemetry = latestTelemetry,
                            latestResolved = latestResolved,
                            latestCheck = latestCheck,
                            preparedCalibration = preparedCalibration,
                            acceptedClinicalForecastAuthority = acceptedClinicalForecasts,
                            acceptedUnifiedUam = acceptedUnifiedUam
                        )
                    },
                    runLocalMaintenance = {
                        runAcceptedLocalMaintenance(
                            nowTs = now,
                            settings = authoritativeSettings,
                            therapy = therapy
                        )
                    }
                )
                settings = authoritativeSettings

                val lowGlucoseSafetyCycle = prepareLowGlucoseSafetyCycle(
                    nowTs = now,
                    currentGlucoseMmol = forecastRuntime.effectiveCurrentGlucoseMmol,
                    forecasts = acceptedForecasts,
                    activeTempTarget = activeTempTarget,
                    mode = ruleEvaluationModeStatic(intent)
                )
                val effectiveBaseTarget = resolveCircadianEffectiveBaseTarget(
                    settings = settings,
                    nowTs = now,
                    controlForecasts = acceptedForecasts,
                    runtimeCobIob = runtimeCobIob,
                    insulinCycleContext = targetInsulinCycle,
                    sensorQuality = sensorQuality,
                    sensorBlocked = sensorBlocked,
                    lowGlucoseSafetyState = lowGlucoseSafetyCycle.state,
                    latestTelemetry = latestTelemetry
                )

                val context = RuleContext(
                    nowTs = now,
                    glucose = glucose,
                    therapyEvents = therapy,
                    forecasts = acceptedForecasts,
                    currentDayPattern = currentPattern,
                    baseTargetMmol = effectiveBaseTarget.effectiveTargetMmol,
                    postHypoThresholdMmol = settings.postHypoThresholdMmol,
                    postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m,
                    postHypoTargetMmol = settings.postHypoTargetMmol,
                    postHypoDurationMinutes = settings.postHypoDurationMinutes,
                    postHypoLookbackMinutes = settings.postHypoLookbackMinutes,
                    dataFresh = dataFresh,
                    activeTempTargetMmol = activeTempTarget,
                    actionsLast6h = actionsLast6h,
                    actionChronologyResolved = localSafetyChronologyResolved,
                    sensorBlocked = sensorBlocked,
                    currentGlucoseMmol = forecastRuntime.effectiveCurrentGlucoseMmol,
                    currentProfileEstimate = currentProfile,
                    currentProfileSegment = currentSegment,
                    latestTelemetry = latestTelemetry,
                    safetyIobUnits = targetInsulinCycle.safetyIobUnits,
                    retargetCooldownMinutes = settings.adaptiveControllerRetargetMinutes,
                    adaptiveMaxStepMmol = settings.adaptiveControllerMaxStepMmol,
                    adaptiveMinTargetMmol = settings.safetyMinTargetMmol,
                    adaptiveMaxTargetMmol = settings.safetyMaxTargetMmol
                )
                val writerSettings = settingsStore.settings.first()
                require(writerSettings.sensitivitySettingsRevision == sensitivitySnapshot.settingsRevision) {
                    "settings revision changed before therapy writer stage"
                }
                val writerPolicy = restrictCyclePolicyStatic(
                    bootstrapPolicy = bootstrapPolicy,
                    intent = intent,
                    therapyActionsArmed = writerSettings.therapyActionsArmed,
                    killSwitch = writerSettings.killSwitch,
                    powerSaveActive = PowerSaveController.isActive(writerSettings)
                )
                settings = writerSettings
                val acceptedDecisions = runAcceptedClinicalCalculationFanOutStatic(
                    intent = intent,
                    policy = writerPolicy,
                    acceptedSnapshot = sensitivitySnapshot,
                    acceptedClinicalForecastAuthority = acceptedClinicalForecasts,
                    unifiedUam = acceptedUnifiedUam,
                    writeUamCarbs = {
                        processUnifiedUamExport(
                            nowTs = now,
                            settings = settings,
                            unified = acceptedUnifiedUam,
                            acceptedClinicalForecastAuthority = acceptedClinicalForecasts,
                            currentGlucoseMmol = forecastRuntime.effectiveCurrentGlucoseMmol,
                            latestTelemetry = latestTelemetry
                        )
                    },
                    evaluateRulesAndTargetManager = { targetSensitivityRuntime, externalWritesAllowed ->
                        requireCycleSensitivityRuntime(
                            context = targetSensitivityRuntime,
                            expectedConsumer = SensitivityRuntimeConsumer.TARGET_MANAGER,
                            cycleSnapshot = sensitivitySnapshot
                        )
                        executeRulesAndActions(
                            settings = settings,
                            nowTs = now,
                            context = context,
                            controlForecasts = acceptedForecasts,
                            lagCorrectedForecasts = lagCorrectedForecasts,
                            activeAapsTarget = activeAapsTarget,
                            activeTempTarget = activeTempTarget,
                            effectiveBaseTarget = effectiveBaseTarget,
                            dataFresh = dataFresh,
                            actionsLast6h = actionsLast6h,
                            localSafetyChronologyResolved = localSafetyChronologyResolved,
                            localSafetyCausalThroughTs = localSafetyCausalThroughTs,
                            latestAutomaticSent = latestAutomaticSent,
                            sensorBlocked = sensorBlocked,
                            sensorQuality = sensorQuality,
                            lowGlucoseSafetyCycle = lowGlucoseSafetyCycle,
                            latestTelemetry = latestTelemetry,
                            deliveryTrustTelemetry = deliveryTrustTelemetry,
                            forecastRuntime = forecastRuntime,
                            insulinCycleContext = targetInsulinCycle,
                            sensitivityRuntime = targetSensitivityRuntime,
                            acceptedClinicalForecastAuthority = acceptedClinicalForecasts,
                            externalWritesAllowed = externalWritesAllowed,
                            mode = ruleEvaluationModeStatic(intent)
                        )
                    },
                    assessAlertCause = { alertSensitivityRuntime, unifiedUam ->
                        evaluateAlertCauseDiagnostic(
                            settings = settings,
                            nowTs = now,
                            latestGlucose = latestGlucose,
                            latestResolved = latestResolved,
                            controlForecasts = acceptedForecasts,
                            dataFresh = dataFresh,
                            sensorBlocked = sensorBlocked,
                            sensorQuality = sensorQuality,
                            insulinCycleContext = insulinCycleFanOut.contextFor(InsulinCycleConsumer.ALERTS),
                            sensitivityRuntime = alertSensitivityRuntime,
                            unifiedUam = unifiedUam,
                            deliveryTrustTelemetry = deliveryTrustTelemetry,
                            effectiveBaseTarget = effectiveBaseTarget,
                            circadianBiasApplied = forecastRuntime.circadianBiasApplied,
                            circadianPrior = circadianPrior,
                            therapy = therapy,
                            acceptedClinicalForecastAuthority = acceptedClinicalForecasts
                        )
                    },
                    publishAlerts = { alertSensitivityRuntime, unifiedUam ->
                        evaluateAndPublishGlucoseAlerts(
                            settings = settings,
                            nowTs = now,
                            latestGlucose = latestGlucose,
                            latestResolved = latestResolved,
                            controlForecasts = acceptedForecasts,
                            dataFresh = dataFresh,
                            sensorBlocked = sensorBlocked,
                            sensorQuality = sensorQuality,
                            insulinCycleContext = insulinCycleFanOut.contextFor(InsulinCycleConsumer.ALERTS),
                            sensitivityRuntime = alertSensitivityRuntime,
                            unifiedUam = unifiedUam,
                            deliveryTrustTelemetry = deliveryTrustTelemetry,
                            effectiveBaseTarget = effectiveBaseTarget,
                            circadianBiasApplied = forecastRuntime.circadianBiasApplied,
                            circadianPrior = circadianPrior,
                            therapy = therapy,
                            acceptedClinicalForecastAuthority = acceptedClinicalForecasts
                        )
                        publishDeliveryDiagnostic(
                            settings = settings, nowTs = now, glucose = latestGlucose,
                            sensorTrusted = dataFresh && !sensorBlocked && !sensorQuality.blocked &&
                                !sensorQuality.suspectFalseLow && sensorQuality.score in 0.7..1.0,
                            insulin = insulinCycleFanOut.contextFor(InsulinCycleConsumer.ALERTS),
                            sensitivity = alertSensitivityRuntime, uam = unifiedUam,
                            forecasts = acceptedClinicalForecasts, calibration = forecastRuntime.calibrationIdentity
                        )
                    },
                    publishAcceptedForecast = {
                        onWidgetDataChanged?.invoke()
                    }
                )
                runAcceptedSensitivityMaintenance(
                    policy = writerPolicy,
                    settings = settings,
                    nowTs = now,
                    realtimeSnapshot = realtimeIsfCrSnapshot,
                    latestTelemetry = latestTelemetry
                )
                acceptedDecisions
            }
        )

        if (intent == AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE) return sensitivitySnapshot

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
        return sensitivitySnapshot
    }

    private suspend fun persistPendingSensitivityCycle(
        acceptedAtTs: Long,
        snapshot: SensitivityRuntimeSnapshot,
        forecasts: List<Forecast>,
        decomposition: ForecastDecompositionSnapshot?
    ) {
        val forecastTimestamp = requireNotNull(
            resolveAcceptedForecastTimestampStatic(forecasts)
        ) {
            "accepted sensitivity cycle requires one 5/30/60 forecast generation timestamp"
        }
        val acceptedDecomposition = decomposition.toAcceptedDecomposition()
        val encodedDecomposition = SensitivityAcceptedForecastDecompositionCodec.encode(acceptedDecomposition)
        val digest = requireNotNull(
            SensitivityAcceptedForecastDigest.compute(
                cycleId = snapshot.forecastCycleId,
                settingsRevision = snapshot.settingsRevision,
                forecasts = forecasts.map { forecast ->
                    SensitivityAcceptedForecastRow(
                        horizonMinutes = forecast.horizonMinutes,
                        targetTimestamp = forecast.ts,
                        valueMmol = forecast.valueMmol,
                        ciLow = forecast.ciLow,
                        ciHigh = forecast.ciHigh,
                        modelVersion = forecast.modelVersion
                    )
                },
                decomposition = acceptedDecomposition
            )
        ) { "accepted sensitivity cycle forecast digest is invalid" }
        db.telemetryDao().upsertAcceptedSensitivityTuple(
            listOf(
                TelemetrySampleEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = acceptedAtTs,
                    source = SENSITIVITY_ACCEPTED_SOURCE,
                    key = SENSITIVITY_ACCEPTED_CYCLE_ID_KEY,
                    valueDouble = null,
                    valueText = snapshot.forecastCycleId,
                    unit = null,
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = acceptedAtTs,
                    source = SENSITIVITY_ACCEPTED_SOURCE,
                    key = SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
                    valueDouble = null,
                    valueText = encodedDecomposition,
                    unit = null,
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = acceptedAtTs,
                    source = SENSITIVITY_ACCEPTED_SOURCE,
                    key = SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
                    valueDouble = snapshot.settingsRevision.toDouble(),
                    valueText = null,
                    unit = "revision",
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = acceptedAtTs,
                    source = SENSITIVITY_ACCEPTED_SOURCE,
                    key = SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
                    valueDouble = forecastTimestamp.toDouble(),
                    valueText = null,
                    unit = "epoch_ms",
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = acceptedAtTs,
                    source = SENSITIVITY_ACCEPTED_SOURCE,
                    key = SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
                    valueDouble = null,
                    valueText = digest,
                    unit = "sha256",
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = acceptedAtTs,
                    source = SENSITIVITY_ACCEPTED_SOURCE,
                    key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                    valueDouble = null,
                    valueText = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING,
                    unit = null,
                    quality = "OK"
                )
            )
        )
    }

    private suspend fun commitAcceptedSensitivityCycle(
        acceptedAtTs: Long,
        snapshot: SensitivityRuntimeSnapshot,
        forecasts: List<Forecast>,
        preparedCalibration: PreparedCalibrationCycle,
        expectedIsfCrInputGeneration: IsfCrInputGeneration
    ) {
        val rows = forecasts.map { it.toForecastEntity() }
        glucoseCalibrationRepository.commitPreparedCalibrationAcceptance(
            prepared = preparedCalibration,
            acceptedAtTs = acceptedAtTs
        ) {
            commitAcceptedSensitivityCycleStatic(
                db = db,
                acceptedAtTs = acceptedAtTs,
                snapshot = snapshot,
                forecastRows = rows,
                expectedIsfCrInputGeneration = expectedIsfCrInputGeneration
            )
        }
        auditLogger.infoThrottled(
            throttleKey = "forecast_storage_accepted",
            intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
            message = "forecast_storage_accepted",
            metadata = mapOf(
                "insertedRows" to rows.size,
                "cycleId" to snapshot.forecastCycleId,
                "settingsRevision" to snapshot.settingsRevision
            )
        )
    }

    private fun requireSensitivitySettingsExpectation(
        snapshot: SensitivityRuntimeSnapshot,
        settings: AppSettings,
        expectation: SensitivitySettingsAcceptanceExpectation
    ) {
        require(snapshot.settingsRevision == expectation.settingsRevision) {
            "accepted sensitivity settings revision mismatch"
        }
        require(snapshot.isf.requested == expectation.isfSource) {
            "accepted ISF requested source mismatch"
        }
        require(snapshot.cr.requested == expectation.crSource) {
            "accepted CR requested source mismatch"
        }
        require(settings.sensitivitySettingsRevision == expectation.settingsRevision) {
            "current sensitivity settings revision mismatch"
        }
        require(settings.sensitivityRuntimeFingerprint() == expectation.fingerprint) {
            "current sensitivity runtime fingerprint mismatch"
        }
    }

    private fun ForecastDecompositionSnapshot?.toAcceptedDecomposition() =
        this?.let { value ->
            SensitivityAcceptedForecastDecomposition(
                trend60Mmol = value.trend60Mmol,
                therapy60Mmol = value.therapy60Mmol,
                uam60Mmol = value.uam60Mmol,
                residualRoc0Mmol5 = value.residualRoc0Mmol5,
                sigmaEMmol5 = value.sigmaEMmol5,
                kfSigmaGMmol = value.kfSigmaGMmol,
                modelVersion = value.modelVersion
            )
        } ?: SensitivityAcceptedForecastDecomposition.unavailable()

    private suspend fun persistAcceptedCycleRuntimeTelemetry(
        nowTs: Long,
        settings: AppSettings,
        acceptedSnapshot: SensitivityRuntimeSnapshot,
        prepared: PredictionPreparationContext,
        forecastRuntime: ForecastRuntimeContext,
        latestTelemetry: Map<String, Double?>,
        latestResolved: io.aaps.copilot.domain.model.ResolvedGlucosePoint?,
        latestCheck: io.aaps.copilot.domain.model.BloodGlucoseCheck?,
        preparedCalibration: PreparedCalibrationCycle,
        acceptedClinicalForecastAuthority: AcceptedClinicalForecasts,
        acceptedUnifiedUam: UnifiedUamRuntimeSnapshot
    ) {
        val acceptedForecasts = acceptedClinicalForecastAuthority.forecasts
        require(forecastRuntime.sensitivityRuntime.snapshot === acceptedSnapshot) {
            "runtime telemetry must use the exact accepted sensitivity snapshot"
        }
        require(acceptedUnifiedUam.sensitivityCycleId == acceptedSnapshot.forecastCycleId) {
            "UAM telemetry cycle does not match accepted sensitivity cycle"
        }
        require(
            acceptedUnifiedUam.sensitivitySettingsRevision == acceptedSnapshot.settingsRevision
        ) { "UAM telemetry revision does not match accepted sensitivity cycle" }
        requireUnifiedUamAcceptedForecastAuthorityStatic(
            unified = acceptedUnifiedUam,
            authority = acceptedClinicalForecastAuthority
        )
        requireExactCalibrationIdentityStatic(
            prepared = preparedCalibration.identity,
            forecast = forecastRuntime.calibrationIdentity,
            uam = requireNotNull(acceptedUnifiedUam.calibrationIdentity),
            targetManager = forecastRuntime.calibrationIdentity,
            ui = preparedCalibration.identity
        )

        persistRealtimeIsfCrTelemetry(nowTs = nowTs, latestTelemetry = latestTelemetry)
        persistRuntimeDiaTelemetry(nowTs = nowTs, runtimeDia = prepared.runtimeDia)
        persistRuntimeCobIobTelemetry(
            nowTs = nowTs,
            runtime = prepared.runtimeCobIob,
            rawCobGrams = prepared.rawCobFromTelemetry,
            rawIobUnits = prepared.rawIobFromTelemetry
        )
        persistSensorQualityTelemetry(nowTs = nowTs, assessment = prepared.sensorQuality)
        prepared.circadianPrior?.let { prior ->
            db.telemetryDao().upsertAll(
                buildCircadianPriorTelemetryRowsStatic(
                    nowTs = nowTs,
                    prior = prior,
                    weight30 = settings.circadianForecastWeight30,
                    weight60 = settings.circadianForecastWeight60
                )
            )
        }
        persistActivityForecastTelemetry(
            nowTs = nowTs,
            context = forecastRuntime.activityEffectContext,
            plan = forecastRuntime.activityForecastPlan
        )
        persistGlucoseCalibrationTelemetry(
            nowTs = nowTs,
            latestResolved = latestResolved,
            calibrationModel = preparedCalibration.model,
            latestCheck = latestCheck
        )
        persistSensorLagTelemetry(
            nowTs = nowTs,
            estimate = forecastRuntime.sensorLagEstimate,
            latestGlucoseInput = forecastRuntime.latestGlucoseInput,
            controlForecasts = acceptedForecasts,
            candidateForecasts = forecastRuntime.lagCorrectedForecasts,
            shadowRuleChanged = null,
            shadowTargetDeltaMmol = null
        )
        persistForecastDecompositionTelemetry(
            nowTs = nowTs,
            decomposition = forecastRuntime.forecastDecomposition,
            acceptedCycleId = acceptedSnapshot.forecastCycleId
        )
        db.telemetryDao().upsertAll(
            buildAcceptedClinicalForecastTelemetryRowsStatic(
                nowTs = nowTs,
                source = ACCEPTED_RUNTIME_TELEMETRY_SOURCE,
                keyPrefix = "overview_accepted_forecast",
                authority = acceptedClinicalForecastAuthority
            )
        )
        persistUnifiedUamTelemetry(
            nowTs = nowTs,
            unified = acceptedUnifiedUam,
            acceptedClinicalForecastAuthority = acceptedClinicalForecastAuthority
        )
    }

    private suspend fun runAcceptedLocalMaintenance(
        nowTs: Long,
        settings: AppSettings,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>
    ) {
        val removedInvalidTelemetryTs = db.telemetryDao().deleteByTimestampAtOrBelow(0L)
        if (removedInvalidTelemetryTs > 0) {
            auditLogger.info(
                "telemetry_invalid_timestamp_cleanup",
                mapOf("removedRows" to removedInvalidTelemetryTs)
            )
        }
        // Accepted publication already replaces each timestamp/horizon in one transaction.
        // Do not scan all forecast history for duplicates on the realtime path.
        db.forecastDao().deleteOlderThan(nowTs - resolveHistoryRetentionMs(settings))
        refreshRealInsulinProfileTelemetry(nowTs = nowTs, settings = settings)
        require(therapy.none { it.ts > nowTs }) { "accepted maintenance therapy must be causal" }
        energyProfileRepository.refreshPendingSelections(therapy)
    }

    private suspend fun runAcceptedSensitivityMaintenance(
        policy: AutomationCyclePolicy,
        settings: AppSettings,
        nowTs: Long,
        realtimeSnapshot: IsfCrRealtimeSnapshot?,
        latestTelemetry: Map<String, Double?>
    ) {
        runSensitivityMaintenanceActionStatic(policy) {
            recoverAndScheduleRealtimeIsfCrRefresh(settings = settings, nowTs = nowTs)
        }
        if (realtimeSnapshot?.mode?.name == "SHADOW") {
            runSensitivityMaintenanceActionStatic(policy) {
                maybeProcessIsfCrShadowAutoActivation(
                    settings = settings,
                    nowTs = nowTs,
                    latestTelemetry = latestTelemetry
                )
            }
        }
    }

    private suspend fun recoverAndScheduleRealtimeIsfCrRefresh(
        settings: AppSettings,
        nowTs: Long
    ) {
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
                mapOf("reason" to "stale_in_flight_guard", "hadActiveJob" to hadActiveJob)
            )
        }
        val latest = runCatching { isfCrRepository.latestSnapshot() }.getOrNull()
        val ageMs = latest?.let { nowTs - it.ts } ?: Long.MAX_VALUE
        val inFailureBackoff = isfCrRealtimeLastFailureTs > 0L &&
            (nowTs - isfCrRealtimeLastFailureTs) < ISFCR_REALTIME_RETRY_BACKOFF_MS
        val shouldProactivelyRefresh = latest != null &&
            ageMs in ISFCR_PROACTIVE_REFRESH_AGE_MS..ISFCR_SNAPSHOT_FRESHNESS_MS
        if (
            !isfCrRealtimeRefreshInFlight &&
            !inFailureBackoff &&
            (latest == null || ageMs > ISFCR_SNAPSHOT_FRESHNESS_MS || shouldProactivelyRefresh)
        ) {
            val reason = when {
                latest == null -> "missing_snapshot"
                ageMs > ISFCR_SNAPSHOT_FRESHNESS_MS -> "stale_snapshot"
                else -> "proactive_refresh"
            }
            scheduleRealtimeIsfCrRefresh(settings = settings, nowTs = nowTs, reason = reason)
        } else if (inFailureBackoff && !isfCrRealtimeRefreshInFlight) {
            auditLogger.infoThrottled(
                throttleKey = "isfcr_realtime_refresh_skipped:backoff",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "isfcr_realtime_refresh_skipped",
                metadata = mapOf("reason" to "backoff")
            )
        } else if (isfCrRealtimeRefreshInFlight) {
            auditLogger.infoThrottled(
                throttleKey = "isfcr_realtime_refresh_skipped:in_flight",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "isfcr_realtime_refresh_skipped",
                metadata = mapOf("reason" to "in_flight")
            )
        }
    }

    private data class GlucoseAlertCauseAssessment(
        val decision: GlucoseAlertDecision,
        val currentGlucoseMmol: Double,
        val preparedCause: PreparedAlertCause?
    )

    private suspend fun assessGlucoseAlertCause(
        settings: AppSettings,
        nowTs: Long,
        latestGlucose: GlucosePoint,
        latestResolved: io.aaps.copilot.domain.model.ResolvedGlucosePoint?,
        controlForecasts: List<Forecast>,
        dataFresh: Boolean,
        sensorBlocked: Boolean,
        sensorQuality: SensorQualityAssessment,
        insulinCycleContext: InsulinCycleContext,
        sensitivityRuntime: SensitivityRuntimeConsumerContext,
        unifiedUam: UnifiedUamRuntimeSnapshot,
        deliveryTrustTelemetry: List<DeliveryTrustTelemetryValue>,
        effectiveBaseTarget: EffectiveBaseTarget,
        circadianBiasApplied: Boolean,
        circadianPrior: CircadianForecastPrior?,
        therapy: List<TherapyEvent>,
        acceptedClinicalForecastAuthority: AcceptedClinicalForecasts,
        persisted: GlucoseAlertRuntimeState
    ): GlucoseAlertCauseAssessment {
        val pred5 = controlForecasts.firstOrNull { it.horizonMinutes == 5 }?.valueMmol
        val pred30 = controlForecasts.firstOrNull { it.horizonMinutes == 30 }?.valueMmol
        val pred60 = controlForecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol
        val ciLow30 = controlForecasts.firstOrNull { it.horizonMinutes == 30 }?.ciLow
        val ciHigh30 = controlForecasts.firstOrNull { it.horizonMinutes == 30 }?.ciHigh
        val currentGlucoseMmol = latestResolved?.calibratedMmol ?: latestGlucose.valueMmol
        val currentAgeMinutes = ((nowTs - latestGlucose.ts).coerceAtLeast(0L)) / 60_000L
        val decision = glucoseAlertEngine.evaluate(
            input = GlucoseAlertInput(
                nowTs = nowTs,
                settings = settings,
                currentGlucoseMmol = currentGlucoseMmol,
                currentGlucoseAgeMinutes = currentAgeMinutes,
                currentGlucoseTimestamp = latestGlucose.ts,
                pred10 = controlForecasts.firstOrNull { it.horizonMinutes == 10 }?.valueMmol,
                pred5 = pred5,
                pred30 = pred30,
                pred60 = pred60,
                ciLow30 = ciLow30,
                ciHigh30 = ciHigh30,
                trendDelta5Mmol = sensorQuality.delta5Mmol,
                staleData = !dataFresh,
                sensorBlocked = sensorBlocked,
                sensorSuspectFalseLow = sensorQuality.suspectFalseLow
            ),
            persisted = persisted
        )
        val preparedCause = prepareAlertCauseForDecisionStatic(decision) {
            val loadedContext = loadOptionalAlertCauseContextStatic {
                loadActiveAlertEventContext(nowTs = nowTs, therapy = therapy)
            }
            val contextResult = when (loadedContext) {
                is AlertCauseContextLoadResult.Success -> {
                    val loaded = loadedContext.value
                    if (loaded.available) {
                        loadedContext
                    } else if (loaded.overflowSource != null) {
                        AlertCauseContextLoadResult.Overflow(loaded.overflowSource)
                    } else {
                        AlertCauseContextLoadResult.Failure(
                            loaded.failureType ?: "EventContextUnavailable"
                        )
                    }
                }
                else -> loadedContext
            }
            reportAlertCauseContextOutcomeStatic(
                result = contextResult,
                stage = decision.episodeStage,
                warn = auditLogger::warn
            )
            val eventContext = (contextResult as? AlertCauseContextLoadResult.Success)?.value
            require(controlForecasts == acceptedClinicalForecastAuthority.forecasts) {
                "alert cause must consume the exact authenticated accepted forecast tuple"
            }
            buildAlertCauseInputStatic(
                nowTs = nowTs,
                currentGlucoseTimestamp = latestGlucose.ts,
                decision = decision,
                forecasts = controlForecasts,
                dataFresh = dataFresh,
                sensorQuality = sensorQuality,
                sensorBlocked = sensorBlocked,
                insulinCycleContext = insulinCycleContext,
                sensitivitySnapshot = sensitivityRuntime.snapshot,
                unifiedUam = unifiedUam,
                deliveryTrust = decodeAlertDeliveryTrustStatic(deliveryTrustTelemetry),
                effectiveBaseTarget = effectiveBaseTarget,
                circadianBiasApplied = circadianBiasApplied,
                circadianDelta30Mmol = circadianPrior?.delta30,
                circadianConfidence = circadianPrior?.confidence,
                activeEventTypes = eventContext?.eventTypes.orEmpty(),
                eventContextAvailable = eventContext?.available == true
            )
        }
        return GlucoseAlertCauseAssessment(
            decision = decision,
            currentGlucoseMmol = currentGlucoseMmol,
            preparedCause = preparedCause
        )
    }

    private suspend fun evaluateAlertCauseDiagnostic(
        settings: AppSettings,
        nowTs: Long,
        latestGlucose: GlucosePoint,
        latestResolved: io.aaps.copilot.domain.model.ResolvedGlucosePoint?,
        controlForecasts: List<Forecast>,
        dataFresh: Boolean,
        sensorBlocked: Boolean,
        sensorQuality: SensorQualityAssessment,
        insulinCycleContext: InsulinCycleContext,
        sensitivityRuntime: SensitivityRuntimeConsumerContext,
        unifiedUam: UnifiedUamRuntimeSnapshot,
        deliveryTrustTelemetry: List<DeliveryTrustTelemetryValue>,
        effectiveBaseTarget: EffectiveBaseTarget,
        circadianBiasApplied: Boolean,
        circadianPrior: CircadianForecastPrior?,
        therapy: List<TherapyEvent>,
        acceptedClinicalForecastAuthority: AcceptedClinicalForecasts
    ) {
        val assessment = assessGlucoseAlertCause(
            settings = settings,
            nowTs = nowTs,
            latestGlucose = latestGlucose,
            latestResolved = latestResolved,
            controlForecasts = controlForecasts,
            dataFresh = dataFresh,
            sensorBlocked = sensorBlocked,
            sensorQuality = sensorQuality,
            insulinCycleContext = insulinCycleContext,
            sensitivityRuntime = sensitivityRuntime,
            unifiedUam = unifiedUam,
            deliveryTrustTelemetry = deliveryTrustTelemetry,
            effectiveBaseTarget = effectiveBaseTarget,
            circadianBiasApplied = circadianBiasApplied,
            circadianPrior = circadianPrior,
            therapy = therapy,
            acceptedClinicalForecastAuthority = acceptedClinicalForecastAuthority,
            persisted = glucoseAlertStateStore.state.first()
        )
        assessment.preparedCause?.let { prepared ->
            db.telemetryDao().upsertAll(
                buildAlertCauseDiagnosticTelemetryRowsStatic(
                    nowTs = nowTs,
                    prepared = prepared
                ) + buildAcceptedClinicalForecastTelemetryRowsStatic(
                    nowTs = nowTs,
                    source = ALERT_CAUSE_DIAGNOSTIC_SOURCE,
                    keyPrefix = "alert_cause_accepted_forecast",
                    authority = acceptedClinicalForecastAuthority
                )
            )
        }
    }

    private suspend fun publishDeliveryDiagnostic(
        settings: AppSettings, nowTs: Long, glucose: GlucosePoint, sensorTrusted: Boolean,
        insulin: InsulinCycleContext, sensitivity: SensitivityRuntimeConsumerContext,
        uam: UnifiedUamRuntimeSnapshot, forecasts: AcceptedClinicalForecasts,
        calibration: GlucoseCalibrationCycleIdentity
    ) {
        val diagnostic = deliveryDiagnostic ?: return
        try {
            val observation = DeliveryDiagnosticObservationMapper.mapAccepted(
                nowTs, glucose, sensorTrusted, insulin, sensitivity, uam, forecasts, calibration
            )
            val lowRisk = glucose.valueMmol < 4.0 || forecasts.forecasts.any { !it.ciLow.isFinite() || it.ciLow < 4.0 }
            diagnostic.accept(nowTs, observation, settings.softAlertEnabled, lowRisk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostic failure must not retry already accepted therapeutic or glucose-alert effects.
            android.util.Log.w("DeliveryDiagnostic", "Advisory evidence or notification unavailable")
        }
    }

    private suspend fun evaluateAndPublishGlucoseAlerts(
        settings: AppSettings,
        nowTs: Long,
        latestGlucose: GlucosePoint,
        latestResolved: io.aaps.copilot.domain.model.ResolvedGlucosePoint?,
        controlForecasts: List<Forecast>,
        dataFresh: Boolean,
        sensorBlocked: Boolean,
        sensorQuality: SensorQualityAssessment,
        insulinCycleContext: InsulinCycleContext,
        sensitivityRuntime: SensitivityRuntimeConsumerContext,
        unifiedUam: UnifiedUamRuntimeSnapshot,
        deliveryTrustTelemetry: List<DeliveryTrustTelemetryValue>,
        effectiveBaseTarget: EffectiveBaseTarget,
        circadianBiasApplied: Boolean,
        circadianPrior: CircadianForecastPrior?,
        therapy: List<TherapyEvent>,
        acceptedClinicalForecastAuthority: AcceptedClinicalForecasts
    ) {
        val persisted = glucoseAlertStateStore.state.first()
        episodeAlertDelivery.importLegacyIfNeeded(persisted, nowTs)
        if (!glucoseAlertRestoreLogged && persisted.activeAlertState != GlucoseAlertState.NONE) {
            auditLogger.info(
                "glucose_alert_state_restored",
                mapOf(
                    "state" to persisted.activeAlertState.name,
                    "direction" to (persisted.activeDirection?.name ?: ""),
                    "lastSoftAlertAtTs" to persisted.lastSoftAlertAtTs,
                    "lastStrongAlertAtTs" to persisted.lastStrongAlertAtTs
                )
            )
            glucoseAlertRestoreLogged = true
        }
        val assessment = assessGlucoseAlertCause(
            settings = settings,
            nowTs = nowTs,
            latestGlucose = latestGlucose,
            latestResolved = latestResolved,
            controlForecasts = controlForecasts,
            dataFresh = dataFresh,
            sensorBlocked = sensorBlocked,
            sensorQuality = sensorQuality,
            insulinCycleContext = insulinCycleContext,
            sensitivityRuntime = sensitivityRuntime,
            unifiedUam = unifiedUam,
            deliveryTrustTelemetry = deliveryTrustTelemetry,
            effectiveBaseTarget = effectiveBaseTarget,
            circadianBiasApplied = circadianBiasApplied,
            circadianPrior = circadianPrior,
            therapy = therapy,
            acceptedClinicalForecastAuthority = acceptedClinicalForecastAuthority,
            persisted = persisted
        )
        val decision = assessment.decision
        val currentGlucoseMmol = assessment.currentGlucoseMmol
        val preparedCause = assessment.preparedCause
        glucoseAlertStateStore.update { decision.nextState }
        val delivery = glucoseAlertNotifier.deliver(
            decision = decision,
            currentGlucoseMmol = currentGlucoseMmol,
            settings = settings,
            causeAnalysis = preparedCause?.analysis,
            causeSnapshot = preparedCause?.snapshot,
            nowTs = nowTs
        )
        persistGlucoseAlertTelemetry(
            nowTs = nowTs,
            decision = decision,
            persisted = decision.nextState,
            delivery = delivery,
            insulinSnapshot = insulinCycleContext.snapshot,
            sensitivitySnapshot = sensitivityRuntime.snapshot
        )
        when (decision.notifyKind) {
            GlucoseAlertNotifyKind.WATCH_60,
            GlucoseAlertNotifyKind.WARNING_30,
            GlucoseAlertNotifyKind.SOFT_HIGH -> auditLogger.info(
                "glucose_soft_alert_triggered",
                mapOf(
                    "state" to decision.state.name,
                    "direction" to (decision.direction?.name ?: ""),
                    "predictedMinutesToLow" to decision.predictedMinutesToLow,
                    "pred5" to decision.pred5,
                    "pred30" to decision.pred30,
                    "pred60" to decision.pred60,
                    "ciLow30" to decision.ciLow30,
                    "ciHigh30" to decision.ciHigh30,
                    "trendDelta5Mmol" to decision.trendDelta5Mmol,
                    "degradedReason" to (delivery.degradedReason ?: ""),
                    "audioClip" to (delivery.audioClipLabel ?: ""),
                    "audioFallbackUsed" to delivery.audioFallbackUsed,
                    "causeCode" to (preparedCause?.analysis?.primary?.name ?: "")
                )
            )
            GlucoseAlertNotifyKind.CRITICAL_5,
            GlucoseAlertNotifyKind.LOW_NOW -> auditLogger.warn(
                "glucose_strong_low_triggered",
                mapOf(
                    "state" to decision.state.name,
                    "currentGlucoseMmol" to currentGlucoseMmol,
                    "predictedMinutesToLow" to decision.predictedMinutesToLow,
                    "urgentLowThreshold" to decision.urgentLowThreshold,
                    "currentFresh" to decision.currentGlucoseFresh,
                    "degradedReason" to (delivery.degradedReason ?: ""),
                    "audioClip" to (delivery.audioClipLabel ?: ""),
                    "audioFallbackUsed" to delivery.audioFallbackUsed,
                    "causeCode" to (preparedCause?.analysis?.primary?.name ?: "")
                )
            )
            GlucoseAlertNotifyKind.CLEAR -> auditLogger.info(
                "glucose_alert_cleared",
                mapOf("disableReason" to (decision.disableReason ?: "risk_resolved"))
            )
            GlucoseAlertNotifyKind.NONE -> {
                if (!decision.disableReason.isNullOrBlank()) {
                    auditLogger.infoThrottled(
                        throttleKey = "glucose_soft_alert_skipped:${decision.disableReason}",
                        intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                        message = "glucose_soft_alert_skipped",
                        metadata = mapOf(
                            "reason" to decision.disableReason,
                            "predictedMinutesToLow" to decision.predictedMinutesToLow,
                            "pred30" to decision.pred30,
                            "ciLow30" to decision.ciLow30,
                            "ciHigh30" to decision.ciHigh30
                        )
                    )
                }
                if (decision.repeatSuppressedByTrend && decision.softActive) {
                    auditLogger.infoThrottled(
                        throttleKey = "glucose_soft_alert_repeat_suppressed_by_trend:${decision.direction?.name}",
                        intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                        message = "glucose_soft_alert_repeat_suppressed_by_trend",
                        metadata = mapOf(
                            "state" to decision.state.name,
                            "direction" to (decision.direction?.name ?: ""),
                            "trendDelta5Mmol" to decision.trendDelta5Mmol,
                            "predictedMinutesToLow" to decision.predictedMinutesToLow,
                            "pred30" to decision.pred30,
                            "ciLow30" to decision.ciLow30,
                            "ciHigh30" to decision.ciHigh30
                        )
                    )
                }
            }
        }
    }

    private suspend fun loadActiveAlertEventContext(
        nowTs: Long,
        therapy: List<TherapyEvent>
    ): ActiveAlertEventContext {
        val eventTimelineRepository = EventTimelineRepository(gson)
        val boundedTherapy = boundAlertCauseTherapyStatic(therapy)
        if (boundedTherapy.overflow) {
            return ActiveAlertEventContext(
                eventTypes = emptySet(),
                available = false,
                overflowSource = AlertCauseContextSource.THERAPY,
                failureType = null
            )
        }
        val contextTags = db.physioContextTagDao().activeAtLimited(
            ts = nowTs,
            limit = ALERT_CAUSE_MAX_CONTEXT_EVENTS + 1
        )
        if (hasAlertCauseOverflowStatic(contextTags, ALERT_CAUSE_MAX_CONTEXT_EVENTS)) {
            return ActiveAlertEventContext(
                eventTypes = emptySet(),
                available = false,
                overflowSource = AlertCauseContextSource.CONTEXT_TAGS,
                failureType = null
            )
        }
        val plannedResult = loadRelevantPlannedAlertEventsStatic(nowTs) { afterStart, afterId, limit ->
            db.energyProfileDao().alertCauseEnabledCandidatesPage(afterStart, afterId, limit)
        }
        val plannedEvents = when (plannedResult) {
            is AlertCausePlannedLoadResult.Complete -> plannedResult.events
            AlertCausePlannedLoadResult.RelevantOverflow -> {
                return ActiveAlertEventContext(
                    eventTypes = emptySet(),
                    available = false,
                    overflowSource = AlertCauseContextSource.PLANNED_ACTIVITY,
                    failureType = null
                )
            }
            AlertCausePlannedLoadResult.ScanLimitReached,
            AlertCausePlannedLoadResult.CursorStalled -> {
                return ActiveAlertEventContext(
                    eventTypes = emptySet(),
                    available = false,
                    overflowSource = null,
                    failureType = when (plannedResult) {
                        AlertCausePlannedLoadResult.ScanLimitReached -> "PlannedActivityScanLimit"
                        else -> "PlannedActivityCursorStalled"
                    }
                )
            }
        }
        val timeline = eventTimelineRepository.aggregate(
            sources = EventTimelineSources(
                therapyEvents = boundedTherapy.rows,
                contextTags = contextTags,
                plannedActivity = plannedEvents
            ),
            nowTs = nowTs
        )
        return ActiveAlertEventContext(
            eventTypes = activeAlertContextTypesStatic(timeline, nowTs),
            available = true,
            overflowSource = null,
            failureType = null
        )
    }

    private suspend fun <T> runCycleStep(
        name: String,
        block: suspend () -> T
    ): T {
        val startedAt = System.currentTimeMillis()
        return try {
            val result = block()
            val durationMs = System.currentTimeMillis() - startedAt
            val level = if (durationMs >= AUTOMATION_STEP_SLOW_MS) "warn" else "info"
            val meta = mapOf("step" to name, "durationMs" to durationMs)
            if (level == "warn") {
                auditLogger.warn("automation_cycle_step_completed", meta)
            } else {
                auditLogger.infoThrottled(
                    throttleKey = "automation_cycle_step_completed:$name",
                    intervalMs = AUTOMATION_STEP_INFO_LOG_INTERVAL_MS,
                    message = "automation_cycle_step_completed",
                    metadata = meta
                )
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

    private suspend fun <T> runNonFatalCycleStep(
        name: String,
        timeoutMs: Long? = null,
        block: suspend () -> T
    ): T? {
        return runNonFatalCycleStepStatic(
            timeoutMs = timeoutMs,
            onNonFatalFailure = { error, timeout ->
                auditLogger.warn(
                    "automation_cycle_step_nonfatal_continue",
                    mapOf(
                        "step" to name,
                        "timeout" to timeout,
                        "timeoutMs" to timeoutMs,
                        "error" to (
                            error?.message ?: if (timeout) "step timed out" else "unknown failure"
                        )
                    )
                )
            }
        ) {
            runCycleStep(name) { block() }
        }
    }

    private suspend fun prepareCalibrationRuntimeContext(
        rawGlucose: List<GlucosePoint>,
        nowTs: Long,
        settings: AppSettings,
        intent: AutomationCycleIntent,
        resolvedTelemetry: ResolvedLatestTelemetry
    ): CalibrationRuntimeContext {
        val sensorLagRawGlucose = if (settings.sensorLagCorrectionMode != SensorLagCorrectionMode.OFF) {
            GlucoseSanitizer.filterEntities(
                db.glucoseDao().since(nowTs - SENSOR_LAG_HISTORY_LOOKBACK_MS)
            ).map { it.toDomain() }
        } else {
            emptyList()
        }
        val preparedCalibration = glucoseCalibrationRepository.prepareAcceptedCycleCalibration(
            rawGlucose = rawGlucose,
            nowTs = nowTs,
            allowMaintenance = allowsCalibrationMaintenanceStatic(intent),
            additionalRawGlucose = sensorLagRawGlucose
        )
        val resolvedGlucose = preparedCalibration.resolvedGlucose
        val glucose = resolvedGlucose.map { it.toDomain() }
        val therapy = syncRepository.recentTherapyEvents(hoursBack = 24, nowTs = nowTs)
        val sensorLagGlucoseHistory = if (settings.sensorLagCorrectionMode != SensorLagCorrectionMode.OFF) {
            preparedCalibration.additionalResolvedGlucose.map { it.toDomain() }
        } else {
            glucose
        }
        val sensorLagTherapyHistory = if (settings.sensorLagCorrectionMode != SensorLagCorrectionMode.OFF) {
            TherapySanitizer.filterEntities(
                db.therapyDao().between(nowTs - SENSOR_LAG_HISTORY_LOOKBACK_MS, nowTs)
            ).map { it.toDomain(gson) }
        } else {
            therapy
        }
        val sensorLagRuntimeContext = if (settings.sensorLagCorrectionMode != SensorLagCorrectionMode.OFF) {
            resolveSensorLagRuntimeContext(nowTs = nowTs)
        } else {
            SensorLagRuntimeContext()
        }
        val latestTelemetry = resolvedTelemetry.values.toMutableMap()
        val latestResolved = resolvedGlucose.lastOrNull()
        val latestCheck = db.bloodGlucoseCheckDao().latest(1).firstOrNull()?.toDomain()
        latestTelemetry["glucose_raw_mmol"] = latestResolved?.rawMmol ?: rawGlucose.lastOrNull()?.valueMmol
        latestTelemetry["glucose_calibrated_mmol"] = latestResolved?.calibratedMmol ?: rawGlucose.lastOrNull()?.valueMmol
        latestTelemetry["glucose_calibration_source_ts"] = latestResolved?.ts?.toDouble()
        latestTelemetry["glucose_calibration_gain"] =
            latestResolved?.takeIf { it.calibrationApplied }?.gain
        latestTelemetry["glucose_calibration_offset_mmol"] =
            latestResolved?.takeIf { it.calibrationApplied }?.offsetMmolApplied
        latestTelemetry["glucose_calibration_last_check_age_minutes"] = latestCheck
            ?.let { ((nowTs - it.timestamp).coerceAtLeast(0L)) / 60_000.0 }
        return CalibrationRuntimeContext(
            preparedCalibration = preparedCalibration,
            resolvedGlucose = resolvedGlucose,
            glucose = glucose,
            therapy = therapy,
            sensorLagGlucoseHistory = sensorLagGlucoseHistory,
            sensorLagTherapyHistory = sensorLagTherapyHistory,
            sensorLagRuntimeContext = sensorLagRuntimeContext,
            latestTelemetry = latestTelemetry,
            deliveryTrustTelemetry = resolvedTelemetry.deliveryTrustTelemetry,
            latestResolved = latestResolved,
            latestCheck = latestCheck
        )
    }

    private data class CalibrationRuntimeContext(
        val preparedCalibration: PreparedCalibrationCycle,
        val resolvedGlucose: List<io.aaps.copilot.domain.model.ResolvedGlucosePoint>,
        val glucose: List<GlucosePoint>,
        val therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        val sensorLagGlucoseHistory: List<GlucosePoint>,
        val sensorLagTherapyHistory: List<io.aaps.copilot.domain.model.TherapyEvent>,
        val sensorLagRuntimeContext: SensorLagRuntimeContext,
        val latestTelemetry: MutableMap<String, Double?>,
        val deliveryTrustTelemetry: List<DeliveryTrustTelemetryValue>,
        val latestResolved: io.aaps.copilot.domain.model.ResolvedGlucosePoint?,
        val latestCheck: io.aaps.copilot.domain.model.BloodGlucoseCheck?
    )

    private data class ResolvedLatestTelemetry(
        val values: Map<String, Double?>,
        val deliveryTrustTelemetry: List<DeliveryTrustTelemetryValue>
    )

    private data class CumulativeActivityDayCache(
        val dayStartTs: Long,
        val cursorTimestamp: Long,
        val cursorId: String,
        val rowsByKey: Map<String, TelemetrySampleEntity>
    )

    private data class PredictionPreparationContext(
        val sensitivityRuntime: SensitivityRuntimeConsumerContext,
        val realtimeIsfCrSnapshot: IsfCrRealtimeSnapshot?,
        val isfCrRuntimeGate: IsfCrRuntimeGate,
        val isfCrOverrideBlendWeight: Double?,
        val runtimeDia: RuntimeDiaInputs,
        val runtimeCobIob: RuntimeCobIobInputs,
        val rawCobFromTelemetry: Double?,
        val rawIobFromTelemetry: Double?,
        val insulinCycleFanOut: InsulinCycleFanOut,
        val insulinTherapyAvailable: Boolean,
        val latestGlucose: GlucosePoint,
        val effectiveStaleMaxMinutes: Int,
        val dataFresh: Boolean,
        val actionsLast6h: Int,
        val activeAapsTarget: ActiveAapsTarget?,
        val localSafetyChronologyResolved: Boolean,
        val localSafetyCausalThroughTs: Long,
        val latestAutomaticSent: LastSentTempTarget?,
        val sensorQuality: SensorQualityAssessment,
        val sensorBlocked: Boolean,
        val currentPattern: io.aaps.copilot.domain.model.PatternWindow?,
        val circadianPrior: CircadianForecastPrior?,
        val currentProfile: io.aaps.copilot.domain.model.ProfileEstimate?,
        val currentSegment: io.aaps.copilot.domain.model.ProfileSegmentEstimate?
    )

    private data class ForecastRuntimeContext(
        val rawLocalForecasts: List<Forecast>,
        val mergedForecasts: List<Forecast>,
        val controlForecasts: List<Forecast>,
        val lagCorrectedForecasts: List<Forecast>,
        val effectiveCurrentGlucoseMmol: Double,
        val calibrationApplied: Boolean,
        val contextBiasApplied: Boolean,
        val cobIobBiasApplied: Boolean,
        val circadianBiasApplied: Boolean,
        val calibrationSampleCount: Int,
        val calibrationPoints: List<ForecastCalibrationPoint>,
        val sensorLagEstimate: SensorLagEstimate,
        val latestGlucoseInput: GlucoseInputMetadata?,
        val sensorLagControlPlan: SensorLagControlPlan,
        val activityEffectContext: ActivityEffectContext,
        val activityForecastPlan: ActivityForecastPlan,
        val plannedActivityTargetOccurrence: PlannedActivityOccurrence?,
        val unifiedUam: UnifiedUamRuntimeSnapshot,
        val forecastDecomposition: ForecastDecompositionSnapshot?,
        val sensitivityRuntime: SensitivityRuntimeConsumerContext,
        val calibrationIdentity: GlucoseCalibrationCycleIdentity
    )

    internal data class PreparedAlertCause(
        val analysis: AlertCauseAnalysis,
        val snapshot: AlertCauseSnapshot,
        val input: AlertCauseInput?
    )

    private data class ActiveAlertEventContext(
        val eventTypes: Set<AlertContextEventType>,
        val available: Boolean,
        val overflowSource: AlertCauseContextSource?,
        val failureType: String?
    )

    private data class LowGlucoseSafetyCycleContext(
        val state: LowGlucoseTargetSafetyLatch.State,
        val activeSafetyTargetMmol: Double?,
        val forecastMinimumMmol: Double?
    )

    private data class ResolvedRealtimeIsfCrSnapshot(
        val snapshot: IsfCrRealtimeSnapshot?,
        val servedMode: String,
        val ageMs: Long?
    )

    private suspend fun preparePredictionPreparationContext(
        settings: AppSettings,
        nowTs: Long,
        glucose: List<GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        latestTelemetry: MutableMap<String, Double?>,
        sensitivityRuntime: SensitivityRuntimeConsumerContext,
        allowActionRepositoryAccess: Boolean,
        allowLocalSafetyEvidence: Boolean
    ): PredictionPreparationContext {
        val resolvedRealtimeIsfCr = resolveRealtimeIsfCrSnapshot(nowTs = nowTs)
        val realtimeIsfCrSnapshot = resolvedRealtimeIsfCr.snapshot
        latestTelemetry["isfcr_snapshot_age_ms"] = resolvedRealtimeIsfCr.ageMs?.toDouble()
        latestTelemetry["isfcr_refresh_in_flight"] = if (isfCrRealtimeRefreshInFlight) 1.0 else 0.0
        latestTelemetry["isfcr_refresh_last_duration_ms"] = isfCrRealtimeLastRefreshDurationMs.toDouble()
        latestTelemetry["isfcr_snapshot_served_mode"] = when (resolvedRealtimeIsfCr.servedMode) {
            "FRESH" -> 1.0
            "STALE_REUSED" -> 0.5
            else -> 0.0
        }
        val isfCrRuntimeGate = resolveIsfCrRuntimeGateStatic(
            snapshot = realtimeIsfCrSnapshot,
            confidenceThreshold = settings.isfCrConfidenceThreshold
        )
        val isfCrOverrideBlendWeight = resolveIsfCrOverrideBlendWeightStatic(
            snapshot = realtimeIsfCrSnapshot,
            runtimeGate = isfCrRuntimeGate,
            confidenceThreshold = settings.isfCrConfidenceThreshold
        )
        val sharedSensitivitySnapshot = sensitivityRuntime.snapshot
        require(sharedSensitivitySnapshot.settingsRevision == settings.sensitivitySettingsRevision) {
            "sensitivity snapshot/settings revision mismatch during prediction preparation"
        }
        val sensitivityRuntimeApplication = sensitivityRuntimeApplicationFromSnapshot(
            sharedSensitivitySnapshot,
            settings
        )
        listOf(
            Triple("isf", sensitivityRuntimeApplication.isf, latestTelemetry["isf_aaps_raw_sample_ts"]),
            Triple("cr", sensitivityRuntimeApplication.cr, latestTelemetry["cr_aaps_raw_sample_ts"])
        ).forEach { (metric, application, aapsTimestamp) ->
            latestTelemetry["${metric}_runtime_source_preference"] =
                metricPreferenceCodeStatic(application.decision.requested)
            latestTelemetry["${metric}_runtime_source_resolved"] =
                metricResolvedCodeStatic(application.decision.resolved)
            latestTelemetry["${metric}_runtime_selected_value"] = application.override?.value
            latestTelemetry["${metric}_runtime_aaps_sample_ts"] = aapsTimestamp
            latestTelemetry["${metric}_runtime_fallback_active"] =
                if (application.decision.fallbackReason != null) 1.0 else 0.0
            auditLogger.infoThrottled(
                throttleKey = "${metric}_runtime_source:${application.decision.requested}:${application.decision.resolved}:${application.decision.fallbackReason}",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "${metric}_runtime_source_resolved",
                metadata = mapOf(
                    "requested" to application.decision.requested.name,
                    "resolved" to application.decision.resolved.name,
                    "selectedValue" to application.override?.value,
                    "fallbackReason" to application.decision.fallbackReason,
                    "aapsSampleTs" to aapsTimestamp,
                    "evidenceGate" to isfCrRuntimeGate.reason,
                    "evidenceBlendWeight" to isfCrOverrideBlendWeight
                )
            )
        }
        configurePredictionEngine(
            settings = settings,
            sensitivityRuntimeApplication = sensitivityRuntimeApplication,
            runtimeTelemetry = latestTelemetry
        )
        latestTelemetry["sensitivity_runtime_cycle_id_hash"] = sharedSensitivitySnapshot.forecastCycleId.hashCode().toDouble()
        latestTelemetry["sensitivity_runtime_settings_revision"] = sharedSensitivitySnapshot.settingsRevision.toDouble()
        realtimeIsfCrSnapshot?.let { snapshot ->
            latestTelemetry["isf_realtime_value"] = snapshot.isfEff
            latestTelemetry["cr_realtime_value"] = snapshot.crEff
            latestTelemetry["isf_realtime_confidence"] = snapshot.confidence
            latestTelemetry["isf_realtime_quality_score"] = snapshot.qualityScore
            latestTelemetry["isf_realtime_applied"] = if (isfCrRuntimeGate.applyToRuntime) 1.0 else 0.0
            latestTelemetry["isf_realtime_override_blend_weight"] = isfCrOverrideBlendWeight ?: 0.0
            latestTelemetry["isf_realtime_mode"] = when (snapshot.mode.name) {
                "ACTIVE" -> 1.0
                "SPARSE_REAL_FETCHED" -> 0.25
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
            auditLogger.infoThrottled(
                throttleKey = "isfcr_runtime_gate:${snapshot.mode.name}:${isfCrRuntimeGate.reason}:${resolvedRealtimeIsfCr.servedMode}",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "isfcr_runtime_gate",
                metadata = mapOf(
                    "mode" to snapshot.mode.name,
                    "servedMode" to resolvedRealtimeIsfCr.servedMode,
                    "snapshotAgeMs" to resolvedRealtimeIsfCr.ageMs,
                    "confidence" to snapshot.confidence,
                    "threshold" to settings.isfCrConfidenceThreshold,
                    "applied" to isfCrRuntimeGate.applyToRuntime,
                    "reason" to isfCrRuntimeGate.reason,
                    "overrideBlendWeight" to isfCrOverrideBlendWeight,
                    "shadowSoftBlendApplied" to (!isfCrRuntimeGate.applyToRuntime && (isfCrOverrideBlendWeight ?: 0.0) > 0.0)
                )
            )
        }
        auditCycleCheckpoint(
            stage = "post_isfcr",
            metadata = mapOf(
                "hasRealtimeSnapshot" to (realtimeIsfCrSnapshot != null),
                "runtimeGateApplied" to isfCrRuntimeGate.applyToRuntime,
                "servedMode" to resolvedRealtimeIsfCr.servedMode
            )
        )
        val cycleCausalReferenceTimestamp = resolveCycleCausalReferenceTimestampStatic(glucose)

        val realInsulinProfile = readLatestRealInsulinProfileEstimate(nowTs)
        val rawDiaFromTelemetry = latestTelemetry["dia_hours"]?.takeIf { it.isFinite() }
        val runtimeDia = resolveRuntimeDiaInputs(
            settings = settings,
            realProfile = realInsulinProfile,
            rawExternalDiaHours = rawDiaFromTelemetry
        )
        runtimeDia.rawExternalHours?.let { latestTelemetry["dia_external_raw_hours"] = it }
        runtimeDia.rawEstimatedHours?.let { latestTelemetry["dia_real_raw_hours"] = it }
        latestTelemetry["dia_profile_hours"] = runtimeDia.profileHours
        latestTelemetry["dia_effective_hours"] = runtimeDia.effectiveHours
        latestTelemetry["dia_hours"] = runtimeDia.effectiveHours
        if (realInsulinProfile != null) {
            latestTelemetry["insulin_profile_real_updated_ts"] = realInsulinProfile.updatedTs.toDouble()
            latestTelemetry["insulin_profile_real_confidence"] = realInsulinProfile.confidence
            latestTelemetry["insulin_profile_real_samples"] = realInsulinProfile.sampleCount.toDouble()
            latestTelemetry["insulin_profile_real_onset_min"] = realInsulinProfile.onsetMinutes
            latestTelemetry["insulin_profile_real_peak_min"] = realInsulinProfile.peakMinutes
            latestTelemetry["insulin_profile_real_scale"] = realInsulinProfile.shapeScale
            latestTelemetry["insulin_profile_real_published_ts"] = realInsulinProfile.lastPublishedTs.toDouble()
        }

        val runtimeCobIob = resolveRuntimeCobIobInputs(
            cycleTimestamp = nowTs,
            causalReferenceTimestamp = cycleCausalReferenceTimestamp,
            glucose = glucose,
            therapy = therapy,
            telemetry = latestTelemetry,
            settings = settings,
            effectiveDiaHours = runtimeDia.effectiveHours
        )
        val insulinCycleFanOut = buildInsulinCycleFanOutStatic(runtimeCobIob.insulinCycleContext)
        val insulinTherapyAvailable = hasInsulinTherapyEvidence(therapy)
        val rawCobFromTelemetry = latestTelemetry["cob_grams"]
        val rawIobFromTelemetry = latestTelemetry["iob_net_units"] ?: latestTelemetry["iob_units"]
        latestTelemetry["cob_grams"] = runtimeCobIob.cobGrams
        latestTelemetry["iob_units"] = runtimeCobIob.iobUnits
        latestTelemetry["cob_effective_grams"] = runtimeCobIob.cobGrams
        latestTelemetry["iob_effective_units"] = runtimeCobIob.iobUnits
        latestTelemetry["iob_real_units"] = runtimeCobIob.realIobUnits
        runtimeCobIob.insulinSnapshot?.let { snapshot ->
            latestTelemetry["iob_net_units"] = snapshot.netIobUnits
            latestTelemetry["iob_bolus_units"] = snapshot.bolusIobUnits
            latestTelemetry["iob_basal_units"] = snapshot.basalIobUnits
            latestTelemetry["insulin_activity"] = snapshot.insulinActivity
            latestTelemetry["iob_runtime_timestamp_ms"] = snapshot.timestamp.toDouble()
            latestTelemetry["iob_runtime_confidence"] = snapshot.confidence
            latestTelemetry["iob_runtime_source_code"] = InsulinRuntimeSnapshotResolver.sourceCode(snapshot.source)
            latestTelemetry["iob_runtime_fallback_active"] = if (snapshot.fallbackReason == null) 0.0 else 1.0
        }
        latestTelemetry["cob_local_fallback_grams"] = runtimeCobIob.localCobGrams
        latestTelemetry["iob_local_fallback_units"] = runtimeCobIob.localIobUnits
        runtimeCobIob.externalCobAdjustedGrams?.let { latestTelemetry["cob_external_adjusted_grams"] = it }
        latestTelemetry["cob_synthetic_uam_subtracted_grams"] = runtimeCobIob.syntheticUamCobGrams
        latestTelemetry["insulin_real_onset_min"] = runtimeCobIob.realOnsetMinutes
        latestTelemetry["insulin_profile_base_onset_min"] = runtimeCobIob.baseOnsetMinutes
        latestTelemetry["insulin_real_onset_samples"] = runtimeCobIob.onsetSampleCount.toDouble()
        (runtimeCobIob.externalCobRawGrams ?: rawCobFromTelemetry)?.let { latestTelemetry["cob_external_raw_grams"] = it }
        rawIobFromTelemetry?.let { latestTelemetry["iob_external_raw_units"] = it }
        latestTelemetry["cob_iob_local_used"] = if (runtimeCobIob.usedLocalFallback) 1.0 else 0.0
        latestTelemetry["cob_iob_merged"] = if (runtimeCobIob.mergedWithTelemetry) 1.0 else 0.0

        if (runtimeCobIob.usedLocalFallback || runtimeCobIob.mergedWithTelemetry) {
            auditLogger.infoThrottled(
                throttleKey = "cob_iob_runtime_resolved",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "cob_iob_runtime_resolved",
                metadata = mapOf(
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
        auditCycleCheckpoint("post_cob_iob_runtime")

        val zoned = Instant.ofEpochMilli(nowTs).atZone(ZoneId.systemDefault())
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
        val latestGlucose = glucose.maxBy { it.ts }
        val effectiveStaleMaxMinutes = resolveEffectiveStaleMaxMinutesStatic(settings)
        val dataFresh = nowTs - latestGlucose.ts <= effectiveStaleMaxMinutes * 60 * 1000L
        val localSafetyEvidence = if (allowLocalSafetyEvidence) {
            loadLocalSafetyEvidenceStatic(db = db, gson = gson, nowTs = nowTs)
        } else {
            null
        }
        val actionsLast6h = when {
            localSafetyEvidence != null -> localSafetyEvidence.actionsLast6h
            allowActionRepositoryAccess -> actionRepository.countSentActionsLast6h(nowTs)
            else -> 0
        }
        val activeAapsTarget = when {
            localSafetyEvidence != null -> localSafetyEvidence.activeAapsTarget
            allowActionRepositoryAccess -> resolveActiveAapsTarget(nowTs)
            else -> null
        }
        val localSafetyChronologyResolved = localSafetyEvidence?.chronologyResolved ?: true
        val localSafetyCausalThroughTs = localSafetyEvidence?.causalThroughTs ?: nowTs
        val latestAutomaticSent = localSafetyEvidence?.latestAutomaticSent
        val therapySensorBlocked = isSensorBlocked(therapy, nowTs)
        val sensorQuality = evaluateSensorQuality(
            glucose = glucose,
            nowTs = nowTs,
            staleMaxMinutes = effectiveStaleMaxMinutes
        )
        val sensorBlocked = therapySensorBlocked || sensorQuality.blocked
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
        auditCycleCheckpoint("post_sensor_quality")

        val circadianPrior = analyticsRepository.resolveCircadianPrior(
            nowTs = nowTs,
            currentGlucose = latestGlucose.valueMmol,
            telemetry = latestTelemetry,
            settings = settings
        )
        latestTelemetry["pattern_prior_confidence"] = circadianPrior?.confidence
        latestTelemetry["pattern_prior_bg_median_mmol"] = circadianPrior?.bgMedian
        latestTelemetry["pattern_prior_30_mmol"] = circadianPrior?.delta30
        latestTelemetry["pattern_prior_60_mmol"] = circadianPrior?.delta60
        latestTelemetry["pattern_prior_residual_bias_30_mmol"] = circadianPrior?.residualBias30
        latestTelemetry["pattern_prior_residual_bias_60_mmol"] = circadianPrior?.residualBias60
        latestTelemetry["pattern_prior_replay_bias_30"] = circadianPrior?.replayBias30
        latestTelemetry["pattern_prior_replay_bias_60"] = circadianPrior?.replayBias60
        latestTelemetry["pattern_prior_median_reversion_30"] = circadianPrior?.medianReversion30
        latestTelemetry["pattern_prior_median_reversion_60"] = circadianPrior?.medianReversion60
        latestTelemetry["pattern_prior_horizon_quality_30"] = circadianPrior?.horizonQuality30
        latestTelemetry["pattern_prior_horizon_quality_60"] = circadianPrior?.horizonQuality60
        latestTelemetry["pattern_prior_stability_score"] = circadianPrior?.stabilityScore
        latestTelemetry["pattern_prior_replay_weight_30"] = circadianPrior?.let {
            patternPriorWeightForHorizonStatic(
                horizonMinutes = 30,
                prior = it,
                weight30 = settings.circadianForecastWeight30,
                weight60 = settings.circadianForecastWeight60
            )
        }
        latestTelemetry["pattern_prior_replay_weight_60"] = circadianPrior?.let {
            patternPriorWeightForHorizonStatic(
                horizonMinutes = 60,
                prior = it,
                weight30 = settings.circadianForecastWeight30,
                weight60 = settings.circadianForecastWeight60
            )
        }
        latestTelemetry["pattern_prior_acute_attenuation"] = circadianPrior?.acuteAttenuation
        latestTelemetry["pattern_prior_stale_blocked"] = if (circadianPrior?.staleBlocked == true) 1.0 else 0.0
        val currentSlot = resolveTimeSlot(zoned.hour)
        val (legacyProfileEntity, currentSegmentEntity) = db.withTransaction {
            db.profileEstimateDao().active() to db.profileSegmentEstimateDao()
                .byDayTypeAndTimeSlot(dayType.name, currentSlot.name)
        }
        val legacyProfile = legacyProfileEntity?.toProfileEstimate()
        realtimeIsfCrSnapshot?.let { snapshot ->
            if (snapshot.mode.name == "SHADOW") {
                logIsfCrShadowDiff(snapshot = snapshot, legacyProfile = legacyProfile)
            }
        }
        val baselineCurrentProfile = when {
            (sensitivityRuntimeApplication.isf.decision.resolved == MetricRuntimeResolvedSource.EVIDENCE_BLEND ||
                sensitivityRuntimeApplication.cr.decision.resolved == MetricRuntimeResolvedSource.EVIDENCE_BLEND) &&
                realtimeIsfCrSnapshot != null ->
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
        val currentProfile = baselineCurrentProfile?.copy(
            isfMmolPerUnit = sensitivityRuntimeApplication.isf.override?.value
                ?: baselineCurrentProfile.isfMmolPerUnit,
            crGramPerUnit = sensitivityRuntimeApplication.cr.override?.value
                ?: baselineCurrentProfile.crGramPerUnit
        )
        val currentSegment = normalizeSegmentForRuntimeStatic(
            segment = currentSegmentEntity
                ?.takeIf {
                    isMatchingProfileSegmentGenerationStatic(
                        profileTimestamp = legacyProfileEntity?.timestamp,
                        segmentUpdatedAt = it.updatedAt
                    )
                }
                ?.toProfileSegmentEstimate(),
            localProfile = legacyProfile,
            runtimeProfile = currentProfile
        )

        val uamRuntimeQuality = resolveUamRuntimeQualityStatic(
            sensorQualityScore = sensorQuality.score,
            sensorBlocked = sensorBlocked,
            nowTs = nowTs,
            effectiveDiaHours = runtimeDia.effectiveHours,
            insulinEvidenceTimestamps = causalInsulinEvidenceTimestamps(
                nowTs = nowTs,
                therapy = therapy
            ),
            iobSampleTs = latestTelemetry["iob_sample_ts"]?.toLong(),
            iobUnits = runtimeCobIob.iobUnits,
            insulinCycleContext = insulinCycleFanOut.contextFor(InsulinCycleConsumer.UAM),
            effectiveCobGrams = runtimeCobIob.cobGrams,
            externalCobGrams = runtimeCobIob.externalCobRawGrams,
            carbTherapyAvailable = hasRecentCarbEvents(
                nowTs = nowTs,
                therapy = therapy,
                cutoffMinutes = settings.carbAbsorptionMaxAgeMinutes
            ),
            iobFreshnessMaxMinutes = settings.staleDataMaxMinutes
        )
        latestTelemetry["uam_runtime_input_sensor_trust"] = uamRuntimeQuality.sensorTrust
        latestTelemetry["uam_runtime_input_therapy_coverage"] = uamRuntimeQuality.therapyCoverage
        latestTelemetry["uam_runtime_input_announced_carb_coverage"] = uamRuntimeQuality.announcedCarbCoverage
        configurePredictionEngine(
            settings = settings,
            sensitivityRuntimeApplication = sensitivityRuntimeApplication,
            runtimeTelemetry = latestTelemetry,
            uamRuntimeQuality = uamRuntimeQuality
        )
        return PredictionPreparationContext(
            sensitivityRuntime = sensitivityRuntime,
            realtimeIsfCrSnapshot = realtimeIsfCrSnapshot,
            isfCrRuntimeGate = isfCrRuntimeGate,
            isfCrOverrideBlendWeight = isfCrOverrideBlendWeight,
            runtimeDia = runtimeDia,
            runtimeCobIob = runtimeCobIob,
            rawCobFromTelemetry = rawCobFromTelemetry,
            rawIobFromTelemetry = rawIobFromTelemetry,
            insulinCycleFanOut = insulinCycleFanOut,
            insulinTherapyAvailable = insulinTherapyAvailable,
            latestGlucose = latestGlucose,
            effectiveStaleMaxMinutes = effectiveStaleMaxMinutes,
            dataFresh = dataFresh,
            actionsLast6h = actionsLast6h,
            activeAapsTarget = activeAapsTarget,
            localSafetyChronologyResolved = localSafetyChronologyResolved,
            localSafetyCausalThroughTs = localSafetyCausalThroughTs,
            latestAutomaticSent = latestAutomaticSent,
            sensorQuality = sensorQuality,
            sensorBlocked = sensorBlocked,
            currentPattern = currentPattern,
            circadianPrior = circadianPrior,
            currentProfile = currentProfile,
            currentSegment = currentSegment
        )
    }

    private suspend fun buildForecastRuntimeContext(
        settings: AppSettings,
        nowTs: Long,
        glucose: List<GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        latestTelemetry: MutableMap<String, Double?>,
        latestGlucose: GlucosePoint,
        sensorLagGlucoseHistory: List<GlucosePoint>,
        sensorLagTherapyHistory: List<io.aaps.copilot.domain.model.TherapyEvent>,
        sensorLagRuntimeContext: SensorLagRuntimeContext,
        effectiveStaleMaxMinutes: Int,
        sensorQuality: SensorQualityAssessment,
        sensorBlocked: Boolean,
        effectiveCobGrams: Double,
        insulinCycleContext: InsulinCycleContext,
        currentPattern: io.aaps.copilot.domain.model.PatternWindow?,
        circadianPrior: CircadianForecastPrior?,
        sensitivityRuntime: SensitivityRuntimeConsumerContext,
        uamSensitivityRuntime: SensitivityRuntimeConsumerContext,
        calibrationIdentity: GlucoseCalibrationCycleIdentity
    ): ForecastRuntimeContext {
        require(sensitivityRuntime.consumer == SensitivityRuntimeConsumer.FORECAST_5_30_60) {
            "forecast runtime requires FORECAST_5_30_60 sensitivity context"
        }
        require(uamSensitivityRuntime.consumer == SensitivityRuntimeConsumer.UAM) {
            "UAM runtime requires UAM sensitivity context"
        }
        require(sensitivityRuntime.snapshot === uamSensitivityRuntime.snapshot) {
            "forecast and UAM must share the exact sensitivity snapshot"
        }
        (predictionEngine as? HybridPredictionEngine)
            ?.setUamSensitivityRuntimeContext(uamSensitivityRuntime)
        (predictionEngine as? HybridPredictionEngine)?.setMealAbsorptionContext(
            energyProfileRepository.mealAbsorptionContext(settings, therapy)
        )
        val activityEffectContext = resolveActivityEffectContext(
            settings = settings,
            nowTs = nowTs,
            latestTelemetry = latestTelemetry
        )
        val plannedActivityTargetOccurrence = resolvePlannedActivityTargetOccurrence(
            nowTs = nowTs
        )
        val localForecasts = predictionEngine.predict(glucose, therapy)
        val mergedForecastsRaw = ensureForecast30(
            selectAutomationControlForecastsStatic(
                local = localForecasts,
                forecastGateway = null
            )
        )
        val latestGlucoseInput = resolveLatestGlucoseInputMetadata(
            latestGlucose = latestGlucose,
            nowTs = nowTs
        )
        val sensorLagEstimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = nowTs,
                glucose = sensorLagGlucoseHistory,
                therapy = sensorLagTherapyHistory,
                latestGlucose = latestGlucose,
                requestedMode = settings.sensorLagCorrectionMode,
                staleMaxMinutes = effectiveStaleMaxMinutes,
                sensorQualityScore = sensorQuality.score,
                sensorBlocked = sensorBlocked,
                sensorSuspectFalseLow = sensorQuality.suspectFalseLow,
                latestInput = latestGlucoseInput,
                devicestatusAgeHours = sensorLagRuntimeContext.sensorAgeHours,
                devicestatusAgeTs = sensorLagRuntimeContext.sensorAgeTs,
                devicestatusAgeSourceRaw = sensorLagRuntimeContext.sensorAgeSourceRaw,
                sageDays = sensorLagRuntimeContext.sageDays,
                cageDays = sensorLagRuntimeContext.cageDays,
                replayBucketStats = sensorLagRuntimeContext.replayBucketStats
            )
        )
        sensorLagEstimate.ageConflictHours
            ?.takeIf { it > 24.0 }
            ?.let { conflictHours ->
                auditLogger.warn(
                    "sensor_lag_age_source_conflict",
                    mapOf(
                        "conflictHours" to conflictHours,
                        "ageSource" to sensorLagEstimate.ageSource.name,
                        "devicestatusAgeHours" to sensorLagRuntimeContext.sensorAgeHours,
                        "sourceRaw" to sensorLagRuntimeContext.sensorAgeSourceRaw
                    )
                )
            }
        applySensorLagTelemetry(
            latestTelemetry = latestTelemetry,
            estimate = sensorLagEstimate,
            latestGlucoseInput = latestGlucoseInput
        )
        val lagSeedForecasts = when (sensorLagEstimate.mode) {
            SensorLagCorrectionMode.OFF -> mergedForecastsRaw
            else -> SensorLagRuntimeEstimator.applyForecastBias(
                forecasts = mergedForecastsRaw,
                estimate = sensorLagEstimate
            )
        }
        val lagReferenceGlucoseMmol = when (sensorLagEstimate.mode) {
            SensorLagCorrectionMode.OFF -> latestGlucose.valueMmol
            else -> sensorLagEstimate.correctedGlucoseMmol
        }
        val forecastDiagnostics = (predictionEngine as? HybridPredictionEngine)?.diagnosticsSnapshot()
        val unifiedUam = projectUnifiedUamRuntimeStatic(
            diagnostics = forecastDiagnostics,
            effectiveCobGrams = effectiveCobGrams,
            sensorBlocked = sensorBlocked,
            sensitivityRuntime = uamSensitivityRuntime,
            calibrationIdentity = calibrationIdentity
        )
        val calibrationErrors = collectForecastCalibrationErrors(nowTs = nowTs)
        val calibrationTuning = resolveAiCalibrationTuning(
            latestTelemetry = latestTelemetry,
            nowTs = nowTs
        )
        val mergedForecastsCalibrated = applyRecentForecastCalibrationBias(
            forecasts = mergedForecastsRaw,
            history = calibrationErrors,
            aiTuning = calibrationTuning
        )
        val lagForecastsCalibrated = applyRecentForecastCalibrationBias(
            forecasts = lagSeedForecasts,
            history = calibrationErrors,
            aiTuning = calibrationTuning
        )
        val calibrationApplied = mergedForecastsCalibrated != mergedForecastsRaw
        if (calibrationApplied) {
            auditLogger.infoThrottled(
                throttleKey = "forecast_calibration_bias_applied",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "forecast_calibration_bias_applied",
                metadata = buildCalibrationAuditMeta(
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
        val lagForecastsContextBiased = applyContextFactorForecastBias(
            forecasts = lagForecastsCalibrated,
            telemetry = latestTelemetry,
            latestGlucoseMmol = lagReferenceGlucoseMmol,
            pattern = currentPattern
        )
        val contextBiasApplied = mergedForecastsContextBiased != mergedForecastsCalibrated
        if (contextBiasApplied) {
            auditLogger.infoThrottled(
                throttleKey = "forecast_context_bias_applied",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "forecast_context_bias_applied",
                metadata = mapOf(
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
            diagnosticIobUnits = latestTelemetry["iob_effective_units"],
            insulinCycleContext = insulinCycleContext,
            isfMmolPerUnit = resolveForecastIsfStatic(latestTelemetry),
            latestGlucoseMmol = latestGlucose.valueMmol,
            uamActive = resolveCobIobBiasUamActive(
                latestTelemetry = latestTelemetry,
                currentUnifiedUamFlag = unifiedUam.flag
            )
        )
        val lagForecastsBiased = applyCobIobForecastBias(
            forecasts = lagForecastsContextBiased,
            cobGrams = latestTelemetry["cob_grams"],
            diagnosticIobUnits = latestTelemetry["iob_effective_units"],
            insulinCycleContext = insulinCycleContext,
            isfMmolPerUnit = resolveForecastIsfStatic(latestTelemetry),
            latestGlucoseMmol = lagReferenceGlucoseMmol,
            uamActive = resolveCobIobBiasUamActive(
                latestTelemetry = latestTelemetry,
                currentUnifiedUamFlag = unifiedUam.flag
            )
        )
        val cobIobBiasApplied = mergedForecastsBiased != mergedForecastsContextBiased
        if (cobIobBiasApplied) {
            auditLogger.infoThrottled(
                throttleKey = "forecast_bias_applied",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "forecast_bias_applied",
                metadata = mapOf(
                    "cobGrams" to latestTelemetry["cob_grams"],
                    "iobNetUnits" to insulinCycleContext.snapshot?.netIobUnits,
                    "iobEffectivePositiveUnits" to insulinCycleContext.snapshot?.effectivePositiveIobUnits,
                    "iobBasalUnits" to insulinCycleContext.snapshot?.basalIobUnits,
                    "insulinActivity" to insulinCycleContext.snapshot?.insulinActivity,
                    "insulinResidualAllowed" to insulinCycleContext.residualComparisonAllowed,
                    "insulinResidualReason" to insulinCycleContext.residualComparisonReason
                )
            )
        }
        val mergedForecastsCircadian = applyCircadianPatternForecastBias(
            forecasts = mergedForecastsBiased,
            prior = circadianPrior,
            latestGlucoseMmol = latestGlucose.valueMmol,
            settings = settings
        )
        val lagForecastsCircadian = applyCircadianPatternForecastBias(
            forecasts = lagForecastsBiased,
            prior = circadianPrior,
            latestGlucoseMmol = lagReferenceGlucoseMmol,
            settings = settings
        )
        val circadianBiasApplied = mergedForecastsCircadian != mergedForecastsBiased
        if (circadianBiasApplied && circadianPrior != null) {
            auditLogger.infoThrottled(
                throttleKey = "forecast_circadian_prior_applied",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "forecast_circadian_prior_applied",
                metadata = mapOf(
                    "segmentSource" to circadianPrior.segmentSource.name,
                    "requestedDayType" to circadianPrior.requestedDayType.name,
                    "confidence" to circadianPrior.confidence,
                    "qualityScore" to circadianPrior.qualityScore,
                    "acuteAttenuation" to circadianPrior.acuteAttenuation,
                    "delta15" to circadianPrior.delta15,
                    "delta30" to circadianPrior.delta30,
                    "delta60" to circadianPrior.delta60,
                    "residualBias30" to circadianPrior.residualBias30,
                    "residualBias60" to circadianPrior.residualBias60
                )
            )
        }
        val mergedForecasts = normalizeForecastSet(mergedForecastsCircadian)
        if (mergedForecasts.size != mergedForecastsBiased.size) {
            auditLogger.warn(
                "forecast_duplicate_horizon_deduped",
                mapOf("before" to mergedForecastsBiased.size, "after" to mergedForecasts.size)
            )
        }
        val lagCorrectedForecasts = normalizeForecastSet(lagForecastsCircadian)
        if (lagCorrectedForecasts.size != lagForecastsBiased.size) {
            auditLogger.warn(
                "forecast_sensor_lag_duplicate_horizon_deduped",
                mapOf("before" to lagForecastsBiased.size, "after" to lagCorrectedForecasts.size)
            )
        }
        val sensorLagControlPlan = resolveSensorLagControlPlanStatic(
            requestedMode = settings.sensorLagCorrectionMode,
            estimate = sensorLagEstimate,
            rawCurrentGlucoseMmol = latestGlucose.valueMmol,
            mergedForecasts = mergedForecasts,
            lagCorrectedForecasts = lagCorrectedForecasts
        )
        val activityForecastPlan = resolveActivityForecastPlanStatic(
            controlForecasts = sensorLagControlPlan.controlForecasts,
            context = activityEffectContext,
            existingControlActivityFactor = resolveContextActivityFactorStatic(latestTelemetry)
        )
        activityForecastPlan.shadowForecasts?.let { shadow ->
            auditLogger.infoThrottled(
                throttleKey = "activity_forecast_shadow",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "activity_forecast_shadow",
                metadata = shadow.associate { forecast ->
                    val control = activityForecastPlan.controlForecasts
                        .firstOrNull { it.horizonMinutes == forecast.horizonMinutes }
                    "h${forecast.horizonMinutes}_delta" to
                        ((forecast.valueMmol - (control?.valueMmol ?: forecast.valueMmol)) * 1000.0).toInt() / 1000.0
                }
            )
        }
        val forecastDecomposition = extractForecastDecompositionSnapshotStatic(
            diagnostics = forecastDiagnostics,
            localForecasts = localForecasts
        )
        applyUnifiedUamTelemetry(latestTelemetry = latestTelemetry, unified = unifiedUam)
        requireCycleSensitivityRuntime(
            context = uamSensitivityRuntime,
            expectedConsumer = SensitivityRuntimeConsumer.UAM,
            cycleSnapshot = sensitivityRuntime.snapshot
        )
        return ForecastRuntimeContext(
            rawLocalForecasts = localForecasts,
            mergedForecasts = mergedForecasts,
            controlForecasts = sensorLagControlPlan.controlForecasts,
            lagCorrectedForecasts = lagCorrectedForecasts,
            effectiveCurrentGlucoseMmol = sensorLagControlPlan.effectiveCurrentGlucoseMmol,
            calibrationApplied = calibrationApplied,
            contextBiasApplied = contextBiasApplied,
            cobIobBiasApplied = cobIobBiasApplied,
            circadianBiasApplied = circadianBiasApplied,
            calibrationSampleCount = calibrationErrors.size,
            calibrationPoints = calibrationErrors,
            sensorLagEstimate = sensorLagEstimate,
            latestGlucoseInput = latestGlucoseInput,
            sensorLagControlPlan = sensorLagControlPlan,
            activityEffectContext = activityEffectContext,
            activityForecastPlan = activityForecastPlan,
            plannedActivityTargetOccurrence = plannedActivityTargetOccurrence,
            unifiedUam = unifiedUam,
            forecastDecomposition = forecastDecomposition,
            sensitivityRuntime = sensitivityRuntime,
            calibrationIdentity = calibrationIdentity
        )
    }

    private suspend fun auditForecastFactorCoverage(
        nowTs: Long,
        latestTelemetry: Map<String, Double?>,
        realtimeIsfCrSnapshot: IsfCrRealtimeSnapshot?,
        runtimeGate: IsfCrRuntimeGate,
        runtimeCobIob: RuntimeCobIobInputs,
        currentPattern: io.aaps.copilot.domain.model.PatternWindow?,
        calibrationSampleCount: Int,
        calibrationApplied: Boolean,
        contextBiasApplied: Boolean,
        cobIobBiasApplied: Boolean,
        circadianPrior: CircadianForecastPrior?,
        circadianBiasApplied: Boolean,
        insulinTherapyAvailable: Boolean
    ) {
        auditLogger.infoThrottled(
            throttleKey = "forecast_factor_coverage",
            intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
            message = "forecast_factor_coverage",
            metadata = buildForecastFactorCoverageMeta(
                latestTelemetry = latestTelemetry,
                realtimeIsfCrSnapshot = realtimeIsfCrSnapshot,
                runtimeGate = runtimeGate,
                runtimeCobIob = runtimeCobIob,
                currentPattern = currentPattern,
                calibrationSampleCount = calibrationSampleCount,
                calibrationApplied = calibrationApplied,
                contextBiasApplied = contextBiasApplied,
                cobIobBiasApplied = cobIobBiasApplied,
                circadianPrior = circadianPrior,
                circadianBiasApplied = circadianBiasApplied,
                calculatedUam = CalculatedUamSnapshot(0.0, 0.0, null, null, null, null),
                inferredUam = null,
                insulinTherapyAvailable = insulinTherapyAvailable
            )
        )
        auditCycleCheckpoint("post_uam_and_coverage")
    }

    private suspend fun executeRulesAndActions(
        settings: AppSettings,
        nowTs: Long,
        context: RuleContext,
        controlForecasts: List<Forecast>,
        lagCorrectedForecasts: List<Forecast>,
        activeAapsTarget: ActiveAapsTarget?,
        activeTempTarget: Double?,
        effectiveBaseTarget: EffectiveBaseTarget,
        dataFresh: Boolean,
        actionsLast6h: Int,
        localSafetyChronologyResolved: Boolean,
        localSafetyCausalThroughTs: Long,
        latestAutomaticSent: LastSentTempTarget?,
        sensorBlocked: Boolean,
        sensorQuality: SensorQualityAssessment,
        lowGlucoseSafetyCycle: LowGlucoseSafetyCycleContext,
        latestTelemetry: MutableMap<String, Double?>,
        deliveryTrustTelemetry: MutableList<DeliveryTrustTelemetryValue>,
        forecastRuntime: ForecastRuntimeContext,
        insulinCycleContext: InsulinCycleContext,
        sensitivityRuntime: SensitivityRuntimeConsumerContext,
        acceptedClinicalForecastAuthority: AcceptedClinicalForecasts,
        externalWritesAllowed: Boolean,
        mode: RuleEvaluationMode
    ): List<RuleDecision> {
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
        auditCycleCheckpoint(
            stage = "post_rule_evaluate",
            metadata = mapOf("decisions" to decisions.size)
        )
        if (forecastRuntime.sensorLagControlPlan.shouldEvaluateShadow) {
            val shadowDecisions = sensorLagShadowRuleEngine.evaluate(
                context = context.copy(
                    forecasts = checkNotNull(forecastRuntime.sensorLagControlPlan.shadowForecasts),
                    currentGlucoseMmol = checkNotNull(forecastRuntime.sensorLagControlPlan.shadowCurrentGlucoseMmol)
                ),
                config = SafetyPolicyConfig(
                    killSwitch = settings.killSwitch,
                    maxActionsIn6Hours = resolveEffectiveMaxActions6h(settings),
                    minTargetMmol = settings.safetyMinTargetMmol,
                    maxTargetMmol = settings.safetyMaxTargetMmol
                ),
                runtimeConfig = runtimeConfig(settings)
            )
            val shadowRuleChanged = hasSensorLagShadowRuleChanged(
                baseline = decisions,
                candidate = shadowDecisions
            )
            val shadowTargetDeltaMmol = resolveSensorLagShadowTargetDeltaMmol(
                baseline = decisions,
                candidate = shadowDecisions
            )
            latestTelemetry["sensor_lag_shadow_rule_changed"] = if (shadowRuleChanged) 1.0 else 0.0
            latestTelemetry["sensor_lag_shadow_target_delta_mmol"] = shadowTargetDeltaMmol
            persistSensorLagTelemetry(
                nowTs = nowTs,
                estimate = forecastRuntime.sensorLagEstimate,
                latestGlucoseInput = forecastRuntime.latestGlucoseInput,
                controlForecasts = controlForecasts,
                candidateForecasts = lagCorrectedForecasts,
                shadowRuleChanged = shadowRuleChanged,
                shadowTargetDeltaMmol = shadowTargetDeltaMmol
            )
        }

        val effectiveDecisions = decisions.map { decision ->
            if (decision.state == RuleState.TRIGGERED && decision.actionProposal != null) {
                val cooldownMinutes = ruleCooldownMinutes(decision.ruleId, settings)
                if (
                    cooldownMinutes > 0 &&
                    !shouldBypassAdaptiveCooldownStatic(
                        decision = decision,
                        activeTempTargetMmol = activeTempTarget
                    ) &&
                    isRuleInCooldown(decision.ruleId, nowTs, cooldownMinutes)
                ) {
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
        }

        val proposedSafetyTarget = effectiveDecisions.asSequence()
            .filter { it.state == RuleState.TRIGGERED }
            .filter { it.ruleId == AdaptiveTargetControllerRule.RULE_ID }
            .mapNotNull { it.actionProposal }
            .filter { it.type.equals("temp_target", ignoreCase = true) }
            .filter { isLowGlucoseSafetyReasonStatic(it.reason) }
            .maxOfOrNull { it.targetMmol }
        val lowGlucoseSafetyState = if (
            lowGlucoseSafetyCycle.state.riskNow && proposedSafetyTarget != null
        ) {
            evaluateLowGlucoseSafetyLatch(
                mode = mode,
                currentGlucoseMmol = context.currentGlucoseMmol,
                forecastMinimumMmol = lowGlucoseSafetyCycle.forecastMinimumMmol,
                activeSafetyTargetMmol = lowGlucoseSafetyCycle.activeSafetyTargetMmol,
                proposedSafetyTargetMmol = proposedSafetyTarget,
                seedFromActiveSafety = lowGlucoseSafetyCycle.activeSafetyTargetMmol != null
            )
        } else {
            lowGlucoseSafetyCycle.state
        }
        latestTelemetry["target_low_risk_active"] = if (lowGlucoseSafetyState.riskNow) 1.0 else 0.0
        latestTelemetry["target_low_risk_latched"] = if (lowGlucoseSafetyState.latched) 1.0 else 0.0
        latestTelemetry["target_low_risk_safe_cycles"] = lowGlucoseSafetyState.safeCycles.toDouble()
        latestTelemetry["target_low_risk_protected_target_mmol"] = lowGlucoseSafetyState.protectedTargetMmol
        if (mode == RuleEvaluationMode.LIVE) {
            persistLowGlucoseTargetSafetyTelemetry(nowTs, lowGlucoseSafetyState)
        }

        val writerRouting = automaticTargetWriterRoutingStatic(settings.targetManagerMode)
        val targetProposals = mutableListOf<TargetProposal>()
        val latestGlucoseTs = context.glucose.maxOfOrNull { it.ts } ?: nowTs
        val therapyWatermark = context.therapyEvents.maxOfOrNull { it.ts } ?: 0L
        if (externalWritesAllowed && writerRouting.submitLegacyAutomatic) {
            maybeSendSensorQualityRollbackTempTarget(
                settings = settings,
                nowTs = nowTs,
                dataFresh = dataFresh,
                assessment = sensorQuality,
                activeTempTarget = activeTempTarget,
                actionsLast6h = actionsLast6h,
                baseTargetMmol = effectiveBaseTarget.effectiveTargetMmol
            )
        }

        var adaptiveTriggeredThisCycle = false
        for (effectiveDecision in effectiveDecisions) {

            db.ruleExecutionDao().insert(
                RuleExecutionEntity(
                    timestamp = nowTs,
                    ruleId = effectiveDecision.ruleId,
                    state = if (mode == RuleEvaluationMode.DIAGNOSTIC_READ_ONLY) {
                        DIAGNOSTIC_RULE_EXECUTION_STATE
                    } else {
                        effectiveDecision.state.name
                    },
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
                val normalizedAction = alignTempTargetToBaseTarget(
                    action = effectiveDecision.actionProposal,
                    forecasts = controlForecasts,
                    baseTargetMmol = effectiveBaseTarget.effectiveTargetMmol,
                    sourceRuleId = effectiveDecision.ruleId
                )
                val protectedAction = protectLowGlucoseTargetStatic(
                    action = normalizedAction,
                    state = lowGlucoseSafetyState,
                    minTargetMmol = settings.safetyMinTargetMmol,
                    maxTargetMmol = settings.safetyMaxTargetMmol
                )
                val idempotencyKey = buildIdempotencyKey(
                    ruleId = effectiveDecision.ruleId,
                    nowTs = nowTs,
                    settings = settings,
                    action = protectedAction
                )
                val targetProposal = targetProposalFactory.fromRuleDecision(
                    decision = effectiveDecision.copy(actionProposal = protectedAction),
                    priority = targetRulePriority(effectiveDecision.ruleId, settings),
                    generatedAt = nowTs,
                    inputFingerprint = listOf(
                        latestGlucoseTs,
                        therapyWatermark,
                        effectiveDecision.ruleId,
                        protectedAction.targetMmol,
                        protectedAction.durationMinutes,
                        protectedAction.reason
                    ).joinToString(":")
                )
                targetProposal?.let(targetProposals::add)

                val isAutomaticTempTarget = protectedAction.type.equals("temp_target", ignoreCase = true)
                if (externalWritesAllowed && (!isAutomaticTempTarget || writerRouting.submitLegacyAutomatic)) {
                    val command = ActionCommand(
                        id = UUID.randomUUID().toString(),
                        type = protectedAction.type,
                        params = mapOf(
                            "targetMmol" to protectedAction.targetMmol.toString(),
                            "durationMinutes" to protectedAction.durationMinutes.toString(),
                            "reason" to protectedAction.reason,
                            "targetIntent" to (targetProposal?.intent ?: TargetIntent.NORMAL_CONTROL).name
                        ),
                        safetySnapshot = SafetySnapshot(
                            killSwitch = settings.killSwitch,
                            dataFresh = dataFresh,
                            activeTempTargetMmol = activeTempTarget,
                            actionsLast6h = actionsLast6h
                        ),
                        idempotencyKey = idempotencyKey
                    )
                    submitActionWithTimeout(
                        command = command,
                        sourceRuleId = effectiveDecision.ruleId,
                        nowTs = nowTs
                    )
                }
            }
        }

        val sameCycleAdaptiveDecision = effectiveDecisions
            .asSequence()
            .filter { it.ruleId == AdaptiveTargetControllerRule.RULE_ID }
            .filter { it.state == RuleState.TRIGGERED }
            .mapNotNull { decision ->
                decision.actionProposal
                    ?.takeIf { it.type.equals("temp_target", ignoreCase = true) }
                    ?.let { action -> decision to action }
            }
            .firstOrNull()
        val sameCycleAdaptiveCandidate = sameCycleAdaptiveDecision?.second?.targetMmol
        val sameCycleAdaptiveCandidateFingerprint = sameCycleAdaptiveDecision?.let { (decision, action) ->
            listOf(
                latestGlucoseTs,
                therapyWatermark,
                decision.ruleId,
                action.targetMmol,
                action.durationMinutes,
                action.reason
            ).joinToString(":")
        }
        val safePredictedFall = activitySafePredictedFallStatic(
            currentGlucoseMmol = context.currentGlucoseMmol,
            glucose = context.glucose,
            controlForecasts = controlForecasts,
            lowRiskThresholdMmol = ACTIVITY_TARGET_LOW_RISK_THRESHOLD_MMOL
        )
        val activityProposal = ActivityTargetProposalFactory().create(
            ActivityTargetProposalInput(
                now = Instant.ofEpochMilli(nowTs),
                occurrence = forecastRuntime.plannedActivityTargetOccurrence,
                moduleEnabled = settings.energyProfile.enabled &&
                    settings.energyProfile.forecastActivityInfluenceEnabled,
                baseTargetMmol = effectiveBaseTarget.effectiveTargetMmol,
                minTargetMmol = settings.safetyMinTargetMmol,
                maxTargetMmol = settings.safetyMaxTargetMmol,
                sameCycleAdaptiveCandidateMmol = sameCycleAdaptiveCandidate,
                sameCycleAdaptiveCandidateFingerprint = sameCycleAdaptiveCandidateFingerprint,
                safePredictedFall = safePredictedFall,
                // Canonical personal/replay evidence is intentionally unavailable until Task 9.
                personalRiseEvidence = null,
                replayEvidence = null
            )
        )
        ActivityTargetProposalFactory().asTargetProposal(activityProposal, nowTs)?.let(targetProposals::add)

        if (writerRouting.evaluateManager) {
            val targetManagerNoProposalReason = effectiveDecisions
                .asSequence()
                .filter { it.ruleId == AdaptiveTargetControllerRule.RULE_ID }
                .flatMap { it.reasons.asSequence() }
                .map { it.removePrefix("reason=") }
                .firstOrNull { it == "safety_iob_missing_blocks_lowering" }
            evaluateTargetManager(
                settings = settings,
                nowTs = nowTs,
                latestGlucoseTs = latestGlucoseTs,
                therapyWatermark = therapyWatermark,
                proposals = targetProposals,
                activeAapsTarget = activeAapsTarget,
                dataFresh = dataFresh,
                localSafetyChronologyResolved = localSafetyChronologyResolved,
                latestAutomaticSent = latestAutomaticSent,
                sensorQuality = sensorQuality,
                sensorBlocked = sensorBlocked,
                currentGlucoseMmol = context.currentGlucoseMmol,
                forecasts = controlForecasts,
                calibrationPoints = forecastRuntime.calibrationPoints,
                effectiveBaseTarget = effectiveBaseTarget,
                canonicalGlucose = context.glucose,
                therapyEvents = context.therapyEvents,
                latestTelemetry = latestTelemetry,
                deliveryTrustTelemetry = deliveryTrustTelemetry,
                insulinCycleContext = insulinCycleContext,
                sensitivityRuntime = sensitivityRuntime,
                acceptedClinicalForecastAuthority = acceptedClinicalForecastAuthority,
                calibrationIdentity = forecastRuntime.calibrationIdentity,
                plannedActivityProposal = activityProposal,
                sameCycleAdaptiveCandidateMmol = sameCycleAdaptiveCandidate,
                noProposalReason = targetManagerNoProposalReason,
                activityReturnToBaseRequested = activityReturnToBaseRequestedStatic(
                    plannedActivityTargetOccurrence = forecastRuntime.plannedActivityTargetOccurrence,
                    activityContextSource = forecastRuntime.activityEffectContext.source
                ),
                dispatchAllowed = externalWritesAllowed,
                liveEvaluation = mode == RuleEvaluationMode.LIVE
            )
        }
        if (externalWritesAllowed && !adaptiveTriggeredThisCycle && writerRouting.submitLegacyAutomatic) {
            maybeSendAdaptiveKeepaliveTempTarget(
                settings = settings,
                nowTs = nowTs,
                dataFresh = dataFresh,
                sensorBlocked = sensorBlocked,
                activeTempTarget = activeTempTarget,
                actionsLast6h = actionsLast6h,
                chronologyResolved = localSafetyChronologyResolved,
                causalThroughTs = localSafetyCausalThroughTs,
                forecasts = controlForecasts,
                baseTargetMmol = effectiveBaseTarget.effectiveTargetMmol,
                lowGlucoseSafetyState = lowGlucoseSafetyState
            )
        }
        auditCycleCheckpoint("post_actions")
        auditAdaptiveController(effectiveDecisions, context, settings, controlForecasts)
        auditCycleCheckpoint("post_adaptive_audit")
        return decisions
    }

    private suspend fun prepareLowGlucoseSafetyCycle(
        nowTs: Long,
        currentGlucoseMmol: Double,
        forecasts: List<Forecast>,
        activeTempTarget: Double?,
        mode: RuleEvaluationMode
    ): LowGlucoseSafetyCycleContext {
        val activeSafetyTarget = resolveRecentActiveSafetyTarget(
            nowTs = nowTs,
            activeTempTarget = activeTempTarget
        )
        val forecastMinimum = resolveLowGlucoseForecastMinimumStatic(
            currentGlucoseMmol = currentGlucoseMmol,
            forecasts = forecasts
        )
        val state = evaluateLowGlucoseSafetyLatch(
            mode = mode,
            currentGlucoseMmol = currentGlucoseMmol,
            forecastMinimumMmol = forecastMinimum,
            activeSafetyTargetMmol = activeSafetyTarget,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = activeSafetyTarget != null
        )
        return LowGlucoseSafetyCycleContext(
            state = state,
            activeSafetyTargetMmol = activeSafetyTarget,
            forecastMinimumMmol = forecastMinimum
        )
    }

    private fun evaluateLowGlucoseSafetyLatch(
        mode: RuleEvaluationMode,
        currentGlucoseMmol: Double?,
        forecastMinimumMmol: Double?,
        activeSafetyTargetMmol: Double?,
        proposedSafetyTargetMmol: Double?,
        seedFromActiveSafety: Boolean
    ): LowGlucoseTargetSafetyLatch.State = when (mode) {
        RuleEvaluationMode.LIVE -> lowGlucoseTargetSafetyLatch.update(
            currentGlucoseMmol = currentGlucoseMmol,
            forecastMinimumMmol = forecastMinimumMmol,
            activeSafetyTargetMmol = activeSafetyTargetMmol,
            proposedSafetyTargetMmol = proposedSafetyTargetMmol,
            seedFromActiveSafety = seedFromActiveSafety
        )
        RuleEvaluationMode.DIAGNOSTIC_READ_ONLY -> lowGlucoseTargetSafetyLatch.preview(
            currentGlucoseMmol = currentGlucoseMmol,
            forecastMinimumMmol = forecastMinimumMmol,
            activeSafetyTargetMmol = activeSafetyTargetMmol,
            proposedSafetyTargetMmol = proposedSafetyTargetMmol,
            seedFromActiveSafety = seedFromActiveSafety
        )
    }

    private suspend fun resolveCircadianEffectiveBaseTarget(
        settings: AppSettings,
        nowTs: Long,
        controlForecasts: List<Forecast>,
        runtimeCobIob: RuntimeCobIobInputs,
        insulinCycleContext: InsulinCycleContext,
        sensorQuality: SensorQualityAssessment,
        sensorBlocked: Boolean,
        lowGlucoseSafetyState: LowGlucoseTargetSafetyLatch.State,
        latestTelemetry: MutableMap<String, Double?>
    ): EffectiveBaseTarget {
        val result = circadianTargetRepository.resolveEffectiveTarget(
            now = nowTs,
            schedule = settings.baseTargetSchedule,
            zoneId = ZoneId.systemDefault(),
            targetManagerMode = settings.targetManagerMode,
            hardMinTargetMmol = settings.safetyMinTargetMmol,
            hardMaxTargetMmol = settings.safetyMaxTargetMmol,
            gates = EffectiveTargetRuntimeGates(
                sensorTrust = resolveSensorTrustStateStatic(sensorQuality, sensorBlocked),
                sensorAgeHours = latestTelemetry["sensor_age_hours"]
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: latestTelemetry["sensor_lag_age_hours"]
                        ?.takeIf { it.isFinite() && it >= 0.0 },
                lowRiskLatched = lowGlucoseSafetyState.latched,
                forecast5CiLowMmol = controlForecasts
                    .firstOrNull { it.horizonMinutes == 5 }
                    ?.ciLow,
                forecast30CiLowMmol = controlForecasts
                    .firstOrNull { it.horizonMinutes == 30 }
                    ?.ciLow,
                safetyIobUnits = insulinCycleContext.safetyIobUnits,
                effectiveCobGrams = runtimeCobIob.cobGrams,
                uamActive = latestTelemetry["uam_runtime_control_flag"]
                    ?.takeIf(Double::isFinite)
                    ?.let { it >= 0.5 }
                    ?: false
            )
        )
        latestTelemetry["target_base_manual_mmol"] = result.manualTargetMmol
        latestTelemetry["target_base_auto_delta_mmol"] = result.autoDeltaMmol
        latestTelemetry["target_base_effective_mmol"] = result.effectiveTargetMmol
        latestTelemetry["target_base_schedule_revision"] = result.scheduleRevision.toDouble()
        auditLogger.infoThrottled(
            throttleKey = "effective_base_target_resolved",
            intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
            message = "effective_base_target_resolved",
            metadata = mapOf(
                "manualTargetMmol" to result.manualTargetMmol,
                "autoDeltaMmol" to result.autoDeltaMmol,
                "effectiveTargetMmol" to result.effectiveTargetMmol,
                "state" to result.state.name,
                "scheduleRevision" to result.scheduleRevision,
                "intervalId" to result.intervalId.orEmpty(),
                "adjustmentRunId" to result.adjustmentRunId.orEmpty(),
                "reasons" to result.reasonCodes.joinToString("|")
            )
        )
        return result
    }

    private suspend fun evaluateTargetManager(
        settings: AppSettings,
        nowTs: Long,
        latestGlucoseTs: Long,
        therapyWatermark: Long,
        proposals: List<TargetProposal>,
        activeAapsTarget: ActiveAapsTarget?,
        dataFresh: Boolean,
        localSafetyChronologyResolved: Boolean,
        latestAutomaticSent: LastSentTempTarget?,
        sensorQuality: SensorQualityAssessment,
        sensorBlocked: Boolean,
        currentGlucoseMmol: Double?,
        forecasts: List<Forecast>,
        calibrationPoints: List<ForecastCalibrationPoint>,
        effectiveBaseTarget: EffectiveBaseTarget,
        canonicalGlucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        latestTelemetry: MutableMap<String, Double?>,
        deliveryTrustTelemetry: MutableList<DeliveryTrustTelemetryValue>,
        insulinCycleContext: InsulinCycleContext,
        sensitivityRuntime: SensitivityRuntimeConsumerContext,
        acceptedClinicalForecastAuthority: AcceptedClinicalForecasts,
        calibrationIdentity: GlucoseCalibrationCycleIdentity,
        plannedActivityProposal: io.aaps.copilot.domain.profile.ActivityTargetProposal,
        sameCycleAdaptiveCandidateMmol: Double?,
        noProposalReason: String?,
        activityReturnToBaseRequested: Boolean,
        dispatchAllowed: Boolean,
        liveEvaluation: Boolean
    ) {
        val reliability = listOf(5, 30, 60).associateWith { horizon ->
            val forecast = forecasts.firstOrNull { it.horizonMinutes == horizon }
            buildHorizonReliabilityStatic(
                horizonMinutes = horizon,
                currentForecast = forecast,
                maturedPoints = calibrationPoints,
                evaluatedAt = nowTs
            )
        }
        val minimumPredictedOrCi = forecasts.asSequence()
            .flatMap { sequenceOf(it.valueMmol, it.ciLow) }
            .filter(Double::isFinite)
            .minOrNull()
        val sensorTrust = resolveSensorTrustStateStatic(sensorQuality, sensorBlocked)
        val deliveryTrust = resolveLiveDeliveryTrustStatic(
            nowTs = nowTs,
            canonicalGlucose = canonicalGlucose,
            therapyEvents = therapyEvents,
            sensorTrust = sensorTrust,
            latestTelemetry = latestTelemetry
        )
        val encodedDeliveryTrust = DeliveryTrustStateWireCodec.encode(deliveryTrust)
        latestTelemetry[DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY] = encodedDeliveryTrust
        deliveryTrustTelemetry += buildSameCycleAlertDeliveryTrustTelemetryStatic(
            nowTs = nowTs,
            state = deliveryTrust
        )
        val activityForecasts = forecasts
            .filter { it.horizonMinutes in setOf(5, 30, 60) }
            .associate { forecast ->
                forecast.horizonMinutes to ActivityForecastSafety(
                    horizonMinutes = forecast.horizonMinutes,
                    valueMmol = forecast.valueMmol,
                    ciLowMmol = forecast.ciLow,
                    ciHighMmol = forecast.ciHigh
                )
            }
        val observedDelta5 = contextDelta5MmolStatic(context = canonicalGlucose)
        val activitySafety = ActivityTargetSafetyContext(
            moduleEnabled = settings.energyProfile.enabled &&
                settings.energyProfile.forecastActivityInfluenceEnabled,
            occurrenceId = plannedActivityProposal.occurrenceId.ifBlank { null },
            occurrenceRevision = plannedActivityProposal.occurrenceRevision.takeIf { it >= 0L },
            validFromMs = plannedActivityProposal.validFromMs,
            validUntilMs = plannedActivityProposal.validUntilMs,
            evidenceHash = plannedActivityProposal.evidenceHash.ifBlank { null },
            sameCycleAdaptiveCandidateMmol = sameCycleAdaptiveCandidateMmol,
            sameCycleCandidateFingerprint = plannedActivityProposal.sameCycleCandidateFingerprint,
            personalEvidenceHash = plannedActivityProposal.personalEvidenceHash,
            replayHash = plannedActivityProposal.replayHash,
            observedDelta5Mmol = observedDelta5,
            diagnosticIobUnits = latestTelemetry["iob_effective_units"]?.takeIf(Double::isFinite),
            cobGrams = latestTelemetry["cob_effective_grams"]?.takeIf(Double::isFinite),
            uamActive = latestTelemetry["uam_runtime_control_flag"]
                ?.takeIf(Double::isFinite)
                ?.let { it >= 0.5 }
                ?: false,
            forecasts = activityForecasts,
            safePredictedFall = activitySafePredictedFallStatic(
                currentGlucoseMmol = currentGlucoseMmol,
                glucose = canonicalGlucose,
                controlForecasts = forecasts,
                lowRiskThresholdMmol = ACTIVITY_TARGET_LOW_RISK_THRESHOLD_MMOL
            ),
            keepaliveAllowed = plannedActivityProposal.targetMmol != null &&
                plannedActivityProposal.blockers.isEmpty() &&
                nowTs in plannedActivityProposal.validFromMs until plannedActivityProposal.validUntilMs,
            returnToBaseRequested = activityReturnToBaseRequested
        )
        val input = TargetManagerInput(
            nowTs = nowTs,
            glucoseTimestamp = latestGlucoseTs,
            therapyWatermark = therapyWatermark,
            mode = settings.targetManagerMode,
            proposals = proposals,
            runtimeState = TargetManagerRuntimeState(settings.targetManagerMode),
            activeAapsTarget = activeAapsTarget,
            safety = TargetManagerSafetyContext(
                killSwitch = settings.killSwitch,
                dataFresh = dataFresh,
                sensorTrust = sensorTrust,
                deliveryTrust = deliveryTrust,
                currentGlucoseMmol = currentGlucoseMmol,
                minimumPredictedOrCiMmol = minimumPredictedOrCi,
                lowRiskThresholdMmol = 4.4,
                minTargetMmol = settings.safetyMinTargetMmol,
                maxTargetMmol = settings.safetyMaxTargetMmol,
                minDurationMinutes = 15,
                maxDurationMinutes = 120,
                baseTargetMmol = effectiveBaseTarget.effectiveTargetMmol,
                safetyIobUnits = insulinCycleContext.safetyIobUnits,
                localChronologyResolved = localSafetyChronologyResolved
            ),
            reliability = reliability,
            lastAutomaticSent = latestAutomaticSent,
            baseProvenance = TargetBaseProvenance(
                scheduleRevision = effectiveBaseTarget.scheduleRevision,
                intervalId = effectiveBaseTarget.intervalId,
                adjustmentRunId = effectiveBaseTarget.adjustmentRunId
            ),
            activitySafety = activitySafety,
            sensitivityRuntime = sensitivityRuntime,
            calibrationIdentity = calibrationIdentity,
            copilotPriorityEnabled = settings.targetManagerCopilotPriorityEnabled,
            priorityRevision = settings.targetManagerPolicyRevision
        )
        require(forecasts == acceptedClinicalForecastAuthority.forecasts) {
            "Target Manager must consume the exact authenticated accepted forecast tuple"
        }
        evaluateTargetManagerWithFailureBoundaryStatic(
            mode = settings.targetManagerMode,
            evaluateAndReport = {
                evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                    nowTs = nowTs,
                    state = deliveryTrust,
                    evaluate = {
                        if (dispatchAllowed) {
                            targetManagerRepository.evaluateAndDispatch(input)
                        } else {
                            targetManagerRepository.evaluateReadOnly(input)
                        }
                    },
                    persist = { row ->
                        db.telemetryDao().upsertAll(
                            listOf(row) + buildTargetManagerSensitivityTelemetryRowsStatic(
                                nowTs = nowTs,
                                sensitivityRuntime = sensitivityRuntime
                            ) + buildAcceptedClinicalForecastTelemetryRowsStatic(
                                nowTs = nowTs,
                                source = TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                                keyPrefix = "target_manager_accepted_forecast",
                                authority = acceptedClinicalForecastAuthority
                            )
                        )
                    },
                    warn = auditLogger::warn,
                    reportDecision = { decision ->
                        runBestEffortTargetManagerPostEvaluationAuditStatic {
                            auditLogger.info(
                                "target_manager_decision",
                                mapOf(
                                    "mode" to settings.targetManagerMode.name,
                                    "outcome" to decision.outcome.name,
                                    "winner" to decision.winner?.sourceRuleId.orEmpty(),
                                    "targetMmol" to (decision.winner?.targetMmol ?: -1.0),
                                    "semanticFingerprint" to decision.semanticFingerprint.orEmpty(),
                                    "cadenceOutcome" to decision.cadenceOutcome?.name.orEmpty(),
                                    "cadenceReason" to decision.cadenceReason.orEmpty(),
                                    "scheduleRevision" to effectiveBaseTarget.scheduleRevision,
                                    "intervalId" to effectiveBaseTarget.intervalId.orEmpty(),
                                    "adjustmentRunId" to effectiveBaseTarget.adjustmentRunId.orEmpty(),
                                    "deliveryTrust" to deliveryTrust.name,
                                    "sensitivityCycleId" to sensitivityRuntime.snapshot.forecastCycleId,
                                    "sensitivitySettingsRevision" to
                                        sensitivityRuntime.snapshot.settingsRevision
                                )
                            )
                        }
                        reportTargetManagerLiveStatusStatic(
                            liveEvaluation = liveEvaluation,
                            status = buildTargetManagerLiveStatusStatic(
                                nowTs = nowTs,
                                mode = settings.targetManagerMode,
                                priorityEnabled = settings.targetManagerCopilotPriorityEnabled,
                                policyRevision = settings.targetManagerPolicyRevision,
                                activeAapsTarget = activeAapsTarget,
                                decision = decision,
                                noProposalReason = noProposalReason,
                                dispatchAllowed = dispatchAllowed
                            ),
                            persist = { row -> db.telemetryDao().upsertAll(listOf(row)) }
                        )
                    }
                )
            },
            reportEvaluationFailure = auditLogger::error
        )
    }

    private fun targetRulePriority(ruleId: String, settings: AppSettings): Int = when (ruleId) {
        AdaptiveTargetControllerRule.RULE_ID -> settings.adaptiveControllerPriority
        "PostHypoReboundGuard.v1" -> settings.rulePostHypoPriority
        "PatternAdaptiveTarget.v1" -> settings.rulePatternPriority
        "SegmentProfileGuard.v1" -> settings.ruleSegmentPriority
        else -> 0
    }

    private suspend fun maybeRecalculateAnalytics(settings: AppSettings) {
        // Analytics recalculation is intentionally decoupled from reactive runtime cycle.
        // Heavy DB writes can block forecast/action loop; use dedicated analysis workers/manual runs instead.
        auditLogger.infoThrottled(
            throttleKey = "analytics_recalculate_skipped",
            intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
            message = "analytics_recalculate_skipped",
            metadata = mapOf(
                "reason" to "decoupled_from_reactive_cycle",
                "trigger" to "automation_cycle"
            )
        )
    }

    private suspend fun resolveRealtimeIsfCrSnapshot(nowTs: Long): ResolvedRealtimeIsfCrSnapshot {
        val latest = runCatching { isfCrRepository.latestSnapshot() }.getOrNull()
        val ageMs = latest?.let { nowTs - it.ts } ?: Long.MAX_VALUE

        if (latest != null && ageMs in 0..ISFCR_SNAPSHOT_FRESHNESS_MS) {
            return ResolvedRealtimeIsfCrSnapshot(
                snapshot = latest,
                servedMode = "FRESH",
                ageMs = ageMs
            )
        }

        if (latest == null) {
            auditLogger.warnThrottled(
                throttleKey = "isfcr_realtime_unavailable:missing",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "isfcr_realtime_unavailable",
                metadata = mapOf("reason" to "snapshot_missing_or_stale")
            )
        } else {
            if (ageMs <= ISFCR_STALE_REUSE_MAX_MS) {
                val reused = latest.copy(
                    mode = IsfCrRuntimeMode.FALLBACK,
                    confidence = latest.confidence.coerceIn(0.05, ISFCR_STALE_REUSE_CONFIDENCE_MAX),
                    reasons = (latest.reasons + "stale_snapshot_reused").distinct()
                )
                auditLogger.infoThrottled(
                    throttleKey = "isfcr_realtime_stale_reused",
                    intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                    message = "isfcr_realtime_stale_reused",
                    metadata = mapOf(
                        "snapshotTs" to latest.ts,
                        "snapshotAgeMs" to ageMs,
                        "confidence" to reused.confidence
                    )
                )
                return ResolvedRealtimeIsfCrSnapshot(
                    snapshot = reused,
                    servedMode = "STALE_REUSED",
                    ageMs = ageMs
                )
            }
            auditLogger.warnThrottled(
                throttleKey = "isfcr_realtime_unavailable:stale",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "isfcr_realtime_unavailable",
                metadata = mapOf(
                    "reason" to "snapshot_stale",
                    "snapshotTs" to latest.ts,
                    "snapshotAgeMs" to ageMs,
                    "freshnessMs" to ISFCR_SNAPSHOT_FRESHNESS_MS
                )
            )
        }
        return ResolvedRealtimeIsfCrSnapshot(
            snapshot = null,
            servedMode = "MISSING",
            ageMs = if (latest != null && ageMs != Long.MAX_VALUE) ageMs else null
        )
    }

    private suspend fun maybeRunBaselineImport(
        settings: AppSettings,
        nowTs: Long
    ) {
        val exportFolderUri = settings.exportFolderUri?.trim().orEmpty()
        if (exportFolderUri.isBlank()) {
            auditLogger.infoThrottled(
                throttleKey = "baseline_import_skipped:missing_export_uri_runtime",
                intervalMs = BASELINE_IMPORT_SKIP_LOG_INTERVAL_MS,
                message = "baseline_import_skipped",
                metadata = mapOf("reason" to "missing_export_uri_runtime_skip")
            )
            return
        }
        if ((nowTs - lastBaselineImportAttemptTs) < BASELINE_IMPORT_INTERVAL_MS) {
            auditLogger.infoThrottled(
                throttleKey = "baseline_import_skipped:interval",
                intervalMs = BASELINE_IMPORT_SKIP_LOG_INTERVAL_MS,
                message = "baseline_import_skipped",
                metadata = mapOf(
                    "reason" to "interval_guard",
                    "intervalMs" to BASELINE_IMPORT_INTERVAL_MS
                )
            )
            return
        }
        lastBaselineImportAttemptTs = nowTs
        exportRepository.importBaselineFromExports()
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
                    isfCrRealtimeLastRefreshDurationMs = System.currentTimeMillis() - startedAt
                    auditLogger.info(
                        "isfcr_realtime_refresh_completed",
                        mapOf(
                            "durationMs" to isfCrRealtimeLastRefreshDurationMs,
                            "snapshotTs" to snapshot.ts
                        )
                    )
                }.onFailure { error ->
                    isfCrRealtimeLastFailureTs = System.currentTimeMillis()
                    isfCrRealtimeLastRefreshDurationMs = System.currentTimeMillis() - startedAt
                    auditLogger.warn(
                        "isfcr_realtime_refresh_failed",
                        mapOf(
                            "timeout" to (error is TimeoutCancellationException),
                            "durationMs" to isfCrRealtimeLastRefreshDurationMs,
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
        val refreshBucket = (nowTs / CALIBRATION_REFRESH_INTERVAL_MS) * CALIBRATION_REFRESH_INTERVAL_MS
        if (refreshBucket == lastCalibrationRefreshBucketTs) {
            return lastCalibrationPointsCache
        }
        val forecastHistory = db.forecastDao().latest(CALIBRATION_FORECAST_LIMIT)
        if (forecastHistory.isEmpty()) {
            lastCalibrationRefreshBucketTs = refreshBucket
            lastCalibrationPointsCache = emptyList()
            return emptyList()
        }
        val glucoseHistory = glucoseCalibrationRepository.resolveGlucoseHistory(
            rawGlucose = GlucoseSanitizer.filterEntities(db.glucoseDao().latest(CALIBRATION_GLUCOSE_LIMIT)),
            nowTs = nowTs
        ).sortedBy { it.ts }
        if (glucoseHistory.isEmpty()) {
            lastCalibrationRefreshBucketTs = refreshBucket
            lastCalibrationPointsCache = emptyList()
            return emptyList()
        }

        val sensorTrustTimeline = TreeMap<Long, Boolean>()
        db.telemetryDao().sinceByKeys(
            since = nowTs - CALIBRATION_LOOKBACK_MS - SENSOR_TRUST_MATCH_TOLERANCE_MS,
            keys = FORECAST_RELIABILITY_SENSOR_KEYS
        ).groupBy { it.timestamp }
            .forEach { (timestamp, rows) ->
                val values = rows.associate { it.key to it.valueDouble }
                val score = values["sensor_quality_score"]
                val blocked = values["sensor_quality_blocked"]
                val suspectFalseLow = values["sensor_quality_suspect_false_low"]
                sensorTrustTimeline[timestamp] = score != null && score.isFinite() && score >= 0.65 &&
                    blocked != null && blocked.isFinite() && blocked < 0.5 &&
                    suspectFalseLow != null && suspectFalseLow.isFinite() && suspectFalseLow < 0.5
            }

        val computed = forecastHistory.asSequence()
            .filter { row ->
                val age = nowTs - row.timestamp
                age in CALIBRATION_MIN_AGE_MS..CALIBRATION_LOOKBACK_MS
            }
            .mapNotNull { row ->
                val nearest = nearestResolvedGlucoseAt(
                    targetTs = row.timestamp,
                    sorted = glucoseHistory,
                    toleranceMs = CALIBRATION_MATCH_TOLERANCE_MS
                ) ?: return@mapNotNull null
                val forecastGeneratedAt = forecastGeneratedAtStatic(
                    targetTimestamp = row.timestamp,
                    horizonMinutes = row.horizonMinutes
                ) ?: return@mapNotNull null
                ForecastCalibrationPoint(
                    horizonMinutes = row.horizonMinutes,
                    errorMmol = nearest.calibratedMmol - row.valueMmol,
                    ageMs = nowTs - row.timestamp,
                    predictedMmol = row.valueMmol,
                    ciLowMmol = row.ciLow,
                    ciHighMmol = row.ciHigh,
                    sensorTrusted = sensorTrustTimeline.floorEntry(forecastGeneratedAt)
                        ?.takeIf { entry ->
                            forecastGeneratedAt - entry.key in 0L..SENSOR_TRUST_MATCH_TOLERANCE_MS
                        }
                        ?.value == true,
                    modelVersion = row.modelVersion
                )
            }
            .toList()
        lastCalibrationRefreshBucketTs = refreshBucket
        lastCalibrationPointsCache = computed
        return computed
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

    private fun nearestResolvedGlucoseAt(
        targetTs: Long,
        sorted: List<ResolvedGlucosePoint>,
        toleranceMs: Long
    ): ResolvedGlucosePoint? {
        if (sorted.isEmpty()) return null
        var lo = 0
        var hi = sorted.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val midTs = sorted[mid].ts
            when {
                midTs < targetTs -> lo = mid + 1
                midTs > targetTs -> hi = mid - 1
                else -> return sorted[mid]
            }
        }

        val right = sorted.getOrNull(lo)
        val left = sorted.getOrNull(lo - 1)
        val rightDiff = right?.let { abs(it.ts - targetTs) } ?: Long.MAX_VALUE
        val leftDiff = left?.let { abs(it.ts - targetTs) } ?: Long.MAX_VALUE
        val best = if (rightDiff < leftDiff) right else left
        return best?.takeIf { abs(it.ts - targetTs) <= toleranceMs }
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
        diagnosticIobUnits: Double?,
        latestGlucoseMmol: Double? = null,
        uamActive: Boolean? = null,
        insulinCycleContext: InsulinCycleContext,
        isfMmolPerUnit: Double? = null,
    ): List<Forecast> {
        return applyCobIobForecastBiasStatic(
            forecasts = forecasts,
            cobGrams = cobGrams,
            diagnosticIobUnits = diagnosticIobUnits,
            latestGlucoseMmol = latestGlucoseMmol,
            uamActive = uamActive,
            insulinCycleContext = insulinCycleContext,
            isfMmolPerUnit = isfMmolPerUnit,
        )
    }

    private fun applyCircadianPatternForecastBias(
        forecasts: List<Forecast>,
        prior: CircadianForecastPrior?,
        latestGlucoseMmol: Double,
        settings: AppSettings
    ): List<Forecast> {
        return applyCircadianPatternForecastBiasStatic(
            forecasts = forecasts,
            prior = prior,
            latestGlucoseMmol = latestGlucoseMmol,
            weight30 = settings.circadianForecastWeight30,
            weight60 = settings.circadianForecastWeight60
        )
    }

    private suspend fun resolveActivityEffectContext(
        settings: AppSettings,
        nowTs: Long,
        latestTelemetry: MutableMap<String, Double?>
    ): ActivityEffectContext {
        if (!settings.energyProfile.enabled || !settings.energyProfile.forecastActivityInfluenceEnabled) {
            return ActivityEffectContext.DISABLED
        }
        val measured = physicalActivityMeasurementStatic(
            sample = db.telemetryDao().latestPhysicalActivityRatioSince(
                since = nowTs - PHYSICAL_ACTIVITY_MEASUREMENT_FRESHNESS_MS,
                through = nowTs
            ),
            nowTs = nowTs
        )
        val context = activityEffectModel.evaluate(
            enabled = true,
            now = Instant.ofEpochMilli(nowTs),
            planned = resolveCurrentPlannedActivity(nowTs),
            measured = measured,
            // Canonical replay evidence is intentionally unavailable until Task 9.
            replayEvidence = null
        )
        latestTelemetry["activity_context_factor_5m"] = context.factor5
        latestTelemetry["activity_context_factor_30m"] = context.factor30
        latestTelemetry["activity_context_factor_60m"] = context.factor60
        latestTelemetry["activity_context_confidence"] = context.confidence
        return context
    }

    private suspend fun resolveCurrentPlannedActivity(nowTs: Long): PlannedActivityOccurrence? {
        val now = Instant.ofEpochMilli(nowTs)
        val candidates = db.energyProfileDao().enabledEvents().flatMap { entity ->
            val schedule = entity.toPlannedActivityScheduleOrNull() ?: return@flatMap emptyList()
            val zone = runCatching { ZoneId.of(schedule.timezoneId) }.getOrNull() ?: return@flatMap emptyList()
            val localDate = now.atZone(zone).toLocalDate()
            listOf(localDate, localDate.minusDays(1)).mapNotNull { date ->
                activityScheduleEngine.materialize(schedule, date)
            }
        }.filter { occurrence -> activityEffectModel.isWithinPlannedEffectWindow(now, occurrence) }
        return candidates.singleOrNull()
    }

    private suspend fun resolvePlannedActivityTargetOccurrence(nowTs: Long): PlannedActivityOccurrence? {
        val now = Instant.ofEpochMilli(nowTs)
        val candidates = db.energyProfileDao().enabledEvents().flatMap { entity ->
            val schedule = entity.toPlannedActivityScheduleOrNull() ?: return@flatMap emptyList()
            val zone = runCatching { ZoneId.of(schedule.timezoneId) }.getOrNull() ?: return@flatMap emptyList()
            val localDate = now.atZone(zone).toLocalDate()
            listOf(localDate, localDate.minusDays(1)).mapNotNull { date ->
                activityScheduleEngine.materialize(schedule, date)
            }
        }.filter { occurrence ->
            activityEffectModel.isWithinPlannedTargetWindow(now, occurrence)
        }
        // Ambiguous overlapping plans fail closed even if legacy records bypassed schedule validation.
        return candidates.singleOrNull()
    }

    private fun contextDelta5MmolStatic(context: List<GlucosePoint>): Double? {
        val latest = context.sortedBy(GlucosePoint::ts).takeLast(2)
        if (latest.size < 2) return null
        val elapsed = latest[1].ts - latest[0].ts
        val values = latest.map(GlucosePoint::valueMmol)
        if (elapsed !in 60_000L..15 * 60_000L || values.any { !it.isFinite() }) return null
        return (values[1] - values[0]) * (5 * 60_000.0 / elapsed.toDouble())
    }

    private fun activitySafePredictedFallStatic(
        currentGlucoseMmol: Double?,
        glucose: List<GlucosePoint>,
        controlForecasts: List<Forecast>,
        lowRiskThresholdMmol: Double
    ): ActivitySafePredictedFall? {
        val current = currentGlucoseMmol?.takeIf(Double::isFinite) ?: return null
        val delta = contextDelta5MmolStatic(glucose) ?: return null
        val byHorizon = controlForecasts.associateBy(Forecast::horizonMinutes)
        val forecast5 = byHorizon[5] ?: return null
        val forecast30 = byHorizon[30] ?: return null
        val forecast60 = byHorizon[60] ?: return null
        val evidenceHash = listOf(
            current,
            delta,
            lowRiskThresholdMmol,
            forecast5.ts,
            forecast5.valueMmol,
            forecast5.ciLow,
            forecast30.ts,
            forecast30.valueMmol,
            forecast30.ciLow,
            forecast60.ts,
            forecast60.valueMmol,
            forecast60.ciLow
        ).joinToString(":")
        return ActivitySafePredictedFall(
            evidenceHash = evidenceHash,
            currentGlucoseMmol = current,
            observedDelta5Mmol = delta,
            pred5Mmol = forecast5.valueMmol,
            pred30Mmol = forecast30.valueMmol,
            pred60Mmol = forecast60.valueMmol,
            ciLow5Mmol = forecast5.ciLow,
            ciLow30Mmol = forecast30.ciLow,
            ciLow60Mmol = forecast60.ciLow,
            lowRiskThresholdMmol = lowRiskThresholdMmol
        )
    }

    private fun PlannedActivityEventEntity.toPlannedActivityScheduleOrNull(): PlannedActivitySchedule? = runCatching {
        PlannedActivitySchedule(
            eventId = eventId,
            enabled = enabled,
            title = title,
            type = PlannedActivityType.valueOf(activityType),
            intensity = PlannedActivityIntensity.valueOf(intensity),
            localStart = LocalDateTime.parse(localStartIso),
            durationMinutes = durationMinutes,
            timezoneId = timezoneId,
            recurrenceDays = DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
                recurrenceDaysMask and (1 shl (day.value - 1)) != 0
            },
            recurrenceEndEpochDay = recurrenceEndEpochDay,
            revision = revision,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs
        )
    }.getOrNull()

    private suspend fun resolveLatestTelemetry(
        nowTs: Long,
        settings: AppSettings
    ): ResolvedLatestTelemetry {
        val baseRows = db.telemetryDao().latestBySourceAndKeySince(nowTs - TELEMETRY_LOOKBACK_MS)
        val reportRows = db.telemetryDao().latestReportAndProfileSince(nowTs - TELEMETRY_REPORT_LOOKBACK_MS)
        val dayStartTs = Instant.ofEpochMilli(nowTs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val activityRowsToday = resolveCumulativeActivityRowsToday(dayStartTs)
        val readCompletedAt = System.currentTimeMillis()
        val deliveryTrustTelemetry = selectAcceptedAlertDeliveryTrustTelemetryStatic(
            baseRows = baseRows,
            reportRows = reportRows
        )
        val rows = (baseRows + reportRows)
            .distinctBy { row -> "${row.source}:${row.key}:${row.timestamp}" }
        if (rows.isEmpty()) return ResolvedLatestTelemetry(emptyMap(), emptyList())
        val usableRows = rows
            .filter { it.valueDouble != null && telemetryValueUsable(it.key, it.valueDouble) }
        val usableActivityRowsToday = activityRowsToday
            .filter { row -> row.valueDouble != null && telemetryValueUsable(row.key, row.valueDouble) }
        val latestTimestampByKey = usableRows
            .groupBy { it.key }
            .mapValues { (_, values) -> values.maxOfOrNull { it.timestamp } ?: 0L }
            .toMutableMap()
        val latestByKey = usableRows
            .groupBy { it.key }
            .mapValues { (key, values) ->
                if (key in CUMULATIVE_ACTIVITY_KEYS) {
                    val dayValues = usableActivityRowsToday.filter { it.key == key }
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
                latestTimestampByKey[targetKey] = preferred.second.first
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
            if (candidate != null) {
                latestTimestampByKey[targetKey] = latestTimestampByKey[candidate] ?: Long.MIN_VALUE
            }
        }

        alias(
            targetKey = "iob_units",
            preferredKeys = listOf(
                "iob_units",
                "raw_iob",
                "raw_iob_units",
                "raw_iob_iob",
                "raw_openaps_iob_iob"
            ),
            tokenAliases = listOf("insulinonboard")
        )
        val selectedAuthoritativeIob = selectStrictAapsIob(rows)
        val runtimeInsulinResolution = resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = readCompletedAt,
            freshnessMs = settings.staleDataMaxMinutes.coerceIn(5, 60) * 60_000L
        )
        val runtimeInsulinSnapshot = runtimeInsulinResolution.snapshot
        ATOMIC_AAPS_INSULIN_PACKET_KEYS.forEach { key -> latestByKey[key] = null }
        if (runtimeInsulinSnapshot != null) {
            val snapshot = runtimeInsulinSnapshot
            latestByKey["iob_units"] = snapshot.effectivePositiveIobUnits
            latestByKey["iob_net_units"] = snapshot.netIobUnits
            latestByKey["iob_bolus_units"] = snapshot.bolusIobUnits
            latestByKey["iob_basal_units"] = snapshot.basalIobUnits
            latestByKey["insulin_activity"] = snapshot.insulinActivity
            latestByKey["iob_effective_positive_units"] = snapshot.effectivePositiveIobUnits
            latestByKey["iob_relay_timestamp_ms"] = snapshot.timestamp.toDouble()
            latestByKey["iob_runtime_confidence"] = snapshot.confidence
            latestByKey["iob_runtime_source_code"] = InsulinRuntimeSnapshotResolver.sourceCode(snapshot.source)
            ATOMIC_AAPS_INSULIN_PACKET_KEYS.forEach { key ->
                latestTimestampByKey[key] = snapshot.timestamp
            }
        }
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
        val selectedAuthoritativeCob = selectStrictAapsCob(rows)
        selectedAuthoritativeCob?.let { selected ->
            latestByKey["cob_grams"] = selected.valueDouble
            latestTimestampByKey["cob_grams"] = selected.timestamp
        }
        val selectedAuthoritativeIsf = selectStrictAapsIsf(rows)
        val selectedAuthoritativeCr = selectStrictAapsCr(rows)
        alias(
            targetKey = "activity_ratio",
            preferredKeys = listOf("activity_ratio", "raw_activityratio"),
            tokenAliases = listOf("activityratio", "sensitivityratio")
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
                ((latestTimestampByKey[key]?.let { ts -> readCompletedAt - ts > runtimeFreshnessMs }) == true)
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
        val iobProvenance = resolveIobSampleProvenanceStatic(
            selected = selectedAuthoritativeIob,
            nowTs = readCompletedAt,
            freshnessMs = runtimeFreshnessMs
        )
        val runtimeIobSampleTs = latestByKey["iob_relay_timestamp_ms"]
            ?.takeIf { it.isFinite() }
            ?.toLong()
        val effectiveIobSampleTs = runtimeIobSampleTs ?: iobProvenance.sampleTs
        latestByKey["iob_sample_ts"] = effectiveIobSampleTs?.toDouble()
        latestByKey["iob_sample_age_min"] = effectiveIobSampleTs?.let { (readCompletedAt - it) / 60_000.0 }
        latestByKey["iob_sample_fresh"] = if (
            effectiveIobSampleTs != null &&
            effectiveIobSampleTs in 0..readCompletedAt &&
            readCompletedAt - effectiveIobSampleTs <= runtimeFreshnessMs
        ) 1.0 else 0.0
        selectedAuthoritativeIsf?.let { selected ->
            val sampleTs = selected.timestamp.takeIf { it in 0..readCompletedAt }
            latestByKey["isf_aaps_raw_value"] = selected.valueDouble
            latestByKey["isf_aaps_raw_sample_ts"] = sampleTs?.toDouble()
            latestByKey["isf_aaps_raw_age_min"] = sampleTs?.let { (readCompletedAt - it) / 60_000.0 }
        }
        selectedAuthoritativeCr?.let { selected ->
            val sampleTs = selected.timestamp.takeIf { it in 0..readCompletedAt }
            latestByKey["cr_aaps_raw_value"] = selected.valueDouble
            latestByKey["cr_aaps_raw_sample_ts"] = sampleTs?.toDouble()
            latestByKey["cr_aaps_raw_age_min"] = sampleTs?.let { (readCompletedAt - it) / 60_000.0 }
        }
        return ResolvedLatestTelemetry(
            values = latestByKey,
            deliveryTrustTelemetry = deliveryTrustTelemetry
        )
    }

    private suspend fun resolveCumulativeActivityRowsToday(dayStartTs: Long): List<TelemetrySampleEntity> {
        val previous = cumulativeActivityDayCache?.takeIf { it.dayStartTs == dayStartTs }
        val bestRowsByKey = previous?.rowsByKey?.toMutableMap() ?: linkedMapOf()
        var afterTimestamp = previous?.cursorTimestamp ?: (dayStartTs - 1L)
        var afterId = previous?.cursorId ?: ""
        while (true) {
            val page = db.telemetryDao().sinceByKeysPage(
                since = dayStartTs,
                keys = CUMULATIVE_ACTIVITY_KEYS.toList(),
                afterTimestamp = afterTimestamp,
                afterId = afterId,
                limit = CUMULATIVE_ACTIVITY_PAGE_SIZE
            )
            if (page.isEmpty()) break
            page.forEach { row ->
                val value = row.valueDouble
                if (!telemetryValueUsable(row.key, value)) return@forEach
                val entity = row.toEntity()
                val existing = bestRowsByKey[row.key]
                val replace = existing == null ||
                    (value ?: Double.NEGATIVE_INFINITY) > (existing.valueDouble ?: Double.NEGATIVE_INFINITY) ||
                    (
                        value == existing.valueDouble &&
                            entity.timestamp > existing.timestamp
                        )
                if (replace) {
                    bestRowsByKey[row.key] = entity
                }
            }
            val last = page.last()
            afterTimestamp = last.timestamp
            afterId = last.id
            if (page.size < CUMULATIVE_ACTIVITY_PAGE_SIZE) break
        }
        cumulativeActivityDayCache = CumulativeActivityDayCache(
            dayStartTs = dayStartTs,
            cursorTimestamp = afterTimestamp,
            cursorId = afterId,
            rowsByKey = bestRowsByKey.toMap()
        )
        return bestRowsByKey.values.toList()
    }

    private suspend fun resolveLatestGlucoseInputMetadata(
        latestGlucose: GlucosePoint,
        nowTs: Long
    ): GlucoseInputMetadata? {
        val rows = db.telemetryDao().sinceByKeysDescLimit(
            since = maxOf(latestGlucose.ts - 60L * 60L * 1000L, nowTs - TELEMETRY_LOOKBACK_MS),
            keys = listOf("glucose_input_key", "glucose_input_kind"),
            limit = 24
        )
        return resolveLatestGlucoseInputMetadataStatic(
            rows = rows,
            latestGlucoseTs = latestGlucose.ts,
            latestGlucoseSource = latestGlucose.source
        )
    }

    private fun applySensorLagTelemetry(
        latestTelemetry: MutableMap<String, Double?>,
        estimate: SensorLagEstimate,
        latestGlucoseInput: GlucoseInputMetadata?
    ) {
        latestTelemetry += buildSensorLagTelemetryMapUpdatesStatic(
            estimate = estimate,
            latestGlucoseInput = latestGlucoseInput
        )
    }

    private suspend fun resolveSensorLagRuntimeContext(
        nowTs: Long
    ): SensorLagRuntimeContext {
        val rows = db.telemetryDao().latestBySourceAndKeySinceForKeys(
            since = nowTs - SENSOR_LAG_RUNTIME_CONTEXT_LOOKBACK_MS,
            keys = SENSOR_LAG_RUNTIME_CONTEXT_KEYS
        )
        if (rows.isEmpty()) return SensorLagRuntimeContext()
        val ageHoursRow = rows
            .asSequence()
            .filter { it.key == "sensor_age_hours" && it.valueDouble?.isFinite() == true }
            .maxByOrNull { it.timestamp }
        val ageDaysRow = rows
            .asSequence()
            .filter { it.key == "sensor_age_days" && it.valueDouble?.isFinite() == true }
            .maxByOrNull { it.timestamp }
        val sensorAgeTs = listOfNotNull(ageHoursRow?.timestamp, ageDaysRow?.timestamp).maxOrNull()
        val sensorAgeHours = when {
            ageHoursRow != null && ageDaysRow != null -> {
                if (ageHoursRow.timestamp >= ageDaysRow.timestamp) {
                    ageHoursRow.valueDouble
                } else {
                    ageDaysRow.valueDouble?.times(24.0)
                }
            }
            ageHoursRow != null -> ageHoursRow.valueDouble
            ageDaysRow != null -> ageDaysRow.valueDouble?.times(24.0)
            else -> {
                rows.asSequence()
                    .filter {
                        it.key == "raw_com_eveningoutpost_dexdrip_extras_sensorstartedat" &&
                            it.valueDouble?.isFinite() == true
                    }
                    .maxByOrNull { it.timestamp }
                    ?.let { startedAtRow ->
                        val startedAtMs = normalizeSensorStartedAtMillis(startedAtRow.valueDouble)
                        startedAtMs
                            ?.takeIf { ts -> ts in 1L..nowTs }
                            ?.let { ts -> ((nowTs - ts).coerceAtLeast(0L)) / 3_600_000.0 }
                    }
            }
        }
        val ageSourceRaw = rows
            .asSequence()
            .filter { it.key == "sensor_age_source_raw" }
            .maxByOrNull { it.timestamp }
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: rows.asSequence()
                .filter { it.key == "raw_com_eveningoutpost_dexdrip_extras_sensorstartedat" }
                .maxByOrNull { it.timestamp }
                ?.key
        val sageDays = rows
            .asSequence()
            .filter { it.key == "sage_days" && it.valueDouble?.isFinite() == true }
            .maxByOrNull { it.timestamp }
            ?.valueDouble
        val cageDays = rows
            .asSequence()
            .filter { it.key == "cage_days" && it.valueDouble?.isFinite() == true }
            .maxByOrNull { it.timestamp }
            ?.valueDouble
        val replayBuckets = rows
            .asSequence()
            .filter { it.key == "daily_report_sensor_lag_bucket_json" }
            .maxByOrNull { it.timestamp }
            ?.valueText
            .let(::parseSensorLagReplayBucketStats)
        return SensorLagRuntimeContext(
            sensorAgeHours = sensorAgeHours,
            sensorAgeTs = sensorAgeTs ?: rows
                .asSequence()
                .filter { it.key == "raw_com_eveningoutpost_dexdrip_extras_sensorstartedat" }
                .maxByOrNull { it.timestamp }
                ?.timestamp,
            sensorAgeSourceRaw = ageSourceRaw,
            sageDays = sageDays,
            cageDays = cageDays,
            replayBucketStats = replayBuckets
        )
    }

    private fun parseSensorLagReplayBucketStats(
        raw: String?
    ): List<SensorLagReplayBucketStats> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", -1)
                    val bucket = item.optString("bucket", "").trim()
                    val sampleCount = item.optInt("sampleCount", 0)
                    val rawMae = item.optDouble("rawMae", Double.NaN)
                    val lagMae = item.optDouble("lagMae", Double.NaN)
                    val maeImprovement = item.optDouble("maeImprovementMmol", Double.NaN)
                    val rawBias = item.optDouble("rawBias", Double.NaN)
                    val lagBias = item.optDouble("lagBias", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        bucket.isBlank() ||
                        sampleCount <= 0 ||
                        !rawMae.isFinite() ||
                        !lagMae.isFinite() ||
                        !maeImprovement.isFinite() ||
                        !rawBias.isFinite() ||
                        !lagBias.isFinite()
                    ) {
                        continue
                    }
                    add(
                        SensorLagReplayBucketStats(
                            horizonMinutes = horizonMinutes,
                            bucket = bucket,
                            sampleCount = sampleCount,
                            rawMae = rawMae,
                            lagMae = lagMae,
                            maeImprovementMmol = maeImprovement,
                            rawBias = rawBias,
                            lagBias = lagBias
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun persistRealtimeIsfCrTelemetry(
        nowTs: Long,
        latestTelemetry: Map<String, Double?>
    ) {
        val source = "copilot_runtime_isfcr"
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

        addNumeric("isfcr_snapshot_age_ms", latestTelemetry["isfcr_snapshot_age_ms"], "ms")
        addNumeric("isfcr_refresh_in_flight", latestTelemetry["isfcr_refresh_in_flight"])
        addNumeric("isfcr_refresh_last_duration_ms", latestTelemetry["isfcr_refresh_last_duration_ms"], "ms")
        addNumeric("isfcr_snapshot_served_mode", latestTelemetry["isfcr_snapshot_served_mode"])
        addNumeric("isf_realtime_value", latestTelemetry["isf_realtime_value"], "mmol/L/U")
        addNumeric("cr_realtime_value", latestTelemetry["cr_realtime_value"], "g/U")
        addNumeric("isf_realtime_confidence", latestTelemetry["isf_realtime_confidence"])
        addNumeric("isf_realtime_quality_score", latestTelemetry["isf_realtime_quality_score"])
        addNumeric("isf_realtime_applied", latestTelemetry["isf_realtime_applied"])
        addNumeric("isf_realtime_override_blend_weight", latestTelemetry["isf_realtime_override_blend_weight"])
        addNumeric("isf_realtime_mode", latestTelemetry["isf_realtime_mode"])
        addNumeric("isf_runtime_source_preference", latestTelemetry["isf_runtime_source_preference"])
        addNumeric("isf_runtime_source_resolved", latestTelemetry["isf_runtime_source_resolved"])
        addNumeric("isf_runtime_selected_value", latestTelemetry["isf_runtime_selected_value"], "mmol/L/U")
        addNumeric("isf_aaps_raw_value", latestTelemetry["isf_aaps_raw_value"], "mmol/L/U")
        addNumeric("isf_aaps_raw_sample_ts", latestTelemetry["isf_aaps_raw_sample_ts"], "ms")
        addNumeric("isf_runtime_aaps_sample_ts", latestTelemetry["isf_runtime_aaps_sample_ts"], "ms")
        addNumeric("isf_runtime_fallback_active", latestTelemetry["isf_runtime_fallback_active"])
        addNumeric("cr_runtime_source_preference", latestTelemetry["cr_runtime_source_preference"])
        addNumeric("cr_runtime_source_resolved", latestTelemetry["cr_runtime_source_resolved"])
        addNumeric("cr_runtime_selected_value", latestTelemetry["cr_runtime_selected_value"], "g/U")
        addNumeric("cr_aaps_raw_value", latestTelemetry["cr_aaps_raw_value"], "g/U")
        addNumeric("cr_aaps_raw_sample_ts", latestTelemetry["cr_aaps_raw_sample_ts"], "ms")
        addNumeric("cr_runtime_aaps_sample_ts", latestTelemetry["cr_runtime_aaps_sample_ts"], "ms")
        addNumeric("cr_runtime_fallback_active", latestTelemetry["cr_runtime_fallback_active"])
        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    private suspend fun persistSensorLagTelemetry(
        nowTs: Long,
        estimate: SensorLagEstimate,
        latestGlucoseInput: GlucoseInputMetadata?,
        controlForecasts: List<Forecast>,
        candidateForecasts: List<Forecast>,
        shadowRuleChanged: Boolean?,
        shadowTargetDeltaMmol: Double?
    ) {
        val rows = buildSensorLagTelemetryRowsStatic(
            nowTs = nowTs,
            estimate = estimate,
            latestGlucoseInput = latestGlucoseInput,
            controlForecasts = controlForecasts,
            candidateForecasts = candidateForecasts,
            shadowRuleChanged = shadowRuleChanged,
            shadowTargetDeltaMmol = shadowTargetDeltaMmol
        )
        db.telemetryDao().upsertAll(rows)
    }

    private fun hasSensorLagShadowRuleChanged(
        baseline: List<RuleDecision>,
        candidate: List<RuleDecision>
    ): Boolean = hasSensorLagShadowRuleChangedStatic(baseline = baseline, candidate = candidate)

    private fun resolveSensorLagShadowTargetDeltaMmol(
        baseline: List<RuleDecision>,
        candidate: List<RuleDecision>
    ): Double? = resolveSensorLagShadowTargetDeltaMmolStatic(baseline = baseline, candidate = candidate)

    private fun buildSensorLagShadowRuleEngine(): RuleEngine {
        return RuleEngine(
            rules = listOf(
                AdaptiveTargetControllerRule(),
                PostHypoReboundGuardRule(),
                PatternAdaptiveTargetRule(),
                SegmentProfileGuardRule()
            ),
            safetyPolicy = SafetyPolicy()
        )
    }

    private suspend fun auditCycleCheckpoint(
        stage: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        auditLogger.infoThrottled(
            throttleKey = "automation_cycle_checkpoint:$stage",
            intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
            message = "automation_cycle_checkpoint",
            metadata = mapOf("stage" to stage) + metadata
        )
    }

    suspend fun runDbHousekeepingNowForDebug() {
        val settings = settingsStore.settings.first()
        runDbHousekeepingIfDue(
            nowTs = System.currentTimeMillis(),
            settings = settings,
            force = true
        )
    }

    private suspend fun runDbHousekeepingIfDue(nowTs: Long, settings: AppSettings, force: Boolean = false) {
        val lastRunTs = db.syncStateDao().bySource(SOURCE_DB_HOUSEKEEPING_CURSOR)?.lastSyncedTimestamp ?: 0L
        if (!force && lastRunTs > 0L && (nowTs - lastRunTs) < DB_HOUSEKEEPING_INTERVAL_MS) {
            return
        }

        val historyRetentionMs = resolveHistoryRetentionMs(settings)
        val reportAndProfileRetentionMs = resolveReportAndProfileRetentionMs(settings)
        val forecastOlderThan = nowTs - historyRetentionMs
        val historyOlderThan = nowTs - historyRetentionMs
        val telemetryGeneralOlderThan = nowTs - TELEMETRY_GENERAL_RETENTION_MS
        val reportOlderThan = nowTs - reportAndProfileRetentionMs
        val physicalActivityOlderThan = nowTs - TELEMETRY_PHYSICAL_ACTIVITY_RETENTION_MS
        val telemetryNoisy7dOlderThan = nowTs - TELEMETRY_NOISY_RETENTION_7D_MS
        val telemetryNoisy14dOlderThan = nowTs - TELEMETRY_NOISY_RETENTION_14D_MS
        val auditNoisyOlderThan = nowTs - AUDIT_LOG_NOISY_RETENTION_MS
        val integratedRuntimeOlderThan = nowTs - INTEGRATED_RUNTIME_RETENTION_MS

        var glucoseDedupRemoved = 0
        GLUCOSE_HOUSEKEEPING_SOURCES.forEach { source ->
            glucoseDedupRemoved += db.glucoseDao().deleteDuplicateBySourceAndTimestamp(source)
        }
        val therapyDedupRemoved = db.therapyDao().deleteDuplicateByTimestampTypePayload()
        val telemetryDedupSince = nowTs - TELEMETRY_DEDUP_LOOKBACK_MS
        val telemetryDedupRemoved = db.telemetryDao().deleteDuplicateRowsSince(telemetryDedupSince)
        val glucoseTrimmed = db.glucoseDao().deleteOlderThan(historyOlderThan)
        val therapyTrimmed = db.therapyDao().deleteOlderThan(historyOlderThan)
        val forecastTrimmed = db.forecastDao().deleteOlderThan(forecastOlderThan)
        val ruleExecutionTrimmed = db.ruleExecutionDao().deleteOlderThan(historyOlderThan)
        val actionCommandTrimmed = db.actionCommandDao().deleteOlderThan(historyOlderThan)
        val rawTelemetryTrimmed = db.telemetryDao().deleteOlderThanByKeyPattern(
            olderThan = nowTs - TELEMETRY_RAW_RETENTION_MS,
            keyPattern = "raw_%"
        )
        val noisyTelemetry7dTrimmed = TELEMETRY_NOISY_7D_PATTERNS.sumOf { pattern ->
            db.telemetryDao().deleteOlderThanByKeyPattern(
                olderThan = telemetryNoisy7dOlderThan,
                keyPattern = pattern
            )
        } + db.telemetryDao().deleteOlderThanByKeys(
            olderThan = telemetryNoisy7dOlderThan,
            keys = TELEMETRY_NOISY_7D_KEYS
        )
        val noisyTelemetry14dTrimmed = TELEMETRY_NOISY_14D_PATTERNS.sumOf { pattern ->
            db.telemetryDao().deleteOlderThanByKeyPattern(
                olderThan = telemetryNoisy14dOlderThan,
                keyPattern = pattern
            )
        } + db.telemetryDao().deleteOlderThanByKeys(
            olderThan = telemetryNoisy14dOlderThan,
            keys = TELEMETRY_NOISY_14D_KEYS
        )
        val telemetryTrimmed = trimTelemetryWithRetention(
            generalOlderThan = telemetryGeneralOlderThan,
            reportOlderThan = reportOlderThan,
            physicalActivityOlderThan = physicalActivityOlderThan,
            maxBatches = if (force) {
                TELEMETRY_TRIM_DEBUG_MAX_BATCHES_PER_RUN
            } else {
                TELEMETRY_TRIM_MAX_BATCHES_PER_RUN
            }
        )
        val noisyAuditTrimmed = db.auditLogDao().deleteOlderThanInfoMessages(
            olderThan = auditNoisyOlderThan,
            messages = AUDIT_NOISY_INFO_MESSAGES
        )
        val auditTrimmed = db.auditLogDao().deleteOlderThan(nowTs - AUDIT_LOG_RETENTION_MS)
        val alertEventsTrimmed = db.alertEventDao().deleteResolvedOlderThan(integratedRuntimeOlderThan)
        val contextSyncTrimmed = db.contextEventSyncDao().deleteCompletedOlderThan(integratedRuntimeOlderThan)
        val runtimeSnapshotsTrimmed = db.sensitivityRuntimeSnapshotDao().deleteOlderThan(integratedRuntimeOlderThan)
        val totalTrimmed = glucoseTrimmed +
            therapyTrimmed +
            forecastTrimmed +
            ruleExecutionTrimmed +
            actionCommandTrimmed +
            rawTelemetryTrimmed +
            noisyTelemetry7dTrimmed +
            noisyTelemetry14dTrimmed +
            telemetryTrimmed +
            noisyAuditTrimmed +
            auditTrimmed +
            alertEventsTrimmed +
            contextSyncTrimmed +
            runtimeSnapshotsTrimmed

        db.syncStateDao().upsert(
            SyncStateEntity(
                source = SOURCE_DB_HOUSEKEEPING_CURSOR,
                lastSyncedTimestamp = nowTs
            )
        )

        auditLogger.info(
            "db_housekeeping_completed",
            mapOf(
                "intervalHours" to (DB_HOUSEKEEPING_INTERVAL_MS / 3_600_000L),
                "glucoseDedupRemoved" to glucoseDedupRemoved,
                "therapyDedupRemoved" to therapyDedupRemoved,
                "telemetryDedupRemoved" to telemetryDedupRemoved,
                "telemetryDedupLookbackHours" to (TELEMETRY_DEDUP_LOOKBACK_MS / 3_600_000L),
                "historyRetentionDays" to (historyRetentionMs / DAY_MS),
                "telemetryGeneralRetentionDays" to (TELEMETRY_GENERAL_RETENTION_MS / DAY_MS),
                "telemetryPhysicalActivityRetentionDays" to
                    (TELEMETRY_PHYSICAL_ACTIVITY_RETENTION_MS / DAY_MS),
                "reportRetentionDays" to (reportAndProfileRetentionMs / DAY_MS),
                "glucoseTrimmed" to glucoseTrimmed,
                "therapyTrimmed" to therapyTrimmed,
                "forecastTrimmed" to forecastTrimmed,
                "ruleExecutionTrimmed" to ruleExecutionTrimmed,
                "actionCommandTrimmed" to actionCommandTrimmed,
                "rawTelemetryTrimmed" to rawTelemetryTrimmed,
                "noisyTelemetry7dTrimmed" to noisyTelemetry7dTrimmed,
                "noisyTelemetry14dTrimmed" to noisyTelemetry14dTrimmed,
                "telemetryTrimmed" to telemetryTrimmed,
                "noisyAuditTrimmed" to noisyAuditTrimmed,
                "auditTrimmed" to auditTrimmed,
                "alertEventsTrimmed" to alertEventsTrimmed,
                "contextSyncTrimmed" to contextSyncTrimmed,
                "runtimeSnapshotsTrimmed" to runtimeSnapshotsTrimmed,
                "totalTrimmed" to totalTrimmed
            )
        )
        auditLogger.info(
            "audit_log_rotation_completed",
            mapOf(
                "intervalHours" to (DB_HOUSEKEEPING_INTERVAL_MS / 3_600_000L),
                "retentionHours" to (AUDIT_LOG_RETENTION_MS / 3_600_000L),
                "deletedRows" to (noisyAuditTrimmed + auditTrimmed),
                "noisyDeletedRows" to noisyAuditTrimmed,
                "generalDeletedRows" to auditTrimmed
            )
        )
        runDbCompactionIfDue(nowTs = nowTs, totalTrimmed = totalTrimmed)
    }

    private suspend fun trimTelemetryWithRetention(
        generalOlderThan: Long,
        reportOlderThan: Long,
        physicalActivityOlderThan: Long,
        maxBatches: Int
    ): Int {
        var totalDeleted = 0
        var batches = 0
        while (batches < maxBatches) {
            val deleted = db.telemetryDao().deleteOlderThanWithReportProfileAndPhysicalActivityRetentionLimit(
                generalOlderThan = generalOlderThan,
                reportOlderThan = reportOlderThan,
                physicalActivityOlderThan = physicalActivityOlderThan,
                physicalActivityKeys = PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS.sorted(),
                physicalActivitySources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.sorted(),
                physicalActivityQualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.sorted(),
                limit = TELEMETRY_TRIM_BATCH_LIMIT
            )
            if (deleted <= 0) break
            totalDeleted += deleted
            batches += 1
            if (batches % TELEMETRY_TRIM_CHECKPOINT_EVERY_BATCHES == 0) {
                val checkpoint = checkpointWal("PASSIVE")
                auditLogger.infoThrottled(
                    throttleKey = "db_housekeeping_telemetry_trim_progress",
                    intervalMs = 60_000L,
                    message = "db_housekeeping_telemetry_trim_progress",
                    metadata = mapOf(
                        "deletedRows" to totalDeleted,
                        "batches" to batches,
                        "batchLimit" to TELEMETRY_TRIM_BATCH_LIMIT,
                        "checkpoint" to checkpoint
                    )
                )
            }
            delay(TELEMETRY_TRIM_BATCH_PAUSE_MS)
        }
        if (batches >= maxBatches) {
            auditLogger.warn(
                "db_housekeeping_telemetry_trim_limited",
                mapOf(
                    "deletedRows" to totalDeleted,
                    "batches" to batches,
                    "maxBatches" to maxBatches,
                    "batchLimit" to TELEMETRY_TRIM_BATCH_LIMIT
                )
            )
        }
        return totalDeleted
    }

    private suspend fun runDbCompactionIfDue(nowTs: Long, totalTrimmed: Int) {
        if (totalTrimmed < DB_COMPACTION_MIN_TRIMMED_ROWS) return
        val lastRunTs = db.syncStateDao().bySource(SOURCE_DB_COMPACTION_CURSOR)?.lastSyncedTimestamp ?: 0L
        if (lastRunTs > 0L && (nowTs - lastRunTs) < DB_COMPACTION_INTERVAL_MS) {
            return
        }
        runCatching {
            val checkpoint = checkpointWal("TRUNCATE")
            db.syncStateDao().upsert(
                SyncStateEntity(
                    source = SOURCE_DB_COMPACTION_CURSOR,
                    lastSyncedTimestamp = nowTs
                )
            )
            auditLogger.info(
                "db_compaction_completed",
                mapOf(
                    "trimmedRows" to totalTrimmed,
                    "checkpoint" to checkpoint,
                    "vacuumSkipped" to true,
                    "vacuumSkipReason" to "avoid_full_db_rewrite_on_device",
                    "intervalHours" to (DB_COMPACTION_INTERVAL_MS / 3_600_000L)
                )
            )
        }.onFailure { error ->
            auditLogger.warn(
                "db_compaction_failed",
                mapOf(
                    "trimmedRows" to totalTrimmed,
                    "message" to (error.message ?: error::class.java.simpleName)
                )
            )
        }
    }

    private fun checkpointWal(mode: String): String {
        val safeMode = when (mode.uppercase(Locale.US)) {
            "PASSIVE" -> "PASSIVE"
            "TRUNCATE" -> "TRUNCATE"
            else -> "PASSIVE"
        }
        return db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint($safeMode)")
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    "busy=${cursor.getInt(0)},log=${cursor.getInt(1)},checkpointed=${cursor.getInt(2)}"
                } else {
                    "unknown"
                }
            }
    }

    private fun resolveHistoryRetentionMs(settings: AppSettings): Long {
        return settings.analyticsLookbackDays.coerceIn(MIN_STORAGE_RETENTION_DAYS, MAX_STORAGE_RETENTION_DAYS) * DAY_MS
    }

    private fun resolveReportAndProfileRetentionMs(settings: AppSettings): Long {
        return maxOf(resolveHistoryRetentionMs(settings), REPORT_AND_PROFILE_MIN_RETENTION_DAYS * DAY_MS)
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
            key.startsWith("uam_runtime_") ||
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

    private fun normalizeTelemetryKey(value: String): String = TherapyPayloadLookup.normalizeKey(value)

    private fun selectStrictAapsIob(
        rows: List<TelemetrySampleEntity>
    ): TelemetrySampleEntity? = selectStrictAapsIobStatic(rows)

    private fun selectStrictAapsCob(
        rows: List<TelemetrySampleEntity>
    ): TelemetrySampleEntity? = selectStrictAapsCobStatic(rows)

    private fun selectStrictAapsIsf(
        rows: List<TelemetrySampleEntity>
    ): TelemetrySampleEntity? = selectStrictAapsIsfStatic(rows)

    private fun selectStrictAapsCr(
        rows: List<TelemetrySampleEntity>
    ): TelemetrySampleEntity? = selectStrictAapsCrStatic(rows)

    private fun sensitivityRuntimeApplicationFromSnapshot(
        snapshot: SensitivityRuntimeSnapshot,
        @Suppress("UNUSED_PARAMETER") settings: AppSettings
    ): SensitivityRuntimeApplication = sensitivityRuntimeApplicationFromSnapshotStatic(snapshot)

    private suspend fun requireCycleSensitivityRuntime(
        context: SensitivityRuntimeConsumerContext,
        expectedConsumer: SensitivityRuntimeConsumer,
        cycleSnapshot: SensitivityRuntimeSnapshot
    ) {
        validateSensitivityRuntimeDispatchStatic(
            context = context,
            expectedConsumer = expectedConsumer,
            currentSettingsRevision = settingsStore.settings.first().sensitivitySettingsRevision,
            cycleSnapshot = cycleSnapshot
        )
    }

    private fun configurePredictionEngine(
        settings: AppSettings,
        sensitivityRuntimeApplication: SensitivityRuntimeApplication? = null,
        runtimeTelemetry: Map<String, Double?> = emptyMap(),
        uamRuntimeQuality: UamRuntimeQualityAssessment? = null,
        engine: HybridPredictionEngine? = predictionEngine as? HybridPredictionEngine
    ) {
        engine ?: return
        val profileId = InsulinActionProfileId.fromRaw(settings.insulinProfileId)
        val profile = InsulinActionProfiles.profile(profileId)
        engine.setInsulinProfile(profileId)
        engine.setCarbSafetyLimits(
            maxAgeMinutes = settings.carbAbsorptionMaxAgeMinutes,
            maxGrams = settings.carbComputationMaxGrams
        )
        val diaHours = runtimeTelemetry["dia_effective_hours"]
            ?.takeIf { it.isFinite() }
            ?: runtimeTelemetry["dia_profile_hours"]?.takeIf { it.isFinite() }
            ?: profile.defaultDurationHours
        if (abs(diaHours - profile.defaultDurationHours) <= 1e-6) {
            engine.setInsulinDurationHours(null)
        } else {
            engine.setInsulinDurationHours(diaHours.coerceIn(0.5, 24.0))
        }
        engine.setSensitivityOverrides(
            isf = sensitivityRuntimeApplication?.isf?.override,
            cr = sensitivityRuntimeApplication?.cr?.override
        )
        engine.setUamRuntimeQualityContext(
            sensorTrust = uamRuntimeQuality?.sensorTrust ?: 0.0,
            therapyCoverage = uamRuntimeQuality?.therapyCoverage ?: 0.0,
            announcedCarbCoverage = uamRuntimeQuality?.announcedCarbCoverage ?: 0.0,
            sensorBlocked = uamRuntimeQuality?.sensorBlocked ?: true
        )
    }

    private fun resolveRuntimeCobIobInputs(
        cycleTimestamp: Long,
        causalReferenceTimestamp: Long,
        glucose: List<GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        telemetry: Map<String, Double?>,
        settings: AppSettings,
        effectiveDiaHours: Double
    ): RuntimeCobIobInputs {
        val carbMax = io.aaps.copilot.domain.nutrition.MealCarbLimits.effectiveCobMaximum(
            therapy, causalReferenceTimestamp, settings.carbAbsorptionMaxAgeMinutes,
            settings.carbComputationMaxGrams
        )
        val telemetryCobRaw = telemetry["cob_grams"]?.takeIf { it.isFinite() }?.coerceIn(0.0, carbMax)
        val syntheticUamCob = estimateSyntheticUamExportCob(
            nowTs = cycleTimestamp,
            glucose = glucose,
            therapy = therapy,
            settings = settings
        )
        val telemetryCob = telemetryCobRaw?.let {
            adjustExternalCobForSyntheticUamStatic(
                externalCobGrams = it,
                syntheticUamCobGrams = syntheticUamCob,
                carbMaxGrams = carbMax
            )
        }
        val hasRecentCarbEvents = hasRecentCarbEvents(
            nowTs = cycleTimestamp,
            therapy = therapy,
            cutoffMinutes = settings.carbAbsorptionMaxAgeMinutes.coerceIn(60, 180)
        )
        val localEstimate = estimateLocalCobIob(
            nowTs = causalReferenceTimestamp,
            glucose = glucose,
            therapy = therapy,
            settings = settings,
            effectiveDiaHours = effectiveDiaHours
        ).also { lastLocalCobIobEstimate = it }
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
        val insulinResolution = InsulinRuntimeSnapshotResolver.resolveTelemetry(
            nowTimestamp = cycleTimestamp,
            telemetry = telemetry,
            localEstimate = localEstimate.insulinEvidenceTimestamp?.let { evidenceTimestamp ->
                QualifiedLocalInsulinEstimate(
                    value = localIob,
                    timestamp = causalReferenceTimestamp,
                    evidenceTimestamp = evidenceTimestamp,
                    therapyCoverage = localEstimate.insulinTherapyCoverage,
                    confidence = localEstimate.insulinConfidence
                )
            }
        )
        val insulinSnapshot = insulinResolution.snapshot
        val modeledAtSnapshotUnits = insulinSnapshot
            ?.takeIf { it.timestamp != causalReferenceTimestamp && localEstimate.explicitInsulinEvents > 0 }
            ?.let { snapshot ->
                (predictionEngine as? HybridPredictionEngine)?.let { engine ->
                    modelSameCycleActiveInsulinStatic(
                        engine = engine,
                        profileIdRaw = settings.insulinProfileId,
                        resolvedEffectiveDiaHours = effectiveDiaHours,
                        baseOnsetMinutes = localEstimate.baseOnsetMinutes,
                        realOnsetMinutes = localEstimate.realOnsetMinutes,
                        therapyEvents = therapy,
                        nowTs = snapshot.timestamp
                    ).activeUnits
                }
            }
        val insulinCycleContext = buildInsulinCycleContextStatic(
            cycleTimestamp = cycleTimestamp,
            causalReferenceTimestamp = causalReferenceTimestamp,
            insulinSnapshot = insulinSnapshot,
            modeledActiveInsulinUnits = localIob,
            modeledActiveInsulinAtSnapshotUnits = modeledAtSnapshotUnits,
            therapyModelAvailable = localEstimate.explicitInsulinEvents > 0,
            freshnessMs = settings.staleDataMaxMinutes.coerceIn(5, 60) * 60_000L
        )
        val realIobUnits = insulinSnapshot?.netIobUnits ?: 0.0
        val mergedIob = insulinSnapshot?.effectivePositiveIobUnits ?: 0.0
        val usedLocalFallback = (telemetryCob == null && localCob > 0.0) ||
            insulinSnapshot?.source == io.aaps.copilot.domain.predict.InsulinRuntimeSource.LOCAL_ESTIMATE
        val mergedWithTelemetry = telemetryCob != null && localCob > 0.0
        return RuntimeCobIobInputs(
            cobGrams = mergedCob,
            iobUnits = mergedIob,
            realIobUnits = realIobUnits,
            localCobGrams = localCob,
            localIobUnits = localIob,
            externalCobRawGrams = telemetryCobRaw,
            externalCobAdjustedGrams = telemetryCob,
            syntheticUamCobGrams = syntheticUamCob,
            realOnsetMinutes = localEstimate.realOnsetMinutes,
            baseOnsetMinutes = localEstimate.baseOnsetMinutes,
            onsetSampleCount = localEstimate.onsetSampleCount,
            usedLocalFallback = usedLocalFallback,
            mergedWithTelemetry = mergedWithTelemetry,
            insulinCycleContext = insulinCycleContext
        )
    }

    private fun resolveRuntimeDiaInputs(
        settings: AppSettings,
        realProfile: RealInsulinProfileEstimate?,
        rawExternalDiaHours: Double?
    ): RuntimeDiaInputs {
        val profile = InsulinActionProfiles.profile(InsulinActionProfileId.fromRaw(settings.insulinProfileId))
        val resolution = resolveEffectiveDiaHoursStatic(
            profileDurationHours = profile.defaultDurationHours,
            profileBaseOnsetMinutes = profileOnsetMinutes(profile),
            realProfileOnsetMinutes = realProfile?.onsetMinutes,
            realProfileShapeScale = realProfile?.shapeScale,
            realProfileConfidence = realProfile?.confidence,
            realProfileStatus = realProfile?.status,
            realProfileSourceId = realProfile?.sourceProfileId,
            selectedProfileId = profile.id.name
        )
        return RuntimeDiaInputs(
            profileHours = resolution.profileHours,
            effectiveHours = resolution.effectiveHours,
            rawEstimatedHours = resolution.rawEstimatedHours,
            rawExternalHours = rawExternalDiaHours?.coerceIn(0.5, 24.0),
            source = resolution.source
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
        circadianPrior: CircadianForecastPrior?,
        circadianBiasApplied: Boolean,
        calculatedUam: CalculatedUamSnapshot,
        inferredUam: UamInferenceCycleResult?,
        insulinTherapyAvailable: Boolean
    ): Map<String, Any> {
        fun hasValue(key: String): Boolean = latestTelemetry[key]?.isFinite() == true
        fun boolFlag(value: Boolean): Double = if (value) 1.0 else 0.0

        val isfConfidence = latestTelemetry["isf_realtime_confidence"] ?: 0.0
        val isfApplied = runtimeGate.applyToRuntime && isfConfidence > 0.0
        val hasIsfRealtime = realtimeIsfCrSnapshot != null
        val hasDia = hasValue("dia_effective_hours") || hasValue("dia_profile_hours") || hasValue("dia_hours")
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
        val hasCircadian = circadianPrior != null
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
            hasCircadian,
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
            "patternPriorAvailable" to boolFlag(hasCircadian),
            "patternPriorApplied" to boolFlag(circadianBiasApplied),
            "patternPriorConfidence" to (circadianPrior?.confidence ?: 0.0),
            "patternPriorQualityScore" to (circadianPrior?.qualityScore ?: 0.0),
            "patternPriorAcuteAttenuation" to (circadianPrior?.acuteAttenuation ?: 0.0),
            "patternPriorStaleBlocked" to boolFlag(circadianPrior?.staleBlocked == true),
            "patternPriorSegmentSource" to (circadianPrior?.segmentSource?.name ?: "NONE"),
            "localCobIobFallbackUsed" to boolFlag(runtimeCobIob.usedLocalFallback),
            "localCobIobMergedWithTelemetry" to boolFlag(runtimeCobIob.mergedWithTelemetry),
            "insulinTherapyAvailable" to boolFlag(insulinTherapyAvailable),
            "forecastCalibrationApplied" to boolFlag(calibrationApplied),
            "forecastContextBiasApplied" to boolFlag(contextBiasApplied),
            "forecastCobIobBiasApplied" to boolFlag(cobIobBiasApplied),
            "forecastCircadianBiasApplied" to boolFlag(circadianBiasApplied)
        )
    }

    private fun estimateLocalCobIob(
        nowTs: Long,
        glucose: List<GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        settings: AppSettings,
        effectiveDiaHours: Double
    ): LocalCobIobEstimate {
        val carbCutoffMinutes = settings.carbAbsorptionMaxAgeMinutes.coerceIn(60, 180).toDouble()
        val carbMaxGrams = io.aaps.copilot.domain.nutrition.MealCarbLimits.effectiveCobMaximum(
            therapy, nowTs, settings.carbAbsorptionMaxAgeMinutes, settings.carbComputationMaxGrams
        )
        val profile = InsulinActionProfiles.profile(InsulinActionProfileId.fromRaw(settings.insulinProfileId))
        val baseOnsetMinutes = profileOnsetMinutes(profile)
        val recentEvents = therapy.asSequence()
            .filter { event -> event.ts in 0..nowTs && nowTs - event.ts <= LOCAL_COB_IOB_LOOKBACK_MS }
            .toList()
        val engine = (predictionEngine as? HybridPredictionEngine)?.also { prediction ->
            prediction.setInsulinProfile(InsulinActionProfileId.fromRaw(settings.insulinProfileId))
            prediction.setInsulinDurationHours(effectiveDiaHours)
            prediction.setInsulinOnsetMinutes(
                baseOnsetMinutes = baseOnsetMinutes,
                realOnsetMinutes = baseOnsetMinutes
            )
        }
        if (recentEvents.isEmpty()) {
            return fallbackLocalCobIobEstimate(settings)
        }
        val eligibleInsulinEvents = engine?.let { prediction ->
            recentEvents.filter(prediction::isModeledInsulinEvent)
        }.orEmpty()
        val hasEligibleInsulin = eligibleInsulinEvents.any { event ->
            (extractInsulinUnits(event) ?: 0.0) >= 0.5
        }
        val realOnsetEstimate = if (hasEligibleInsulin) {
            estimateRealInsulinOnsetMinutes(
                nowTs = nowTs,
                glucose = glucose,
                therapy = eligibleInsulinEvents
            )
        } else {
            null
        }
        val realOnsetMinutes = (realOnsetEstimate?.first ?: baseOnsetMinutes)
            .coerceIn(INSULIN_ONSET_MIN_MINUTES, INSULIN_ONSET_MAX_MINUTES)
        val onsetSampleCount = realOnsetEstimate?.second ?: 0
        var cob = 0.0
        val modeledInsulin = engine?.let { prediction ->
            modelSameCycleActiveInsulinStatic(
                engine = prediction,
                profileIdRaw = settings.insulinProfileId,
                resolvedEffectiveDiaHours = effectiveDiaHours,
                baseOnsetMinutes = baseOnsetMinutes,
                realOnsetMinutes = realOnsetMinutes,
                therapyEvents = recentEvents,
                nowTs = nowTs
            )
        }
            ?: HybridPredictionEngine.ModeledActiveInsulinEvidence()
        val iob = modeledInsulin.activeUnits
        val explicitInsulinEvents = modeledInsulin.eventCount
        val insulinEvidenceTimestamp = modeledInsulin.latestQualifiedEvidenceTimestamp
        val effectiveDiaMs = ((engine?.currentInsulinDurationHoursForTest() ?: 0.0) * 60.0 * 60_000.0)
            .toLong()
        // Event presence is useful diagnostic evidence, but it does not prove continuous
        // therapy import/revision coverage. LOCAL_ESTIMATE therefore remains non-safety data.
        val insulinTherapyCoverage = 0.0
        val insulinConfidence = if (
            insulinEvidenceTimestamp != null &&
            effectiveDiaMs > 0L &&
            nowTs - insulinEvidenceTimestamp <= effectiveDiaMs
        ) {
            0.5
        } else {
            0.0
        }
        recentEvents.forEach { event ->
            val ageMin = ((nowTs - event.ts).coerceAtLeast(0L)) / 60_000.0
            val carbsGrams = io.aaps.copilot.domain.nutrition.MealCarbLimits.announcedGrams(
                event, settings.carbComputationMaxGrams
            )
            if (carbsGrams != null && ageMin <= carbCutoffMinutes) {
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
            insulinEvidenceTimestamp = insulinEvidenceTimestamp,
            insulinTherapyCoverage = insulinTherapyCoverage,
            insulinConfidence = insulinConfidence,
            realOnsetMinutes = realOnsetMinutes,
            baseOnsetMinutes = baseOnsetMinutes,
            onsetSampleCount = onsetSampleCount
        )
    }

    private fun fallbackLocalCobIobEstimate(settings: AppSettings): LocalCobIobEstimate {
        val profile = InsulinActionProfiles.profile(InsulinActionProfileId.fromRaw(settings.insulinProfileId))
        val baseOnsetMinutes = profileOnsetMinutes(profile)
        return LocalCobIobEstimate(
            cobGrams = 0.0,
            iobUnits = 0.0,
            explicitInsulinEvents = 0,
            insulinEvidenceTimestamp = null,
            insulinTherapyCoverage = 0.0,
            insulinConfidence = 0.0,
            realOnsetMinutes = lastLocalCobIobEstimate?.realOnsetMinutes ?: baseOnsetMinutes,
            baseOnsetMinutes = baseOnsetMinutes,
            onsetSampleCount = lastLocalCobIobEstimate?.onsetSampleCount ?: 0
        )
    }

    private fun estimateSyntheticUamExportCob(
        nowTs: Long,
        glucose: List<GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        settings: AppSettings
    ): Double {
        val carbCutoffMinutes = settings.carbAbsorptionMaxAgeMinutes.coerceIn(60, 180).toDouble()
        val carbMaxGrams = settings.carbComputationMaxGrams.coerceIn(20.0, 60.0)
        return therapy.asSequence()
            .filter { isSyntheticUamCarbEvent(it) }
            .mapNotNull { event ->
                val ageMin = ((nowTs - event.ts).coerceAtLeast(0L)) / 60_000.0
                if (ageMin > carbCutoffMinutes) return@mapNotNull null
                val carbsGrams = payloadDouble(event, "grams", "carbs", "enteredCarbs", "mealCarbs")
                    ?.takeIf { it in 0.5..400.0 }
                    ?.coerceAtMost(carbMaxGrams)
                    ?: return@mapNotNull null
                val carbType = CarbAbsorptionProfiles.classifyCarbEvent(
                    event = event,
                    glucose = glucose,
                    nowTs = nowTs
                ).type
                val absorbed = CarbAbsorptionProfiles.cumulative(
                    type = carbType,
                    ageMinutes = ageMin
                ).coerceIn(0.0, 1.0)
                carbsGrams * (1.0 - absorbed)
            }
            .sum()
            .coerceIn(0.0, carbMaxGrams)
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
        val mealExclusion = InsulinOnsetMealExclusion(therapy, INSULIN_ONSET_MEAL_EXCLUSION_MS)
        val candidates = mutableListOf<Double>()
        therapy.asSequence()
            .filter { event ->
                (nowTs - event.ts) in 0..LOCAL_COB_IOB_LOOKBACK_MS &&
                    (extractInsulinUnits(event) ?: 0.0) >= 0.5
            }
            .forEach { bolusEvent ->
                val hasNearbyMeal = mealExclusion.hasNearbyMeal(bolusEvent.ts)
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
        var lo = 0
        var hi = sortedGlucose.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val midTs = sortedGlucose[mid].ts
            when {
                midTs < targetTs -> lo = mid + 1
                midTs > targetTs -> hi = mid - 1
                else -> return sortedGlucose[mid].valueMmol
            }
        }
        val right = sortedGlucose.getOrNull(lo)
        val left = sortedGlucose.getOrNull(lo - 1)
        val rightDiff = right?.let { abs(it.ts - targetTs) } ?: Long.MAX_VALUE
        val leftDiff = left?.let { abs(it.ts - targetTs) } ?: Long.MAX_VALUE
        val best = if (rightDiff < leftDiff) right else left
        return best?.takeIf { abs(it.ts - targetTs) <= toleranceMs }?.valueMmol
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

    private fun causalInsulinEvidenceTimestamps(
        nowTs: Long,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>
    ): List<Long> = therapy.asSequence()
        .filter { event ->
            event.ts <= nowTs && (
                extractInsulinUnits(event)?.let { it > 0.0 } == true ||
                    event.type.equals("meal_bolus", ignoreCase = true) ||
                    event.type.equals("correction_bolus", ignoreCase = true) ||
                    event.type.equals("bolus", ignoreCase = true) ||
                    event.type.equals("insulin", ignoreCase = true)
                )
        }
        .map { it.ts }
        .distinct()
        .sorted()
        .toList()

    private fun resolveUamActiveTelemetry(latestTelemetry: Map<String, Double?>): Boolean {
        return resolveUamActiveTelemetryStatic(latestTelemetry)
    }

    private fun resolveCobIobBiasUamActive(
        latestTelemetry: Map<String, Double?>,
        currentUnifiedUamFlag: Double?
    ): Boolean {
        return resolveCobIobBiasUamActiveStatic(
            latestTelemetry = latestTelemetry,
            currentUnifiedUamFlag = currentUnifiedUamFlag
        )
    }

    private fun payloadDouble(
        event: io.aaps.copilot.domain.model.TherapyEvent,
        vararg keys: String
    ): Double? = TherapyPayloadLookup.number(event.payload, keys)

    private fun extractInsulinUnits(
        event: io.aaps.copilot.domain.model.TherapyEvent
    ): Double? {
        return payloadDouble(event, "units", "bolusUnits", "insulin", "enteredInsulin")
            ?.takeIf { it in 0.02..30.0 }
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

    private fun buildIdempotencyKey(
        ruleId: String,
        nowTs: Long,
        settings: AppSettings,
        action: ActionProposal? = null
    ): String {
        if (ruleId == AdaptiveTargetControllerRule.RULE_ID && action != null) {
            val minuteBucket = nowTs / 60_000L
            val targetBucket = roundToStep(action.targetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL), 0.05)
            val targetToken = String.format(Locale.US, "%.2f", targetBucket)
            val modeToken = action.reason
                .substringAfter("mode=", action.reason)
                .replace(Regex("[^a-zA-Z0-9_:-]+"), "_")
                .take(48)
            return "$ruleId:$minuteBucket:$targetToken:$modeToken"
        }

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

        fun addNullableNumeric(key: String, value: Double?, unit: String? = null) {
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = value,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
        }

        fun addNullableText(key: String, value: String?) {
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = value,
                unit = null,
                quality = "OK"
            )
        }

        addNumeric("cob_effective_grams", runtime.cobGrams, "g")
        runtime.insulinSnapshot?.let { snapshot ->
            addNumeric("iob_effective_units", snapshot.effectivePositiveIobUnits, "U")
            addNumeric("iob_effective_positive_units", snapshot.effectivePositiveIobUnits, "U")
            addNumeric("iob_real_units", snapshot.netIobUnits, "U")
            addNumeric("iob_units", snapshot.effectivePositiveIobUnits, "U")
            addNumeric("iob_net_units", snapshot.netIobUnits, "U")
            addNullableNumeric("iob_bolus_units", snapshot.bolusIobUnits, "U")
            addNullableNumeric("iob_basal_units", snapshot.basalIobUnits, "U")
            addNullableNumeric("insulin_activity", snapshot.insulinActivity, "U/min")
            addNumeric("iob_runtime_timestamp_ms", snapshot.timestamp.toDouble(), "epoch_ms")
            addNumeric("iob_runtime_evidence_timestamp_ms", snapshot.evidenceTimestamp?.toDouble(), "epoch_ms")
            addNumeric("iob_runtime_therapy_coverage", snapshot.therapyCoverage)
            addNumeric("iob_runtime_confidence", snapshot.confidence)
            addNumeric("iob_runtime_source_code", InsulinRuntimeSnapshotResolver.sourceCode(snapshot.source))
            addNumeric("iob_runtime_fallback_active", if (snapshot.fallbackReason == null) 0.0 else 1.0)
            addNullableText("iob_runtime_source", snapshot.source.name)
            addNullableText("iob_runtime_fallback_reason", snapshot.fallbackReason?.take(96))
        }
        if (runtime.insulinSnapshot == null) {
            addNullableNumeric("iob_effective_units", null, "U")
            addNullableNumeric("iob_effective_positive_units", null, "U")
            addNullableNumeric("iob_real_units", null, "U")
            addNullableNumeric("iob_units", null, "U")
            addNullableNumeric("iob_net_units", null, "U")
            addNullableNumeric("iob_bolus_units", null, "U")
            addNullableNumeric("iob_basal_units", null, "U")
            addNullableNumeric("insulin_activity", null, "U/min")
            addNullableNumeric("iob_runtime_timestamp_ms", null, "epoch_ms")
            addNullableNumeric("iob_runtime_evidence_timestamp_ms", null, "epoch_ms")
            addNullableNumeric("iob_runtime_therapy_coverage", null)
            addNullableNumeric("iob_runtime_confidence", null)
            addNullableNumeric("iob_runtime_source_code", null)
            addNullableNumeric("iob_runtime_fallback_active", null)
            addNullableText("iob_runtime_source", "UNAVAILABLE")
            addNullableText("iob_runtime_fallback_reason", null)
        }
        addNumeric("insulin_real_onset_min", runtime.realOnsetMinutes, "min")
        addNumeric("insulin_profile_base_onset_min", runtime.baseOnsetMinutes, "min")
        addNumeric("insulin_real_onset_samples", runtime.onsetSampleCount.toDouble())
        addNumeric("cob_local_fallback_grams", runtime.localCobGrams, "g")
        addNumeric("iob_local_fallback_units", runtime.localIobUnits, "U")
        addNumeric("cob_external_raw_grams", runtime.externalCobRawGrams ?: rawCobGrams, "g")
        addNumeric("cob_external_adjusted_grams", runtime.externalCobAdjustedGrams, "g")
        addNumeric("cob_synthetic_uam_subtracted_grams", runtime.syntheticUamCobGrams, "g")
        addNumeric("iob_external_raw_units", rawIobUnits, "U")
        addNumeric("cob_iob_local_used", if (runtime.usedLocalFallback) 1.0 else 0.0)
        addNumeric("cob_iob_merged", if (runtime.mergedWithTelemetry) 1.0 else 0.0)

        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    private suspend fun persistRuntimeDiaTelemetry(
        nowTs: Long,
        runtimeDia: RuntimeDiaInputs
    ) {
        val source = "copilot_runtime_dia"
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

        addNumeric("dia_profile_hours", runtimeDia.profileHours, "h")
        addNumeric("dia_effective_hours", runtimeDia.effectiveHours, "h")
        addNumeric("dia_real_raw_hours", runtimeDia.rawEstimatedHours, "h")
        addNumeric("dia_external_raw_hours", runtimeDia.rawExternalHours, "h")
        addText("dia_effective_source", runtimeDia.source)

        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    private suspend fun persistGlucoseCalibrationTelemetry(
        nowTs: Long,
        latestResolved: io.aaps.copilot.domain.model.ResolvedGlucosePoint?,
        calibrationModel: io.aaps.copilot.domain.model.GlucoseCalibrationModel?,
        latestCheck: io.aaps.copilot.domain.model.BloodGlucoseCheck?
    ) {
        val source = "copilot_glucose_calibration"
        val rows = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            if (value == null) return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = value,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
        }

        fun addText(key: String, value: String?) {
            val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return
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

        addNumeric("glucose_raw_mmol", latestResolved?.rawMmol, "mmol/L")
        addNumeric("glucose_calibrated_mmol", latestResolved?.calibratedMmol, "mmol/L")
        addNumeric("glucose_calibration_source_ts", latestResolved?.ts?.toDouble(), "epoch_ms")
        addNumeric(
            "glucose_calibration_gain",
            latestResolved?.takeIf { it.calibrationApplied }?.gain ?: calibrationModel?.gain
        )
        addNumeric(
            "glucose_calibration_offset_mmol",
            latestResolved?.takeIf { it.calibrationApplied }?.offsetMmolApplied ?: calibrationModel?.offsetMmol,
            "mmol/L"
        )
        addNumeric("glucose_calibration_confidence", calibrationModel?.confidence)
        addNumeric(
            "glucose_calibration_last_check_age_minutes",
            latestCheck?.let { ((nowTs - it.timestamp).coerceAtLeast(0L)) / 60_000.0 },
            "min"
        )
        addText("glucose_calibration_model_type", calibrationModel?.modelType?.name)
        addText("glucose_calibration_model_id", calibrationModel?.id)
        addText("glucose_calibration_status", calibrationModel?.status?.name ?: "OFF")
        addText("glucose_calibration_sensor_session_key", calibrationModel?.sensorSessionKey)

        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
        if (latestResolved?.calibrationApplied == true && calibrationModel != null) {
            auditLogger.infoThrottled(
                throttleKey = "glucose_calibration_applied",
                intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
                message = "glucose_calibration_applied",
                metadata = mapOf(
                    "modelType" to calibrationModel.modelType.name,
                    "status" to calibrationModel.status.name,
                    "gain" to latestResolved.gain,
                    "offsetMmol" to latestResolved.offsetMmolApplied,
                    "confidence" to calibrationModel.confidence
                )
            )
        }
    }

    private suspend fun persistGlucoseAlertTelemetry(
        nowTs: Long,
        decision: GlucoseAlertDecision,
        persisted: GlucoseAlertRuntimeState,
        delivery: GlucoseAlertDeliveryResult,
        insulinSnapshot: InsulinRuntimeSnapshot?,
        sensitivitySnapshot: SensitivityRuntimeSnapshot
    ) {
        val rows = buildGlucoseAlertTelemetryRowsStatic(
            nowTs = nowTs,
            decision = decision,
            persisted = persisted,
            delivery = delivery,
            insulinSnapshot = insulinSnapshot,
            sensitivitySnapshot = sensitivitySnapshot
        )
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
        val shouldRecomputeProfile = shouldRecomputeRealInsulinProfileStatic(
            existingUpdatedTs = existing?.updatedTs,
            nowTs = nowTs,
            existingAlgoVersion = existing?.algoVersion,
            existingStatus = existing?.status,
            existingSampleCount = existing?.sampleCount ?: 0
        )
        val recomputed = if (shouldRecomputeProfile) {
            computeRealInsulinProfileEstimate(nowTs = nowTs, settings = settings)
        } else {
            null
        }
        val effective = recomputed ?: existing
        if (effective == null) return null

        val shouldPublish = shouldRecomputeProfile ||
            nowTs - effective.lastPublishedTs >= REAL_PROFILE_PUBLISH_KEEPALIVE_MS
        auditLogger.infoThrottled(
            throttleKey = "insulin_profile_real_refresh:${effective.status}",
            intervalMs = RECURRING_INFO_LOG_INTERVAL_MS,
            message = "insulin_profile_real_refresh",
            metadata = mapOf(
                "existingFound" to (existing != null),
                "recomputed" to (recomputed != null),
                "shouldRecomputeProfile" to shouldRecomputeProfile,
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
        val rows = db.telemetryDao().latestReportAndProfileSince(nowTs - REAL_PROFILE_READ_LOOKBACK_MS)
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
        val glucose = glucoseCalibrationRepository.resolveDomainGlucoseHistory(
            rawGlucose = GlucoseSanitizer.filterEntities(db.glucoseDao().since(historyStart)),
            nowTs = nowTs
        ).sortedBy { it.ts }
        val therapy = TherapySanitizer
            .filterEntities(db.therapyDao().since(historyStart))
            .map { it.toDomain(gson) }
            .sortedBy { it.ts }
        val (telemetry, _) = collectTelemetryEntitiesByKeysPaged(
            telemetryDao = db.telemetryDao(),
            since = historyStart,
            keys = REAL_PROFILE_IMPLICIT_IOB_KEYS,
            callerTag = "automation_real_profile_implicit_iob",
            auditLogger = auditLogger
        )

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
            .filter { sample -> sample.key in REAL_PROFILE_IMPLICIT_IOB_KEYS }
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
        sensorBlocked: Boolean,
        nowTs: Long,
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        profile: ProfileEstimate?,
        calculatedSnapshot: CalculatedUamSnapshot
    ): UamInferenceCycleResult? {
        val cycleBucket = (nowTs / UAM_PROCESSING_BUCKET_MS) * UAM_PROCESSING_BUCKET_MS
        val shouldProcessBucket = cycleBucket > lastUamProcessingBucketTs
        if (!shouldProcessBucket) {
            lastUamInferenceResultCache?.let { cached ->
                return cached.copy(modeBoosted = settings.enableUamBoost)
            }
        }
        val existingEvents = uamEventStore.loadAll()
        if (!shouldProcessBucket) {
            val active = existingEvents
                .filter {
                    it.state == io.aaps.copilot.domain.predict.UamInferenceState.SUSPECTED ||
                        it.state == io.aaps.copilot.domain.predict.UamInferenceState.CONFIRMED
                }
                .maxByOrNull { it.updatedAt }
            val fallback = UamInferenceCycleResult(
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
            lastUamInferenceResultCache = fallback
            return fallback
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

        val finalizedEvents = inferenceOutput.events
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
        val result = UamInferenceCycleResult(
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
        lastUamInferenceResultCache = result
        return result
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

    private fun applyUnifiedUamTelemetry(
        latestTelemetry: MutableMap<String, Double?>,
        unified: UnifiedUamRuntimeSnapshot
    ) {
        latestTelemetry["uam_value"] = unified.flag
        latestTelemetry["uam_runtime_flag"] = unified.flag
        latestTelemetry["uam_runtime_control_flag"] = unified.controlFlag
        latestTelemetry["uam_runtime_confidence"] = unified.confidence
        latestTelemetry["uam_runtime_impact_mmol5"] = unified.impactMmol5
        latestTelemetry["uam_runtime_signed_residual_mmol5"] = unified.signedResidualMmol5
        latestTelemetry["uam_runtime_short_average_delta_mmol5"] = unified.shortAverageDeltaMmol5
        latestTelemetry["uam_runtime_60_mmol"] = unified.forecastComponent60Mmol
        latestTelemetry["uam_runtime_equivalent_carbs_grams"] = unified.equivalentCarbsGrams ?: 0.0
        latestTelemetry["uam_runtime_lower_bound_grams"] = unified.supportedLowerBoundGrams ?: 0.0
        latestTelemetry["uam_runtime_onset_ts"] = unified.onsetTs?.toDouble() ?: 0.0
        latestTelemetry["uam_runtime_sensor_trust"] = unified.sensorTrust
        latestTelemetry["uam_runtime_therapy_coverage"] = unified.therapyCoverage
        latestTelemetry["uam_runtime_sensitivity_cycle_id_hash"] = unified.sensitivityCycleId.hashCode().toDouble()
        latestTelemetry["uam_runtime_sensitivity_settings_revision"] =
            unified.sensitivitySettingsRevision.toDouble()
        latestTelemetry["uam_runtime_sensitivity_isf_mmol_per_unit"] = unified.sensitivityIsfMmolPerUnit
        latestTelemetry["uam_runtime_sensitivity_cr_gram_per_unit"] = unified.sensitivityCrGramPerUnit
        // Clear compatibility keys in the same cycle so older readers cannot revive stale legacy UAM.
        latestTelemetry["uam_runtime_carbs_grams"] = unified.equivalentCarbsGrams ?: 0.0
        latestTelemetry["uam_runtime_ingestion_ts"] = 0.0
        latestTelemetry["uam_uci0_mmol5"] = unified.impactMmol5
        latestTelemetry["uam_runtime_stale_decay"] = if (unified.state == "DECAYING") 1.0 else 0.0
    }

    private suspend fun persistUnifiedUamTelemetry(
        nowTs: Long,
        unified: UnifiedUamRuntimeSnapshot,
        acceptedClinicalForecastAuthority: AcceptedClinicalForecasts
    ) {
        requireUnifiedUamAcceptedForecastAuthorityStatic(
            unified = unified,
            authority = acceptedClinicalForecastAuthority
        )
        val source = "copilot_uam_runtime"
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

        addNumeric("uam_value", unified.flag)
        addNumeric("uam_runtime_flag", unified.flag)
        addNumeric("uam_runtime_control_flag", unified.controlFlag)
        addNumeric("uam_runtime_confidence", unified.confidence)
        addNumeric("uam_runtime_impact_mmol5", unified.impactMmol5, "mmol/5m")
        addNumeric("uam_runtime_signed_residual_mmol5", unified.signedResidualMmol5, "mmol/5m")
        addNumeric("uam_runtime_short_average_delta_mmol5", unified.shortAverageDeltaMmol5, "mmol/5m")
        addNumeric("uam_runtime_60_mmol", unified.forecastComponent60Mmol, "mmol/L")
        addNumeric("uam_runtime_equivalent_carbs_grams", unified.equivalentCarbsGrams ?: 0.0, "g")
        addNumeric("uam_runtime_lower_bound_grams", unified.supportedLowerBoundGrams ?: 0.0, "g")
        addNumeric("uam_runtime_onset_ts", unified.onsetTs?.toDouble() ?: 0.0)
        addNumeric("uam_runtime_first_detection_ts", unified.firstDetectionTs?.toDouble() ?: 0.0)
        addNumeric("uam_runtime_active_since_ts", unified.activeSinceTs?.toDouble() ?: 0.0)
        addNumeric("uam_runtime_support_stable_buckets", unified.supportStableBuckets.toDouble())
        addNumeric("uam_runtime_lower_bound_stable_buckets", unified.lowerBoundStableBuckets.toDouble())
        addNumeric("uam_runtime_sensor_trust", unified.sensorTrust)
        addNumeric("uam_runtime_therapy_coverage", unified.therapyCoverage)
        addNumeric("uam_runtime_sensitivity_settings_revision", unified.sensitivitySettingsRevision.toDouble())
        addNumeric("uam_runtime_sensitivity_isf_mmol_per_unit", unified.sensitivityIsfMmolPerUnit, "mmol/L/U")
        addNumeric("uam_runtime_sensitivity_cr_gram_per_unit", unified.sensitivityCrGramPerUnit, "g/U")
        addText("uam_runtime_state", unified.state)
        addText("uam_runtime_source", unified.source)
        addText("uam_runtime_reason", unified.reasons.sorted().joinToString("|").ifBlank { "none" })
        addText("uam_runtime_algorithm_version", unified.algorithmVersion)
        addText("uam_runtime_episode_id", unified.episodeId.ifBlank { "none" })
        addText("uam_runtime_sensitivity_cycle_id", unified.sensitivityCycleId)
        rows += buildAcceptedClinicalForecastTelemetryRowsStatic(
            nowTs = nowTs,
            source = source,
            keyPrefix = "uam_runtime_accepted_forecast",
            authority = acceptedClinicalForecastAuthority
        )

        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    private suspend fun processUnifiedUamExport(
        nowTs: Long,
        settings: AppSettings,
        unified: UnifiedUamRuntimeSnapshot,
        acceptedClinicalForecastAuthority: AcceptedClinicalForecasts,
        currentGlucoseMmol: Double,
        latestTelemetry: MutableMap<String, Double?>
    ) {
        requireUnifiedUamAcceptedForecastAuthorityStatic(
            unified = unified,
            authority = acceptedClinicalForecastAuthority
        )
        val controlForecasts = unified.acceptedForecasts
        val candidate = UamExportPolicyInput(
            nowTs = nowTs,
            episodeId = unified.episodeId,
            activeSinceTs = unified.activeSinceTs,
            confidence = unified.confidence,
            supportedLowerBoundGrams = unified.supportedLowerBoundGrams,
            lowerBoundStableBuckets = unified.lowerBoundStableBuckets,
            sensorTrust = unified.sensorTrust,
            sensorBlocked = unified.sensorBlocked,
            signedResidualMmol5 = unified.signedResidualMmol5,
            shortAverageDeltaMmol5 = unified.shortAverageDeltaMmol5,
            currentGlucoseMmol = currentGlucoseMmol,
            forecastMinimumMmol = controlForecasts.minOfOrNull { forecast ->
                minOf(forecast.valueMmol, forecast.ciLow)
            } ?: currentGlucoseMmol,
            effectiveCobGrams = unified.effectiveCobGrams,
            therapyCoverage = unified.therapyCoverage,
            remoteLedger = emptyList(),
            sourceSnapshotTs = unified.timestamp,
            maximumIncrementGrams = if (settings.enableUamAutoExportCap) {
                settings.uamAutoExportCapGrams.toDouble()
            } else {
                UamExportPolicy.MAX_INCREMENT_G
            }
        )
        val dispatch = dispatchUnifiedUamExportStatic(
            settings = settings,
            candidate = candidate,
            coordinator = uamExportCoordinator
        )
        val route = dispatch.route
        val outcome = dispatch.outcome
        val exportTelemetry = resolveUnifiedUamExportTelemetryStatic(
            nowTs = nowTs,
            episodeId = unified.episodeId,
            onsetTs = unified.onsetTs,
            lowerBoundGrams = unified.supportedLowerBoundGrams ?: 0.0,
            enabled = route.invokeCoordinator,
            dryRun = route.dryRun,
            outcome = outcome
        )

        latestTelemetry["uam_export_live_enabled"] = if (exportTelemetry.liveEnabled) 1.0 else 0.0
        latestTelemetry["uam_export_eligible"] = if (exportTelemetry.eligible) 1.0 else 0.0
        latestTelemetry["uam_export_episode_age_min"] = exportTelemetry.episodeAgeMinutes
        latestTelemetry["uam_export_supported_lower_bound_g"] = exportTelemetry.lowerBoundGrams
        latestTelemetry["uam_export_cumulative_g"] = exportTelemetry.cumulativeGrams
        latestTelemetry["uam_export_rolling_30m_g"] = exportTelemetry.rolling30Grams
        latestTelemetry["uam_export_rolling_60m_g"] = exportTelemetry.rolling60Grams
        latestTelemetry["uam_export_last_increment_g"] = exportTelemetry.lastIncrementGrams
        latestTelemetry["uam_export_last_ts"] = exportTelemetry.lastIncrementTs?.toDouble() ?: 0.0
        latestTelemetry["uam_export_delivered"] = if (exportTelemetry.delivered) 1.0 else 0.0

        persistUnifiedUamExportTelemetry(
            nowTs = nowTs,
            liveEnabled = exportTelemetry.liveEnabled,
            eligible = exportTelemetry.eligible,
            blockReason = exportTelemetry.blockReason,
            episodeId = exportTelemetry.episodeId,
            episodeAgeMinutes = exportTelemetry.episodeAgeMinutes,
            lowerBoundGrams = exportTelemetry.lowerBoundGrams,
            cumulativeGrams = exportTelemetry.cumulativeGrams,
            rolling30Grams = exportTelemetry.rolling30Grams,
            rolling60Grams = exportTelemetry.rolling60Grams,
            lastIncrementGrams = exportTelemetry.lastIncrementGrams,
            lastIncrementTs = exportTelemetry.lastIncrementTs,
            delivered = exportTelemetry.delivered
        )
    }

    private suspend fun persistUnifiedUamExportTelemetry(
        nowTs: Long,
        liveEnabled: Boolean,
        eligible: Boolean,
        blockReason: String,
        episodeId: String,
        episodeAgeMinutes: Double,
        lowerBoundGrams: Double,
        cumulativeGrams: Double,
        rolling30Grams: Double,
        rolling60Grams: Double,
        lastIncrementGrams: Double,
        lastIncrementTs: Long?,
        delivered: Boolean
    ) {
        val source = "copilot_uam_export"
        fun numeric(key: String, value: Double, unit: String? = null) = TelemetrySampleEntity(
            id = "tm-$source-$key-$nowTs",
            timestamp = nowTs,
            source = source,
            key = key,
            valueDouble = value,
            valueText = null,
            unit = unit,
            quality = "OK"
        )
        fun text(key: String, value: String) = TelemetrySampleEntity(
            id = "tm-$source-$key-$nowTs",
            timestamp = nowTs,
            source = source,
            key = key,
            valueDouble = null,
            valueText = value,
            unit = null,
            quality = "OK"
        )
        db.telemetryDao().upsertAll(
            listOf(
                numeric("uam_export_live_enabled", if (liveEnabled) 1.0 else 0.0),
                numeric("uam_export_eligible", if (eligible) 1.0 else 0.0),
                text("uam_export_block_reason", blockReason.ifBlank { "none" }),
                text("uam_export_episode_id", episodeId.ifBlank { "none" }),
                numeric("uam_export_episode_age_min", episodeAgeMinutes, "min"),
                numeric("uam_export_supported_lower_bound_g", lowerBoundGrams, "g"),
                numeric("uam_export_cumulative_g", cumulativeGrams, "g"),
                numeric("uam_export_rolling_30m_g", rolling30Grams, "g"),
                numeric("uam_export_rolling_60m_g", rolling60Grams, "g"),
                numeric("uam_export_last_increment_g", lastIncrementGrams, "g"),
                numeric("uam_export_last_ts", lastIncrementTs?.toDouble() ?: 0.0),
                numeric("uam_export_delivered", if (delivered) 1.0 else 0.0)
            )
        )
    }

    private suspend fun persistForecastDecompositionTelemetry(
        nowTs: Long,
        decomposition: ForecastDecompositionSnapshot?,
        acceptedCycleId: String
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
        val foodSteps = decomposition?.announcedCarbSteps.orEmpty()
        if (io.aaps.copilot.domain.predict.MealRollingImpact.nextThirtyMinutes(foodSteps).isNotEmpty()) {
            addText("forecast_meal_steps", gson.toJson(mapOf(
                "cycle" to acceptedCycleId, "steps" to foodSteps.take(13)
            )))
        } else {
            addText("forecast_meal_steps", null)
        }

        db.telemetryDao().upsertAll(rows)
    }

    private suspend fun persistActivityForecastTelemetry(
        nowTs: Long,
        context: ActivityEffectContext,
        plan: ActivityForecastPlan
    ) {
        if (context == ActivityEffectContext.DISABLED) return

        fun forecastValue(forecasts: List<Forecast>?, horizon: Int): Double? =
            forecasts?.firstOrNull { it.horizonMinutes == horizon }?.valueMmol
        val fingerprint = buildString {
            append(context.source.name)
            append('|').append(context.factor5)
            append('|').append(context.factor30)
            append('|').append(context.factor60)
            append('|').append(context.confidence)
            append('|').append(context.blockers.map { it.name }.sorted().joinToString(","))
            append('|').append(plan.suppressionReasons.map { it.name }.sorted().joinToString(","))
            append('|').append(plan.shadowForecasts != null)
            listOf(5, 30, 60).forEach { horizon ->
                append('|').append(forecastValue(plan.controlForecasts, horizon))
                append('|').append(forecastValue(plan.shadowForecasts, horizon))
            }
        }
        if (fingerprint == lastActivityForecastTelemetryFingerprint &&
            nowTs - lastActivityForecastTelemetryAtMs < RECURRING_INFO_LOG_INTERVAL_MS
        ) {
            return
        }

        val source = "copilot_activity_forecast"
        val rows = mutableListOf<TelemetrySampleEntity>()
        fun numeric(key: String, value: Double?, unit: String? = null) {
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
        fun text(key: String, value: String) {
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = value,
                unit = null,
                quality = "OK"
            )
        }

        numeric("activity_shadow_available", if (plan.shadowForecasts != null) 1.0 else 0.0)
        numeric("activity_shadow_factor_5m", context.factor5)
        numeric("activity_shadow_factor_30m", context.factor30)
        numeric("activity_shadow_factor_60m", context.factor60)
        numeric("activity_shadow_confidence", context.confidence)
        text("activity_shadow_source", context.source.name)
        text("activity_shadow_blockers", context.blockers.map { it.name }.sorted().joinToString(","))
        text("activity_shadow_suppressions", plan.suppressionReasons.map { it.name }.sorted().joinToString(","))
        listOf(5, 30, 60).forEach { horizon ->
            numeric("activity_shadow_control_${horizon}m_mmol", forecastValue(plan.controlForecasts, horizon), "mmol/L")
            numeric("activity_shadow_${horizon}m_mmol", forecastValue(plan.shadowForecasts, horizon), "mmol/L")
        }
        db.telemetryDao().upsertAll(rows)
        lastActivityForecastTelemetryFingerprint = fingerprint
        lastActivityForecastTelemetryAtMs = nowTs
    }

    suspend fun runDryRunSimulation(days: Int): DryRunReport {
        val settings = settingsStore.settings.first()
        val dryRunPredictionEngine = (predictionEngine as? HybridPredictionEngine)
            ?.newSimulationEngine()
            ?.also { configurePredictionEngine(settings = settings, engine = it) }
            ?: predictionEngine
        val periodDays = days.coerceIn(1, 60)
        val startTs = System.currentTimeMillis() - periodDays * 24L * 60 * 60 * 1000

        val glucose = glucoseCalibrationRepository.resolveDomainGlucoseHistory(
            rawGlucose = GlucoseSanitizer.filterEntities(db.glucoseDao().since(startTs)),
            nowTs = System.currentTimeMillis()
        ).sortedBy { it.ts }
        val therapy = db.therapyDao().since(startTs)
            .map { mapDryRunTherapyEventStatic(it, gson) }
            .sortedBy { it.ts }
        val patterns = db.patternDao().all()
        val (profileEntity, segmentEntities) = db.withTransaction {
            db.profileEstimateDao().active() to db.profileSegmentEstimateDao().all()
        }
        val profile = profileEntity?.toProfileEstimate()
        val segments = segmentEntities
            .filter { segment ->
                isMatchingProfileSegmentGenerationStatic(
                    profileTimestamp = profileEntity?.timestamp,
                    segmentUpdatedAt = segment.updatedAt
                )
            }
            .associateBy { it.dayType to it.timeSlot }

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
            val forecasts = ensureForecast30(dryRunPredictionEngine.predict(gWindow, tWindow))

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

    private suspend fun resolveActiveAapsTarget(now: Long): ActiveAapsTarget? {
        return resolveActiveAapsTargetFromRoomStatic(db = db, gson = gson, nowTs = now)
    }

    private fun runtimeConfig(settings: AppSettings): RuleRuntimeConfig {
        val enabled = enabledTargetRuleIdsStatic(
            postHypoEnabled = settings.rulePostHypoEnabled,
            patternEnabled = settings.rulePatternEnabled,
            segmentEnabled = settings.ruleSegmentEnabled,
            scheduleAutoEnabled = settings.baseTargetSchedule.autoEnabled
        )
        val priorities = mapOf(
            AdaptiveTargetControllerRule.RULE_ID to settings.adaptiveControllerPriority,
            "PostHypoReboundGuard.v1" to settings.rulePostHypoPriority,
            "PatternAdaptiveTarget.v1" to settings.rulePatternPriority,
            "SegmentProfileGuard.v1" to settings.ruleSegmentPriority
        )
        return RuleRuntimeConfig(enabledRuleIds = enabled, priorities = priorities)
    }

    private suspend fun resolveRecentActiveSafetyTarget(
        nowTs: Long,
        activeTempTarget: Double?
    ): Double? {
        val activeTarget = activeTempTarget?.takeIf(Double::isFinite) ?: return null
        val latest = db.actionCommandDao().latestByTypeAndStatusAtOrBeforeExcludingPrefix(
            type = "temp_target",
            status = NightscoutActionRepository.STATUS_SENT,
            through = nowTs,
            excludedPrefix = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%"
        ) ?: return null
        if (nowTs - latest.timestamp !in 0L..LOW_GLUCOSE_SAFETY_SEED_MAX_AGE_MS) return null

        val payload = runCatching {
            gson.fromJson(latest.payloadJson, MutableMap::class.java) as? Map<*, *>
        }.getOrNull() ?: return null
        val reason = payload["reason"]?.toString().orEmpty()
        if (!isLowGlucoseSafetyReasonStatic(reason)) return null
        val sentTarget = payload["targetMmol"]
            ?.toString()
            ?.replace(",", ".")
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?: return null
        if (abs(sentTarget - activeTarget) > LOW_GLUCOSE_SAFETY_TARGET_MATCH_TOLERANCE_MMOL) return null
        return maxOf(activeTarget, sentTarget)
    }

    private suspend fun persistLowGlucoseTargetSafetyTelemetry(
        nowTs: Long,
        state: LowGlucoseTargetSafetyLatch.State
    ) {
        val source = "copilot_target_safety"
        fun numeric(key: String, value: Double?, unit: String? = null) = TelemetrySampleEntity(
            id = "tm-$source-$key-$nowTs",
            timestamp = nowTs,
            source = source,
            key = key,
            valueDouble = value,
            valueText = null,
            unit = unit,
            quality = if (value == null) "STALE" else "OK"
        )
        db.telemetryDao().upsertAll(
            listOf(
                numeric("target_low_risk_active", if (state.riskNow) 1.0 else 0.0),
                numeric("target_low_risk_latched", if (state.latched) 1.0 else 0.0),
                numeric("target_low_risk_safe_cycles", state.safeCycles.toDouble(), "cycle"),
                numeric(
                    "target_low_risk_protected_target_mmol",
                    state.protectedTargetMmol,
                    "mmol/L"
                )
            )
        )
    }

    private suspend fun maybeSendAdaptiveKeepaliveTempTarget(
        settings: AppSettings,
        nowTs: Long,
        dataFresh: Boolean,
        sensorBlocked: Boolean,
        activeTempTarget: Double?,
        actionsLast6h: Int,
        chronologyResolved: Boolean,
        causalThroughTs: Long,
        forecasts: List<Forecast>,
        baseTargetMmol: Double,
        lowGlucoseSafetyState: LowGlucoseTargetSafetyLatch.State
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
        if (!chronologyResolved) {
            auditLogger.info(
                "adaptive_keepalive_skipped",
                mapOf("reason" to "local_safety_chronology_unresolved")
            )
            return
        }

        val lastAutoSentTs = db.actionCommandDao()
            .latestTimestampByTypeAndStatusAtOrBeforeExcludingPrefix(
            type = "temp_target",
            status = NightscoutActionRepository.STATUS_SENT,
            through = causalThroughTs,
            excludedPrefix = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%"
        )
        if (lastAutoSentTs != null && nowTs - lastAutoSentTs < ADAPTIVE_KEEPALIVE_INTERVAL_MS) {
            return
        }

        val baseTarget = baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val alignedProposal = alignTempTargetToBaseTarget(
            action = ActionProposal(
                type = "temp_target",
                targetMmol = baseTarget,
                durationMinutes = ADAPTIVE_KEEPALIVE_DURATION_MINUTES,
                reason = "adaptive_keepalive_5m"
            ),
            forecasts = forecasts,
            baseTargetMmol = baseTarget,
            sourceRuleId = AdaptiveTargetControllerRule.RULE_ID
        )
        val proposal = protectLowGlucoseTargetStatic(
            action = alignedProposal,
            state = lowGlucoseSafetyState,
            minTargetMmol = settings.safetyMinTargetMmol,
            maxTargetMmol = settings.safetyMaxTargetMmol
        )

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
        val sent = submitActionWithTimeout(
            command = command,
            sourceRuleId = "adaptive_keepalive",
            nowTs = nowTs
        )
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

    private suspend fun submitActionWithTimeout(
        command: ActionCommand,
        sourceRuleId: String,
        nowTs: Long
    ): Boolean {
        val type = command.type.lowercase()
        if (type != "temp_target" && type != "carbs") {
            auditLogger.warn(
                "automation_action_skipped",
                mapOf("reason" to "unsupported_action_type", "type" to command.type, "ruleId" to sourceRuleId)
            )
            return false
        }
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            withTimeout(ACTION_SUBMIT_TIMEOUT_MS) {
                when (type) {
                    "temp_target" -> actionRepository.submitTempTarget(command)
                    "carbs" -> actionRepository.submitCarbs(command)
                    else -> false
                }
            }
        }
        return result.fold(
            onSuccess = { it },
            onFailure = { error ->
                auditLogger.warn(
                    "automation_action_send_failed",
                    mapOf(
                        "ruleId" to sourceRuleId,
                        "type" to command.type,
                        "idempotencyKey" to command.idempotencyKey,
                        "timeout" to (error is TimeoutCancellationException),
                        "durationMs" to (System.currentTimeMillis() - startedAt),
                        "cycleTs" to nowTs,
                        "reason" to (error.message ?: error::class.simpleName.orEmpty())
                    )
                )
                false
            }
        )
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
        val since = nowTs - cooldownMinutes * 60_000L + COOLDOWN_TOLERANCE_MS
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

    private data class SensorLagRuntimeContext(
        val sensorAgeHours: Double? = null,
        val sensorAgeTs: Long? = null,
        val sensorAgeSourceRaw: String? = null,
        val sageDays: Double? = null,
        val cageDays: Double? = null,
        val replayBucketStats: List<SensorLagReplayBucketStats> = emptyList()
    )

    private fun normalizeSensorStartedAtMillis(raw: Double?): Long? {
        val value = raw?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return when {
            value > 1_000_000_000_000.0 -> value.toLong()
            value > 1_000_000_000.0 -> (value * 1000.0).toLong()
            else -> null
        }
    }

    companion object {
        internal fun resolveEffectiveStaleMaxMinutesStatic(settings: AppSettings): Int {
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

        internal fun normalizeSegmentForRuntimeStatic(
            segment: ProfileSegmentEstimate?,
            localProfile: ProfileEstimate?,
            runtimeProfile: ProfileEstimate?
        ): ProfileSegmentEstimate? {
            if (segment == null || localProfile == null || runtimeProfile == null) return null

            fun normalize(value: Double?, localBaseline: Double, runtimeBaseline: Double): Double? {
                if (
                    value == null || !value.isFinite() || value <= 0.0 ||
                    !localBaseline.isFinite() || localBaseline <= 0.0 ||
                    !runtimeBaseline.isFinite() || runtimeBaseline <= 0.0
                ) {
                    return null
                }
                return (value / localBaseline * runtimeBaseline).takeIf { it.isFinite() && it > 0.0 }
            }

            return segment.copy(
                isfMmolPerUnit = normalize(
                    value = segment.isfMmolPerUnit,
                    localBaseline = localProfile.isfMmolPerUnit,
                    runtimeBaseline = runtimeProfile.isfMmolPerUnit
                ),
                crGramPerUnit = normalize(
                    value = segment.crGramPerUnit,
                    localBaseline = localProfile.crGramPerUnit,
                    runtimeBaseline = runtimeProfile.crGramPerUnit
                )
            )
        }

        internal fun isMatchingProfileSegmentGenerationStatic(
            profileTimestamp: Long?,
            segmentUpdatedAt: Long?
        ): Boolean = profileTimestamp != null &&
            segmentUpdatedAt != null &&
            profileTimestamp == segmentUpdatedAt

        private fun ruleEvaluationModeStatic(intent: AutomationCycleIntent): RuleEvaluationMode =
            if (intent == AutomationCycleIntent.NORMAL) {
                RuleEvaluationMode.LIVE
            } else {
                RuleEvaluationMode.DIAGNOSTIC_READ_ONLY
            }

        internal const val TARGET_MANAGER_DELIVERY_TRUST_SOURCE = "copilot_target_manager"
        internal const val ALERT_CAUSE_DIAGNOSTIC_SOURCE = "copilot_alert_cause_diagnostic"
        internal const val ACCEPTED_RUNTIME_TELEMETRY_SOURCE = "copilot_accepted_runtime"
        internal const val ALERT_CAUSE_CONTEXT_TIMEOUT_MS = 125L
        internal const val ALERT_CAUSE_MAX_THERAPY_EVENTS = 128
        internal const val ALERT_CAUSE_MAX_CONTEXT_EVENTS = 64
        internal const val ALERT_CAUSE_MAX_PLANNED_EVENTS = 64
        internal const val ALERT_CAUSE_PLANNED_PAGE_LIMIT = 65
        internal const val ALERT_CAUSE_MAX_PLANNED_PAGES = 8

        internal suspend fun <T> loadOptionalAlertCauseContextStatic(
            timeoutMs: Long = ALERT_CAUSE_CONTEXT_TIMEOUT_MS,
            load: suspend () -> T
        ): AlertCauseContextLoadResult<T> = try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                AlertCauseContextLoadResult.Success(load())
            } ?: AlertCauseContextLoadResult.Timeout
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AlertCauseContextLoadResult.Failure(
                error::class.java.simpleName.take(80).ifBlank { "Exception" }
            )
        }

        internal suspend fun reportAlertCauseContextOutcomeStatic(
            result: AlertCauseContextLoadResult<*>,
            stage: GlucoseAlertState,
            warn: suspend (String, Map<String, Any?>) -> Unit
        ) {
            val metadata = when (result) {
                is AlertCauseContextLoadResult.Success -> return
                AlertCauseContextLoadResult.Timeout -> mapOf(
                    "stage" to stage.name,
                    "eventContextAvailable" to false,
                    "reason" to "timeout",
                    "timeoutMs" to ALERT_CAUSE_CONTEXT_TIMEOUT_MS
                )
                is AlertCauseContextLoadResult.Failure -> mapOf(
                    "stage" to stage.name,
                    "eventContextAvailable" to false,
                    "reason" to "failure",
                    "errorType" to result.errorType
                )
                is AlertCauseContextLoadResult.Overflow -> mapOf(
                    "stage" to stage.name,
                    "eventContextAvailable" to false,
                    "reason" to "overflow",
                    "source" to result.source.name
                )
            }
            warn("glucose_alert_cause_context_unavailable", metadata)
        }

        internal fun boundAlertCauseTherapyStatic(
            therapy: List<TherapyEvent>
        ): AlertCauseBoundedRows<TherapyEvent> {
            val relevant = ArrayDeque<TherapyEvent>(ALERT_CAUSE_MAX_THERAPY_EVENTS)
            var overflow = false
            val timelineRepository = EventTimelineRepository()
            therapy.forEach { event ->
                if (timelineRepository.isAlertCauseContextTherapyEvent(event)) {
                    if (relevant.size == ALERT_CAUSE_MAX_THERAPY_EVENTS) {
                        overflow = true
                    } else {
                        relevant.addLast(event)
                    }
                }
            }
            return AlertCauseBoundedRows(rows = relevant.toList(), overflow = overflow)
        }

        internal fun hasAlertCauseOverflowStatic(rows: Collection<*>, limit: Int): Boolean =
            rows.size > limit.coerceAtLeast(0)

        internal suspend fun loadRelevantPlannedAlertEventsStatic(
            nowTs: Long,
            loadPage: suspend (
                afterLocalStartIso: String?,
                afterEventId: String?,
                limit: Int
            ) -> List<PlannedActivityEventEntity>
        ): AlertCausePlannedLoadResult {
            val relevant = ArrayList<CompensationEvent>(ALERT_CAUSE_MAX_PLANNED_EVENTS + 1)
            val timelineRepository = EventTimelineRepository()
            var afterStart: String? = null
            var afterId: String? = null
            var pagesRead = 0

            repeat(ALERT_CAUSE_MAX_PLANNED_PAGES) {
                val page = loadPage(afterStart, afterId, ALERT_CAUSE_PLANNED_PAGE_LIMIT)
                pagesRead += 1
                if (page.isEmpty()) {
                    return AlertCausePlannedLoadResult.Complete(relevant, pagesRead)
                }
                if (page.size > ALERT_CAUSE_PLANNED_PAGE_LIMIT) {
                    return AlertCausePlannedLoadResult.ScanLimitReached
                }

                var previousStart = afterStart
                var previousId = afterId
                page.forEach { row ->
                    val advances = previousStart == null ||
                        row.localStartIso > previousStart ||
                        (row.localStartIso == previousStart && row.eventId > requireNotNull(previousId))
                    if (!advances) return AlertCausePlannedLoadResult.CursorStalled
                    previousStart = row.localStartIso
                    previousId = row.eventId
                }

                timelineRepository.plannedActivityEvents(
                    rows = page,
                    fromTs = nowTs,
                    throughTs = nowTs,
                    statusAtTs = nowTs
                ).asSequence()
                    .filter { event -> event.startTs <= nowTs && event.endTs > nowTs }
                    .forEach { event ->
                        relevant += event
                        if (relevant.size > ALERT_CAUSE_MAX_PLANNED_EVENTS) {
                            return AlertCausePlannedLoadResult.RelevantOverflow
                        }
                    }

                afterStart = page.last().localStartIso
                afterId = page.last().eventId
                if (page.size < ALERT_CAUSE_PLANNED_PAGE_LIMIT) {
                    return AlertCausePlannedLoadResult.Complete(
                        events = relevant.sortedWith(
                            compareBy<CompensationEvent> { it.startTs }.thenBy { it.localId }
                        ),
                        pagesRead = pagesRead
                    )
                }
            }
            return AlertCausePlannedLoadResult.ScanLimitReached
        }

        internal suspend fun prepareAlertCauseForDecisionStatic(
            decision: GlucoseAlertDecision,
            buildInput: suspend () -> AlertCauseInput
        ): PreparedAlertCause? {
            if (decision.episodeStage == GlucoseAlertState.NONE || decision.episodeDirection == null) {
                return null
            }
            return try {
                val input = buildInput()
                val analysis = AlertCauseAnalyzer.analyze(input)
                PreparedAlertCause(
                    analysis = analysis,
                    snapshot = AlertCauseSnapshotCodec.encode(input, analysis),
                    input = input
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: VirtualMachineError) {
                throw fatal
            } catch (termination: ThreadDeath) {
                throw termination
            } catch (_: Throwable) {
                PreparedAlertCause(
                    analysis = AlertCauseAnalyzer.failureAnalysis(),
                    snapshot = AlertCauseSnapshotCodec.failureSnapshot(),
                    input = null
                )
            }
        }

        internal fun buildAlertCauseDiagnosticTelemetryRowsStatic(
            nowTs: Long,
            prepared: PreparedAlertCause
        ): List<TelemetrySampleEntity> {
            val input = prepared.input
            val sensitivity = input?.sensitivity
            val uam = input?.uam
            val identityMatched = sensitivity != null && uam != null &&
                sensitivity.cycleId == uam.sensitivityCycleId &&
                sensitivity.settingsRevision == uam.sensitivitySettingsRevision
            fun text(key: String, value: String?) = TelemetrySampleEntity(
                id = "tm-copilot-alert-cause-diagnostic-$key-$nowTs",
                timestamp = nowTs,
                source = ALERT_CAUSE_DIAGNOSTIC_SOURCE,
                key = key,
                valueDouble = null,
                valueText = value?.trim()?.takeIf(String::isNotBlank),
                unit = null,
                quality = if (value.isNullOrBlank()) "STALE" else "OK"
            )
            fun numeric(key: String, value: Long?) = TelemetrySampleEntity(
                id = "tm-copilot-alert-cause-diagnostic-$key-$nowTs",
                timestamp = nowTs,
                source = ALERT_CAUSE_DIAGNOSTIC_SOURCE,
                key = key,
                valueDouble = value?.toDouble(),
                valueText = null,
                unit = null,
                quality = if (value == null) "STALE" else "OK"
            )
            return listOf(
                text("alert_cause_primary", prepared.analysis.primary.name),
                text("alert_cause_confidence", prepared.analysis.confidence.name),
                text(
                    "alert_cause_identity_status",
                    if (identityMatched) "MATCHED" else "UNAVAILABLE"
                ),
                text("alert_cause_sensitivity_cycle_id", sensitivity?.cycleId),
                numeric(
                    "alert_cause_sensitivity_settings_revision",
                    sensitivity?.settingsRevision
                ),
                text("alert_cause_uam_sensitivity_cycle_id", uam?.sensitivityCycleId),
                numeric(
                    "alert_cause_uam_sensitivity_settings_revision",
                    uam?.sensitivitySettingsRevision
                ),
                text("alert_cause_input_snapshot", prepared.snapshot.canonicalJson)
            )
        }

        internal fun buildTargetManagerSensitivityTelemetryRowsStatic(
            nowTs: Long,
            sensitivityRuntime: SensitivityRuntimeConsumerContext
        ): List<TelemetrySampleEntity> {
            require(sensitivityRuntime.consumer == SensitivityRuntimeConsumer.TARGET_MANAGER) {
                "Target Manager diagnostics require TARGET_MANAGER sensitivity context"
            }
            val snapshot = sensitivityRuntime.snapshot
            return listOf(
                TelemetrySampleEntity(
                    id = "tm-copilot-target-manager-sensitivity-cycle-$nowTs",
                    timestamp = nowTs,
                    source = TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                    key = "target_manager_sensitivity_cycle_id",
                    valueDouble = null,
                    valueText = snapshot.forecastCycleId,
                    unit = null,
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = "tm-copilot-target-manager-sensitivity-revision-$nowTs",
                    timestamp = nowTs,
                    source = TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                    key = "target_manager_sensitivity_settings_revision",
                    valueDouble = snapshot.settingsRevision.toDouble(),
                    valueText = null,
                    unit = null,
                    quality = "OK"
                )
            )
        }

        internal fun parseAlertUamStateStatic(
            raw: String,
            parse: (String) -> AlertUamState = AlertUamState::valueOf
        ): AlertUamState = ordinaryExceptionOrNull { parse(raw) } ?: AlertUamState.BLOCKED

        internal fun buildAlertCauseInputStatic(
            nowTs: Long,
            currentGlucoseTimestamp: Long,
            decision: GlucoseAlertDecision,
            forecasts: List<Forecast>,
            dataFresh: Boolean,
            sensorQuality: SensorQualityAssessment,
            sensorBlocked: Boolean,
            insulinCycleContext: InsulinCycleContext,
            sensitivitySnapshot: SensitivityRuntimeSnapshot,
            unifiedUam: UnifiedUamRuntimeSnapshot,
            deliveryTrust: DeliveryTrustState?,
            effectiveBaseTarget: EffectiveBaseTarget,
            circadianBiasApplied: Boolean,
            circadianDelta30Mmol: Double?,
            circadianConfidence: Double?,
            activeEventTypes: Set<AlertContextEventType>,
            eventContextAvailable: Boolean
        ): AlertCauseInput {
            val byHorizon = forecasts.associateBy(Forecast::horizonMinutes)
            val insulin = insulinCycleContext.snapshot?.let { snapshot ->
                AlertInsulinEvidence(
                    cycleTimestamp = insulinCycleContext.cycleTimestamp,
                    snapshotTimestamp = snapshot.timestamp,
                    evidenceTimestamp = snapshot.evidenceTimestamp,
                    source = AlertInsulinSource.valueOf(snapshot.source.name),
                    netIobUnits = snapshot.netIobUnits,
                    effectivePositiveIobUnits = snapshot.effectivePositiveIobUnits,
                    insulinActivity = snapshot.insulinActivity,
                    confidence = snapshot.confidence
                )
            }
            val sensitivity = AlertSensitivityEvidence(
                cycleId = sensitivitySnapshot.forecastCycleId,
                settingsRevision = sensitivitySnapshot.settingsRevision,
                timestamp = sensitivitySnapshot.timestamp,
                isfMmolPerUnit = sensitivitySnapshot.isf.effective,
                crGramPerUnit = sensitivitySnapshot.cr.effective,
                isfSource = AlertSensitivitySource.valueOf(sensitivitySnapshot.isf.resolved.name),
                crSource = AlertSensitivitySource.valueOf(sensitivitySnapshot.cr.resolved.name),
                confidence = minOf(
                    sensitivitySnapshot.isf.confidence,
                    sensitivitySnapshot.cr.confidence
                )
            )
            return AlertCauseInput(
                nowTs = nowTs,
                cycleTimestamp = insulinCycleContext.cycleTimestamp,
                direction = when (requireNotNull(decision.episodeDirection)) {
                    GlucoseAlertDirection.LOW -> AlertCauseDirection.LOW
                    GlucoseAlertDirection.HIGH -> AlertCauseDirection.HIGH
                },
                glucose = AlertGlucoseEvidence(
                    currentMmol = decision.currentGlucoseMmol,
                    currentTimestamp = currentGlucoseTimestamp,
                    forecast5Mmol = byHorizon[5]?.valueMmol,
                    forecast30Mmol = byHorizon[30]?.valueMmol,
                    forecast60Mmol = byHorizon[60]?.valueMmol,
                    lowerCi5Mmol = byHorizon[5]?.ciLow,
                    lowerCi30Mmol = byHorizon[30]?.ciLow,
                    lowerCi60Mmol = byHorizon[60]?.ciLow,
                    trendDelta5Mmol = decision.trendDelta5Mmol,
                    dataFresh = dataFresh
                ),
                sensor = AlertSensorEvidence(
                    score = sensorQuality.score,
                    blocked = sensorBlocked || sensorQuality.blocked,
                    suspectFalseLow = sensorQuality.suspectFalseLow
                ),
                insulin = insulin,
                sensitivity = sensitivity,
                uam = AlertUamEvidence(
                    timestamp = unifiedUam.timestamp,
                    state = parseAlertUamStateStatic(unifiedUam.state),
                    active = unifiedUam.flag >= 0.5,
                    controlActive = unifiedUam.controlFlag >= 0.5,
                    confidence = unifiedUam.confidence,
                    signedResidualMmol5 = unifiedUam.signedResidualMmol5,
                    equivalentCarbsGrams = unifiedUam.equivalentCarbsGrams,
                    sensitivityCycleId = unifiedUam.sensitivityCycleId,
                    sensitivitySettingsRevision = unifiedUam.sensitivitySettingsRevision
                ),
                deliveryTrust = deliveryTrust,
                target = AlertTargetEvidence(
                    manualTargetMmol = effectiveBaseTarget.manualTargetMmol,
                    autoDeltaMmol = effectiveBaseTarget.autoDeltaMmol,
                    effectiveTargetMmol = effectiveBaseTarget.effectiveTargetMmol,
                    state = AlertTargetState.valueOf(effectiveBaseTarget.state.name),
                    reasonCodes = effectiveBaseTarget.reasonCodes
                ),
                circadian = AlertCircadianEvidence(
                    applied = circadianBiasApplied,
                    delta30Mmol = circadianDelta30Mmol,
                    confidence = circadianConfidence ?: 0.0
                ),
                activeEventTypes = activeEventTypes,
                eventContextAvailable = eventContextAvailable
            )
        }

        internal fun decodeAlertDeliveryTrustStatic(
            telemetry: Iterable<DeliveryTrustTelemetryValue>
        ): DeliveryTrustState? {
            fun latestStateFor(key: String): Pair<Boolean, DeliveryTrustState?> {
                val valid = telemetry.asSequence()
                    .filter { row -> row.key == key }
                    .mapNotNull { row ->
                        if (
                            row.key == DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY &&
                            row.source != TARGET_MANAGER_DELIVERY_TRUST_SOURCE
                        ) {
                            return@mapNotNull null
                        }
                        DeliveryTrustStateWireCodec.decodeTelemetry(
                            key = row.key,
                            source = row.source,
                            value = row.value
                        )?.let { state -> row.timestamp to state }
                    }
                    .toList()
                if (valid.isEmpty()) return false to null
                val latestTimestamp = valid.maxOf { it.first }
                return true to valid.asSequence()
                    .filter { it.first == latestTimestamp }
                    .map { it.second }
                    .distinct()
                    .singleOrNull()
            }

            val stable = latestStateFor(DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY)
            if (stable.first) return stable.second
            return latestStateFor(DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY).second
        }

        internal fun selectAlertDeliveryTrustTelemetryStatic(
            rows: Iterable<TelemetrySampleEntity>
        ): List<DeliveryTrustTelemetryValue> = rows.asSequence()
            .filter { row ->
                when (row.key) {
                    DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY ->
                        row.source == TARGET_MANAGER_DELIVERY_TRUST_SOURCE
                    DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY -> true
                    else -> false
                }
            }
            .map { row ->
                DeliveryTrustTelemetryValue(
                    timestamp = row.timestamp,
                    source = row.source,
                    key = row.key,
                    value = row.valueDouble
                )
            }
            .sortedWith(
                compareBy<DeliveryTrustTelemetryValue> { it.timestamp }
                    .thenBy { it.source }
                    .thenBy { it.key }
                    .thenBy { it.value }
            )
            .toList()

        internal fun selectAcceptedAlertDeliveryTrustTelemetryStatic(
            baseRows: Iterable<TelemetrySampleEntity>,
            reportRows: Iterable<TelemetrySampleEntity>
        ): List<DeliveryTrustTelemetryValue> = selectAlertDeliveryTrustTelemetryStatic(
            (baseRows.asSequence() + reportRows.asSequence()).asIterable()
        )

        internal fun buildSameCycleAlertDeliveryTrustTelemetryStatic(
            nowTs: Long,
            state: DeliveryTrustState
        ): DeliveryTrustTelemetryValue {
            val row = buildTargetManagerDeliveryTrustTelemetryRowStatic(nowTs, state)
            return DeliveryTrustTelemetryValue(
                timestamp = row.timestamp,
                source = row.source,
                key = row.key,
                value = row.valueDouble
            )
        }

        internal fun buildTargetManagerDeliveryTrustTelemetryRowStatic(
            nowTs: Long,
            state: DeliveryTrustState
        ): TelemetrySampleEntity {
            require(nowTs > 0L) { "target manager delivery trust timestamp must be positive" }
            val encoded = DeliveryTrustStateWireCodec.encode(state)
            require(encoded.isFinite()) { "target manager delivery trust code must be finite" }
            return TelemetrySampleEntity(
                id = "tm-$TARGET_MANAGER_DELIVERY_TRUST_SOURCE-" +
                    "${DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY}-$nowTs",
                timestamp = nowTs,
                source = TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                valueDouble = encoded,
                valueText = null,
                unit = "code",
                quality = "OK"
            )
        }

        internal fun buildTargetManagerLiveStatusStatic(
            nowTs: Long,
            mode: TargetManagerMode,
            priorityEnabled: Boolean,
            policyRevision: Long,
            activeAapsTarget: ActiveAapsTarget?,
            decision: TargetManagerDecision,
            noProposalReason: String? = null,
            dispatchAllowed: Boolean = true
        ): TargetManagerLiveStatus {
            val dispatchSuppressed = !dispatchAllowed && decision.outcome in setOf(
                TargetDecisionOutcome.SEND,
                TargetDecisionOutcome.RENEW_SAME_TARGET
            )
            val preflightFailure = TargetCommandPreflightFailure.fromMarkedReasonCodes(
                decision.reasonCodes
            )?.takeIf {
                decision.outcome == TargetDecisionOutcome.DELIVERY_FAILED &&
                    "delivery_status_unknown" !in decision.reasonCodes
            }
            return TargetManagerLiveStatus(
            schemaVersion = TargetManagerLiveStatusCodec.SCHEMA_VERSION,
            timestamp = nowTs,
            mode = mode.name,
            priorityEnabled = priorityEnabled,
            policyRevision = policyRevision,
            currentTargetMmol = activeAapsTarget?.takeIf {
                it.evidenceResolved && it.targetMmol.isFinite() && it.targetMmol > 0.0 &&
                    it.startedAt in 1L..nowTs && it.expiresAt > nowTs
            }?.targetMmol,
            proposedTargetMmol = decision.winner?.targetMmol,
            outcome = when {
                dispatchSuppressed -> "DISPATCH_DISABLED"
                preflightFailure != null -> preflightFailure.outcome.name
                else -> decision.outcome.name
            },
            reason = if (dispatchSuppressed) {
                "therapy_writes_disabled"
            } else if (preflightFailure != null) {
                preflightFailure.reasonCode
            } else when (decision.outcome) {
                TargetDecisionOutcome.NO_PROPOSAL -> when {
                    noProposalReason == "safety_iob_missing_blocks_lowering" -> "iob_unqualified"
                    decision.reasonCodes.any { it == "mode_off" } -> "mode_off"
                    else -> "no_eligible_proposal"
                }
                TargetDecisionOutcome.SHADOW_WOULD_SEND -> "shadow_would_send"
                TargetDecisionOutcome.SEND,
                TargetDecisionOutcome.RENEW_SAME_TARGET -> "eligible"
                TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE -> "semantic_duplicate"
                TargetDecisionOutcome.BLOCK_CADENCE -> "cadence_blocked"
                TargetDecisionOutcome.BLOCK_KILL_SWITCH -> "kill_switch"
                TargetDecisionOutcome.BLOCK_STALE_DATA -> "stale_data"
                TargetDecisionOutcome.BLOCK_SENSOR_TRUST -> "sensor_untrusted"
                TargetDecisionOutcome.BLOCK_DELIVERY_TRUST -> "delivery_untrusted"
                TargetDecisionOutcome.BLOCK_PROTECTIVE_DIRECTION -> "protective_direction"
                TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY -> "forecast_unreliable"
                TargetDecisionOutcome.BLOCK_MANUAL_TARGET -> if (
                    "external_target_writer_conflict" in decision.reasonCodes
                ) "external_target_writer_conflict" else "external_target_retained"
                TargetDecisionOutcome.BLOCK_LEGACY_TARGET_DRAIN -> "legacy_target_drain"
                TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS -> "safety_bounds"
                TargetDecisionOutcome.DELIVERY_FAILED -> "delivery_failed"
            }
        )
        }

        internal suspend fun reportTargetManagerLiveStatusStatic(
            liveEvaluation: Boolean,
            status: TargetManagerLiveStatus,
            persist: suspend (TelemetrySampleEntity) -> Unit
        ) {
            if (!liveEvaluation) return
            runBestEffortTargetManagerPostEvaluationAuditStatic {
                persist(TargetManagerLiveStatusCodec.toTelemetryRow(status))
            }
        }

        internal suspend fun <T : Any> evaluateAndPersistTargetManagerDeliveryTrustStatic(
            nowTs: Long,
            state: DeliveryTrustState,
            evaluate: suspend () -> T?,
            persist: suspend (TelemetrySampleEntity) -> Unit
        ): TargetManagerDeliveryTrustEvaluation<T>? {
            val evaluation = evaluate() ?: return null
            val row = buildTargetManagerDeliveryTrustTelemetryRowStatic(nowTs, state)
            return try {
                persist(row)
                TargetManagerDeliveryTrustEvaluation(
                    evaluation = evaluation,
                    telemetryPersisted = true,
                    persistenceFailureType = null
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (operational: Exception) {
                TargetManagerDeliveryTrustEvaluation(
                    evaluation = evaluation,
                    telemetryPersisted = false,
                    persistenceFailureType = operational::class.java.simpleName
                        .take(80)
                        .ifBlank { "Exception" }
                )
            }
        }

        internal suspend fun reportTargetManagerDeliveryTrustPersistenceFailureStatic(
            nowTs: Long,
            failureType: String?,
            warn: suspend (String, Map<String, Any?>) -> Unit
        ) {
            val boundedFailureType = failureType?.take(80)?.ifBlank { "Exception" } ?: return
            warn(
                "target_manager_delivery_trust_persistence_failed",
                mapOf(
                    "timestamp" to nowTs,
                    "source" to TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
                    "key" to DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    "failureType" to boundedFailureType
                )
            )
        }

        internal suspend fun <T : Any> evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
            nowTs: Long,
            state: DeliveryTrustState,
            evaluate: suspend () -> T?,
            persist: suspend (TelemetrySampleEntity) -> Unit,
            warn: suspend (String, Map<String, Any?>) -> Unit,
            reportDecision: suspend (T) -> Unit
        ): T? {
            val result = evaluateAndPersistTargetManagerDeliveryTrustStatic(
                nowTs = nowTs,
                state = state,
                evaluate = evaluate,
                persist = persist
            ) ?: return null
            runBestEffortTargetManagerPostEvaluationAuditStatic {
                reportTargetManagerDeliveryTrustPersistenceFailureStatic(
                    nowTs = nowTs,
                    failureType = result.persistenceFailureType,
                    warn = warn
                )
            }
            runBestEffortTargetManagerPostEvaluationAuditStatic {
                reportDecision(result.evaluation)
            }
            return result.evaluation
        }

        internal suspend fun <T : Any> evaluateTargetManagerWithFailureBoundaryStatic(
            mode: TargetManagerMode,
            evaluateAndReport: suspend () -> T?,
            reportEvaluationFailure: suspend (String, Map<String, Any?>) -> Unit
        ): T? = try {
            evaluateAndReport()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (operational: Exception) {
            reportEvaluationFailure(
                "target_manager_evaluation_failed",
                mapOf(
                    "mode" to mode.name,
                    "failureType" to operational.javaClass.simpleName,
                    "legacyFallback" to false
                )
            )
            null
        }

        internal suspend fun runBestEffortTargetManagerPostEvaluationAuditStatic(
            audit: suspend () -> Unit
        ) {
            try {
                audit()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                // A completed therapy dispatch must not be retried because its audit store is unavailable.
            }
        }

        internal fun activeAlertContextTypesStatic(
            events: List<CompensationEvent>,
            nowTs: Long
        ): Set<AlertContextEventType> {
            val mapped = events.asSequence()
                .filter { it.isActiveAt(nowTs) }
                .mapNotNull { event ->
                    when (event.type) {
                        CompensationEventType.ACTIVITY -> AlertContextEventType.ACTIVITY
                        CompensationEventType.STRESS -> AlertContextEventType.STRESS
                        CompensationEventType.ILLNESS -> AlertContextEventType.ILLNESS
                        CompensationEventType.SLEEP -> AlertContextEventType.SLEEP
                        CompensationEventType.HORMONAL,
                        CompensationEventType.MENSTRUAL_CYCLE -> AlertContextEventType.HORMONAL
                        CompensationEventType.MEDICATION_STEROID -> AlertContextEventType.STEROID
                        CompensationEventType.ALCOHOL -> AlertContextEventType.ALCOHOL
                        CompensationEventType.INFUSION_PUMP_INSULIN -> AlertContextEventType.INFUSION
                        CompensationEventType.SENSOR_CALIBRATION -> AlertContextEventType.SENSOR
                        CompensationEventType.MEAL,
                        CompensationEventType.CUSTOM -> null
                    }
                }
                .toSet()
            return AlertContextEventType.entries
                .filter(mapped::contains)
                .toCollection(linkedSetOf())
        }

        internal fun resolveCyclePolicyStatic(
            intent: AutomationCycleIntent,
            therapyActionsArmed: Boolean,
            killSwitch: Boolean,
            powerSaveActive: Boolean
        ): AutomationCyclePolicy = when (intent) {
            AutomationCycleIntent.NORMAL -> AutomationCyclePolicy(
                runCalculations = true,
                runRemoteRefresh = therapyActionsArmed && !powerSaveActive,
                therapyWritersAllowed = therapyActionsArmed && !killSwitch && !powerSaveActive,
                allowSensitivityMaintenance = therapyActionsArmed && !powerSaveActive,
                allowActionRepositoryAccess = therapyActionsArmed && !powerSaveActive,
                allowLocalSafetyEvidence = therapyActionsArmed && !powerSaveActive,
                allowAlertPublication = true,
                publishWidgetAfterAcceptance = true
            )
            AutomationCycleIntent.LOCAL_READ_ONLY -> AutomationCyclePolicy(
                runCalculations = true,
                runRemoteRefresh = false,
                therapyWritersAllowed = false,
                allowSensitivityMaintenance = false,
                allowActionRepositoryAccess = false,
                allowLocalSafetyEvidence = false,
                allowAlertPublication = false,
                publishWidgetAfterAcceptance = false
            )
            AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE -> AutomationCyclePolicy(
                runCalculations = true,
                runRemoteRefresh = false,
                therapyWritersAllowed = false,
                allowSensitivityMaintenance = false,
                allowActionRepositoryAccess = false,
                allowLocalSafetyEvidence = true,
                allowAlertPublication = false,
                publishWidgetAfterAcceptance = true
            )
        }

        internal fun restrictCyclePolicyStatic(
            bootstrapPolicy: AutomationCyclePolicy,
            intent: AutomationCycleIntent,
            therapyActionsArmed: Boolean,
            killSwitch: Boolean,
            powerSaveActive: Boolean
        ): AutomationCyclePolicy {
            val current = resolveCyclePolicyStatic(
                intent = intent,
                therapyActionsArmed = therapyActionsArmed,
                killSwitch = killSwitch,
                powerSaveActive = powerSaveActive
            )
            return bootstrapPolicy.copy(
                runCalculations = bootstrapPolicy.runCalculations && current.runCalculations,
                runRemoteRefresh = bootstrapPolicy.runRemoteRefresh && current.runRemoteRefresh,
                therapyWritersAllowed =
                    bootstrapPolicy.therapyWritersAllowed && current.therapyWritersAllowed,
                allowSensitivityMaintenance =
                    bootstrapPolicy.allowSensitivityMaintenance && current.allowSensitivityMaintenance,
                allowActionRepositoryAccess =
                    bootstrapPolicy.allowActionRepositoryAccess && current.allowActionRepositoryAccess,
                allowLocalSafetyEvidence =
                    bootstrapPolicy.allowLocalSafetyEvidence && current.allowLocalSafetyEvidence,
                allowAlertPublication =
                    bootstrapPolicy.allowAlertPublication && current.allowAlertPublication,
                publishWidgetAfterAcceptance =
                    bootstrapPolicy.publishWidgetAfterAcceptance && current.publishWidgetAfterAcceptance
            )
        }

        internal suspend fun requireFreshAcceptedForecastGenerationStatic(
            generationTimestamp: Long,
            acceptedAtTs: Long
        ) {
            currentCoroutineContext().ensureActive()
            if (!AcceptedSensitivityTupleFreshness.isFresh(generationTimestamp, acceptedAtTs)) {
                throw AcceptedForecastGenerationNotFresh(
                    reason = if (generationTimestamp > acceptedAtTs) {
                        "future_forecast_generation"
                    } else {
                        "stale_forecast_generation"
                    }
                )
            }
        }

        internal suspend fun <Reservation : Any, T> runAfterAcceptedSensitivityCycleStatic(
            persistPendingRoomTuple: suspend () -> Unit,
            reserveAccepted: suspend () -> Reservation?,
            commitAcceptedRoomTuple: suspend () -> Unit,
            reconcileAcceptedRoomTuple: suspend () -> Boolean,
            finalizeAccepted: (Reservation) -> Unit,
            abortReservation: (Reservation) -> Unit = {},
            beforeClinicalSideEffects: (suspend () -> Unit)? = null,
            clinicalSideEffects: suspend () -> T
        ): T {
            persistPendingRoomTuple()
            val reservation = requireNotNull(reserveAccepted()) {
                "accepted sensitivity reservation rejected the candidate cycle"
            }
            var finalized = false
            try {
                withContext(NonCancellable) {
                    var commitFailure: Exception? = null
                    try {
                        runBoundedAcceptedDurabilityStepStatic(
                            label = "accepted sensitivity Room commit",
                            timeoutMs = SENSITIVITY_ACCEPTED_DURABILITY_STEP_TIMEOUT_MS,
                            block = commitAcceptedRoomTuple
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (fatal: Error) {
                        throw fatal
                    } catch (operational: Exception) {
                        commitFailure = operational
                    }
                    val reconciled = try {
                        runBoundedAcceptedDurabilityStepStatic(
                            label = "accepted sensitivity Room readback",
                            timeoutMs = SENSITIVITY_ACCEPTED_DURABILITY_STEP_TIMEOUT_MS,
                            block = reconcileAcceptedRoomTuple
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (fatal: Error) {
                        throw fatal
                    } catch (readbackFailure: Exception) {
                        throw IllegalStateException(
                            "accepted sensitivity Room commit is ambiguous: exact readback unavailable",
                            readbackFailure
                        ).also { ambiguous ->
                            commitFailure?.let(ambiguous::addSuppressed)
                        }
                    }
                    if (!reconciled) {
                        throw commitFailure ?: IllegalStateException(
                            "accepted sensitivity Room tuple was not committed"
                        )
                    }
                    finalizeAccepted(reservation)
                    finalized = true
                    beforeClinicalSideEffects?.let { durabilityFence ->
                        runBoundedAcceptedDurabilityStepStatic(
                            label = "accepted sensitivity pre-clinical durability fence",
                            timeoutMs = SENSITIVITY_ACCEPTED_DURABILITY_STEP_TIMEOUT_MS,
                            block = durabilityFence
                        )
                    }
                }
                currentCoroutineContext().ensureActive()
                return clinicalSideEffects()
            } finally {
                if (!finalized) {
                    abortReservation(reservation)
                }
            }
        }

        internal suspend fun prepareProfileEstimatorRevisionStatic(
            isPending: suspend () -> Boolean,
            rebuild: suspend () -> Boolean,
            onNotReady: suspend (String) -> Unit = {}
        ): ProfileEstimatorRevisionPreparation {
            suspend fun reportNotReady(reason: String) {
                try {
                    onNotReady(reason)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Revision preparation remains fail-closed even when audit persistence is unavailable.
                }
            }

            return try {
                if (!isPending()) {
                    ProfileEstimatorRevisionPreparation(wasPending = false, ready = true)
                } else {
                    val rebuilt = rebuild()
                    if (!rebuilt) reportNotReady("active_profile_not_rebuilt")
                    ProfileEstimatorRevisionPreparation(wasPending = true, ready = rebuilt)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportNotReady(failure.message ?: failure::class.simpleName.orEmpty())
                ProfileEstimatorRevisionPreparation(wasPending = true, ready = false)
            }
        }

        internal suspend fun <T> runNonFatalCycleStepStatic(
            timeoutMs: Long? = null,
            onNonFatalFailure: suspend (error: Throwable?, timedOut: Boolean) -> Unit = { _, _ -> },
            block: suspend () -> T
        ): T? {
            return try {
                if (timeoutMs == null) {
                    block()
                } else {
                    require(timeoutMs > 0L)
                    val result = withTimeoutOrNull(timeoutMs) {
                        NonFatalCycleStepResult(block())
                    }
                    if (result == null) {
                        onNonFatalFailure(null, true)
                        null
                    } else {
                        result.value
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (operational: Exception) {
                onNonFatalFailure(operational, false)
                null
            }
        }

        private suspend fun <T> runBoundedAcceptedDurabilityStepStatic(
            label: String,
            timeoutMs: Long,
            block: suspend () -> T
        ): T {
            require(timeoutMs > 0L)
            return withTimeoutOrNull(timeoutMs) {
                BoundedAcceptedDurabilityResult(block())
            }?.value ?: throw IllegalStateException("$label timed out")
        }

        private data class BoundedAcceptedDurabilityResult<T>(val value: T)

        private data class NonFatalCycleStepResult<T>(val value: T)

        internal suspend fun runRemoteRefreshStatic(
            policy: AutomationCyclePolicy,
            refresh: suspend () -> Unit
        ) {
            if (policy.runRemoteRefresh) refresh()
        }

        internal suspend fun runSensitivityMaintenanceStatic(
            policy: AutomationCyclePolicy,
            refreshRealtimeCandidate: suspend () -> Unit,
            evaluateShadowAutoActivation: suspend () -> Unit,
            runRetentionMaintenance: suspend () -> Unit
        ) {
            runSensitivityMaintenanceActionStatic(policy, refreshRealtimeCandidate)
            runSensitivityMaintenanceActionStatic(policy, evaluateShadowAutoActivation)
            runSensitivityMaintenanceActionStatic(policy, runRetentionMaintenance)
        }

        internal suspend fun runSensitivityMaintenanceActionStatic(
            policy: AutomationCyclePolicy,
            action: suspend () -> Unit
        ) {
            if (policy.allowSensitivityMaintenance) action()
        }

        internal fun allowsCalibrationMaintenanceStatic(intent: AutomationCycleIntent): Boolean =
            intent == AutomationCycleIntent.NORMAL

        internal fun requireExactCalibrationIdentityStatic(
            prepared: GlucoseCalibrationCycleIdentity,
            forecast: GlucoseCalibrationCycleIdentity,
            uam: GlucoseCalibrationCycleIdentity,
            targetManager: GlucoseCalibrationCycleIdentity,
            ui: GlucoseCalibrationCycleIdentity
        ) {
            require(
                prepared === forecast &&
                    prepared === uam &&
                    prepared === targetManager &&
                    prepared === ui
            ) { "accepted runtime consumers require one exact calibration identity" }
        }

        internal fun requireAcceptedClinicalForecastsStatic(
            roomTuple: AcceptedSensitivityRoomTuple
        ): AcceptedClinicalForecasts {
            val accepted = roomTuple.accepted
            require(accepted.error == null) { "accepted clinical forecast tuple is not authenticated" }
            val generationTimestamp = requireNotNull(accepted.generationTimestamp) {
                "accepted clinical forecast generation is missing"
            }
            val digest = requireNotNull(accepted.forecastDigest) {
                "accepted clinical forecast digest is missing"
            }
            val rows = listOf(5, 30, 60).map { horizon ->
                requireNotNull(accepted.forecastsByHorizon[horizon]) {
                    "accepted clinical forecast horizon $horizon is missing"
                }
            }
            require(
                SensitivityAcceptedForecastDigest.matches(
                    cycleId = roomTuple.snapshot.forecastCycleId,
                    settingsRevision = roomTuple.snapshot.settingsRevision,
                    forecasts = rows.map { row ->
                        SensitivityAcceptedForecastRow(
                            horizonMinutes = row.horizonMinutes,
                            targetTimestamp = row.timestamp,
                            valueMmol = row.valueMmol,
                            ciLow = row.ciLow,
                            ciHigh = row.ciHigh,
                            modelVersion = row.modelVersion
                        )
                    },
                    decomposition = requireNotNull(accepted.decomposition),
                    expectedDigest = digest
                )
            ) { "accepted clinical forecast digest no longer matches Room" }
            return AcceptedClinicalForecasts(
                forecasts = rows.map { row ->
                    Forecast(
                        ts = row.timestamp,
                        horizonMinutes = row.horizonMinutes,
                        valueMmol = row.valueMmol,
                        ciLow = row.ciLow,
                        ciHigh = row.ciHigh,
                        modelVersion = row.modelVersion
                    )
                },
                generationTimestamp = generationTimestamp,
                digest = digest
            )
        }

        internal fun bindUnifiedUamToAcceptedForecastsStatic(
            unified: UnifiedUamRuntimeSnapshot,
            authority: AcceptedClinicalForecasts
        ): UnifiedUamRuntimeSnapshot {
            requireAcceptedClinicalForecastAuthorityStatic(authority)
            return unified.copy(
                acceptedForecastGenerationTimestamp = authority.generationTimestamp,
                acceptedForecastDigest = authority.digest,
                acceptedForecasts = authority.forecasts.toList()
            )
        }

        internal fun requireUnifiedUamAcceptedForecastAuthorityStatic(
            unified: UnifiedUamRuntimeSnapshot,
            authority: AcceptedClinicalForecasts
        ) {
            requireAcceptedClinicalForecastAuthorityStatic(authority)
            require(unified.acceptedForecastGenerationTimestamp != null) {
                "UAM accepted forecast authority is missing generation"
            }
            require(unified.acceptedForecastDigest != null) {
                "UAM accepted forecast authority is missing digest"
            }
            require(unified.acceptedForecasts.isNotEmpty()) {
                "UAM accepted forecast authority is missing rows"
            }
            require(unified.acceptedForecastGenerationTimestamp == authority.generationTimestamp) {
                "UAM accepted forecast generation mismatch"
            }
            require(unified.acceptedForecastDigest == authority.digest) {
                "UAM accepted forecast digest mismatch"
            }
            require(unified.acceptedForecasts == authority.forecasts) {
                "UAM accepted forecast rows mismatch"
            }
        }

        private fun requireAcceptedClinicalForecastAuthorityStatic(
            authority: AcceptedClinicalForecasts
        ) {
            require(authority.forecasts.map(Forecast::horizonMinutes) == listOf(5, 30, 60)) {
                "accepted clinical forecast authority requires ordered 5/30/60 rows"
            }
            require(resolveAcceptedForecastTimestampStatic(authority.forecasts) == authority.generationTimestamp) {
                "accepted clinical forecast authority generation mismatch"
            }
            require(authority.digest.matches(Regex("[0-9a-f]{64}"))) {
                "accepted clinical forecast authority requires a SHA-256 digest"
            }
        }

        internal fun buildAcceptedClinicalForecastTelemetryRowsStatic(
            nowTs: Long,
            source: String,
            keyPrefix: String,
            authority: AcceptedClinicalForecasts
        ): List<TelemetrySampleEntity> {
            require(nowTs > 0L)
            require(source.isNotBlank())
            require(keyPrefix.isNotBlank())
            requireAcceptedClinicalForecastAuthorityStatic(authority)
            fun numeric(key: String, value: Double, unit: String? = null) = TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = value,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
            fun text(key: String, value: String, unit: String? = null) = TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = value,
                unit = unit,
                quality = "OK"
            )
            return buildList {
                add(
                    numeric(
                        "${keyPrefix}_generation_timestamp",
                        authority.generationTimestamp.toDouble(),
                        "epoch_ms"
                    )
                )
                add(text("${keyPrefix}_digest", authority.digest, "sha256"))
                authority.forecasts.forEach { forecast ->
                    val horizonPrefix = "${keyPrefix}_${forecast.horizonMinutes}m"
                    add(numeric("${horizonPrefix}_target_timestamp", forecast.ts.toDouble(), "epoch_ms"))
                    add(numeric("${horizonPrefix}_value_mmol", forecast.valueMmol, "mmol/L"))
                    add(numeric("${horizonPrefix}_ci_low_mmol", forecast.ciLow, "mmol/L"))
                    add(numeric("${horizonPrefix}_ci_high_mmol", forecast.ciHigh, "mmol/L"))
                    add(text("${horizonPrefix}_model_version", forecast.modelVersion))
                }
            }
        }

        internal suspend fun publishAcceptedCycleStateStatic(
            intent: AutomationCycleIntent,
            acceptedSnapshot: SensitivityRuntimeSnapshot,
            publishUiTelemetry: suspend (SensitivityRuntimeSnapshot) -> Unit,
            runLocalMaintenance: suspend () -> Unit
        ) {
            if (intent == AutomationCycleIntent.LOCAL_READ_ONLY) return
            publishUiTelemetry(acceptedSnapshot)
            if (intent == AutomationCycleIntent.NORMAL) runLocalMaintenance()
        }

        internal suspend fun <Decision> runAcceptedClinicalCalculationFanOutStatic(
            intent: AutomationCycleIntent,
            policy: AutomationCyclePolicy,
            acceptedSnapshot: SensitivityRuntimeSnapshot,
            acceptedClinicalForecastAuthority: AcceptedClinicalForecasts,
            unifiedUam: UnifiedUamRuntimeSnapshot,
            writeUamCarbs: suspend () -> Unit,
            evaluateRulesAndTargetManager: suspend (
                SensitivityRuntimeConsumerContext,
                Boolean
            ) -> List<Decision>,
            assessAlertCause: suspend (
                SensitivityRuntimeConsumerContext,
                UnifiedUamRuntimeSnapshot
            ) -> Unit,
            publishAlerts: suspend (
                SensitivityRuntimeConsumerContext,
                UnifiedUamRuntimeSnapshot
            ) -> Unit,
            publishAcceptedForecast: suspend () -> Unit
        ): List<Decision> {
            if (intent == AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE) {
                require(
                    policy.runCalculations &&
                        !policy.runRemoteRefresh &&
                        !policy.therapyWritersAllowed &&
                        !policy.allowSensitivityMaintenance &&
                        !policy.allowActionRepositoryAccess &&
                        policy.allowLocalSafetyEvidence &&
                        !policy.allowAlertPublication &&
                        policy.publishWidgetAfterAcceptance
                ) { "source-change policy must disable writers and alerts" }
            }
            requireAcceptedClinicalForecastAuthorityStatic(acceptedClinicalForecastAuthority)
            require(unifiedUam.sensitivityCycleId == acceptedSnapshot.forecastCycleId) {
                "accepted clinical fan-out UAM cycle mismatch"
            }
            require(unifiedUam.sensitivitySettingsRevision == acceptedSnapshot.settingsRevision) {
                "accepted clinical fan-out UAM revision mismatch"
            }
            requireUnifiedUamAcceptedForecastAuthorityStatic(
                unified = unifiedUam,
                authority = acceptedClinicalForecastAuthority
            )
            val fanOut = SensitivityRuntimeFanOut(acceptedSnapshot)
            val decisions = runTherapyStageStatic(
                policy = policy,
                writeUamCarbs = writeUamCarbs,
                evaluateRulesAndTargetManager = { externalWritesAllowed ->
                    evaluateRulesAndTargetManager(
                        fanOut.contextFor(SensitivityRuntimeConsumer.TARGET_MANAGER),
                        externalWritesAllowed
                    )
                }
            )
            val alertContext = fanOut.contextFor(SensitivityRuntimeConsumer.ALERT_CAUSE)
            when {
                policy.allowAlertPublication -> publishAlerts(alertContext, unifiedUam)
                intent == AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE -> {
                    assessAlertCause(alertContext, unifiedUam)
                }
            }
            if (policy.publishWidgetAfterAcceptance) publishAcceptedForecast()
            return decisions
        }

        internal suspend fun publishAcceptedForecastConsumerStatic(
            intent: AutomationCycleIntent,
            onAcceptedForecastChanged: (suspend () -> Unit)?
        ) {
            if (intent == AutomationCycleIntent.LOCAL_READ_ONLY) return
            onAcceptedForecastChanged?.invoke()
        }

        internal suspend fun commitAcceptedSensitivityCycleStatic(
            db: CopilotDatabase,
            acceptedAtTs: Long,
            snapshot: SensitivityRuntimeSnapshot,
            forecastRows: List<io.aaps.copilot.data.local.entity.ForecastEntity>,
            expectedIsfCrInputGeneration: IsfCrInputGeneration
        ) {
            db.withTransaction {
                require(expectedIsfCrInputGeneration.modelRevision > 0L) {
                    "expected ISF/CR base model revision is invalid"
                }
                require(expectedIsfCrInputGeneration.profileRevision > 0L) {
                    "expected ISF/CR profile revision is invalid"
                }
                val currentGeneration = isfCrInputGenerationOrNull(
                    modelUpdatedAt = db.isfCrModelStateDao().active()?.updatedAt,
                    profileTimestamp = db.profileEstimateDao().active()?.timestamp
                )
                require(currentGeneration == expectedIsfCrInputGeneration) {
                    "ISF/CR input generation changed before accepted cycle publication"
                }
                forecastRows.forEach { row ->
                    db.forecastDao().deleteByTimestampAndHorizon(
                        timestamp = row.timestamp,
                        horizonMinutes = row.horizonMinutes
                    )
                }
                db.forecastDao().insertAll(forecastRows)
                require(
                    db.telemetryDao().commitAcceptedSensitivityTuple(
                        timestamp = acceptedAtTs,
                        cycleId = snapshot.forecastCycleId,
                        settingsRevision = snapshot.settingsRevision
                    )
                ) { "accepted sensitivity Room publication commit was rejected" }
            }
        }

        internal suspend fun allocateAcceptedSensitivityMarkerStatic(
            telemetryDao: TelemetryDao,
            wallClockTs: Long,
            authoritativeNow: () -> Long = { System.currentTimeMillis() },
            suspendForMs: suspend (Long) -> Unit = { delayMs -> delay(delayMs) }
        ): Long {
            require(wallClockTs > 0L) { "accepted sensitivity wall clock must be positive" }
            val latestMarkerTs = telemetryDao.latestTimestampBySourceAndKey(
                source = SENSITIVITY_ACCEPTED_SOURCE,
                key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
            )
            val markerTs = if (latestMarkerTs == null || latestMarkerTs < wallClockTs) {
                wallClockTs
            } else {
                val requiredMarkerTs = try {
                    Math.addExact(latestMarkerTs, 1L)
                } catch (overflow: ArithmeticException) {
                    throw IllegalStateException("accepted sensitivity marker sequence overflow", overflow)
                }
                val aheadMs = try {
                    Math.subtractExact(requiredMarkerTs, wallClockTs)
                } catch (overflow: ArithmeticException) {
                    throw IllegalStateException("accepted sensitivity marker future distance overflow", overflow)
                }
                require(aheadMs in 1L..SENSITIVITY_ACCEPTED_MARKER_MAX_AHEAD_MS) {
                    "accepted sensitivity marker is too far in the future"
                }
                suspendForMs(aheadMs)
                requiredMarkerTs
            }
            val actualNow = authoritativeNow()
            require(actualNow >= markerTs) {
                "accepted sensitivity clock did not reach required marker"
            }
            return markerTs
        }

        internal suspend fun <T> runTherapyStageStatic(
            policy: AutomationCyclePolicy,
            writeUamCarbs: suspend () -> Unit,
            evaluateRulesAndTargetManager: suspend (writersAllowed: Boolean) -> T
        ): T {
            if (policy.therapyWritersAllowed) writeUamCarbs()
            return evaluateRulesAndTargetManager(policy.therapyWritersAllowed)
        }

        internal data class NormalCycleExecution<T>(
            val acquired: Boolean,
            val value: T?
        )

        private data class SerializedCycleExecution<T>(val value: T)

        internal suspend fun <T> runNormalCycleIfIdleStatic(
            mutex: Mutex,
            block: suspend () -> T
        ): NormalCycleExecution<T> {
            val owner = Any()
            if (!mutex.tryLock(owner)) return NormalCycleExecution(acquired = false, value = null)
            return try {
                NormalCycleExecution(acquired = true, value = block())
            } finally {
                mutex.unlock(owner)
            }
        }

        internal suspend fun <T> runReactiveCycleUnderLeaseStatic(
            mutex: Mutex,
            block: suspend () -> T
        ): T = mutex.withLock { block() }

        internal suspend fun <T> runSerializedSensitivitySourceChangeStatic(
            mutex: Mutex,
            lockTimeoutMs: Long,
            block: suspend () -> T
        ): T {
            require(lockTimeoutMs > 0L)
            val acquired = withTimeoutOrNull(lockTimeoutMs) {
                mutex.lock()
                true
            } ?: false
            if (!acquired) throw IllegalStateException(
                "sensitivity source change timed out waiting for cycle lease"
            )
            return try {
                block()
            } finally {
                mutex.unlock()
            }
        }

        internal suspend fun <Mutation, Result> applySensitivitySettingsChangeUnderCycleLeaseStatic(
            mutex: Mutex,
            lockTimeoutMs: Long,
            mutateSettings: suspend () -> Mutation,
            runAcceptedCycle: suspend (Mutation) -> Result
        ): Result = runSerializedSensitivitySourceChangeStatic(mutex, lockTimeoutMs) {
            val mutation = mutateSettings()
            runAcceptedCycle(mutation)
        }

        internal suspend fun <Mutation, Result> applySensitivitySourceChangeUnderCycleLeaseStatic(
            mutex: Mutex,
            lockTimeoutMs: Long,
            mutateSource: suspend () -> Mutation,
            runAcceptedCycle: suspend (Mutation) -> Result
        ): Result = applySensitivitySettingsChangeUnderCycleLeaseStatic(
            mutex = mutex,
            lockTimeoutMs = lockTimeoutMs,
            mutateSettings = mutateSource,
            runAcceptedCycle = runAcceptedCycle
        )

        internal fun resolveAcceptedForecastTimestampStatic(
            forecasts: List<Forecast>
        ): Long? {
            val requiredHorizons = setOf(5, 30, 60)
            val grouped = forecasts
                .filter { it.horizonMinutes in requiredHorizons }
                .groupBy(Forecast::horizonMinutes)
            if (grouped.keys != requiredHorizons) return null
            if (requiredHorizons.any { grouped[it]?.size != 1 }) return null
            return requiredHorizons.mapNotNull { horizon ->
                val forecast = grouped.getValue(horizon).single()
                runCatching {
                    Math.subtractExact(forecast.ts, Math.multiplyExact(horizon.toLong(), 60_000L))
                }.getOrNull()
            }.takeIf { it.size == requiredHorizons.size }
                ?.distinct()
                ?.singleOrNull()
        }

        internal fun selectCloudCompatibleControlForecastsStatic(
            local: List<Forecast>,
            cloud: List<Forecast>
        ): List<Forecast> {
            if (cloud.isEmpty()) return local
            // CloudForecast currently carries no verifiable therapy decomposition. Keep it out
            // of control horizons until the wire contract can prove local-physiology parity.
            return local
        }

        @Suppress("UNUSED_PARAMETER")
        internal fun selectAutomationControlForecastsStatic(
            local: List<Forecast>,
            forecastGateway: ForecastGateway?
        ): List<Forecast> {
            // Automated control is local-only until the gateway contract carries a verified
            // insulin/carb decomposition. The parameter makes accidental invocation testable.
            return local
        }

        internal fun buildInsulinCycleFanOutStatic(
            context: InsulinCycleContext
        ): InsulinCycleFanOut = InsulinCycleFanOut(
            revision = context.cycleTimestamp,
            context = context
        )

        private const val ACTIVITY_TARGET_LOW_RISK_THRESHOLD_MMOL = 4.4

        internal fun mapDryRunTherapyEventStatic(
            row: TherapyEventEntity,
            gson: Gson
        ): TherapyEvent = row.toDomain(gson)

        internal fun activityReturnToBaseRequestedStatic(
            plannedActivityTargetOccurrence: PlannedActivityOccurrence?,
            activityContextSource: ActivityContextSource
        ): Boolean = when (activityContextSource) {
            ActivityContextSource.DISABLED,
            ActivityContextSource.MEASURED,
            ActivityContextSource.SCHEDULED,
            ActivityContextSource.UNKNOWN -> plannedActivityTargetOccurrence == null
        }

        internal fun isLowGlucoseSafetyReasonStatic(reason: String): Boolean =
            reason.contains("safety_force_high") ||
                reason.contains("safety_hypo_guard") ||
                reason.contains("safety_raise_target_to_five") ||
                reason.contains("hypo_preemptive_") ||
                reason.contains("low_glucose_safety_latch")

        internal fun resolveLowGlucoseForecastMinimumStatic(
            currentGlucoseMmol: Double?,
            forecasts: List<Forecast>
        ): Double? = buildList {
            currentGlucoseMmol?.takeIf(Double::isFinite)?.let(::add)
            forecasts.forEach { forecast ->
                forecast.valueMmol.takeIf(Double::isFinite)?.let(::add)
                forecast.ciLow.takeIf(Double::isFinite)?.let(::add)
            }
        }.minOrNull()

        internal fun protectLowGlucoseTargetStatic(
            action: ActionProposal,
            state: LowGlucoseTargetSafetyLatch.State,
            minTargetMmol: Double = MIN_TARGET_MMOL,
            maxTargetMmol: Double = MAX_TARGET_MMOL
        ): ActionProposal {
            if (!action.type.equals("temp_target", ignoreCase = true)) return action
            require(minTargetMmol.isFinite() && maxTargetMmol.isFinite() && minTargetMmol <= maxTargetMmol)
            if (!action.targetMmol.isFinite()) return action
            val protectedTarget = state.protectedTargetMmol
            if (state.latched && protectedTarget != null && !protectedTarget.isFinite()) {
                return action.copy(targetMmol = protectedTarget)
            }
            val raised = state.latched && protectedTarget != null && action.targetMmol < protectedTarget
            val protectedAction = if (raised) {
                val reason = if (action.reason.contains("low_glucose_safety_latch")) {
                    action.reason
                } else {
                    "${action.reason}|low_glucose_safety_latch"
                }
                action.copy(targetMmol = checkNotNull(protectedTarget), reason = reason)
            } else {
                action
            }
            // A historical latch must not reintroduce a target above a newly configured ceiling.
            // This projects a proposal only; Target Manager still checks the actual active anchor.
            return protectedAction.copy(
                targetMmol = protectedAction.targetMmol.coerceIn(minTargetMmol, maxTargetMmol)
            )
        }

        internal fun resolveIobSampleProvenanceStatic(
            selected: TelemetrySampleEntity?,
            nowTs: Long,
            freshnessMs: Long = UAM_IOB_SAMPLE_FRESHNESS_MS
        ): IobSampleProvenance {
            val sampleTs = selected?.timestamp?.takeIf { it in 0..nowTs }
            val ageMs = sampleTs?.let { nowTs - it }
            return IobSampleProvenance(
                sampleTs = sampleTs,
                ageMinutes = ageMs?.div(60_000.0),
                fresh = ageMs != null && freshnessMs >= 0L && ageMs <= freshnessMs
            )
        }

        internal fun selectStrictAapsIobStatic(
            rows: List<TelemetrySampleEntity>,
            directPreferenceWindowMs: Long = DIRECT_AAPS_THERAPY_PREFERENCE_WINDOW_MS
        ): TelemetrySampleEntity? {
            if (rows.isEmpty()) return null
            val candidates = rows.filter { row ->
                row.valueDouble?.isFinite() == true &&
                    row.valueDouble in -30.0..30.0 &&
                    row.source in IOB_STRICT_ALLOWED_SOURCES &&
                    isStrictIobKeyStatic(row.key)
            }
            val selected = selectPreferredDirectAapsSampleStatic(candidates, directPreferenceWindowMs)
                ?: return null
            return candidates.firstOrNull { row ->
                row.source == selected.source &&
                    row.timestamp == selected.timestamp &&
                    normalizeStrictTherapyKeyStatic(row.key) == "iob_net_units"
            } ?: selected
        }

        internal fun selectStrictAapsCobStatic(
            rows: List<TelemetrySampleEntity>,
            directPreferenceWindowMs: Long = DIRECT_AAPS_THERAPY_PREFERENCE_WINDOW_MS
        ): TelemetrySampleEntity? {
            if (rows.isEmpty()) return null
            val candidates = rows.filter { row ->
                row.valueDouble?.isFinite() == true &&
                    row.valueDouble in 0.0..400.0 &&
                    row.source in IOB_STRICT_ALLOWED_SOURCES &&
                    normalizeStrictTherapyKeyStatic(row.key) in COB_STRICT_ALLOWED_KEYS
            }
            val selected = selectPreferredDirectAapsSampleStatic(candidates, directPreferenceWindowMs)
                ?: return null
            // Canonical values replace earlier broadcasts in the same bucket; raw aliases
            // retain value-hashed IDs, whose lexical order is not an update sequence.
            return candidates.firstOrNull { row ->
                row.source == selected.source && row.timestamp == selected.timestamp &&
                    row.key == "cob_grams"
            } ?: selected
        }

        internal fun selectStrictAapsIsfStatic(
            rows: List<TelemetrySampleEntity>,
            directPreferenceWindowMs: Long = DIRECT_AAPS_THERAPY_PREFERENCE_WINDOW_MS
        ): TelemetrySampleEntity? {
            if (rows.isEmpty()) return null
            val candidates = rows.filter { row ->
                row.valueDouble?.isFinite() == true &&
                    row.valueDouble in 0.8..18.0 &&
                    row.source in ISF_AAPS_ALLOWED_SOURCES &&
                    normalizeStrictTherapyKeyStatic(row.key) in ISF_AAPS_ALLOWED_KEYS
            }
            return selectPreferredDirectAapsSampleStatic(candidates, directPreferenceWindowMs)
        }

        internal fun selectStrictAapsCrStatic(
            rows: List<TelemetrySampleEntity>,
            directPreferenceWindowMs: Long = DIRECT_AAPS_THERAPY_PREFERENCE_WINDOW_MS
        ): TelemetrySampleEntity? {
            if (rows.isEmpty()) return null
            val candidates = rows.filter { row ->
                row.valueDouble?.isFinite() == true &&
                    row.valueDouble in 2.0..60.0 &&
                    row.source in CR_AAPS_ALLOWED_SOURCES &&
                    normalizeStrictTherapyKeyStatic(row.key) in CR_AAPS_ALLOWED_KEYS
            }
            return selectPreferredDirectAapsSampleStatic(candidates, directPreferenceWindowMs)
        }

        internal fun sensitivityRuntimeApplicationFromSnapshotStatic(
            snapshot: SensitivityRuntimeSnapshot
        ): SensitivityRuntimeApplication {
            fun metric(
                requested: IsfRuntimeSourcePreference,
                resolved: SensitivityResolvedSource,
                value: Double,
                confidence: Double,
                fallbackReason: String?
            ): MetricRuntimeApplication {
                val runtimeResolved = when (resolved) {
                    SensitivityResolvedSource.AAPS -> MetricRuntimeResolvedSource.AAPS
                    SensitivityResolvedSource.EVIDENCE_BLEND -> MetricRuntimeResolvedSource.EVIDENCE_BLEND
                    SensitivityResolvedSource.COPILOT_NATIVE -> MetricRuntimeResolvedSource.COPILOT_NATIVE
                }
                return MetricRuntimeApplication(
                    decision = MetricRuntimeSourceDecision(
                        requested = requested,
                        resolved = runtimeResolved,
                        overrideValue = value,
                        fallbackReason = fallbackReason
                    ),
                    override = SensitivityMetricOverride(
                        value = value,
                        confidence = confidence.coerceIn(0.0, 1.0),
                        minConfidenceRequired = 0.0,
                        blendWeight = 1.0,
                        source = resolved.name.lowercase(Locale.US),
                        authoritative = true
                    )
                )
            }
            return SensitivityRuntimeApplication(
                isf = metric(
                    snapshot.isf.requested,
                    snapshot.isf.resolved,
                    snapshot.isf.effective,
                    snapshot.isf.confidence,
                    snapshot.isf.fallbackReason
                ),
                cr = metric(
                    snapshot.cr.requested,
                    snapshot.cr.resolved,
                    snapshot.cr.effective,
                    snapshot.cr.confidence,
                    snapshot.cr.fallbackReason
                )
            )
        }

        internal fun validateSensitivityRuntimeDispatchStatic(
            context: SensitivityRuntimeConsumerContext,
            expectedConsumer: SensitivityRuntimeConsumer,
            currentSettingsRevision: Long,
            cycleSnapshot: SensitivityRuntimeSnapshot?
        ) {
            require(context.consumer == expectedConsumer) {
                "unexpected sensitivity consumer: expected=$expectedConsumer actual=${context.consumer}"
            }
            require(cycleSnapshot === context.snapshot) {
                "sensitivity snapshot was replaced before dispatch"
            }
            require(context.snapshot.settingsRevision == currentSettingsRevision) {
                "sensitivity settings changed before dispatch"
            }
        }

        private fun metricPreferenceCodeStatic(preference: IsfRuntimeSourcePreference): Double = when (preference) {
            IsfRuntimeSourcePreference.AAPS -> 1.0
            IsfRuntimeSourcePreference.EVIDENCE -> 2.0
            IsfRuntimeSourcePreference.COPILOT -> 3.0
        }

        private fun metricResolvedCodeStatic(source: MetricRuntimeResolvedSource): Double = when (source) {
            MetricRuntimeResolvedSource.AAPS -> 1.0
            MetricRuntimeResolvedSource.EVIDENCE_BLEND -> 2.0
            MetricRuntimeResolvedSource.COPILOT_NATIVE -> 3.0
        }

        private fun selectPreferredDirectAapsSampleStatic(
            candidates: List<TelemetrySampleEntity>,
            directPreferenceWindowMs: Long
        ): TelemetrySampleEntity? {
            val newest = candidates.maxWithOrNull(
                compareBy<TelemetrySampleEntity> { it.timestamp }
                    .thenBy { IOB_STRICT_SOURCE_PRIORITY[it.source] ?: 0 }
                    .thenBy { it.id }
            ) ?: return null
            val boundedWindowMs = directPreferenceWindowMs.coerceAtLeast(0L)
            return candidates
                .asSequence()
                .filter { row ->
                    row.source == "aaps_broadcast" &&
                        row.timestamp <= newest.timestamp &&
                        newest.timestamp - row.timestamp <= boundedWindowMs
                }
                .maxWithOrNull(compareBy<TelemetrySampleEntity> { it.timestamp }.thenBy { it.id })
                ?: newest
        }

        private fun isStrictIobKeyStatic(key: String): Boolean {
            val normalized = normalizeStrictTherapyKeyStatic(key)
            return normalized in IOB_STRICT_ALLOWED_KEYS ||
                normalized.contains("insulinonboard") ||
                normalized.endsWith("_iob")
        }

        private fun normalizeStrictTherapyKeyStatic(key: String): String = TherapyPayloadLookup.normalizeKey(key)

        private fun resolveInsulinRuntimeSourceMarkerStatic(
            rows: List<TelemetrySampleEntity>
        ): InsulinRuntimeSource? {
            val codeRow = rows.firstOrNull { it.key == "iob_runtime_source_code" }
            val nameRow = rows.firstOrNull { it.key == "iob_runtime_source" }
            if (codeRow == null && nameRow == null) return null

            val codeSource = codeRow?.let { row ->
                val code = row.valueDouble?.takeIf(Double::isFinite) ?: return null
                InsulinRuntimeSource.values().firstOrNull { source ->
                    InsulinRuntimeSnapshotResolver.sourceCode(source) == code
                } ?: return null
            }
            val nameSource = nameRow?.let { row ->
                val name = row.valueText?.trim()?.uppercase(Locale.US) ?: return null
                runCatching { InsulinRuntimeSource.valueOf(name) }.getOrNull() ?: return null
            }
            if (codeSource != null && nameSource != null && codeSource != nameSource) return null
            return codeSource ?: nameSource
        }

        internal fun isInsulinSnapshotSafetyQualifiedStatic(
            snapshot: InsulinRuntimeSnapshot?
        ): Boolean {
            snapshot ?: return false
            if (
                snapshot.timestamp <= 0L ||
                !snapshot.netIobUnits.isFinite() ||
                !snapshot.effectivePositiveIobUnits.isFinite() ||
                snapshot.effectivePositiveIobUnits !in 0.0..30.0 ||
                snapshot.evidenceTimestamp?.let { it <= 0L || it > snapshot.timestamp } != false
            ) {
                return false
            }
            val (minimumConfidence, minimumCoverage) = when (snapshot.source) {
                InsulinRuntimeSource.AAPS_COMPONENTS -> 0.95 to 0.95
                InsulinRuntimeSource.LEGACY_AAPS -> 0.60 to 0.95
                InsulinRuntimeSource.EXTERNAL_ESTIMATE -> 0.55 to 0.70
                InsulinRuntimeSource.LOCAL_ESTIMATE -> return false
            }
            val componentsComplete = snapshot.source != InsulinRuntimeSource.AAPS_COMPONENTS ||
                listOf(snapshot.bolusIobUnits, snapshot.basalIobUnits, snapshot.insulinActivity)
                    .all { it?.isFinite() == true }
            return componentsComplete &&
                snapshot.confidence.isFinite() &&
                snapshot.confidence >= minimumConfidence &&
                snapshot.therapyCoverage.isFinite() &&
                snapshot.therapyCoverage >= minimumCoverage
        }

        internal fun resolveUamRuntimeQualityStatic(
            sensorQualityScore: Double,
            sensorBlocked: Boolean,
            nowTs: Long,
            effectiveDiaHours: Double,
            insulinEvidenceTimestamps: List<Long>,
            iobSampleTs: Long?,
            iobUnits: Double,
            effectiveCobGrams: Double,
            externalCobGrams: Double?,
            carbTherapyAvailable: Boolean,
            iobFreshnessMaxMinutes: Int = 15,
            insulinCycleContext: InsulinCycleContext? = null,
            insulinSnapshot: InsulinRuntimeSnapshot? = null
        ): UamRuntimeQualityAssessment {
            val reasons = linkedSetOf<String>()
            val boundedSensorScore = sensorQualityScore
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, 1.0)
                ?: 0.0.also { reasons += "invalid_sensor_quality" }
            val sensorTrust = if (sensorBlocked) {
                reasons += "sensor_blocked"
                0.0
            } else {
                boundedSensorScore
            }

            val qualifiedSnapshot = (insulinCycleContext?.snapshot ?: insulinSnapshot)
                ?.takeIf(::isInsulinSnapshotSafetyQualifiedStatic)
            if (qualifiedSnapshot == null) reasons += "iob_snapshot_unqualified"
            val effectiveIobUnits = qualifiedSnapshot?.effectivePositiveIobUnits ?: Double.NaN
            val effectiveIobSampleTs = qualifiedSnapshot?.timestamp
            val iobValid = effectiveIobUnits.isFinite() && effectiveIobUnits >= 0.0
            val cobValid = effectiveCobGrams.isFinite() && effectiveCobGrams >= 0.0
            val iobSampleFresh = effectiveIobSampleTs != null &&
                effectiveIobSampleTs in 0..nowTs &&
                nowTs - effectiveIobSampleTs <= iobFreshnessMaxMinutes.coerceIn(5, 60) * 60_000L
            if (!iobSampleFresh) reasons += "iob_sample_missing_or_stale"
            val diaEvidenceCoverage = if (
                iobValid && effectiveIobUnits < UAM_MEANINGFUL_IOB_UNITS && iobSampleFresh
            ) {
                1.0
            } else if (nowTs < 0L || !effectiveDiaHours.isFinite() || effectiveDiaHours <= 0.0) {
                reasons += "invalid_insulin_evidence_window"
                0.0
            } else {
                val diaWindowMs = (effectiveDiaHours.coerceIn(0.5, 24.0) * 60.0 * 60_000.0).toLong()
                val causalEvidence = insulinEvidenceTimestamps
                    .asSequence()
                    .filter { it in 0..nowTs }
                    .distinct()
                    .sorted()
                    .toList()
                val recentEvidence = causalEvidence.filter { nowTs - it <= diaWindowMs }
                when {
                    recentEvidence.isEmpty() -> {
                        reasons += if (causalEvidence.isEmpty()) {
                            "iob_without_causal_insulin"
                        } else {
                            "iob_without_recent_dia_insulin"
                        }
                        0.0
                    }
                    recentEvidence.size >= 2 &&
                        nowTs - recentEvidence.last() <= UAM_STRONG_INSULIN_FRESHNESS_MS -> 1.0
                    else -> {
                        reasons += "partial_causal_insulin_coverage"
                        UAM_PARTIAL_INSULIN_THERAPY_COVERAGE
                    }
                }
            }
            val insulinCoverageRaw = when {
                !iobValid -> {
                    reasons += "invalid_iob"
                    0.0
                }
                effectiveIobUnits < UAM_MEANINGFUL_IOB_UNITS && iobSampleFresh -> 1.0
                else -> diaEvidenceCoverage
            }
            val insulinCoverage = qualifiedSnapshot
                ?.let { min(insulinCoverageRaw, it.therapyCoverage.coerceIn(0.0, 1.0)) }
                ?: 0.0
            val hasMeaningfulExternalCob = externalCobGrams
                ?.takeIf { it.isFinite() }
                ?.let { it > UAM_MEANINGFUL_COB_GRAMS } == true
            val announcedCarbCoverage = when {
                !cobValid -> {
                    reasons += "invalid_effective_cob"
                    0.0
                }
                effectiveCobGrams <= UAM_MEANINGFUL_COB_GRAMS -> 1.0
                carbTherapyAvailable -> 1.0
                else -> 0.0
            }
            val carbCoverage = when {
                !cobValid -> 0.0
                hasMeaningfulExternalCob && !carbTherapyAvailable -> {
                    reasons += "external_cob_without_causal_carbs"
                    UAM_DIAGNOSTIC_ONLY_THERAPY_COVERAGE
                }
                effectiveCobGrams > UAM_MEANINGFUL_COB_GRAMS && !carbTherapyAvailable -> {
                    reasons += "cob_without_causal_carbs"
                    UAM_DIAGNOSTIC_ONLY_THERAPY_COVERAGE
                }
                else -> 1.0
            }
            return UamRuntimeQualityAssessment(
                sensorTrust = sensorTrust,
                therapyCoverage = min(insulinCoverage, carbCoverage),
                announcedCarbCoverage = announcedCarbCoverage,
                sensorBlocked = sensorBlocked,
                reasons = reasons
            )
        }

        internal fun buildUnifiedUamEpisodeIdStatic(algorithmVersion: String, onsetTs: Long?): String {
            val normalizedVersion = algorithmVersion.trim()
            if (normalizedVersion.isEmpty() || onsetTs == null || onsetTs < 0L) return ""
            return "$normalizedVersion:${onsetTs / UAM_EPISODE_BUCKET_MS}"
        }

        internal fun projectUnifiedUamRuntimeStatic(
            diagnostics: HybridPredictionEngine.V3Diagnostics?,
            effectiveCobGrams: Double,
            sensorBlocked: Boolean,
            sensitivityRuntime: SensitivityRuntimeConsumerContext,
            calibrationIdentity: GlucoseCalibrationCycleIdentity? = null
        ): UnifiedUamRuntimeSnapshot {
            require(sensitivityRuntime.consumer == SensitivityRuntimeConsumer.UAM) {
                "unified UAM projection requires UAM sensitivity context"
            }
            val sensitivitySnapshot = sensitivityRuntime.snapshot
            val boundedEffectiveCob = effectiveCobGrams
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: Double.NaN
            if (diagnostics == null) {
                return UnifiedUamRuntimeSnapshot(
                    timestamp = 0L,
                    state = "DISABLED",
                    flag = 0.0,
                    controlFlag = 0.0,
                    confidence = 0.0,
                    impactMmol5 = 0.0,
                    signedResidualMmol5 = 0.0,
                    shortAverageDeltaMmol5 = 0.0,
                    forecastComponent60Mmol = 0.0,
                    equivalentCarbsGrams = null,
                    supportedLowerBoundGrams = null,
                    onsetTs = null,
                    firstDetectionTs = null,
                    activeSinceTs = null,
                    supportStableBuckets = 0,
                    lowerBoundStableBuckets = 0,
                    sensorTrust = 0.0,
                    therapyCoverage = 0.0,
                    source = "disabled",
                    reasons = setOf("forecast_diagnostics_missing"),
                    algorithmVersion = "unified-uam-v1",
                    episodeId = "",
                    effectiveCobGrams = boundedEffectiveCob,
                    sensorBlocked = sensorBlocked,
                    sensitivityCycleId = sensitivitySnapshot.forecastCycleId,
                    sensitivitySettingsRevision = sensitivitySnapshot.settingsRevision,
                    sensitivityIsfMmolPerUnit = sensitivitySnapshot.isf.effective,
                    sensitivityCrGramPerUnit = sensitivitySnapshot.cr.effective,
                    calibrationIdentity = calibrationIdentity
                )
            }
            require(diagnostics.unifiedUamSensitivityCycleId == sensitivitySnapshot.forecastCycleId) {
                "UAM diagnostics sensitivity cycle mismatch"
            }
            require(diagnostics.unifiedUamSensitivitySettingsRevision == sensitivitySnapshot.settingsRevision) {
                "UAM diagnostics sensitivity revision mismatch"
            }
            require(diagnostics.unifiedUamSensitivityIsfMmolPerUnit == sensitivitySnapshot.isf.effective) {
                "UAM diagnostics ISF mismatch"
            }
            require(diagnostics.unifiedUamSensitivityCrGramPerUnit == sensitivitySnapshot.cr.effective) {
                "UAM diagnostics CR mismatch"
            }
            val episodeId = buildUnifiedUamEpisodeIdStatic(
                algorithmVersion = diagnostics.unifiedUamAlgorithmVersion,
                onsetTs = diagnostics.unifiedUamOnsetTs
            )
            return UnifiedUamRuntimeSnapshot(
                timestamp = diagnostics.unifiedUamTimestamp,
                state = diagnostics.unifiedUamState,
                flag = if (diagnostics.unifiedUamActiveForForecast) 1.0 else 0.0,
                controlFlag = if (diagnostics.unifiedUamActiveForControl) 1.0 else 0.0,
                confidence = diagnostics.unifiedUamConfidence,
                impactMmol5 = diagnostics.unifiedUamImpactMmol5,
                signedResidualMmol5 = diagnostics.unifiedUamSignedResidualMmol5,
                shortAverageDeltaMmol5 = diagnostics.unifiedUamShortAverageDeltaMmol5,
                forecastComponent60Mmol = diagnostics.rawUnifiedUamStep.drop(1).take(12).sum(),
                equivalentCarbsGrams = diagnostics.unifiedUamEquivalentCarbsGrams,
                supportedLowerBoundGrams = diagnostics.unifiedUamLowerBoundCarbsGrams,
                onsetTs = diagnostics.unifiedUamOnsetTs,
                firstDetectionTs = diagnostics.unifiedUamFirstDetectionTs,
                activeSinceTs = diagnostics.unifiedUamActiveSinceTs,
                supportStableBuckets = diagnostics.unifiedUamSupportStableBuckets,
                lowerBoundStableBuckets = diagnostics.unifiedUamLowerBoundStableBuckets,
                sensorTrust = diagnostics.unifiedUamSensorTrust,
                therapyCoverage = diagnostics.unifiedUamTherapyCoverage,
                source = diagnostics.unifiedUamSource,
                reasons = diagnostics.unifiedUamReasons,
                algorithmVersion = diagnostics.unifiedUamAlgorithmVersion,
                episodeId = episodeId,
                effectiveCobGrams = boundedEffectiveCob,
                sensorBlocked = sensorBlocked,
                sensitivityCycleId = sensitivitySnapshot.forecastCycleId,
                sensitivitySettingsRevision = sensitivitySnapshot.settingsRevision,
                sensitivityIsfMmolPerUnit = sensitivitySnapshot.isf.effective,
                sensitivityCrGramPerUnit = sensitivitySnapshot.cr.effective,
                calibrationIdentity = calibrationIdentity
            )
        }

        internal fun resolveUnifiedUamExportRuntimeRouteStatic(
            settings: AppSettings
        ): UnifiedUamExportRuntimeRoute = when (UamExportUiModePolicy.resolve(settings)) {
            UamExportUiMode.OFF -> UnifiedUamExportRuntimeRoute(
                invokeCoordinator = false,
                dryRun = true
            )
            UamExportUiMode.OBSERVE -> UnifiedUamExportRuntimeRoute(
                invokeCoordinator = true,
                dryRun = true
            )
            UamExportUiMode.AUTO -> UnifiedUamExportRuntimeRoute(
                invokeCoordinator = true,
                dryRun = false
            )
        }

        internal suspend fun dispatchUnifiedUamExportStatic(
            settings: AppSettings,
            candidate: UamExportPolicyInput,
            coordinator: UamExportCoordinator
        ): UnifiedUamExportDispatch {
            val route = resolveUnifiedUamExportRuntimeRouteStatic(settings)
            val outcome = if (route.invokeCoordinator) {
                coordinator.processUnified(
                    candidate = candidate,
                    enabled = true,
                    dryRun = route.dryRun
                )
            } else {
                UamExportCoordinator.Outcome(
                    events = emptyList(),
                    remoteEntries = emptyList(),
                    reason = "disabled"
                )
            }
            return UnifiedUamExportDispatch(route = route, outcome = outcome)
        }

        internal fun resolveUnifiedUamExportTelemetryStatic(
            nowTs: Long,
            episodeId: String,
            onsetTs: Long?,
            lowerBoundGrams: Double,
            enabled: Boolean,
            dryRun: Boolean,
            outcome: UamExportCoordinator.Outcome
        ): UnifiedUamExportTelemetrySnapshot {
            val decision = outcome.decision as? UamExportDecision
            val deliveredNow = outcome.delivered && !dryRun && decision is UamExportDecision.Send
            val validGlobalV2Entries = outcome.remoteEntries.mapNotNull { entry ->
                val tag = io.aaps.copilot.domain.predict.UamTagCodec.parseUamTag(entry.note)
                if (
                    tag?.ver == 2 &&
                    entry.tsMs in 0..nowTs &&
                    entry.grams.isFinite() &&
                    entry.grams > 0.0
                ) {
                    tag to entry
                } else {
                    null
                }
            }
                .groupBy { (tag, _) -> tag.id to tag.seq }
                .mapNotNull { (_, entries) ->
                    val first = entries.first()
                    val consistent = entries.all { (_, entry) ->
                        entry.tsMs == first.second.tsMs && entry.grams == first.second.grams
                    }
                    first.second.takeIf { consistent }
                }
            val remoteLatest = validGlobalV2Entries.maxByOrNull { it.tsMs }
            val deliveredDecision = decision as? UamExportDecision.Send
            val deliveredGrams = if (deliveredNow) deliveredDecision?.grams ?: 0.0 else 0.0
            val deliveredTs = if (deliveredNow) deliveredDecision?.treatmentTs else null
            val rolling30Remote = validGlobalV2Entries
                .filter { it.tsMs > nowTs - 30 * 60_000L }
                .sumOf { it.grams }
            val rolling60Remote = validGlobalV2Entries
                .filter { it.tsMs > nowTs - 60 * 60_000L }
                .sumOf { it.grams }
            val deliveredIn30 = deliveredTs?.let { it > nowTs - 30 * 60_000L } == true
            val deliveredIn60 = deliveredTs?.let { it > nowTs - 60 * 60_000L } == true
            val latestIncrement = if (
                deliveredTs != null && (remoteLatest == null || deliveredTs >= remoteLatest.tsMs)
            ) {
                deliveredGrams to deliveredTs
            } else {
                (remoteLatest?.grams ?: 0.0) to remoteLatest?.tsMs
            }
            return UnifiedUamExportTelemetrySnapshot(
                liveEnabled = enabled && !dryRun,
                eligible = decision is UamExportDecision.Send,
                blockReason = when (decision) {
                    is UamExportDecision.Block -> decision.reason
                    else -> outcome.reason ?: "none"
                },
                episodeId = episodeId,
                episodeAgeMinutes = onsetTs?.let { onset ->
                    ((nowTs - onset).coerceAtLeast(0L) / 60_000.0)
                } ?: 0.0,
                lowerBoundGrams = lowerBoundGrams,
                cumulativeGrams = validGlobalV2Entries.sumOf { it.grams } + deliveredGrams,
                rolling30Grams = rolling30Remote + if (deliveredIn30) deliveredGrams else 0.0,
                rolling60Grams = rolling60Remote + if (deliveredIn60) deliveredGrams else 0.0,
                lastIncrementGrams = latestIncrement.first,
                lastIncrementTs = latestIncrement.second,
                delivered = deliveredNow
            )
        }

        private const val AUTOMATION_CYCLE_TIMEOUT_MS = 180_000L
        private const val SENSITIVITY_SOURCE_CHANGE_LOCK_TIMEOUT_MS = 30_000L
        private const val SENSITIVITY_ACCEPTED_DURABILITY_STEP_TIMEOUT_MS = 5_000L
        private const val SENSITIVITY_ACCEPTED_MARKER_MAX_AHEAD_MS = 1_000L
        private const val AUTOMATION_STALL_WARN_MS = 180_000L
        private const val AUTOMATION_STEP_SLOW_MS = 10_000L
        private const val AUTOMATION_STEP_INFO_LOG_INTERVAL_MS = 15 * 60_000L
        private const val ACTION_SUBMIT_TIMEOUT_MS = 8_000L
        private const val COOLDOWN_TOLERANCE_MS = 2_000L
        private const val CLOUD_PUSH_STEP_TIMEOUT_MS = 20_000L
        private const val BASELINE_IMPORT_INTERVAL_MS = 30L * 60_000L
        private const val BASELINE_IMPORT_SKIP_LOG_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val ISFCR_SNAPSHOT_FRESHNESS_MS = 15 * 60_000L
        private const val ISFCR_PROACTIVE_REFRESH_AGE_MS = 6 * 60_000L
        private const val LOCAL_ACTIVE_TARGET_FUTURE_MARKER_SOURCE = "copilot_target_safety"
        private const val LOCAL_ACTIVE_TARGET_FUTURE_MARKER_KEY =
            "active_target_future_first_seen"
        private const val LOCAL_SAFETY_CLOCK_HIGH_WATER_ID =
            "copilot-local-safety-clock-high-water-v1"
        private const val LOCAL_SAFETY_CLOCK_SOURCE = "copilot_local_safety_clock"
        private const val LOCAL_SAFETY_CLOCK_KEY = "wall_clock_high_water_ms"
        private const val LOCAL_SAFETY_CLOCK_RECOVERY_KEY = "wall_clock_recovery_candidate_ms"
        private const val LOCAL_SAFETY_CLOCK_UNIT = "epoch_ms|elapsed_ms_v1"
        private const val LOCAL_SAFETY_CLOCK_LEGACY_UNIT = "epoch_ms"
        private const val LOCAL_SAFETY_CLOCK_RECOVERY_ID_PREFIX =
            "copilot-local-safety-clock-recovery-v1"
        private const val LOCAL_SAFETY_CLOCK_RECOVERY_SLOTS = 4
        private const val LOCAL_SAFETY_CLOCK_MAX_TRUSTED_ROLLBACK_MS = 60L * 60L * 1_000L
        private const val LOCAL_SAFETY_CLOCK_MAX_WALL_TS = 253_402_300_799_999L
        private const val MAX_EXACT_DOUBLE_INTEGER = 9_007_199_254_740_992L
        private const val DIAGNOSTIC_RULE_EXECUTION_STATE = "DIAGNOSTIC_BLOCKED"
        private const val ISFCR_REALTIME_TIMEOUT_MS = 45_000L
        private const val ISFCR_REALTIME_IN_FLIGHT_STALE_MS = 180_000L
        private const val ISFCR_REALTIME_RETRY_BACKOFF_MS = 5 * 60_000L
        private const val ISFCR_SYNC_REFRESH_STALE_MS = 45 * 60_000L
        private const val ISFCR_STALE_REUSE_MAX_MS = 72 * 60 * 60_000L
        private const val ISFCR_STALE_REUSE_CONFIDENCE_MAX = 0.35
        private const val SENSOR_BLOCK_TTL_MS = 30 * 60 * 1000L
        private const val SENSOR_LAG_RUNTIME_CONTEXT_LOOKBACK_MS = 72L * 60L * 60L * 1000L
        private const val MIN_TARGET_MMOL = 4.0
        private const val MAX_TARGET_MMOL = 10.0
        private const val LOW_GLUCOSE_SAFETY_SEED_MAX_AGE_MS = 45 * 60_000L
        private const val LOW_GLUCOSE_SAFETY_TARGET_MATCH_TOLERANCE_MMOL = 0.15
        private const val TEMP_TARGET_MGDL_THRESHOLD = 30.0
        // One rounded target step is enough to justify an immediate retarget.
        // Same-target resends are already suppressed in AdaptiveTargetControllerRule.
        private const val ADAPTIVE_COOLDOWN_BYPASS_DELTA_MMOL = 0.05
        private const val ALIGN_GAIN = 0.35
        private const val MAX_ALIGN_STEP_MMOL = 1.20
        private const val ADAPTIVE_KEEPALIVE_INTERVAL_MS = 5 * 60 * 1000L
        private const val ADAPTIVE_KEEPALIVE_DURATION_MINUTES = 30
        private const val SENSOR_QUALITY_ROLLBACK_INTERVAL_MS = 20 * 60 * 1000L
        private const val SENSOR_QUALITY_ROLLBACK_DURATION_MINUTES = 30
        private const val SENSOR_QUALITY_ROLLBACK_IDEMPOTENCY_PREFIX = "sensor_quality_rollback:"
        private const val TELEMETRY_LOOKBACK_MS = 6 * 60 * 60 * 1000L
        private const val TELEMETRY_REPORT_LOOKBACK_MS = 72 * 60 * 60 * 1000L
        private const val SENSOR_LAG_HISTORY_LOOKBACK_MS = 21L * 24L * 60L * 60L * 1000L
        private const val RECURRING_INFO_LOG_INTERVAL_MS = 15 * 60_000L
        private const val CALCULATED_UAM_LOOKBACK_MINUTES = 120
        private const val UAM_PROCESSING_BUCKET_MS = 5 * 60 * 1000L
        private const val UAM_EPISODE_BUCKET_MS = 5 * 60 * 1000L
        private const val UAM_MEANINGFUL_IOB_UNITS = 0.3
        private const val UAM_MEANINGFUL_COB_GRAMS = 0.5
        private const val UAM_DIAGNOSTIC_ONLY_THERAPY_COVERAGE = 0.5
        private const val UAM_PARTIAL_INSULIN_THERAPY_COVERAGE = 0.85
        private const val UAM_STRONG_INSULIN_FRESHNESS_MS = 90L * 60_000L
        private const val UAM_IOB_SAMPLE_FRESHNESS_MS = 15L * 60_000L
        private const val DIRECT_AAPS_THERAPY_PREFERENCE_WINDOW_MS = 6L * 60_000L
        private const val CALIBRATION_REFRESH_INTERVAL_MS = 5 * 60_000L
        private const val CUMULATIVE_ACTIVITY_PAGE_SIZE = 500
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val UAM_EVENT_RETENTION_MS = 14L * DAY_MS
        private const val INTEGRATED_RUNTIME_RETENTION_MS = 30L * DAY_MS
        private val DELIVERY_CARB_KEYS = setOf("grams", "carbs", "enteredcarbs", "mealcarbs")
        private val ATOMIC_AAPS_INSULIN_PACKET_KEYS = setOf(
            "iob_units",
            "iob_net_units",
            "iob_bolus_units",
            "iob_basal_units",
            "insulin_activity",
            "iob_effective_positive_units",
            "iob_relay_timestamp_ms",
            "iob_runtime_confidence",
            "iob_runtime_source_code"
        )
        private val ATOMIC_AAPS_INSULIN_PACKET_MARKER_KEYS = setOf(
            "iob_relay_timestamp_ms",
            "iob_runtime_confidence",
            "iob_runtime_source_code",
            "iob_runtime_source",
            "iob_runtime_fallback_reason"
        )
        private val AAPS_COMPONENT_ONLY_INSULIN_KEYS = ATOMIC_AAPS_INSULIN_PACKET_KEYS - "iob_units"

        private val CUMULATIVE_ACTIVITY_KEYS = setOf(
            "steps_count",
            "distance_km",
            "active_minutes",
            "calories_active_kcal"
        )
        private val SENSOR_LAG_RUNTIME_CONTEXT_KEYS = listOf(
            "sensor_age_hours",
            "sensor_age_days",
            "raw_com_eveningoutpost_dexdrip_extras_sensorstartedat",
            "sensor_age_source_raw",
            "sage_days",
            "cage_days",
            "daily_report_sensor_lag_bucket_json"
        )
        private val IOB_STRICT_ALLOWED_KEYS = setOf(
            "iob_units",
            "iob_net_units",
            "raw_iob",
            "raw_iob_units",
            "raw_iob_iob",
            "raw_openaps_iob_iob",
            "openaps_iob_iob"
        )
        private val COB_STRICT_ALLOWED_KEYS = setOf(
            "cob_grams",
            "raw_cob"
        )
        private val ISF_AAPS_ALLOWED_KEYS = setOf(
            "isf_value",
            "raw_isf",
            "aaps_isf",
            "isf"
        )
        private val CR_AAPS_ALLOWED_KEYS = setOf(
            "cr_value",
            "raw_cr",
            "aaps_cr",
            "cr"
        )
        private val IOB_STRICT_ALLOWED_SOURCES = setOf(
            "aaps_broadcast",
            "xdrip_broadcast",
            "local_broadcast",
            "nightscout",
            "nightscout_devicestatus",
            "nightscout_treatment",
            "local_nightscout_devicestatus"
        )
        private val ISF_AAPS_ALLOWED_SOURCES = IOB_STRICT_ALLOWED_SOURCES
        private val CR_AAPS_ALLOWED_SOURCES = IOB_STRICT_ALLOWED_SOURCES
        private val IOB_STRICT_SOURCE_PRIORITY = mapOf(
            "aaps_broadcast" to 6,
            "xdrip_broadcast" to 5,
            "local_broadcast" to 4,
            "nightscout_devicestatus" to 3,
            "local_nightscout_devicestatus" to 2,
            "nightscout_treatment" to 1,
            "nightscout" to 1
        )

        private const val COB_FORECAST_GAIN_5 = 0.006
        private const val COB_FORECAST_GAIN_30 = 0.012
        private const val COB_FORECAST_GAIN_60 = 0.018
        private const val COB_FORECAST_BIAS_MAX = 2.5

        private const val COMPONENT_IOB_BIAS_MIN = -1.5
        private const val COMPONENT_IOB_BIAS_MAX = 1.5
        private const val DEFAULT_FORECAST_ISF_MMOL_PER_UNIT = 3.0
        private const val COB_IOB_TELEMETRY_WEIGHT = 0.60
        private const val LOCAL_COB_IOB_LOOKBACK_MS = 12L * 60 * 60 * 1000
        private const val INSULIN_CAUSAL_NORMALIZATION_MAX_MS = 10L * 60_000L
        private const val INSULIN_MODEL_RATIO_EPSILON = 0.01
        private const val INSULIN_MODEL_RATIO_MIN = 0.75
        private const val INSULIN_MODEL_RATIO_MAX = 1.25
        private const val INSULIN_ACTIVITY_NORMALIZATION_MAX_UNITS = 0.5
        private const val INSULIN_NORMALIZED_IOB_MAX_UNITS = 30.0
        private const val INSULIN_NORMALIZED_ACTIVITY_MAX = 5.0
        private const val MIN_AUTHORITATIVE_INSULIN_TIMESTAMP_MS = 1_000_000_000_000L
        private const val MAX_AUTHORITATIVE_INSULIN_TIMESTAMP_MS = 10_000_000_000_000L
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
        private const val ISFCR_SPARSE_BLEND_MIN = 0.12
        private const val ISFCR_SPARSE_BLEND_MAX = 0.32

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
        private const val IOB_LOW_GUARD_GAIN = 0.14
        private const val COB_LOW_GUARD_GAIN = 0.004
        private const val GLUCOSE_LOW_GUARD_GAIN = 0.30
        private const val LOW_GUARD_FALLING_MULTIPLIER = 1.10
        private const val LOW_GUARD_EXTRA_DOWN_MAX = 0.90
        private const val MIN_GLUCOSE_MMOL = 2.2
        private const val MAX_GLUCOSE_MMOL = 22.0
        private const val PHYSICAL_ACTIVITY_RATIO_KEY = "activity_ratio"
        private const val PHYSICAL_ACTIVITY_MEASUREMENT_FRESHNESS_MS = 10L * 60 * 1000
        private val PHYSICAL_ACTIVITY_SOURCES = setOf("local_sensor", "health_connect")
        private val PHYSICAL_ACTIVITY_QUALITIES = setOf("OK", "TRUSTED")
        private const val CALIBRATION_FORECAST_LIMIT = 4_000
        private const val CALIBRATION_GLUCOSE_LIMIT = 8_000
        private const val CALIBRATION_LOOKBACK_MS = 12L * 60 * 60 * 1000
        private const val CALIBRATION_MIN_AGE_MS = 2L * 60 * 1000
        private const val CALIBRATION_MATCH_TOLERANCE_MS = 2L * 60 * 1000
        private const val SENSOR_TRUST_MATCH_TOLERANCE_MS = 10L * 60 * 1000
        private const val FORECAST_RELIABILITY_MIN_SAMPLES = 36
        private const val FORECAST_RELIABILITY_FULL_SAMPLE_COUNT = 288
        private val FORECAST_RELIABILITY_SENSOR_KEYS = listOf(
            "sensor_quality_score",
            "sensor_quality_blocked",
            "sensor_quality_suspect_false_low"
        )
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
        private const val REAL_PROFILE_RECOMPUTE_INTERVAL_MS = 72L * 60 * 60 * 1000
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
        private val REAL_PROFILE_IMPLICIT_IOB_KEYS = listOf(
            "iob_effective_units",
            "iob_units",
            "iob_real_units",
            "raw_iob",
            "raw_iob_units",
            "raw_iob_iob",
            "openaps_iob",
            "openaps_iob_iob",
            "openaps_iob_basaliob",
            "openaps_iob_activity",
            "iob_iob",
            "iob_basaliob"
        )
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
        private const val DIA_EFFECTIVE_MIN_SCALE = 0.50
        private const val DIA_EFFECTIVE_MAX_SCALE = 1.50
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

        internal data class DiaResolution(
            val profileHours: Double,
            val effectiveHours: Double,
            val rawEstimatedHours: Double?,
            val source: String
        )

        internal fun shouldRecomputeRealInsulinProfileStatic(
            existingUpdatedTs: Long?,
            nowTs: Long,
            existingAlgoVersion: String?,
            existingStatus: String?,
            existingSampleCount: Int
        ): Boolean {
            if (existingUpdatedTs == null) return true
            if (existingAlgoVersion != null && existingAlgoVersion != REAL_PROFILE_ALGO_VERSION) return true
            if (existingStatus.equals("fallback_template", ignoreCase = true) && existingSampleCount <= 0) return true
            return nowTs - existingUpdatedTs >= REAL_PROFILE_RECOMPUTE_INTERVAL_MS
        }

        internal fun resolveEffectiveDiaHoursStatic(
            profileDurationHours: Double,
            profileBaseOnsetMinutes: Double,
            realProfileOnsetMinutes: Double?,
            realProfileShapeScale: Double?,
            realProfileConfidence: Double?,
            realProfileStatus: String?,
            realProfileSourceId: String?,
            selectedProfileId: String
        ): DiaResolution {
            val profileHours = profileDurationHours.coerceIn(0.5, 24.0)
            val profileMinutes = profileHours * 60.0
            val canUseRealProfile = realProfileOnsetMinutes != null &&
                realProfileShapeScale != null &&
                (realProfileConfidence ?: 0.0) >= 0.35 &&
                !realProfileStatus.equals("fallback_template", ignoreCase = true) &&
                (realProfileSourceId.isNullOrBlank() || realProfileSourceId.equals(selectedProfileId, ignoreCase = true))
            if (!canUseRealProfile) {
                return DiaResolution(
                    profileHours = profileHours,
                    effectiveHours = profileHours,
                    rawEstimatedHours = null,
                    source = "profile_default"
                )
            }

            val clampedShapeScale = realProfileShapeScale.coerceIn(REAL_PROFILE_MIN_SCALE, REAL_PROFILE_MAX_SCALE)
            val onsetShiftMinutes = (realProfileOnsetMinutes - profileBaseOnsetMinutes)
                .coerceIn(-30.0, 90.0)
            val rawEstimatedHours = (((profileMinutes * clampedShapeScale) + onsetShiftMinutes) / 60.0)
                .coerceIn(
                    (profileHours * DIA_EFFECTIVE_MIN_SCALE).coerceAtLeast(2.0),
                    (profileHours * DIA_EFFECTIVE_MAX_SCALE).coerceAtMost(8.0)
                )
            val blendWeight = (realProfileConfidence ?: 0.35).coerceIn(0.35, 0.95)
            val effectiveHours = (profileHours + (rawEstimatedHours - profileHours) * blendWeight)
                .coerceIn(
                    (profileHours * DIA_EFFECTIVE_MIN_SCALE).coerceAtLeast(2.0),
                    (profileHours * DIA_EFFECTIVE_MAX_SCALE).coerceAtMost(8.0)
                )
            return DiaResolution(
                profileHours = profileHours,
                effectiveHours = effectiveHours,
                rawEstimatedHours = rawEstimatedHours,
                source = "profile_real_blended"
            )
        }

        private data class CalibrationConfig(
            val minSamples: Int,
            val gain: Double,
            val maxUp: Double,
            val maxDown: Double,
            val minBucketSamples: Int,
            val bucketBlend: Double,
            val ciQuantile: Double,
            val ciBlend: Double,
            val ciMaxExpandScale: Double,
            val ciMaxShrinkScale: Double
        )

        internal fun evaluateSensorQualityStatic(
            glucose: List<GlucosePoint>,
            nowTs: Long,
            staleMaxMinutes: Int
        ): SensorQualityAssessment {
            val sanitized = GlucoseSanitizer.filterPoints(glucose)
            if (sanitized.isEmpty()) {
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

            val sorted = sanitized.sortedBy { it.ts }
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

        internal fun resolveActiveTempTargetStatic(
            now: Long,
            recentTargets: List<TherapyEventEntity>,
            gson: Gson
        ): Double? = resolveActiveAapsTargetStatic(now, recentTargets, gson)
            ?.takeIf(ActiveAapsTarget::evidenceResolved)
            ?.targetMmol

        internal fun resolveActiveAapsTargetStatic(
            now: Long,
            recentTargets: List<TherapyEventEntity>,
            gson: Gson
        ): ActiveAapsTarget? {
            val latest = recentTargets.maxWithOrNull(
                compareBy<TherapyEventEntity>(TherapyEventEntity::timestamp, TherapyEventEntity::id)
            ) ?: return null
            fun unresolved(): ActiveAapsTarget? {
                val maxPotentialExpiry = if (latest.timestamp > now) {
                    saturatingAddStatic(now, LocalTargetSafetyTimeWindow.FUTURE_AMBIGUITY_MS)
                } else {
                    saturatingAddStatic(
                        latest.timestamp,
                        LocalTargetSafetyTimeWindow.ACTIVE_TARGET_LOOKBACK_MS
                    )
                }
                if (maxPotentialExpiry < now) return null
                return ActiveAapsTarget(
                    targetMmol = MIN_TARGET_MMOL,
                    startedAt = latest.timestamp.coerceAtMost(now),
                    expiresAt = maxPotentialExpiry.coerceAtLeast(now + 1L),
                    source = "unresolved_therapy_history",
                    ownership = ActiveTargetOwnership.UNKNOWN,
                    idempotencyKey = null,
                    evidenceResolved = false
                )
            }
            if (recentTargets.count { it.timestamp == latest.timestamp } > 1) return unresolved()
            if (latest.timestamp > now) return unresolved()
            val payload = runCatching {
                gson.fromJson(latest.payloadJson, MutableMap::class.java) as? Map<*, *>
            }.getOrNull() ?: return unresolved()
            val rawDurationMinutes = payload.doubleValueOf(
                "duration",
                "durationInMinutes"
            ) ?: return unresolved()
            if (
                !rawDurationMinutes.isFinite() ||
                rawDurationMinutes % 1.0 != 0.0 ||
                rawDurationMinutes < 0.0 ||
                rawDurationMinutes in 1.0..<5.0 ||
                rawDurationMinutes >
                LocalTargetSafetyTimeWindow.ACTIVE_TARGET_MAX_DURATION_MINUTES.toDouble()
            ) return unresolved()
            val durationMinutes = rawDurationMinutes.toLong()
            if (durationMinutes == 0L) return null
            val durationMs = runCatching { Math.multiplyExact(durationMinutes, 60_000L) }.getOrNull()
                ?: return unresolved()
            val activeUntil = runCatching { Math.addExact(latest.timestamp, durationMs) }.getOrNull()
                ?: return unresolved()
            if (now > activeUntil) return null
            val target = extractTempTargetMmolStatic(payload)
                ?.takeIf { it.isFinite() && it in MIN_TARGET_MMOL..MAX_TARGET_MMOL }
                ?: return unresolved()
            val notes = payload["notes"]?.toString()?.trim().orEmpty()
            val idempotencyKey = notes
                .takeIf { it.startsWith("copilot:") }
                ?.removePrefix("copilot:")
                ?.takeIf(String::isNotBlank)
            val ownership = when {
                idempotencyKey?.startsWith(NightscoutActionRepository.TARGET_MANAGER_IDEMPOTENCY_PREFIX) == true ->
                    ActiveTargetOwnership.TARGET_MANAGER
                idempotencyKey?.startsWith(NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX) == true ->
                    ActiveTargetOwnership.MANUAL_OR_FOREIGN
                idempotencyKey != null -> ActiveTargetOwnership.LEGACY_COPILOT
                notes.isNotBlank() -> ActiveTargetOwnership.MANUAL_OR_FOREIGN
                else -> ActiveTargetOwnership.UNKNOWN
            }
            return ActiveAapsTarget(
                targetMmol = target,
                startedAt = latest.timestamp,
                expiresAt = activeUntil,
                source = payload["source"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
                    ?: "therapy_history",
                ownership = ownership,
                idempotencyKey = idempotencyKey
            )
        }

        internal suspend fun resolveActiveAapsTargetFromRoomStatic(
            db: CopilotDatabase,
            gson: Gson,
            nowTs: Long
        ): ActiveAapsTarget? = loadLocalSafetyEvidenceStatic(db, gson, nowTs).activeAapsTarget

        private fun isCanonicalLocalFutureTargetMarkerStatic(
            marker: TelemetrySampleEntity,
            causalThroughTs: Long
        ): Boolean = marker.source == LOCAL_ACTIVE_TARGET_FUTURE_MARKER_SOURCE &&
            marker.key == LOCAL_ACTIVE_TARGET_FUTURE_MARKER_KEY &&
            marker.valueDouble == null &&
            marker.valueText == marker.id &&
            marker.unit == null &&
            marker.quality == "OK" &&
            marker.timestamp in 1L..causalThroughTs

        private fun unresolvedTargetMarkerCollisionStatic(nowTs: Long): ActiveAapsTarget =
            unresolvedActiveAapsTargetStatic(
                nowTs = nowTs,
                expiresAt = saturatingAddStatic(
                    nowTs,
                    LocalTargetSafetyTimeWindow.FUTURE_AMBIGUITY_MS
                ),
                source = "unresolved_target_quarantine_marker"
            )

        internal fun localFutureTargetMarkerStatic(
            row: TherapyEventEntity,
            firstSeenTs: Long
        ) = TelemetrySampleEntity(
            id = localFutureTargetMarkerIdStatic(row),
            timestamp = firstSeenTs,
            source = LOCAL_ACTIVE_TARGET_FUTURE_MARKER_SOURCE,
            key = LOCAL_ACTIVE_TARGET_FUTURE_MARKER_KEY,
            valueDouble = null,
            valueText = localFutureTargetMarkerIdStatic(row),
            unit = null,
            quality = "OK"
        )

        private fun localFutureTargetMarkerIdStatic(row: TherapyEventEntity): String {
            val identity = listOf(
                row.id,
                row.timestamp.toString(),
                row.type,
                row.payloadJson
            ).joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte ->
                    "%02x".format(Locale.US, byte.toInt() and 0xff)
                }
            return "local-active-target-future-$digest"
        }

        private fun unresolvedActiveAapsTargetStatic(
            nowTs: Long,
            expiresAt: Long,
            source: String
        ): ActiveAapsTarget = ActiveAapsTarget(
            targetMmol = MIN_TARGET_MMOL,
            startedAt = nowTs,
            expiresAt = expiresAt.coerceAtLeast(saturatingAddStatic(nowTs, 1L)),
            source = source,
            ownership = ActiveTargetOwnership.UNKNOWN,
            idempotencyKey = null,
            evidenceResolved = false
        )

        private fun saturatingAddStatic(value: Long, increment: Long): Long = runCatching {
            Math.addExact(value, increment)
        }.getOrDefault(Long.MAX_VALUE)

        internal suspend fun loadLocalSafetyEvidenceStatic(
            db: CopilotDatabase,
            gson: Gson,
            nowTs: Long,
            monotonicNowTs: Long = SystemClock.elapsedRealtime()
        ): LocalSafetyEvidence = db.withTransaction {
            val clock = observeLocalSafetyClockStatic(
                telemetryDao = db.telemetryDao(),
                nowTs = nowTs,
                monotonicNowTs = monotonicNowTs
            )
            val window = LocalTargetSafetyTimeWindow.at(clock.effectiveNowTs, clock.highWaterTs)
            val actionDao = db.actionCommandDao()
            val actionsLast6h = actionDao.countByStatusBetweenExcludingTwoPrefixes(
                status = NightscoutActionRepository.STATUS_SENT,
                since = window.actionSinceInclusive,
                through = window.causalThroughInclusive,
                excludedPrefix1 = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%",
                excludedPrefix2 = "${NightscoutActionRepository.KEEPALIVE_IDEMPOTENCY_PREFIX}%"
            )
            val latestAutomaticEntity = actionDao.latestByTypeAndStatusAtOrBeforeExcludingPrefix(
                type = "temp_target",
                status = NightscoutActionRepository.STATUS_SENT,
                through = window.causalThroughInclusive,
                excludedPrefix = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%"
            )
            val latestAutomatic = latestAutomaticEntity?.let { entity ->
                parseLastSentTempTargetStatic(entity, gson)
            }
            val activeTarget = resolveActiveAapsTargetInTransactionStatic(
                db = db,
                gson = gson,
                window = window
            )
            LocalSafetyEvidence(
                actionsLast6h = actionsLast6h.coerceAtLeast(0),
                activeAapsTarget = activeTarget,
                latestAutomaticSent = latestAutomatic,
                chronologyResolved = clock.markerResolved &&
                    window.chronologyResolved &&
                    (latestAutomaticEntity == null || latestAutomatic != null),
                causalThroughTs = window.causalThroughInclusive
            )
        }

        private suspend fun resolveActiveAapsTargetInTransactionStatic(
            db: CopilotDatabase,
            gson: Gson,
            window: LocalTargetSafetyTimeWindow
        ): ActiveAapsTarget? {
            val rows = db.therapyDao().newestByTypeBetween(
                type = "temp_target",
                since = window.targetSinceInclusive,
                through = window.targetEvidenceThroughInclusive,
                limit = LocalTargetSafetyTimeWindow.TARGET_EVIDENCE_PROBE_ROWS
            )
            if (rows.size > LocalTargetSafetyTimeWindow.TARGET_EVIDENCE_MAX_ROWS) {
                return unresolvedActiveAapsTargetStatic(
                    nowTs = window.nowTs,
                    expiresAt = saturatingAddStatic(
                        window.nowTs,
                        LocalTargetSafetyTimeWindow.FUTURE_AMBIGUITY_MS
                    ),
                    source = "unresolved_therapy_history_truncated"
                )
            }
            if (rows.isEmpty()) return null

            val markerIds = rows.map(::localFutureTargetMarkerIdStatic)
            if (markerIds.distinct().size != markerIds.size) {
                return unresolvedTargetMarkerCollisionStatic(window.nowTs)
            }
            val markerRows = db.telemetryDao().byIds(markerIds)
            if (markerRows.any { marker ->
                    !isCanonicalLocalFutureTargetMarkerStatic(
                        marker,
                        window.causalThroughInclusive
                    )
                }
            ) {
                return unresolvedTargetMarkerCollisionStatic(window.nowTs)
            }
            val markedIds = markerRows.mapTo(mutableSetOf(), TelemetrySampleEntity::id)
            val usableRows = rows.zip(markerIds)
                .filterNot { (_, markerId) -> markerId in markedIds }
                .map { (row, _) -> row }
            return resolveActiveAapsTargetStatic(
                now = window.causalThroughInclusive,
                recentTargets = usableRows,
                gson = gson
            )
        }

        private suspend fun observeLocalSafetyClockStatic(
            telemetryDao: TelemetryDao,
            nowTs: Long,
            monotonicNowTs: Long
        ): LocalSafetyClockObservation {
            require(nowTs > 0L) { "local safety observation timestamp must be positive" }
            if (!isValidLocalSafetyWallTsStatic(nowTs) ||
                !isValidLocalSafetyMonotonicTsStatic(monotonicNowTs)
            ) {
                return LocalSafetyClockObservation(
                    effectiveNowTs = 1L,
                    highWaterTs = 1L,
                    markerResolved = false
                )
            }
            var marker = telemetryDao.byId(LOCAL_SAFETY_CLOCK_HIGH_WATER_ID)
            if (marker == null) {
                telemetryDao.insertIgnoreAll(
                    listOf(localSafetyClockMarkerStatic(nowTs, monotonicNowTs))
                )
                marker = telemetryDao.byId(LOCAL_SAFETY_CLOCK_HIGH_WATER_ID)
            }
            if (marker == null) {
                return LocalSafetyClockObservation(nowTs, nowTs, markerResolved = false)
            }

            val markerMonotonicTs = canonicalLocalSafetyClockMonotonicTsStatic(marker)
            if (markerMonotonicTs != null &&
                nowTs >= marker.timestamp &&
                isLocalSafetyClockProgressionConsistentStatic(
                    anchorWallTs = marker.timestamp,
                    anchorMonotonicTs = markerMonotonicTs,
                    nowTs = nowTs,
                    monotonicNowTs = monotonicNowTs
                )
            ) {
                telemetryDao.upsertAll(
                    listOf(localSafetyClockMarkerStatic(nowTs, monotonicNowTs))
                )
                return LocalSafetyClockObservation(nowTs, nowTs, markerResolved = true)
            }

            val recovery = observeLocalSafetyClockRecoveryStatic(
                telemetryDao = telemetryDao,
                blockedMarker = marker,
                nowTs = nowTs,
                monotonicNowTs = monotonicNowTs
            )
            if (recovery.confirmed) {
                if (isOwnedLocalSafetyClockMarkerStatic(marker)) {
                    telemetryDao.upsertAll(
                        listOf(localSafetyClockMarkerStatic(nowTs, monotonicNowTs))
                    )
                }
                return LocalSafetyClockObservation(nowTs, nowTs, markerResolved = true)
            }

            val trustedRollbackHighWater = marker.timestamp.takeIf { persisted ->
                markerMonotonicTs != null &&
                    persisted > nowTs &&
                    persisted - nowTs <= LOCAL_SAFETY_CLOCK_MAX_TRUSTED_ROLLBACK_MS
            }
            return LocalSafetyClockObservation(
                effectiveNowTs = if (nowTs > marker.timestamp && markerMonotonicTs != null) {
                    marker.timestamp
                } else {
                    nowTs
                },
                highWaterTs = trustedRollbackHighWater ?: if (
                    nowTs > marker.timestamp && markerMonotonicTs != null
                ) {
                    marker.timestamp
                } else {
                    nowTs
                },
                markerResolved = false
            )
        }

        private fun localSafetyClockMarkerStatic(
            highWaterTs: Long,
            monotonicTs: Long
        ) = TelemetrySampleEntity(
            id = LOCAL_SAFETY_CLOCK_HIGH_WATER_ID,
            timestamp = highWaterTs,
            source = LOCAL_SAFETY_CLOCK_SOURCE,
            key = LOCAL_SAFETY_CLOCK_KEY,
            valueDouble = monotonicTs.toDouble(),
            valueText = highWaterTs.toString(),
            unit = LOCAL_SAFETY_CLOCK_UNIT,
            quality = "OK"
        )

        private fun canonicalLocalSafetyClockMonotonicTsStatic(
            marker: TelemetrySampleEntity
        ): Long? {
            if (!isCanonicalLocalSafetyClockMarkerStatic(marker)) return null
            val raw = marker.valueDouble ?: return null
            val value = raw.toLong()
            return value.takeIf { raw == value.toDouble() }
        }

        private fun isCanonicalLocalSafetyClockMarkerStatic(marker: TelemetrySampleEntity): Boolean =
            marker.id == LOCAL_SAFETY_CLOCK_HIGH_WATER_ID &&
                isValidLocalSafetyWallTsStatic(marker.timestamp) &&
                marker.source == LOCAL_SAFETY_CLOCK_SOURCE &&
                marker.key == LOCAL_SAFETY_CLOCK_KEY &&
                marker.valueDouble?.let { value ->
                    value.isFinite() &&
                        value >= 0.0 &&
                        value <= MAX_EXACT_DOUBLE_INTEGER.toDouble()
                } == true &&
                marker.valueText == marker.timestamp.toString() &&
                marker.unit == LOCAL_SAFETY_CLOCK_UNIT &&
                marker.quality == "OK"

        private fun isOwnedLocalSafetyClockMarkerStatic(marker: TelemetrySampleEntity): Boolean =
            marker.id == LOCAL_SAFETY_CLOCK_HIGH_WATER_ID &&
                marker.timestamp > 0L &&
                marker.source == LOCAL_SAFETY_CLOCK_SOURCE &&
                marker.key == LOCAL_SAFETY_CLOCK_KEY &&
                marker.valueText == marker.timestamp.toString() &&
                marker.quality == "OK" &&
                (
                    marker.unit == LOCAL_SAFETY_CLOCK_UNIT ||
                        (marker.unit == LOCAL_SAFETY_CLOCK_LEGACY_UNIT && marker.valueDouble == null)
                    )

        private suspend fun observeLocalSafetyClockRecoveryStatic(
            telemetryDao: TelemetryDao,
            blockedMarker: TelemetrySampleEntity,
            nowTs: Long,
            monotonicNowTs: Long
        ): LocalSafetyClockRecoveryObservation {
            val fingerprint = localSafetyClockBlockedMarkerFingerprintStatic(blockedMarker)
            repeat(LOCAL_SAFETY_CLOCK_RECOVERY_SLOTS) { slot ->
                val id = "$LOCAL_SAFETY_CLOCK_RECOVERY_ID_PREFIX-$slot-$fingerprint"
                val existing = telemetryDao.byId(id)
                if (existing == null) {
                    telemetryDao.insertIgnoreAll(
                        listOf(
                            localSafetyClockRecoveryMarkerStatic(
                                id = id,
                                blockedMarkerFingerprint = fingerprint,
                                nowTs = nowTs,
                                monotonicNowTs = monotonicNowTs
                            )
                        )
                    )
                    val inserted = telemetryDao.byId(id)
                    if (inserted != null &&
                        isCanonicalLocalSafetyClockRecoveryStatic(inserted, fingerprint)
                    ) {
                        return LocalSafetyClockRecoveryObservation(confirmed = false)
                    }
                    return@repeat
                }
                val recoveryMonotonicTs = canonicalLocalSafetyClockRecoveryMonotonicTsStatic(
                    existing,
                    fingerprint
                ) ?: return@repeat
                val confirmed = isLocalSafetyClockProgressionConsistentStatic(
                    anchorWallTs = existing.timestamp,
                    anchorMonotonicTs = recoveryMonotonicTs,
                    nowTs = nowTs,
                    monotonicNowTs = monotonicNowTs
                )
                telemetryDao.upsertAll(
                    listOf(
                        localSafetyClockRecoveryMarkerStatic(
                            id = id,
                            blockedMarkerFingerprint = fingerprint,
                            nowTs = nowTs,
                            monotonicNowTs = monotonicNowTs
                        )
                    )
                )
                return LocalSafetyClockRecoveryObservation(confirmed = confirmed)
            }
            return LocalSafetyClockRecoveryObservation(confirmed = false)
        }

        private fun localSafetyClockRecoveryMarkerStatic(
            id: String,
            blockedMarkerFingerprint: String,
            nowTs: Long,
            monotonicNowTs: Long
        ) = TelemetrySampleEntity(
            id = id,
            timestamp = nowTs,
            source = LOCAL_SAFETY_CLOCK_SOURCE,
            key = LOCAL_SAFETY_CLOCK_RECOVERY_KEY,
            valueDouble = monotonicNowTs.toDouble(),
            valueText = blockedMarkerFingerprint,
            unit = LOCAL_SAFETY_CLOCK_UNIT,
            quality = "QUARANTINED"
        )

        private fun canonicalLocalSafetyClockRecoveryMonotonicTsStatic(
            marker: TelemetrySampleEntity,
            blockedMarkerFingerprint: String
        ): Long? {
            if (!isCanonicalLocalSafetyClockRecoveryStatic(marker, blockedMarkerFingerprint)) {
                return null
            }
            val raw = marker.valueDouble ?: return null
            val value = raw.toLong()
            return value.takeIf { raw == value.toDouble() }
        }

        private fun isCanonicalLocalSafetyClockRecoveryStatic(
            marker: TelemetrySampleEntity,
            blockedMarkerFingerprint: String
        ): Boolean =
            isValidLocalSafetyWallTsStatic(marker.timestamp) &&
                marker.id.startsWith("$LOCAL_SAFETY_CLOCK_RECOVERY_ID_PREFIX-") &&
                marker.source == LOCAL_SAFETY_CLOCK_SOURCE &&
                marker.key == LOCAL_SAFETY_CLOCK_RECOVERY_KEY &&
                marker.valueDouble?.let { value ->
                    value.isFinite() &&
                        value >= 0.0 &&
                        value <= MAX_EXACT_DOUBLE_INTEGER.toDouble()
                } == true &&
                marker.valueText == blockedMarkerFingerprint &&
                marker.unit == LOCAL_SAFETY_CLOCK_UNIT &&
                marker.quality == "QUARANTINED"

        private fun localSafetyClockBlockedMarkerFingerprintStatic(
            marker: TelemetrySampleEntity
        ): String {
            val identity = listOf(
                marker.id,
                marker.timestamp.toString(),
                marker.source,
                marker.key,
                marker.valueDouble?.toString().orEmpty(),
                marker.valueText.orEmpty(),
                marker.unit.orEmpty(),
                marker.quality
            ).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
        }

        private fun isLocalSafetyClockProgressionConsistentStatic(
            anchorWallTs: Long,
            anchorMonotonicTs: Long,
            nowTs: Long,
            monotonicNowTs: Long
        ): Boolean {
            if (monotonicNowTs < anchorMonotonicTs) return false
            val expectedNowTs = saturatingAddStatic(
                anchorWallTs,
                monotonicNowTs - anchorMonotonicTs
            )
            val lowerBound = (expectedNowTs - LocalTargetSafetyTimeWindow.SUPPORTED_FUTURE_SKEW_MS)
                .coerceAtLeast(1L)
            val upperBound = saturatingAddStatic(
                expectedNowTs,
                LocalTargetSafetyTimeWindow.SUPPORTED_FUTURE_SKEW_MS
            )
            return nowTs in lowerBound..upperBound
        }

        private fun isValidLocalSafetyWallTsStatic(value: Long): Boolean =
            value in 1L..LOCAL_SAFETY_CLOCK_MAX_WALL_TS

        private fun isValidLocalSafetyMonotonicTsStatic(value: Long): Boolean =
            value in 0L..MAX_EXACT_DOUBLE_INTEGER

        private fun parseLastSentTempTargetStatic(
            entity: io.aaps.copilot.data.local.entity.ActionCommandEntity,
            gson: Gson
        ): LastSentTempTarget? {
            val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
            val payload = runCatching {
                gson.fromJson<Map<String, Any?>>(entity.payloadJson, payloadType)
            }.getOrNull()
            val target = when (val raw = payload?.get("targetMmol")) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }?.takeIf(Double::isFinite) ?: return null
            return LastSentTempTarget(
                timestamp = entity.timestamp,
                targetMmol = target,
                idempotencyKey = entity.idempotencyKey
            )
        }

        private data class LocalSafetyClockObservation(
            val effectiveNowTs: Long,
            val highWaterTs: Long,
            val markerResolved: Boolean
        )

        private data class LocalSafetyClockRecoveryObservation(
            val confirmed: Boolean
        )

        internal fun automaticTargetWriterRoutingStatic(
            mode: TargetManagerMode
        ): AutomaticTargetWriterRouting = when (mode) {
            TargetManagerMode.OFF -> AutomaticTargetWriterRouting(
                evaluateManager = false,
                submitLegacyAutomatic = false
            )
            TargetManagerMode.SHADOW -> AutomaticTargetWriterRouting(
                evaluateManager = true,
                submitLegacyAutomatic = false
            )
            TargetManagerMode.ACTIVE -> AutomaticTargetWriterRouting(
                evaluateManager = true,
                submitLegacyAutomatic = false
            )
        }

        internal fun enabledTargetRuleIdsStatic(
            postHypoEnabled: Boolean,
            patternEnabled: Boolean,
            segmentEnabled: Boolean,
            scheduleAutoEnabled: Boolean
        ): Set<String> = buildSet {
            add(AdaptiveTargetControllerRule.RULE_ID)
            if (postHypoEnabled) add("PostHypoReboundGuard.v1")
            if (patternEnabled && !scheduleAutoEnabled) add("PatternAdaptiveTarget.v1")
            if (segmentEnabled) add("SegmentProfileGuard.v1")
        }

        internal fun resolveSensorTrustStateStatic(
            assessment: SensorQualityAssessment,
            sensorBlocked: Boolean
        ): SensorTrustState = when {
            sensorBlocked || assessment.blocked -> SensorTrustState.BLOCKED
            assessment.score >= 0.65 && !assessment.suspectFalseLow -> SensorTrustState.TRUSTED
            else -> SensorTrustState.WARN
        }

        internal fun forecastGeneratedAtStatic(
            targetTimestamp: Long,
            horizonMinutes: Int
        ): Long? {
            if (targetTimestamp <= 0L || horizonMinutes <= 0) return null
            return runCatching {
                Math.subtractExact(
                    targetTimestamp,
                    Math.multiplyExact(horizonMinutes.toLong(), MINUTE_MS)
                )
            }.getOrNull()?.takeIf { it > 0L }
        }

        internal fun buildHorizonReliabilityStatic(
            horizonMinutes: Int,
            currentForecast: Forecast?,
            maturedPoints: List<ForecastCalibrationPoint>,
            evaluatedAt: Long
        ): HorizonReliability {
            val points = maturedPoints.filter { point ->
                point.horizonMinutes == horizonMinutes &&
                    currentForecast != null &&
                    point.modelVersion == currentForecast.modelVersion &&
                    point.sensorTrusted &&
                    point.errorMmol.isFinite() &&
                    point.predictedMmol.isFinite() &&
                    point.ciLowMmol.isFinite() &&
                    point.ciHighMmol.isFinite() &&
                    point.ciLowMmol <= point.ciHighMmol
            }
            val sampleCount = points.size
            val mae = points.takeIf { it.isNotEmpty() }
                ?.map { abs(it.errorMmol) }
                ?.average()
            val bias = points.takeIf { it.isNotEmpty() }
                ?.map { it.errorMmol }
                ?.average()
            val ciCoverage = points.takeIf { it.isNotEmpty() }?.let { samples ->
                samples.count { point ->
                    val actual = point.predictedMmol + point.errorMmol
                    actual in point.ciLowMmol..point.ciHighMmol
                }.toDouble() / samples.size.toDouble()
            }
            val sampleFactor = (sampleCount / FORECAST_RELIABILITY_FULL_SAMPLE_COUNT.toDouble())
                .coerceIn(0.0, 1.0)
            val errorFactor = mae?.let { 1.0 / (1.0 + it * it) } ?: 0.0
            val biasFactor = if (mae != null && bias != null) {
                (1.0 - abs(bias) / maxOf(mae, 0.3)).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val coverageFactor = ((ciCoverage ?: 0.0) / 0.80).coerceIn(0.0, 1.0)
            val weight = (sampleFactor * errorFactor * biasFactor * coverageFactor)
                .coerceIn(0.0, 1.0)
            val currentForecastValid = currentForecast != null &&
                currentForecast.horizonMinutes == horizonMinutes &&
                currentForecast.modelVersion.isNotBlank() &&
                currentForecast.valueMmol.isFinite() &&
                currentForecast.ciLow.isFinite() &&
                currentForecast.ciHigh.isFinite() &&
                currentForecast.ciLow <= currentForecast.ciHigh
            val limits = when (horizonMinutes) {
                5 -> Triple(1.2, 0.7, 0.70)
                30 -> Triple(1.8, 0.9, 0.70)
                60 -> Triple(2.5, 1.1, 0.65)
                else -> Triple(1.8, 0.9, 0.70)
            }
            val state = when {
                !currentForecastValid || sampleCount < FORECAST_RELIABILITY_MIN_SAMPLES ->
                    HorizonReliabilityState.INSUFFICIENT
                mae == null || bias == null || ciCoverage == null ->
                    HorizonReliabilityState.INSUFFICIENT
                mae > limits.first * 1.75 || abs(bias) > limits.second * 1.75 || ciCoverage < 0.40 ->
                    HorizonReliabilityState.BLOCKED
                mae <= limits.first && abs(bias) <= limits.second && ciCoverage >= limits.third ->
                    HorizonReliabilityState.RELIABLE
                else -> HorizonReliabilityState.DEGRADED
            }
            return HorizonReliability(
                horizonMinutes = horizonMinutes,
                state = state,
                sampleCount = sampleCount,
                maeMmol = mae,
                biasMmol = bias,
                ciCoverage = ciCoverage,
                weightMultiplier = if (currentForecastValid) weight else 0.0,
                evaluatedAt = evaluatedAt
            )
        }

        private fun extractTempTargetMmolStatic(payload: Map<*, *>): Double? {
            val explicitMmol = listOfNotNull(
                payload.doubleValueOf("targetBottomMmol", "targetBottom_mmol", "targetLowMmol"),
                payload.doubleValueOf("targetTopMmol", "targetTop_mmol", "targetHighMmol")
            )
            if (explicitMmol.isNotEmpty()) return explicitMmol.average()

            val rawTargets = listOfNotNull(
                payload.doubleValueOf("targetBottom", "target_bottom", "targetLow"),
                payload.doubleValueOf("targetTop", "target_top", "targetHigh")
            )
            if (rawTargets.isEmpty()) return null
            return rawTargets.map(::normalizeTempTargetMmolStatic).average()
        }

        private fun normalizeTempTargetMmolStatic(raw: Double): Double {
            if (!raw.isFinite()) return raw
            return if (raw > TEMP_TARGET_MGDL_THRESHOLD) UnitConverter.mgdlToMmol(raw) else raw
        }

        private fun Map<*, *>.doubleValueOf(vararg keys: String): Double? {
            return keys.firstNotNullOfOrNull { key ->
                val value = this[key] ?: return@firstNotNullOfOrNull null
                when (value) {
                    is Number -> value.toDouble()
                    is String -> value.replace(",", ".").toDoubleOrNull()
                    else -> value.toString().replace(",", ".").toDoubleOrNull()
                }
            }
        }

        internal fun shouldBypassAdaptiveCooldownStatic(
            decision: RuleDecision,
            activeTempTargetMmol: Double?
        ): Boolean {
            if (decision.ruleId != AdaptiveTargetControllerRule.RULE_ID) return false
            val action = decision.actionProposal ?: return false
            if (!action.type.equals("temp_target", ignoreCase = true)) return false

            val activeTarget = activeTempTargetMmol
                ?.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
                ?.let { floor(it / ADAPTIVE_COOLDOWN_BYPASS_DELTA_MMOL + 0.5) * ADAPTIVE_COOLDOWN_BYPASS_DELTA_MMOL }
            if (activeTarget == null) return true

            val proposedTarget = floor(
                action.targetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL) /
                    ADAPTIVE_COOLDOWN_BYPASS_DELTA_MMOL + 0.5
            ) * ADAPTIVE_COOLDOWN_BYPASS_DELTA_MMOL
            val materialRetarget = abs(proposedTarget - activeTarget) >=
                ADAPTIVE_COOLDOWN_BYPASS_DELTA_MMOL - 1e-6
            val urgentMode = action.reason.contains("safety_force_high") ||
                action.reason.contains("safety_hypo_guard") ||
                action.reason.contains("safety_raise_target_to_five") ||
                action.reason.contains("hypo_preemptive_") ||
                action.reason.contains("activity_protection")

            return materialRetarget || urgentMode
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

                "SPARSE_REAL_FETCHED" -> IsfCrRuntimeGate(
                    applyToRuntime = false,
                    reason = "sparse_real_fetched_mode"
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
            if (runtimeGate.applyToRuntime) return 1.0
            if (current.mode == IsfCrRuntimeMode.SPARSE_REAL_FETCHED) {
                val sparseThreshold = maxOf(0.30, threshold - 0.22)
                if (current.confidence < sparseThreshold) return null
                val confidenceScale = ((current.confidence - sparseThreshold) / (threshold - sparseThreshold).coerceAtLeast(0.05))
                    .coerceIn(0.0, 1.0)
                val realFetchedCount =
                    (current.factors["therapy_history_real_fetched_insulin_30d"] ?: 0.0).coerceIn(0.0, 12.0)
                val sampleScale = ((realFetchedCount - 4.0) / 5.0).coerceIn(0.0, 1.0)
                val combinedScale = (confidenceScale * 0.6 + sampleScale * 0.4).coerceIn(0.0, 1.0)
                return (
                    ISFCR_SPARSE_BLEND_MIN +
                        (ISFCR_SPARSE_BLEND_MAX - ISFCR_SPARSE_BLEND_MIN) * combinedScale
                    ).coerceIn(ISFCR_SPARSE_BLEND_MIN, ISFCR_SPARSE_BLEND_MAX)
            }
            if (current.confidence < threshold) return null
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
                    compareBy<Forecast> { forecast ->
                        forecast.modelVersion.contains("cloud", ignoreCase = true)
                    }
                        .thenByDescending { it.ts }
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
                bucketBlend = 0.45,
                ciQuantile = 0.82,
                ciBlend = 0.45,
                ciMaxExpandScale = 1.45,
                ciMaxShrinkScale = 0.88
            )
            30 -> CalibrationConfig(
                minSamples = 18,
                gain = 0.45,
                maxUp = 0.70,
                maxDown = 0.45,
                minBucketSamples = 10,
                bucketBlend = 0.55,
                ciQuantile = 0.90,
                ciBlend = 0.60,
                ciMaxExpandScale = 1.90,
                ciMaxShrinkScale = 0.82
            )
            60 -> CalibrationConfig(
                minSamples = 12,
                gain = 0.55,
                maxUp = 1.10,
                maxDown = 0.65,
                minBucketSamples = 8,
                bucketBlend = 0.65,
                ciQuantile = 0.92,
                ciBlend = 0.70,
                ciMaxExpandScale = 2.20,
                ciMaxShrinkScale = 0.80
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
                val overallAbsErrorSamples = mutableListOf<Pair<Double, Double>>()
                val bucketAbsErrorSamples = Array(3) { mutableListOf<Pair<Double, Double>>() }
                points.forEach { point ->
                    val age = point.ageMs.coerceAtLeast(0L).toDouble()
                    val weight = exp(-age / CALIBRATION_HALF_LIFE_MS)
                    sumW += weight
                    sumErr += weight * point.errorMmol
                    val absError = abs(point.errorMmol)
                    overallAbsErrorSamples += absError to weight
                    if (point.predictedMmol.isFinite()) {
                        val bucket = forecastValueBucket(point.predictedMmol)
                        bucketErrSum[bucket] += weight * point.errorMmol
                        bucketWeightSum[bucket] += weight
                        bucketCount[bucket] += 1
                        bucketAbsErrorSamples[bucket] += absError to weight
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

                val currentHalfWidth = ((forecast.ciHigh - forecast.ciLow) / 2.0).coerceIn(0.30, 3.2)
                val overallQuantile = weightedQuantile(
                    samples = overallAbsErrorSamples,
                    quantile = cfg.ciQuantile
                ) ?: currentHalfWidth
                val bucketQuantile = if (bucketCount[bucket] >= cfg.minBucketSamples) {
                    weightedQuantile(
                        samples = bucketAbsErrorSamples[bucket],
                        quantile = cfg.ciQuantile
                    )
                } else {
                    null
                }
                val targetHalfWidthRaw = if (bucketQuantile != null) {
                    overallQuantile * (1.0 - cfg.bucketBlend) + bucketQuantile * cfg.bucketBlend
                } else {
                    overallQuantile
                }
                val targetHalfWidth = targetHalfWidthRaw
                    .coerceIn(
                        currentHalfWidth * cfg.ciMaxShrinkScale,
                        currentHalfWidth * cfg.ciMaxExpandScale
                    )
                    .coerceIn(0.30, 3.2)
                val calibratedHalfWidth = (
                    currentHalfWidth + (targetHalfWidth - currentHalfWidth) * cfg.ciBlend
                    ).coerceIn(
                        currentHalfWidth * cfg.ciMaxShrinkScale,
                        currentHalfWidth * cfg.ciMaxExpandScale
                    )
                    .coerceIn(0.30, 3.2)
                val ciChanged = abs(calibratedHalfWidth - currentHalfWidth) >= 0.02
                val biasChanged = abs(bias) >= 0.02
                if (!biasChanged && !ciChanged) return@map forecast

                val shiftedValue = (forecast.valueMmol + bias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedLow = (shiftedValue - calibratedHalfWidth).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedHigh = (shiftedValue + calibratedHalfWidth).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                if (shiftedLow > shiftedValue) shiftedLow = shiftedValue
                if (shiftedHigh < shiftedValue) shiftedHigh = shiftedValue
                val versionWithBias = if (forecast.modelVersion.contains("|calib_v1")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|calib_v1"
                }
                val version = if (ciChanged && !versionWithBias.contains("|ci_calib_v1")) {
                    "$versionWithBias|ci_calib_v1"
                } else {
                    versionWithBias
                }
                forecast.copy(
                    valueMmol = shiftedValue,
                    ciLow = shiftedLow,
                    ciHigh = shiftedHigh,
                    modelVersion = version
                )
            }
        }

        private fun weightedQuantile(
            samples: List<Pair<Double, Double>>,
            quantile: Double
        ): Double? {
            if (samples.isEmpty()) return null
            val normalizedQ = quantile.coerceIn(0.0, 1.0)
            val prepared = samples.asSequence()
                .mapNotNull { (value, weight) ->
                    if (!value.isFinite() || !weight.isFinite() || weight <= 0.0) null else value to weight
                }
                .sortedBy { it.first }
                .toList()
            if (prepared.isEmpty()) return null
            val totalWeight = prepared.sumOf { it.second }.coerceAtLeast(1e-9)
            val target = totalWeight * normalizedQ
            var cumulative = 0.0
            prepared.forEach { (value, weight) ->
                cumulative += weight
                if (cumulative >= target) return value
            }
            return prepared.last().first
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
                modelVersion = modelVersion,
                announcedCarbSteps = diagnostics.announcedCarbStep.toList()
            )
        }

        internal fun applyContextFactorForecastBiasStatic(
            forecasts: List<Forecast>,
            telemetry: Map<String, Double?>,
            latestGlucoseMmol: Double,
            pattern: io.aaps.copilot.domain.model.PatternWindow?
        ): List<Forecast> {
            if (forecasts.isEmpty()) return forecasts

            val activityFactor = resolveContextActivityFactorStatic(telemetry)
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

        internal fun physicalActivityMeasurementStatic(
            sample: TelemetrySampleEntity?,
            nowTs: Long
        ): ActivityMeasurement {
            val physical = sample?.takeIf {
                it.key == PHYSICAL_ACTIVITY_RATIO_KEY &&
                    it.source in PHYSICAL_ACTIVITY_SOURCES &&
                    it.timestamp in 0..nowTs &&
                    it.quality.uppercase(Locale.US) in PHYSICAL_ACTIVITY_QUALITIES &&
                    it.valueDouble?.isFinite() == true
            }
            return if (physical == null) {
                ActivityMeasurement(observedAt = null, activityRatio = null, coverage = ActivityCoverage.UNKNOWN)
            } else {
                ActivityMeasurement(
                    observedAt = Instant.ofEpochMilli(physical.timestamp),
                    activityRatio = physical.valueDouble,
                    coverage = ActivityCoverage.AVAILABLE
                )
            }
        }

        internal fun adjustExternalCobForSyntheticUamStatic(
            externalCobGrams: Double,
            syntheticUamCobGrams: Double,
            carbMaxGrams: Double = 60.0
        ): Double {
            return (externalCobGrams - syntheticUamCobGrams)
                .coerceIn(0.0, carbMaxGrams)
        }

        internal fun resolveFreshRuntimeInsulinTelemetryStatic(
            rows: List<TelemetrySampleEntity>,
            nowTs: Long,
            freshnessMs: Long
        ): InsulinRuntimeResolution {
            val boundedFreshnessMs = freshnessMs.coerceAtLeast(0L)
            fun TelemetrySampleEntity.isFresh(): Boolean =
                timestamp in 0..nowTs && nowTs - timestamp <= boundedFreshnessMs

            data class Packet(
                val source: String,
                val rowsByKey: Map<String, TelemetrySampleEntity>,
                val runtimeSource: InsulinRuntimeSource?,
                val authoritativeTimestamp: Long?
            ) {
                fun timedValue(key: String): TimedInsulinValue? {
                    val timestamp = authoritativeTimestamp ?: return null
                    val value = rowsByKey[key]?.valueDouble?.takeIf(Double::isFinite) ?: return null
                    return TimedInsulinValue(value, timestamp)
                }

                fun totalIob(): TimedInsulinValue? = timedValue("iob_net_units")
                    ?: timedValue("iob_units")
            }

            val packetRowsByIdentity = rows.asSequence()
                .filter { it.source in IOB_STRICT_ALLOWED_SOURCES }
                .groupBy { it.source to it.timestamp }
                .filter { (identity, packetRows) ->
                    packetRows.any { it.key in ATOMIC_AAPS_INSULIN_PACKET_MARKER_KEYS } ||
                        (
                            identity.first == "aaps_broadcast" &&
                                packetRows.any { it.key in AAPS_COMPONENT_ONLY_INSULIN_KEYS }
                            )
                }
            val newestPacketRowsBySource = packetRowsByIdentity
                .entries
                .groupBy { it.key.first }
                .mapValues { (_, groups) -> groups.maxByOrNull { it.key.second }?.value.orEmpty() }

            fun parsePacket(packetRows: List<TelemetrySampleEntity>): Packet? {
                if (packetRows.isEmpty()) return null
                val rowsByKey = packetRows.associateBy(TelemetrySampleEntity::key)
                val runtimeSource = resolveInsulinRuntimeSourceMarkerStatic(packetRows)
                val authoritativeTimestamp = rowsByKey["iob_relay_timestamp_ms"]
                    ?.valueDouble
                    ?.takeIf(Double::isFinite)
                    ?.takeIf { value ->
                        value in MIN_AUTHORITATIVE_INSULIN_TIMESTAMP_MS.toDouble()..
                            MAX_AUTHORITATIVE_INSULIN_TIMESTAMP_MS.toDouble() &&
                            value == value.toLong().toDouble()
                    }
                    ?.toLong()
                    ?.takeIf { timestamp ->
                        timestamp <= nowTs &&
                            nowTs - timestamp <= boundedFreshnessMs
                    }
                return Packet(
                    source = packetRows.first().source,
                    rowsByKey = rowsByKey,
                    runtimeSource = runtimeSource,
                    authoritativeTimestamp = authoritativeTimestamp
                )
            }

            val newestPackets = newestPacketRowsBySource.mapValues { (_, packetRows) ->
                parsePacket(packetRows)
            }
            val aapsPacket = newestPackets["aaps_broadcast"]
            val components = aapsPacket
                ?.takeIf {
                    it.runtimeSource == InsulinRuntimeSource.AAPS_COMPONENTS &&
                        it.authoritativeTimestamp != null
                }
                ?.let { packet ->
                    InsulinComponentTelemetry(
                        netIob = packet.timedValue("iob_net_units"),
                        bolusIob = packet.timedValue("iob_bolus_units"),
                        basalIob = packet.timedValue("iob_basal_units"),
                        insulinActivity = packet.timedValue("insulin_activity")
                    )
                }

            fun latestCandidate(
                sourcePredicate: (String) -> Boolean,
                excludedPackets: Set<Pair<String, Long>> = emptySet()
            ): TimedInsulinValue? = rows.asSequence()
                .filter { row ->
                    sourcePredicate(row.source) &&
                        (row.source to row.timestamp) !in excludedPackets &&
                        row.isFresh() &&
                        row.valueDouble?.isFinite() == true &&
                        row.valueDouble in -30.0..30.0 &&
                        isStrictIobKeyStatic(row.key)
                }
                .sortedWith(
                    compareByDescending<TelemetrySampleEntity> { it.timestamp }
                        .thenByDescending { normalizeStrictTherapyKeyStatic(it.key) == "iob_net_units" }
                        .thenByDescending { it.id }
                )
                .firstOrNull()
                ?.let { TimedInsulinValue(requireNotNull(it.valueDouble), it.timestamp) }

            val packetIdentities = packetRowsByIdentity.keys
            val legacy = when {
                aapsPacket == null -> latestCandidate(
                    sourcePredicate = { source -> source == "aaps_broadcast" }
                )
                aapsPacket.runtimeSource == InsulinRuntimeSource.LEGACY_AAPS &&
                    aapsPacket.authoritativeTimestamp != null -> aapsPacket.totalIob()
                else -> null
            }
            val packetExternal = newestPackets.values.asSequence()
                .filterNotNull()
                .filter {
                    it.source != "aaps_broadcast" &&
                        it.runtimeSource == InsulinRuntimeSource.EXTERNAL_ESTIMATE &&
                        it.authoritativeTimestamp != null
                }
                .mapNotNull(Packet::totalIob)
                .maxByOrNull(TimedInsulinValue::timestamp)
            val standaloneExternal = latestCandidate(
                sourcePredicate = { source ->
                    source != "aaps_broadcast" && source in IOB_STRICT_ALLOWED_SOURCES
                },
                excludedPackets = packetIdentities
            )
            val external = listOfNotNull(packetExternal, standaloneExternal)
                .maxByOrNull(TimedInsulinValue::timestamp)
            return InsulinRuntimeSnapshotResolver.resolve(
                nowTimestamp = nowTs,
                components = components,
                legacyIob = legacy,
                externalEstimate = external
            )
        }

        internal fun applyAtomicAapsInsulinPacketStatic(
            rows: List<TelemetrySampleEntity>,
            latestByKey: MutableMap<String, Double?>,
            latestTimestampByKey: MutableMap<String, Long>
        ): Long? {
            val packetTimestamp = rows.asSequence()
                .filter { row ->
                    row.source == "aaps_broadcast" &&
                        row.key in ATOMIC_AAPS_INSULIN_PACKET_MARKER_KEYS
                }
                .maxOfOrNull(TelemetrySampleEntity::timestamp)
                ?: return null
            val packet = rows.asSequence()
                .filter { row ->
                    row.source == "aaps_broadcast" &&
                        row.timestamp == packetTimestamp
                }
                .associateBy(TelemetrySampleEntity::key)
            val authoritativeTimestamp = packet["iob_relay_timestamp_ms"]
                ?.valueDouble
                ?.takeIf(Double::isFinite)
                ?.takeIf { value ->
                    value in MIN_AUTHORITATIVE_INSULIN_TIMESTAMP_MS.toDouble()..
                        MAX_AUTHORITATIVE_INSULIN_TIMESTAMP_MS.toDouble() &&
                        value == value.toLong().toDouble()
                }
                ?.toLong()
                ?: return null
            if (resolveInsulinRuntimeSourceMarkerStatic(packet.values.toList()) == null) return null
            ATOMIC_AAPS_INSULIN_PACKET_KEYS.forEach { key ->
                latestByKey[key] = packet[key]?.valueDouble
                latestTimestampByKey[key] = authoritativeTimestamp
            }
            return authoritativeTimestamp
        }

        internal fun applyCobIobForecastBiasStatic(
            forecasts: List<Forecast>,
            cobGrams: Double?,
            diagnosticIobUnits: Double?,
            latestGlucoseMmol: Double? = null,
            uamActive: Boolean? = null,
            insulinCycleContext: InsulinCycleContext = InsulinCycleContext(
                cycleTimestamp = 0L,
                causalReferenceTimestamp = 0L,
                snapshot = null,
                alignedSnapshot = null,
                safetyIobUnits = null,
                modeledActiveInsulinUnits = null,
                signedResidualUnits = null,
                residualComparisonAllowed = false,
                residualComparisonReason = "diagnostic_only"
            ),
            isfMmolPerUnit: Double? = null,
        ): List<Forecast> {
            if (forecasts.isEmpty()) return forecasts
            val cob = (cobGrams ?: 0.0).coerceIn(0.0, 400.0)
            // The loose scalar is retained only for diagnostics/UI parity. Forecast insulin
            // effects come exclusively from the causal cycle context shared with the engine.
            val runtimeSnapshot = insulinCycleContext.alignedSnapshot
                ?.takeIf { insulinCycleContext.residualComparisonAllowed && it.netIobUnits.isFinite() }
            val signedResidualUnits = insulinCycleContext.signedResidualUnits
                ?.takeIf { insulinCycleContext.residualComparisonAllowed && it.isFinite() }
                ?.coerceIn(-30.0, 30.0)
                ?: 0.0
            val residualPositiveIob = signedResidualUnits.coerceAtLeast(0.0)
            val snapshotInputPresent = runtimeSnapshot != null && insulinCycleContext.residualComparisonAllowed
            if (cob <= 0.0 && abs(signedResidualUnits) <= 1e-6) return forecasts
            val latestGlucose = latestGlucoseMmol?.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            val hasUam = uamActive == true
            val lowRisk = !hasUam &&
                latestGlucose != null &&
                latestGlucose <= COB_IOB_LOW_RISK_MMOL &&
                residualPositiveIob >= COB_IOB_LOW_RISK_MIN_IOB &&
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
                val baseCobBias = (cob * cobGain).coerceIn(0.0, COB_FORECAST_BIAS_MAX)
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

                val residualBias = runtimeSnapshot?.let { snapshot ->
                    val isf = isfMmolPerUnit
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(0.2, 18.0)
                        ?: DEFAULT_FORECAST_ISF_MMOL_PER_UNIT
                    val horizonFraction = (forecast.horizonMinutes / 60.0).coerceIn(0.08, 1.25)
                    val activityScale = snapshot.insulinActivity
                        ?.takeIf { it.isFinite() }
                        ?.let { 1.0 + (abs(it) * 15.0).coerceIn(0.0, 0.25) }
                        ?: 1.0
                    (-signedResidualUnits * isf * horizonFraction * 0.12 * activityScale)
                        .takeIf(Double::isFinite)
                        ?.coerceIn(COMPONENT_IOB_BIAS_MIN, COMPONENT_IOB_BIAS_MAX)
                        ?: 0.0
                } ?: 0.0

                val horizonLowRiskScale = when (forecast.horizonMinutes) {
                    5 -> 0.45
                    30 -> 0.75
                    60 -> 1.0
                    else -> (forecast.horizonMinutes / 60.0).coerceIn(0.45, 1.1)
                }
                val extraLowGuardDown = if (
                    !hasUam &&
                    hardLowRisk &&
                    residualPositiveIob >= COB_IOB_EXTRA_GUARD_MIN_IOB &&
                    cob >= COB_IOB_EXTRA_GUARD_MIN_COB
                ) {
                    val glucoseDelta = (COB_IOB_HARD_LOW_MMOL - (latestGlucose ?: forecast.valueMmol))
                        .coerceAtLeast(0.0)
                    var guard = (
                        (residualPositiveIob - COB_IOB_EXTRA_GUARD_MIN_IOB).coerceAtLeast(0.0) * IOB_LOW_GUARD_GAIN +
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

                val totalBias = (cobBias - extraLowGuardDown + residualBias)
                    .coerceIn(FORECAST_BIAS_MIN, FORECAST_BIAS_MAX)
                if (abs(totalBias) < 1e-6) return@map forecast

                val shiftedValue = (forecast.valueMmol + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedLow = (forecast.ciLow + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedHigh = (forecast.ciHigh + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                if (shiftedLow > shiftedValue) shiftedLow = shiftedValue
                if (shiftedHigh < shiftedValue) shiftedHigh = shiftedValue
                val legacyVersion = if (forecast.modelVersion.contains("|cob_iob_bias_v1")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|cob_iob_bias_v1"
                }
                val version = if (snapshotInputPresent && !legacyVersion.contains("|component_iob_residual_v3")) {
                    "$legacyVersion|component_iob_residual_v3"
                } else {
                    legacyVersion
                }
                forecast.copy(
                    valueMmol = shiftedValue,
                    ciLow = shiftedLow,
                    ciHigh = shiftedHigh,
                    modelVersion = version
                )
            }
        }

        internal fun resolveCycleCausalReferenceTimestampStatic(
            glucose: List<GlucosePoint>
        ): Long = glucose.asSequence()
            .map(GlucosePoint::ts)
            .filter { it > 0L }
            .maxOrNull()
            ?: 0L

        internal fun resolveTargetManagerSafetyIobStatic(
            insulinSnapshot: InsulinRuntimeSnapshot?,
            cycleTimestamp: Long,
            freshnessMs: Long
        ): Double? {
            val snapshot = insulinSnapshot
                ?.takeIf(::isInsulinSnapshotSafetyQualifiedStatic)
                ?: return null
            val boundedFreshnessMs = freshnessMs.coerceAtLeast(0L)
            if (
                cycleTimestamp <= 0L ||
                snapshot.timestamp !in 1..cycleTimestamp ||
                cycleTimestamp - snapshot.timestamp > boundedFreshnessMs
            ) {
                return null
            }
            return snapshot.effectivePositiveIobUnits
                .takeIf(Double::isFinite)
                ?.coerceIn(0.0, 30.0)
        }

        internal fun buildInsulinCycleContextStatic(
            cycleTimestamp: Long,
            causalReferenceTimestamp: Long,
            insulinSnapshot: InsulinRuntimeSnapshot?,
            modeledActiveInsulinUnits: Double?,
            modeledActiveInsulinAtSnapshotUnits: Double? = null,
            therapyModelAvailable: Boolean,
            freshnessMs: Long
        ): InsulinCycleContext {
            val safetyIobUnits = resolveTargetManagerSafetyIobStatic(
                insulinSnapshot = insulinSnapshot,
                cycleTimestamp = cycleTimestamp,
                freshnessMs = freshnessMs
            )
            val modeledUnits = modeledActiveInsulinUnits
                ?.takeIf { therapyModelAvailable && it.isFinite() && it >= 0.0 }
                ?.coerceIn(0.0, 30.0)
            val causalTimestampValid = causalReferenceTimestamp in 1..cycleTimestamp
            val modeledAtSnapshotUnits = modeledActiveInsulinAtSnapshotUnits
                ?.takeIf { therapyModelAvailable && it.isFinite() && it >= 0.0 }
                ?.coerceIn(0.0, 30.0)
            val alignment = alignInsulinSnapshotToCausalStatic(
                snapshot = insulinSnapshot,
                causalReferenceTimestamp = causalReferenceTimestamp,
                modeledAtCausalUnits = modeledUnits,
                modeledAtSnapshotUnits = modeledAtSnapshotUnits
            )
            val alignedSnapshot = alignment.snapshot
            val residualReason: String
            val residualUnits: Double?
            when {
                insulinSnapshot == null -> {
                    residualReason = "snapshot_missing"
                    residualUnits = null
                }
                !causalTimestampValid -> {
                    residualReason = "causal_timestamp_invalid"
                    residualUnits = null
                }
                alignedSnapshot == null -> {
                    residualReason = alignment.reason
                    residualUnits = null
                }
                therapyModelAvailable && modeledUnits == null -> {
                    residualReason = "modeled_insulin_missing"
                    residualUnits = null
                }
                modeledUnits != null -> {
                    residualReason = alignment.reason
                    residualUnits = (alignedSnapshot.netIobUnits - modeledUnits)
                        .takeIf(Double::isFinite)
                        ?.coerceIn(-30.0, 30.0)
                }
                else -> {
                    residualReason = alignment.reason
                    residualUnits = alignedSnapshot.netIobUnits
                        .takeIf(Double::isFinite)
                        ?.coerceIn(-30.0, 30.0)
                }
            }
            return InsulinCycleContext(
                cycleTimestamp = cycleTimestamp,
                causalReferenceTimestamp = causalReferenceTimestamp,
                snapshot = insulinSnapshot,
                alignedSnapshot = alignedSnapshot,
                safetyIobUnits = safetyIobUnits,
                modeledActiveInsulinUnits = modeledUnits,
                signedResidualUnits = residualUnits,
                residualComparisonAllowed = residualUnits != null,
                residualComparisonReason = residualReason
            )
        }

        private data class InsulinSnapshotAlignment(
            val snapshot: InsulinRuntimeSnapshot?,
            val reason: String
        )

        private fun alignInsulinSnapshotToCausalStatic(
            snapshot: InsulinRuntimeSnapshot?,
            causalReferenceTimestamp: Long,
            modeledAtCausalUnits: Double?,
            modeledAtSnapshotUnits: Double?
        ): InsulinSnapshotAlignment {
            snapshot ?: return InsulinSnapshotAlignment(null, "snapshot_missing")
            if (causalReferenceTimestamp <= 0L || snapshot.timestamp <= 0L) {
                return InsulinSnapshotAlignment(null, "causal_timestamp_invalid")
            }
            val offsetMs = snapshot.timestamp - causalReferenceTimestamp
            if (kotlin.math.abs(offsetMs) > INSULIN_CAUSAL_NORMALIZATION_MAX_MS) {
                return InsulinSnapshotAlignment(null, "snapshot_offset_exceeds_normalization_bound")
            }
            if (offsetMs == 0L) {
                return InsulinSnapshotAlignment(snapshot, "authoritative_local_aligned")
            }

            val modelRatio = if (
                modeledAtCausalUnits != null &&
                modeledAtSnapshotUnits != null &&
                modeledAtSnapshotUnits > INSULIN_MODEL_RATIO_EPSILON
            ) {
                modeledAtCausalUnits / modeledAtSnapshotUnits
            } else {
                null
            }
            if (modelRatio != null) {
                if (!modelRatio.isFinite() || modelRatio !in INSULIN_MODEL_RATIO_MIN..INSULIN_MODEL_RATIO_MAX) {
                    return InsulinSnapshotAlignment(null, "snapshot_model_ratio_out_of_bounds")
                }
                val scaledBolus = snapshot.bolusIobUnits?.times(modelRatio)
                val scaledBasal = snapshot.basalIobUnits?.times(modelRatio)
                val scaledNet = snapshot.netIobUnits * modelRatio
                val scaledEffective = if (scaledBolus != null && scaledBasal != null) {
                    scaledBolus.coerceAtLeast(0.0) + scaledBasal.coerceAtLeast(0.0)
                } else {
                    scaledNet.coerceAtLeast(0.0)
                }
                val aligned = snapshot.copy(
                    timestamp = causalReferenceTimestamp,
                    netIobUnits = scaledNet,
                    bolusIobUnits = scaledBolus,
                    basalIobUnits = scaledBasal,
                    insulinActivity = snapshot.insulinActivity?.times(modelRatio),
                    effectivePositiveIobUnits = scaledEffective
                )
                if (!isCausalAlignedSnapshotBoundedStatic(aligned)) {
                    return InsulinSnapshotAlignment(null, "snapshot_model_alignment_out_of_bounds")
                }
                return InsulinSnapshotAlignment(aligned, "authoritative_local_ratio_aligned")
            }

            val activity = snapshot.insulinActivity
                ?.takeIf { snapshot.source == InsulinRuntimeSource.AAPS_COMPONENTS && it.isFinite() && it >= 0.0 }
            val bolus = snapshot.bolusIobUnits
            val basal = snapshot.basalIobUnits
            if (offsetMs <= 0L || activity == null || bolus == null || basal == null) {
                return InsulinSnapshotAlignment(null, "snapshot_normalization_evidence_missing")
            }
            val offsetMinutes = offsetMs / 60_000.0
            val correctionUnits = activity * offsetMinutes
            if (!correctionUnits.isFinite() || correctionUnits !in 0.0..INSULIN_ACTIVITY_NORMALIZATION_MAX_UNITS) {
                return InsulinSnapshotAlignment(null, "snapshot_activity_normalization_out_of_bounds")
            }
            val alignedBolus = bolus + correctionUnits
            val alignedBasal = basal
            val alignedNet = alignedBolus + alignedBasal
            val aligned = snapshot.copy(
                timestamp = causalReferenceTimestamp,
                netIobUnits = alignedNet,
                bolusIobUnits = alignedBolus,
                basalIobUnits = alignedBasal,
                effectivePositiveIobUnits = alignedBolus.coerceAtLeast(0.0) +
                    alignedBasal.coerceAtLeast(0.0)
            )
            if (!isCausalAlignedSnapshotBoundedStatic(aligned)) {
                return InsulinSnapshotAlignment(null, "snapshot_activity_alignment_out_of_bounds")
            }
            return InsulinSnapshotAlignment(aligned, "aaps_activity_backward_aligned")
        }

        private fun isCausalAlignedSnapshotBoundedStatic(snapshot: InsulinRuntimeSnapshot): Boolean =
            snapshot.netIobUnits.isFinite() &&
                snapshot.netIobUnits in -INSULIN_NORMALIZED_IOB_MAX_UNITS..INSULIN_NORMALIZED_IOB_MAX_UNITS &&
                snapshot.effectivePositiveIobUnits.isFinite() &&
                snapshot.effectivePositiveIobUnits in 0.0..INSULIN_NORMALIZED_IOB_MAX_UNITS &&
                snapshot.bolusIobUnits?.let {
                    it.isFinite() && it in 0.0..INSULIN_NORMALIZED_IOB_MAX_UNITS
                } != false &&
                snapshot.basalIobUnits?.let {
                    it.isFinite() && it in -INSULIN_NORMALIZED_IOB_MAX_UNITS..INSULIN_NORMALIZED_IOB_MAX_UNITS
                } != false &&
                snapshot.insulinActivity?.let {
                    it.isFinite() && it in -INSULIN_NORMALIZED_ACTIVITY_MAX..INSULIN_NORMALIZED_ACTIVITY_MAX
                } != false

        internal fun modelSameCycleActiveInsulinStatic(
            engine: HybridPredictionEngine,
            profileIdRaw: String,
            resolvedEffectiveDiaHours: Double,
            baseOnsetMinutes: Double,
            realOnsetMinutes: Double,
            therapyEvents: List<io.aaps.copilot.domain.model.TherapyEvent>,
            nowTs: Long
        ): HybridPredictionEngine.ModeledActiveInsulinEvidence {
            engine.setInsulinProfile(InsulinActionProfileId.fromRaw(profileIdRaw))
            engine.setInsulinDurationHours(resolvedEffectiveDiaHours)
            engine.setInsulinOnsetMinutes(
                baseOnsetMinutes = baseOnsetMinutes,
                realOnsetMinutes = realOnsetMinutes
            )
            return engine.modeledActiveInsulinEvidence(therapyEvents, nowTs)
        }

        internal fun resolveForecastIsfStatic(telemetry: Map<String, Double?>): Double = sequenceOf(
            telemetry["isf_runtime_selected_value"],
            telemetry["isf_realtime_value"],
            telemetry["isf_value"]
        )
            .filterNotNull()
            .firstOrNull { it.isFinite() && it in 0.2..18.0 }
            ?: DEFAULT_FORECAST_ISF_MMOL_PER_UNIT

        internal fun applyCircadianPatternForecastBiasStatic(
            forecasts: List<Forecast>,
            prior: CircadianForecastPrior?,
            latestGlucoseMmol: Double,
            weight30: Double,
            weight60: Double
        ): List<Forecast> {
            if (forecasts.isEmpty() || prior == null || prior.staleBlocked) return forecasts
            val currentGlucose = latestGlucoseMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            return forecasts.map { forecast ->
                val weight = patternPriorWeightForHorizonStatic(
                    horizonMinutes = forecast.horizonMinutes,
                    prior = prior,
                    weight30 = weight30,
                    weight60 = weight60
                )
                if (weight <= 1e-6) return@map forecast

                val priorTarget = (currentGlucose + priorDeltaForHorizonStatic(prior, forecast.horizonMinutes))
                    .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                val replayBias = replayBiasForHorizonStatic(prior, forecast.horizonMinutes)
                val blendedValueRaw = forecast.valueMmol * (1.0 - weight) + priorTarget * weight + replayBias
                val shiftCap = when {
                    forecast.horizonMinutes <= 5 -> 0.12
                    forecast.horizonMinutes <= 30 -> 0.45
                    else -> 0.75
                }
                val shift = (blendedValueRaw - forecast.valueMmol).coerceIn(-shiftCap, shiftCap)
                if (abs(shift) < 1e-6) return@map forecast
                val blendedValue = (forecast.valueMmol + shift).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)

                var shiftedLow = (forecast.ciLow + shift).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedHigh = (forecast.ciHigh + shift).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                if (shiftedLow > blendedValue) shiftedLow = blendedValue
                if (shiftedHigh < blendedValue) shiftedHigh = blendedValue
                val version = if (forecast.modelVersion.contains("|circadian_v2")) {
                    forecast.modelVersion
                } else {
                    forecast.modelVersion
                        .replace("|circadian_v1", "")
                        .let { "$it|circadian_v2" }
                }
                forecast.copy(
                    valueMmol = blendedValue,
                    ciLow = shiftedLow,
                    ciHigh = shiftedHigh,
                    modelVersion = version
                )
            }
        }

        internal fun patternPriorWeightForHorizonStatic(
            horizonMinutes: Int,
            prior: CircadianForecastPrior,
            weight30: Double,
            weight60: Double
        ): Double {
            val replayMultiplier = when {
                horizonMinutes < 30 -> 1.0
                horizonMinutes < 60 -> when (prior.replayBucketStatus30) {
                    CircadianReplayBucketStatus.HELPFUL -> 1.0
                    CircadianReplayBucketStatus.NEUTRAL -> 0.70
                    CircadianReplayBucketStatus.HARMFUL -> 0.35
                    CircadianReplayBucketStatus.INSUFFICIENT -> 0.0
                }
                else -> when (prior.replayBucketStatus60) {
                    CircadianReplayBucketStatus.HELPFUL -> 1.0
                    CircadianReplayBucketStatus.NEUTRAL -> 0.70
                    CircadianReplayBucketStatus.HARMFUL -> 0.35
                    CircadianReplayBucketStatus.INSUFFICIENT -> 0.0
                }
            }
            val helpfulBoost = when {
                horizonMinutes < 30 -> 1.0
                horizonMinutes < 60 -> CircadianReplaySummaryEvaluator.replayHelpfulBoost(
                    horizonMinutes = 30,
                    sampleCount = prior.replaySampleCount30,
                    maeImprovementMmol = prior.replayMaeImprovement30,
                    winRate = prior.replayWinRate30,
                    acuteAttenuation = prior.acuteAttenuation
                )
                else -> CircadianReplaySummaryEvaluator.replayHelpfulBoost(
                    horizonMinutes = 60,
                    sampleCount = prior.replaySampleCount60,
                    maeImprovementMmol = prior.replayMaeImprovement60,
                    winRate = prior.replayWinRate60,
                    acuteAttenuation = prior.acuteAttenuation
                )
            }
            return CircadianReplaySummaryEvaluator.weightForHorizon(
                horizonMinutes = horizonMinutes,
                confidence = prior.confidence,
                qualityScore = prior.qualityScore,
                stabilityScore = prior.stabilityScore,
                horizonQuality = horizonQualityForHorizonStatic(prior, horizonMinutes),
                acuteAttenuation = prior.acuteAttenuation,
                replayMultiplier = (replayMultiplier * helpfulBoost).coerceIn(0.0, 1.25),
                weight30 = weight30,
                weight60 = weight60
            )
        }

        internal fun priorDeltaForHorizonStatic(
            prior: CircadianForecastPrior,
            horizonMinutes: Int
        ): Double {
            val base = when {
                horizonMinutes <= 5 -> prior.delta15 * 0.33
                horizonMinutes <= 15 -> prior.delta15
                horizonMinutes <= 30 -> {
                    val ratio = ((horizonMinutes - 15).toDouble() / 15.0).coerceIn(0.0, 1.0)
                    prior.delta15 + (prior.delta30 - prior.delta15) * ratio
                }
                horizonMinutes <= 60 -> {
                    val ratio = ((horizonMinutes - 30).toDouble() / 30.0).coerceIn(0.0, 1.0)
                    prior.delta30 + (prior.delta60 - prior.delta30) * ratio
                }
                else -> prior.delta60
            }
            val reversion = when {
                horizonMinutes <= 15 -> 0.0
                horizonMinutes <= 30 -> {
                    val ratio = ((horizonMinutes - 15).toDouble() / 15.0).coerceIn(0.0, 1.0)
                    prior.medianReversion30 * ratio
                }
                horizonMinutes <= 60 -> {
                    val ratio = ((horizonMinutes - 30).toDouble() / 30.0).coerceIn(0.0, 1.0)
                    prior.medianReversion30 + (prior.medianReversion60 - prior.medianReversion30) * ratio
                }
                else -> prior.medianReversion60
            }
            return base + reversion
        }

        internal fun replayBiasForHorizonStatic(
            prior: CircadianForecastPrior,
            horizonMinutes: Int
        ): Double {
            return when {
                horizonMinutes < 30 -> 0.0
                horizonMinutes < 60 -> if (prior.replayBucketStatus30 == CircadianReplayBucketStatus.HARMFUL) {
                    0.0
                } else {
                    (prior.replayBias30 * replayBiasStrengthForHorizonStatic(prior, horizonMinutes)).coerceIn(-0.20, 0.20)
                }
                else -> if (prior.replayBucketStatus60 == CircadianReplayBucketStatus.HARMFUL) {
                    0.0
                } else {
                    (prior.replayBias60 * replayBiasStrengthForHorizonStatic(prior, horizonMinutes)).coerceIn(-0.35, 0.35)
                }
            }
        }

        private fun horizonQualityForHorizonStatic(
            prior: CircadianForecastPrior,
            horizonMinutes: Int
        ): Double {
            return when {
                horizonMinutes <= 15 -> 1.0
                horizonMinutes <= 30 -> {
                    val ratio = ((horizonMinutes - 15).toDouble() / 15.0).coerceIn(0.0, 1.0)
                    1.0 + (prior.horizonQuality30 - 1.0) * ratio
                }
                horizonMinutes <= 60 -> {
                    val ratio = ((horizonMinutes - 30).toDouble() / 30.0).coerceIn(0.0, 1.0)
                    prior.horizonQuality30 + (prior.horizonQuality60 - prior.horizonQuality30) * ratio
                }
                else -> prior.horizonQuality60
            }.coerceIn(0.25, 1.0)
        }

        private fun replayBiasStrengthForHorizonStatic(
            prior: CircadianForecastPrior,
            horizonMinutes: Int
        ): Double {
            val horizonQuality = horizonQualityForHorizonStatic(prior, horizonMinutes)
            val replayMultiplier = when {
                horizonMinutes < 60 -> when (prior.replayBucketStatus30) {
                    CircadianReplayBucketStatus.HELPFUL -> 1.0
                    CircadianReplayBucketStatus.NEUTRAL -> 0.70
                    CircadianReplayBucketStatus.HARMFUL -> 0.0
                    CircadianReplayBucketStatus.INSUFFICIENT -> 0.45
                }
                else -> when (prior.replayBucketStatus60) {
                    CircadianReplayBucketStatus.HELPFUL -> 1.0
                    CircadianReplayBucketStatus.NEUTRAL -> 0.70
                    CircadianReplayBucketStatus.HARMFUL -> 0.0
                    CircadianReplayBucketStatus.INSUFFICIENT -> 0.45
                }
            }
            val helpfulBoost = when {
                horizonMinutes < 60 -> CircadianReplaySummaryEvaluator.replayHelpfulBoost(
                    horizonMinutes = 30,
                    sampleCount = prior.replaySampleCount30,
                    maeImprovementMmol = prior.replayMaeImprovement30,
                    winRate = prior.replayWinRate30,
                    acuteAttenuation = prior.acuteAttenuation
                )
                else -> CircadianReplaySummaryEvaluator.replayHelpfulBoost(
                    horizonMinutes = 60,
                    sampleCount = prior.replaySampleCount60,
                    maeImprovementMmol = prior.replayMaeImprovement60,
                    winRate = prior.replayWinRate60,
                    acuteAttenuation = prior.acuteAttenuation
                )
            }
            return ((replayMultiplier * helpfulBoost).coerceIn(0.0, 1.25) * prior.acuteAttenuation * horizonQuality).coerceIn(0.0, 1.0)
        }

        internal fun hasSensorLagShadowRuleChangedStatic(
            baseline: List<RuleDecision>,
            candidate: List<RuleDecision>
        ): Boolean {
            val baselineWinner = baseline.firstOrNull { it.state == RuleState.TRIGGERED && it.actionProposal != null }
            val candidateWinner = candidate.firstOrNull { it.state == RuleState.TRIGGERED && it.actionProposal != null }
            return when {
                baselineWinner == null && candidateWinner == null -> false
                baselineWinner == null || candidateWinner == null -> true
                baselineWinner.ruleId != candidateWinner.ruleId -> true
                baselineWinner.actionProposal?.type != candidateWinner.actionProposal?.type -> true
                else -> abs(
                    (candidateWinner.actionProposal?.targetMmol ?: 0.0) -
                        (baselineWinner.actionProposal?.targetMmol ?: 0.0)
                ) >= 0.05
            }
        }

        internal fun resolveSensorLagShadowTargetDeltaMmolStatic(
            baseline: List<RuleDecision>,
            candidate: List<RuleDecision>
        ): Double? {
            val baselineTarget = baseline
                .firstOrNull { it.state == RuleState.TRIGGERED && it.actionProposal != null }
                ?.actionProposal
                ?.targetMmol
            val candidateTarget = candidate
                .firstOrNull { it.state == RuleState.TRIGGERED && it.actionProposal != null }
                ?.actionProposal
                ?.targetMmol
            return when {
                baselineTarget == null && candidateTarget == null -> null
                baselineTarget == null -> candidateTarget
                candidateTarget == null -> -baselineTarget
                else -> candidateTarget - baselineTarget
            }
        }

        internal fun buildSensorLagTelemetryRowsStatic(
            nowTs: Long,
            estimate: SensorLagEstimate,
            latestGlucoseInput: GlucoseInputMetadata?,
            controlForecasts: List<Forecast>,
            candidateForecasts: List<Forecast>,
            shadowRuleChanged: Boolean?,
            shadowTargetDeltaMmol: Double?
        ): List<TelemetrySampleEntity> = buildList {
            fun addNumeric(key: String, value: Double?, unit: String? = null) {
                add(
                    TelemetrySampleEntity(
                        id = "tm-copilot-sensor-lag-$key-$nowTs",
                        timestamp = nowTs,
                        source = "copilot_sensor_lag",
                        key = key,
                        valueDouble = value,
                        valueText = null,
                        unit = unit,
                        quality = if (value == null) "STALE" else "OK"
                    )
                )
            }

            fun addText(key: String, value: String?) {
                add(
                    TelemetrySampleEntity(
                        id = "tm-copilot-sensor-lag-$key-$nowTs",
                        timestamp = nowTs,
                        source = "copilot_sensor_lag",
                        key = key,
                        valueDouble = null,
                        valueText = value?.trim()?.takeIf { it.isNotBlank() },
                        unit = null,
                        quality = if (value.isNullOrBlank()) "STALE" else "OK"
                    )
                )
            }

            addNumeric("sensor_lag_age_hours", estimate.ageHours, "h")
            addText("sensor_lag_age_source", estimate.ageSource.name.lowercase(Locale.US))
            addNumeric("sensor_lag_minutes", estimate.lagMinutes, "min")
            addText("sensor_lag_wear_bucket", estimate.wearBucket)
            addNumeric("sensor_lag_source_confidence", estimate.sourceConfidence)
            addNumeric("sensor_lag_trend_consistency", estimate.trendConsistency)
            addNumeric("sensor_lag_replay_multiplier", estimate.replayMultiplier)
            addNumeric("sensor_lag_effective_lag_minutes", estimate.effectiveLagMinutes, "min")
            addNumeric("sensor_lag_effective_correction_cap", estimate.effectiveCorrectionCap, "mmol/L")
            addNumeric("sensor_lag_correction_mmol", estimate.correctionMmol, "mmol/L")
            addNumeric("sensor_lag_corrected_glucose_mmol", estimate.correctedGlucoseMmol, "mmol/L")
            addNumeric("sensor_lag_confidence", estimate.confidence)
            addNumeric("sensor_lag_active", if (estimate.mode == SensorLagCorrectionMode.ACTIVE) 1.0 else 0.0)
            addText("sensor_lag_mode", estimate.mode.name)
            addText("sensor_lag_disable_reason", estimate.disableReason)
            addNumeric("sensor_lag_shadow_rule_changed", shadowRuleChanged?.let { if (it) 1.0 else 0.0 })
            addNumeric("sensor_lag_shadow_target_delta_mmol", shadowTargetDeltaMmol, "mmol/L")
            controlForecasts
                .filter { it.horizonMinutes in setOf(5, 30, 60) }
                .forEach { forecast ->
                    addNumeric(
                        key = "sensor_lag_control_forecast_${forecast.horizonMinutes}m",
                        value = forecast.valueMmol,
                        unit = "mmol/L"
                    )
                }
            if (estimate.mode != SensorLagCorrectionMode.OFF) {
                candidateForecasts
                    .filter { it.horizonMinutes in setOf(5, 30, 60) }
                    .forEach { forecast ->
                        addNumeric(
                            key = "sensor_lag_candidate_forecast_${forecast.horizonMinutes}m",
                            value = forecast.valueMmol,
                            unit = "mmol/L"
                        )
                    }
            }
            addText("glucose_input_key", latestGlucoseInput?.key)
            addText("glucose_input_kind", latestGlucoseInput?.kind)
        }

        internal fun buildGlucoseAlertTelemetryRowsStatic(
            nowTs: Long,
            decision: GlucoseAlertDecision,
            persisted: GlucoseAlertRuntimeState,
            delivery: GlucoseAlertDeliveryResult,
            insulinSnapshot: InsulinRuntimeSnapshot? = null,
            sensitivitySnapshot: SensitivityRuntimeSnapshot? = null
        ): List<TelemetrySampleEntity> = buildList {
            fun addNumeric(key: String, value: Double?, unit: String? = null) {
                add(
                    TelemetrySampleEntity(
                        id = "tm-copilot-glucose-alert-$key-$nowTs",
                        timestamp = nowTs,
                        source = "copilot_glucose_alert",
                        key = key,
                        valueDouble = value,
                        valueText = null,
                        unit = unit,
                        quality = if (value == null) "STALE" else "OK"
                    )
                )
            }

            fun addText(key: String, value: String?) {
                add(
                    TelemetrySampleEntity(
                        id = "tm-copilot-glucose-alert-$key-$nowTs",
                        timestamp = nowTs,
                        source = "copilot_glucose_alert",
                        key = key,
                        valueDouble = null,
                        valueText = value?.trim()?.takeIf { it.isNotBlank() },
                        unit = null,
                        quality = if (value.isNullOrBlank()) "STALE" else "OK"
                    )
                )
            }

            addText("glucose_alert_state", decision.state.name)
            addText("glucose_alert_direction", decision.direction?.name)
            addNumeric("glucose_alert_soft_active", if (decision.softActive) 1.0 else 0.0)
            addNumeric("glucose_alert_strong_active", if (decision.strongActive) 1.0 else 0.0)
            addNumeric("glucose_alert_soft_last_ts", persisted.lastSoftAlertAtTs.toDouble())
            addNumeric("glucose_alert_strong_last_ts", persisted.lastStrongAlertAtTs.toDouble())
            addNumeric("glucose_alert_last_stage_change_ts", persisted.lastStageChangeTs.toDouble())
            addNumeric("glucose_alert_muted_until_ts", persisted.mutedUntilTs.toDouble())
            addNumeric("glucose_alert_muted_active", if (persisted.mutedUntilTs > nowTs) 1.0 else 0.0)
            addNumeric("glucose_alert_predicted_minutes_to_low", decision.predictedMinutesToLow?.toDouble(), "min")
            addNumeric("glucose_alert_current_glucose_mmol", decision.currentGlucoseMmol, "mmol/L")
            addNumeric("glucose_alert_pred5", decision.pred5, "mmol/L")
            addNumeric("glucose_alert_pred30", decision.pred30, "mmol/L")
            addNumeric("glucose_alert_pred60", decision.pred60, "mmol/L")
            addNumeric("glucose_alert_ci_low30", decision.ciLow30, "mmol/L")
            addNumeric("glucose_alert_ci_high30", decision.ciHigh30, "mmol/L")
            addNumeric("glucose_alert_trend_delta5_mmol", decision.trendDelta5Mmol, "mmol/5m")
            addNumeric("glucose_alert_repeat_suppressed_by_trend", if (decision.repeatSuppressedByTrend) 1.0 else 0.0)
            addNumeric("glucose_alert_low_threshold", decision.lowThreshold, "mmol/L")
            addNumeric("glucose_alert_high_threshold", decision.highThreshold, "mmol/L")
            addNumeric("glucose_alert_urgent_low_threshold", decision.urgentLowThreshold, "mmol/L")
            addNumeric(
                "glucose_alert_notification_permission_granted",
                if (delivery.notificationPermissionGranted) 1.0 else 0.0
            )
            addNumeric("glucose_alert_notification_posted", if (delivery.notificationPosted) 1.0 else 0.0)
            addNumeric("glucose_alert_vibration_attempted", if (delivery.vibrationAttempted) 1.0 else 0.0)
            addNumeric("glucose_alert_audio_played", if (delivery.audioPlayed) 1.0 else 0.0)
            addText("glucose_alert_disable_reason", decision.disableReason ?: delivery.degradedReason)
            addText("glucose_alert_audio_clip", delivery.audioClipLabel)
            addNumeric("glucose_alert_audio_fallback_used", if (delivery.audioFallbackUsed) 1.0 else 0.0)
            addNumeric("glucose_alert_audio_start_ms", delivery.audioStartMs?.toDouble(), "ms")
            addNumeric("glucose_alert_audio_duration_ms", delivery.audioDurationMs?.toDouble(), "ms")
            insulinSnapshot?.let { snapshot ->
                addNumeric("glucose_alert_iob_net_units", snapshot.netIobUnits, "U")
                addNumeric("glucose_alert_iob_effective_positive_units", snapshot.effectivePositiveIobUnits, "U")
                addNumeric("glucose_alert_iob_bolus_units", snapshot.bolusIobUnits, "U")
                addNumeric("glucose_alert_iob_basal_units", snapshot.basalIobUnits, "U")
                addNumeric("glucose_alert_insulin_activity", snapshot.insulinActivity, "U/min")
                addNumeric("glucose_alert_iob_runtime_timestamp_ms", snapshot.timestamp.toDouble(), "epoch_ms")
                addNumeric("glucose_alert_iob_runtime_confidence", snapshot.confidence)
                addText("glucose_alert_iob_runtime_source", snapshot.source.name)
                addText("glucose_alert_iob_fallback_reason", snapshot.fallbackReason)
            }
            sensitivitySnapshot?.let { snapshot ->
                addText("glucose_alert_sensitivity_cycle_id", snapshot.forecastCycleId)
                addNumeric("glucose_alert_sensitivity_settings_revision", snapshot.settingsRevision.toDouble())
                addNumeric("glucose_alert_isf_effective", snapshot.isf.effective, "mmol/L/U")
                addText("glucose_alert_isf_resolved_source", snapshot.isf.resolved.name)
                addNumeric("glucose_alert_cr_effective", snapshot.cr.effective, "g/U")
                addText("glucose_alert_cr_resolved_source", snapshot.cr.resolved.name)
            }
        }

        internal fun shouldKeepInferredUamRuntimeActiveStatic(
            nowTs: Long,
            activeFlag: Double,
            inferredCarbsGrams: Double?,
            ingestionTs: Long?,
            manualCobGrams: Double,
            gAbsRecent: List<Double>,
            forecastRocPer5Used: Double?,
            forecastUci0: Double?,
            forecastVirtualMealConfidence: Double?,
            forecastUam60Mmol: Double?
        ): Boolean {
            if (activeFlag < 0.5 || inferredCarbsGrams == null || ingestionTs == null) return false

            val ageMinutes = ((nowTs - ingestionTs).coerceAtLeast(0L)) / 60_000.0
            if (ageMinutes < 45.0) return true

            val tail = gAbsRecent.takeLast(4).filter { it.isFinite() }
            val weakTail = tail.isNotEmpty() &&
                (tail.maxOrNull() ?: 0.0) < 0.40 &&
                tail.average() < 0.20
            val moderateWeakTail = tail.isNotEmpty() &&
                (tail.maxOrNull() ?: 0.0) < 0.52 &&
                tail.average() < 0.24
            val veryWeakTail = tail.isEmpty() || (
                (tail.maxOrNull() ?: 0.0) < 0.28 &&
                    tail.average() < 0.14
                )
            val lowManualCob = manualCobGrams < 3.0
            val veryLowManualCob = manualCobGrams < 2.0
            val calmRoc = abs(forecastRocPer5Used ?: 0.0) < 0.20
            val lowForecastUam =
                (forecastUci0 ?: 0.0) < 0.10 &&
                    (forecastVirtualMealConfidence ?: 0.0) < 0.35 &&
                    (forecastUam60Mmol ?: 0.0) < 0.75
            val moderateForecastUam =
                (forecastUci0 ?: 0.0) < 0.12 &&
                    (forecastVirtualMealConfidence ?: 0.0) < 0.42 &&
                    (forecastUam60Mmol ?: 0.0) < 1.10 &&
                    abs(forecastRocPer5Used ?: 0.0) < 0.18
            val veryLowForecastUam =
                (forecastUci0 ?: 0.0) < 0.08 &&
                    (forecastVirtualMealConfidence ?: 0.0) < 0.28 &&
                    (forecastUam60Mmol ?: 0.0) < 0.50 &&
                    abs(forecastRocPer5Used ?: 0.0) < 0.15

            if (ageMinutes >= 60.0 && lowManualCob && veryWeakTail && veryLowForecastUam) {
                return false
            }
            if (ageMinutes >= 90.0 && veryLowManualCob && moderateWeakTail && moderateForecastUam) {
                return false
            }
            if (ageMinutes >= 75.0 && lowManualCob && weakTail && calmRoc && lowForecastUam) {
                return false
            }
            if (ageMinutes >= 120.0 && lowManualCob && veryWeakTail && (forecastUam60Mmol ?: 0.0) < 1.0) {
                return false
            }
            return true
        }

        internal fun evaluateForecastVirtualMealRuntimeGateStatic(
            usingVirtualMeal: Boolean,
            virtualMealCarbs: Double?,
            virtualMealConfidence: Double?,
            forecastUam60Mmol: Double?,
            forecastUci0: Double?,
            forecastRocPer5Used: Double?,
            inferredStaleDecayApplied: Boolean
        ): ForecastVirtualMealGateDecision {
            if (!usingVirtualMeal) {
                return ForecastVirtualMealGateDecision(
                    active = false,
                    reason = "virtual_meal_disabled"
                )
            }
            val carbs = virtualMealCarbs ?: return ForecastVirtualMealGateDecision(
                active = false,
                reason = "virtual_meal_carbs_missing"
            )
            if (!carbs.isFinite() || carbs <= 0.0) {
                return ForecastVirtualMealGateDecision(
                    active = false,
                    reason = "virtual_meal_carbs_invalid"
                )
            }

            val confidence = (virtualMealConfidence ?: 0.0).coerceIn(0.0, 1.0)
            val forecastUam60 = (forecastUam60Mmol ?: 0.0).coerceAtLeast(0.0)
            val uci0 = (forecastUci0 ?: 0.0).coerceAtLeast(0.0)
            val rocAbs = abs(forecastRocPer5Used ?: 0.0)

            val strongSupport = forecastUam60 >= 2.00 ||
                uci0 >= 0.14 ||
                rocAbs >= 0.22 ||
                (confidence >= 0.60 && (
                    forecastUam60 >= 1.75 ||
                        uci0 >= 0.10 ||
                        rocAbs >= 0.18 ||
                        carbs >= 24.0
                    ))
            if (strongSupport) {
                return ForecastVirtualMealGateDecision(
                    active = true,
                    reason = "forecast_virtual_meal_strong_support"
                )
            }

            val weakSupport = confidence < 0.45 &&
                forecastUam60 < 0.75 &&
                uci0 < 0.10 &&
                rocAbs < 0.18
            if (inferredStaleDecayApplied && weakSupport) {
                return ForecastVirtualMealGateDecision(
                    active = false,
                    reason = "forecast_virtual_meal_weak_support_released_by_inferred_decay"
                )
            }
            val modestSupport = confidence < 0.50 &&
                forecastUam60 < 1.05 &&
                uci0 < 0.12 &&
                rocAbs < 0.20
            if (inferredStaleDecayApplied && modestSupport) {
                return ForecastVirtualMealGateDecision(
                    active = false,
                    reason = "forecast_virtual_meal_modest_support_released_by_inferred_decay"
                )
            }

            val calmTailRelease = confidence < 0.45 &&
                carbs < 22.0 &&
                forecastUam60 < 1.95 &&
                uci0 < 0.06 &&
                rocAbs < 0.14
            if (calmTailRelease) {
                return ForecastVirtualMealGateDecision(
                    active = false,
                    reason = "forecast_virtual_meal_calm_tail_release"
                )
            }

            val lateWeakForecastTailRelease = confidence < 0.72 &&
                carbs < 21.0 &&
                forecastUam60 < 1.85 &&
                uci0 < 0.05 &&
                rocAbs < 0.10
            if (lateWeakForecastTailRelease) {
                return ForecastVirtualMealGateDecision(
                    active = false,
                    reason = "forecast_virtual_meal_late_weak_tail_release"
                )
            }

            val fallbackSupport = confidence >= 0.35 &&
                (forecastUam60 >= 0.50 || uci0 >= 0.08 || rocAbs >= 0.16)
            return ForecastVirtualMealGateDecision(
                active = fallbackSupport,
                reason = if (fallbackSupport) {
                    "forecast_virtual_meal_fallback_support"
                } else {
                    "forecast_virtual_meal_released_no_support"
                }
            )
        }

        internal fun shouldKeepForecastVirtualMealRuntimeActiveStatic(
            usingVirtualMeal: Boolean,
            virtualMealCarbs: Double?,
            virtualMealConfidence: Double?,
            forecastUam60Mmol: Double?,
            forecastUci0: Double?,
            forecastRocPer5Used: Double?,
            inferredStaleDecayApplied: Boolean
        ): Boolean {
            return evaluateForecastVirtualMealRuntimeGateStatic(
                usingVirtualMeal = usingVirtualMeal,
                virtualMealCarbs = virtualMealCarbs,
                virtualMealConfidence = virtualMealConfidence,
                forecastUam60Mmol = forecastUam60Mmol,
                forecastUci0 = forecastUci0,
                forecastRocPer5Used = forecastRocPer5Used,
                inferredStaleDecayApplied = inferredStaleDecayApplied
            ).active
        }

        internal fun resolveUamActiveTelemetryStatic(latestTelemetry: Map<String, Double?>): Boolean {
            val controlFlag = latestTelemetry["uam_runtime_control_flag"]?.takeIf { it.isFinite() }
            if (controlFlag != null) return controlFlag >= 0.5
            val runtimeFlag = latestTelemetry["uam_runtime_flag"]?.takeIf { it.isFinite() }
            return runtimeFlag != null && runtimeFlag >= 0.5
        }

        internal fun resolveLiveDeliveryTrustStatic(
            nowTs: Long,
            canonicalGlucose: List<GlucosePoint>,
            therapyEvents: List<TherapyEvent>,
            sensorTrust: SensorTrustState,
            latestTelemetry: Map<String, Double?>
        ): DeliveryTrustState {
            var announcedCarbsKnown = true
            val announcedCarbTimestamps = buildList {
                therapyEvents.forEach { event ->
                    val rawValues = event.payload.asSequence()
                        .filter { (key, _) -> normalizeDeliveryKey(key) in DELIVERY_CARB_KEYS }
                        .map { (_, value) -> value.trim().replace(',', '.').toDoubleOrNull() }
                        .toList()
                    if (rawValues.isEmpty()) return@forEach
                    if (rawValues.any { value -> value == null || !value.isFinite() || value !in 0.0..400.0 }) {
                        announcedCarbsKnown = false
                        return@forEach
                    }
                    val values = rawValues.filterNotNull()
                    if (values.maxOrNull()!! - values.minOrNull()!! > 1e-9) {
                        announcedCarbsKnown = false
                        return@forEach
                    }
                    if (values.firstOrNull()?.let { it >= 0.5 } == true) add(event.ts)
                }
            }
            val uamActive = resolveNullableRuntimeFlag(
                latestTelemetry = latestTelemetry,
                primaryKey = "uam_runtime_control_flag",
                fallbackKey = "uam_runtime_flag"
            )
            val setAgeHours = when {
                latestTelemetry.containsKey("isf_factor_set_age_hours") ->
                    latestTelemetry["isf_factor_set_age_hours"]?.takeIf { it.isFinite() && it >= 0.0 }
                latestTelemetry.containsKey("cage_days") ->
                    latestTelemetry["cage_days"]?.takeIf { it.isFinite() && it >= 0.0 }?.times(24.0)
                else -> null
            }
            return DeliveryTrustEvaluator().evaluate(
                DeliveryTrustInput(
                    evaluationTimestamp = nowTs,
                    canonicalGlucose = canonicalGlucose.filter { it.ts >= nowTs - 30L * 60_000L },
                    sensorTrust = sensorTrust,
                    iobUnits = latestTelemetry["iob_units"],
                    effectiveCobGrams = latestTelemetry["cob_effective_grams"],
                    uamActive = uamActive,
                    announcedCarbsKnown = announcedCarbsKnown,
                    announcedCarbTimestamps = announcedCarbTimestamps,
                    setAgeHours = setAgeHours
                )
            )
        }

        private fun resolveNullableRuntimeFlag(
            latestTelemetry: Map<String, Double?>,
            primaryKey: String,
            fallbackKey: String
        ): Boolean? {
            val key = when {
                latestTelemetry.containsKey(primaryKey) -> primaryKey
                latestTelemetry.containsKey(fallbackKey) -> fallbackKey
                else -> return null
            }
            val value = latestTelemetry[key] ?: return null
            return value.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { it >= 0.5 }
        }

        private fun normalizeDeliveryKey(value: String): String = value
            .lowercase(Locale.US)
            .filter(Char::isLetterOrDigit)

        internal fun resolveCobIobBiasUamActiveStatic(
            latestTelemetry: Map<String, Double?>,
            currentUnifiedUamFlag: Double?
        ): Boolean {
            val current = currentUnifiedUamFlag?.takeIf { it.isFinite() }
            return if (current != null) {
                current >= 0.5
            } else {
                resolveUamActiveTelemetryStatic(latestTelemetry)
            }
        }

        internal fun buildCircadianPriorTelemetryRowsStatic(
            nowTs: Long,
            prior: CircadianForecastPrior,
            weight30: Double,
            weight60: Double
        ): List<TelemetrySampleEntity> = buildList {
            fun addNumeric(key: String, value: Double?, unit: String? = null) {
                add(
                    TelemetrySampleEntity(
                        id = "tm-circadian-$key-$nowTs",
                        timestamp = nowTs,
                        source = "copilot_circadian",
                        key = key,
                        valueDouble = value,
                        valueText = null,
                        unit = unit,
                        quality = if (value == null) "STALE" else "OK"
                    )
                )
            }

            fun addText(key: String, value: String?) {
                add(
                    TelemetrySampleEntity(
                        id = "tm-circadian-$key-$nowTs",
                        timestamp = nowTs,
                        source = "copilot_circadian",
                        key = key,
                        valueDouble = null,
                        valueText = value?.trim()?.takeIf { it.isNotBlank() },
                        unit = null,
                        quality = if (value.isNullOrBlank()) "STALE" else "OK"
                    )
                )
            }

            addText("pattern_prior_segment_source", prior.segmentSource.name)
            addText("pattern_prior_requested_day_type", prior.requestedDayType.name)
            addNumeric("pattern_prior_confidence", prior.confidence)
            addNumeric("pattern_prior_bg_median_mmol", prior.bgMedian, "mmol/L")
            addNumeric("pattern_prior_30_mmol", prior.delta30, "mmol/L")
            addNumeric("pattern_prior_60_mmol", prior.delta60, "mmol/L")
            addNumeric("pattern_prior_residual_bias_30_mmol", prior.residualBias30, "mmol/L")
            addNumeric("pattern_prior_residual_bias_60_mmol", prior.residualBias60, "mmol/L")
            addNumeric("pattern_prior_replay_bias_30", prior.replayBias30, "mmol/L")
            addNumeric("pattern_prior_replay_bias_60", prior.replayBias60, "mmol/L")
            addNumeric("pattern_prior_median_reversion_30", prior.medianReversion30, "mmol/L")
            addNumeric("pattern_prior_median_reversion_60", prior.medianReversion60, "mmol/L")
            addNumeric("pattern_prior_horizon_quality_30", prior.horizonQuality30)
            addNumeric("pattern_prior_horizon_quality_60", prior.horizonQuality60)
            addNumeric("pattern_prior_stability_score", prior.stabilityScore)
            addNumeric(
                "pattern_prior_replay_weight_30",
                patternPriorWeightForHorizonStatic(
                    horizonMinutes = 30,
                    prior = prior,
                    weight30 = weight30,
                    weight60 = weight60
                )
            )
            addNumeric(
                "pattern_prior_replay_weight_60",
                patternPriorWeightForHorizonStatic(
                    horizonMinutes = 60,
                    prior = prior,
                    weight30 = weight30,
                    weight60 = weight60
                )
            )
            addNumeric("pattern_prior_acute_attenuation", prior.acuteAttenuation)
            addNumeric("pattern_prior_stale_blocked", if (prior.staleBlocked) 1.0 else 0.0)
            addText("pattern_prior_replay_status_30", prior.replayBucketStatus30.name)
            addText("pattern_prior_replay_status_60", prior.replayBucketStatus60.name)
        }

        internal fun buildSensorLagTelemetryMapUpdatesStatic(
            estimate: SensorLagEstimate,
            latestGlucoseInput: GlucoseInputMetadata?
        ): Map<String, Double?> {
            return mapOf(
                "sensor_lag_age_hours" to estimate.ageHours,
                "sensor_lag_minutes" to estimate.lagMinutes,
                "sensor_lag_source_confidence" to estimate.sourceConfidence,
                "sensor_lag_trend_consistency" to estimate.trendConsistency,
                "sensor_lag_replay_multiplier" to estimate.replayMultiplier,
                "sensor_lag_effective_lag_minutes" to estimate.effectiveLagMinutes,
                "sensor_lag_effective_correction_cap" to estimate.effectiveCorrectionCap,
                "sensor_lag_correction_mmol" to estimate.correctionMmol,
                "sensor_lag_corrected_glucose_mmol" to estimate.correctedGlucoseMmol,
                "sensor_lag_confidence" to estimate.confidence,
                "sensor_lag_active" to if (estimate.mode == SensorLagCorrectionMode.ACTIVE) 1.0 else 0.0,
                "sensor_lag_age_source" to when (estimate.ageSource) {
                    SensorLagAgeSource.DEVICESTATUS -> 1.0
                    SensorLagAgeSource.EXPLICIT_EVENT -> 0.8
                    SensorLagAgeSource.INFERRED_BOUNDARY -> 0.5
                    SensorLagAgeSource.MISSING -> 0.0
                },
                "glucose_input_kind" to when (latestGlucoseInput?.kind?.lowercase(Locale.US)) {
                    "raw" -> 1.0
                    "estimate" -> 0.5
                    else -> 0.0
                }
            )
        }

        internal fun resolveLatestGlucoseInputMetadataStatic(
            rows: List<TelemetrySampleEntity>,
            latestGlucoseTs: Long,
            latestGlucoseSource: String
        ): GlucoseInputMetadata? {
            if (rows.isEmpty()) return null
            val scoped = rows
                .filter { row ->
                    row.timestamp == latestGlucoseTs &&
                        row.source.equals(latestGlucoseSource, ignoreCase = true)
                }
                .ifEmpty { rows.filter { row -> row.timestamp == latestGlucoseTs } }
            val key = scoped
                .filter { it.key == "glucose_input_key" }
                .maxByOrNull { it.timestamp }
                ?.valueText
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val kind = scoped
                .filter { it.key == "glucose_input_kind" }
                .maxByOrNull { it.timestamp }
                ?.valueText
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            return if (key == null && kind == null) null else GlucoseInputMetadata(key = key, kind = kind)
        }

        internal data class SensorLagControlPlan(
            val effectiveCurrentGlucoseMmol: Double,
            val controlForecasts: List<Forecast>,
            val shouldEvaluateShadow: Boolean,
            val shadowCurrentGlucoseMmol: Double?,
            val shadowForecasts: List<Forecast>?
        )

        internal data class ActivityForecastPlan(
            val controlForecasts: List<Forecast>,
            val shadowForecasts: List<Forecast>?,
            val suppressionReasons: Set<ActivityShadowSuppressionReason> = emptySet()
        )

        /**
         * Activity is advisory until Task 8 supplies complete target gates. The
         * original list is therefore the only list eligible for control.
         */
        internal fun resolveActivityForecastPlanStatic(
            controlForecasts: List<Forecast>,
            context: ActivityEffectContext,
            existingControlActivityFactor: Double? = null
        ): ActivityForecastPlan {
            if (context == ActivityEffectContext.DISABLED) {
                return ActivityForecastPlan(controlForecasts = controlForecasts, shadowForecasts = null)
            }
            if (existingControlActivityFactor.isNonIdentityActivityFactor()) {
                return ActivityForecastPlan(
                    controlForecasts = controlForecasts,
                    shadowForecasts = null,
                    suppressionReasons = setOf(
                        ActivityShadowSuppressionReason.UNPROVEN_PREEXISTING_CONTROL_ACTIVITY_FACTOR
                    )
                )
            }
            val shadow = ActivityEffectModel().applyForecastContext(controlForecasts, context)
            return ActivityForecastPlan(
                controlForecasts = controlForecasts,
                shadowForecasts = shadow.takeUnless { it == controlForecasts }
            )
        }

        private fun Double?.isNonIdentityActivityFactor(): Boolean = when {
            this == null -> false
            !isFinite() -> true
            else -> kotlin.math.abs(this - 1.0) >= 1e-6
        }

        internal fun resolveContextActivityFactorStatic(telemetry: Map<String, Double?>): Double = (
            telemetry["isf_factor_activity_factor"]
                ?: telemetry["activity_factor"]
                ?: telemetry["activity_ratio"]
                ?: 1.0
            ).coerceIn(0.6, 1.8)

        internal fun resolveSensorLagControlPlanStatic(
            requestedMode: SensorLagCorrectionMode,
            estimate: SensorLagEstimate,
            rawCurrentGlucoseMmol: Double,
            mergedForecasts: List<Forecast>,
            lagCorrectedForecasts: List<Forecast>
        ): SensorLagControlPlan {
            val useLagCorrectedControlPath = estimate.mode == SensorLagCorrectionMode.ACTIVE
            val shouldEvaluateShadow = estimate.mode == SensorLagCorrectionMode.SHADOW &&
                requestedMode != SensorLagCorrectionMode.OFF
            return SensorLagControlPlan(
                effectiveCurrentGlucoseMmol = if (useLagCorrectedControlPath) {
                    estimate.correctedGlucoseMmol
                } else {
                    rawCurrentGlucoseMmol
                },
                controlForecasts = if (useLagCorrectedControlPath) {
                    lagCorrectedForecasts
                } else {
                    mergedForecasts
                },
                shouldEvaluateShadow = shouldEvaluateShadow,
                shadowCurrentGlucoseMmol = if (shouldEvaluateShadow) {
                    estimate.correctedGlucoseMmol
                } else {
                    null
                },
                shadowForecasts = if (shouldEvaluateShadow) {
                    lagCorrectedForecasts
                } else {
                    null
                }
            )
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
        private const val CIRCADIAN_FORECAST_WEIGHT_5 = 0.10
        private const val ISFCR_AUTO_ACTIVATION_EVAL_INTERVAL_MINUTES = 30
        private const val ISFCR_AUTO_ACTIVATION_HYPO_THRESHOLD_MMOL = 3.9
        private val ISFCR_ROLLING_WINDOWS_DAYS = listOf(14, 30, 90)
        private const val SOURCE_DB_HOUSEKEEPING_CURSOR = "db_housekeeping_cursor"
        private const val SOURCE_DB_COMPACTION_CURSOR = "db_compaction_cursor"
        private const val DB_HOUSEKEEPING_INTERVAL_MS = 2L * 60 * 60 * 1000
        private const val DB_COMPACTION_INTERVAL_MS = 7L * DAY_MS
        private const val DB_COMPACTION_MIN_TRIMMED_ROWS = 5_000
        private const val MIN_STORAGE_RETENTION_DAYS = 30
        private const val MAX_STORAGE_RETENTION_DAYS = 730
        private const val REPORT_AND_PROFILE_MIN_RETENTION_DAYS = 45
        private const val AUDIT_LOG_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        private const val AUDIT_LOG_NOISY_RETENTION_MS = 3L * DAY_MS
        private const val TELEMETRY_DEDUP_LOOKBACK_MS = 6L * 60 * 60 * 1000
        private const val TELEMETRY_GENERAL_RETENTION_MS = 7L * DAY_MS
        private const val TELEMETRY_PHYSICAL_ACTIVITY_RETENTION_MS = 45L * DAY_MS
        private const val TELEMETRY_TRIM_BATCH_LIMIT = 20_000
        private const val TELEMETRY_TRIM_MAX_BATCHES_PER_RUN = 25
        private const val TELEMETRY_TRIM_DEBUG_MAX_BATCHES_PER_RUN = 100
        private const val TELEMETRY_TRIM_CHECKPOINT_EVERY_BATCHES = 5
        private const val TELEMETRY_TRIM_BATCH_PAUSE_MS = 50L
        private const val TELEMETRY_RAW_RETENTION_MS = DAY_MS
        private const val TELEMETRY_NOISY_RETENTION_7D_MS = 7L * DAY_MS
        private const val TELEMETRY_NOISY_RETENTION_14D_MS = 14L * DAY_MS
        private val TELEMETRY_NOISY_7D_PATTERNS = listOf(
            "glucose_input_%",
            "temp_target_%"
        )
        private val TELEMETRY_NOISY_7D_KEYS = listOf(
            "activity_label"
        )
        private val TELEMETRY_NOISY_14D_PATTERNS = listOf(
            "isfcr_%",
            "glucose_alert_%",
            "therapy_history_%",
            "sensor_lag_%"
        )
        private val TELEMETRY_NOISY_14D_KEYS = listOf(
            "uam_runtime_gate_reason"
        )
        private val AUDIT_NOISY_INFO_MESSAGES = listOf(
            "broadcast_ingest_completed",
            "automation_cycle_started",
            "automation_cycle_finished",
            "automation_cycle_completed",
            "automation_cycle_checkpoint",
            "automation_cycle_step_started",
            "automation_cycle_step_completed",
            "cloud_push_skipped",
            "broadcast_reactive_automation_skipped",
            "nightscout_sync_started",
            "nightscout_sync_completed",
            "nightscout_sync_skipped",
            "adaptive_controller_evaluated",
            "adaptive_controller_blocked",
            "forecast_storage_normalized",
            "forecast_bias_applied",
            "forecast_calibration_bias_applied",
            "isfcr_shadow_diff_logged"
        )
        private val GLUCOSE_HOUSEKEEPING_SOURCES = listOf(
            "nightscout",
            "aaps_broadcast",
            "xdrip_broadcast",
            "local_broadcast",
            "local_nightscout_entry"
        )
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
                val glucose24h = glucoseCalibrationRepository.resolveGlucoseHistory(
                    rawGlucose = GlucoseSanitizer.filterEntities(
                        db.glucoseDao().since(nowTs - 24L * 60L * 60L * 1000L)
                    ),
                    nowTs = nowTs
                )
                if (glucose24h.isEmpty()) {
                    null
                } else {
                    val hypoCount = glucose24h.count {
                        it.calibratedMmol < ISFCR_AUTO_ACTIVATION_HYPO_THRESHOLD_MMOL
                    }
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

        val mutation = settingsStore.beginSensitivitySettingsMutation { current ->
            if (current.isfCrShadowMode && current.isfCrAutoActivationEnabled) {
                current.copy(isfCrShadowMode = false)
            } else {
                current
            }
        }
        if (!mutation.changed) return

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
        throw SensitivitySettingsRestartRequired(mutation)
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
        val productionThresholds = ProfileEstimatorConfig()
        val useCalculatedIsf = calculatedIsfMmolPerUnit != null &&
            calculatedIsfSampleCount >= productionThresholds.minIsfSamples
        val useCalculatedCr = calculatedCrGramPerUnit != null &&
            calculatedCrSampleCount >= productionThresholds.minCrSamples
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

    private fun Forecast.toForecastEntity() = io.aaps.copilot.data.local.entity.ForecastEntity(
        timestamp = ts,
        horizonMinutes = horizonMinutes,
        valueMmol = valueMmol,
        ciLow = ciLow,
        ciHigh = ciHigh,
        modelVersion = modelVersion
    )
}
