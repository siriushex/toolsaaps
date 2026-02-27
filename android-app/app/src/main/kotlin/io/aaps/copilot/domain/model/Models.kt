package io.aaps.copilot.domain.model

import kotlinx.serialization.Serializable

enum class DataQuality {
    OK,
    STALE,
    SENSOR_ERROR
}

@Serializable
data class GlucosePoint(
    val ts: Long,
    val valueMmol: Double,
    val source: String,
    val quality: DataQuality = DataQuality.OK
)

@Serializable
data class TherapyEvent(
    val ts: Long,
    val type: String,
    val payload: Map<String, String>
)

@Serializable
data class Forecast(
    val ts: Long,
    val horizonMinutes: Int,
    val valueMmol: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val modelVersion: String
)

@Serializable
data class ActionProposal(
    val type: String,
    val targetMmol: Double,
    val durationMinutes: Int,
    val reason: String
)

@Serializable
data class RuleDecision(
    val ruleId: String,
    val state: RuleState,
    val reasons: List<String>,
    val actionProposal: ActionProposal?
)

enum class RuleState {
    TRIGGERED,
    BLOCKED,
    NO_MATCH
}

@Serializable
data class SafetySnapshot(
    val killSwitch: Boolean,
    val dataFresh: Boolean,
    val activeTempTargetMmol: Double?,
    val actionsLast6h: Int
)

@Serializable
data class ActionCommand(
    val id: String,
    val type: String,
    val params: Map<String, String>,
    val safetySnapshot: SafetySnapshot,
    val idempotencyKey: String
)
