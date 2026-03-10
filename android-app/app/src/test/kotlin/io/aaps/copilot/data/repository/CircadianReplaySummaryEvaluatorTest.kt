package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.entity.CircadianPatternSnapshotEntity
import io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianSlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianTransitionStatEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.CircadianPatternSnapshot
import io.aaps.copilot.domain.model.CircadianReplaySlotStat
import io.aaps.copilot.domain.model.CircadianSlotStat
import io.aaps.copilot.domain.model.CircadianTransitionStat
import io.aaps.copilot.domain.predict.CircadianPatternConfig
import io.aaps.copilot.domain.predict.CircadianPatternEngine
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import org.junit.Test
import java.time.ZoneId

class CircadianReplaySummaryEvaluatorTest {

    private val zoneId: ZoneId = ZoneId.of("UTC")
    private val evaluator = CircadianReplaySummaryEvaluator(zoneId = zoneId)

    @Test
    fun fitSlotStats_bootstrapReplayFitProducesNonZeroHelpfulRows() {
        val nowTs = ts("2026-03-10T12:00:00Z")
        val slotIndex = 48
        val forecasts = mutableListOf<ForecastEntity>()
        val glucose = mutableListOf<GlucoseSampleEntity>()
        val telemetry = mutableListOf<TelemetrySampleEntity>()
        val weekdays = listOf(2, 3, 4, 5, 6, 9, 10, 11)
        weekdays.forEachIndexed { index, day ->
            val baseTs = ts("2026-03-${"%02d".format(day)}T12:00:00Z")
            forecasts += ForecastEntity(
                id = index.toLong() + 1L,
                timestamp = baseTs,
                horizonMinutes = 30,
                valueMmol = 6.0,
                ciLow = 5.6,
                ciHigh = 6.4,
                modelVersion = "local-v3"
            )
            glucose += GlucoseSampleEntity(
                timestamp = baseTs,
                mmol = 6.0,
                source = "sensor",
                quality = "OK"
            )
            glucose += GlucoseSampleEntity(
                timestamp = baseTs + 30L * 60_000L,
                mmol = 7.1,
                source = "sensor",
                quality = "OK"
            )
            telemetry += telemetrySample(baseTs, "sensor_quality_delta5_mmol", 0.05)
            telemetry += telemetrySample(baseTs, "cob_effective_grams", 2.0)
            telemetry += telemetrySample(baseTs, "iob_effective_units", 1.0)
            telemetry += telemetrySample(baseTs, "uam_value", 0.0)
        }

        val stats = evaluator.fitSlotStats(
            forecasts = forecasts,
            glucose = glucose,
            telemetry = telemetry,
            snapshots = listOf(
                CircadianPatternSnapshotEntity(
                    dayType = "WEEKDAY",
                    segmentSource = "WEEKDAY",
                    stableWindowDays = 14,
                    recencyWindowDays = 5,
                    recencyWeight = 0.0,
                    coverageDays = 8,
                    sampleCount = 96,
                    segmentFallback = false,
                    fallbackReason = null,
                    confidence = 0.95,
                    qualityScore = 0.95,
                    updatedAt = nowTs
                )
            ),
            slotStats = listOf(
                CircadianSlotStatEntity(
                    dayType = "WEEKDAY",
                    windowDays = 14,
                    slotIndex = slotIndex,
                    sampleCount = 16,
                    activeDays = 8,
                    medianBg = 6.0,
                    p10 = 5.7,
                    p25 = 5.9,
                    p75 = 6.2,
                    p90 = 6.4,
                    pLow = 0.0,
                    pHigh = 0.0,
                    pInRange = 1.0,
                    fastRiseRate = 0.0,
                    fastDropRate = 0.0,
                    meanCob = 0.0,
                    meanIob = 0.0,
                    meanUam = 0.0,
                    meanActivity = 1.0,
                    confidence = 0.95,
                    qualityScore = 0.95,
                    updatedAt = nowTs
                )
            ),
            transitionStats = listOf(
                CircadianTransitionStatEntity(
                    dayType = "WEEKDAY",
                    windowDays = 14,
                    slotIndex = slotIndex,
                    horizonMinutes = 15,
                    sampleCount = 8,
                    deltaMedian = 0.5,
                    deltaP25 = 0.4,
                    deltaP75 = 0.6,
                    residualBiasMmol = 0.0,
                    confidence = 0.95,
                    updatedAt = nowTs
                ),
                CircadianTransitionStatEntity(
                    dayType = "WEEKDAY",
                    windowDays = 14,
                    slotIndex = slotIndex,
                    horizonMinutes = 30,
                    sampleCount = 8,
                    deltaMedian = 1.0,
                    deltaP25 = 0.8,
                    deltaP75 = 1.2,
                    residualBiasMmol = 0.0,
                    confidence = 0.95,
                    updatedAt = nowTs
                ),
                CircadianTransitionStatEntity(
                    dayType = "WEEKDAY",
                    windowDays = 14,
                    slotIndex = slotIndex,
                    horizonMinutes = 60,
                    sampleCount = 8,
                    deltaMedian = 1.4,
                    deltaP25 = 1.2,
                    deltaP75 = 1.6,
                    residualBiasMmol = 0.0,
                    confidence = 0.95,
                    updatedAt = nowTs
                )
            ),
            settings = testSettings(),
            nowTs = nowTs
        )

        val slotStat = stats.firstOrNull {
            it.dayType == CircadianDayType.WEEKDAY &&
                it.windowDays == 14 &&
                it.slotIndex == slotIndex &&
                it.horizonMinutes == 30
        }
        assertThat(slotStat).isNotNull()
        assertThat(slotStat!!.sampleCount).isAtLeast(7)
        assertThat(slotStat.winRate).isGreaterThan(0.55)
        assertThat(slotStat.maeImprovementMmol).isLessThan(-0.05)
    }

