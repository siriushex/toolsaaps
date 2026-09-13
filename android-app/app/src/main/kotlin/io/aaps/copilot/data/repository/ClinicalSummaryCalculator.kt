package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

object ClinicalSummaryCalculator {
    const val BUCKET_MS = 5L * 60L * 1_000L
    const val EXPECTED_HALF_CADENCE_MS = BUCKET_MS / 2L
    const val MAX_BRIDGE_GAP_MS = 10L * 60L * 1_000L
    const val WINDOW_DURATION_TOLERANCE_MS = 1_000L

    private const val DAY_MS = 24L * 60L * 60L * 1_000L
    private val SUPPORTED_DAYS = setOf(1, 7, 30)

    fun calculate(
        days: Int,
        fromTs: Long,
        throughTs: Long,
        zoneId: ZoneId,
        glucose: List<ClinicalGlucosePoint>,
        therapy: List<ClinicalTherapyPoint>,
        targets: List<ClinicalTargetPoint>,
        targetIntervals: List<ClinicalTargetInterval>? = null,
        rejections: ClinicalRejectionCounts = ClinicalRejectionCounts(),
        telemetry: List<ClinicalTelemetryPoint> = emptyList(),
        authoritativeTdd: ClinicalAapsTddPeriod? = null
    ): ClinicalPeriodSummary {
        require(days in SUPPORTED_DAYS) { "Clinical summary supports only 1, 7, or 30 days" }
        require(throughTs > fromTs) { "Clinical summary window must have positive duration" }
        val windowDurationMs = Math.subtractExact(throughTs, fromTs)
        val expectedWindowDurationMs = Math.multiplyExact(days.toLong(), DAY_MS)
        require(
            safeAbsDifference(windowDurationMs, expectedWindowDurationMs) <=
                WINDOW_DURATION_TOLERANCE_MS
        ) {
            "Clinical summary window must match the requested epoch-day duration within 1 second"
        }

        val validGlucose = glucose.asSequence()
            .filter { it.ts in fromTs..throughTs && it.mmol.isFinite() && it.mmol > 0.0 }
            .groupBy(ClinicalGlucosePoint::ts)
            .toSortedMap()
            .map { (ts, points) -> ClinicalGlucosePoint(ts, median(points.map { it.mmol })) }
        val values = validGlucose.map { it.mmol }
        val expectedBuckets = (expectedWindowDurationMs / BUCKET_MS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val supportedSamples = supportedSamples(validGlucose, fromTs, throughTs)
        val supportedDurationMs = supportedSamples.sumOf { it.durationMs }
        val coveragePct = percent(supportedDurationMs, windowDurationMs)

        val validTherapy = therapy.filter { it.ts in fromTs..throughTs }
        val eventInsulin = validTherapy.sumOf {
            it.insulinU?.takeIf { value -> value.isFinite() && value > 0.0 } ?: 0.0
        }
        val confirmedInsulin = validTherapy.asSequence()
            .filter { it.insulinEvidence != ClinicalInsulinEvidence.IOB_DERIVED }
            .sumOf {
                it.insulinU?.takeIf { value -> value.isFinite() && value > 0.0 } ?: 0.0
            }
        val estimatedInsulin = validTherapy.asSequence()
            .filter { it.insulinEvidence == ClinicalInsulinEvidence.IOB_DERIVED }
            .sumOf {
                it.insulinU?.takeIf { value -> value.isFinite() && value > 0.0 } ?: 0.0
            }
        val enteredCarbs = validTherapy.asSequence()
            .filterNot(ClinicalTherapyPoint::syntheticUam)
            .sumOf {
                it.carbsG?.takeIf { value -> value.isFinite() && value > 0.0 } ?: 0.0
            }
        val uamCarbs = validTherapy.asSequence()
            .filter(ClinicalTherapyPoint::syntheticUam)
            .sumOf {
                it.carbsG?.takeIf { value -> value.isFinite() && value > 0.0 } ?: 0.0
            }
        val knownCarbs = enteredCarbs + uamCarbs
        val aapsHistory = authoritativeTdd?.takeIf {
            it.fromTs == fromTs &&
                it.throughTs == throughTs &&
                it.totalInsulinU.isFinite() &&
                it.totalInsulinU >= 0.0 &&
                it.basalInsulinU.isFinite() &&
                it.basalInsulinU >= 0.0 &&
                it.bolusInsulinU.isFinite() &&
                it.bolusInsulinU >= 0.0 &&
                it.carbsG.isFinite() &&
                it.carbsG >= 0.0
        }
        val totalInsulin = aapsHistory?.totalInsulinU ?: eventInsulin
        // The report's carbohydrate total must remain explainable as the canonical
        // deduplicated Copilot event stream: entered carbs plus synthetic UAM. The
        // raw AAPS aggregate is retained separately for reconciliation because it
        // can lag and does not necessarily include Copilot synthetic UAM entries.
        val totalCarbs = knownCarbs
        val activitySummary = activitySummary(
            telemetry = telemetry,
            fromTs = fromTs,
            throughTs = throughTs,
            zoneId = zoneId
        )
        val basalContext = basalContextSummary(
            telemetry = telemetry,
            fromTs = fromTs,
            throughTs = throughTs
        )
        val therapyContext = ClinicalTherapyContextSummary(
            infusionSetChanges = validTherapy.count {
                it.contextKind == ClinicalTherapyContextKind.INFUSION_SET_CHANGE
            },
            sensorChanges = validTherapy.count {
                it.contextKind == ClinicalTherapyContextKind.SENSOR_CHANGE
            },
            insulinRefills = validTherapy.count {
                it.contextKind == ClinicalTherapyContextKind.INSULIN_REFILL
            },
            pumpBatteryChanges = validTherapy.count {
                it.contextKind == ClinicalTherapyContextKind.PUMP_BATTERY_CHANGE
            },
            exerciseEvents = validTherapy.count {
                it.contextKind == ClinicalTherapyContextKind.EXERCISE
            },
            profileSwitches = validTherapy.count {
                it.contextKind == ClinicalTherapyContextKind.PROFILE_SWITCH
            }
        )
        val targetValues = targets.asSequence()
            .distinct()
            .filter { it.ts in fromTs..throughTs }
            .mapNotNull { it.targetMmol }
            .filter { it.isFinite() && it > 0.0 }
            .toList()

        return ClinicalPeriodSummary(
            days = days,
            fromTs = fromTs,
            throughTs = throughTs,
            coveragePct = coveragePct,
            meanMmol = mean(values),
            medianMmol = values.takeIf { it.isNotEmpty() }?.let(::median),
            coefficientOfVariationPct = coefficientOfVariation(values),
            timeBelow4Pct = supportedSamples.takeIf { it.isNotEmpty() }?.let { samples ->
                percent(
                    samples.filter { it.point.mmol < 4.0 }.sumOf { it.durationMs },
                    supportedDurationMs
                )
            },
            timeInRangePct = supportedSamples.takeIf { it.isNotEmpty() }?.let { samples ->
                percent(
                    samples.filter { it.point.mmol in 4.0..10.0 }.sumOf { it.durationMs },
                    supportedDurationMs
                )
            },
            timeAboveRangePct = supportedSamples.takeIf { it.isNotEmpty() }?.let { samples ->
                percent(
                    samples.filter { it.point.mmol > 10.0 }.sumOf { it.durationMs },
                    supportedDurationMs
                )
            },
            totalInsulinU = totalInsulin,
            totalCarbsG = totalCarbs,
            meanTargetMmol = when (targetIntervals) {
                null -> mean(targetValues)
                else -> durationWeightedTargetMean(targetIntervals)
            },
            weekdayPattern = hourlyPattern(validGlucose, zoneId, weekend = false),
            weekendPattern = hourlyPattern(validGlucose, zoneId, weekend = true),
            quality = ClinicalDataQuality(
                expectedBuckets = expectedBuckets,
                coveredBuckets = validGlucose.size,
                missingBuckets = (expectedBuckets - validGlucose.size).coerceAtLeast(0),
                maxGapMinutes = maxGapMinutes(validGlucose, fromTs, throughTs),
                rejected = rejections
            ),
            confirmedInsulinU = confirmedInsulin,
            estimatedInsulinU = estimatedInsulin,
            deliveredBasalInsulinU = aapsHistory?.basalInsulinU,
            deliveredBolusInsulinU = aapsHistory?.bolusInsulinU,
            insulinTotalSource = if (aapsHistory != null) {
                ClinicalInsulinTotalSource.AAPS_RAW_HISTORY
            } else {
                ClinicalInsulinTotalSource.COPILOT_EVENTS
            },
            enteredCarbsG = enteredCarbs,
            uamCarbsG = uamCarbs,
            aapsCarbsG = aapsHistory?.carbsG,
            insulinEventCount = validTherapy.count {
                it.insulinU?.let { value -> value.isFinite() && value > 0.0 } == true
            },
            estimatedInsulinEventCount = validTherapy.count {
                it.insulinEvidence == ClinicalInsulinEvidence.IOB_DERIVED &&
                    it.insulinU?.let { value -> value.isFinite() && value > 0.0 } == true
            },
            enteredCarbEventCount = validTherapy.count {
                !it.syntheticUam &&
                    it.carbsG?.let { value -> value.isFinite() && value > 0.0 } == true
            },
            uamCarbEventCount = validTherapy.count {
                it.syntheticUam &&
                    it.carbsG?.let { value -> value.isFinite() && value > 0.0 } == true
            },
            activity = activitySummary,
            basalContext = basalContext,
            therapyContext = therapyContext
        )
    }

    private fun activitySummary(
        telemetry: List<ClinicalTelemetryPoint>,
        fromTs: Long,
        throughTs: Long,
        zoneId: ZoneId
    ): ClinicalActivitySummary {
        val physical = telemetry.asSequence()
            .filter { it.ts in fromTs..throughTs }
            .filter {
                it.origin == ClinicalTelemetryOrigin.LOCAL_ACTIVITY ||
                    it.origin == ClinicalTelemetryOrigin.HEALTH_CONNECT
            }
            .filter {
                PhysicalActivityTelemetryPolicy.normalizeQuality(it.quality) in
                    PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES
            }
            .filter { it.value.isFinite() }
            .toList()
        val coveragePoints = physical.asSequence()
            .filter { it.key == "steps_count" || it.key == "activity_ratio" }
            .groupBy(ClinicalTelemetryPoint::ts)
            .toSortedMap()
            .map { (ts, candidates) ->
                ClinicalGlucosePoint(ts, candidates.maxOf(ClinicalTelemetryPoint::value))
            }
        val coveredMs = supportedSamples(coveragePoints, fromTs, throughTs)
            .sumOf(SupportedSample::durationMs)
        val ratios = physical.filter {
            it.key == "activity_ratio" && it.value in 0.2..3.0
        }.map(ClinicalTelemetryPoint::value)
        val ratioPeaks = physical.filter {
            it.key == "activity_ratio_peak" && it.value in 0.2..3.0
        }.map(ClinicalTelemetryPoint::value)
        return ClinicalActivitySummary(
            coveragePct = percent(coveredMs, throughTs - fromTs),
            steps = cumulativeMetric(physical, "steps_count", fromTs, zoneId),
            distanceKm = cumulativeMetric(physical, "distance_km", fromTs, zoneId),
            activeMinutes = cumulativeMetric(physical, "active_minutes", fromTs, zoneId),
            activeCaloriesKcal = cumulativeMetric(
                physical,
                "calories_active_kcal",
                fromTs,
                zoneId
            ),
            meanActivityRatio = mean(ratios),
            maxActivityRatio = ratioPeaks.maxOrNull() ?: ratios.maxOrNull()
        )
    }

    private fun cumulativeMetric(
        telemetry: List<ClinicalTelemetryPoint>,
        key: String,
        fromTs: Long,
        zoneId: ZoneId
    ): Double? {
        val grouped = telemetry.asSequence()
            .filter { it.key == key && it.value >= 0.0 }
            .groupBy {
                Instant.ofEpochMilli(it.ts).atZone(zoneId).toLocalDate()
            }
        if (grouped.isEmpty()) return null
        return grouped.entries.sumOf { (date, rows) ->
            val ordered = rows.sortedBy(ClinicalTelemetryPoint::ts)
            val maximum = ordered.maxOf(ClinicalTelemetryPoint::value)
            val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            if (dayStart < fromTs) {
                (maximum - ordered.first().value).coerceAtLeast(0.0)
            } else {
                maximum
            }
        }
    }

    private fun basalContextSummary(
        telemetry: List<ClinicalTelemetryPoint>,
        fromTs: Long,
        throughTs: Long
    ): ClinicalBasalContextSummary {
        val basal = telemetry.asSequence()
            .filter {
                it.ts in fromTs..throughTs &&
                    it.origin == ClinicalTelemetryOrigin.AAPS &&
                    it.key == "basal_rate_u_h" &&
                    it.value.isFinite() &&
                    it.value in 0.0..15.0
            }
            .groupBy(ClinicalTelemetryPoint::ts)
            .toSortedMap()
            .map { (ts, rows) -> ClinicalGlucosePoint(ts, rows.maxOf { it.value }) }
        val profilePercents = telemetry.asSequence()
            .filter {
                it.ts in fromTs..throughTs &&
                    it.origin == ClinicalTelemetryOrigin.AAPS &&
                    it.key == "profile_percent" &&
                    it.value.isFinite() &&
                    it.value in 1.0..500.0
            }
            .map(ClinicalTelemetryPoint::value)
            .toList()
        val coveredMs = supportedSamples(basal, fromTs, throughTs)
            .sumOf(SupportedSample::durationMs)
        val rates = basal.map(ClinicalGlucosePoint::mmol)
        return ClinicalBasalContextSummary(
            coveragePct = percent(coveredMs, throughTs - fromTs),
            meanProfileRateUph = mean(rates),
            minProfileRateUph = rates.minOrNull(),
            maxProfileRateUph = rates.maxOrNull(),
            meanProfilePercent = mean(profilePercents)
        )
    }

    internal fun durationWeightedTargetMean(intervals: List<ClinicalTargetInterval>): Double? {
        var weightedTotal = 0.0
        var durationTotal = 0L
        intervals.forEach { interval ->
            val duration = Math.subtractExact(interval.endTs, interval.startTs)
            if (duration > 0L && interval.midpointMmol.isFinite()) {
                durationTotal = Math.addExact(durationTotal, duration)
                weightedTotal += interval.midpointMmol * duration.toDouble()
            }
        }
        return weightedTotal.takeIf { durationTotal > 0L }?.div(durationTotal.toDouble())
    }

    private fun hourlyPattern(
        glucose: List<ClinicalGlucosePoint>,
        zoneId: ZoneId,
        weekend: Boolean
    ): List<ClinicalHourlyMetric> {
        val grouped = glucose.mapNotNull { point ->
            val local = Instant.ofEpochMilli(point.ts).atZone(zoneId)
            val isWeekend = local.dayOfWeek == DayOfWeek.SATURDAY ||
                local.dayOfWeek == DayOfWeek.SUNDAY
            point.takeIf { isWeekend == weekend }?.let { local.hour to it.mmol }
        }.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second }
        )
        return (0..23).map { hour ->
            val values = grouped[hour].orEmpty()
            ClinicalHourlyMetric(
                hour = hour,
                sampleCount = values.size,
                meanMmol = mean(values),
                medianMmol = values.takeIf { it.isNotEmpty() }?.let(::median)
            )
        }
    }

    private fun supportedSamples(
        glucose: List<ClinicalGlucosePoint>,
        fromTs: Long,
        throughTs: Long
    ): List<SupportedSample> = glucose.mapIndexedNotNull { index, point ->
        val previous = glucose.getOrNull(index - 1)
        val next = glucose.getOrNull(index + 1)
        val start = if (previous != null && point.ts - previous.ts <= MAX_BRIDGE_GAP_MS) {
            midpoint(previous.ts, point.ts)
        } else {
            (point.ts - EXPECTED_HALF_CADENCE_MS).coerceAtLeast(fromTs)
        }
        val end = if (next != null && next.ts - point.ts <= MAX_BRIDGE_GAP_MS) {
            midpoint(point.ts, next.ts)
        } else {
            (point.ts + EXPECTED_HALF_CADENCE_MS).coerceAtMost(throughTs)
        }
        SupportedSample(point, start, end).takeIf { it.durationMs > 0L }
    }

    private fun maxGapMinutes(
        glucose: List<ClinicalGlucosePoint>,
        fromTs: Long,
        throughTs: Long
    ): Int {
        val maxGapMs = if (glucose.isEmpty()) {
            throughTs - fromTs
        } else {
            maxOf(
                glucose.first().ts - fromTs,
                glucose.zipWithNext().maxOfOrNull { (left, right) -> right.ts - left.ts } ?: 0L,
                throughTs - glucose.last().ts
            )
        }
        return ceil(maxGapMs / 60_000.0).toInt()
    }

    private fun midpoint(left: Long, right: Long): Long = left + (right - left) / 2L

    private fun safeAbsDifference(left: Long, right: Long): Long =
        runCatching { Math.subtractExact(left, right) }.getOrNull()?.let {
            if (it == Long.MIN_VALUE) Long.MAX_VALUE else abs(it)
        } ?: Long.MAX_VALUE

    private fun mean(values: List<Double>): Double? =
        values.takeIf { it.isNotEmpty() }?.average()

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun coefficientOfVariation(values: List<Double>): Double? {
        val mean = mean(values)?.takeIf { it > 0.0 } ?: return null
        val variance = values.sumOf { value ->
            val delta = value - mean
            delta * delta
        } / values.size
        return sqrt(variance) / mean * 100.0
    }

    private fun percent(part: Long, total: Long): Double =
        if (total <= 0L) 0.0 else part.toDouble() / total.toDouble() * 100.0

    private data class SupportedSample(
        val point: ClinicalGlucosePoint,
        val startTs: Long,
        val endTs: Long
    ) {
        val durationMs: Long
            get() = (endTs - startTs).coerceAtLeast(0L)
    }
}
