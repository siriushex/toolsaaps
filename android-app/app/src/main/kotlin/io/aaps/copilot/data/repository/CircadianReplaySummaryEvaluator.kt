package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.entity.CircadianPatternSnapshotEntity
import io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianSlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianTransitionStatEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.CircadianForecastPrior
import io.aaps.copilot.domain.model.CircadianPatternSnapshot
import io.aaps.copilot.domain.model.CircadianReplayBucketStatus
import io.aaps.copilot.domain.model.CircadianReplaySlotStat
import io.aaps.copilot.domain.model.CircadianSlotStat
import io.aaps.copilot.domain.model.CircadianTransitionStat
import io.aaps.copilot.domain.predict.CircadianPatternConfig
import io.aaps.copilot.domain.predict.CircadianPatternEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CircadianReplayMetricSummary(
    val horizonMinutes: Int,
    val sampleCount: Int,
    val maeBaseline: Double,
    val maeCircadian: Double,
    val deltaMmol: Double,
    val deltaPct: Double,
    val winRate: Double,
    val qualityScore: Double,
    val bucketStatus: CircadianReplayBucketStatus
)

data class CircadianReplayBucketSummary(
    val bucket: String,
    val metrics: List<CircadianReplayMetricSummary>
)

data class CircadianReplayWindowSummary(
    val days: Int,
    val appliedRows: Int,
    val appliedPct: Double,
    val meanShift30: Double?,
    val meanShift60: Double?,
    val buckets: List<CircadianReplayBucketSummary>
)

data class CircadianReplaySummary(
    val generatedAtTs: Long,
    val windows: List<CircadianReplayWindowSummary>
)

