package io.aaps.copilot.domain.activity

import java.time.Instant
import java.time.ZoneId
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
    val activityRatio: Double
)

class LocalActivityMetricsEstimator(
    private val strideMeters: Double = 0.78,
    private val kcalPerStep: Double = 0.04
) {

    fun update(
        counterTotal: Double,
        timestamp: Long,
        previous: LocalActivityState,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<LocalActivityState, LocalActivitySnapshot> {
        val safeCounter = counterTotal.coerceAtLeast(0.0)
        val safeTimestamp = timestamp.coerceAtLeast(1L)
        val dayStart = Instant.ofEpochMilli(safeTimestamp)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val isNewDay = previous.dayStartTs != dayStart
        val baseline = when {
            isNewDay -> safeCounter
            previous.baselineCounter <= 0.0 -> safeCounter
            safeCounter < previous.baselineCounter -> safeCounter
            else -> previous.baselineCounter
        }

        val stepsToday = max(0.0, safeCounter - baseline)
        val (deltaSteps, dtMin) = computeDelta(previous, safeCounter, safeTimestamp)
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
            (previous.activeMinutesToday + activeIncrementMinutes).coerceIn(0.0, 1_440.0)
        }

        val ratio = ActivityIntensity.ratioFromPacePerMinute(pacePerMinute)
        val distanceKm = stepsToday * strideMeters / 1000.0
        val caloriesKcal = stepsToday * kcalPerStep

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
            activityRatio = ratio
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

}
