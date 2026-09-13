package io.aaps.copilot.domain.activity

import java.time.Instant
import java.time.ZoneId
import io.aaps.copilot.domain.profile.PhysiologicalSex
import kotlin.math.max

data class LocalActivityState(
    val dayStartTs: Long = 0L,
    val baselineCounter: Double = 0.0,
    val lastCounter: Double = 0.0,
    val lastTs: Long = 0L,
    val activeMinutesToday: Double = 0.0
)

data class LocalActivitySnapshot(
    val dayStartTs: Long,
    val stepsToday: Double,
    val distanceKmToday: Double,
    val activeMinutesToday: Double,
    val activeCaloriesKcalToday: Double,
    val activityRatio: Double,
    val distanceSource: LocalActivityMetricSource = LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK,
    val activeCaloriesSource: LocalActivityMetricSource = LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK
)

enum class LocalActivityMetricSource {
    HEALTH_CONNECT,
    PERSONALIZED_LOCAL_FALLBACK,
    DEFAULT_LOCAL_FALLBACK
}

data class HealthConnectActivityMetrics(
    val distanceKmToday: Double? = null,
    val activeCaloriesKcalToday: Double? = null
)

data class LocalActivityPersonalization(
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val ageYears: Int? = null,
    val physiologicalSex: PhysiologicalSex = PhysiologicalSex.UNSPECIFIED
)

