package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import java.util.Locale

/** Observed configuration, never a retrospective assertion about medication delivered. */
internal data class AiInsulinContext(
    val configuredProfileId: String,
    val onsetMinutes: Double? = null,
    val onsetEvidenceTs: Long? = null,
    val fittedOnsetMinutes: Double? = null,
    val fittedPeakMinutes: Double? = null,
    val fittedScale: Double? = null,
    val updatedAtMs: Long? = null,
    val confidence: Double? = null,
    val sampleCount: Int? = null,
    val status: String? = null,
    val fittedProfileId: String? = null,
    val algorithmVersion: String? = null,
    val diaHours: Double? = null,
    val diaEvidenceTs: Long? = null
) {
    val knownProfile get() = InsulinActionProfileId.entries.singleOrNull { it.name == configuredProfileId }
    val profileMatches get() = fittedProfileId?.let { it == configuredProfileId }

    fun summary(): String {
        fun number(value: Double?) = value?.takeIf { it.isFinite() && it >= 0 }
            ?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
        fun token(value: String?) = value?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) } ?: "unknown"
        return buildString {
            appendLine("configured_insulin_profile_id=${knownProfile?.name ?: "unknown"}")
            appendLine("configured_insulin_profile_label=${knownProfile?.label ?: "unknown"}")
            appendLine("insulin_onset_minutes=${number(onsetMinutes)} insulin_onset_evidence_ms=${onsetEvidenceTs ?: "unknown"}")
            appendLine("insulin_onset_role=current_runtime_estimate fitted_profile_is_a_separate_estimate=true")
            appendLine("insulin_profile_estimated_onset_minutes=${number(fittedOnsetMinutes)}")
            appendLine("insulin_profile_estimated_peak_minutes=${number(fittedPeakMinutes)} scale=${number(fittedScale)}")
            appendLine("insulin_profile_updated_at_ms=${updatedAtMs ?: "unknown"}")
            appendLine("insulin_profile_confidence=${number(confidence)} insulin_profile_sample_count=${sampleCount ?: "unknown"}")
            appendLine("insulin_profile_status=${token(status)} source_profile=${token(fittedProfileId)} algorithm=${token(algorithmVersion)}")
            appendLine("insulin_profile_matches_current_settings=${profileMatches ?: "unknown"}")
            appendLine("insulin_action_duration_hours=${diaHours?.takeIf { it.isFinite() && it > 0 }?.let(::number) ?: "not_provided"}")
            appendLine("insulin_duration_evidence_ms=${diaEvidenceTs ?: "unknown"}")
            appendLine("insulin_duration_kind=effective_runtime_model_not_prescribed_DIA")
            appendLine("insulin_onset_is_not_meal_waiting_interval=true")
            appendLine("current_settings_do_not_identify_historical_bolus_insulin=true")
            appendLine("insulin_profile_selection_is_not_delivery_confirmation=true")
        }
    }

    companion object {
        val KEYS = listOf("insulin_real_onset_min", "insulin_profile_real_onset_min",
            "insulin_profile_real_peak_min", "insulin_profile_real_scale", "insulin_profile_real_updated_ts",
            "insulin_profile_real_confidence", "insulin_profile_real_samples", "insulin_profile_real_status",
            "insulin_profile_real_source_profile", "insulin_profile_real_algo_version", "dia_effective_hours")

        fun fromTelemetry(profileId: String, rows: List<TelemetrySampleEntity>, nowMs: Long): AiInsulinContext {
            val latest = rows.filter { it.key in KEYS && it.timestamp in 1..nowMs &&
                it.source == when {
                    it.key.startsWith("insulin_profile_real_") -> "copilot_real_insulin_profile"
                    it.key == "dia_effective_hours" -> "copilot_runtime_dia"
                    else -> "copilot_runtime_cob_iob"
                } }
                .groupBy { it.key }.mapValues { (_, candidates) ->
                    val timestamp = candidates.maxOf { it.timestamp }
                    candidates.filter { it.timestamp == timestamp }
                        .distinctBy { Triple(it.valueDouble, it.valueText, it.unit) }.singleOrNull()
                }
            fun valid(key: String): TelemetrySampleEntity? = latest[key]?.takeIf {
                val unit = when {
                    key.endsWith("_min") -> "min"
                    key == "dia_effective_hours" -> "h"
                    else -> null
                }
                it.quality == "OK" && it.unit == unit
            }
            fun number(key: String) = valid(key)?.valueDouble?.takeIf { it.isFinite() && it >= 0 }
            fun text(key: String) = valid(key)?.valueText?.trim()
            val updated = number("insulin_profile_real_updated_ts")?.takeIf { it <= nowMs && it >= 1 }?.toLong()
            return AiInsulinContext(profileId,
                number("insulin_real_onset_min"), latest["insulin_real_onset_min"]?.timestamp,
                number("insulin_profile_real_onset_min"), number("insulin_profile_real_peak_min"),
                number("insulin_profile_real_scale"), updated,
                number("insulin_profile_real_confidence")?.takeIf { it <= 1 },
                number("insulin_profile_real_samples")?.takeIf { it % 1 == 0.0 && it <= Int.MAX_VALUE }?.toInt(),
                text("insulin_profile_real_status"), text("insulin_profile_real_source_profile"),
                text("insulin_profile_real_algo_version"), number("dia_effective_hours"), latest["dia_effective_hours"]?.timestamp)
        }
    }
}
