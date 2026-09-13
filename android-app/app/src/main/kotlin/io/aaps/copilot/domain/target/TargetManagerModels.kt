package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumerContext
import io.aaps.copilot.domain.profile.ActivitySafePredictedFall
import io.aaps.copilot.domain.profile.ActivityTargetProposal

enum class TargetIntent {
    SENSOR_SAFETY_RELEASE,
    HYPO_PROTECTION,
    ACTIVITY_PROTECTION,
    PLANNED_ACTIVITY_ADAPTATION,
    POST_HYPO_PROTECTION,
    NORMAL_CONTROL,
    RECOVERY_TO_BASE
}

enum class TargetManagerMode { OFF, SHADOW, ACTIVE }

enum class SensorTrustState { TRUSTED, WARN, RESTRICTED, BLOCKED }

enum class DeliveryTrustState { NORMAL, WATCH, SUSPECTED_NONRESPONSE, UNKNOWN }

enum class HorizonReliabilityState { RELIABLE, DEGRADED, BLOCKED, INSUFFICIENT }

data class HorizonReliability(
    val horizonMinutes: Int,
    val state: HorizonReliabilityState,
    val sampleCount: Int,
    val maeMmol: Double?,
    val biasMmol: Double?,
    val ciCoverage: Double?,
    val weightMultiplier: Double,
    val evaluatedAt: Long
)

data class TargetProposal(
    val sourceRuleId: String,
    val intent: TargetIntent,
    val targetMmol: Double,
    val durationMinutes: Int,
    val priority: Int,
    val confidence: Double,
    val reasonCodes: List<String>,
    val generatedAt: Long,
    val inputFingerprint: String,
    val activityProposal: ActivityTargetProposal? = null
)

enum class ActiveTargetOwnership {
    TARGET_MANAGER,
    LEGACY_COPILOT,
    MANUAL_OR_FOREIGN,
    UNKNOWN
}

data class ActiveAapsTarget(
    val targetMmol: Double,
    val startedAt: Long,
    val expiresAt: Long,
    val source: String,
    val ownership: ActiveTargetOwnership,
    val idempotencyKey: String?,
    val evidenceResolved: Boolean = true
)

data class AcceptedTargetState(
    val revision: Long,
    val targetMmol: Double,
    val durationMinutes: Int,
    val ownerRuleId: String,
    val intent: TargetIntent,
    val acceptedAt: Long,
    val expiresAt: Long,
    val lastInputFingerprint: String,
    val lastCommandId: String?,
    val lastCommandStatus: String?,
    val activityProposal: ActivityTargetProposal? = null
)

data class TargetManagerRuntimeState(
    val mode: TargetManagerMode,
    val acceptedTarget: AcceptedTargetState? = null,
    val lastDecisionFingerprint: String? = null,
    val lastSafetyBypassFingerprint: String? = null,
    val reconciliationStatus: String = "not_reconciled"
)

data class TargetManagerSafetyContext(
    val killSwitch: Boolean,
    val dataFresh: Boolean,
    val sensorTrust: SensorTrustState,
    val deliveryTrust: DeliveryTrustState,
    val currentGlucoseMmol: Double?,
    val minimumPredictedOrCiMmol: Double?,
    val lowRiskThresholdMmol: Double,
    val minTargetMmol: Double,
    val maxTargetMmol: Double,
    val minDurationMinutes: Int,
    val maxDurationMinutes: Int,
    val baseTargetMmol: Double,
    val safetyIobUnits: Double? = null,
    val localChronologyResolved: Boolean = true
)

data class ActivityForecastSafety(
    val horizonMinutes: Int,
    val valueMmol: Double,
    val ciLowMmol: Double,
    val ciHighMmol: Double
)

data class ActivityTargetSafetyContext(
    val moduleEnabled: Boolean = false,
    val occurrenceId: String? = null,
    val occurrenceRevision: Long? = null,
    val validFromMs: Long? = null,
    val validUntilMs: Long? = null,
    val evidenceHash: String? = null,
    val sameCycleAdaptiveCandidateMmol: Double? = null,
    val sameCycleCandidateFingerprint: String? = null,
    val personalEvidenceHash: String? = null,
    val replayHash: String? = null,
    val observedDelta5Mmol: Double? = null,
    val diagnosticIobUnits: Double? = null,
    val cobGrams: Double? = null,
    val uamActive: Boolean = false,
    val forecasts: Map<Int, ActivityForecastSafety> = emptyMap(),
    val safePredictedFall: ActivitySafePredictedFall? = null,
    val keepaliveAllowed: Boolean = false,
    val returnToBaseRequested: Boolean = false
)

