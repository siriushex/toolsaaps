package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.CircadianForecastPrior
import io.aaps.copilot.domain.model.CircadianPatternSnapshot
import io.aaps.copilot.domain.model.CircadianReplayBucketStatus
import io.aaps.copilot.domain.model.CircadianReplaySlotStat
import io.aaps.copilot.domain.model.CircadianSlotStat
import io.aaps.copilot.domain.model.CircadianTransitionStat
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.PatternWindow
import io.aaps.copilot.domain.model.Forecast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class CircadianPatternConfig(
    val baseTargetMmol: Double,
    val lowThresholdMmol: Double = 3.9,
    val highThresholdMmol: Double = 10.0,
    val stableWindowsDays: List<Int> = listOf(14, 10, 7),
    val recencyWindowDays: Int = 5,
    val minSlotSamples: Int = 6,
    val minActiveDaysWeekday: Int = 3,
    val minActiveDaysWeekend: Int = 2,
    val minActiveDaysAll: Int = 4,
    val minCoverageRatioWeekday: Double = 0.45,
    val minCoverageRatioWeekend: Double = 0.40,
    val minCoverageRatioAll: Double = 0.40,
    val useWeekendSplit: Boolean = true,
    val useReplayResidualBias: Boolean = true,
    val recencyMaxWeight: Double = 0.30,
    val recencyBgClampMmol: Double = 1.5,
    val recencyDeltaClampMmol: Double = 1.2,
    val minDayCoverageQuality: Double = 0.35,
    val minDayObservedShare: Double = 0.35,
    val maxGapMinutesForGoodWindow: Int = 60
)

data class CircadianPatternResult(
    val slotStats: List<CircadianSlotStat>,
    val transitionStats: List<CircadianTransitionStat>,
    val snapshots: List<CircadianPatternSnapshot>,
    val derivedPatternWindows: List<PatternWindow>
)

