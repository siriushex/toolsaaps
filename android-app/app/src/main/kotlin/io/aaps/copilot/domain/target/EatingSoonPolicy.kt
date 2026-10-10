package io.aaps.copilot.domain.target

import io.aaps.copilot.util.UnitConverter

data class EatingSoonEvidence(
    val nowTs: Long,
    val killSwitch: Boolean,
    val actionsArmed: Boolean,
    val minTargetMmol: Double,
    val maxTargetMmol: Double,
    val glucoseTs: Long?,
    val glucoseMmol: Double?,
    val forecastGeneratedAt: Long?,
    val forecasts: Map<Int, EatingSoonForecast>,
    val sensorTrusted: Boolean,
    val chronologyResolved: Boolean
)

data class EatingSoonForecast(
    val valueMmol: Double,
    val ciLow: Double,
    val ciHigh: Double
)

object EatingSoonPolicy {
    const val TARGET_MMOL = 4.1
    const val DURATION_MINUTES = 30
    const val MAX_DATA_AGE_MS = 300_000L

    fun isRequestKey(key: String): Boolean = REQUEST_KEY.matches(key)

    fun isCanonicalRequest(key: String, target: Double?, duration: Int?, reason: String?): Boolean =
        isRequestKey(key) && target == TARGET_MMOL && duration == DURATION_MINUTES && reason == "Eating Soon"

    fun isActiveConfirmedTarget(active: ActiveAapsTarget?, nowTs: Long): Boolean {
        if (active == null || !active.eatingSoonConfirmed || !active.evidenceResolved ||
            active.ownership != ActiveTargetOwnership.MANUAL_OR_FOREIGN || active.source.isBlank() ||
            active.idempotencyKey?.let(::isRequestKey) != true ||
            active.startedAt <= 0L || active.startedAt > nowTs || active.expiresAt <= nowTs ||
            !UnitConverter.matchesTempTargetObservation(TARGET_MMOL, active.targetMmol)) return false
        return runCatching { Math.subtractExact(active.expiresAt, active.startedAt) }
            .getOrNull() == DURATION_MINUTES * 60_000L
    }

    fun replacementFailure(active: ActiveAapsTarget?, nowTs: Long, intent: TargetIntent, target: Double): String? {
        if (!isActiveConfirmedTarget(active, nowTs)) return null
        return if (intent.isProtective() && target.isFinite() && target > checkNotNull(active).targetMmol) {
            null
        } else "eating_soon_target_active"
    }

    fun blockReason(evidence: EatingSoonEvidence): String? {
        if (evidence.killSwitch) return "kill_switch_active"
        if (!evidence.actionsArmed) return "actions_disarmed"
        if (
            !evidence.minTargetMmol.isFinite() ||
            !evidence.maxTargetMmol.isFinite() ||
            evidence.minTargetMmol > evidence.maxTargetMmol
        ) {
            return "target_bounds_invalid"
        }
        if (TARGET_MMOL !in evidence.minTargetMmol..evidence.maxTargetMmol) {
            return "target_out_of_bounds"
        }
        if (!evidence.chronologyResolved) return "chronology_unresolved"
        if (!evidence.sensorTrusted) return "sensor_untrusted"

        val glucoseTs = evidence.glucoseTs ?: return "glucose_timestamp_missing"
        when {
            glucoseTs <= 0L -> return "glucose_timestamp_invalid"
            glucoseTs > evidence.nowTs -> return "glucose_timestamp_future"
            evidence.nowTs - glucoseTs > MAX_DATA_AGE_MS -> return "glucose_stale"
        }

        val glucoseMmol = evidence.glucoseMmol ?: return "glucose_missing"
        when {
            !glucoseMmol.isFinite() -> return "glucose_invalid"
            glucoseMmol <= LOW_GLUCOSE_FLOOR_MMOL -> return "glucose_too_low"
        }

        val forecastGeneratedAt =
            evidence.forecastGeneratedAt ?: return "forecast_timestamp_missing"
        when {
            forecastGeneratedAt <= 0L -> return "forecast_timestamp_invalid"
            forecastGeneratedAt > evidence.nowTs -> return "forecast_timestamp_future"
            evidence.nowTs - forecastGeneratedAt > MAX_DATA_AGE_MS -> return "forecast_stale"
        }

        REQUIRED_HORIZONS.forEach { horizon ->
            val forecast = evidence.forecasts[horizon]
                ?: return "forecast_" + horizon + "m_missing"
            if (
                !forecast.valueMmol.isFinite() ||
                !forecast.ciLow.isFinite() ||
                !forecast.ciHigh.isFinite() ||
                forecast.ciLow > forecast.valueMmol ||
                forecast.valueMmol > forecast.ciHigh
            ) {
                return "forecast_" + horizon + "m_invalid"
            }
            if (forecast.valueMmol <= LOW_GLUCOSE_FLOOR_MMOL) {
                return "forecast_" + horizon + "m_too_low"
            }
            if (forecast.ciLow <= LOW_GLUCOSE_FLOOR_MMOL) {
                return "forecast_" + horizon + "m_ci_low"
            }
        }

        return null
    }

    private const val LOW_GLUCOSE_FLOOR_MMOL = 4.0
    private val REQUIRED_HORIZONS = intArrayOf(5, 30, 60)
    private val REQUEST_KEY = Regex("manual:meal:[A-Za-z0-9_-]{1,80}:eating-soon")
}