data class TargetBaseProvenance(
    val scheduleRevision: Long,
    val intervalId: String?,
    val adjustmentRunId: String?
)

data class TargetManagerInput(
    val nowTs: Long,
    val glucoseTimestamp: Long,
    val therapyWatermark: Long,
    val mode: TargetManagerMode,
    val proposals: List<TargetProposal>,
    val runtimeState: TargetManagerRuntimeState,
    val activeAapsTarget: ActiveAapsTarget?,
    val safety: TargetManagerSafetyContext,
    val reliability: Map<Int, HorizonReliability>,
    val lastAutomaticSent: LastSentTempTarget?,
    val baseProvenance: TargetBaseProvenance,
    val activitySafety: ActivityTargetSafetyContext = ActivityTargetSafetyContext(),
    val sensitivityRuntime: SensitivityRuntimeConsumerContext,
    val calibrationIdentity: GlucoseCalibrationCycleIdentity? = null,
    val copilotPriorityEnabled: Boolean = false,
    val priorityRevision: Long = 0L
) {
    init {
        require(sensitivityRuntime.consumer == SensitivityRuntimeConsumer.TARGET_MANAGER) {
            "TargetManagerInput requires TARGET_MANAGER sensitivity context"
        }
        require(sensitivityRuntime.snapshot.forecastCycleId.isNotBlank()) {
            "TargetManagerInput requires a published sensitivity cycle"
        }
    }
}

enum class TargetDecisionOutcome {
    NO_PROPOSAL,
    SHADOW_WOULD_SEND,
    SEND,
    RENEW_SAME_TARGET,
    SUPPRESS_SEMANTIC_DUPLICATE,
    BLOCK_CADENCE,
    BLOCK_KILL_SWITCH,
    BLOCK_STALE_DATA,
    BLOCK_SENSOR_TRUST,
    BLOCK_DELIVERY_TRUST,
    BLOCK_PROTECTIVE_DIRECTION,
    BLOCK_FORECAST_RELIABILITY,
    BLOCK_MANUAL_TARGET,
    BLOCK_LEGACY_TARGET_DRAIN,
    BLOCK_SAFETY_BOUNDS,
    DELIVERY_FAILED
}

data class TargetCommandObservation(
    val activeAapsTarget: ActiveAapsTarget? = null,
    val copilotPriorityEnabled: Boolean = false,
    val priorityRevision: Long = 0L
)

data class TargetCommandCandidate(
    val targetMmol: Double,
    val durationMinutes: Int,
    val ownerRuleId: String,
    val intent: TargetIntent,
    val reason: String,
    val idempotencyKey: String,
    val semanticFingerprint: String,
    val baseProvenance: TargetBaseProvenance? = null,
    val generatedAt: Long = 0L,
    val targetObservation: TargetCommandObservation? = null,
    val sensitivityCycleId: String? = null
)

data class TargetManagerDecision(
    val outcome: TargetDecisionOutcome,
    val winner: TargetProposal?,
    val command: TargetCommandCandidate?,
    val semanticFingerprint: String?,
    val cadenceOutcome: TempTargetCadenceOutcome?,
    val cadenceReason: String?,
    val reasonCodes: List<String>,
    val rejectedProposalReasons: Map<String, String>,
    val nextRuntimeState: TargetManagerRuntimeState
)

data class LastSentTempTarget(
    val timestamp: Long,
    val targetMmol: Double,
    val idempotencyKey: String
)

data class TargetCadenceRequest(
    val nowTs: Long,
    val targetMmol: Double,
    val intent: TargetIntent?,
    val manual: Boolean,
    val lastAutomaticSent: LastSentTempTarget?
)

enum class TempTargetCadenceOutcome {
    ALLOW_NO_PREVIOUS,
    ALLOW_WINDOW_ELAPSED,
    ALLOW_MATERIAL_CHANGE,
    ALLOW_URGENT_HYPO_RAISE,
    ALLOW_MANUAL,
    BLOCK_DUPLICATE_WITHIN_WINDOW
}

data class TargetCadenceDecision(
    val allowed: Boolean,
    val outcome: TempTargetCadenceOutcome,
    val reason: String
)
