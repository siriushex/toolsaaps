package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.GlucosePoint
import java.time.LocalDate

private const val MILLIS_PER_MINUTE = 60_000L
private const val MAX_SAFE_DURATION_MINUTES = Long.MAX_VALUE / MILLIS_PER_MINUTE

enum class CircadianAutoState {
    OFF,
    WAITING_DATA,
    BLOCKED_SENSOR,
    BLOCKED_LOW_RISK,
    WAITING_WRITER,
    ACTIVE
}

data class CircadianTargetSample(
    val timestamp: Long,
    val localDate: LocalDate,
    val hour: Int,
    val dayType: CircadianDayType,
    val glucoseMmol: Double,
    val sensorTrusted: Boolean,
    val sensorAgeHours: Double?,
    val sensorBlocked: Boolean,
    val suspectFalseLow: Boolean,
    val effectiveCobGrams: Double?,
    val uamActive: Boolean,
    val acuteCarbs: Boolean,
    val postHypo: Boolean,
    val lowRiskLatched: Boolean,
    val deliveryTrust: DeliveryTrustState,
    val cleanWeight: Double
)

data class CircadianTargetCohorts(
    val clean: List<CircadianTargetSample>,
    val allContext: List<CircadianTargetSample>,
    val validDates: Set<LocalDate>,
    val reasonCounts: Map<String, Int>
)

data class CircadianTelemetrySignal(
    val timestamp: Long,
    val key: String,
    val valueDouble: Double? = null,
    val valueText: String? = null
)

data class CircadianTargetCohortConfig(
    val telemetryFreshnessMinutes: Long = 15L,
    val announcedCarbAbsorptionMinutes: Long = 180L,
    val minimumTrustedSensorQuality: Double = 0.65,
    val maximumCleanCobGrams: Double = 5.0,
    val downWeightSensorAgeHours: Double = 12.0 * 24.0,
    val maximumSensorAgeHours: Double = 14.0 * 24.0
) {
    init {
        require(telemetryFreshnessMinutes >= 0L)
        require(telemetryFreshnessMinutes <= MAX_SAFE_DURATION_MINUTES)
        require(announcedCarbAbsorptionMinutes >= 0L)
        require(announcedCarbAbsorptionMinutes <= MAX_SAFE_DURATION_MINUTES)
        require(minimumTrustedSensorQuality.isFinite() && minimumTrustedSensorQuality in 0.0..1.0)
        require(maximumCleanCobGrams.isFinite() && maximumCleanCobGrams >= 0.0)
        require(downWeightSensorAgeHours.isFinite() && downWeightSensorAgeHours >= 0.0)
        require(maximumSensorAgeHours.isFinite() && maximumSensorAgeHours > downWeightSensorAgeHours)
    }
}

data class DeliveryTrustInput(
    val evaluationTimestamp: Long,
    val canonicalGlucose: List<GlucosePoint>,
    val sensorTrust: SensorTrustState,
    val iobUnits: Double?,
    val effectiveCobGrams: Double?,
    val uamActive: Boolean?,
    val announcedCarbsKnown: Boolean,
    val announcedCarbTimestamps: List<Long>,
    val setAgeHours: Double?
)

data class DeliveryTrustConfig(
    val expectedCadenceMinutes: Long = 5L,
    val cadenceToleranceMinutes: Long = 1L,
    val minimumRiseSpanMinutes: Long = 15L,
    val minimumRiseMmol: Double = 1.5,
    val minimumIobUnits: Double = 1.0,
    val maximumUnconfoundedCobGrams: Double = 5.0,
    val announcedCarbLookbackMinutes: Long = 180L,
    val minimumSetAgeHours: Double = 72.0
) {
    init {
        require(expectedCadenceMinutes > 0L)
        require(cadenceToleranceMinutes in 0 until expectedCadenceMinutes)
        require(expectedCadenceMinutes <= MAX_SAFE_DURATION_MINUTES - cadenceToleranceMinutes)
        require(minimumRiseSpanMinutes > 0L)
        require(minimumRiseSpanMinutes <= MAX_SAFE_DURATION_MINUTES)
        require(minimumRiseMmol.isFinite() && minimumRiseMmol > 0.0)
        require(minimumIobUnits.isFinite() && minimumIobUnits >= 0.0)
        require(maximumUnconfoundedCobGrams.isFinite() && maximumUnconfoundedCobGrams >= 0.0)
        require(announcedCarbLookbackMinutes >= 0L)
        require(announcedCarbLookbackMinutes <= MAX_SAFE_DURATION_MINUTES)
        require(minimumSetAgeHours.isFinite() && minimumSetAgeHours >= 0.0)
    }
}