class CircadianPatternEngine(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    fun fit(
        glucoseHistory: List<GlucosePoint>,
        telemetryHistory: List<TelemetrySignal>,
        forecastHistory: List<Forecast>,
        nowTs: Long,
        config: CircadianPatternConfig
    ): CircadianPatternResult {
        if (glucoseHistory.isEmpty()) {
            return CircadianPatternResult(
                slotStats = emptyList(),
                transitionStats = emptyList(),
                snapshots = emptyList(),
                derivedPatternWindows = emptyList()
            )
        }
        val maxWindowDays = max(config.stableWindowsDays.maxOrNull() ?: 14, config.recencyWindowDays)
        val startTs = nowTs - maxWindowDays * DAY_MS
        val canonical = buildObservedFiveMinuteSeries(glucoseHistory, startTs = startTs)
        if (canonical.points.isEmpty()) {
            return CircadianPatternResult(
                slotStats = emptyList(),
                transitionStats = emptyList(),
                snapshots = emptyList(),
                derivedPatternWindows = emptyList()
            )
        }
        val telemetryMetrics = telemetryHistory
            .asSequence()
            .filter { it.ts >= startTs }
            .mapNotNull(::toTelemetryMetric)
            .sortedBy { it.ts }
            .toList()

        val windowDays = (config.stableWindowsDays + config.recencyWindowDays)
            .distinct()
            .sortedDescending()

        val rawSlotStats = mutableListOf<CircadianSlotStat>()
        val rawTransitions = mutableListOf<CircadianTransitionStat>()
        val dayCoverageByType = mutableMapOf<Pair<CircadianDayType, Int>, Int>()

        val analyzedDayTypes = if (config.useWeekendSplit) {
            CircadianDayType.entries.toList()
        } else {
            listOf(CircadianDayType.ALL)
        }
        windowDays.forEach { days ->
            val scoped = canonical.points.filter { it.ts >= nowTs - days * DAY_MS }
            if (scoped.isEmpty()) return@forEach
            val dayQuality = buildDayQualityMap(
                points = scoped,
                nowTs = nowTs,
                config = config
            )
            analyzedDayTypes.forEach { dayType ->
                val dayTypeScoped = scoped.filter { matchesDayType(it.ts, dayType) }
                if (dayTypeScoped.isEmpty()) return@forEach
                val usableDates = dayTypeScoped
                    .asSequence()
                    .map { localDate(it.ts) }
                    .distinct()
                    .filter { (dayQuality[it]?.usable == true) || dayType == CircadianDayType.ALL }
                    .toSet()
                dayCoverageByType[dayType to days] = usableDates.size

                val slotStats = buildSlotStats(
                    points = dayTypeScoped,
                    telemetryMetrics = telemetryMetrics,
                    dayType = dayType,
                    windowDays = days,
                    nowTs = nowTs,
                    usableDates = usableDates,
                    dayQuality = dayQuality,
                    config = config
                )
                rawSlotStats += slotStats

                val transitions = buildTransitionStats(
                    points = dayTypeScoped,
                    forecastHistory = forecastHistory,
                    dayType = dayType,
                    windowDays = days,
                    nowTs = nowTs,
                    usableDates = usableDates,
                    dayQuality = dayQuality,
                    config = config
                )
                rawTransitions += transitions
            }
        }

        val snapshots = buildSnapshots(
            slotStats = rawSlotStats,
            config = config,
            nowTs = nowTs,
            dayCoverageByType = dayCoverageByType
        )
        val derivedPatternWindows = derivePatternWindows(
            slotStats = rawSlotStats,
            snapshots = snapshots,
            config = config
        )

        return CircadianPatternResult(
            slotStats = rawSlotStats.sortedWith(compareBy<CircadianSlotStat> { it.dayType.name }.thenByDescending { it.windowDays }.thenBy { it.slotIndex }),
            transitionStats = rawTransitions.sortedWith(
                compareBy<CircadianTransitionStat> { it.dayType.name }
                    .thenByDescending { it.windowDays }
                    .thenBy { it.slotIndex }
                    .thenBy { it.horizonMinutes }
            ),
            snapshots = snapshots.sortedBy { it.requestedDayType.name },
            derivedPatternWindows = derivedPatternWindows.sortedWith(compareBy<PatternWindow> { it.dayType.name }.thenBy { it.hour })
        )
    }

    fun resolvePrior(
        nowTs: Long,
        currentGlucoseMmol: Double,
        telemetry: Map<String, Double?>,
        snapshots: List<CircadianPatternSnapshot>,
        slotStats: List<CircadianSlotStat>,
        transitionStats: List<CircadianTransitionStat>,
        replayStats: List<CircadianReplaySlotStat> = emptyList(),
        config: CircadianPatternConfig
    ): CircadianForecastPrior? {
        val requestedDayType = currentDayType(nowTs)
        val snapshot = snapshots.firstOrNull { it.requestedDayType == requestedDayType } ?: return null
        val effectiveSource = snapshot.segmentSource
        val slotIndex = slotIndex(nowTs)
        val stableSlot = slotStats.firstOrNull {
            it.dayType == effectiveSource && it.windowDays == snapshot.stableWindowDays && it.slotIndex == slotIndex
        } ?: return null
        val recencySlot = slotStats.firstOrNull {
            it.dayType == effectiveSource && it.windowDays == snapshot.recencyWindowDays && it.slotIndex == slotIndex
        }
        val transitionsByHorizon = transitionStats.filter {
            it.dayType == effectiveSource &&
                it.windowDays == snapshot.stableWindowDays &&
                it.slotIndex == slotIndex &&
                it.horizonMinutes in setOf(15, 30, 60)
        }.associateBy { it.horizonMinutes }
        val recencyTransitions = transitionStats.filter {
            it.dayType == effectiveSource &&
                it.windowDays == snapshot.recencyWindowDays &&
                it.slotIndex == slotIndex &&
                it.horizonMinutes in setOf(15, 30, 60)
        }.associateBy { it.horizonMinutes }

        val recencyWeight = snapshot.recencyWeight.coerceIn(0.0, config.recencyMaxWeight)
        val blendedBg = blendBounded(
            stable = stableSlot.medianBg,
            recency = recencySlot?.medianBg,
            weight = recencyWeight,
            clampAbs = config.recencyBgClampMmol
        )
        val delta15 = blendBounded(
            stable = transitionsByHorizon[15]?.deltaMedian ?: 0.0,
            recency = recencyTransitions[15]?.deltaMedian,
            weight = recencyWeight,
            clampAbs = config.recencyDeltaClampMmol
        )
        val delta30 = blendBounded(
            stable = transitionsByHorizon[30]?.deltaMedian ?: 0.0,
            recency = recencyTransitions[30]?.deltaMedian,
            weight = recencyWeight,
            clampAbs = config.recencyDeltaClampMmol
        )
        val delta60 = blendBounded(
            stable = transitionsByHorizon[60]?.deltaMedian ?: 0.0,
            recency = recencyTransitions[60]?.deltaMedian,
            weight = recencyWeight,
            clampAbs = config.recencyDeltaClampMmol
        )
        val residualBias30 = (transitionsByHorizon[30]?.residualBiasMmol ?: 0.0)
            .coerceIn(-0.25, 0.25)
        val residualBias60 = (transitionsByHorizon[60]?.residualBiasMmol ?: 0.0)
            .coerceIn(-0.40, 0.40)
        val horizonQuality30 = transitionQuality(
            stat = transitionsByHorizon[30],
            defaultQuality = stableSlot.qualityScore,
            spreadReference = 2.4
        )
        val horizonQuality60 = transitionQuality(
            stat = transitionsByHorizon[60],
            defaultQuality = stableSlot.qualityScore,
            spreadReference = 3.2
        )
        val stabilityScore = slotStabilityScore(stableSlot)
        val stableReplayRows = replayStats.filter {
            it.dayType == requestedDayType &&
                it.windowDays == snapshot.stableWindowDays &&
                it.horizonMinutes in setOf(30, 60)
        }
        val recencyReplayRows = replayStats.filter {
            it.dayType == requestedDayType &&
                it.windowDays == snapshot.recencyWindowDays &&
                it.horizonMinutes in setOf(30, 60)
        }
        val fallbackReplayRows = replayStats.filter {
            it.dayType == CircadianDayType.ALL &&
                it.windowDays == snapshot.stableWindowDays &&
                it.horizonMinutes in setOf(30, 60)
        }

        val delta5 = telemetry["sensor_quality_delta5_mmol"]
            ?: telemetry["delta5_mmol"]
            ?: telemetry["glucose_delta5_mmol"]
            ?: 0.0
        val uamFlag = maxOf(
            telemetry["uam_runtime_flag"] ?: 0.0,
            telemetry["uam_inferred_flag"] ?: 0.0,
            telemetry["uam_value"] ?: 0.0
        )
        val uamConfidence = maxOf(
            telemetry["uam_runtime_confidence"] ?: 0.0,
            telemetry["uam_inferred_confidence"] ?: 0.0
        )
        val uamRecentAbsorption = telemetry["uam_inferred_gabs_last5_g"] ?: 0.0
        val uamUci0 = abs(telemetry["uam_uci0_mmol5"] ?: 0.0)
        val strongUam = uamFlag >= 0.5 &&
            (
                uamConfidence >= 0.30 ||
                    uamRecentAbsorption >= 0.8 ||
                    uamUci0 >= 0.10
                )
        val dormantUam = uamFlag >= 0.5 && !strongUam
        val stale = (telemetry["sensor_quality_blocked"] ?: 0.0) >= 0.5 ||
            (telemetry["sensor_quality_suspect_false_low"] ?: 0.0) >= 0.5
        val acuteAttenuation = when {
            abs(delta5) > 0.30 -> 0.4
            (telemetry["cob_grams"] ?: 0.0) >= 15.0 -> 0.4
            (telemetry["iob_units"] ?: 0.0) >= 3.0 -> 0.4
            strongUam -> 0.4
            dormantUam -> 0.7
            else -> 1.0
        }
        val confidence = min(
            snapshot.confidence,
            min(
                stableSlot.confidence,
                listOfNotNull(
                    transitionsByHorizon[15]?.confidence,
                    transitionsByHorizon[30]?.confidence,
                    transitionsByHorizon[60]?.confidence
                ).minOrNull() ?: stableSlot.confidence
            )
        ).coerceIn(0.0, 1.0)
        val replay30 = resolveReplayMetric(
            stable = resolveReplayBucket(
                rows = stableReplayRows,
                slotIndex = slotIndex,
                horizonMinutes = 30,
                requestedDayType = requestedDayType
            ) ?: resolveReplayBucket(
                rows = fallbackReplayRows,
                slotIndex = slotIndex,
                horizonMinutes = 30,
                requestedDayType = CircadianDayType.ALL
            ),
            recency = resolveReplayBucket(
                rows = recencyReplayRows,
                slotIndex = slotIndex,
                horizonMinutes = 30,
                requestedDayType = requestedDayType
            ),
            requestedDayType = requestedDayType,
            correctionClamp = 0.12
        )
        val replay60 = resolveReplayMetric(
            stable = resolveReplayBucket(
                rows = stableReplayRows,
                slotIndex = slotIndex,
                horizonMinutes = 60,
                requestedDayType = requestedDayType
            ) ?: resolveReplayBucket(
                rows = fallbackReplayRows,
                slotIndex = slotIndex,
                horizonMinutes = 60,
                requestedDayType = CircadianDayType.ALL
            ),
            recency = resolveReplayBucket(
                rows = recencyReplayRows,
                slotIndex = slotIndex,
                horizonMinutes = 60,
                requestedDayType = requestedDayType
            ),
            requestedDayType = requestedDayType,
            correctionClamp = 0.20
        )
        val medianReversion30 = medianReversionBias(
            currentGlucoseMmol = currentGlucoseMmol,
            slot = stableSlot,
            horizonMinutes = 30,
            horizonQuality = horizonQuality30,
            stabilityScore = stabilityScore,
            replayStatus = replay30.status,
            acuteAttenuation = acuteAttenuation,
            stale = stale
        )
        val medianReversion60 = medianReversionBias(
            currentGlucoseMmol = currentGlucoseMmol,
            slot = stableSlot,
            horizonMinutes = 60,
            horizonQuality = horizonQuality60,
            stabilityScore = stabilityScore,
            replayStatus = replay60.status,
            acuteAttenuation = acuteAttenuation,
            stale = stale
        )
        return CircadianForecastPrior(
            requestedDayType = requestedDayType,
            segmentSource = effectiveSource,
            slotIndex = slotIndex,
            bgMedian = blendedBg.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL),
            slotP10 = stableSlot.p10,
            slotP25 = stableSlot.p25,
            slotP75 = stableSlot.p75,
            slotP90 = stableSlot.p90,
            delta15 = delta15,
            delta30 = delta30 + residualBias30,
            delta60 = delta60 + residualBias60,
            residualBias30 = residualBias30,
            residualBias60 = residualBias60,
            medianReversion30 = medianReversion30,
            medianReversion60 = medianReversion60,
            replayBias30 = replay30.bias.coerceIn(-0.20, 0.20),
            replayBias60 = replay60.bias.coerceIn(-0.35, 0.35),
            replaySampleCount30 = replay30.sampleCount,
            replaySampleCount60 = replay60.sampleCount,
            replayWinRate30 = replay30.winRate,
            replayWinRate60 = replay60.winRate,
            replayMaeImprovement30 = replay30.maeImprovementMmol,
            replayMaeImprovement60 = replay60.maeImprovementMmol,
            replayBucketStatus30 = replay30.status,
            replayBucketStatus60 = replay60.status,
            confidence = confidence,
            qualityScore = stableSlot.qualityScore,
            stabilityScore = stabilityScore,
            horizonQuality30 = horizonQuality30,
            horizonQuality60 = horizonQuality60,
            acuteAttenuation = acuteAttenuation,
            staleBlocked = stale
        )
    }

    private fun buildSlotStats(
        points: List<ObservedFiveMinutePoint>,
        telemetryMetrics: List<TelemetryMetricPoint>,
        dayType: CircadianDayType,
        windowDays: Int,
        nowTs: Long,
        usableDates: Set<LocalDate>,
        dayQuality: Map<LocalDate, DayQualitySummary>,
        config: CircadianPatternConfig
    ): List<CircadianSlotStat> {
        val slotPoints = points
            .asSequence()
            .filter { it.ts >= nowTs - windowDays * DAY_MS }
            .filter { dayType == CircadianDayType.ALL || matchesDayType(it.ts, dayType) }
            .filter { localDate(it.ts) in usableDates }
            .groupBy { slotIndex(it.ts) }
        val telemetryBySlot = telemetryMetrics
            .asSequence()
            .filter { it.ts >= nowTs - windowDays * DAY_MS }
            .filter { dayType == CircadianDayType.ALL || matchesDayType(it.ts, dayType) }
            .groupBy { slotIndex(it.ts) }
        return slotPoints.map { (slot, slotSamples) ->
            val bgValues = slotSamples.map { it.valueMmol }
            val activeDays = slotSamples.asSequence()
                .map { localDate(it.ts) }
                .distinct()
                .count()
            val dayQualityMean = slotSamples
                .mapNotNull { dayQuality[localDate(it.ts)]?.score }
                .averageOrNull()
                ?: 0.0
            val possibleDays = possibleDaysForWindow(nowTs = nowTs, dayType = dayType, windowDays = windowDays)
                .coerceAtLeast(1)
            val coverageRatio = activeDays.toDouble() / possibleDays.toDouble()
            val confidence = (
                min(1.0, slotSamples.size.toDouble() / max(config.minSlotSamples.toDouble(), 1.0)) * 0.55 +
                    coverageRatio.coerceIn(0.0, 1.0) * 0.30 +
                    dayQualityMean.coerceIn(0.0, 1.0) * 0.15
                ).coerceIn(0.0, 1.0)
            val rises = slotSamples.count { it.deltaFromPrev != null && it.deltaFromPrev >= 0.30 }
            val drops = slotSamples.count { it.deltaFromPrev != null && it.deltaFromPrev <= -0.30 }
            val total = slotSamples.size.toDouble().coerceAtLeast(1.0)
            val metrics = telemetryBySlot[slot].orEmpty()
            CircadianSlotStat(
                dayType = dayType,
                windowDays = windowDays,
                slotIndex = slot,
                sampleCount = slotSamples.size,
                activeDays = activeDays,
                medianBg = percentile(bgValues, 0.50),
                p10 = percentile(bgValues, 0.10),
                p25 = percentile(bgValues, 0.25),
                p75 = percentile(bgValues, 0.75),
                p90 = percentile(bgValues, 0.90),
                pLow = slotSamples.count { it.valueMmol <= config.lowThresholdMmol } / total,
                pHigh = slotSamples.count { it.valueMmol >= config.highThresholdMmol } / total,
                pInRange = slotSamples.count {
                    it.valueMmol > config.lowThresholdMmol && it.valueMmol < config.highThresholdMmol
                } / total,
                fastRiseRate = rises / total,
                fastDropRate = drops / total,
                meanCob = metrics.averageFor(TelemetryMetricType.COB),
                meanIob = metrics.averageFor(TelemetryMetricType.IOB),
                meanUam = metrics.averageFor(TelemetryMetricType.UAM),
                meanActivity = metrics.averageFor(TelemetryMetricType.ACTIVITY),
                confidence = confidence,
                qualityScore = dayQualityMean.coerceIn(0.0, 1.0),
                updatedAt = nowTs
            )
        }
    }

    private fun buildTransitionStats(
        points: List<ObservedFiveMinutePoint>,
        forecastHistory: List<Forecast>,
        dayType: CircadianDayType,
        windowDays: Int,
        nowTs: Long,
        usableDates: Set<LocalDate>,
        dayQuality: Map<LocalDate, DayQualitySummary>,
        config: CircadianPatternConfig
    ): List<CircadianTransitionStat> {
        val filtered = points
            .asSequence()
            .filter { it.ts >= nowTs - windowDays * DAY_MS }
            .filter { dayType == CircadianDayType.ALL || matchesDayType(it.ts, dayType) }
            .filter { localDate(it.ts) in usableDates }
            .sortedBy { it.ts }
            .toList()
        val pointByTs = filtered.associateBy { it.ts }
        val deltaBuckets = mutableMapOf<Triple<Int, Int, CircadianDayType>, MutableList<Double>>()
        val residualBuckets = mutableMapOf<Triple<Int, Int, CircadianDayType>, MutableList<Double>>()
        HORIZONS_MINUTES.forEach { horizon ->
            filtered.forEach { point ->
                val future = pointByTs[point.ts + horizon * MINUTE_MS] ?: return@forEach
                deltaBuckets.getOrPut(Triple(slotIndex(point.ts), horizon, dayType)) { mutableListOf() }
                    .add(future.valueMmol - point.valueMmol)
            }
            forecastHistory
                .asSequence()
                .filter { it.ts >= nowTs - windowDays * DAY_MS }
                .filter { it.horizonMinutes == horizon }
                .filter { dayType == CircadianDayType.ALL || matchesDayType(it.ts, dayType) }
                .filter { localDate(it.ts) in usableDates }
                .forEach { forecast ->
                    val targetTs = roundToFiveMinuteBucket(forecast.ts + horizon * MINUTE_MS)
                    val actual = pointByTs[targetTs] ?: return@forEach
                val residual = if (config.useReplayResidualBias) {
                    (actual.valueMmol - forecast.valueMmol).coerceIn(-4.0, 4.0)
                } else {
                    0.0
                }
                residualBuckets.getOrPut(Triple(slotIndex(forecast.ts), horizon, dayType)) { mutableListOf() }
                    .add(residual)
                }
        }
        return deltaBuckets.map { (key, values) ->
            val (slot, horizon, bucketDayType) = key
            val dayQualityMean = filtered
                .asSequence()
                .filter { slotIndex(it.ts) == slot }
                .mapNotNull { dayQuality[localDate(it.ts)]?.score }
                .toList()
                .averageOrNull()
                ?: 0.0
            val confidence = (
                min(1.0, values.size.toDouble() / 10.0) * 0.65 +
                    dayQualityMean.coerceIn(0.0, 1.0) * 0.35
                ).coerceIn(0.0, 1.0)
            CircadianTransitionStat(
                dayType = bucketDayType,
                windowDays = windowDays,
                slotIndex = slot,
                horizonMinutes = horizon,
                sampleCount = values.size,
                deltaMedian = percentile(values, 0.50),
                deltaP25 = percentile(values, 0.25),
                deltaP75 = percentile(values, 0.75),
                residualBiasMmol = percentile(
                    residualBuckets[Triple(slot, horizon, bucketDayType)].orEmpty(),
                    0.50
                ).coerceIn(
                    when (horizon) {
                        30 -> -0.25
                        60 -> -0.40
                        else -> -0.20
                    },
                    when (horizon) {
                        30 -> 0.25
                        60 -> 0.40
                        else -> 0.20
                    }
                ),
                confidence = confidence,
                updatedAt = nowTs
            )
        }
    }

    private fun buildSnapshots(
        slotStats: List<CircadianSlotStat>,
        config: CircadianPatternConfig,
        nowTs: Long,
        dayCoverageByType: Map<Pair<CircadianDayType, Int>, Int>
    ): List<CircadianPatternSnapshot> {
        val requestedDayTypes = if (config.useWeekendSplit) {
            CircadianDayType.entries.toList()
        } else {
            listOf(CircadianDayType.ALL)
        }
        return requestedDayTypes.map { requested ->
            val requestedStableWindow = config.stableWindowsDays.firstOrNull { days ->
                hasEnoughCoverage(
                    dayType = requested,
                    windowDays = days,
                    coverageDays = dayCoverageByType[requested to days] ?: 0,
                    config = config,
                    nowTs = nowTs
                )
            }
            val source = when {
                requested == CircadianDayType.ALL -> CircadianDayType.ALL
                requestedStableWindow != null -> requested
                else -> CircadianDayType.ALL
            }
            val stableWindow = if (source == requested && requestedStableWindow != null) {
                requestedStableWindow
            } else {
                config.stableWindowsDays.firstOrNull { days ->
                    hasEnoughCoverage(
                        dayType = CircadianDayType.ALL,
                        windowDays = days,
                        coverageDays = dayCoverageByType[CircadianDayType.ALL to days] ?: 0,
                        config = config,
                        nowTs = nowTs
                    )
                } ?: config.stableWindowsDays.last()
            }
            val fallback = source != requested
            val coverageDays = dayCoverageByType[source to stableWindow] ?: 0
            val slotRows = slotStats.filter { it.dayType == source && it.windowDays == stableWindow }
            val recencyCoverageDays = dayCoverageByType[source to config.recencyWindowDays] ?: 0
            val recencyPossible = possibleDaysForWindow(nowTs, source, config.recencyWindowDays).coerceAtLeast(1)
            val recencyCoverageRatio = recencyCoverageDays.toDouble() / recencyPossible.toDouble()
            val recencyWeight = (recencyCoverageRatio * config.recencyMaxWeight).coerceIn(0.0, config.recencyMaxWeight)
            CircadianPatternSnapshot(
                requestedDayType = requested,
                segmentSource = source,
                stableWindowDays = stableWindow,
                recencyWindowDays = config.recencyWindowDays,
                recencyWeight = recencyWeight,
                coverageDays = coverageDays,
                sampleCount = slotRows.sumOf { it.sampleCount },
                segmentFallback = fallback,
                fallbackReason = if (fallback) "segment_sparse_fallback_to_all" else null,
                confidence = slotRows.map { it.confidence }.averageOrNull()?.coerceIn(0.0, 1.0) ?: 0.0,
                qualityScore = slotRows.map { it.qualityScore }.averageOrNull()?.coerceIn(0.0, 1.0) ?: 0.0,
                updatedAt = nowTs
            )
        }
    }

    private fun derivePatternWindows(
        slotStats: List<CircadianSlotStat>,
        snapshots: List<CircadianPatternSnapshot>,
        config: CircadianPatternConfig
    ): List<PatternWindow> {
        return listOf(CircadianDayType.WEEKDAY, CircadianDayType.WEEKEND).flatMap { requested ->
            val snapshot = snapshots.firstOrNull { it.requestedDayType == requested } ?: return@flatMap emptyList()
            val rows = slotStats.filter {
                it.dayType == snapshot.segmentSource && it.windowDays == snapshot.stableWindowDays
            }
            (0 until 24).mapNotNull { hour ->
                val hourRows = rows.filter { it.slotIndex / 4 == hour }
                if (hourRows.isEmpty()) return@mapNotNull null
                val sampleCount = hourRows.sumOf { it.sampleCount }
                val activeDays = hourRows.maxOfOrNull { it.activeDays } ?: 0
                val lowRate = hourRows.map { it.pLow }.averageOrNull() ?: 0.0
                val highRate = hourRows.map { it.pHigh }.averageOrNull() ?: 0.0
                val enoughEvidence = sampleCount >= max(config.minSlotSamples * 2, 12)
                val recommendedTarget = recommendedTargetForRates(
                    baseTargetMmol = config.baseTargetMmol,
                    lowRate = lowRate,
                    highRate = highRate,
                    lowTrigger = 0.12,
                    highTrigger = 0.18
                )
                PatternWindow(
                    dayType = if (requested == CircadianDayType.WEEKEND) DayType.WEEKEND else DayType.WEEKDAY,
                    hour = hour,
                    sampleCount = sampleCount,
                    activeDays = activeDays,
                    lowRate = lowRate,
                    highRate = highRate,
                    recommendedTargetMmol = recommendedTarget,
                    isRiskWindow = enoughEvidence && (lowRate >= 0.12 || highRate >= 0.18)
                )
            }
        }
    }

    private fun buildObservedFiveMinuteSeries(
        glucoseHistory: List<GlucosePoint>,
        startTs: Long
    ): ObservedSeries {
        val buckets = glucoseHistory
            .asSequence()
            .filter { it.ts >= startTs }
            .sortedBy { it.ts }
            .groupBy { roundToFiveMinuteBucket(it.ts) }
            .mapNotNull { (bucketTs, points) ->
                val values = points
                    .map { it.valueMmol }
                    .filter { it.isFinite() }
                if (values.isEmpty()) {
                    null
                } else {
                    ObservedFiveMinutePoint(
                        ts = bucketTs,
                        valueMmol = percentile(values, 0.50),
                        deltaFromPrev = null
                    )
                }
            }
            .sortedBy { it.ts }

        val withDelta = buckets.mapIndexed { index, point ->
            val prev = buckets.getOrNull(index - 1)
            val delta = if (prev != null && point.ts - prev.ts == FIVE_MINUTES_MS) {
                point.valueMmol - prev.valueMmol
            } else {
                null
            }
            point.copy(deltaFromPrev = delta)
        }
        return ObservedSeries(points = withDelta)
    }

    private fun buildDayQualityMap(
        points: List<ObservedFiveMinutePoint>,
        nowTs: Long,
        config: CircadianPatternConfig
    ): Map<LocalDate, DayQualitySummary> {
        val grouped = points.groupBy { localDate(it.ts) }
        return grouped.mapValues { (_, dayPoints) ->
            val slots = dayPoints.map { slotIndex(it.ts) * 3 + (minuteOfSlot(it.ts) / 5) }.sorted()
            val coverage = (dayPoints.size.toDouble() / EXPECTED_5M_PER_DAY.toDouble()).coerceIn(0.0, 1.0)
            val maxGapBuckets = slots
                .zipWithNext()
                .maxOfOrNull { (a, b) -> (b - a).coerceAtLeast(1) - 1 }
                ?: 0
            val gapPenalty = when {
                maxGapBuckets <= 6 -> 1.0
                maxGapBuckets >= (config.maxGapMinutesForGoodWindow / 5) * 2 -> 0.0
                else -> 1.0 - (
                    (maxGapBuckets - 6).toDouble() /
                        ((config.maxGapMinutesForGoodWindow / 5) * 2 - 6).toDouble()
                    ).coerceIn(0.0, 1.0)
            }
            val score = (coverage * 0.75 + gapPenalty * 0.25).coerceIn(0.0, 1.0)
            DayQualitySummary(
                score = score,
                usable = score >= config.minDayCoverageQuality && coverage >= config.minDayObservedShare
            )
        }
    }

    private fun hasEnoughCoverage(
        dayType: CircadianDayType,
        windowDays: Int,
        coverageDays: Int,
        config: CircadianPatternConfig,
        nowTs: Long
    ): Boolean {
        val possibleDays = possibleDaysForWindow(nowTs = nowTs, dayType = dayType, windowDays = windowDays)
            .coerceAtLeast(1)
        val ratio = coverageDays.toDouble() / possibleDays.toDouble()
        val minDays = when (dayType) {
            CircadianDayType.WEEKDAY -> min(config.minActiveDaysWeekday, possibleDays)
            CircadianDayType.WEEKEND -> min(config.minActiveDaysWeekend, possibleDays)
            CircadianDayType.ALL -> min(config.minActiveDaysAll, possibleDays)
        }
        val minRatio = when (dayType) {
            CircadianDayType.WEEKDAY -> config.minCoverageRatioWeekday
            CircadianDayType.WEEKEND -> config.minCoverageRatioWeekend
            CircadianDayType.ALL -> config.minCoverageRatioAll
        }
        return coverageDays >= minDays && ratio >= minRatio
    }

    private fun possibleDaysForWindow(nowTs: Long, dayType: CircadianDayType, windowDays: Int): Int {
        val endDate = localDate(nowTs)
        val startDate = endDate.minusDays(windowDays.toLong() - 1L)
        var current = startDate
        var count = 0
        while (!current.isAfter(endDate)) {
            val matches = when (dayType) {
                CircadianDayType.ALL -> true
                CircadianDayType.WEEKDAY -> current.dayOfWeek.value !in setOf(6, 7)
                CircadianDayType.WEEKEND -> current.dayOfWeek.value in setOf(6, 7)
            }
            if (matches) count += 1
            current = current.plusDays(1)
        }
        return count
    }

    private fun currentDayType(ts: Long): CircadianDayType {
        return if (Instant.ofEpochMilli(ts).atZone(zoneId).dayOfWeek.value in setOf(6, 7)) {
            CircadianDayType.WEEKEND
        } else {
            CircadianDayType.WEEKDAY
        }
    }

    private fun localDate(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()

    private fun slotIndex(ts: Long): Int {
        val zoned = Instant.ofEpochMilli(ts).atZone(zoneId)
        val minuteOfDay = zoned.hour * 60 + zoned.minute
        return (minuteOfDay / 15).coerceIn(0, 95)
    }

    private fun minuteOfSlot(ts: Long): Int = Instant.ofEpochMilli(ts).atZone(zoneId).minute

    private fun matchesDayType(ts: Long, dayType: CircadianDayType): Boolean {
        return when (dayType) {
            CircadianDayType.ALL -> true
            CircadianDayType.WEEKDAY -> currentDayType(ts) == CircadianDayType.WEEKDAY
            CircadianDayType.WEEKEND -> currentDayType(ts) == CircadianDayType.WEEKEND
        }
    }

    private fun resolveReplayMetric(
        stable: CircadianReplaySlotStat?,
        recency: CircadianReplaySlotStat?,
        requestedDayType: CircadianDayType,
        correctionClamp: Double
    ): ReplayMetric {
        if (stable == null) {
            return ReplayMetric(
                bias = 0.0,
                sampleCount = 0,
                winRate = 0.0,
                maeImprovementMmol = 0.0,
                status = CircadianReplayBucketStatus.INSUFFICIENT
            )
        }
        val recencyCorrection = when {
            recency == null -> 0.0
            sameBiasDirection(stable.medianSignedErrorCircadian, recency.medianSignedErrorCircadian) ||
                abs(recency.medianSignedErrorCircadian) <= correctionClamp * 0.5 -> {
                (recency.medianSignedErrorCircadian - stable.medianSignedErrorCircadian)
                    .coerceIn(-correctionClamp, correctionClamp)
            }
            else -> 0.0
        }
        return ReplayMetric(
            bias = stable.medianSignedErrorCircadian + recencyCorrection,
            sampleCount = stable.sampleCount,
            winRate = stable.winRate,
            maeImprovementMmol = stable.maeImprovementMmol,
            status = replayBucketStatus(stable, requestedDayType)
        )
    }

    private fun resolveReplayBucket(
        rows: List<CircadianReplaySlotStat>,
        slotIndex: Int,
        horizonMinutes: Int,
        requestedDayType: CircadianDayType
    ): CircadianReplaySlotStat? {
        val exact = aggregateReplayNeighborhood(
            rows = rows,
            slotIndex = slotIndex,
            horizonMinutes = horizonMinutes,
            radius = 0,
            includeCenter = true
        )
        val exactStatus = exact?.let { replayBucketStatus(it, requestedDayType) }
        val neighborhood = aggregateReplayNeighborhood(
            rows = rows,
            slotIndex = slotIndex,
            horizonMinutes = horizonMinutes,
            radius = 2,
            includeCenter = false
        )
        val neighborhoodStatus = neighborhood?.let { replayBucketStatus(it, requestedDayType) }
        return when {
            exact == null -> neighborhood
            exactStatus == CircadianReplayBucketStatus.INSUFFICIENT -> neighborhood ?: exact
            neighborhood == null -> exact
            shouldPreferReplayNeighborhood(
                exact = exact,
                exactStatus = exactStatus,
                neighborhood = neighborhood,
                neighborhoodStatus = neighborhoodStatus
            ) -> neighborhood
            else -> exact
        }
    }

    private fun aggregateReplayNeighborhood(
        rows: List<CircadianReplaySlotStat>,
        slotIndex: Int,
        horizonMinutes: Int,
        radius: Int,
        includeCenter: Boolean
    ): CircadianReplaySlotStat? {
        val candidates = rows.filter {
            it.horizonMinutes == horizonMinutes &&
                circularSlotDistance(it.slotIndex, slotIndex) <= radius &&
                (includeCenter || it.slotIndex != slotIndex)
        }
        if (candidates.isEmpty()) return null
        val weighted = candidates.map { row ->
            val distance = circularSlotDistance(row.slotIndex, slotIndex)
            val distanceWeight = when (distance) {
                0 -> 1.0
                1 -> 0.65
                2 -> 0.40
                else -> 0.0
            }
            row to (distanceWeight * row.sampleCount.toDouble()).coerceAtLeast(0.0)
        }.filter { it.second > 0.0 }
        if (weighted.isEmpty()) return null
        val totalWeight = weighted.sumOf { it.second }.coerceAtLeast(1.0)
        val template = weighted.maxByOrNull { it.second }!!.first
        fun weightedAvg(selector: (CircadianReplaySlotStat) -> Double): Double {
            return weighted.sumOf { selector(it.first) * it.second } / totalWeight
        }
        return CircadianReplaySlotStat(
            dayType = template.dayType,
            windowDays = template.windowDays,
            slotIndex = slotIndex,
            horizonMinutes = template.horizonMinutes,
            sampleCount = weighted.sumOf { it.first.sampleCount },
            coverageDays = weighted.maxOf { it.first.coverageDays },
            maeBaseline = weightedAvg { it.maeBaseline },
            maeCircadian = weightedAvg { it.maeCircadian },
            maeImprovementMmol = weightedAvg { it.maeImprovementMmol },
            medianSignedErrorBaseline = weightedAvg { it.medianSignedErrorBaseline },
            medianSignedErrorCircadian = weightedAvg { it.medianSignedErrorCircadian },
            winRate = weightedAvg { it.winRate }.coerceIn(0.0, 1.0),
            qualityScore = weightedAvg { it.qualityScore }.coerceIn(0.0, 1.0),
            updatedAt = weighted.maxOf { it.first.updatedAt }
        )
    }

    private fun replayBucketStatus(
        stable: CircadianReplaySlotStat,
        requestedDayType: CircadianDayType
    ): CircadianReplayBucketStatus {
        val minCoverageDays = when (requestedDayType) {
            CircadianDayType.WEEKDAY -> 3
            CircadianDayType.WEEKEND -> 2
            CircadianDayType.ALL -> 4
        }
        return when {
            stable.sampleCount < minimumReplaySampleCount(requestedDayType) || stable.coverageDays < minCoverageDays -> CircadianReplayBucketStatus.INSUFFICIENT
            stable.maeImprovementMmol <= -0.05 && stable.winRate >= 0.55 -> CircadianReplayBucketStatus.HELPFUL
            stable.maeImprovementMmol >= 0.05 -> CircadianReplayBucketStatus.HARMFUL
            stable.maeImprovementMmol > 0.02 && stable.winRate < 0.40 -> CircadianReplayBucketStatus.HARMFUL
            else -> CircadianReplayBucketStatus.NEUTRAL
        }
    }

    private fun shouldPreferReplayNeighborhood(
        exact: CircadianReplaySlotStat,
        exactStatus: CircadianReplayBucketStatus?,
        neighborhood: CircadianReplaySlotStat,
        neighborhoodStatus: CircadianReplayBucketStatus?
    ): Boolean {
        if (exactStatus == null || neighborhoodStatus == null) return false
        val exactRank = replayBucketRank(exactStatus)
        val neighborhoodRank = replayBucketRank(neighborhoodStatus)
        return when {
            neighborhoodRank <= exactRank -> false
            exactStatus == CircadianReplayBucketStatus.HARMFUL -> true
            exactStatus == CircadianReplayBucketStatus.NEUTRAL &&
                neighborhoodStatus == CircadianReplayBucketStatus.HELPFUL &&
                neighborhood.sampleCount >= exact.sampleCount &&
                neighborhood.coverageDays >= exact.coverageDays -> true
            else -> false
        }
    }

    private fun replayBucketRank(status: CircadianReplayBucketStatus): Int {
        return when (status) {
            CircadianReplayBucketStatus.HELPFUL -> 3
            CircadianReplayBucketStatus.NEUTRAL -> 2
            CircadianReplayBucketStatus.HARMFUL -> 1
            CircadianReplayBucketStatus.INSUFFICIENT -> 0
        }
    }


    private fun minimumReplaySampleCount(dayType: CircadianDayType): Int {
        return when (dayType) {
            CircadianDayType.WEEKDAY -> 5
            CircadianDayType.WEEKEND -> 4
            CircadianDayType.ALL -> 6
        }
    }

    private fun transitionQuality(
        stat: CircadianTransitionStat?,
        defaultQuality: Double,
        spreadReference: Double
    ): Double {
        if (stat == null) return defaultQuality.coerceIn(0.25, 0.80)
        val spread = (stat.deltaP75 - stat.deltaP25).coerceAtLeast(0.0)
        val spreadQuality = (1.0 - spread / spreadReference).coerceIn(0.25, 1.0)
        return (stat.confidence.coerceIn(0.0, 1.0) * 0.65 + spreadQuality * 0.35).coerceIn(0.0, 1.0)
    }

    private fun slotStabilityScore(slot: CircadianSlotStat): Double {
        val iqrPenalty = ((slot.p75 - slot.p25).coerceAtLeast(0.0) / 4.5).coerceIn(0.0, 0.35)
        val dynamicPenalty = (
            max(slot.fastRiseRate, slot.fastDropRate).coerceIn(0.0, 1.0) * 0.35 +
                ((slot.meanCob ?: 0.0) / 20.0).coerceIn(0.0, 0.20) * 0.20 +
                ((slot.meanUam ?: 0.0) / 1.5).coerceIn(0.0, 0.20) * 0.20
            ).coerceIn(0.0, 0.45)
        return (1.0 - iqrPenalty - dynamicPenalty).coerceIn(0.35, 1.0)
    }

    private fun medianReversionBias(
        currentGlucoseMmol: Double,
        slot: CircadianSlotStat,
        horizonMinutes: Int,
        horizonQuality: Double,
        stabilityScore: Double,
        replayStatus: CircadianReplayBucketStatus,
        acuteAttenuation: Double,
        stale: Boolean
    ): Double {
        if (stale || horizonMinutes < 30) return 0.0
        if (currentGlucoseMmol in slot.p25..slot.p75) return 0.0
        val replayMultiplier = when (replayStatus) {
            CircadianReplayBucketStatus.HELPFUL -> 1.0
            CircadianReplayBucketStatus.NEUTRAL -> 0.75
            CircadianReplayBucketStatus.INSUFFICIENT -> 0.55
            CircadianReplayBucketStatus.HARMFUL -> 0.0
        }
        if (replayMultiplier <= 0.0) return 0.0
        val bandEdge = if (currentGlucoseMmol > slot.p75) slot.p75 else slot.p25
        val bandExcess = abs(currentGlucoseMmol - bandEdge)
        if (bandExcess <= 1e-6) return 0.0
        val spread = (slot.p90 - slot.p10).coerceAtLeast((slot.p75 - slot.p25).coerceAtLeast(0.35))
        val deviationStrength = ((bandExcess / spread) + 0.20).coerceIn(0.20, 1.0)
        val pullTowardMedian = slot.medianBg - currentGlucoseMmol
        val baseFactor = if (horizonMinutes < 60) 0.18 else 0.30
        val raw = pullTowardMedian *
            baseFactor *
            deviationStrength *
            horizonQuality.coerceIn(0.0, 1.0) *
            stabilityScore.coerceIn(0.0, 1.0) *
            replayMultiplier *
            acuteAttenuation.coerceIn(0.0, 1.0)
        return if (horizonMinutes < 60) {
            raw.coerceIn(-0.18, 0.18)
        } else {
            raw.coerceIn(-0.32, 0.32)
        }
    }

    private fun sameBiasDirection(a: Double, b: Double): Boolean {
        return a == 0.0 || b == 0.0 || (a > 0.0 && b > 0.0) || (a < 0.0 && b < 0.0)
    }

    private fun circularSlotDistance(a: Int, b: Int): Int {
        val diff = abs(a - b)
        return min(diff, 96 - diff)
    }

    private fun blendBounded(
        stable: Double,
        recency: Double?,
        weight: Double,
        clampAbs: Double
    ): Double {
        if (recency == null || !recency.isFinite() || weight <= 0.0) return stable
        val correction = (recency - stable).coerceIn(-clampAbs, clampAbs)
        return stable + correction * weight.coerceIn(0.0, 1.0)
    }

    private fun percentile(values: List<Double>, quantile: Double): Double {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted.first()
        val position = quantile.coerceIn(0.0, 1.0) * (sorted.lastIndex)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val ratio = position - lower.toDouble()
        return sorted[lower] + (sorted[upper] - sorted[lower]) * ratio
    }

    private fun recommendedTargetForRates(
        baseTargetMmol: Double,
        lowRate: Double,
        highRate: Double,
        lowTrigger: Double,
        highTrigger: Double
    ): Double {
        val adjusted = when {
            lowRate >= max(lowTrigger + 0.16, 0.30) -> baseTargetMmol + 0.6
            lowRate >= lowTrigger + 0.08 -> baseTargetMmol + 0.4
            lowRate >= lowTrigger -> baseTargetMmol + 0.25
            highRate >= max(highTrigger + 0.18, 0.35) && lowRate < lowTrigger * 0.5 -> baseTargetMmol - 0.45
            highRate >= highTrigger + 0.10 && lowRate < lowTrigger * 0.5 -> baseTargetMmol - 0.30
            highRate >= highTrigger && lowRate < lowTrigger * 0.6 -> baseTargetMmol - 0.20
            else -> baseTargetMmol
        }
        return roundToStep(adjusted.coerceIn(4.0, 10.0), 0.05)
    }

    private fun roundToStep(value: Double, step: Double): Double {
        return floor(value / step + 0.5) * step
    }

    private fun List<TelemetryMetricPoint>.averageFor(type: TelemetryMetricType): Double? {
        val values = filter { it.type == type }.map { it.value }.filter { it.isFinite() }
        return if (values.isEmpty()) null else values.average().coerceAtLeast(0.0)
    }

    private fun Iterable<Double>.averageOrNull(): Double? {
        val values = filter { it.isFinite() }.toList()
        return if (values.isEmpty()) null else values.average()
    }

    private fun toTelemetryMetric(signal: TelemetrySignal): TelemetryMetricPoint? {
        val value = signal.valueDouble?.takeIf { it.isFinite() } ?: return null
        val normalized = signal.key
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        val type = when {
            normalized in setOf("cob_grams", "cob_effective_grams", "cob_external_adjusted_grams") -> TelemetryMetricType.COB
            normalized in setOf("iob_units", "iob_effective_units", "iob_real_units") -> TelemetryMetricType.IOB
            normalized in setOf("uam_runtime_carbs_grams", "uam_inferred_carbs_grams", "uam_calculated_carbs_grams") -> TelemetryMetricType.UAM
            normalized == "activity_ratio" -> TelemetryMetricType.ACTIVITY
            else -> return null
        }
        return TelemetryMetricPoint(ts = signal.ts, type = type, value = value)
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val MINUTE_MS = 60_000L
        private const val FIVE_MINUTES_MS = 5 * MINUTE_MS
        private const val EXPECTED_5M_PER_DAY = 288
        private const val MIN_GLUCOSE_MMOL = 2.2
        private const val MAX_GLUCOSE_MMOL = 22.0
        private val HORIZONS_MINUTES = listOf(15, 30, 60)

        fun roundToFiveMinuteBucket(ts: Long): Long {
            return ((ts + FIVE_MINUTES_MS / 2L) / FIVE_MINUTES_MS) * FIVE_MINUTES_MS
        }

        fun slotStartMinutes(slotIndex: Int): Int = slotIndex.coerceIn(0, 95) * 15
    }
}

private data class ReplayMetric(
    val bias: Double,
    val sampleCount: Int,
    val winRate: Double,
    val maeImprovementMmol: Double,
    val status: CircadianReplayBucketStatus
)

private data class ObservedSeries(
    val points: List<ObservedFiveMinutePoint>
)

private data class ObservedFiveMinutePoint(
    val ts: Long,
    val valueMmol: Double,
    val deltaFromPrev: Double?
)

private data class DayQualitySummary(
    val score: Double,
    val usable: Boolean
)

private enum class TelemetryMetricType {
    COB,
    IOB,
    UAM,
    ACTIVITY
}

private data class TelemetryMetricPoint(
    val ts: Long,
    val type: TelemetryMetricType,
    val value: Double
)
