package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.model.CircadianDayType
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

data class EffectiveTargetRuntimeGates(
    val sensorTrust: SensorTrustState,
    val sensorAgeHours: Double?,
    val lowRiskLatched: Boolean,
    val forecast5CiLowMmol: Double?,
    val forecast30CiLowMmol: Double?,
    val safetyIobUnits: Double?,
    val effectiveCobGrams: Double?,
    val uamActive: Boolean
)

data class EffectiveTargetAdjustment(
    val runId: String,
    val scheduleRevision: Long,
    val dayType: CircadianDayType,
    val hour: Int,
    val appliedDeltaMmol: Double,
    val generatedAt: Long,
    val validUntil: Long,
    val state: CircadianAutoState,
    val reasonCodes: List<String> = emptyList()
)

data class EffectiveBaseTarget(
    val manualTargetMmol: Double,
    val autoDeltaMmol: Double,
    val effectiveTargetMmol: Double,
    val state: CircadianAutoState,
    val scheduleRevision: Long,
    val intervalId: String?,
    val adjustmentRunId: String?,
    val reasonCodes: List<String>
)

class EffectiveBaseTargetResolver {

    fun resolve(
        nowTs: Long,
        zoneId: ZoneId,
        schedule: BaseTargetSchedule,
        targetManagerMode: TargetManagerMode,
        hardMinTargetMmol: Double,
        hardMaxTargetMmol: Double,
        gates: EffectiveTargetRuntimeGates,
        adjustments: List<EffectiveTargetAdjustment>
    ): EffectiveBaseTarget {
        val localNow = Instant.ofEpochMilli(nowTs).atZone(zoneId)
        val manual = BaseTargetSchedulePolicy.resolveManual(schedule, localNow.toInstant(), zoneId)
        require(manual.targetMmol.isFinite()) { "Manual target must be finite" }

        fun manualOnly(
            state: CircadianAutoState,
            reasons: Collection<String>,
            adjustmentRunId: String? = null
        ): EffectiveBaseTarget = EffectiveBaseTarget(
            manualTargetMmol = manual.targetMmol,
            autoDeltaMmol = 0.0,
            effectiveTargetMmol = manual.targetMmol,
            state = state,
            scheduleRevision = schedule.revision,
            intervalId = manual.intervalId,
            adjustmentRunId = adjustmentRunId,
            reasonCodes = normalizeReasons(reasons)
        )

        if (!schedule.autoEnabled) {
            return manualOnly(CircadianAutoState.OFF, listOf("auto_disabled"))
        }
        if (!validBounds(hardMinTargetMmol, hardMaxTargetMmol)) {
            return manualOnly(CircadianAutoState.WAITING_DATA, listOf("invalid_hard_bounds"))
        }

        val requestedDayType = localNow.dayOfWeek.toCircadianDayType()
        val selection = selectAdjustment(
            adjustments = adjustments,
            requestedDayType = requestedDayType,
            hour = localNow.hour
        )
        selection.errorReason?.let { reason ->
            return manualOnly(CircadianAutoState.WAITING_DATA, listOf(reason))
        }
        val adjustment = selection.adjustment
            ?: return manualOnly(CircadianAutoState.WAITING_DATA, listOf("adjustment_missing"))
        val storedReasons = buildList {
            addAll(adjustment.reasonCodes)
            if (selection.usedAllFallback) add("day_type_fallback_all")
        }

        if (adjustment.runId.isBlank()) {
            return manualOnly(
                CircadianAutoState.WAITING_DATA,
                storedReasons + "adjustment_run_id_invalid"
            )
        }
        if (adjustment.scheduleRevision != schedule.revision) {
            return manualOnly(
                CircadianAutoState.WAITING_DATA,
                storedReasons + "adjustment_revision_mismatch",
                adjustment.runId
            )
        }
        if (!adjustment.appliedDeltaMmol.isFinite()) {
            return manualOnly(
                CircadianAutoState.WAITING_DATA,
                storedReasons + "adjustment_delta_invalid",
                adjustment.runId
            )
        }
        if (!isFresh(adjustment, nowTs)) {
            return manualOnly(
                CircadianAutoState.WAITING_DATA,
                storedReasons + "adjustment_stale",
                adjustment.runId
            )
        }
        if (adjustment.state != CircadianAutoState.ACTIVE) {
            return manualOnly(
                adjustment.state,
                storedReasons + "adjustment_state_${adjustment.state.name.lowercase()}",
                adjustment.runId
            )
        }

        val boundedStoredDelta = adjustment.appliedDeltaMmol.coerceIn(MIN_DELTA, MAX_DELTA)
        val effectiveTarget = (manual.targetMmol + boundedStoredDelta)
            .coerceIn(hardMinTargetMmol, hardMaxTargetMmol)
        val appliedDelta = normalizeZero(effectiveTarget - manual.targetMmol)

        if (appliedDelta < -EPSILON && gates.sensorTrust != SensorTrustState.TRUSTED) {
            return manualOnly(
                CircadianAutoState.BLOCKED_SENSOR,
                storedReasons + "sensor_not_trusted",
                adjustment.runId
            )
        }

        if (appliedDelta < -EPSILON) {
            val sensorAgeFailure = when {
                gates.sensorAgeHours == null || !gates.sensorAgeHours.isFinite() || gates.sensorAgeHours < 0.0 ->
                    "sensor_age_unknown"
                gates.sensorAgeHours >= MAXIMUM_SENSOR_AGE_HOURS -> "sensor_age_old"
                else -> null
            }
            if (sensorAgeFailure != null) {
                return manualOnly(
                    CircadianAutoState.BLOCKED_SENSOR,
                    storedReasons + sensorAgeFailure,
                    adjustment.runId
                )
            }
            if (gates.lowRiskLatched) {
                return manualOnly(
                    CircadianAutoState.BLOCKED_LOW_RISK,
                    storedReasons + "low_risk_latched",
                    adjustment.runId
                )
            }
            if (
                gates.safetyIobUnits == null ||
                !gates.safetyIobUnits.isFinite() ||
                gates.safetyIobUnits < 0.0
            ) {
                return manualOnly(
                    CircadianAutoState.BLOCKED_LOW_RISK,
                    storedReasons + "iob_missing",
                    adjustment.runId
                )
            }
        }

        if (appliedDelta < -EPSILON && effectiveTarget < AGGRESSIVE_TARGET_THRESHOLD_MMOL) {
            val gateFailures = aggressiveGateFailures(gates)
            if (gateFailures.isNotEmpty()) {
                return manualOnly(
                    CircadianAutoState.BLOCKED_LOW_RISK,
                    storedReasons + gateFailures,
                    adjustment.runId
                )
            }
        }

        if (targetManagerMode != TargetManagerMode.ACTIVE) {
            return manualOnly(
                CircadianAutoState.WAITING_WRITER,
                storedReasons + "target_manager_not_active",
                adjustment.runId
            )
        }

        return EffectiveBaseTarget(
            manualTargetMmol = manual.targetMmol,
            autoDeltaMmol = appliedDelta,
            effectiveTargetMmol = effectiveTarget,
            state = CircadianAutoState.ACTIVE,
            scheduleRevision = schedule.revision,
            intervalId = manual.intervalId,
            adjustmentRunId = adjustment.runId,
            reasonCodes = normalizeReasons(storedReasons + "auto_adjustment_applied")
        )
    }

