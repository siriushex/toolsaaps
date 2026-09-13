package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.CircadianDayType
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class EffectiveBaseTargetResolverTest {

    private val resolver = EffectiveBaseTargetResolver()

    @Test
    fun autoOffReturnsExactCurrentManualTarget() {
        val result = resolve(schedule = schedule(autoEnabled = false))

        assertThat(result.manualTargetMmol).isWithin(EPSILON).of(6.2)
        assertThat(result.autoDeltaMmol).isWithin(EPSILON).of(0.0)
        assertThat(result.effectiveTargetMmol).isWithin(EPSILON).of(6.2)
        assertThat(result.state).isEqualTo(CircadianAutoState.OFF)
        assertThat(result.adjustmentRunId).isNull()
    }

    @Test
    fun missingAdjustmentReturnsManualAndWaitsForData() {
        val result = resolve(adjustments = emptyList())

        assertManualOnly(result)
        assertThat(result.state).isEqualTo(CircadianAutoState.WAITING_DATA)
        assertThat(result.reasonCodes).contains("adjustment_missing")
    }

    @Test
    fun revisionMismatchFailsClosed() {
        val result = resolve(adjustments = listOf(adjustment(scheduleRevision = 8L)))

        assertManualOnly(result)
        assertThat(result.state).isEqualTo(CircadianAutoState.WAITING_DATA)
        assertThat(result.reasonCodes).contains("adjustment_revision_mismatch")
    }

    @Test
    fun adjustmentOlderThanThirtySixHoursIsStaleEvenWithFutureValidUntil() {
        val result = resolve(
            adjustments = listOf(
                adjustment(
                    generatedAt = NOW - THIRTY_SIX_HOURS_MS - 1L,
                    validUntil = Long.MAX_VALUE
                )
            )
        )

        assertManualOnly(result)
        assertThat(result.state).isEqualTo(CircadianAutoState.WAITING_DATA)
        assertThat(result.reasonCodes).contains("adjustment_stale")
    }

    @Test
    fun weekdayFallsBackToAllAndPrefersSpecificRowWhenPresent() {
        val allOnly = resolve(
            adjustments = listOf(
                adjustment(runId = "all", dayType = CircadianDayType.ALL, delta = -0.1)
            )
        )
        assertThat(allOnly.effectiveTargetMmol).isWithin(EPSILON).of(6.1)
        assertThat(allOnly.adjustmentRunId).isEqualTo("all")
        assertThat(allOnly.reasonCodes).contains("day_type_fallback_all")

        val specific = resolve(
            adjustments = listOf(
                adjustment(runId = "all", dayType = CircadianDayType.ALL, delta = -0.1),
                adjustment(runId = "weekday", dayType = CircadianDayType.WEEKDAY, delta = -0.3)
            )
        )
        assertThat(specific.effectiveTargetMmol).isWithin(EPSILON).of(5.9)
        assertThat(specific.adjustmentRunId).isEqualTo("weekday")
        assertThat(specific.reasonCodes).doesNotContain("day_type_fallback_all")
    }

    @Test
    fun storedDeltaAndEffectiveTargetAreClamped() {
        val relative = resolve(adjustments = listOf(adjustment(delta = -5.0)))
        assertThat(relative.autoDeltaMmol).isWithin(EPSILON).of(-1.0)
        assertThat(relative.effectiveTargetMmol).isWithin(EPSILON).of(5.2)

        val hardBoundSchedule = BaseTargetSchedule(
            revision = REVISION,
            defaultTargetMmol = 4.2,
            autoEnabled = true,
            intervals = listOf(BaseTargetInterval("low", 600, 720, 4.2))
        )
        val hardBound = resolve(
            schedule = hardBoundSchedule,
            adjustments = listOf(adjustment(delta = -1.0)),
            gates = trustedGates(
                forecast5CiLowMmol = 4.4,
                forecast30CiLowMmol = 4.4,
                safetyIobUnits = 0.0,
                effectiveCobGrams = 0.0
            )
        )
        assertThat(hardBound.effectiveTargetMmol).isWithin(EPSILON).of(4.0)
        assertThat(hardBound.autoDeltaMmol).isWithin(EPSILON).of(-0.2)

        val positive = resolve(adjustments = listOf(adjustment(delta = 8.0)))
        assertThat(positive.autoDeltaMmol).isWithin(EPSILON).of(0.6)
        assertThat(positive.effectiveTargetMmol).isWithin(EPSILON).of(6.8)
    }

    @Test
    fun negativeDeltaIsRemovedImmediatelyWhenSensorTrustDegrades() {
        SensorTrustState.entries.filter { it != SensorTrustState.TRUSTED }.forEach { trust ->
            val result = resolve(gates = trustedGates().copy(sensorTrust = trust))

            assertManualOnly(result)
            assertThat(result.state).isEqualTo(CircadianAutoState.BLOCKED_SENSOR)
            assertThat(result.reasonCodes).contains("sensor_not_trusted")
        }
    }

    @Test
    fun everyNegativeDeltaIsBlockedWhenCurrentSensorAgeIsUnknownOrOld() {
        listOf(null, 14.0 * 24.0, Double.NaN).forEach { sensorAgeHours ->
            val result = resolve(gates = trustedGates(sensorAgeHours = sensorAgeHours))

            assertManualOnly(result)
            assertThat(result.state).isEqualTo(CircadianAutoState.BLOCKED_SENSOR)
            assertThat(result.reasonCodes).contains(
                if (sensorAgeHours == null || sensorAgeHours.isNaN()) "sensor_age_unknown" else "sensor_age_old"
            )
        }
    }

    @Test
    fun everyNegativeDeltaIsBlockedWhileLowRiskLatchIsActive() {
        val result = resolve(
            schedule = schedule(targetMmol = 6.2),
            adjustments = listOf(adjustment(delta = -0.2)),
            gates = trustedGates().copy(lowRiskLatched = true)
        )

        assertManualOnly(result)
        assertThat(result.state).isEqualTo(CircadianAutoState.BLOCKED_LOW_RISK)
        assertThat(result.reasonCodes).contains("low_risk_latched")
    }

    @Test
    fun everyAutomaticBaseLoweringRequiresSafetyQualifiedIob() {
        val blocked = resolve(
            schedule = schedule(targetMmol = 6.2),
            adjustments = listOf(adjustment(delta = -0.2)),
            gates = trustedGates().copy(safetyIobUnits = null)
        )
        val allowed = resolve(
            schedule = schedule(targetMmol = 6.2),
            adjustments = listOf(adjustment(delta = -0.2)),
            gates = trustedGates().copy(safetyIobUnits = 0.0)
        )

        assertManualOnly(blocked)
        assertThat(blocked.state).isEqualTo(CircadianAutoState.BLOCKED_LOW_RISK)
        assertThat(blocked.reasonCodes).contains("iob_missing")
        assertThat(allowed.effectiveTargetMmol).isWithin(EPSILON).of(6.0)
    }

    @Test
    fun targetBelowFivePointFiveRequiresBothForecastLowerBounds() {
        val lowSchedule = schedule(targetMmol = 5.6)

        val fiveMinuteBlocked = resolve(
            schedule = lowSchedule,
            adjustments = listOf(adjustment(delta = -0.2)),
            gates = trustedGates(forecast5CiLowMmol = 4.39)
        )
        assertManualOnly(fiveMinuteBlocked, expectedManual = 5.6)
        assertThat(fiveMinuteBlocked.reasonCodes).contains("forecast_5m_low")

        val thirtyMinuteBlocked = resolve(
            schedule = lowSchedule,
            adjustments = listOf(adjustment(delta = -0.2)),
            gates = trustedGates(forecast30CiLowMmol = null)
        )
        assertManualOnly(thirtyMinuteBlocked, expectedManual = 5.6)
        assertThat(thirtyMinuteBlocked.reasonCodes).contains("forecast_30m_missing")

        val boundaryAllowed = resolve(
            schedule = lowSchedule,
            adjustments = listOf(adjustment(delta = -0.2)),
            gates = trustedGates(forecast5CiLowMmol = 4.4, forecast30CiLowMmol = 4.4)
        )
        assertThat(boundaryAllowed.effectiveTargetMmol).isWithin(EPSILON).of(5.4)
        assertThat(boundaryAllowed.state).isEqualTo(CircadianAutoState.ACTIVE)
    }

    @Test
    fun aggressiveTargetIsBlockedByLowLatchHighIobCobOrUam() {
        val lowSchedule = schedule(targetMmol = 5.6)
        val cases = listOf(
            trustedGates().copy(lowRiskLatched = true) to "low_risk_latched",
            trustedGates().copy(safetyIobUnits = 2.5) to "iob_not_below_2_5",
            trustedGates().copy(effectiveCobGrams = 5.01) to "effective_cob_above_5",
            trustedGates().copy(uamActive = true) to "uam_active"
        )

        cases.forEach { (gates, reason) ->
            val result = resolve(
                schedule = lowSchedule,
                adjustments = listOf(adjustment(delta = -0.2)),
                gates = gates
            )
            assertManualOnly(result, expectedManual = 5.6)
            assertThat(result.state).isEqualTo(CircadianAutoState.BLOCKED_LOW_RISK)
            assertThat(result.reasonCodes).contains(reason)
        }
    }

    @Test
    fun everyNegativeAutoAdjustmentRequiresSafetyIobEvenAtFivePointFive() {
        val result = resolve(
            schedule = schedule(targetMmol = 5.6),
            adjustments = listOf(adjustment(delta = -0.1)),
            gates = trustedGates(
                forecast5CiLowMmol = null,
                forecast30CiLowMmol = null,
                safetyIobUnits = null,
                effectiveCobGrams = null,
                uamActive = true
            )
        )

        assertManualOnly(result, expectedManual = 5.6)
        assertThat(result.state).isEqualTo(CircadianAutoState.BLOCKED_LOW_RISK)
        assertThat(result.reasonCodes).contains("iob_missing")
    }

    @Test
    fun protectivePositiveDeltaSurvivesDegradedRuntimeGates() {
        val result = resolve(
            adjustments = listOf(adjustment(delta = 0.4)),
            gates = EffectiveTargetRuntimeGates(
                sensorTrust = SensorTrustState.BLOCKED,
                sensorAgeHours = null,
                lowRiskLatched = true,
                forecast5CiLowMmol = 2.0,
                forecast30CiLowMmol = 2.0,
                safetyIobUnits = 12.0,
                effectiveCobGrams = 100.0,
                uamActive = true
            )
        )

        assertThat(result.autoDeltaMmol).isWithin(EPSILON).of(0.4)
        assertThat(result.effectiveTargetMmol).isWithin(EPSILON).of(6.6)
        assertThat(result.state).isEqualTo(CircadianAutoState.ACTIVE)
    }

    @Test
    fun eligibleAdjustmentWaitsForActiveTargetManager() {
        val result = resolve(targetManagerMode = TargetManagerMode.SHADOW)

        assertManualOnly(result)
        assertThat(result.state).isEqualTo(CircadianAutoState.WAITING_WRITER)
        assertThat(result.adjustmentRunId).isEqualTo("run")
        assertThat(result.reasonCodes).contains("target_manager_not_active")
    }

    @Test
    fun blockedStoredStateIsNotApplied() {
        val result = resolve(
            adjustments = listOf(adjustment(state = CircadianAutoState.BLOCKED_LOW_RISK))
        )

        assertManualOnly(result)
        assertThat(result.state).isEqualTo(CircadianAutoState.BLOCKED_LOW_RISK)
        assertThat(result.adjustmentRunId).isEqualTo("run")
    }

    @Test
    fun storedOffStateIsPreservedPrecisely() {
        val result = resolve(
            adjustments = listOf(adjustment(state = CircadianAutoState.OFF))
        )

        assertManualOnly(result)
        assertThat(result.state).isEqualTo(CircadianAutoState.OFF)
        assertThat(result.adjustmentRunId).isEqualTo("run")
    }

    @Test
    fun activeZeroDeltaStillReportsEligibleActiveContour() {
        val result = resolve(adjustments = listOf(adjustment(delta = 0.0)))

        assertManualOnly(result)
        assertThat(result.state).isEqualTo(CircadianAutoState.ACTIVE)
        assertThat(result.adjustmentRunId).isEqualTo("run")
    }

    private fun resolve(
        schedule: BaseTargetSchedule = schedule(),
        targetManagerMode: TargetManagerMode = TargetManagerMode.ACTIVE,
        gates: EffectiveTargetRuntimeGates = trustedGates(),
        adjustments: List<EffectiveTargetAdjustment> = listOf(adjustment()),
        hardMinTargetMmol: Double = 4.0,
        hardMaxTargetMmol: Double = 10.0,
        nowTs: Long = NOW
    ): EffectiveBaseTarget = resolver.resolve(
        nowTs = nowTs,
        zoneId = ZONE,
        schedule = schedule,
        targetManagerMode = targetManagerMode,
        hardMinTargetMmol = hardMinTargetMmol,
        hardMaxTargetMmol = hardMaxTargetMmol,
        gates = gates,
        adjustments = adjustments
    )

    private fun schedule(
        autoEnabled: Boolean = true,
        targetMmol: Double = 6.2
    ): BaseTargetSchedule = BaseTargetSchedule(
        revision = REVISION,
        defaultTargetMmol = 6.0,
        autoEnabled = autoEnabled,
        intervals = listOf(
            BaseTargetInterval(
                id = "morning",
                startMinuteOfDay = 600,
                endMinuteOfDay = 720,
                targetMmol = targetMmol
            )
        )
    )

    private fun adjustment(
        runId: String = "run",
        scheduleRevision: Long = REVISION,
        dayType: CircadianDayType = CircadianDayType.WEEKDAY,
        hour: Int = 10,
        delta: Double = -0.2,
        generatedAt: Long = NOW - 60L * 60L * 1_000L,
        validUntil: Long = NOW + 60L * 60L * 1_000L,
        state: CircadianAutoState = CircadianAutoState.ACTIVE
    ): EffectiveTargetAdjustment = EffectiveTargetAdjustment(
        runId = runId,
        scheduleRevision = scheduleRevision,
        dayType = dayType,
        hour = hour,
        appliedDeltaMmol = delta,
        generatedAt = generatedAt,
        validUntil = validUntil,
        state = state,
        reasonCodes = listOf("stored_reason")
    )

    private fun trustedGates(
        sensorAgeHours: Double? = 120.0,
        forecast5CiLowMmol: Double? = 4.8,
        forecast30CiLowMmol: Double? = 4.7,
        safetyIobUnits: Double? = 1.0,
        effectiveCobGrams: Double? = 0.0,
        uamActive: Boolean = false
    ): EffectiveTargetRuntimeGates = EffectiveTargetRuntimeGates(
        sensorTrust = SensorTrustState.TRUSTED,
        sensorAgeHours = sensorAgeHours,
        lowRiskLatched = false,
        forecast5CiLowMmol = forecast5CiLowMmol,
        forecast30CiLowMmol = forecast30CiLowMmol,
        safetyIobUnits = safetyIobUnits,
        effectiveCobGrams = effectiveCobGrams,
        uamActive = uamActive
    )

    private fun assertManualOnly(result: EffectiveBaseTarget, expectedManual: Double = 6.2) {
        assertThat(result.manualTargetMmol).isWithin(EPSILON).of(expectedManual)
        assertThat(result.autoDeltaMmol).isWithin(EPSILON).of(0.0)
        assertThat(result.effectiveTargetMmol).isWithin(EPSILON).of(expectedManual)
        assertThat(result.scheduleRevision).isEqualTo(REVISION)
        assertThat(result.intervalId).isEqualTo("morning")
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Tbilisi")
        val NOW: Long = LocalDateTime.parse("2026-07-20T10:30:00")
            .atZone(ZONE)
            .toInstant()
            .toEpochMilli()
        const val REVISION = 7L
        const val THIRTY_SIX_HOURS_MS = 36L * 60L * 60L * 1_000L
        const val EPSILON = 1e-9
    }
}
