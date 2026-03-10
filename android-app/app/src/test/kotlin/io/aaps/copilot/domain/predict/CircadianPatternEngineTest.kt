package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.CircadianReplayBucketStatus
import io.aaps.copilot.domain.model.CircadianReplaySlotStat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CircadianPatternEngineTest {

    private val zoneId: ZoneId = ZoneId.of("UTC")
    private val engine = CircadianPatternEngine(zoneId = zoneId)

    @Test
    fun fit_builds15MinuteStats_andFallsBackWeekendToAllWhenSparse() {
        val nowTs = ts("2026-03-09", 12, 0)
        val glucose = buildGlucoseHistory(
            endDate = LocalDate.parse("2026-03-09"),
            lookbackDays = 14
        ) { date, minuteOfDay ->
            if (date.dayOfWeek.value in setOf(6, 7) && date != LocalDate.parse("2026-03-08")) {
                null
            } else {
                5.8 + (minuteOfDay / 1440.0)
            }
        }

        val result = engine.fit(
            glucoseHistory = glucose,
            telemetryHistory = emptyList(),
            forecastHistory = emptyList(),
            nowTs = nowTs,
            config = testConfig()
        )

        val weekdaySnapshot = result.snapshots.first { it.requestedDayType == CircadianDayType.WEEKDAY }
        val weekendSnapshot = result.snapshots.first { it.requestedDayType == CircadianDayType.WEEKEND }

        assertThat(weekdaySnapshot.segmentSource).isEqualTo(CircadianDayType.WEEKDAY)
        assertThat(weekdaySnapshot.segmentFallback).isFalse()
        assertThat(weekendSnapshot.segmentSource).isEqualTo(CircadianDayType.ALL)
        assertThat(weekendSnapshot.segmentFallback).isTrue()
        assertThat(
            result.slotStats.count {
                it.dayType == CircadianDayType.WEEKDAY && it.windowDays == 14
            }
        ).isEqualTo(96)
        assertThat(
            result.transitionStats.count {
                it.dayType == CircadianDayType.WEEKDAY &&
                    it.windowDays == 14 &&
                    it.horizonMinutes in setOf(15, 30, 60)
            }
        ).isEqualTo(96 * 3)
        assertThat(result.derivedPatternWindows).isNotEmpty()
    }

    @Test
    fun fit_fallsBackStableWindowFrom14To7WhenHistorySparse() {
        val nowTs = ts("2026-03-09", 12, 0)
        val glucose = buildGlucoseHistory(
            endDate = LocalDate.parse("2026-03-09"),
            lookbackDays = 7
        ) { _, minuteOfDay -> 6.0 + (minuteOfDay / 1440.0) * 0.2 }

        val result = engine.fit(
            glucoseHistory = glucose,
            telemetryHistory = emptyList(),
            forecastHistory = emptyList(),
            nowTs = nowTs,
            config = testConfig(
                useWeekendSplit = false,
                minCoverageRatioAll = 0.8
            )
        )

        val snapshot = result.snapshots.single()
        assertThat(snapshot.requestedDayType).isEqualTo(CircadianDayType.ALL)
        assertThat(snapshot.stableWindowDays).isEqualTo(7)
        assertThat(snapshot.segmentFallback).isFalse()
    }

    @Test
    fun resolvePrior_appliesBoundedRecencyCorrection() {
        val nowTs = ts("2026-03-09", 12, 0)
        val glucose = buildGlucoseHistory(
            endDate = LocalDate.parse("2026-03-09"),
            lookbackDays = 14
        ) { date, _ ->
            if (date >= LocalDate.parse("2026-03-05")) 12.0 else 6.0
        }

        val result = engine.fit(
            glucoseHistory = glucose,
            telemetryHistory = emptyList(),
            forecastHistory = emptyList(),
            nowTs = nowTs,
            config = testConfig(useWeekendSplit = true)
        )
        val prior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 7.0,
            telemetry = emptyMap(),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            config = testConfig(useWeekendSplit = true)
        )

        assertThat(prior).isNotNull()
        assertThat(prior!!.bgMedian).isWithin(0.01).of(6.45)
    }

    @Test
    fun resolvePrior_appliesAcuteAttenuation_andBlocksOnSensorFlag() {
        val nowTs = ts("2026-03-09", 12, 0)
        val result = fitUniformWeekdayWeekend(nowTs)

        val acutePrior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 6.8,
            telemetry = mapOf("delta5_mmol" to 0.36),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            config = testConfig(useWeekendSplit = true)
        )
        val stalePrior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 6.8,
            telemetry = mapOf("sensor_quality_suspect_false_low" to 1.0),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            config = testConfig(useWeekendSplit = true)
        )

        assertThat(acutePrior).isNotNull()
        assertThat(acutePrior!!.acuteAttenuation).isWithin(0.001).of(0.4)
        assertThat(acutePrior.staleBlocked).isFalse()
        assertThat(stalePrior).isNotNull()
        assertThat(stalePrior!!.staleBlocked).isTrue()
    }

    @Test
    fun resolvePrior_softensDormantUamWithoutCurrentRise() {
        val nowTs = ts("2026-03-09", 12, 0)
        val result = fitUniformWeekdayWeekend(nowTs)

        val dormantUamPrior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 6.8,
            telemetry = mapOf(
                "delta5_mmol" to 0.0,
                "iob_units" to 0.2,
                "cob_grams" to 0.0,
                "uam_value" to 1.0,
                "uam_runtime_flag" to 1.0,
                "uam_runtime_confidence" to 0.12,
                "uam_inferred_confidence" to 0.12,
                "uam_inferred_gabs_last5_g" to 0.1,
                "uam_uci0_mmol5" to 0.0
            ),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            config = testConfig(useWeekendSplit = true)
        )

        assertThat(dormantUamPrior).isNotNull()
        assertThat(dormantUamPrior!!.acuteAttenuation).isWithin(0.001).of(0.7)
    }

    @Test
    fun resolvePrior_keepsDormantUamSoftWhenDeltaIsBelowPrimaryAcuteGate() {
        val nowTs = ts("2026-03-09", 12, 0)
        val result = fitUniformWeekdayWeekend(nowTs)

        val prior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 6.8,
            telemetry = mapOf(
                "delta5_mmol" to -0.27,
                "iob_units" to 0.0,
                "cob_grams" to 0.0,
                "uam_value" to 1.0,
                "uam_runtime_flag" to 1.0,
                "uam_runtime_confidence" to 0.24,
                "uam_inferred_confidence" to 0.24,
                "uam_inferred_gabs_last5_g" to 0.0,
                "uam_uci0_mmol5" to 0.0
            ),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            config = testConfig(useWeekendSplit = true)
        )

        assertThat(prior).isNotNull()
        assertThat(prior!!.acuteAttenuation).isWithin(0.001).of(0.7)
    }

    @Test
    fun resolvePrior_attachesReplayAwareBiasAndStatus() {
        val nowTs = ts("2026-03-09", 12, 0)
        val result = fitUniformWeekdayWeekend(nowTs)
        val replayStats = listOf(
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 48,
                horizonMinutes = 30,
                sampleCount = 12,
                coverageDays = 4,
                maeBaseline = 1.0,
                maeCircadian = 0.90,
                maeImprovementMmol = -0.10,
                medianSignedErrorBaseline = 0.18,
                medianSignedErrorCircadian = 0.06,
                winRate = 0.62,
                qualityScore = 0.84,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 48,
                horizonMinutes = 60,
                sampleCount = 12,
                coverageDays = 4,
                maeBaseline = 1.3,
                maeCircadian = 1.16,
                maeImprovementMmol = -0.14,
                medianSignedErrorBaseline = 0.22,
                medianSignedErrorCircadian = 0.10,
                winRate = 0.60,
                qualityScore = 0.81,
                updatedAt = nowTs
            )
        )

        val prior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 6.8,
            telemetry = emptyMap(),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            replayStats = replayStats,
            config = testConfig(useWeekendSplit = true)
        )

        assertThat(prior).isNotNull()
        assertThat(prior!!.replaySampleCount30).isEqualTo(12)
        assertThat(prior.replaySampleCount60).isEqualTo(12)
        assertThat(prior.replayBucketStatus30).isEqualTo(CircadianReplayBucketStatus.HELPFUL)
        assertThat(prior.replayBucketStatus60).isEqualTo(CircadianReplayBucketStatus.HELPFUL)
        assertThat(prior.replayBias30).isWithin(0.001).of(0.06)
        assertThat(prior.replayBias60).isWithin(0.001).of(0.10)
    }

    @Test
    fun resolvePrior_usesNeighbourReplayBucketsWhenExactSlotIsMissing() {
        val nowTs = ts("2026-03-09", 12, 0)
        val result = fitUniformWeekdayWeekend(nowTs)
        val replayStats = listOf(
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 47,
                horizonMinutes = 30,
                sampleCount = 6,
                coverageDays = 4,
                maeBaseline = 1.0,
                maeCircadian = 0.90,
                maeImprovementMmol = -0.10,
                medianSignedErrorBaseline = 0.18,
                medianSignedErrorCircadian = 0.07,
                winRate = 0.60,
                qualityScore = 0.82,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 49,
                horizonMinutes = 30,
                sampleCount = 5,
                coverageDays = 4,
                maeBaseline = 1.1,
                maeCircadian = 0.96,
                maeImprovementMmol = -0.14,
                medianSignedErrorBaseline = 0.20,
                medianSignedErrorCircadian = 0.09,
                winRate = 0.58,
                qualityScore = 0.80,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 47,
                horizonMinutes = 60,
                sampleCount = 6,
                coverageDays = 4,
                maeBaseline = 1.4,
                maeCircadian = 1.22,
                maeImprovementMmol = -0.18,
                medianSignedErrorBaseline = 0.24,
                medianSignedErrorCircadian = 0.10,
                winRate = 0.61,
                qualityScore = 0.83,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 49,
                horizonMinutes = 60,
                sampleCount = 5,
                coverageDays = 4,
                maeBaseline = 1.5,
                maeCircadian = 1.29,
                maeImprovementMmol = -0.21,
                medianSignedErrorBaseline = 0.26,
                medianSignedErrorCircadian = 0.11,
                winRate = 0.59,
                qualityScore = 0.81,
                updatedAt = nowTs
            )
        )

        val prior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 6.8,
            telemetry = emptyMap(),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            replayStats = replayStats,
            config = testConfig(useWeekendSplit = true)
        )

        assertThat(prior).isNotNull()
        assertThat(prior!!.replaySampleCount30).isEqualTo(11)
        assertThat(prior.replaySampleCount60).isEqualTo(11)
        assertThat(prior.replayBucketStatus30).isEqualTo(CircadianReplayBucketStatus.HELPFUL)
        assertThat(prior.replayBucketStatus60).isEqualTo(CircadianReplayBucketStatus.HELPFUL)
    }

    @Test
    fun resolvePrior_prefersHelpfulNeighbourhoodWhenExactSlotIsHarmful() {
        val nowTs = ts("2026-03-09", 12, 0)
        val result = fitUniformWeekdayWeekend(nowTs)
        val replayStats = listOf(
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 48,
                horizonMinutes = 30,
                sampleCount = 5,
                coverageDays = 3,
                maeBaseline = 0.9,
                maeCircadian = 1.02,
                maeImprovementMmol = 0.12,
                medianSignedErrorBaseline = 0.12,
                medianSignedErrorCircadian = 0.18,
                winRate = 0.30,
                qualityScore = 0.70,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 47,
                horizonMinutes = 30,
                sampleCount = 6,
                coverageDays = 4,
                maeBaseline = 1.0,
                maeCircadian = 0.90,
                maeImprovementMmol = -0.10,
                medianSignedErrorBaseline = 0.18,
                medianSignedErrorCircadian = 0.06,
                winRate = 0.62,
                qualityScore = 0.84,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 49,
                horizonMinutes = 30,
                sampleCount = 6,
                coverageDays = 4,
                maeBaseline = 1.0,
                maeCircadian = 0.91,
                maeImprovementMmol = -0.09,
                medianSignedErrorBaseline = 0.17,
                medianSignedErrorCircadian = 0.05,
                winRate = 0.60,
                qualityScore = 0.83,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 48,
                horizonMinutes = 60,
                sampleCount = 5,
                coverageDays = 3,
                maeBaseline = 1.2,
                maeCircadian = 1.30,
                maeImprovementMmol = 0.10,
                medianSignedErrorBaseline = 0.16,
                medianSignedErrorCircadian = 0.21,
                winRate = 0.32,
                qualityScore = 0.71,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 47,
                horizonMinutes = 60,
                sampleCount = 6,
                coverageDays = 4,
                maeBaseline = 1.3,
                maeCircadian = 1.16,
                maeImprovementMmol = -0.14,
                medianSignedErrorBaseline = 0.22,
                medianSignedErrorCircadian = 0.10,
                winRate = 0.60,
                qualityScore = 0.81,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 49,
                horizonMinutes = 60,
                sampleCount = 6,
                coverageDays = 4,
                maeBaseline = 1.3,
                maeCircadian = 1.17,
                maeImprovementMmol = -0.13,
                medianSignedErrorBaseline = 0.21,
                medianSignedErrorCircadian = 0.09,
                winRate = 0.58,
                qualityScore = 0.82,
                updatedAt = nowTs
            )
        )

        val prior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 6.8,
            telemetry = emptyMap(),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            replayStats = replayStats,
            config = testConfig(useWeekendSplit = true)
        )

        assertThat(prior).isNotNull()
        assertThat(prior!!.replayBucketStatus30).isEqualTo(CircadianReplayBucketStatus.HELPFUL)
        assertThat(prior.replayBucketStatus60).isEqualTo(CircadianReplayBucketStatus.HELPFUL)
        assertThat(prior.replaySampleCount30).isEqualTo(12)
        assertThat(prior.replaySampleCount60).isEqualTo(12)
        assertThat(prior.replayBias30).isLessThan(0.10)
        assertThat(prior.replayBias60).isLessThan(0.15)
    }

    @Test
    fun resolvePrior_addsMedianReversionWhenCurrentGlucoseIsOutsideTypicalBand() {
        val nowTs = ts("2026-03-09", 12, 0)
        val result = fitUniformWeekdayWeekend(nowTs)
        val replayStats = listOf(
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 48,
                horizonMinutes = 30,
                sampleCount = 14,
                coverageDays = 4,
                maeBaseline = 1.1,
                maeCircadian = 0.95,
                maeImprovementMmol = -0.15,
                medianSignedErrorBaseline = 0.22,
                medianSignedErrorCircadian = 0.05,
                winRate = 0.64,
                qualityScore = 0.86,
                updatedAt = nowTs
            ),
            CircadianReplaySlotStat(
                dayType = CircadianDayType.WEEKDAY,
                windowDays = 14,
                slotIndex = 48,
                horizonMinutes = 60,
                sampleCount = 14,
                coverageDays = 4,
                maeBaseline = 1.4,
                maeCircadian = 1.18,
                maeImprovementMmol = -0.22,
                medianSignedErrorBaseline = 0.30,
                medianSignedErrorCircadian = 0.08,
                winRate = 0.66,
                qualityScore = 0.88,
                updatedAt = nowTs
            )
        )

        val prior = engine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = 9.6,
            telemetry = emptyMap(),
            snapshots = result.snapshots,
            slotStats = result.slotStats,
            transitionStats = result.transitionStats,
            replayStats = replayStats,
            config = testConfig(useWeekendSplit = true)
        )

        assertThat(prior).isNotNull()
        assertThat(prior!!.medianReversion30).isLessThan(0.0)
        assertThat(prior.medianReversion60).isLessThan(prior.medianReversion30)
        assertThat(prior.stabilityScore).isAtLeast(0.35)
        assertThat(prior.horizonQuality60).isGreaterThan(0.0)
    }

    private fun fitUniformWeekdayWeekend(nowTs: Long) = engine.fit(
        glucoseHistory = buildGlucoseHistory(
            endDate = LocalDate.parse("2026-03-09"),
            lookbackDays = 14
        ) { _, minuteOfDay -> 6.1 + (minuteOfDay / 1440.0) * 0.3 },
        telemetryHistory = emptyList(),
        forecastHistory = emptyList(),
        nowTs = nowTs,
        config = testConfig(useWeekendSplit = true)
    )

    private fun testConfig(
        useWeekendSplit: Boolean = true,
        minCoverageRatioAll: Double = 0.35
    ): CircadianPatternConfig {
        return CircadianPatternConfig(
            baseTargetMmol = 5.5,
            stableWindowsDays = listOf(14, 10, 7),
            recencyWindowDays = 5,
            minSlotSamples = 2,
            minActiveDaysWeekday = 3,
            minActiveDaysWeekend = 2,
            minActiveDaysAll = 4,
            minCoverageRatioWeekday = 0.35,
            minCoverageRatioWeekend = 0.35,
            minCoverageRatioAll = minCoverageRatioAll,
            useWeekendSplit = useWeekendSplit,
            minDayCoverageQuality = 0.20,
            minDayObservedShare = 0.20
        )
    }

    private fun buildGlucoseHistory(
        endDate: LocalDate,
        lookbackDays: Int,
        valueForPoint: (LocalDate, Int) -> Double?
    ): List<GlucosePoint> {
        val startDate = endDate.minusDays(lookbackDays.toLong() - 1L)
        val out = mutableListOf<GlucosePoint>()
        var current = startDate
        while (!current.isAfter(endDate)) {
            var minute = 0
            while (minute < 24 * 60) {
                val value = valueForPoint(current, minute)
                if (value != null) {
                    out += GlucosePoint(
                        ts = current.atStartOfDay(zoneId).plusMinutes(minute.toLong()).toInstant().toEpochMilli(),
                        valueMmol = value,
                        source = "test",
                        quality = DataQuality.OK
                    )
                }
                minute += 15
            }
            current = current.plusDays(1)
        }
        return out
    }

    private fun ts(date: String, hour: Int, minute: Int): Long {
        return LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}