class LocalActivityMetricsEstimator(
    private val strideMeters: Double = 0.78,
    private val kcalPerStep: Double = 0.04
) {
    private val safeStrideMeters = strideMeters.takeIf {
        it.isFinite() && it in MIN_STRIDE_METERS..MAX_STRIDE_METERS
    } ?: DEFAULT_STRIDE_METERS
    private val safeKcalPerStep = kcalPerStep.takeIf {
        it.isFinite() && it in 0.0..MAX_KCAL_PER_STEP
    } ?: DEFAULT_KCAL_PER_STEP

    fun update(
        counterTotal: Double,
        timestamp: Long,
        previous: LocalActivityState,
        zoneId: ZoneId = ZoneId.systemDefault(),
        healthConnectMetrics: HealthConnectActivityMetrics = HealthConnectActivityMetrics(),
        personalization: LocalActivityPersonalization? = null
    ): Pair<LocalActivityState, LocalActivitySnapshot> {
        val normalizedPrevious = previous.normalized()
        val safeTimestamp = timestamp.coerceAtLeast(1L)
        val dayStart = Instant.ofEpochMilli(safeTimestamp)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val isNewDay = normalizedPrevious.dayStartTs != dayStart
        val candidateCounter = counterTotal.validStepCounterOrNull()
            ?: normalizedPrevious.lastCounter
        val counterSpike = !isNewDay && normalizedPrevious.baselineCounter > 0.0 && (
            candidateCounter - normalizedPrevious.lastCounter > MAX_STEP_DELTA_PER_SAMPLE ||
                candidateCounter - normalizedPrevious.baselineCounter > MAX_DAILY_STEPS
            )
        val safeCounter = if (counterSpike) normalizedPrevious.lastCounter else candidateCounter
        val baseline = when {
            isNewDay -> safeCounter
            normalizedPrevious.baselineCounter <= 0.0 -> safeCounter
            safeCounter < normalizedPrevious.baselineCounter -> safeCounter
            else -> normalizedPrevious.baselineCounter
        }

        val stepsToday = max(0.0, safeCounter - baseline)
        val (deltaSteps, dtMin) = computeDelta(normalizedPrevious, safeCounter, safeTimestamp)
        val pacePerMinute = if (dtMin != null && dtMin > 0.0) deltaSteps / dtMin else 0.0
        val activeIncrementMinutes = when {
            dtMin == null -> 0.0
            dtMin <= 0.0 || dtMin > 15.0 -> 0.0
            pacePerMinute >= 20.0 -> dtMin.coerceAtMost(10.0)
            else -> 0.0
        }
        val activeMinutes = if (isNewDay) {
            activeIncrementMinutes
        } else {
            (normalizedPrevious.activeMinutesToday + activeIncrementMinutes).coerceIn(0.0, 1_440.0)
        }

        val ratio = ActivityIntensity.ratioFromPacePerMinute(pacePerMinute)
        val reportedDistanceKm = healthConnectMetrics.distanceKmToday.validDistanceKmOrNull()
        val reportedActiveCaloriesKcal = healthConnectMetrics.activeCaloriesKcalToday.validActiveCaloriesKcalOrNull()
        val personalizedStrideMeters = personalization?.heightCm.validHeightOrNull()
            ?.let { (it * STRIDE_PER_HEIGHT_CM).coerceIn(MIN_STRIDE_METERS, MAX_STRIDE_METERS) }
        val fallbackDistanceKm = stepsToday * (personalizedStrideMeters ?: safeStrideMeters) / METERS_PER_KILOMETER
        val distanceKm = reportedDistanceKm ?: fallbackDistanceKm
        val distanceSource = when {
            reportedDistanceKm != null -> LocalActivityMetricSource.HEALTH_CONNECT
            personalizedStrideMeters != null -> LocalActivityMetricSource.PERSONALIZED_LOCAL_FALLBACK
            else -> LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK
        }

        val personalizedCaloriesKcal = personalization?.weightKg.validWeightOrNull()
            ?.let { weightKg -> (distanceKm * weightKg * WALKING_KCAL_PER_KG_KM).coerceIn(0.0, MAX_DAILY_ACTIVE_KCAL) }
        val fallbackCaloriesKcal = personalizedCaloriesKcal ?: (stepsToday * safeKcalPerStep)
            .coerceIn(0.0, MAX_DAILY_ACTIVE_KCAL)
        val caloriesKcal = reportedActiveCaloriesKcal ?: fallbackCaloriesKcal
        val caloriesSource = when {
            reportedActiveCaloriesKcal != null -> LocalActivityMetricSource.HEALTH_CONNECT
            personalizedCaloriesKcal != null -> LocalActivityMetricSource.PERSONALIZED_LOCAL_FALLBACK
            else -> LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK
        }

        val updatedState = LocalActivityState(
            dayStartTs = dayStart,
            baselineCounter = baseline,
            lastCounter = safeCounter,
            lastTs = safeTimestamp,
            activeMinutesToday = activeMinutes
        )
        val snapshot = LocalActivitySnapshot(
            dayStartTs = dayStart,
            stepsToday = stepsToday,
            distanceKmToday = distanceKm,
            activeMinutesToday = activeMinutes,
            activeCaloriesKcalToday = caloriesKcal,
            activityRatio = ratio,
            distanceSource = distanceSource,
            activeCaloriesSource = caloriesSource
        )
        return updatedState to snapshot
    }

    private fun computeDelta(previous: LocalActivityState, counter: Double, ts: Long): Pair<Double, Double?> {
        if (previous.lastTs <= 0L || previous.lastCounter <= 0.0) {
            return 0.0 to null
        }
        val dtMin = ((ts - previous.lastTs).coerceAtLeast(0L)) / 60_000.0
        if (dtMin <= 0.0) return 0.0 to null
        val deltaSteps = (counter - previous.lastCounter).coerceAtLeast(0.0)
        return deltaSteps to dtMin
    }

    private fun Double?.validDistanceKmOrNull(): Double? =
        this?.takeIf { it.isFinite() && it in 0.0..MAX_REPORTED_DISTANCE_KM }

    private fun Double?.validActiveCaloriesKcalOrNull(): Double? =
        this?.takeIf { it.isFinite() && it in 0.0..MAX_DAILY_ACTIVE_KCAL }

    private fun LocalActivityState.normalized(): LocalActivityState {
        val lastCounter = lastCounter.validStepCounterOrNull()
            ?: baselineCounter.validStepCounterOrNull()
            ?: 0.0
        val baselineCounter = baselineCounter.validStepCounterOrNull()
            ?.takeIf { it <= lastCounter }
            ?: lastCounter
        val activeMinutes = activeMinutesToday.takeIf { it.isFinite() && it in 0.0..MAX_ACTIVE_MINUTES }
            ?: 0.0
        return copy(
            baselineCounter = baselineCounter,
            lastCounter = lastCounter,
            activeMinutesToday = activeMinutes
        )
    }

    private fun Double.validStepCounterOrNull(): Double? =
        takeIf { it.isFinite() && it in 0.0..MAX_STEP_COUNTER_TOTAL }

    private fun Double?.validHeightOrNull(): Double? =
        this?.takeIf { it.isFinite() && it in PROFILE_HEIGHT_CM_RANGE }

    private fun Double?.validWeightOrNull(): Double? =
        this?.takeIf { it.isFinite() && it in PROFILE_WEIGHT_KG_RANGE }

    private companion object {
        const val METERS_PER_KILOMETER = 1_000.0
        const val DEFAULT_STRIDE_METERS = 0.78
        const val DEFAULT_KCAL_PER_STEP = 0.04
        const val STRIDE_PER_HEIGHT_CM = 0.00415
        const val MIN_STRIDE_METERS = 0.45
        const val MAX_STRIDE_METERS = 1.05
        const val MAX_KCAL_PER_STEP = 0.20
        const val WALKING_KCAL_PER_KG_KM = 0.75
        // Reported Health Connect values outside these single-day limits are treated as invalid.
        const val MAX_REPORTED_DISTANCE_KM = 100.0
        const val MAX_DAILY_ACTIVE_KCAL = 10_000.0
        const val MAX_STEP_DELTA_PER_SAMPLE = 10_000.0
        const val MAX_DAILY_STEPS = 100_000.0
        const val MAX_STEP_COUNTER_TOTAL = 5_000_000.0
        const val MAX_ACTIVE_MINUTES = 1_440.0
        val PROFILE_HEIGHT_CM_RANGE = 80.0..230.0
        val PROFILE_WEIGHT_KG_RANGE = 10.0..350.0
    }

}