internal class CircadianReplaySummaryEvaluator(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val patternEngine: CircadianPatternEngine = CircadianPatternEngine(zoneId)
) {

    fun fitSlotStats(
        forecasts: List<ForecastEntity>,
        glucose: List<GlucoseSampleEntity>,
        telemetry: List<TelemetrySampleEntity>,
        snapshots: List<CircadianPatternSnapshotEntity>,
        slotStats: List<CircadianSlotStatEntity>,
        transitionStats: List<CircadianTransitionStatEntity>,
        settings: AppSettings,
        nowTs: Long
    ): List<CircadianReplaySlotStat> {
        if (forecasts.isEmpty() || glucose.isEmpty() || snapshots.isEmpty()) return emptyList()
        val config = replayConfig(settings)
        val rows = buildEvaluationRows(
            forecasts = forecasts,
            glucose = glucose,
            telemetry = telemetry,
            snapshots = snapshots,
            slotStats = slotStats,
            transitionStats = transitionStats,
            replaySlotStats = emptyList(),
            settings = settings,
            config = config,
            nowTs = nowTs,
            sinceTs = nowTs - (config.stableWindowsDays.maxOrNull() ?: 14) * DAY_MS,
            bootstrapReplayFit = true
        )
        if (rows.isEmpty()) return emptyList()

        val fitWindows = (config.stableWindowsDays + config.recencyWindowDays).distinct().sortedDescending()
        return fitWindows
            .flatMap { windowDays ->
                val sinceTs = nowTs - windowDays * DAY_MS
                requestedDayTypes(settings.circadianUseWeekendSplit).flatMap { dayType ->
                    rows.asSequence()
                        .filter { it.timestamp >= sinceTs }
                        .filter { it.lowAcute }
                        .filter { it.applied }
                        .filter { it.requestedDayType == dayType || dayType == CircadianDayType.ALL }
                        .groupBy { ReplaySlotKey(dayType, windowDays, it.slotIndex, it.horizonMinutes) }
                        .mapNotNull { (key, slotRows) ->
                            val coverageDays = slotRows.map { localDate(it.timestamp) }.distinct().size
                            val minCoverageDays = minimumCoverageDays(key.dayType)
                            if (coverageDays <= 0) return@mapNotNull null
                            val baselineMae = slotRows.sumOf { it.baselineAbsError } / slotRows.size.toDouble()
                            val circadianMae = slotRows.sumOf { it.circadianAbsError } / slotRows.size.toDouble()
                            val improvement = circadianMae - baselineMae
                            val signedBaseline = percentile(slotRows.map { it.baselineSignedError }, 0.50)
                            val signedCircadian = percentile(slotRows.map { it.circadianSignedError }, 0.50)
                            val winRate = slotRows.count { it.circadianAbsError + 1e-9 < it.baselineAbsError }
                                .toDouble() / slotRows.size.toDouble()
                            val qualityScore = replayQualityScore(
                                sampleCount = slotRows.size,
                                coverageDays = coverageDays,
                                minCoverageDays = minCoverageDays
                            )
                            CircadianReplaySlotStat(
                                dayType = key.dayType,
                                windowDays = key.windowDays,
                                slotIndex = key.slotIndex,
                                horizonMinutes = key.horizonMinutes,
                                sampleCount = slotRows.size,
                                coverageDays = coverageDays,
                                maeBaseline = baselineMae,
                                maeCircadian = circadianMae,
                                maeImprovementMmol = improvement,
                                medianSignedErrorBaseline = signedBaseline,
                                medianSignedErrorCircadian = signedCircadian,
                                winRate = winRate,
                                qualityScore = qualityScore,
                                updatedAt = nowTs
                            )
                        }
                }
            }
            .sortedWith(
                compareBy<CircadianReplaySlotStat> { it.dayType.name }
                    .thenByDescending { it.windowDays }
                    .thenBy { it.slotIndex }
                    .thenBy { it.horizonMinutes }
            )
    }

    fun evaluate(
        forecasts: List<ForecastEntity>,
        glucose: List<GlucoseSampleEntity>,
        telemetry: List<TelemetrySampleEntity>,
        snapshots: List<CircadianPatternSnapshotEntity>,
        slotStats: List<CircadianSlotStatEntity>,
        transitionStats: List<CircadianTransitionStatEntity>,
        replaySlotStats: List<CircadianReplaySlotStatEntity>,
        settings: AppSettings,
        nowTs: Long,
        windowsDays: List<Int> = listOf(1, 7)
    ): CircadianReplaySummary {
        if (forecasts.isEmpty() || glucose.isEmpty() || snapshots.isEmpty()) {
            return CircadianReplaySummary(
                generatedAtTs = nowTs,
                windows = emptyList()
            )
        }
        val config = replayConfig(settings)
        val maxDays = max(
            windowsDays.maxOrNull() ?: 1,
            config.stableWindowsDays.maxOrNull() ?: 14
        )
        val replayStats = replaySlotStats.map { it.toReplayDomain() }
        val rows = buildEvaluationRows(
            forecasts = forecasts,
            glucose = glucose,
            telemetry = telemetry,
            snapshots = snapshots,
            slotStats = slotStats,
            transitionStats = transitionStats,
            replaySlotStats = replayStats,
            settings = settings,
            config = config,
            nowTs = nowTs,
            sinceTs = nowTs - maxDays * DAY_MS
        )
        if (rows.isEmpty()) {
            return CircadianReplaySummary(
                generatedAtTs = nowTs,
                windows = emptyList()
            )
        }
        val windows = windowsDays
            .distinct()
            .sorted()
            .mapNotNull { days ->
                evaluateWindow(
                    days = days,
                    rows = rows.filter { it.timestamp >= nowTs - days * DAY_MS },
                    replayStats = replayStats
                )
            }
        return CircadianReplaySummary(
            generatedAtTs = nowTs,
            windows = windows
        )
    }

    private fun evaluateWindow(
        days: Int,
        rows: List<ReplayEvaluationRow>,
        replayStats: List<CircadianReplaySlotStat>
    ): CircadianReplayWindowSummary? {
        if (rows.isEmpty()) return null
        val buckets = linkedMapOf(
            BUCKET_ALL to mutableMapOf<Int, MutableList<ReplayEvaluationRow>>(),
            BUCKET_LOW_ACUTE to mutableMapOf<Int, MutableList<ReplayEvaluationRow>>(),
            BUCKET_WEEKDAY to mutableMapOf<Int, MutableList<ReplayEvaluationRow>>(),
            BUCKET_WEEKEND to mutableMapOf<Int, MutableList<ReplayEvaluationRow>>()
        )
        val shiftsByHorizon = mutableMapOf<Int, MutableList<Double>>()
        var appliedRows = 0

        rows.forEach { row ->
            if (row.applied) {
                appliedRows += 1
                shiftsByHorizon.getOrPut(row.horizonMinutes) { mutableListOf() }.add(row.shiftMmol)
            }
            val weekdayBucket = if (row.requestedDayType == CircadianDayType.WEEKEND) BUCKET_WEEKEND else BUCKET_WEEKDAY
            appendBucket(buckets, BUCKET_ALL, row.horizonMinutes, row)
            appendBucket(buckets, weekdayBucket, row.horizonMinutes, row)
            if (row.lowAcute) {
                appendBucket(buckets, BUCKET_LOW_ACUTE, row.horizonMinutes, row)
            }
        }

        val summaryBuckets = buckets.mapNotNull { (bucket, grouped) ->
            val metrics = EVAL_HORIZONS.mapNotNull { horizon ->
                val bucketRows = grouped[horizon].orEmpty()
                if (bucketRows.isEmpty()) return@mapNotNull null
                val baselineMae = bucketRows.sumOf { it.baselineAbsError } / bucketRows.size.toDouble()
                val circadianMae = bucketRows.sumOf { it.circadianAbsError } / bucketRows.size.toDouble()
                val deltaMmol = circadianMae - baselineMae
                val deltaPct = if (baselineMae > 1e-6) deltaMmol / baselineMae * 100.0 else 0.0
                val winRate = bucketRows.count { it.circadianAbsError + 1e-9 < it.baselineAbsError }
                    .toDouble() / bucketRows.size.toDouble()
                val qualityScore = replayQualityScore(
                    sampleCount = bucketRows.size,
                    coverageDays = bucketRows.map { localDate(it.timestamp) }.distinct().size,
                    minCoverageDays = when (bucket) {
                        BUCKET_WEEKEND -> 2
                        BUCKET_WEEKDAY -> 3
                        else -> 4
                    }
                )
                val status = replayBucketStatus(
                    dayType = when (bucket) {
                        BUCKET_WEEKEND -> CircadianDayType.WEEKEND
                        BUCKET_WEEKDAY -> CircadianDayType.WEEKDAY
                        else -> CircadianDayType.ALL
                    },
                    sampleCount = bucketRows.size,
                    coverageDays = bucketRows.map { localDate(it.timestamp) }.distinct().size,
                    requiredCoverageDays = when (bucket) {
                        BUCKET_WEEKEND -> 2
                        BUCKET_WEEKDAY -> 3
                        else -> 4
                    },
                    maeImprovementMmol = deltaMmol,
                    winRate = winRate
                )
                val replayHint = replayStats.firstOrNull {
                    it.windowDays == selectedReplayWindowDays(bucketRows) &&
                        it.horizonMinutes == horizon &&
                        it.dayType == when (bucket) {
                            BUCKET_WEEKDAY -> CircadianDayType.WEEKDAY
                            BUCKET_WEEKEND -> CircadianDayType.WEEKEND
                            else -> CircadianDayType.ALL
                        }
                }
                CircadianReplayMetricSummary(
                    horizonMinutes = horizon,
                    sampleCount = bucketRows.size,
                    maeBaseline = baselineMae,
                    maeCircadian = circadianMae,
                    deltaMmol = deltaMmol,
                    deltaPct = deltaPct,
                    winRate = replayHint?.winRate ?: winRate,
                    qualityScore = replayHint?.qualityScore ?: qualityScore,
                    bucketStatus = replayHint?.let {
                        replayBucketStatus(
                            dayType = it.dayType,
                            sampleCount = it.sampleCount,
                            coverageDays = it.coverageDays,
                            requiredCoverageDays = minimumCoverageDays(it.dayType),
                            maeImprovementMmol = it.maeImprovementMmol,
                            winRate = it.winRate
                        )
                    } ?: status
                )
            }
            if (metrics.isEmpty()) null else CircadianReplayBucketSummary(bucket = bucket, metrics = metrics)
        }

        if (summaryBuckets.isEmpty()) return null
        return CircadianReplayWindowSummary(
            days = days,
            appliedRows = appliedRows,
            appliedPct = appliedRows * 100.0 / rows.size.toDouble(),
            meanShift30 = shiftsByHorizon[30]?.averageOrNull(),
            meanShift60 = shiftsByHorizon[60]?.averageOrNull(),
            buckets = summaryBuckets
        )
    }

    private fun buildEvaluationRows(
        forecasts: List<ForecastEntity>,
        glucose: List<GlucoseSampleEntity>,
        telemetry: List<TelemetrySampleEntity>,
        snapshots: List<CircadianPatternSnapshotEntity>,
        slotStats: List<CircadianSlotStatEntity>,
        transitionStats: List<CircadianTransitionStatEntity>,
        replaySlotStats: List<CircadianReplaySlotStat> = emptyList(),
        settings: AppSettings,
        config: CircadianPatternConfig,
        nowTs: Long,
        sinceTs: Long,
        bootstrapReplayFit: Boolean = false
    ): List<ReplayEvaluationRow> {
        val glucoseSeries = ReplayGlucoseSeries(glucose)
        val telemetryLookup = ReplayTelemetryLookup(telemetry)
        val domainSnapshots = snapshots.map { it.toReplayDomain() }
        val domainSlotStats = slotStats.map { it.toReplayDomain() }
        val domainTransitionStats = transitionStats.map { it.toReplayDomain() }

        return forecasts
            .asSequence()
            .filter { it.timestamp >= sinceTs }
            .filter { it.timestamp <= nowTs }
            .filter { it.horizonMinutes in EVAL_HORIZONS }
            .groupBy { roundToFiveMinuteBucket(it.timestamp) to it.horizonMinutes }
            .mapNotNull { (_, rows) -> rows.maxWithOrNull(compareBy<ForecastEntity> { it.timestamp }.thenBy { it.id }) }
            .sortedBy { it.timestamp }
            .mapNotNull { row ->
                val currentGlucose = glucoseSeries.actualAt(row.timestamp, maxGapMinutes = 20) ?: return@mapNotNull null
                val actual = glucoseSeries.actualAt(
                    targetTs = roundToFiveMinuteBucket(row.timestamp + row.horizonMinutes * MINUTE_MS),
                    maxGapMinutes = 20
                ) ?: return@mapNotNull null
                val telemetrySnapshot = telemetryLookup.snapshotAt(row.timestamp)
                val prior = patternEngine.resolvePrior(
                    nowTs = row.timestamp,
                    currentGlucoseMmol = currentGlucose,
                    telemetry = telemetrySnapshot,
                    snapshots = domainSnapshots,
                    slotStats = domainSlotStats,
                    transitionStats = domainTransitionStats,
                    replayStats = replaySlotStats,
                    config = config
                ) ?: return@mapNotNull null
                val baselineValue = reconstructBaselineValue(
                    row = row,
                    prior = prior,
                    currentGlucose = currentGlucose,
                    settings = settings,
                    bootstrapReplayFit = bootstrapReplayFit
                )
                val circadianValue = applyPriorValue(
                    baselineValue = baselineValue,
                    horizonMinutes = row.horizonMinutes,
                    modelVersion = row.modelVersion,
                    prior = prior,
                    currentGlucose = currentGlucose,
                    settings = settings,
                    bootstrapReplayFit = bootstrapReplayFit
                )
                val baselineSignedError = actual - baselineValue
                val circadianSignedError = actual - circadianValue
                val staleBlocked = prior.staleBlocked
                val lowAcute = isReplayLowAcute(
                    telemetry = telemetrySnapshot,
                    staleBlocked = staleBlocked
                )
                ReplayEvaluationRow(
                    timestamp = row.timestamp,
                    slotIndex = prior.slotIndex,
                    requestedDayType = prior.requestedDayType,
                    stableWindowDays = domainSnapshots
                        .firstOrNull { it.requestedDayType == prior.requestedDayType }
                        ?.stableWindowDays
                        ?: (config.stableWindowsDays.maxOrNull() ?: 14),
                    horizonMinutes = row.horizonMinutes,
                    baselineValue = baselineValue,
                    circadianValue = circadianValue,
                    actualValue = actual,
                    baselineSignedError = baselineSignedError,
                    circadianSignedError = circadianSignedError,
                    baselineAbsError = abs(baselineSignedError),
                    circadianAbsError = abs(circadianSignedError),
                    lowAcute = lowAcute,
                    staleBlocked = staleBlocked,
                    applied = !staleBlocked && abs(circadianValue - baselineValue) > 1e-6
                )
            }
            .toList()
    }

    private fun reconstructBaselineValue(
        row: ForecastEntity,
        prior: CircadianForecastPrior,
        currentGlucose: Double,
        settings: AppSettings,
        bootstrapReplayFit: Boolean = false
    ): Double {
        if (!row.modelVersion.contains("|circadian_v1") && !row.modelVersion.contains("|circadian_v2")) {
            return row.valueMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        }
        val replayMultiplier = when {
            !row.modelVersion.contains("|circadian_v2") -> 1.0
            bootstrapReplayFit -> 1.0
            else -> replayMultiplierForPrior(prior, row.horizonMinutes)
        }
        val weight = weightForHorizon(
            horizonMinutes = row.horizonMinutes,
            confidence = prior.confidence,
            qualityScore = prior.qualityScore,
            stabilityScore = prior.stabilityScore,
            horizonQuality = horizonQualityForHorizon(prior, row.horizonMinutes),
            acuteAttenuation = prior.acuteAttenuation,
            replayMultiplier = replayMultiplier,
            weight30 = settings.circadianForecastWeight30,
            weight60 = settings.circadianForecastWeight60
        )
        if (weight <= 1e-6 || weight >= 0.999) {
            return row.valueMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        }
        val priorTarget = (currentGlucose + priorDeltaForHorizon(prior, row.horizonMinutes))
            .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val replayBias = if (row.modelVersion.contains("|circadian_v2")) {
            replayBiasForHorizon(prior, row.horizonMinutes)
        } else {
            0.0
        }
        return ((row.valueMmol - replayBias - priorTarget * weight) / (1.0 - weight))
            .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
    }

    private fun applyPriorValue(
        baselineValue: Double,
        horizonMinutes: Int,
        modelVersion: String,
        prior: CircadianForecastPrior,
        currentGlucose: Double,
        settings: AppSettings,
        bootstrapReplayFit: Boolean = false
    ): Double {
        if (prior.staleBlocked) return baselineValue
        val replayMultiplier = if (bootstrapReplayFit && horizonMinutes >= 30) 1.0 else replayMultiplierForPrior(prior, horizonMinutes)
        val weight = weightForHorizon(
            horizonMinutes = horizonMinutes,
            confidence = prior.confidence,
            qualityScore = prior.qualityScore,
            stabilityScore = prior.stabilityScore,
            horizonQuality = horizonQualityForHorizon(prior, horizonMinutes),
            acuteAttenuation = prior.acuteAttenuation,
            replayMultiplier = replayMultiplier,
            weight30 = settings.circadianForecastWeight30,
            weight60 = settings.circadianForecastWeight60
        )
        if (weight <= 1e-6) return baselineValue
        val priorTarget = (currentGlucose + priorDeltaForHorizon(prior, horizonMinutes))
            .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val replayBias = if (modelVersion.contains("|circadian_v2")) {
            replayBiasForHorizon(prior, horizonMinutes)
        } else {
            0.0
        }
        return (baselineValue * (1.0 - weight) + priorTarget * weight + replayBias)
            .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
    }

    private fun isReplayLowAcute(
        telemetry: Map<String, Double?>,
        staleBlocked: Boolean
    ): Boolean {
        if (staleBlocked) return false
        val delta5Value = telemetry["sensor_quality_delta5_mmol"]
            ?: telemetry["delta5_mmol"]
            ?: telemetry["glucose_delta5_mmol"]
        val cobValue = telemetry["cob_effective_grams"]
            ?: telemetry["cob_external_adjusted_grams"]
            ?: telemetry["cob_grams"]
        val iobValue = telemetry["iob_effective_units"]
            ?: telemetry["iob_real_units"]
            ?: telemetry["iob_units"]
        val uamValue = telemetry["uam_value"]
        val hasDynamics = delta5Value != null
        val hasInputs = cobValue != null || iobValue != null || uamValue != null
        if (!hasDynamics || !hasInputs) return false
        val delta5 = delta5Value ?: return false
        val cob = cobValue ?: 0.0
        val iob = iobValue ?: 0.0
        val uam = uamValue ?: 0.0
        val blocked = (telemetry["sensor_quality_blocked"] ?: 0.0) >= 0.5
        val suspectFalseLow = (telemetry["sensor_quality_suspect_false_low"] ?: 0.0) >= 0.5
        return !blocked &&
            !suspectFalseLow &&
            abs(delta5) <= 0.25 &&
            cob < 10.0 &&
            iob < 2.5 &&
            uam < 0.5
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val MINUTE_MS = 60_000L
        private const val FIVE_MINUTES_MS = 5L * MINUTE_MS
        private const val MIN_GLUCOSE_MMOL = 2.2
        private const val MAX_GLUCOSE_MMOL = 22.0
        private const val WEIGHT_5 = 0.10
        private const val BUCKET_ALL = "ALL"
        private const val BUCKET_LOW_ACUTE = "LOW_ACUTE"
        private const val BUCKET_WEEKDAY = "WEEKDAY"
        private const val BUCKET_WEEKEND = "WEEKEND"
        private val EVAL_HORIZONS = listOf(30, 60)
        private val TELEMETRY_KEYS = listOf(
            "sensor_quality_delta5_mmol",
            "delta5_mmol",
            "glucose_delta5_mmol",
            "cob_grams",
            "cob_effective_grams",
            "cob_external_adjusted_grams",
            "uam_value",
            "iob_units",
            "iob_effective_units",
            "iob_real_units",
            "sensor_quality_blocked",
            "sensor_quality_suspect_false_low"
        )

        internal fun replayBucketStatus(
            dayType: CircadianDayType,
            sampleCount: Int,
            coverageDays: Int,
            requiredCoverageDays: Int,
            maeImprovementMmol: Double,
            winRate: Double
        ): CircadianReplayBucketStatus {
            if (sampleCount < minimumReplaySampleCount(dayType) || coverageDays < requiredCoverageDays) {
                return CircadianReplayBucketStatus.INSUFFICIENT
            }
            return when {
                maeImprovementMmol <= -0.05 && winRate >= 0.55 -> CircadianReplayBucketStatus.HELPFUL
                maeImprovementMmol >= 0.05 -> CircadianReplayBucketStatus.HARMFUL
                maeImprovementMmol > 0.02 && winRate < 0.40 -> CircadianReplayBucketStatus.HARMFUL
                else -> CircadianReplayBucketStatus.NEUTRAL
            }
        }

        internal fun replayQualityScore(
            sampleCount: Int,
            coverageDays: Int,
            minCoverageDays: Int
        ): Double {
            val sampleFactor = (sampleCount.toDouble() / 16.0).coerceIn(0.0, 1.0)
            val coverageFactor = (coverageDays.toDouble() / minCoverageDays.toDouble()).coerceIn(0.0, 1.0)
            return (sampleFactor * 0.60 + coverageFactor * 0.40).coerceIn(0.0, 1.0)
        }

        internal fun replayMultiplier(
            dayType: CircadianDayType,
            sampleCount: Int,
            coverageDays: Int,
            requiredCoverageDays: Int,
            maeImprovementMmol: Double,
            winRate: Double
        ): Double {
            return when (replayBucketStatus(dayType, sampleCount, coverageDays, requiredCoverageDays, maeImprovementMmol, winRate)) {
                CircadianReplayBucketStatus.HELPFUL -> 1.0
                CircadianReplayBucketStatus.NEUTRAL -> 0.70
                CircadianReplayBucketStatus.HARMFUL -> 0.35
                CircadianReplayBucketStatus.INSUFFICIENT -> 0.0
            }
        }

        internal fun replayHelpfulBoost(
            horizonMinutes: Int,
            sampleCount: Int,
            maeImprovementMmol: Double,
            winRate: Double,
            acuteAttenuation: Double
        ): Double {
            if (acuteAttenuation < 0.70) return 1.0
            if (sampleCount < 8) return 1.0
            return when {
                horizonMinutes < 60 && winRate >= 0.60 && maeImprovementMmol <= -0.10 -> 1.15
                horizonMinutes < 60 && winRate >= 0.55 && maeImprovementMmol <= -0.05 -> 1.08
                horizonMinutes >= 60 && winRate >= 0.60 && maeImprovementMmol <= -0.15 -> 1.20
                horizonMinutes >= 60 && winRate >= 0.55 && maeImprovementMmol <= -0.08 -> 1.10
                else -> 1.0
            }
        }

        private fun roundToFiveMinuteBucket(ts: Long): Long {
            return ((ts + FIVE_MINUTES_MS / 2) / FIVE_MINUTES_MS) * FIVE_MINUTES_MS
        }

        private fun requestedDayTypes(useWeekendSplit: Boolean): List<CircadianDayType> {
            return if (useWeekendSplit) {
                listOf(CircadianDayType.WEEKDAY, CircadianDayType.WEEKEND, CircadianDayType.ALL)
            } else {
                listOf(CircadianDayType.ALL)
            }
        }

        private fun minimumCoverageDays(dayType: CircadianDayType): Int {
            return when (dayType) {
                CircadianDayType.WEEKDAY -> 3
                CircadianDayType.WEEKEND -> 2
                CircadianDayType.ALL -> 4
            }
        }


        private fun minimumReplaySampleCount(dayType: CircadianDayType): Int {
            return when (dayType) {
                CircadianDayType.WEEKDAY -> 5
                CircadianDayType.WEEKEND -> 4
                CircadianDayType.ALL -> 6
            }
        }

        private fun replayConfig(settings: AppSettings): CircadianPatternConfig {
            return CircadianPatternConfig(
                baseTargetMmol = settings.baseTargetMmol,
                stableWindowsDays = listOf(
                    settings.circadianStableLookbackDays.coerceIn(7, 14),
                    10,
                    7
                ).distinct(),
                recencyWindowDays = settings.circadianRecencyLookbackDays.coerceIn(5, 7),
                useWeekendSplit = settings.circadianUseWeekendSplit,
                useReplayResidualBias = settings.circadianUseReplayResidualBias,
                recencyMaxWeight = 0.30
            )
        }

        private fun priorDeltaForHorizon(prior: CircadianForecastPrior, horizonMinutes: Int): Double {
            val base = when {
                horizonMinutes <= 5 -> prior.delta15 * 0.33
                horizonMinutes <= 15 -> prior.delta15
                horizonMinutes <= 30 -> {
                    val ratio = ((horizonMinutes - 15).toDouble() / 15.0).coerceIn(0.0, 1.0)
                    prior.delta15 + (prior.delta30 - prior.delta15) * ratio
                }
                horizonMinutes <= 60 -> {
                    val ratio = ((horizonMinutes - 30).toDouble() / 30.0).coerceIn(0.0, 1.0)
                    prior.delta30 + (prior.delta60 - prior.delta30) * ratio
                }
                else -> prior.delta60
            }
            val reversion = when {
                horizonMinutes <= 15 -> 0.0
                horizonMinutes <= 30 -> {
                    val ratio = ((horizonMinutes - 15).toDouble() / 15.0).coerceIn(0.0, 1.0)
                    prior.medianReversion30 * ratio
                }
                horizonMinutes <= 60 -> {
                    val ratio = ((horizonMinutes - 30).toDouble() / 30.0).coerceIn(0.0, 1.0)
                    prior.medianReversion30 + (prior.medianReversion60 - prior.medianReversion30) * ratio
                }
                else -> prior.medianReversion60
            }
            return base + reversion
        }

        private fun replayBiasForHorizon(prior: CircadianForecastPrior, horizonMinutes: Int): Double {
            return when {
                horizonMinutes < 30 -> 0.0
                horizonMinutes < 60 -> (prior.replayBias30 * replayBiasStrengthForHorizon(prior, horizonMinutes)).coerceIn(-0.20, 0.20)
                else -> (prior.replayBias60 * replayBiasStrengthForHorizon(prior, horizonMinutes)).coerceIn(-0.35, 0.35)
            }
        }

        private fun horizonQualityForHorizon(prior: CircadianForecastPrior, horizonMinutes: Int): Double {
            return when {
                horizonMinutes <= 15 -> 1.0
                horizonMinutes <= 30 -> {
                    val ratio = ((horizonMinutes - 15).toDouble() / 15.0).coerceIn(0.0, 1.0)
                    1.0 + (prior.horizonQuality30 - 1.0) * ratio
                }
                horizonMinutes <= 60 -> {
                    val ratio = ((horizonMinutes - 30).toDouble() / 30.0).coerceIn(0.0, 1.0)
                    prior.horizonQuality30 + (prior.horizonQuality60 - prior.horizonQuality30) * ratio
                }
                else -> prior.horizonQuality60
            }.coerceIn(0.25, 1.0)
        }

        private fun replayMultiplierForPrior(prior: CircadianForecastPrior, horizonMinutes: Int): Double {
            return when {
                horizonMinutes < 30 -> 1.0
                horizonMinutes < 60 -> {
                    val base = replayMultiplier(
                        dayType = prior.requestedDayType,
                        sampleCount = prior.replaySampleCount30,
                        coverageDays = max(1, prior.replaySampleCount30 / 4),
                        requiredCoverageDays = minimumCoverageDays(prior.requestedDayType),
                        maeImprovementMmol = prior.replayMaeImprovement30,
                        winRate = prior.replayWinRate30
                    )
                    (base * replayHelpfulBoost(
                        horizonMinutes = 30,
                        sampleCount = prior.replaySampleCount30,
                        maeImprovementMmol = prior.replayMaeImprovement30,
                        winRate = prior.replayWinRate30,
                        acuteAttenuation = prior.acuteAttenuation
                    )).coerceIn(0.0, 1.25)
                }
                else -> {
                    val base = replayMultiplier(
                        dayType = prior.requestedDayType,
                        sampleCount = prior.replaySampleCount60,
                        coverageDays = max(1, prior.replaySampleCount60 / 4),
                        requiredCoverageDays = minimumCoverageDays(prior.requestedDayType),
                        maeImprovementMmol = prior.replayMaeImprovement60,
                        winRate = prior.replayWinRate60
                    )
                    (base * replayHelpfulBoost(
                        horizonMinutes = 60,
                        sampleCount = prior.replaySampleCount60,
                        maeImprovementMmol = prior.replayMaeImprovement60,
                        winRate = prior.replayWinRate60,
                        acuteAttenuation = prior.acuteAttenuation
                    )).coerceIn(0.0, 1.25)
                }
            }
        }

        private fun replayBiasStrengthForHorizon(prior: CircadianForecastPrior, horizonMinutes: Int): Double {
            return (
                replayMultiplierForPrior(prior, horizonMinutes) *
                    prior.acuteAttenuation *
                    horizonQualityForHorizon(prior, horizonMinutes)
                ).coerceIn(0.0, 1.0)
        }

        internal fun weightForHorizon(
            horizonMinutes: Int,
            confidence: Double,
            qualityScore: Double,
            stabilityScore: Double,
            horizonQuality: Double,
            acuteAttenuation: Double,
            replayMultiplier: Double,
            weight30: Double,
            weight60: Double
        ): Double {
            val patternMultiplier = kotlin.math.sqrt(
                confidence.coerceIn(0.0, 1.0) *
                    qualityScore.coerceIn(0.0, 1.0) *
                    stabilityScore.coerceIn(0.0, 1.0) *
                    horizonQuality.coerceIn(0.0, 1.0)
            ).coerceIn(0.0, 1.0)
            val acuteMultiplier = acuteAttenuation.coerceIn(0.0, 1.0)
            val replay = replayMultiplier.coerceIn(0.0, 1.25)
            val weight5 = (WEIGHT_5 * patternMultiplier * acuteMultiplier)
                .coerceIn(0.0, WEIGHT_5)
            val weight30Effective = (weight30.coerceIn(0.0, 0.45) * patternMultiplier * acuteMultiplier * replay)
                .coerceIn(0.0, 0.45)
            val weight60Effective = (weight60.coerceIn(0.0, 0.55) * patternMultiplier * acuteMultiplier * replay)
                .coerceIn(0.0, 0.55)
            return when {
                horizonMinutes <= 5 -> weight5
                horizonMinutes <= 30 -> {
                    val ratio = ((horizonMinutes - 5).toDouble() / 25.0).coerceIn(0.0, 1.0)
                    weight5 + (weight30Effective - weight5) * ratio
                }
                horizonMinutes <= 60 -> {
                    val ratio = ((horizonMinutes - 30).toDouble() / 30.0).coerceIn(0.0, 1.0)
                    weight30Effective + (weight60Effective - weight30Effective) * ratio
                }
                else -> weight60Effective
            }
        }

        private fun selectedReplayWindowDays(rows: List<ReplayEvaluationRow>): Int {
            return rows
                .groupingBy { it.stableWindowDays }
                .eachCount()
                .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                ?.key
                ?: 14
        }

        private fun appendBucket(
            buckets: MutableMap<String, MutableMap<Int, MutableList<ReplayEvaluationRow>>>,
            bucket: String,
            horizonMinutes: Int,
            row: ReplayEvaluationRow
        ) {
            buckets.getOrPut(bucket) { mutableMapOf() }
                .getOrPut(horizonMinutes) { mutableListOf() }
                .add(row)
        }

        private fun Iterable<Double>.averageOrNull(): Double? {
            val values = filter { it.isFinite() }
            return if (values.none()) null else values.average()
        }

        private fun percentile(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.filter { it.isFinite() }.sorted()
            if (sorted.isEmpty()) return 0.0
            if (sorted.size == 1) return sorted.first()
            val pos = (sorted.lastIndex) * p.coerceIn(0.0, 1.0)
            val low = pos.toInt()
            val high = min(sorted.lastIndex, low + 1)
            val ratio = pos - low.toDouble()
            return sorted[low] + (sorted[high] - sorted[low]) * ratio
        }

        private fun localDate(ts: Long): LocalDate {
            return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
        }
    }

    private data class ReplaySlotKey(
        val dayType: CircadianDayType,
        val windowDays: Int,
        val slotIndex: Int,
        val horizonMinutes: Int
    )

    private data class ReplayEvaluationRow(
        val timestamp: Long,
        val slotIndex: Int,
        val requestedDayType: CircadianDayType,
        val stableWindowDays: Int,
        val horizonMinutes: Int,
        val baselineValue: Double,
        val circadianValue: Double,
        val actualValue: Double,
        val baselineSignedError: Double,
        val circadianSignedError: Double,
        val baselineAbsError: Double,
        val circadianAbsError: Double,
        val lowAcute: Boolean,
        val staleBlocked: Boolean,
        val applied: Boolean
    ) {
        val shiftMmol: Double get() = circadianValue - baselineValue
    }

    private class ReplayGlucoseSeries(points: List<GlucoseSampleEntity>) {
        private val sorted = GlucoseSanitizer.filterEntities(points).sortedBy { it.timestamp }
        private val timestamps = sorted.map { it.timestamp }
        private val values = sorted.map { it.mmol }

        fun actualAt(targetTs: Long, maxGapMinutes: Int): Double? {
            if (sorted.isEmpty()) return null
            val exactIndex = timestamps.binarySearch(targetTs)
            if (exactIndex >= 0) return values[exactIndex]

            val insertionPoint = exactIndex.inv()
            val beforeIndex = insertionPoint - 1
            val afterIndex = insertionPoint
            val toleranceMs = maxGapMinutes * MINUTE_MS
            if (beforeIndex >= 0 && afterIndex < timestamps.size) {
                val beforeTs = timestamps[beforeIndex]
                val afterTs = timestamps[afterIndex]
                val beforeDelta = targetTs - beforeTs
                val afterDelta = afterTs - targetTs
                val totalGap = afterTs - beforeTs
                if (beforeDelta <= toleranceMs && afterDelta <= toleranceMs && totalGap in 1..(2 * toleranceMs)) {
                    val ratio = beforeDelta.toDouble() / totalGap.toDouble()
                    return values[beforeIndex] + (values[afterIndex] - values[beforeIndex]) * ratio
                }
            }
            val candidate = listOfNotNull(
                beforeIndex.takeIf { it >= 0 }?.let { timestamps[it] to values[it] },
                afterIndex.takeIf { it < timestamps.size }?.let { timestamps[it] to values[it] }
            ).minByOrNull { abs(it.first - targetTs) } ?: return null
            return candidate.second.takeIf { abs(candidate.first - targetTs) <= toleranceMs }
        }
    }

    private class ReplayTelemetryLookup(rows: List<TelemetrySampleEntity>) {
        private val byKey: Map<String, Pair<List<Long>, List<Double>>> = rows
            .asSequence()
            .filter { it.key in TELEMETRY_KEYS }
            .mapNotNull { row ->
                parseTelemetryValue(row)?.let { row.key to (row.timestamp to it) }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) ->
                val sorted = values.sortedBy { it.first }
                sorted.map { it.first } to sorted.map { it.second }
            }

        fun snapshotAt(ts: Long): Map<String, Double?> {
            return TELEMETRY_KEYS.associateWith { key -> latest(key, ts) }
        }

        private fun latest(key: String, ts: Long): Double? {
            val item = byKey[key] ?: return null
            val timestamps = item.first
            val idx = timestamps.binarySearch(ts).let { if (it >= 0) it else it.inv() - 1 }
            if (idx < 0) return null
            return item.second[idx]
        }

        private fun parseTelemetryValue(row: TelemetrySampleEntity): Double? {
            return row.valueDouble ?: row.valueText
                ?.replace(',', '.')
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
        }
    }
}