    private fun selectAdjustment(
        adjustments: List<EffectiveTargetAdjustment>,
        requestedDayType: CircadianDayType,
        hour: Int
    ): AdjustmentSelection {
        val requested = adjustments.filter { it.dayType == requestedDayType && it.hour == hour }
        if (requested.size > 1) {
            return AdjustmentSelection(errorReason = "duplicate_requested_adjustment")
        }
        requested.singleOrNull()?.let { return AdjustmentSelection(adjustment = it) }

        val fallback = adjustments.filter { it.dayType == CircadianDayType.ALL && it.hour == hour }
        if (fallback.size > 1) {
            return AdjustmentSelection(errorReason = "duplicate_all_adjustment")
        }
        return AdjustmentSelection(
            adjustment = fallback.singleOrNull(),
            usedAllFallback = fallback.isNotEmpty()
        )
    }

    private fun isFresh(adjustment: EffectiveTargetAdjustment, nowTs: Long): Boolean {
        if (adjustment.generatedAt > nowTs || adjustment.validUntil < adjustment.generatedAt) return false
        if (adjustment.validUntil < nowTs) return false
        val oldestAllowed = if (nowTs < Long.MIN_VALUE + MAX_ADJUSTMENT_AGE_MS) {
            Long.MIN_VALUE
        } else {
            nowTs - MAX_ADJUSTMENT_AGE_MS
        }
        return adjustment.generatedAt >= oldestAllowed
    }

    private fun aggressiveGateFailures(gates: EffectiveTargetRuntimeGates): List<String> = buildList {
        addForecastFailure(gates.forecast5CiLowMmol, "forecast_5m")
        addForecastFailure(gates.forecast30CiLowMmol, "forecast_30m")

        when {
            gates.safetyIobUnits == null || !gates.safetyIobUnits.isFinite() || gates.safetyIobUnits < 0.0 ->
                add("iob_missing")
            gates.safetyIobUnits >= MAX_AGGRESSIVE_IOB_UNITS -> add("iob_not_below_2_5")
        }
        when {
            gates.effectiveCobGrams == null || !gates.effectiveCobGrams.isFinite() ||
                gates.effectiveCobGrams < 0.0 -> add("effective_cob_missing")
            gates.effectiveCobGrams > MAX_AGGRESSIVE_COB_GRAMS -> add("effective_cob_above_5")
        }
        if (gates.uamActive) add("uam_active")
    }

    private fun MutableList<String>.addForecastFailure(value: Double?, prefix: String) {
        when {
            value == null || !value.isFinite() -> add("${prefix}_missing")
            value < MIN_AGGRESSIVE_FORECAST_LOW_MMOL -> add("${prefix}_low")
        }
    }

    private fun validBounds(minimum: Double, maximum: Double): Boolean =
        minimum.isFinite() && maximum.isFinite() && minimum <= maximum

    private fun normalizeReasons(reasons: Collection<String>): List<String> = reasons.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    private fun normalizeZero(value: Double): Double = if (abs(value) <= EPSILON) 0.0 else value

    private fun DayOfWeek.toCircadianDayType(): CircadianDayType = when (this) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> CircadianDayType.WEEKEND
        else -> CircadianDayType.WEEKDAY
    }

    private data class AdjustmentSelection(
        val adjustment: EffectiveTargetAdjustment? = null,
        val usedAllFallback: Boolean = false,
        val errorReason: String? = null
    )

    private companion object {
        const val MAX_ADJUSTMENT_AGE_MS = 36L * 60L * 60L * 1_000L
        const val MAXIMUM_SENSOR_AGE_HOURS = 14.0 * 24.0
        const val MIN_DELTA = -1.0
        const val MAX_DELTA = 0.6
        const val AGGRESSIVE_TARGET_THRESHOLD_MMOL = 5.5
        const val MIN_AGGRESSIVE_FORECAST_LOW_MMOL = 4.4
        const val MAX_AGGRESSIVE_IOB_UNITS = 2.5
        const val MAX_AGGRESSIVE_COB_GRAMS = 5.0
        const val EPSILON = 1e-9
    }
}