    @Test
    fun evaluate_reconstructsBaselineFromCircadianTaggedForecasts() {
        val forecastTs = ts("2026-03-09T12:00:00Z")
        val nowTs = ts("2026-03-09T13:00:00Z")
        val slotIndex = 48
        val snapshots = listOf(
            CircadianPatternSnapshotEntity(
                dayType = "WEEKDAY",
                segmentSource = "WEEKDAY",
                stableWindowDays = 14,
                recencyWindowDays = 5,
                recencyWeight = 0.0,
                coverageDays = 10,
                sampleCount = 96,
                segmentFallback = false,
                fallbackReason = null,
                confidence = 1.0,
                qualityScore = 1.0,
                updatedAt = nowTs
            )
        )
        val slotStats = listOf(
            CircadianSlotStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                sampleCount = 16,
                activeDays = 10,
                medianBg = 6.4,
                p10 = 5.8,
                p25 = 6.1,
                p75 = 6.9,
                p90 = 7.2,
                pLow = 0.0,
                pHigh = 0.0,
                pInRange = 1.0,
                fastRiseRate = 0.0,
                fastDropRate = 0.0,
                meanCob = 0.0,
                meanIob = 0.0,
                meanUam = 0.0,
                meanActivity = 1.0,
                confidence = 1.0,
                qualityScore = 1.0,
                updatedAt = nowTs
            )
        )
        val transitionStats = listOf(
            CircadianTransitionStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 15,
                sampleCount = 16,
                deltaMedian = 0.5,
                deltaP25 = 0.3,
                deltaP75 = 0.7,
                residualBiasMmol = 0.0,
                confidence = 1.0,
                updatedAt = nowTs
            ),
            CircadianTransitionStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 30,
                sampleCount = 16,
                deltaMedian = 1.0,
                deltaP25 = 0.8,
                deltaP75 = 1.2,
                residualBiasMmol = 0.0,
                confidence = 1.0,
                updatedAt = nowTs
            ),
            CircadianTransitionStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 60,
                sampleCount = 16,
                deltaMedian = 1.5,
                deltaP25 = 1.2,
                deltaP75 = 1.8,
                residualBiasMmol = 0.0,
                confidence = 1.0,
                updatedAt = nowTs
            )
        )
        val replaySlotStats = listOf(
            CircadianReplaySlotStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 30,
                sampleCount = 12,
                coverageDays = 4,
                maeBaseline = 1.0,
                maeCircadian = 0.75,
                maeImprovementMmol = -0.25,
                medianSignedErrorBaseline = 0.0,
                medianSignedErrorCircadian = 0.0,
                winRate = 0.75,
                qualityScore = 0.90,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 60,
                sampleCount = 12,
                coverageDays = 4,
                maeBaseline = 0.9,
                maeCircadian = 0.55,
                maeImprovementMmol = -0.35,
                medianSignedErrorBaseline = 0.0,
                medianSignedErrorCircadian = 0.0,
                winRate = 0.75,
                qualityScore = 0.90,
                updatedAt = nowTs
            )
        )
        val prior = CircadianPatternEngine(zoneId = zoneId).resolvePrior(
            nowTs = forecastTs,
            currentGlucoseMmol = 6.0,
            telemetry = emptyMap(),
            snapshots = snapshots.map {
                CircadianPatternSnapshot(
                    requestedDayType = CircadianDayType.valueOf(it.dayType),
                    segmentSource = CircadianDayType.valueOf(it.segmentSource),
                    stableWindowDays = it.stableWindowDays,
                    recencyWindowDays = it.recencyWindowDays,
                    recencyWeight = it.recencyWeight,
                    coverageDays = it.coverageDays,
                    sampleCount = it.sampleCount,
                    segmentFallback = it.segmentFallback,
                    fallbackReason = it.fallbackReason,
                    confidence = it.confidence,
                    qualityScore = it.qualityScore,
                    updatedAt = it.updatedAt
                )
            },
            slotStats = slotStats.map {
                CircadianSlotStat(
                    dayType = CircadianDayType.valueOf(it.dayType),
                    windowDays = it.windowDays,
                    slotIndex = it.slotIndex,
                    sampleCount = it.sampleCount,
                    activeDays = it.activeDays,
                    medianBg = it.medianBg,
                    p10 = it.p10,
                    p25 = it.p25,
                    p75 = it.p75,
                    p90 = it.p90,
                    pLow = it.pLow,
                    pHigh = it.pHigh,
                    pInRange = it.pInRange,
                    fastRiseRate = it.fastRiseRate,
                    fastDropRate = it.fastDropRate,
                    meanCob = it.meanCob,
                    meanIob = it.meanIob,
                    meanUam = it.meanUam,
                    meanActivity = it.meanActivity,
                    confidence = it.confidence,
                    qualityScore = it.qualityScore,
                    updatedAt = it.updatedAt
                )
            },
            transitionStats = transitionStats.map {
                CircadianTransitionStat(
                    dayType = CircadianDayType.valueOf(it.dayType),
                    windowDays = it.windowDays,
                    slotIndex = it.slotIndex,
                    horizonMinutes = it.horizonMinutes,
                    sampleCount = it.sampleCount,
                    deltaMedian = it.deltaMedian,
                    deltaP25 = it.deltaP25,
                    deltaP75 = it.deltaP75,
                    residualBiasMmol = it.residualBiasMmol,
                    confidence = it.confidence,
                    updatedAt = it.updatedAt
                )
            },
            replayStats = replaySlotStats.map {
                CircadianReplaySlotStat(
                    dayType = CircadianDayType.valueOf(it.dayType),
                    windowDays = it.windowDays,
                    slotIndex = it.slotIndex,
                    horizonMinutes = it.horizonMinutes,
                    sampleCount = it.sampleCount,
                    coverageDays = it.coverageDays,
                    maeBaseline = it.maeBaseline,
                    maeCircadian = it.maeCircadian,
                    maeImprovementMmol = it.maeImprovementMmol,
                    medianSignedErrorBaseline = it.medianSignedErrorBaseline,
                    medianSignedErrorCircadian = it.medianSignedErrorCircadian,
                    winRate = it.winRate,
                    qualityScore = it.qualityScore,
                    updatedAt = it.updatedAt
                )
            },
            config = CircadianPatternConfig(
                baseTargetMmol = 5.5,
                stableWindowsDays = listOf(14, 10, 7),
                recencyWindowDays = 5,
                minSlotSamples = 2,
                minActiveDaysWeekday = 3,
                minActiveDaysWeekend = 2,
                minActiveDaysAll = 4,
                minCoverageRatioWeekday = 0.35,
                minCoverageRatioWeekend = 0.35,
                minCoverageRatioAll = 0.35,
                useWeekendSplit = true,
                useReplayResidualBias = true,
                recencyMaxWeight = 0.30
            )
        )
        assertThat(prior).isNotNull()
        val resolvedPrior = prior!!
        val baseline30 = 6.0
        val baseline60 = 6.5
        val actual30 = 7.0
        val actual60 = 7.4
        val replayMultiplier30 = CircadianReplaySummaryEvaluator.replayMultiplier(
            dayType = resolvedPrior.requestedDayType,
            sampleCount = resolvedPrior.replaySampleCount30,
            coverageDays = maxOf(1, resolvedPrior.replaySampleCount30 / 4),
            requiredCoverageDays = 3,
            maeImprovementMmol = resolvedPrior.replayMaeImprovement30,
            winRate = resolvedPrior.replayWinRate30
        ) * CircadianReplaySummaryEvaluator.replayHelpfulBoost(
            horizonMinutes = 30,
            sampleCount = resolvedPrior.replaySampleCount30,
            maeImprovementMmol = resolvedPrior.replayMaeImprovement30,
            winRate = resolvedPrior.replayWinRate30,
            acuteAttenuation = resolvedPrior.acuteAttenuation
        )
        val replayMultiplier60 = CircadianReplaySummaryEvaluator.replayMultiplier(
            dayType = resolvedPrior.requestedDayType,
            sampleCount = resolvedPrior.replaySampleCount60,
            coverageDays = maxOf(1, resolvedPrior.replaySampleCount60 / 4),
            requiredCoverageDays = 3,
            maeImprovementMmol = resolvedPrior.replayMaeImprovement60,
            winRate = resolvedPrior.replayWinRate60
        ) * CircadianReplaySummaryEvaluator.replayHelpfulBoost(
            horizonMinutes = 60,
            sampleCount = resolvedPrior.replaySampleCount60,
            maeImprovementMmol = resolvedPrior.replayMaeImprovement60,
            winRate = resolvedPrior.replayWinRate60,
            acuteAttenuation = resolvedPrior.acuteAttenuation
        )
        val weight30 = CircadianReplaySummaryEvaluator.weightForHorizon(
            horizonMinutes = 30,
            confidence = resolvedPrior.confidence,
            qualityScore = resolvedPrior.qualityScore,
            stabilityScore = resolvedPrior.stabilityScore,
            horizonQuality = resolvedPrior.horizonQuality30,
            acuteAttenuation = resolvedPrior.acuteAttenuation,
            replayMultiplier = replayMultiplier30,
            weight30 = testSettings().circadianForecastWeight30,
            weight60 = testSettings().circadianForecastWeight60
        )
        val weight60 = CircadianReplaySummaryEvaluator.weightForHorizon(
            horizonMinutes = 60,
            confidence = resolvedPrior.confidence,
            qualityScore = resolvedPrior.qualityScore,
            stabilityScore = resolvedPrior.stabilityScore,
            horizonQuality = resolvedPrior.horizonQuality60,
            acuteAttenuation = resolvedPrior.acuteAttenuation,
            replayMultiplier = replayMultiplier60,
            weight30 = testSettings().circadianForecastWeight30,
            weight60 = testSettings().circadianForecastWeight60
        )
        val storedCircadian30 = (
            baseline30 * (1.0 - weight30) +
                (6.0 + resolvedPrior.delta30 + resolvedPrior.medianReversion30) * weight30
            ).coerceIn(2.2, 22.0)
        val storedCircadian60 = (
            baseline60 * (1.0 - weight60) +
                (6.0 + resolvedPrior.delta60 + resolvedPrior.medianReversion60) * weight60
            ).coerceIn(2.2, 22.0)

        val summary = evaluator.evaluate(
            forecasts = listOf(
                ForecastEntity(
                    id = 1,
                    timestamp = forecastTs,
                    horizonMinutes = 30,
                    valueMmol = storedCircadian30,
                    ciLow = 5.8,
                    ciHigh = 6.7,
                    modelVersion = "local-hybrid-v3|circadian_v1"
                ),
                ForecastEntity(
                    id = 2,
                    timestamp = forecastTs,
                    horizonMinutes = 60,
                    valueMmol = storedCircadian60,
                    ciLow = 6.2,
                    ciHigh = 7.5,
                    modelVersion = "local-hybrid-v3|circadian_v1"
                )
            ),
            glucose = listOf(
                GlucoseSampleEntity(id = 1, timestamp = forecastTs, mmol = 6.0, source = "test", quality = "OK"),
                GlucoseSampleEntity(id = 2, timestamp = forecastTs + 30 * 60_000L, mmol = 7.0, source = "test", quality = "OK"),
                GlucoseSampleEntity(id = 3, timestamp = forecastTs + 60 * 60_000L, mmol = 7.4, source = "test", quality = "OK")
            ),
            telemetry = listOf(
                telemetrySample(forecastTs, "sensor_quality_delta5_mmol", 0.05),
                telemetrySample(forecastTs, "cob_effective_grams", 2.0),
                telemetrySample(forecastTs, "iob_effective_units", 1.0),
                telemetrySample(forecastTs, "uam_value", 0.0)
            ),
            snapshots = snapshots,
            slotStats = slotStats,
            transitionStats = transitionStats,
            replaySlotStats = replaySlotStats,
            settings = testSettings(),
            nowTs = nowTs,
            windowsDays = listOf(1)
        )

        assertThat(summary.windows).hasSize(1)
        val window = summary.windows.single()
        assertThat(window.days).isEqualTo(1)
        assertThat(window.appliedRows).isEqualTo(2)
        assertThat(window.appliedPct).isWithin(0.001).of(100.0)
        assertThat(window.meanShift30).isGreaterThan(0.15)
        assertThat(window.meanShift30).isLessThan(0.30)
        assertThat(window.meanShift60).isGreaterThan(0.25)
        assertThat(window.meanShift60).isLessThan(0.40)

        val allBucket = window.buckets.first { it.bucket == "ALL" }
        val weekdayBucket = window.buckets.first { it.bucket == "WEEKDAY" }
        val lowAcuteBucket = window.buckets.first { it.bucket == "LOW_ACUTE" }

        val metric30 = allBucket.metrics.first { it.horizonMinutes == 30 }
        assertThat(metric30.sampleCount).isEqualTo(1)
        assertThat(metric30.maeBaseline).isWithin(0.05).of(1.0)
        assertThat(metric30.maeCircadian).isLessThan(metric30.maeBaseline)
        assertThat(metric30.deltaMmol).isWithin(0.0001).of(metric30.maeCircadian - metric30.maeBaseline)
        assertThat(metric30.deltaPct).isWithin(0.0001).of(((metric30.maeCircadian - metric30.maeBaseline) / metric30.maeBaseline) * 100.0)
        assertThat(metric30.bucketStatus.name).isEqualTo("INSUFFICIENT")
        assertThat(metric30.winRate).isWithin(0.0001).of(1.0)

        val metric60 = allBucket.metrics.first { it.horizonMinutes == 60 }
        assertThat(metric60.sampleCount).isEqualTo(1)
        assertThat(metric60.maeBaseline).isGreaterThan(0.7)
        assertThat(metric60.maeCircadian).isLessThan(metric60.maeBaseline)
        assertThat(metric60.deltaMmol).isWithin(0.0001).of(metric60.maeCircadian - metric60.maeBaseline)
        assertThat(metric60.deltaPct).isWithin(0.0001).of(((metric60.maeCircadian - metric60.maeBaseline) / metric60.maeBaseline) * 100.0)
        assertThat(metric60.bucketStatus.name).isEqualTo("INSUFFICIENT")
        assertThat(metric60.winRate).isWithin(0.0001).of(1.0)

        val weekdayMetric30 = weekdayBucket.metrics.first { it.horizonMinutes == 30 }
        val weekdayMetric60 = weekdayBucket.metrics.first { it.horizonMinutes == 60 }
        assertThat(weekdayMetric30.bucketStatus.name).isEqualTo("HELPFUL")
        assertThat(weekdayMetric60.bucketStatus.name).isEqualTo("HELPFUL")
        assertThat(weekdayMetric30.winRate).isWithin(0.0001).of(0.75)
        assertThat(weekdayMetric60.winRate).isWithin(0.0001).of(0.75)
        assertThat(weekdayBucket.metrics.map { it.horizonMinutes }).containsExactly(30, 60)
        assertThat(lowAcuteBucket.metrics.map { it.horizonMinutes }).containsExactly(30, 60)
    }

    @Test
    fun replayBucketStatus_classifiesHelpfulNeutralHarmfulAndInsufficient() {
        assertThat(
            CircadianReplaySummaryEvaluator.replayBucketStatus(
                dayType = CircadianDayType.WEEKDAY,
                sampleCount = 12,
                coverageDays = 4,
                requiredCoverageDays = 3,
                maeImprovementMmol = -0.06,
                winRate = 0.60
            )
        ).isEqualTo(io.aaps.copilot.domain.model.CircadianReplayBucketStatus.HELPFUL)

        assertThat(
            CircadianReplaySummaryEvaluator.replayBucketStatus(
                dayType = CircadianDayType.WEEKDAY,
                sampleCount = 12,
                coverageDays = 4,
                requiredCoverageDays = 3,
                maeImprovementMmol = -0.02,
                winRate = 0.52
            )
        ).isEqualTo(io.aaps.copilot.domain.model.CircadianReplayBucketStatus.NEUTRAL)

        assertThat(
            CircadianReplaySummaryEvaluator.replayBucketStatus(
                dayType = CircadianDayType.WEEKDAY,
                sampleCount = 12,
                coverageDays = 4,
                requiredCoverageDays = 3,
                maeImprovementMmol = 0.08,
                winRate = 0.40
            )
        ).isEqualTo(io.aaps.copilot.domain.model.CircadianReplayBucketStatus.HARMFUL)

        assertThat(
            CircadianReplaySummaryEvaluator.replayBucketStatus(
                dayType = CircadianDayType.WEEKDAY,
                sampleCount = 12,
                coverageDays = 4,
                requiredCoverageDays = 3,
                maeImprovementMmol = 0.013,
                winRate = 0.33
            )
        ).isEqualTo(io.aaps.copilot.domain.model.CircadianReplayBucketStatus.NEUTRAL)

        assertThat(
            CircadianReplaySummaryEvaluator.replayBucketStatus(
                dayType = CircadianDayType.WEEKDAY,
                sampleCount = 6,
                coverageDays = 2,
                requiredCoverageDays = 3,
                maeImprovementMmol = -0.20,
                winRate = 0.80
            )
        ).isEqualTo(io.aaps.copilot.domain.model.CircadianReplayBucketStatus.INSUFFICIENT)
    }

    @Test
    fun replayHelpfulBoost_allowsDormantUamAttenuationLevel() {
        val boost30 = CircadianReplaySummaryEvaluator.replayHelpfulBoost(
            horizonMinutes = 30,
            sampleCount = 8,
            maeImprovementMmol = -0.12,
            winRate = 0.62,
            acuteAttenuation = 0.70
        )
        val boost60 = CircadianReplaySummaryEvaluator.replayHelpfulBoost(
            horizonMinutes = 60,
            sampleCount = 8,
            maeImprovementMmol = -0.18,
            winRate = 0.62,
            acuteAttenuation = 0.70
        )

        assertThat(boost30).isGreaterThan(1.0)
        assertThat(boost60).isGreaterThan(boost30)
    }

    @Test
    fun evaluate_reconstructsBaselineFromCircadianV2UsingReplayAwareWeight() {
        val forecastTs = ts("2026-03-09T12:00:00Z")
        val nowTs = ts("2026-03-09T13:00:00Z")
        val slotIndex = 48
        val snapshots = listOf(
            CircadianPatternSnapshotEntity(
                dayType = "WEEKDAY",
                segmentSource = "WEEKDAY",
                stableWindowDays = 14,
                recencyWindowDays = 5,
                recencyWeight = 0.0,
                coverageDays = 10,
                sampleCount = 96,
                segmentFallback = false,
                fallbackReason = null,
                confidence = 1.0,
                qualityScore = 1.0,
                updatedAt = nowTs
            )
        )
        val slotStats = listOf(
            CircadianSlotStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                sampleCount = 16,
                activeDays = 10,
                medianBg = 6.4,
                p10 = 5.8,
                p25 = 6.1,
                p75 = 6.9,
                p90 = 7.2,
                pLow = 0.0,
                pHigh = 0.0,
                pInRange = 1.0,
                fastRiseRate = 0.0,
                fastDropRate = 0.0,
                meanCob = 0.0,
                meanIob = 0.0,
                meanUam = 0.0,
                meanActivity = 1.0,
                confidence = 1.0,
                qualityScore = 1.0,
                updatedAt = nowTs
            )
        )
        val transitionStats = listOf(
            CircadianTransitionStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 15,
                sampleCount = 16,
                deltaMedian = 0.5,
                deltaP25 = 0.3,
                deltaP75 = 0.7,
                residualBiasMmol = 0.0,
                confidence = 1.0,
                updatedAt = nowTs
            ),
            CircadianTransitionStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 30,
                sampleCount = 16,
                deltaMedian = 1.0,
                deltaP25 = 0.8,
                deltaP75 = 1.2,
                residualBiasMmol = 0.0,
                confidence = 1.0,
                updatedAt = nowTs
            ),
            CircadianTransitionStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 60,
                sampleCount = 16,
                deltaMedian = 1.5,
                deltaP25 = 1.2,
                deltaP75 = 1.8,
                residualBiasMmol = 0.0,
                confidence = 1.0,
                updatedAt = nowTs
            )
        )
        val replaySlotStats = listOf(
            CircadianReplaySlotStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 60,
                sampleCount = 12,
                coverageDays = 4,
                maeBaseline = 0.9,
                maeCircadian = 0.88,
                maeImprovementMmol = -0.02,
                medianSignedErrorBaseline = 0.0,
                medianSignedErrorCircadian = 0.0,
                winRate = 0.52,
                qualityScore = 0.90,
                updatedAt = nowTs
            )
        )
        val prior = CircadianPatternEngine(zoneId = zoneId).resolvePrior(
            nowTs = forecastTs,
            currentGlucoseMmol = 6.0,
            telemetry = emptyMap(),
            snapshots = snapshots.map {
                CircadianPatternSnapshot(
                    requestedDayType = CircadianDayType.valueOf(it.dayType),
                    segmentSource = CircadianDayType.valueOf(it.segmentSource),
                    stableWindowDays = it.stableWindowDays,
                    recencyWindowDays = it.recencyWindowDays,
                    recencyWeight = it.recencyWeight,
                    coverageDays = it.coverageDays,
                    sampleCount = it.sampleCount,
                    segmentFallback = it.segmentFallback,
                    fallbackReason = it.fallbackReason,
                    confidence = it.confidence,
                    qualityScore = it.qualityScore,
                    updatedAt = it.updatedAt
                )
            },
            slotStats = slotStats.map {
                CircadianSlotStat(
                    dayType = CircadianDayType.valueOf(it.dayType),
                    windowDays = it.windowDays,
                    slotIndex = it.slotIndex,
                    sampleCount = it.sampleCount,
                    activeDays = it.activeDays,
                    medianBg = it.medianBg,
                    p10 = it.p10,
                    p25 = it.p25,
                    p75 = it.p75,
                    p90 = it.p90,
                    pLow = it.pLow,
                    pHigh = it.pHigh,
                    pInRange = it.pInRange,
                    fastRiseRate = it.fastRiseRate,
                    fastDropRate = it.fastDropRate,
                    meanCob = it.meanCob,
                    meanIob = it.meanIob,
                    meanUam = it.meanUam,
                    meanActivity = it.meanActivity,
                    confidence = it.confidence,
                    qualityScore = it.qualityScore,
                    updatedAt = it.updatedAt
                )
            },
            transitionStats = transitionStats.map {
                CircadianTransitionStat(
                    dayType = CircadianDayType.valueOf(it.dayType),
                    windowDays = it.windowDays,
                    slotIndex = it.slotIndex,
                    horizonMinutes = it.horizonMinutes,
                    sampleCount = it.sampleCount,
                    deltaMedian = it.deltaMedian,
                    deltaP25 = it.deltaP25,
                    deltaP75 = it.deltaP75,
                    residualBiasMmol = it.residualBiasMmol,
                    confidence = it.confidence,
                    updatedAt = it.updatedAt
                )
            },
            replayStats = replaySlotStats.map {
                CircadianReplaySlotStat(
                    dayType = CircadianDayType.valueOf(it.dayType),
                    windowDays = it.windowDays,
                    slotIndex = it.slotIndex,
                    horizonMinutes = it.horizonMinutes,
                    sampleCount = it.sampleCount,
                    coverageDays = it.coverageDays,
                    maeBaseline = it.maeBaseline,
                    maeCircadian = it.maeCircadian,
                    maeImprovementMmol = it.maeImprovementMmol,
                    medianSignedErrorBaseline = it.medianSignedErrorBaseline,
                    medianSignedErrorCircadian = it.medianSignedErrorCircadian,
                    winRate = it.winRate,
                    qualityScore = it.qualityScore,
                    updatedAt = it.updatedAt
                )
            },
            config = CircadianPatternConfig(
                baseTargetMmol = 5.5,
                stableWindowsDays = listOf(14, 10, 7),
                recencyWindowDays = 5,
                minSlotSamples = 2,
                minActiveDaysWeekday = 3,
                minActiveDaysWeekend = 2,
                minActiveDaysAll = 4,
                minCoverageRatioWeekday = 0.35,
                minCoverageRatioWeekend = 0.35,
                minCoverageRatioAll = 0.35,
                useWeekendSplit = true,
                useReplayResidualBias = true,
                recencyMaxWeight = 0.30
            )
        )
        assertThat(prior).isNotNull()
        val resolvedPrior = prior!!
        val baseline60 = 7.0
        val replayAwareWeight = CircadianReplaySummaryEvaluator.weightForHorizon(
            horizonMinutes = 60,
            confidence = resolvedPrior.confidence,
            qualityScore = resolvedPrior.qualityScore,
            stabilityScore = resolvedPrior.stabilityScore,
            horizonQuality = resolvedPrior.horizonQuality60,
            acuteAttenuation = resolvedPrior.acuteAttenuation,
            replayMultiplier = 0.70,
            weight30 = testSettings().circadianForecastWeight30,
            weight60 = testSettings().circadianForecastWeight60
        )
        val storedCircadian60 = (
            baseline60 * (1.0 - replayAwareWeight) +
                (6.0 + resolvedPrior.delta60 + resolvedPrior.medianReversion60) * replayAwareWeight
            ).coerceIn(2.2, 22.0)

        val summary = evaluator.evaluate(
            forecasts = listOf(
                ForecastEntity(
                    id = 1,
                    timestamp = forecastTs,
                    horizonMinutes = 60,
                    valueMmol = storedCircadian60,
                    ciLow = storedCircadian60 - 0.5,
                    ciHigh = storedCircadian60 + 0.5,
                    modelVersion = "local-hybrid-v3|circadian_v2"
                )
            ),
            glucose = listOf(
                GlucoseSampleEntity(id = 1, timestamp = forecastTs, mmol = 6.0, source = "test", quality = "OK"),
                GlucoseSampleEntity(id = 2, timestamp = forecastTs + 60 * 60_000L, mmol = 7.9, source = "test", quality = "OK")
            ),
            telemetry = emptyList(),
            snapshots = snapshots,
            slotStats = slotStats,
            transitionStats = transitionStats,
            replaySlotStats = replaySlotStats,
            settings = testSettings(),
            nowTs = nowTs,
            windowsDays = listOf(1)
        )

        val metric60 = summary.windows.single().buckets.first { it.bucket == "ALL" }.metrics.first { it.horizonMinutes == 60 }
        assertThat(metric60.maeBaseline).isWithin(0.0001).of(0.9)
    }

    @Test
    fun evaluate_doesNotMarkLowAcuteWhenTelemetrySnapshotIsMissing() {
        val nowTs = ts("2026-03-10T12:00:00Z")
        val forecastTs = ts("2026-03-10T11:00:00Z")
        val slotIndex = 44
        val snapshots = listOf(
            CircadianPatternSnapshotEntity(
                dayType = "WEEKDAY",
                segmentSource = "WEEKDAY",
                stableWindowDays = 14,
                recencyWindowDays = 5,
                recencyWeight = 0.0,
                coverageDays = 8,
                sampleCount = 96,
                segmentFallback = false,
                fallbackReason = null,
                confidence = 0.95,
                qualityScore = 0.95,
                updatedAt = nowTs
            )
        )
        val slotStats = listOf(
            CircadianSlotStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                sampleCount = 16,
                activeDays = 8,
                medianBg = 6.0,
                p10 = 5.7,
                p25 = 5.9,
                p75 = 6.2,
                p90 = 6.4,
                pLow = 0.0,
                pHigh = 0.0,
                pInRange = 1.0,
                fastRiseRate = 0.0,
                fastDropRate = 0.0,
                meanCob = 0.0,
                meanIob = 0.0,
                meanUam = 0.0,
                meanActivity = 1.0,
                confidence = 0.95,
                qualityScore = 0.95,
                updatedAt = nowTs
            )
        )
        val transitionStats = listOf(
            CircadianTransitionStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 30,
                sampleCount = 8,
                deltaMedian = 1.0,
                deltaP25 = 0.8,
                deltaP75 = 1.2,
                residualBiasMmol = 0.0,
                confidence = 0.95,
                updatedAt = nowTs
            ),
            CircadianTransitionStatEntity(
                dayType = "WEEKDAY",
                windowDays = 14,
                slotIndex = slotIndex,
                horizonMinutes = 60,
                sampleCount = 8,
                deltaMedian = 1.4,
                deltaP25 = 1.2,
                deltaP75 = 1.6,
                residualBiasMmol = 0.0,
                confidence = 0.95,
                updatedAt = nowTs
            )
        )

        val summary = evaluator.evaluate(
            forecasts = listOf(
                ForecastEntity(1, forecastTs, 30, 6.8, 6.4, 7.2, "local-hybrid-v3|circadian_v2"),
                ForecastEntity(2, forecastTs, 60, 7.0, 6.6, 7.4, "local-hybrid-v3|circadian_v2")
            ),
            glucose = listOf(
                GlucoseSampleEntity(id = 1, timestamp = forecastTs, mmol = 6.0, source = "test", quality = "OK"),
                GlucoseSampleEntity(id = 2, timestamp = forecastTs + 30 * 60_000L, mmol = 6.8, source = "test", quality = "OK"),
                GlucoseSampleEntity(id = 3, timestamp = forecastTs + 60 * 60_000L, mmol = 7.0, source = "test", quality = "OK")
            ),
            telemetry = emptyList(),
            snapshots = snapshots,
            slotStats = slotStats,
            transitionStats = transitionStats,
            replaySlotStats = emptyList(),
            settings = testSettings(),
            nowTs = nowTs,
            windowsDays = listOf(1)
        )

        assertThat(summary.windows).hasSize(1)
        val window = summary.windows.single()
        assertThat(window.buckets.map { it.bucket }).doesNotContain("LOW_ACUTE")
    }

    private fun telemetrySample(timestamp: Long, key: String, value: Double): TelemetrySampleEntity {
        return TelemetrySampleEntity(
            id = "$key-$timestamp",
            timestamp = timestamp,
            source = "test",
            key = key,
            valueDouble = value,
            valueText = null,
            unit = null,
            quality = "OK"
        )
    }

    private fun testSettings(): AppSettings {
        return AppSettings(
            nightscoutUrl = "",
            apiSecret = "",
            cloudBaseUrl = "",
            openAiApiKey = "",
            killSwitch = false,
            rootExperimentalEnabled = false,
            localBroadcastIngestEnabled = true,
            strictBroadcastSenderValidation = false,
            localNightscoutEnabled = true,
            localNightscoutPort = 17582,
            localCommandFallbackEnabled = true,
            localCommandPackage = "info.nightscout.androidaps",
            localCommandAction = "io.aaps.copilot.ACTION_COMMAND",
            insulinProfileId = InsulinActionProfileId.FIASP.name,
            baseTargetMmol = 5.5,
            postHypoThresholdMmol = 3.0,
            postHypoDeltaThresholdMmol5m = 0.2,
            postHypoTargetMmol = 4.4,
            postHypoDurationMinutes = 60,
            postHypoLookbackMinutes = 90,
            rulePostHypoEnabled = true,
            rulePatternEnabled = true,
            ruleSegmentEnabled = true,
            adaptiveControllerEnabled = true,
            rulePostHypoPriority = 100,
            rulePatternPriority = 50,
            ruleSegmentPriority = 40,
            adaptiveControllerPriority = 120,
            rulePostHypoCooldownMinutes = 30,
            rulePatternCooldownMinutes = 30,
            ruleSegmentCooldownMinutes = 30,
            adaptiveControllerRetargetMinutes = 5,
            adaptiveControllerSafetyProfile = "BALANCED",
            adaptiveControllerStaleMaxMinutes = 15,
            adaptiveControllerMaxActions6h = 4,
            adaptiveControllerMaxStepMmol = 0.25,
            patternMinSamplesPerWindow = 40,
            patternMinActiveDaysPerWindow = 7,
            patternLowRateTrigger = 0.12,
            patternHighRateTrigger = 0.18,
            analyticsLookbackDays = 365,
            circadianPatternsEnabled = true,
            circadianStableLookbackDays = 14,
            circadianRecencyLookbackDays = 5,
            circadianUseWeekendSplit = true,
            circadianUseReplayResidualBias = true,
            circadianForecastWeight30 = 0.25,
            circadianForecastWeight60 = 0.35,
            maxActionsIn6Hours = 3,
            staleDataMaxMinutes = 10,
            exportFolderUri = null
        )
    }

    private fun ts(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()
}