private fun CircadianSlotStatEntity.toReplayDomain(): CircadianSlotStat =
    CircadianSlotStat(
        dayType = CircadianDayType.valueOf(dayType),
        windowDays = windowDays,
        slotIndex = slotIndex,
        sampleCount = sampleCount,
        activeDays = activeDays,
        medianBg = medianBg,
        p10 = p10,
        p25 = p25,
        p75 = p75,
        p90 = p90,
        pLow = pLow,
        pHigh = pHigh,
        pInRange = pInRange,
        fastRiseRate = fastRiseRate,
        fastDropRate = fastDropRate,
        meanCob = meanCob,
        meanIob = meanIob,
        meanUam = meanUam,
        meanActivity = meanActivity,
        confidence = confidence,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )

private fun CircadianTransitionStatEntity.toReplayDomain(): CircadianTransitionStat =
    CircadianTransitionStat(
        dayType = CircadianDayType.valueOf(dayType),
        windowDays = windowDays,
        slotIndex = slotIndex,
        horizonMinutes = horizonMinutes,
        sampleCount = sampleCount,
        deltaMedian = deltaMedian,
        deltaP25 = deltaP25,
        deltaP75 = deltaP75,
        residualBiasMmol = residualBiasMmol,
        confidence = confidence,
        updatedAt = updatedAt
    )

private fun CircadianPatternSnapshotEntity.toReplayDomain(): CircadianPatternSnapshot =
    CircadianPatternSnapshot(
        requestedDayType = CircadianDayType.valueOf(dayType),
        segmentSource = CircadianDayType.valueOf(segmentSource),
        stableWindowDays = stableWindowDays,
        recencyWindowDays = recencyWindowDays,
        recencyWeight = recencyWeight,
        coverageDays = coverageDays,
        sampleCount = sampleCount,
        segmentFallback = segmentFallback,
        fallbackReason = fallbackReason,
        confidence = confidence,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )

private fun CircadianReplaySlotStatEntity.toReplayDomain(): CircadianReplaySlotStat =
    CircadianReplaySlotStat(
        dayType = CircadianDayType.valueOf(dayType),
        windowDays = windowDays,
        slotIndex = slotIndex,
        horizonMinutes = horizonMinutes,
        sampleCount = sampleCount,
        coverageDays = coverageDays,
        maeBaseline = maeBaseline,
        maeCircadian = maeCircadian,
        maeImprovementMmol = maeImprovementMmol,
        medianSignedErrorBaseline = medianSignedErrorBaseline,
        medianSignedErrorCircadian = medianSignedErrorCircadian,
        winRate = winRate,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )
