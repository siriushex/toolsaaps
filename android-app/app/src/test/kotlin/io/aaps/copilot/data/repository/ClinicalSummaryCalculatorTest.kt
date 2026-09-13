package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.ClinicalTherapyProjection
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalSummaryCalculatorTest {

    @Test
    fun oneDaySummaryUsesAapsInsulinButCanonicalCarbohydrateTotals() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val through = from + DAY_MS

        val summary = ClinicalSummaryCalculator.calculate(
            days = 1,
            fromTs = from,
            throughTs = through,
            zoneId = ZoneId.of("UTC"),
            glucose = emptyList(),
            therapy = listOf(
                ClinicalTherapyPoint(from + MINUTE_MS, insulinU = 3.0, carbsG = 20.0),
                ClinicalTherapyPoint(
                    from + 2 * MINUTE_MS,
                    carbsG = 15.0,
                    syntheticUam = true
                )
            ),
            targets = emptyList(),
            authoritativeTdd = ClinicalAapsTddPeriod(
                fromTs = from,
                throughTs = through,
                basalInsulinU = 24.0,
                bolusInsulinU = 18.0,
                totalInsulinU = 42.0,
                carbsG = 99.0
            )
        )

        assertThat(summary.days).isEqualTo(1)
        assertThat(summary.totalInsulinU).isEqualTo(42.0)
        assertThat(summary.deliveredBasalInsulinU).isEqualTo(24.0)
        assertThat(summary.deliveredBolusInsulinU).isEqualTo(18.0)
        assertThat(summary.totalCarbsG).isEqualTo(35.0)
        assertThat(summary.enteredCarbsG).isEqualTo(20.0)
        assertThat(summary.uamCarbsG).isEqualTo(15.0)
        assertThat(summary.aapsCarbsG).isEqualTo(99.0)
        assertThat(summary.carbohydrateEnergyKcal).isEqualTo(140.0)
    }

    @Test
    fun insulinTotalKeepsAllSignalsWhilePreservingEvidenceBreakdown() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val inferred = requireNotNull(
            ClinicalReportDatasetBuilder.sanitizeTherapy(
                ClinicalTherapyProjection(
                    ts = from + MINUTE_MS,
                    type = "correction_bolus",
                    payloadJson =
                        """{"insulin":2.4,"classification":"INFERRED_IOB","method":"iob_jump"}"""
                )
            )
        )
        val recovered = requireNotNull(
            ClinicalReportDatasetBuilder.sanitizeTherapy(
                ClinicalTherapyProjection(
                    ts = from + 2 * MINUTE_MS,
                    type = "correction_bolus",
                    payloadJson =
                        """{"insulin":0.2,"classification":"REAL_FETCHED","method":"iob_micro_jump"}"""
                )
            )
        )
        val confirmed = requireNotNull(
            ClinicalReportDatasetBuilder.sanitizeTherapy(
                ClinicalTherapyProjection(
                    ts = from + 3 * MINUTE_MS,
                    type = "correction_bolus",
                    payloadJson =
                        """{"insulin":1.1,"classification":"REAL_FETCHED","source":"nightscout_treatment"}"""
                )
            )
        )

        val summary = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS,
            therapy = listOf(inferred, recovered, confirmed)
        )

        assertThat(summary.totalInsulinU).isWithin(0.001).of(3.7)
        val json = Gson().toJson(summary)
        assertThat(json).contains("\"confirmedInsulinU\":1.1")
        assertThat(json).contains("\"estimatedInsulinU\":2.6")
    }

    @Test
    fun carbohydrateTotalIncludesEnteredAndSyntheticUamWithBreakdown() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val summary = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS,
            therapy = listOf(
                ClinicalTherapyPoint(from + MINUTE_MS, carbsG = 20.0),
                ClinicalTherapyPoint(
                    from + 2 * MINUTE_MS,
                    carbsG = 15.0,
                    syntheticUam = true
                )
            )
        )

        assertThat(summary.totalCarbsG).isWithin(0.001).of(35.0)
        val json = Gson().toJson(summary)
        assertThat(json).contains("\"enteredCarbsG\":20.0")
        assertThat(json).contains("\"uamCarbsG\":15.0")
    }

    @Test
    fun authoritativeAapsTddSuppliesAllBasalBolusAndTreatmentCarbsWithoutDoubleCounting() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val through = from + SEVEN_DAYS_MS
        val summary = calculate(
            from = from,
            through = through,
            therapy = listOf(
                ClinicalTherapyPoint(from + MINUTE_MS, insulinU = 3.0),
                ClinicalTherapyPoint(from + MINUTE_MS, carbsG = 20.0),
                ClinicalTherapyPoint(
                    from + 2 * MINUTE_MS,
                    carbsG = 15.0,
                    syntheticUam = true
                )
            ),
            authoritativeTdd = ClinicalAapsTddPeriod(
                fromTs = from,
                throughTs = through,
                basalInsulinU = 70.0,
                bolusInsulinU = 35.0,
                totalInsulinU = 105.0,
                carbsG = 35.0
            )
        )

        assertThat(summary.totalInsulinU).isEqualTo(105.0)
        assertThat(summary.deliveredBasalInsulinU).isEqualTo(70.0)
        assertThat(summary.deliveredBolusInsulinU).isEqualTo(35.0)
        assertThat(summary.totalCarbsG).isEqualTo(35.0)
        assertThat(summary.enteredCarbsG).isEqualTo(20.0)
        assertThat(summary.uamCarbsG).isEqualTo(15.0)
        assertThat(summary.insulinTotalSource).isEqualTo(ClinicalInsulinTotalSource.AAPS_RAW_HISTORY)
    }

    @Test
    fun authoritativeAapsTddCarbsStayReconciliationOnlyWhenLocalHistoryIsIncomplete() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val through = from + SEVEN_DAYS_MS

        val summary = calculate(
            from = from,
            through = through,
            therapy = listOf(ClinicalTherapyPoint(from + MINUTE_MS, carbsG = 20.0)),
            authoritativeTdd = ClinicalAapsTddPeriod(
                fromTs = from,
                throughTs = through,
                basalInsulinU = 70.0,
                bolusInsulinU = 35.0,
                totalInsulinU = 105.0,
                carbsG = 90.0
            )
        )

        assertThat(summary.totalInsulinU).isEqualTo(105.0)
        assertThat(summary.totalCarbsG).isEqualTo(20.0)
        assertThat(summary.aapsCarbsG).isEqualTo(90.0)
    }

    @Test
    fun activityUsesOnlyPhysicalSourcesAndAggregatesDailyCumulativeValues() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val through = from + SEVEN_DAYS_MS
        val telemetry = listOf(
            ClinicalTelemetryPoint(
                from + MINUTE_MS,
                "steps_count",
                100.0,
                "OK",
                ClinicalTelemetryOrigin.LOCAL_ACTIVITY
            ),
            ClinicalTelemetryPoint(
                from + 12 * 60 * MINUTE_MS,
                "steps_count",
                4_000.0,
                "OK",
                ClinicalTelemetryOrigin.LOCAL_ACTIVITY
            ),
            ClinicalTelemetryPoint(
                from + DAY_MS + 12 * 60 * MINUTE_MS,
                "steps_count",
                5_000.0,
                "OK",
                ClinicalTelemetryOrigin.HEALTH_CONNECT
            ),
            ClinicalTelemetryPoint(
                from + MINUTE_MS,
                "activity_ratio",
                1.2,
                "OK",
                ClinicalTelemetryOrigin.LOCAL_ACTIVITY
            ),
            ClinicalTelemetryPoint(
                from + MINUTE_MS,
                "activity_ratio",
                2.8,
                "OK",
                ClinicalTelemetryOrigin.AAPS
            ),
            ClinicalTelemetryPoint(
                from + 2 * MINUTE_MS,
                "steps_count",
                99_999.0,
                "STALE",
                ClinicalTelemetryOrigin.HEALTH_CONNECT
            ),
            ClinicalTelemetryPoint(
                from + 2 * MINUTE_MS,
                "activity_ratio",
                2.9,
                "UNKNOWN",
                ClinicalTelemetryOrigin.LOCAL_ACTIVITY
            )
        )

        val summary = calculate(
            from = from,
            through = through,
            telemetry = telemetry
        )

        assertThat(summary.activity.steps).isEqualTo(9_000.0)
        assertThat(summary.activity.meanActivityRatio).isEqualTo(1.2)
        assertThat(summary.activity.maxActivityRatio).isEqualTo(1.2)
    }

    @Test
    fun distinctEventsMustBeDeduplicatedByInternalIdBeforeSummaryCalculation() {
        val from = 1_700_000_000_000L
        val event = ClinicalTherapyPoint(
            ts = from + 60_000L,
            insulinU = 2.0,
            carbsG = 20.0
        )

        val summary = ClinicalSummaryCalculator.calculate(
            days = 7,
            fromTs = from,
            throughTs = from + SEVEN_DAYS_MS,
            zoneId = ZoneId.of("UTC"),
            glucose = emptyList(),
            therapy = listOf(event, event),
            targets = emptyList()
        )

        assertThat(summary.totalInsulinU).isEqualTo(4.0)
        assertThat(summary.totalCarbsG).isEqualTo(40.0)
    }

    @Test
    fun calculatesAllCarbsAndPreservesEnteredVersusUamBreakdown() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val through = from + SEVEN_DAYS_MS
        val summary = calculate(
            from = from,
            through = through,
            glucose = listOf(
                glucose(from, 10, 3.8),
                glucose(from, 15, 5.8),
                glucose(from, 20, 6.2),
                glucose(from, 25, 10.5)
            ),
            therapy = listOf(
                ClinicalTherapyPoint(from, insulinU = 2.0),
                ClinicalTherapyPoint(from, carbsG = 20.0),
                ClinicalTherapyPoint(from, carbsG = 15.0, syntheticUam = true),
                ClinicalTherapyPoint(from, carbsG = 12.0, syntheticUam = true)
            ),
            targets = listOf(ClinicalTargetPoint(from, 5.8))
        )

        assertThat(summary.timeBelow4Pct).isWithin(0.001).of(25.0)
        assertThat(summary.timeInRangePct).isWithin(0.001).of(50.0)
        assertThat(summary.timeAboveRangePct).isWithin(0.001).of(25.0)
        assertThat(summary.totalInsulinU).isWithin(0.001).of(2.0)
        assertThat(summary.totalCarbsG).isWithin(0.001).of(47.0)
        assertThat(summary.enteredCarbsG).isWithin(0.001).of(20.0)
        assertThat(summary.uamCarbsG).isWithin(0.001).of(27.0)
        assertThat(summary.carbohydrateEnergyKcal).isWithin(0.001).of(188.0)
        assertThat(summary.meanTargetMmol).isWithin(0.001).of(5.8)
    }

    @Test
    fun irregularCadenceWeightsRangesBySupportedDurationInsteadOfPointCount() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val summary = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS,
            glucose = listOf(
                glucose(from, 10, 3.5),
                glucose(from, 15, 6.0),
                glucose(from, 25, 11.0)
            )
        )

        assertThat(summary.timeBelow4Pct).isWithin(0.001).of(25.0)
        assertThat(summary.timeInRangePct).isWithin(0.001).of(37.5)
        assertThat(summary.timeAboveRangePct).isWithin(0.001).of(37.5)
        assertThat(summary.coveragePct).isWithin(0.000_001)
            .of(20.0 / SEVEN_DAYS_MINUTES * 100.0)
    }

    @Test
    fun partialWindowEdgesBoundSupportAndLongGapIsNotCounted() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val through = from + SEVEN_DAYS_MS
        val summary = calculate(
            from = from,
            through = through,
            glucose = listOf(
                glucose(from, 1, 6.0),
                ClinicalGlucosePoint(through - MINUTE_MS, 6.0)
            )
        )

        assertThat(summary.timeInRangePct).isWithin(0.001).of(100.0)
        assertThat(summary.coveragePct).isWithin(0.000_001)
            .of(7.0 / SEVEN_DAYS_MINUTES * 100.0)
        assertThat(summary.quality.maxGapMinutes).isEqualTo(SEVEN_DAYS_MINUTES.toInt() - 2)
    }

    @Test
    fun canonicalRowsRetainActualTimestampForHourlyPattern() {
        val zone = ZoneId.of("Asia/Tbilisi")
        val from = ZonedDateTime.of(2026, 7, 24, 23, 58, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val summary = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS,
            zoneId = zone,
            glucose = listOf(
                ClinicalGlucosePoint(from + 3 * MINUTE_MS, 5.0),
                ClinicalGlucosePoint(from + 4 * MINUTE_MS, 7.0),
                ClinicalGlucosePoint(from + 61 * MINUTE_MS, 7.0),
                ClinicalGlucosePoint(from + 63 * MINUTE_MS, 9.0),
                ClinicalGlucosePoint(from + 64 * MINUTE_MS, Double.NaN)
            )
        )

        assertThat(summary.meanMmol).isWithin(0.001).of(7.0)
        assertThat(summary.medianMmol).isWithin(0.001).of(7.0)
        assertThat(summary.quality.coveredBuckets).isEqualTo(4)
        assertThat(summary.weekdayPattern.sumOf { it.sampleCount }).isEqualTo(0)
        assertThat(summary.weekendPattern[0].sampleCount).isEqualTo(3)
        assertThat(summary.weekendPattern[0].meanMmol).isWithin(0.001).of(6.333333)
        assertThat(summary.weekendPattern[1].sampleCount).isEqualTo(1)
    }

    @Test
    fun onePointInThirtyDaysHasBoundedCoverageAndEdgeAwareMaxGap() {
        val from = utcMillis(2026, 6, 1, 0, 0)
        val through = from + THIRTY_DAYS_MS
        val summary = calculate(
            days = 30,
            from = from,
            through = through,
            glucose = listOf(ClinicalGlucosePoint(from + THIRTY_DAYS_MS / 2, 6.0))
        )

        assertThat(summary.coveragePct).isWithin(0.000_001)
            .of(5.0 / THIRTY_DAYS_MINUTES * 100.0)
        assertThat(summary.timeInRangePct).isWithin(0.001).of(100.0)
        assertThat(summary.quality.maxGapMinutes).isEqualTo((THIRTY_DAYS_MINUTES / 2).toInt())
    }

    @Test
    fun emptyWindowReportsZeroCoverageNullGlucoseMetricsAndFullWindowGap() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val summary = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS,
            glucose = emptyList()
        )

        assertThat(summary.coveragePct).isEqualTo(0.0)
        assertThat(summary.meanMmol).isNull()
        assertThat(summary.medianMmol).isNull()
        assertThat(summary.coefficientOfVariationPct).isNull()
        assertThat(summary.timeBelow4Pct).isNull()
        assertThat(summary.timeInRangePct).isNull()
        assertThat(summary.timeAboveRangePct).isNull()
        assertThat(summary.quality.maxGapMinutes).isEqualTo(SEVEN_DAYS_MINUTES.toInt())
    }

    @Test
    fun validatesSupportedDaysAndEpochWindowDurationWithOneSecondTolerance() {
        val from = utcMillis(2026, 7, 1, 0, 0)

        assertThrows(IllegalArgumentException::class.java) {
            calculate(days = 14, from = from, through = from + 14 * DAY_MS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculate(from = from, through = from + SEVEN_DAYS_MS + 1_001L)
        }

        val withinTolerance = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS - 1_000L
        )
        assertThat(withinTolerance.days).isEqualTo(7)
    }

    @Test
    fun populationCoefficientOfVariationDefinitionIsPreserved() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val summary = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS,
            glucose = listOf(
                glucose(from, 5, 5.0),
                glucose(from, 10, 7.0)
            )
        )

        assertThat(summary.coefficientOfVariationPct).isWithin(0.001).of(16.666666)
    }

    @Test
    fun exactFourAndTenMmolThresholdsAreBothInRange() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val summary = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS,
            glucose = listOf(
                glucose(from, 5, 4.0),
                glucose(from, 10, 10.0)
            )
        )

        assertThat(summary.timeBelow4Pct).isEqualTo(0.0)
        assertThat(summary.timeInRangePct).isEqualTo(100.0)
        assertThat(summary.timeAboveRangePct).isEqualTo(0.0)
    }

    @Test
    fun suppliedEmptyResolvedTargetIntervalsDoNotFallBackToSourceEvents() {
        val from = utcMillis(2026, 7, 1, 0, 0)
        val summary = ClinicalSummaryCalculator.calculate(
            days = 7,
            fromTs = from,
            throughTs = from + SEVEN_DAYS_MS,
            zoneId = ZoneId.of("UTC"),
            glucose = emptyList(),
            therapy = emptyList(),
            targets = listOf(ClinicalTargetPoint(from, 6.0)),
            targetIntervals = emptyList()
        )

        assertThat(summary.meanTargetMmol).isNull()
    }

    @Test
    fun dstFallbackSamplesUseBothInstantsInTheRepeatedLocalWeekendHour() {
        val zone = ZoneId.of("America/New_York")
        val overlap = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, zone)
        val firstOccurrence = overlap.withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val secondOccurrence = overlap.withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val from = firstOccurrence - DAY_MS
        val summary = calculate(
            from = from,
            through = from + SEVEN_DAYS_MS,
            zoneId = zone,
            glucose = listOf(
                ClinicalGlucosePoint(firstOccurrence, 5.0),
                ClinicalGlucosePoint(secondOccurrence, 7.0)
            )
        )

        assertThat(secondOccurrence - firstOccurrence).isEqualTo(60 * MINUTE_MS)
        assertThat(summary.weekdayPattern.sumOf { it.sampleCount }).isEqualTo(0)
        assertThat(summary.weekendPattern[1].sampleCount).isEqualTo(2)
        assertThat(summary.weekendPattern[1].meanMmol).isWithin(0.001).of(6.0)
    }

    private fun calculate(
        days: Int = 7,
        from: Long,
        through: Long,
        zoneId: ZoneId = ZoneId.of("UTC"),
        glucose: List<ClinicalGlucosePoint> = emptyList(),
        therapy: List<ClinicalTherapyPoint> = emptyList(),
        targets: List<ClinicalTargetPoint> = emptyList(),
        telemetry: List<ClinicalTelemetryPoint> = emptyList(),
        authoritativeTdd: ClinicalAapsTddPeriod? = null
    ): ClinicalPeriodSummary = ClinicalSummaryCalculator.calculate(
        days = days,
        fromTs = from,
        throughTs = through,
        zoneId = zoneId,
        glucose = glucose,
        therapy = therapy,
        targets = targets,
        telemetry = telemetry,
        authoritativeTdd = authoritativeTdd
    )

    private fun glucose(from: Long, minutes: Int, mmol: Double): ClinicalGlucosePoint =
        ClinicalGlucosePoint(from + minutes * MINUTE_MS, mmol)

    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("UTC"))
        .toInstant()
        .toEpochMilli()

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60L * MINUTE_MS
        const val SEVEN_DAYS_MS = 7L * DAY_MS
        const val THIRTY_DAYS_MS = 30L * DAY_MS
        const val SEVEN_DAYS_MINUTES = 7L * 24L * 60L
        const val THIRTY_DAYS_MINUTES = 30L * 24L * 60L
    }
}
