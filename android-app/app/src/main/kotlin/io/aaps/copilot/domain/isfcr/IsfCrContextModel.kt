package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.TelemetrySignal
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.max

class IsfCrContextModel(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    private data class LatestTelemetrySample(
        val ts: Long,
        val valueDouble: Double?,
        val valueText: String?
    )

    data class Output(
        val isfEff: Double,
        val crEff: Double,
        val factors: Map<String, Double>
    )

    fun apply(
        nowTs: Long,
        isfBase: Double,
        crBase: Double,
        therapy: List<TherapyEvent>,
        telemetry: List<TelemetrySignal>,
        tags: List<PhysioContextTag>,
        previous: IsfCrRealtimeSnapshot?,
        settings: IsfCrSettings
    ): Output {
        val latestTelemetry = telemetry
            .groupBy { normalizeKey(it.key) }
            .mapValues { (_, samples) ->
                samples.maxByOrNull { sample -> sample.ts }?.let { sample ->
                    LatestTelemetrySample(
                        ts = sample.ts,
                        valueDouble = sample.valueDouble,
                        valueText = sample.valueText
                    )
                }
            }

        val setAgeHours = resolveSetAgeHours(nowTs = nowTs, therapy = therapy)
        val setFactor = if (setAgeHours <= 24.0) {
            1.0
        } else {
            (0.82 + 0.18 * exp(-(setAgeHours - 24.0) / 72.0)).coerceIn(0.75, 1.0)
        }

        val sensorQuality = latestTelemetry
            .freshValue(
                nowTs = nowTs,
                key = "sensor_quality_score",
                freshnessMs = SENSOR_QUALITY_FRESHNESS_MS
            )
            ?.coerceIn(0.0, 1.0)
            ?: 1.0
        val sensorAgeHours = resolveSensorAgeHours(nowTs = nowTs, therapy = therapy)
        val sensorAgeFactor = when {
            sensorAgeHours <= 0.0 -> 1.0
            sensorAgeHours <= 120.0 -> 1.0
            else -> (0.94 + 0.06 * exp(-(sensorAgeHours - 120.0) / 72.0)).coerceIn(0.90, 1.0)
        }
        val sensorFactor = ((0.90 + 0.10 * sensorQuality) * sensorAgeFactor).coerceIn(0.88, 1.0)

        val activityRatioAgeMin = latestTelemetry.ageMinutes(nowTs = nowTs, key = "activity_ratio")
        val activityRatio = if (settings.useActivityFactor) {
            latestTelemetry
                .freshValue(
                    nowTs = nowTs,
                    key = "activity_ratio",
                    freshnessMs = ACTIVITY_TELEMETRY_FRESHNESS_MS
                )
                ?.coerceIn(0.6, 1.8)
                ?: 1.0
        } else {
            1.0
        }
        val stepsRate15m = if (settings.useActivityFactor) {
            resolveStepsRateNormalized15m(
                nowTs = nowTs,
                telemetry = telemetry,
                latestTelemetry = latestTelemetry,
                windowMinutes = 15,
                explicitKey = "steps_rate_15m"
            )
        } else {
            0.0
        }
        val activityRatioAvg90m = if (settings.useActivityFactor) {
            averageTelemetryValue(
                nowTs = nowTs,
                telemetry = telemetry,
                normalizedKey = "activity_ratio",
                lookbackMinutes = 90,
                latestSampleFreshnessMs = ACTIVITY_TELEMETRY_FRESHNESS_MS
            )?.coerceIn(0.6, 1.8) ?: 1.0
        } else {
            1.0
        }
        val stepsRate60m = if (settings.useActivityFactor) {
            resolveStepsRateNormalized15m(
                nowTs = nowTs,
                telemetry = telemetry,
                latestTelemetry = latestTelemetry,
                windowMinutes = 60,
                explicitKey = "steps_rate_60m"
            )
        } else {
            0.0
        }
        val activityCurrentBoost = ((activityRatio - 1.0) * 0.28 + (stepsRate15m / 240.0) * 0.12)
            .coerceIn(-0.25, 0.35)
        val activityTailBoost = (
            ((activityRatioAvg90m - 1.0).coerceAtLeast(0.0) * 0.18) +
                ((stepsRate60m / 240.0) * 0.08)
            ).coerceIn(0.0, 0.18)
        val activityBoost = (activityCurrentBoost + activityTailBoost).coerceIn(-0.25, 0.42)
        val activityFactor = (1.0 + activityBoost).coerceIn(0.75, 1.42)

        val localHour = Instant.ofEpochMilli(nowTs).atZone(zoneId).hour
        val dawnBaseFactor = when (localHour) {
            in 4..5 -> 0.92
            in 6..7 -> 0.88
            in 8..9 -> 0.93
            else -> 1.0
        }
        val dawnHintFactor = latestTelemetry
            .freshValue(
                nowTs = nowTs,
                key = "dawn_factor_hint",
                freshnessMs = DAWN_HINT_FRESHNESS_MS
            )
            ?.coerceIn(0.80, 1.05)
            ?: latestTelemetry
                .freshValue(
                    nowTs = nowTs,
                    key = "dawn_resistance_score",
                    freshnessMs = DAWN_HINT_FRESHNESS_MS
                )
                ?.coerceIn(0.0, 1.0)
                ?.let { score -> (1.0 - 0.12 * score).coerceIn(0.85, 1.0) }
            ?: 1.0
        val dawnFactor = (dawnBaseFactor * dawnHintFactor).coerceIn(0.80, 1.05)

        val manualStressTag = if (settings.useManualTags) activeTag(tags, "stress", nowTs) else 0.0
        val manualIllnessTag = if (settings.useManualTags) activeTag(tags, "illness", nowTs) else 0.0
        val manualHormoneTag = if (settings.useManualTags) {
            val hormonal = activeTag(tags, "hormonal", nowTs)
            val hormone = activeTag(tags, "hormone", nowTs)
            max(hormonal, hormone)
        } else 0.0
        val manualSteroidTag = if (settings.useManualTags) {
            max(activeTag(tags, "steroid", nowTs), activeTag(tags, "steroids", nowTs))
        } else 0.0
        val manualDawnTag = if (settings.useManualTags) activeTag(tags, "dawn", nowTs) else 0.0
        val latentStress = max(
            latestTelemetry
                .freshValue(
                    nowTs = nowTs,
                    key = "stress_score",
                    freshnessMs = STRESS_TELEMETRY_FRESHNESS_MS
                )
                ?.coerceIn(0.0, 1.0)
                ?: 0.0,
            latestTelemetry
                .freshValue(
                    nowTs = nowTs,
                    key = "uam_stress_index",
                    freshnessMs = UAM_TELEMETRY_FRESHNESS_MS
                )
                ?.coerceIn(0.0, 1.0)
                ?: 0.0
        )
        val stressSignal = max(latentStress, (manualStressTag + manualIllnessTag * 0.9).coerceIn(0.0, 1.0))
        val stressFactor = (1.0 - stressSignal * 0.12).coerceIn(0.75, 1.0)
        val hormoneSignal = manualHormoneTag.coerceIn(0.0, 1.0)
        val hormoneFactor = (1.0 - hormoneSignal * 0.10).coerceIn(0.80, 1.0)
        val steroidSignal = manualSteroidTag.coerceIn(0.0, 1.0)
        val steroidFactor = (1.0 - steroidSignal * 0.15).coerceIn(0.70, 1.0)
        val dawnTagFactor = (1.0 - manualDawnTag.coerceIn(0.0, 1.0) * 0.08).coerceIn(0.80, 1.0)

        val uamValue = latestTelemetry
            .freshValue(
                nowTs = nowTs,
                key = "uam_value",
                freshnessMs = UAM_TELEMETRY_FRESHNESS_MS
            )
            ?.coerceIn(0.0, 1.0)
            ?: 0.0
        val uamPenaltyFactor = (1.0 - 0.05 * uamValue).coerceIn(0.90, 1.0)

        val combinedDawnFactor = (dawnFactor * dawnTagFactor).coerceIn(0.75, 1.05)
        val isfCandidate = isfBase *
            setFactor *
            sensorFactor *
            activityFactor *
            combinedDawnFactor *
            stressFactor *
            hormoneFactor *
            steroidFactor *
            uamPenaltyFactor
        val crCandidate = crBase *
            (1.0 / setFactor) *
            sensorFactor *
            activityFactor *
            combinedDawnFactor *
            stressFactor *
            hormoneFactor *
            steroidFactor *
            uamPenaltyFactor

        val isfLimited = applyRateLimiter(
            previous = previous?.isfEff,
            candidate = isfCandidate.coerceIn(0.8, 18.0),
            maxCycleChange = 0.05
        ).coerceIn(0.8, 18.0)
        val crLimited = applyRateLimiter(
            previous = previous?.crEff,
            candidate = crCandidate.coerceIn(2.0, 60.0),
            maxCycleChange = 0.05
        ).coerceIn(2.0, 60.0)

        return Output(
            isfEff = isfLimited,
            crEff = crLimited,
            factors = mapOf(
                "set_age_hours" to setAgeHours,
                "set_factor" to setFactor,
                "sensor_quality" to sensorQuality,
                "sensor_age_hours" to sensorAgeHours,
                "sensor_age_factor" to sensorAgeFactor,
                "sensor_factor" to sensorFactor,
                "activity_ratio" to activityRatio,
                "activity_ratio_age_min" to (activityRatioAgeMin ?: -1.0),
                "activity_ratio_avg_90m" to activityRatioAvg90m,
                "steps_rate_15m" to stepsRate15m,
                "steps_rate_60m" to stepsRate60m,
                "activity_current_boost" to activityCurrentBoost,
                "activity_tail_boost" to activityTailBoost,
                "activity_factor" to activityFactor,
                "dawn_base_factor" to dawnBaseFactor,
                "dawn_hint_factor" to dawnHintFactor,
                "dawn_tag_factor" to dawnTagFactor,
                "dawn_factor" to combinedDawnFactor,
                "manual_tags_enabled" to if (settings.useManualTags) 1.0 else 0.0,
                "manual_stress_tag" to manualStressTag,
                "manual_illness_tag" to manualIllnessTag,
                "manual_hormone_tag" to hormoneSignal,
                "manual_steroid_tag" to steroidSignal,
                "manual_dawn_tag" to manualDawnTag,
                "latent_stress" to latentStress,
                "stress_factor" to stressFactor,
                "hormone_factor" to hormoneFactor,
                "steroid_factor" to steroidFactor,
                "uam_penalty_factor" to uamPenaltyFactor
            )
        )
    }

    private fun applyRateLimiter(previous: Double?, candidate: Double, maxCycleChange: Double): Double {
        previous ?: return candidate
        val low = previous * (1.0 - maxCycleChange)
        val high = previous * (1.0 + maxCycleChange)
        return candidate.coerceIn(low, high)
    }

    private fun resolveSetAgeHours(nowTs: Long, therapy: List<TherapyEvent>): Double {
        val latestSetChange = therapy
            .asSequence()
            .filter { event ->
                val type = event.type.lowercase()
                type.contains("infusion_set_change") ||
                    type.contains("site_change") ||
                    type.contains("cannula_change")
            }
            .maxOfOrNull { it.ts }
            ?: return 0.0
        return ((nowTs - latestSetChange).coerceAtLeast(0L)) / 3_600_000.0
    }

    private fun resolveSensorAgeHours(nowTs: Long, therapy: List<TherapyEvent>): Double {
        val latestSensorChange = therapy
            .asSequence()
            .filter { event ->
                val type = event.type.lowercase()
                type.contains("sensor_change") ||
                    type.contains("cgm_sensor_change") ||
                    type.contains("sensor_start")
            }
            .maxOfOrNull { it.ts }
            ?: return 0.0
        return ((nowTs - latestSensorChange).coerceAtLeast(0L)) / 3_600_000.0
    }

    private fun resolveStepsRateNormalized15m(
        nowTs: Long,
        telemetry: List<TelemetrySignal>,
        latestTelemetry: Map<String, LatestTelemetrySample?>,
        windowMinutes: Int,
        explicitKey: String
    ): Double {
        val safeWindowMinutes = windowMinutes.coerceIn(10, 90)
        val explicit = latestTelemetry
            .freshValue(
                nowTs = nowTs,
                key = explicitKey,
                freshnessMs = ACTIVITY_TELEMETRY_FRESHNESS_MS
            )
            ?.coerceIn(0.0, 240.0)
        if (explicit != null) return explicit

        val stepSamples = telemetry
            .asSequence()
            .filter { normalizeKey(it.key) == "steps_count" }
            .filter { sample -> sample.ts in (nowTs - (safeWindowMinutes + 5) * 60_000L)..nowTs }
            .sortedBy { it.ts }
            .toList()
        if (stepSamples.size < 2) return 0.0

        val first = stepSamples.first()
        val last = stepSamples.last()
        val firstSteps = first.valueDouble ?: return 0.0
        val lastSteps = last.valueDouble ?: return 0.0
        val dtMinutes = ((last.ts - first.ts).coerceAtLeast(1L)) / 60_000.0
        if (dtMinutes <= 0.0) return 0.0
        val deltaSteps = (lastSteps - firstSteps).coerceAtLeast(0.0)
        val normalized15m = deltaSteps * (15.0 / dtMinutes)
        return normalized15m.coerceIn(0.0, 240.0)
    }

    private fun averageTelemetryValue(
        nowTs: Long,
        telemetry: List<TelemetrySignal>,
        normalizedKey: String,
        lookbackMinutes: Int,
        latestSampleFreshnessMs: Long
    ): Double? {
        val safeLookbackMinutes = lookbackMinutes.coerceIn(15, 180)
        val rows = telemetry
            .asSequence()
            .filter { normalizeKey(it.key) == normalizedKey }
            .filter { sample -> sample.ts in (nowTs - safeLookbackMinutes * 60_000L)..nowTs }
            .toList()
        if (rows.isEmpty()) return null
        val latestTs = rows.maxOfOrNull { it.ts } ?: return null
        if ((nowTs - latestTs) !in 0..latestSampleFreshnessMs) return null
        val numericRows = rows.mapNotNull { it.valueDouble?.takeIf { value -> value.isFinite() } }
        if (numericRows.isEmpty()) return null
        return numericRows.average()
    }

    private fun activeTag(tags: List<PhysioContextTag>, token: String, nowTs: Long): Double {
        return tags
            .filter { it.tsStart <= nowTs && it.tsEnd >= nowTs }
            .filter { normalizeKey(it.tagType).contains(token) }
            .maxOfOrNull { it.severity }
            ?.coerceIn(0.0, 1.0)
            ?: 0.0
    }

    private fun normalizeKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun Map<String, LatestTelemetrySample?>.freshValue(
        nowTs: Long,
        key: String,
        freshnessMs: Long
    ): Double? {
        val sample = this[key] ?: return null
        val ageMs = nowTs - sample.ts
        if (ageMs !in 0..freshnessMs) return null
        return sample.valueDouble
    }

    private fun Map<String, LatestTelemetrySample?>.ageMinutes(
        nowTs: Long,
        key: String
    ): Double? {
        val sample = this[key] ?: return null
        return ((nowTs - sample.ts).coerceAtLeast(0L)) / 60_000.0
    }

    private companion object {
        private const val ACTIVITY_TELEMETRY_FRESHNESS_MS = 20L * 60_000L
        private const val SENSOR_QUALITY_FRESHNESS_MS = 30L * 60_000L
        private const val STRESS_TELEMETRY_FRESHNESS_MS = 60L * 60_000L
        private const val UAM_TELEMETRY_FRESHNESS_MS = 30L * 60_000L
        private const val DAWN_HINT_FRESHNESS_MS = 6L * 60L * 60L * 1000L
    }
}
