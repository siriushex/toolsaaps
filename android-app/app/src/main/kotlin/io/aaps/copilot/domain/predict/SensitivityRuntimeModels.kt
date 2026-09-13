package io.aaps.copilot.domain.predict

import java.util.Locale

enum class SensitivitySourcePreference {
    AAPS,
    EVIDENCE,
    COPILOT;

    companion object {
        fun fromPersisted(raw: String?): SensitivitySourcePreference = when (
            raw?.trim()?.uppercase(Locale.US)
        ) {
            AAPS.name -> AAPS
            COPILOT.name -> COPILOT
            EVIDENCE.name, "AUTO" -> EVIDENCE
            else -> EVIDENCE
        }

        fun fromUserInput(raw: String?): SensitivitySourcePreference? {
            val normalized = raw?.trim()?.uppercase(Locale.US) ?: return null
            return entries.firstOrNull { it.name == normalized }
        }

        fun fromRaw(raw: String?): SensitivitySourcePreference = fromPersisted(raw)
    }
}

enum class SensitivityResolvedSource {
    AAPS,
    EVIDENCE_BLEND,
    COPILOT_NATIVE
}

data class SensitivityCandidate(
    val value: Double?,
    val timestamp: Long?,
    val confidence: Double,
    val qualityPassed: Boolean,
    val sampleCount: Int,
    val coverage: Double,
    val unavailableReason: String?,
    val modelRevision: Long? = null,
    val profileRevision: Long? = null
)

data class SensitivityCandidates(
    val aaps: SensitivityCandidate,
    val evidence: SensitivityCandidate,
    val copilot: SensitivityCandidate
)

data class SensitivityMetricDecision(
    val requested: SensitivitySourcePreference,
    val resolved: SensitivityResolvedSource,
    val rawAaps: Double?,
    val rawEvidence: Double?,
    val rawCopilot: Double,
    val blended: Double?,
    val effective: Double,
    val confidence: Double,
    val fallbackReason: String?
)

data class SensitivityRuntimeSnapshot(
    val settingsRevision: Long,
    val forecastCycleId: String,
    val timestamp: Long,
    val isf: SensitivityMetricDecision,
    val cr: SensitivityMetricDecision
)

data class SensitivityRuntimeSettingsIdentity(
    val revision: Long,
    val isfSource: SensitivitySourcePreference,
    val crSource: SensitivitySourcePreference
)

const val SENSITIVITY_ACCEPTED_SOURCE = "copilot_sensitivity_cycle"
const val SENSITIVITY_ACCEPTED_CYCLE_ID_KEY = "sensitivity_accepted_cycle_id"
const val SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY = "sensitivity_accepted_settings_revision"
const val SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY = "sensitivity_accepted_forecast_timestamp_ms"
const val SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY = "sensitivity_accepted_forecast_decomposition_v1"
const val SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY = "sensitivity_accepted_forecast_digest_sha256"
const val SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY = "sensitivity_accepted_publication_state"
const val SENSITIVITY_ACCEPTED_PUBLICATION_PENDING = "PENDING"
const val SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED = "COMMITTED"

enum class SensitivityRuntimeConsumer {
    OVERVIEW,
    FORECAST_5_30_60,
    UAM,
    TARGET_MANAGER,
    ANALYTICS_REPORT,
    ALERT_CAUSE
}

data class SensitivityRuntimeConsumerContext(
    val consumer: SensitivityRuntimeConsumer,
    val snapshot: SensitivityRuntimeSnapshot
)

/** One cycle owns one immutable snapshot object; every production consumer receives it by reference. */
class SensitivityRuntimeFanOut(
    val snapshot: SensitivityRuntimeSnapshot
) {
    fun contextFor(consumer: SensitivityRuntimeConsumer) = SensitivityRuntimeConsumerContext(
        consumer = consumer,
        snapshot = snapshot
    )
}

typealias IsfRuntimeSourcePreference = SensitivitySourcePreference
